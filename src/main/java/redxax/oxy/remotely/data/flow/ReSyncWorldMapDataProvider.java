package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.flow.data.FlowJson;

import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.flow.player.PlayerFacetState;
import redxax.oxy.remotely.data.flow.world.WorldMapCoordinate;
import redxax.oxy.remotely.data.flow.world.WorldMapDrawing;
import redxax.oxy.remotely.data.flow.world.WorldMapSnapshot;
import redxax.oxy.remotely.data.player.model.PlayerAvatarUrl;
import restudio.rebase.minecraft.MinecraftPlayerLocation;
import restudio.rescreen.platform.Async;
import restudio.rebase.ui.worldmap.WorldMapChunkSnapshot;
import restudio.rebase.ui.worldmap.WorldMapDataProvider;
import restudio.rebase.ui.worldmap.WorldMapOverlay;
import restudio.rebase.ui.worldmap.WorldMapPoint;
import restudio.rebase.ui.worldmap.WorldMapWorldSnapshot;
import restudio.rescreen.util.Identifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class ReSyncWorldMapDataProvider implements WorldMapDataProvider, AutoCloseable {
    private static final String CHUNK_CHANNEL = "chunks";
    private static final int REQUEST_BATCH_MAGIC = 0x52435131;
    private static final int RESPONSE_BATCH_MAGIC = 0x52435031;
    private static final int MAX_BATCH_SIZE = 96;
    private static final int COLUMN_COUNT = 16 * 16;

    private final FlowManager manager;
    private final String serverId;
    private final Map<ChunkKey, WorldMapChunkSnapshot> chunkCache = BrowserSafeState.map();
    private final Map<ChunkKey, PendingChunk> pendingChunks = BrowserSafeState.map();
    private final Set<ChunkKey> queuedChunks = BrowserSafeState.set();
    private final Map<ReSyncFlowClient, ReSyncFlowClient.PluginChannelListener> listeners = BrowserSafeState.map();
    private final Map<ReSyncFlowClient, Consumer<String>> protocolListeners = BrowserSafeState.map();
    private volatile boolean closed;
    private volatile String selectedWorld = "world";
    private volatile Async<ReSyncFlowClient.ReadinessState> readinessWait;
    private volatile boolean readinessRetryUsed;

    public ReSyncWorldMapDataProvider(FlowManager manager, String serverId) {
        this.manager = manager;
        this.serverId = serverId == null ? "" : serverId.trim();
    }

    @Override
    public Async<WorldMapChunkSnapshot> chunk(String worldName, int chunkX, int chunkZ, int priority) {
        if (closed) {
            return Async.failed(new IllegalStateException("World Map Provider Closed"));
        }
        if (manager == null || serverId.isBlank() || worldName == null || worldName.isBlank()) {
            return Async.failed(new IllegalArgumentException("ReSync World Map Configuration Is Incomplete"));
        }
        String normalizedWorld = worldName.trim();
        selectedWorld = normalizedWorld;
        ChunkKey key = new ChunkKey(normalizedWorld, chunkX, chunkZ);
        WorldMapChunkSnapshot cached = chunkCache.get(key);
        if (cached != null) {
            return Async.completed(cached);
        }
        PendingChunk existing = pendingChunks.get(key);
        if (existing != null) {
            return existing.result;
        }
        PendingChunk pending = new PendingChunk(key, Math.max(0, Math.min(4, priority)), Async.pending());
        PendingChunk previous = pendingChunks.putIfAbsent(key, pending);
        if (previous != null) {
            return previous.result;
        }
        queuedChunks.add(key);
        pending.result.onCancel(() -> {
            pendingChunks.remove(key, pending);
            queuedChunks.remove(key);
        });
        bindClient();
        flushRequests();
        return pending.result;
    }

    @Override
    public Async<WorldMapWorldSnapshot> world(String worldName) {
        if (closed || manager == null || serverId.isBlank() || worldName == null || worldName.isBlank()) {
            return Async.completed(WorldMapWorldSnapshot.empty(worldName));
        }
        String requestedWorld = worldName.trim();
        return manager.requestWorldMapSnapshotAsync(serverId, requestedWorld, 0.0, 0.0, 2)
            .thenApply(snapshot -> convertWorldSnapshot(requestedWorld, snapshot));
    }

    @Override
    public Async<List<MinecraftPlayerLocation>> players(String worldName) {
        if (closed || manager == null || serverId.isBlank()) {
            return Async.completed(List.of());
        }
        List<MinecraftPlayerLocation> cached = playerLocations(worldName, manager.getOnlinePlayersForServer(serverId));
        if (!cached.isEmpty()) {
            manager.requestPlayerTrackingSnapshot(serverId);
            return Async.completed(cached);
        }
        return manager.requestOnlinePlayers(serverId).thenApply(players -> playerLocations(worldName, players));
    }

    @Override
    public Async<Identifier> head(MinecraftPlayerLocation player) {
        if (closed || manager == null || player == null) {
            return Async.completed(null);
        }
        String source = PlayerAvatarUrl.resolve(player.name(), player.uuid(), 64);
        if (source.isBlank()) {
            return Async.completed(null);
        }
        return Async.completed(manager.getApplicationHost().registerRemoteImage(source));
    }

    @Override
    public void releaseImage(Identifier image) {
        if (manager != null && image != null) {
            manager.getApplicationHost().releaseRemoteImage(image);
        }
    }

    @Override
    public void retry() {
        if (closed) {
            return;
        }
        Async<ReSyncFlowClient.ReadinessState> wait = readinessWait;
        queuedChunks.addAll(pendingChunks.keySet());
        if (wait != null && !wait.isDone()) {
            return;
        }
        readinessRetryUsed = false;
        bindClient();
        flushRequests();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Async<ReSyncFlowClient.ReadinessState> wait = readinessWait;
        readinessWait = null;
        if (wait != null && !wait.isDone()) {
            wait.cancel();
        }
        for (Map.Entry<ReSyncFlowClient, ReSyncFlowClient.PluginChannelListener> entry : listeners.entrySet()) {
            entry.getKey().removePluginChannelListener(CHUNK_CHANNEL, entry.getValue());
            Consumer<String> protocolListener = protocolListeners.remove(entry.getKey());
            entry.getKey().removeProtocolErrorListener(protocolListener);
        }
        listeners.clear();
        for (PendingChunk pending : pendingChunks.values()) {
            pending.result.fail(new IllegalStateException("World Map Provider Closed"));
        }
        pendingChunks.clear();
        queuedChunks.clear();
        chunkCache.clear();
    }

    private void bindClient() {
        if (closed || manager == null) {
            return;
        }
        ReSyncFlowClient client = manager.ensureReSyncFlowClient(serverId);
        if (client == null) {
            if (readinessRetryUsed) {
                failPending(new IllegalStateException("ReSync Unavailable"));
            } else {
                awaitReadiness();
            }
            return;
        }
        listeners.computeIfAbsent(client, current -> {
            ReSyncFlowClient.PluginChannelListener listener = new ReSyncFlowClient.PluginChannelListener() {
                @Override
                public void onData(String channelId, byte[] payload) {
                    decodeChunkBatch(payload);
                }

                @Override
                public void onAvailable(String channelId) {
                    flushRequests();
                }

                @Override
                public void onRemoved(String channelId) {
                    queuedChunks.addAll(pendingChunks.keySet());
                }
            };
            current.addPluginChannelListener(CHUNK_CHANNEL, listener);
            Consumer<String> protocolListener = message -> failPending(new IllegalStateException(
                message == null || message.isBlank() ? "ReSync Chunk Request Failed" : message));
            current.addProtocolErrorListener(protocolListener);
            protocolListeners.put(current, protocolListener);
            return listener;
        });
        client.subscribePluginChannel(CHUNK_CHANNEL);
    }

    private synchronized void flushRequests() {
        if (closed || pendingChunks.isEmpty()) {
            return;
        }
        ReSyncFlowClient client = manager == null ? null : manager.ensureReSyncFlowClient(serverId);
        if (client == null || !client.isPluginChannelAvailable(CHUNK_CHANNEL)) {
            return;
        }
        while (!queuedChunks.isEmpty()) {
            PendingChunk first = firstQueued();
            if (first == null) {
                return;
            }
            List<PendingChunk> batch = new ArrayList<>();
            for (ChunkKey key : List.copyOf(queuedChunks)) {
                PendingChunk pending = pendingChunks.get(key);
                if (pending == null || !pending.key.world.equals(first.key.world) || batch.size() >= MAX_BATCH_SIZE) {
                    continue;
                }
                batch.add(pending);
            }
            if (batch.isEmpty()) {
                return;
            }
            byte[] payload = encodeRequest(first.key.world, batch);
            if (!client.sendPluginData(CHUNK_CHANNEL, payload)) {
                return;
            }
            for (PendingChunk pending : batch) {
                queuedChunks.remove(pending.key);
            }
        }
    }

    private void awaitReadiness() {
        Async<ReSyncFlowClient.ReadinessState> existing = readinessWait;
        if (existing != null && !existing.isDone()) {
            return;
        }
        readinessRetryUsed = true;
        Async<ReSyncFlowClient.ReadinessState> wait;
        try {
            wait = manager.awaitFlowClientConnected(serverId, false);
        } catch (RuntimeException failure) {
            failPending(new IllegalStateException("ReSync Unavailable", failure));
            return;
        }
        if (wait == null) {
            failPending(new IllegalStateException("ReSync Unavailable"));
            return;
        }
        readinessWait = wait;
        wait.whenComplete((state, failure) -> {
            if (closed || readinessWait != wait) {
                return;
            }
            readinessWait = null;
            if (failure != null || state != ReSyncFlowClient.ReadinessState.READY) {
                failPending(new IllegalStateException("ReSync Unavailable"));
                return;
            }
            bindClient();
            flushRequests();
        });
    }

    private PendingChunk firstQueued() {
        for (ChunkKey key : queuedChunks) {
            PendingChunk pending = pendingChunks.get(key);
            if (pending != null) {
                return pending;
            }
            queuedChunks.remove(key);
        }
        return null;
    }

    private byte[] encodeRequest(String worldName, List<PendingChunk> requests) {
        byte[] worldBytes = worldName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 3 + worldBytes.length + requests.size() * Integer.BYTES * 3);
        buffer.putInt(REQUEST_BATCH_MAGIC);
        buffer.putInt(worldBytes.length);
        buffer.put(worldBytes);
        buffer.putInt(requests.size());
        for (PendingChunk pending : requests) {
            buffer.putInt(pending.key.chunkX);
            buffer.putInt(pending.key.chunkZ);
            buffer.putInt(pending.priority);
        }
        return buffer.array();
    }

    private void decodeChunkBatch(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            failPending(new IllegalStateException("Invalid ReSync Chunk Response"));
            return;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            if (buffer.getInt() != RESPONSE_BATCH_MAGIC) {
                failPending(new IllegalStateException("Invalid ReSync Chunk Response Marker"));
                return;
            }
            int count = buffer.getInt();
            if (count < 0 || count > MAX_BATCH_SIZE || buffer.remaining() < count * Integer.BYTES) {
                failPending(new IllegalStateException("Invalid ReSync Chunk Response Count"));
                return;
            }
            for (int index = 0; index < count; index++) {
                int length = buffer.getInt();
                if (length <= 0 || length > buffer.remaining()) {
                    failPending(new IllegalStateException("Invalid ReSync Chunk Response Length"));
                    return;
                }
                ByteBuffer chunkBuffer = buffer.slice();
                chunkBuffer.limit(length);
                buffer.position(buffer.position() + length);
                WorldMapChunkSnapshot chunk = decodeChunk(chunkBuffer);
                if (chunk != null) {
                    completeChunk(chunk);
                }
            }
        } catch (RuntimeException failure) {
            failPending(failure);
        }
    }

    private WorldMapChunkSnapshot decodeChunk(ByteBuffer buffer) {
        if (buffer.remaining() < Integer.BYTES * 2 + Short.BYTES) {
            throw new IllegalArgumentException("Invalid ReSync Chunk Data");
        }
        int chunkX = buffer.getInt();
        int chunkZ = buffer.getInt();
        int paletteSize = Short.toUnsignedInt(buffer.getShort());
        if (paletteSize == 0 || paletteSize > 4096) {
            throw new IllegalArgumentException("Invalid ReSync Chunk Palette");
        }
        String[] palette = new String[paletteSize];
        for (int index = 0; index < paletteSize; index++) {
            if (buffer.remaining() < Short.BYTES) {
                throw new IllegalArgumentException("Invalid ReSync Chunk Palette Length");
            }
            int length = Short.toUnsignedInt(buffer.getShort());
            if (length <= 0 || length > buffer.remaining()) {
                throw new IllegalArgumentException("Invalid ReSync Chunk Palette Entry");
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            palette[index] = new String(bytes, StandardCharsets.UTF_8);
        }
        if (buffer.remaining() < COLUMN_COUNT * Short.BYTES * 2) {
            throw new IllegalArgumentException("Invalid ReSync Chunk Columns");
        }
        String[] blockIds = new String[COLUMN_COUNT];
        int[] heights = new int[COLUMN_COUNT];
        for (int index = 0; index < COLUMN_COUNT; index++) {
            heights[index] = buffer.getShort();
            int paletteIndex = Short.toUnsignedInt(buffer.getShort());
            blockIds[index] = paletteIndex < palette.length ? palette[paletteIndex] : "minecraft:air";
        }
        return new WorldMapChunkSnapshot(chunkX, chunkZ, blockIds, heights, null, null, null);
    }

    private void completeChunk(WorldMapChunkSnapshot chunk) {
        ChunkKey key = findPendingKey(chunk.chunkX(), chunk.chunkZ());
        if (key == null) {
            return;
        }
        chunkCache.put(key, chunk);
        PendingChunk pending = pendingChunks.remove(key);
        queuedChunks.remove(key);
        if (pending != null) {
            pending.result.complete(chunk);
        }
    }

    private ChunkKey findPendingKey(int chunkX, int chunkZ) {
        ChunkKey preferred = new ChunkKey(selectedWorld, chunkX, chunkZ);
        if (pendingChunks.containsKey(preferred)) {
            return preferred;
        }
        for (ChunkKey key : pendingChunks.keySet()) {
            if (key.chunkX == chunkX && key.chunkZ == chunkZ) {
                return key;
            }
        }
        return null;
    }

    private void failPending(Throwable failure) {
        for (PendingChunk pending : pendingChunks.values()) {
            if (pendingChunks.remove(pending.key, pending)) {
                queuedChunks.remove(pending.key);
                pending.result.fail(failure);
            }
        }
    }

    private WorldMapWorldSnapshot convertWorldSnapshot(String worldName, WorldMapSnapshot snapshot) {
        if (snapshot == null) {
            return WorldMapWorldSnapshot.empty(worldName);
        }
        List<WorldMapOverlay> overlays = new ArrayList<>();
        for (WorldMapDrawing drawing : snapshot.getDrawings()) {
            if (drawing == null) {
                continue;
            }
            List<WorldMapPoint> points = new ArrayList<>();
            for (WorldMapCoordinate coordinate : drawing.getCoordinates()) {
                if (coordinate != null) {
                    points.add(new WorldMapPoint(coordinate.getX(), coordinate.getY(), coordinate.getZ()));
                }
            }
            Map<String, String> data = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : drawing.getData().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    data.put(entry.getKey(), FlowJson.text(entry.getValue()));
                }
            }
            overlays.add(new WorldMapOverlay(drawing.getDrawingId(), drawing.getLabel(), drawing.getKind(), drawing.getWorldName(), points, data));
        }
        return new WorldMapWorldSnapshot(worldName, overlays, snapshot.getGeneratedAt());
    }

    private List<MinecraftPlayerLocation> playerLocations(String worldName, List<PlayerDossier> dossiers) {
        if (worldName == null || worldName.isBlank() || dossiers == null || dossiers.isEmpty()) {
            return List.of();
        }
        List<MinecraftPlayerLocation> result = new ArrayList<>();
        for (PlayerDossier dossier : dossiers) {
            if (dossier == null || !dossier.isOnline()) {
                continue;
            }
            Map<String, Object> data = locationData(dossier);
            String dimension = text(data, "world", text(data, "dimension", ""));
            if (!dimension.isBlank() && !dimension.equalsIgnoreCase(worldName)) {
                continue;
            }
            if (data.isEmpty()) {
                continue;
            }
            UUID uuid = parseUuid(dossier.getPlayerId());
            result.add(new MinecraftPlayerLocation(uuid, dossier.getPlayerName(), number(data, "x"), number(data, "y"), number(data, "z"),
                dimension.isBlank() ? worldName : dimension, true));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> locationData(PlayerDossier dossier) {
        PlayerFacetState worldLocation = dossier.getFacets().get("worldLocation");
        if (worldLocation != null && !worldLocation.getData().isEmpty()) {
            return worldLocation.getData();
        }
        PlayerFacetState playerData = dossier.getFacets().get("playerData");
        if (playerData == null || playerData.getData().isEmpty()) {
            return Map.of();
        }
        Object location = playerData.getData().get("location");
        if (location instanceof Map<?, ?> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(FlowJson.text(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : FlowJson.text(value);
    }

    private double number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(FlowJson.text(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record ChunkKey(String world, int chunkX, int chunkZ) {
    }

    private record PendingChunk(ChunkKey key, int priority, Async<WorldMapChunkSnapshot> result) {
    }
}
