package redxax.oxy.remotely.quickserver;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.mixin.accessor.MinecraftAccessor;
import redxax.oxy.remotely.servers.QuickServerSyncManager;
import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.servers.JvmReProxyConnectorCapability;
import restudio.rebase.Rebase;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceFactory;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.instance.InstanceRepairer;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.localcontrol.LocalServerControllerClient;
import restudio.rebase.localcontrol.LocalServerControllerModels;
import restudio.rebase.resource.ResourceMetadata;
import restudio.rebase.resource.provider.IResourceProvider;
import restudio.rebase.resource.provider.OnlineResource;
import restudio.rebase.resource.provider.OnlineResourceVersion;
import restudio.rebase.util.RebaseLogger;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.FileUtils;
import restudio.rescreen.util.Notification;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static redxax.oxy.remotely.config.Config.quickServerAutoRestart;
import static redxax.oxy.remotely.config.Config.quickServerKeepRunning;
import static redxax.oxy.remotely.config.Config.quickServerMirrorMods;
import static redxax.oxy.remotely.config.Config.quickServerPrecreate;

public final class QuickServerManager {
    private static final Gson GSON = new Gson();
    private static final Pattern CLIENT_SIDE_ONLY_PATTERN = Pattern.compile("(?i)clientSideOnly\\s*=\\s*true");
    private static final String QUICK_SERVER_ENABLED_KEY = "quickServer.enabled";
    private static final String SOURCE_WORLD_PATH_KEY = "quickServer.sourceWorldPath";
    private static final String SOURCE_WORLD_ID_KEY = "quickServer.sourceWorldId";
    private static final String SOURCE_INSTANCE_PATH_KEY = "quickServer.sourceInstancePath";
    private static final String SOURCE_GAME_DIR_KEY = "quickServer.sourceGameDir";
    private static final String LAST_WORLD_SYNC_KEY = "quickServer.lastWorldSync";
    private static final String LAST_MOD_SYNC_KEY = "quickServer.lastModSync";
    private static final String LAST_SUPPORT_SYNC_KEY = "quickServer.lastSupportSync";
    private static final String SUPPORT_SYNC_DIRS_KEY = "quickServer.supportSyncDirs";
    private static final String SYNC_STATE_KEY = "quickServer.syncState";
    private static final String LAST_SERVER_DIRTY_KEY = "quickServer.lastServerDirty";
    private static final String SERVER_RUNTIME_SIGNATURE_KEY = "quickServer.serverRuntimeSignature";
    private static final String SYNC_STATE_CLEAN = "clean";
    private static final String SYNC_STATE_SERVER_DIRTY = "serverDirty";
    private static final String SYNC_STATE_SYNCING_BACK = "syncingBack";
    private static final String METADATA_FILE = "remotely-quick-server.properties";
    private static final String MOD_MANIFEST_FILE = "quick-server-mods.json";
    private static final Pattern GAME_VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?(?:-(?:pre|rc|snapshot)-\\d+)?");
    private static final Path QUICK_SERVERS_DIR = DesktopRemotelyPaths.appDir().resolve("instances").resolve("quick-servers");
    private static final Path QUICK_SERVER_CHECKED_UNKNOWN_HASHES_FILE = DesktopRemotelyPaths.appDir().resolve("quick-server-checked-unknown-hashes.json");
    private static final Set<String> WORLD_SYNC_EXCLUDES = Set.of("session.lock", "remotely-quick-server.properties");
    private static final Set<String> SUPPORT_SYNC_DIRS = Set.of("config", "defaultconfigs", "kubejs", "scripts", "datapacks", "openloader");
    private static final Set<String> CLIENT_ONLY_MOD_IDS = Set.of("remotely");
    private static final Set<String> BASE_DEPENDENCY_MOD_IDS = Set.of("minecraft", "neoforge", "forge", "fabricloader", "fabric_loader", "quilt_loader", "quiltloader");
    private static final Set<String> activeQuickServers = ConcurrentHashMap.newKeySet();
    private static final Set<String> activeDisconnectStops = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicBoolean> activeTasks = new ConcurrentHashMap<>();
    private static final AtomicBoolean ownerTransferInProgress = new AtomicBoolean(false);
    private static volatile String precreatedWorldId = "";
    private static volatile String activeQuickServerInstanceId = "";
    private static volatile String connectedQuickServerInstanceId = "";
    private static volatile IntegratedServer pendingOwnerTransferServer;
    private static volatile String pendingOwnerTransferAddress = "";
    private static volatile long pendingOwnerTransferDeadlineMillis = 0L;
    private static volatile String pendingOwnerConnectAddress = "";
    private static volatile long pendingOwnerConnectAfterMillis = 0L;
    private static final AtomicBoolean ownerConnectStarted = new AtomicBoolean(false);
    private static volatile long quickServerJoinCleanupUntilMillis = 0L;

    private QuickServerManager() {
    }

    public static void startForCurrentWorld(Screen parent) {
        startForCurrentWorld(parent, state -> {});
    }

    public static void startForCurrentWorld(Screen parent, Consumer<String> stateConsumer) {
        WorldContext world = currentWorld();
        if (world == null) {
            if (openCurrentWorldTerminal(parent)) {
                stateConsumer.accept("Online");
                return;
            }
            stateConsumer.accept("Unavailable");
            Notification notification = new Notification.Builder().message("Quick Server").description("Open A Singleplayer World First").type(Notification.Type.WARN).autoSlideOut(true).build();
            change(notification, "Quick Server Unavailable", "Open A Singleplayer World First", Notification.Type.WARN, null, false);
            return;
        }
        Instance mapped = findMappedInstance(world);
        if (mapped != null && isLocalServerRunning(mapped)) {
            mapped.setState(InstanceState.RUNNING);
            setActiveQuickServer(mapped);
            registerTerminalTab(mapped);
            stateConsumer.accept("Online");
            connectOwnerToLocalServer(mapped);
            return;
        }
        runForWorld(world, stateConsumer, true);
    }

    public static void precreateForCurrentWorld() {
        if (!quickServerPrecreate()) {
            return;
        }
        WorldContext world = currentWorld();
        if (world == null || world.worldId().equals(precreatedWorldId)) {
            return;
        }
        precreatedWorldId = world.worldId();
        runForWorld(world, state -> {}, false);
    }

    public static void clientTick() {
        precreateForCurrentWorld();
        advanceOwnerTransfer();
        advanceOwnerConnect();
        clearJoinHandoffScreen();
        handleOwnerDisconnect();
    }

    public static void shutdownAll() {
        for (String instanceId : activeQuickServers) {
            Instance instance = resolveInstance(instanceId);
            if (instance == null) {
                continue;
            }
            ReProxyManager.stopQuietly(JvmReProxyConnectorCapability.server(instance), null);
            if (!instance.isLocalLifecyclePersistent()) {
                stopLocalServerQuietly(instance);
                waitForStop(instance);
                syncServerWorldBack(instance);
            }
            activeQuickServers.remove(instanceId);
        }
    }

    public static boolean isQuickServerOnline() {
        Instance instance = resolveActiveQuickServer();
        return instance != null && isQuickServerManagedRunning(instance);
    }

    public static boolean copyCurrentWorldAddress() {
        Instance instance = resolveActiveQuickServer();
        if (instance == null || !isQuickServerManagedRunning(instance)) {
            return false;
        }
        registerTerminalTab(instance);
        copyAddress(quickServerAddress(instance));
        return true;
    }

    public static void activateGameButton(Screen parent) {
        if (currentWorld() != null) {
            startForCurrentWorld(parent);
            return;
        }
        if (openConnectedQuickServerTerminal(parent)) {
            return;
        }
        ScreenManager.getInstance().execute(() -> new Notification("Quick Server", "No Linked Server", Notification.Type.WARN));
    }

    public static boolean isInQuickServer() {
        return resolveConnectedQuickServer() != null;
    }

    public static boolean shouldShowGameButton() {
        return currentWorld() != null || resolveConnectedQuickServer() != null;
    }

    public static boolean openCurrentWorldTerminal(Object parent) {
        Instance instance = resolveActiveQuickServer();
        if (instance == null || !isQuickServerManagedRunning(instance)) {
            return false;
        }
        registerTerminalTab(instance);
        ScreenManager.getInstance().execute(() -> RemotelyClient.INSTANCE.openInstanceInTerminal(parent, instance));
        return true;
    }

    public static boolean openConnectedQuickServerTerminal(Object parent) {
        Instance instance = resolveConnectedQuickServer();
        if (instance == null) {
            return false;
        }
        refreshConnectedQuickServerState(instance);
        setActiveQuickServer(instance);
        registerTerminalTab(instance);
        ScreenManager.getInstance().execute(() -> RemotelyClient.INSTANCE.openInstanceInTerminal(parent, instance));
        return true;
    }

    public static void runForWorld(WorldContext world, Consumer<String> stateConsumer, boolean startServer) {
        AtomicBoolean running = activeTasks.computeIfAbsent(world.worldId(), id -> new AtomicBoolean(false));
        Notification notification = new Notification.Builder().message("Quick Server").description(world.worldName()).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        if (!running.compareAndSet(false, true)) {
            if (recoverRunningQuickServer(world, stateConsumer, notification)) {
                running.set(false);
                return;
            }
            stateConsumer.accept("Starting");
            change(notification, "Quick Server", "Already Working", Notification.Type.INFO, null, false);
            return;
        }
        stateConsumer.accept(startServer ? "Starting" : "Syncing");
        CompletableFuture.runAsync(() -> {
            try {
                change(notification, startServer ? "Starting Quick Server" : "Preparing Quick Server", world.worldName(), Notification.Type.INFO, null, true);
                Instance instance = getOrCreateInstance(world);
                boolean alreadyRunning = isLocalServerRunning(instance);
                boolean serverDirty = isServerDirty(instance);
                if (!alreadyRunning && serverDirty) {
                    change(notification, startServer ? "Recovering World" : "Recovery Pending", instance.getName(), Notification.Type.INFO, null, true);
                    resolveDirtyWorldState(world, instance, notification, startServer);
                    serverDirty = isServerDirty(instance);
                }
                if (!alreadyRunning) {
                    if (serverDirty && !startServer) {
                        change(notification, "Recovery Pending", "Start Server To Recover", Notification.Type.WARN, null, false);
                        return;
                    }
                    change(notification, "Syncing World", world.worldName(), Notification.Type.INFO, null, true);
                    syncWorldToServer(world, instance);
                    syncMods(instance, notification);
                    syncSupportFiles(instance, notification);
                    ensureDynamicServerPort(instance, notification);
                    prepareServer(instance, notification);
                }
                if (startServer) {
                    change(notification, "Starting Server", instance.getName(), Notification.Type.INFO, null, true);
                    markServerDirty(instance);
                    startLocalServer(instance, notification);
                    setActiveQuickServer(instance);
                    registerTerminalTab(instance);
                    stateConsumer.accept("Online");
                    change(notification, "Joining Server", "127.0.0.1:" + instance.getPort(), Notification.Type.INFO, null, true);
                    connectOwnerToLocalServer(instance);
                    if (ReProxyManager.isForwarded(JvmReProxyConnectorCapability.server(instance))) {
                        String address = quickServerAddress(instance);
                        copyAddress(address);
                        change(notification, "Quick Server Online", address, Notification.Type.SUCCESS, () -> copyAddress(address), false);
                    } else {
                        change(notification, "Quick Server Online", "Starting ReProxy", Notification.Type.INFO, null, false);
                        startReProxy(instance, () -> ScreenManager.getInstance().execute(() -> {
                            boolean forwarded = ReProxyManager.isForwarded(JvmReProxyConnectorCapability.server(instance));
                            stateConsumer.accept("Online");
                            String address = quickServerAddress(instance);
                            if (forwarded) {
                                copyAddress(address);
                                notification.change("Quick Server Online", address, Notification.Type.SUCCESS, () -> copyAddress(address));
                            } else {
                                notification.change("Quick Server Online", address, Notification.Type.SUCCESS, null);
                            }
                            notification.loading = false;
                        }));
                    }
                } else {
                    change(notification, "Quick Server Ready", world.worldName(), Notification.Type.SUCCESS, null, false);
                }
                instance.save().join();
            } catch (Exception e) {
                stateConsumer.accept("Unavailable");
                change(notification, "Quick Server Failed", cleanMessage(e), Notification.Type.ERROR, null, false);
            } finally {
                running.set(false);
            }
        });
    }

    private static boolean recoverRunningQuickServer(WorldContext world, Consumer<String> stateConsumer, Notification notification) {
        Instance instance = findMappedInstance(world);
        if (instance == null || !isQuickServer(instance) || !isLocalServerRunning(instance)) {
            return false;
        }
        instance.setState(InstanceState.RUNNING);
        setActiveQuickServer(instance);
        registerTerminalTab(instance);
        stateConsumer.accept("Online");
        String address = quickServerAddress(instance);
        copyAddress(address);
        change(notification, "Quick Server Online", address, Notification.Type.SUCCESS, () -> copyAddress(address), false);
        connectOwnerToLocalServer(instance);
        return true;
    }

    private static void handleOwnerDisconnect() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData serverData = minecraft.getCurrentServer();
        if (serverData != null && serverData.ip != null && !serverData.ip.isBlank()) {
            Instance connected = resolveConnectedQuickServer();
            if (connected != null && connected.getInstanceId() != null) {
                connectedQuickServerInstanceId = connected.getInstanceId();
            }
            return;
        }
        String instanceId = connectedQuickServerInstanceId;
        if (instanceId.isBlank() || ownerTransferInProgress.get() || !pendingOwnerConnectAddress.isBlank()) {
            return;
        }
        connectedQuickServerInstanceId = "";
        Instance instance = resolveInstance(instanceId);
        if (instance == null || instance.isLocalLifecyclePersistent() || !isLocalServerRunning(instance) || !activeDisconnectStops.add(instanceId)) {
            return;
        }
        new Thread(() -> {
            try {
                ReProxyManager.stopQuietly(JvmReProxyConnectorCapability.server(instance), null);
                stopLocalServerQuietly(instance);
                waitForStop(instance);
                syncServerWorldBack(instance);
                activeQuickServers.remove(instanceId);
            } finally {
                activeDisconnectStops.remove(instanceId);
            }
        }, "Remotely Quick Server Disconnect Stop").start();
    }

    private static void stopLocalServerQuietly(Instance instance) {
        try {
            LocalServerControllerClient.stop(instance);
        } catch (IOException e) {
            RebaseLogger.log("Could not stop quick server " + instance.getName() + ": " + e.getMessage());
        }
    }

    public static WorldContext currentWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        Path worldPath = resolveWorldPath(server);
        if (worldPath == null || !Files.isDirectory(worldPath)) {
            return null;
        }
        worldPath = worldPath.toAbsolutePath().normalize();
        String worldName = worldPath.getFileName() != null ? worldPath.getFileName().toString() : "World";
        String worldId = stableWorldId(worldPath);
        return new WorldContext(worldId, worldName, worldPath);
    }

    private static Instance getOrCreateInstance(WorldContext world) throws IOException {
        Instance existing = findMappedInstance(world);
        if (existing != null) {
            applyRuntimeDefaults(existing, world);
            existing.save().join();
            writeWorldMetadata(world, existing);
            return existing;
        }
        Path instancePath = QUICK_SERVERS_DIR.resolve(sanitizePathName(world.worldName()) + "-" + world.worldId()).normalize();
        Files.createDirectories(instancePath);
        Instance instance = new Instance(world.worldName() + " | Quick Server", resolveGameVersion(), instancePath.toString());
        instance.setServer(true);
        instance.setHidden(true);
        instance.setImported(false);
        applyRuntimeDefaults(instance, world);
        instance.getServerProperties().setProperty("level-name", "world");
        instance.getServerProperties().setProperty("server-port", String.valueOf(resolvePort()));
        instance.getServerProperties().setProperty("online-mode", "true");
        instance.getServerProperties().setProperty("enable-command-block", "true");
        instance.getServerProperties().setProperty("motd", "Remotely | Quick Server");
        instance.getServerProperties().setProperty("eula", "true");
        instance.saveServerProperties().join();
        instance.save().join();
        InstanceManager.getInstance().registerLocalInstance(instance);
        writeWorldMetadata(world, instance);
        return instance;
    }

    private static Instance findMappedInstance(WorldContext world) {
        try {
            Properties metadata = readWorldMetadata(world.worldPath());
            String instanceId = metadata.getProperty("instanceId", "");
            if (!instanceId.isBlank()) {
                Instance byId = InstanceManager.getInstance().getInstanceById(instanceId);
                if (byId != null) {
                    return byId;
                }
            }
            String instancePath = metadata.getProperty("instancePath", "");
            if (!instancePath.isBlank()) {
                Instance loaded = Instance.load(Path.of(instancePath));
                if (loaded != null) {
                    InstanceManager.getInstance().registerLocalInstance(loaded);
                    return loaded;
                }
            }
            for (Instance instance : InstanceManager.getInstance().getLocalInstances()) {
                if ("true".equalsIgnoreCase(instance.getSettings().getProperty(QUICK_SERVER_ENABLED_KEY))
                    && Objects.equals(world.worldId(), instance.getSettings().getProperty(SOURCE_WORLD_ID_KEY))) {
                    return instance;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Instance resolveActiveQuickServer() {
        WorldContext world = currentWorld();
        if (world != null) {
            Instance mapped = findMappedInstance(world);
            if (mapped != null) {
                setActiveQuickServer(mapped);
                return mapped;
            }
        }
        if (!activeQuickServerInstanceId.isBlank()) {
            Instance active = resolveInstance(activeQuickServerInstanceId);
            if (active != null) {
                return active;
            }
        }
        for (Instance instance : allKnownQuickServers()) {
            if (isQuickServerManagedRunning(instance)) {
                setActiveQuickServer(instance);
                return instance;
            }
        }
        return null;
    }

    private static Instance resolveConnectedQuickServer() {
        ServerData serverData = Minecraft.getInstance().getCurrentServer();
        if (serverData == null || serverData.ip == null || serverData.ip.isBlank()) {
            return null;
        }
        if (!activeQuickServerInstanceId.isBlank()) {
            Instance active = resolveInstance(activeQuickServerInstanceId);
            if (isConnectedQuickServer(serverData.ip, active)) {
                setActiveQuickServer(active);
                return active;
            }
        }
        for (Instance instance : allKnownQuickServers()) {
            if (isConnectedQuickServer(serverData.ip, instance)) {
                setActiveQuickServer(instance);
                return instance;
            }
        }
        return null;
    }

    private static List<Instance> allKnownQuickServers() {
        List<Instance> instances = new ArrayList<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        for (Instance instance : InstanceManager.getInstance().getLocalInstances()) {
            if (isQuickServer(instance) && seen.add(quickServerIdentity(instance))) {
                instances.add(instance);
            }
        }
        if (!Files.isDirectory(QUICK_SERVERS_DIR)) {
            return instances;
        }
        try (var paths = Files.list(QUICK_SERVERS_DIR)) {
            paths.filter(Files::isDirectory).forEach(path -> {
                try {
                    Instance instance = Instance.load(path);
                    if (isQuickServer(instance) && seen.add(quickServerIdentity(instance))) {
                        InstanceManager.getInstance().registerLocalInstance(instance);
                        instances.add(instance);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        return instances;
    }

    private static String quickServerIdentity(Instance instance) {
        if (instance == null) {
            return "";
        }
        if (instance.getInstanceId() != null && !instance.getInstanceId().isBlank()) {
            return instance.getInstanceId();
        }
        return instance.getPath() == null ? "" : instance.getPath();
    }

    private static boolean isQuickServer(Instance instance) {
        return instance != null && "true".equalsIgnoreCase(instance.getSettings().getProperty(QUICK_SERVER_ENABLED_KEY));
    }

    private static boolean isConnectedQuickServer(String address, Instance instance) {
        if (!isQuickServer(instance)) {
            return false;
        }
        String forwarded = ReProxyManager.getForwardedAddress(JvmReProxyConnectorCapability.server(instance));
        if (forwarded != null && !forwarded.isBlank() && normalizeAddress(forwarded).equals(normalizeAddress(address))) {
            return true;
        }
        ServerAddress serverAddress = ServerAddress.parseString(address);
        String host = normalizeHost(serverAddress.getHost());
        return serverAddress.getPort() == configuredPort(instance) && isLocalHost(host);
    }

    private static void refreshConnectedQuickServerState(Instance instance) {
        if (instance == null) {
            return;
        }
        LocalServerControllerModels.StatusResponse status = LocalServerControllerClient.status(instance);
        if (status != null && status.knownSession && status.pid > 0 && "RUNNING".equalsIgnoreCase(status.state)) {
            instance.setState(InstanceState.RUNNING);
            return;
        }
        if (isMinecraftServerReady(instance)) {
            instance.setState(InstanceState.RUNNING);
        }
    }

    private static String normalizeAddress(String address) {
        ServerAddress serverAddress = ServerAddress.parseString(address);
        return normalizeHost(serverAddress.getHost()) + ":" + serverAddress.getPort();
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLocalHost(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("0.0.0.0") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
    }

    private static void setActiveQuickServer(Instance instance) {
        if (instance != null && instance.getInstanceId() != null) {
            activeQuickServerInstanceId = instance.getInstanceId();
            activeQuickServers.add(instance.getInstanceId());
        }
    }

    private static void applyRuntimeDefaults(Instance instance, WorldContext world) {
        SourceRuntime source = resolveSourceRuntime(world);
        String oldServerType = instance.getServerSoftwareType();
        String oldVersion = instance.getVersionId();
        String oldLoaderVersion = instance.getModLoaderVersion();
        instance.setName(world.worldName() + " | Quick Server");
        instance.setVersionId(source.versionId());
        instance.setServerSoftwareType(source.loader().name());
        instance.setModLoader(source.loader());
        instance.setModLoaderVersion(source.loaderVersion());
        if (!Objects.equals(oldServerType, instance.getServerSoftwareType())
            || !Objects.equals(oldVersion, instance.getVersionId())
            || !Objects.equals(oldLoaderVersion, instance.getModLoaderVersion())) {
            instance.setServerBuildNumber(null);
        }
        instance.setLocalLifecyclePersistent(quickServerKeepRunning());
        instance.setLocalRestartOnCrash(quickServerAutoRestart());
        instance.getSettings().setProperty(QUICK_SERVER_ENABLED_KEY, "true");
        instance.getSettings().setProperty(SOURCE_WORLD_PATH_KEY, world.worldPath().toString());
        instance.getSettings().setProperty(SOURCE_WORLD_ID_KEY, world.worldId());
        putPathSetting(instance, SOURCE_INSTANCE_PATH_KEY, source.instancePath());
        putPathSetting(instance, SOURCE_GAME_DIR_KEY, source.gameDir());
    }

    private static SourceRuntime resolveSourceRuntime(WorldContext world) {
        Path gameDir = resolveCurrentGameDir();
        Instance source = loadSourceInstance(gameDir);
        if (source == null) {
            source = loadSourceInstance(resolveGameDirFromWorld(world.worldPath()));
        }
        if (source == null) {
            source = findSourceInstanceByPath(gameDir);
        }
        if (source == null) {
            source = findSourceInstanceByPath(resolveGameDirFromWorld(world.worldPath()));
        }
        String versionId = source != null && source.getVersionId() != null && !source.getVersionId().isBlank() ? source.getVersionId() : resolveGameVersion();
        ModLoader loader = source != null && source.getModLoader() != null ? source.getModLoader() : resolveLaunchedModLoader();
        String loaderVersion = source != null && source.getModLoaderVersion() != null ? source.getModLoaderVersion() : "";
        Path instancePath = source != null && source.getPath() != null && !source.getPath().isBlank() ? Path.of(source.getPath()).toAbsolutePath().normalize() : null;
        Path resolvedGameDir = instancePath != null ? instancePath : gameDir;
        return new SourceRuntime(versionId, loader, loaderVersion, instancePath, resolvedGameDir);
    }

    private static Path resolveCurrentGameDir() {
        try {
            return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveGameDirFromWorld(Path worldPath) {
        if (worldPath == null) {
            return null;
        }
        Path normalized = worldPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null && parent.getFileName() != null && "saves".equalsIgnoreCase(parent.getFileName().toString())) {
            return parent.getParent() != null ? parent.getParent().toAbsolutePath().normalize() : null;
        }
        return null;
    }

    private static Instance loadSourceInstance(Path path) {
        if (path == null || !Files.isDirectory(path)) {
            return null;
        }
        try {
            Instance instance = Instance.load(path);
            if (instance != null && !isQuickServer(instance)) {
                return instance;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Instance findSourceInstanceByPath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        try {
            for (Instance instance : InstanceManager.getInstance().getLocalInstances()) {
                if (instance == null || isQuickServer(instance) || instance.getPath() == null || instance.getPath().isBlank()) {
                    continue;
                }
                if (Path.of(instance.getPath()).toAbsolutePath().normalize().equals(normalized)) {
                    return instance;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ModLoader resolveLaunchedModLoader() {
        try {
            String launchedVersion = Minecraft.getInstance().getLaunchedVersion();
            if (launchedVersion != null) {
                String normalized = launchedVersion.toLowerCase(Locale.ROOT);
                if (normalized.contains("neoforge")) {
                    return ModLoader.NEOFORGE;
                }
                if (normalized.contains("forge")) {
                    return ModLoader.FORGE;
                }
                if (normalized.contains("quilt")) {
                    return ModLoader.QUILT;
                }
                if (normalized.contains("fabric")) {
                    return ModLoader.FABRIC;
                }
            }
        } catch (Exception ignored) {
        }
        return ModLoader.VANILLA;
    }

    private static void putPathSetting(Instance instance, String key, Path value) {
        if (value == null) {
            instance.getSettings().remove(key);
            return;
        }
        instance.getSettings().setProperty(key, value.toString());
    }

    private static void syncWorldToServer(WorldContext world, Instance instance) throws IOException {
        saveCurrentWorld();
        Path target = Path.of(instance.getPath()).resolve("world");
        QuickServerSyncManager.syncWorld(world.worldPath(), target);
        markServerClean(instance);
    }

    private static void syncServerWorldBack(Instance instance) {
        syncServerWorldBack(instance, null);
    }

    private static void syncServerWorldBack(Instance instance, Notification notification) {
        Notification progress = notification;
        boolean standalone = progress == null;
        try {
            String sourceWorldPath = instance.getSettings().getProperty(SOURCE_WORLD_PATH_KEY, "");
            if (sourceWorldPath.isBlank()) {
                return;
            }
            Path source = Path.of(instance.getPath()).resolve("world");
            Path target = Path.of(sourceWorldPath);
            if (Files.isDirectory(source) && Files.isDirectory(target)) {
                if (progress == null) {
                    progress = new Notification.Builder().message("Syncing World Back").description(displayWorldName(target)).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
                } else {
                    change(progress, "Syncing World Back", displayWorldName(target), Notification.Type.INFO, null, true);
                }
                instance.getSettings().setProperty(SYNC_STATE_KEY, SYNC_STATE_SYNCING_BACK);
                instance.save().join();
                QuickServerSyncManager.syncWorld(source, target);
                markServerClean(instance);
                if (standalone) {
                    change(progress, "World Synced", displayWorldName(target), Notification.Type.SUCCESS, null, false);
                }
            }
        } catch (Exception e) {
            Notification errorNotification = progress;
            if (errorNotification == null) {
                ScreenManager.getInstance().execute(() -> new Notification("Quick Server Sync Failed", cleanMessage(e), Notification.Type.ERROR));
            } else {
                change(errorNotification, "Quick Server Sync Failed", cleanMessage(e), Notification.Type.ERROR, null, false);
            }
        }
    }

    private static void resolveDirtyWorldState(WorldContext world, Instance instance, Notification notification, boolean startServer) throws IOException {
        if (!isServerDirty(instance)) {
            return;
        }
        if (!serverWorldLooksNewer(instance)) {
            markServerClean(instance);
            return;
        }
        if (startServer && sourceChangedAfterDirty(instance)) {
            change(notification, "Backing Up World", world.worldName(), Notification.Type.INFO, null, true);
            backupSourceWorld(world, instance);
        }
        syncServerWorldBack(instance, notification);
    }

    private static boolean isServerDirty(Instance instance) {
        String state = instance.getSettings().getProperty(SYNC_STATE_KEY);
        if (SYNC_STATE_SERVER_DIRTY.equalsIgnoreCase(state) || SYNC_STATE_SYNCING_BACK.equalsIgnoreCase(state)) {
            return true;
        }
        if (SYNC_STATE_CLEAN.equalsIgnoreCase(state)) {
            return false;
        }
        return serverWorldLooksNewer(instance);
    }

    private static void markServerDirty(Instance instance) {
        instance.getSettings().setProperty(SYNC_STATE_KEY, SYNC_STATE_SERVER_DIRTY);
        instance.getSettings().setProperty(LAST_SERVER_DIRTY_KEY, Instant.now().toString());
        instance.save().join();
    }

    private static void markServerClean(Instance instance) {
        instance.getSettings().setProperty(SYNC_STATE_KEY, SYNC_STATE_CLEAN);
        instance.getSettings().setProperty(LAST_WORLD_SYNC_KEY, Instant.now().toString());
        instance.save().join();
    }

    private static boolean serverWorldLooksNewer(Instance instance) {
        String sourceWorldPath = instance.getSettings().getProperty(SOURCE_WORLD_PATH_KEY, "");
        if (sourceWorldPath.isBlank()) {
            return false;
        }
        Path serverWorld = Path.of(instance.getPath()).resolve("world");
        Path sourceWorld = Path.of(sourceWorldPath);
        if (!Files.isDirectory(serverWorld) || !Files.isDirectory(sourceWorld)) {
            return false;
        }
        return newestWorldModifiedMillis(serverWorld) > newestWorldModifiedMillis(sourceWorld) + 1000L;
    }

    private static boolean sourceChangedAfterDirty(Instance instance) {
        String dirtyAt = instance.getSettings().getProperty(LAST_SERVER_DIRTY_KEY, "");
        if (dirtyAt.isBlank()) {
            return false;
        }
        String sourceWorldPath = instance.getSettings().getProperty(SOURCE_WORLD_PATH_KEY, "");
        if (sourceWorldPath.isBlank()) {
            return false;
        }
        try {
            Path sourceWorld = Path.of(sourceWorldPath);
            return Files.isDirectory(sourceWorld) && newestWorldModifiedMillis(sourceWorld) > Instant.parse(dirtyAt).toEpochMilli() + 1000L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void backupSourceWorld(WorldContext world, Instance instance) throws IOException {
        saveCurrentWorld();
        String stamp = Instant.now().toString().replace(':', '-').replace('.', '-');
        Path backup = Path.of(instance.getPath()).resolve("source-backups").resolve(stamp);
        QuickServerSyncManager.syncWorld(world.worldPath(), backup);
    }

    private static long newestWorldModifiedMillis(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .filter(path -> !isExcluded(root.relativize(path), WORLD_SYNC_EXCLUDES))
                .mapToLong(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis();
                    } catch (IOException ignored) {
                        return 0L;
                    }
                })
                .max()
                .orElse(0L);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static void syncMods(Instance instance, Notification notification) throws IOException {
        if (!quickServerMirrorMods()) {
            return;
        }
        change(notification, "Syncing Mods", instance.getName(), Notification.Type.INFO, null, true);
        Path gameDir = resolveSourceGameDir(instance);
        Path source = gameDir.resolve("mods");
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = Path.of(instance.getPath()).resolve("mods");
        Files.createDirectories(target);
        Map<String, ModEntry> previous = readModManifest(instance);
        Map<String, ModEntry> next = new HashMap<>();
        int skipped = 0;
        List<Path> mods;
        try (var stream = Files.list(source)) {
            mods = stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
        }
        Map<Path, ModMirrorDecision> decisions = resolveModMirrorDecisions(mods);
        for (Path mod : mods) {
            ModMirrorDecision decision = decisions.get(mod.toAbsolutePath().normalize());
            if (decision == null || !decision.serverCompatible()) {
                skipped++;
                continue;
            }
            ModEntry entry = ModEntry.from(mod);
            next.put(mod.getFileName().toString(), entry);
            ModEntry old = previous.get(mod.getFileName().toString());
            Path targetFile = target.resolve(mod.getFileName().toString());
            if (!entry.equals(old) || !Files.exists(targetFile)) {
                Files.copy(mod, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        try (var stream = Files.list(target)) {
            for (Path mod : stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                if (!next.containsKey(mod.getFileName().toString())) {
                    Files.deleteIfExists(mod);
                }
            }
        }
        writeModManifest(instance, next);
        instance.getSettings().setProperty(LAST_MOD_SYNC_KEY, Instant.now().toString());
        if (skipped > 0) {
            change(notification, "Syncing Mods", "Skipped " + skipped + " Unsafe Mods", Notification.Type.INFO, null, true);
        }
    }

    private static Map<Path, ModMirrorDecision> resolveModMirrorDecisions(List<Path> mods) {
        Map<Path, ModMirrorDecision> decisions = new HashMap<>();
        if (mods.isEmpty()) {
            return decisions;
        }
        List<ModCandidate> candidates = collectModCandidates(mods);
        if (candidates.isEmpty()) {
            return decisions;
        }
        populateResourceMetadata(candidates);
        for (ModCandidate candidate : candidates) {
            decisions.put(candidate.path(), resolveModMirrorDecision(candidate));
        }
        return decisions;
    }

    private static List<ModCandidate> collectModCandidates(List<Path> mods) {
        List<ModCandidate> candidates = new ArrayList<>();
        for (Path mod : mods) {
            try {
                Path normalized = mod.toAbsolutePath().normalize();
                String fileName = normalized.getFileName().toString();
                String hash = FileUtils.calculateSHA1(normalized);
                long fingerprint = calculateCurseForgeFingerprint(normalized);
                candidates.add(new ModCandidate(normalized, fileName, hash, fingerprint));
            } catch (IOException e) {
                RebaseLogger.log("Could not hash quick server mod " + mod.getFileName() + ": " + e.getMessage());
            }
        }
        return candidates;
    }

    private static void populateResourceMetadata(List<ModCandidate> candidates) {
        Set<String> checkedUnknownHashes = readCheckedUnknownHashes();
        boolean hashLookupComplete = fetchMissingHashMetadata(candidates, checkedUnknownHashes);
        boolean fingerprintLookupComplete = fetchMissingCurseForgeFingerprintMetadata(candidates, checkedUnknownHashes);
        if (hashLookupComplete && fingerprintLookupComplete) {
            cacheUnresolvedModHashes(candidates, checkedUnknownHashes);
        }
    }

    private static boolean fetchMissingHashMetadata(List<ModCandidate> candidates, Set<String> checkedUnknownHashes) {
        List<String> missing = candidates.stream()
            .map(ModCandidate::hash)
            .filter(Objects::nonNull)
            .filter(hash -> Rebase.get().getResourceMetadataManager().get(hash) == null)
            .filter(hash -> shouldLookupUnknownHash(hash, checkedUnknownHashes))
            .distinct()
            .toList();
        if (missing.isEmpty()) {
            return true;
        }
        if (Rebase.get().getResourceProviders().isEmpty()) {
            return false;
        }
        Map<String, ResourceMetadata> resolved = new ConcurrentHashMap<>();
        AtomicBoolean providerFailure = new AtomicBoolean(false);
        CompletableFuture<?>[] futures = Rebase.get().getResourceProviders().stream()
            .map(provider -> provider.searchByHashes(new ArrayList<>(missing)).thenCompose(versions -> {
                if (versions == null || versions.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                return cacheResolvedVersions(provider, versions, resolved, providerFailure);
            }).exceptionally(throwable -> {
                providerFailure.set(true);
                logProviderMetadataFailure(provider, throwable);
                return null;
            }))
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
        if (!resolved.isEmpty()) {
            Rebase.get().getResourceMetadataManager().putAll(resolved);
        }
        return !providerFailure.get();
    }

    private static CompletableFuture<Void> cacheResolvedVersions(IResourceProvider provider, Map<String, OnlineResourceVersion> versions, Map<String, ResourceMetadata> resolved, AtomicBoolean providerFailure) {
        List<CompletableFuture<Void>> detailFutures = new ArrayList<>();
        for (Map.Entry<String, OnlineResourceVersion> entry : versions.entrySet()) {
            OnlineResourceVersion version = entry.getValue();
            if (version == null || version.projectId == null || version.projectId.isBlank()) {
                continue;
            }
            detailFutures.add(cacheResourceDetails(provider, version.projectId).thenAccept(details -> {
                if (details != null) {
                    resolved.put(entry.getKey(), new ResourceMetadata(provider.getName(), version.projectId, version.id, version.versionNumber));
                }
            }).exceptionally(throwable -> {
                providerFailure.set(true);
                logProviderMetadataFailure(provider, throwable);
                return null;
            }));
        }
        if (detailFutures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(detailFutures.toArray(new CompletableFuture[0]));
    }

    private static boolean fetchMissingCurseForgeFingerprintMetadata(List<ModCandidate> candidates, Set<String> checkedUnknownHashes) {
        IResourceProvider provider = Rebase.get().getResourceProvider("CurseForge");
        if (provider == null) {
            return false;
        }
        Map<String, ModCandidate> missingByFileName = new HashMap<>();
        Map<String, ModCandidate> missingByHash = new HashMap<>();
        List<Long> fingerprints = new ArrayList<>();
        for (ModCandidate candidate : candidates) {
            if (candidate.hash() == null || Rebase.get().getResourceMetadataManager().get(candidate.hash()) != null || !shouldLookupUnknownHash(candidate.hash(), checkedUnknownHashes) || candidate.fingerprint() <= 0) {
                continue;
            }
            missingByFileName.put(candidate.fileName().toLowerCase(Locale.ROOT), candidate);
            missingByHash.put(candidate.hash().toLowerCase(Locale.ROOT), candidate);
            fingerprints.add(candidate.fingerprint());
        }
        if (fingerprints.isEmpty()) {
            return true;
        }
        try {
            Map<String, OnlineResourceVersion> versions = provider.searchByFingerprints(fingerprints).join();
            if (versions == null || versions.isEmpty()) {
                return true;
            }
            Map<String, ResourceMetadata> resolved = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> details = new ArrayList<>();
            AtomicBoolean detailFailure = new AtomicBoolean(false);
            for (Map.Entry<String, OnlineResourceVersion> entry : versions.entrySet()) {
                OnlineResourceVersion version = entry.getValue();
                ModCandidate candidate = resolveFingerprintCandidate(entry.getKey(), version, missingByFileName, missingByHash);
                if (candidate == null || version == null || version.projectId == null || version.projectId.isBlank()) {
                    continue;
                }
                details.add(cacheResourceDetails(provider, version.projectId).thenAccept(resource -> {
                    if (resource != null) {
                        resolved.put(candidate.hash(), new ResourceMetadata(provider.getName(), version.projectId, version.id, version.versionNumber));
                    }
                }).exceptionally(throwable -> {
                    detailFailure.set(true);
                    logProviderMetadataFailure(provider, throwable);
                    return null;
                }));
            }
            CompletableFuture.allOf(details.toArray(new CompletableFuture[0])).join();
            if (!resolved.isEmpty()) {
                Rebase.get().getResourceMetadataManager().putAll(resolved);
            }
            return !detailFailure.get();
        } catch (Exception e) {
            RebaseLogger.log("CurseForge quick server fingerprint lookup failed: " + cleanMessage(e));
            return false;
        }
    }

    private static ModCandidate resolveFingerprintCandidate(String fileName, OnlineResourceVersion version, Map<String, ModCandidate> missingByFileName, Map<String, ModCandidate> missingByHash) {
        if (fileName != null) {
            ModCandidate byFileName = missingByFileName.get(fileName.toLowerCase(Locale.ROOT));
            if (byFileName != null) {
                return byFileName;
            }
        }
        String sha1 = resolveVersionSha1(version);
        return sha1 == null ? null : missingByHash.get(sha1.toLowerCase(Locale.ROOT));
    }

    private static String resolveVersionSha1(OnlineResourceVersion version) {
        if (version == null || version.files == null) {
            return null;
        }
        for (OnlineResourceVersion.VersionFile file : version.files) {
            if (file == null || file.hashes == null) {
                continue;
            }
            String sha1 = file.hashes.get("sha1");
            if (sha1 != null && !sha1.isBlank()) {
                return sha1.trim();
            }
        }
        return null;
    }

    private static void cacheUnresolvedModHashes(List<ModCandidate> candidates, Set<String> checkedUnknownHashes) {
        Set<String> unresolved = new HashSet<>();
        for (ModCandidate candidate : candidates) {
            String hash = candidate.hash();
            if (hash != null && Rebase.get().getResourceMetadataManager().get(hash) == null) {
                unresolved.add(hash.toLowerCase(Locale.ROOT));
            }
        }
        if (!unresolved.isEmpty()) {
            Rebase.get().getResourceMetadataManager().addUnknownHashes(unresolved);
            checkedUnknownHashes.addAll(unresolved);
            writeCheckedUnknownHashes(checkedUnknownHashes);
        }
    }

    private static boolean shouldLookupUnknownHash(String hash, Set<String> checkedUnknownHashes) {
        return !Rebase.get().getResourceMetadataManager().isUnknown(hash) || !checkedUnknownHashes.contains(hash.toLowerCase(Locale.ROOT));
    }

    private static Set<String> readCheckedUnknownHashes() {
        Set<String> hashes = new HashSet<>();
        if (!Files.isRegularFile(QUICK_SERVER_CHECKED_UNKNOWN_HASHES_FILE)) {
            return hashes;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(QUICK_SERVER_CHECKED_UNKNOWN_HASHES_FILE));
            if (root != null && root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    if (element != null && !element.isJsonNull()) {
                        String hash = element.getAsString();
                        if (hash != null && !hash.isBlank()) {
                            hashes.add(hash.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Exception e) {
            RebaseLogger.log("Could not read quick server unknown hash cache: " + cleanMessage(e));
        }
        return hashes;
    }

    private static void writeCheckedUnknownHashes(Set<String> hashes) {
        try {
            Files.writeString(QUICK_SERVER_CHECKED_UNKNOWN_HASHES_FILE, GSON.toJson(hashes));
        } catch (IOException e) {
            RebaseLogger.log("Could not save quick server unknown hash cache: " + e.getMessage());
        }
    }

    private static CompletableFuture<OnlineResource> cacheResourceDetails(IResourceProvider provider, String projectId) {
        OnlineResource cached = Rebase.get().getCacheManager().get(provider.getName(), projectId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return provider.getResourceDetails(projectId).thenApply(details -> {
            if (details != null) {
                Rebase.get().getCacheManager().put(provider.getName(), projectId, details);
            }
            return details;
        });
    }

    private static ModMirrorDecision resolveModMirrorDecision(ModCandidate candidate) {
        if (candidate.hash() != null) {
            ResourceMetadata metadata = Rebase.get().getResourceMetadataManager().get(candidate.hash());
            if (metadata != null) {
                OnlineResource resource = Rebase.get().getCacheManager().get(metadata.providerName, metadata.projectId);
                if (resource == null) {
                    IResourceProvider provider = Rebase.get().getResourceProvider(metadata.providerName);
                    if (provider != null) {
                        try {
                            resource = cacheResourceDetails(provider, metadata.projectId).join();
                        } catch (Exception e) {
                            RebaseLogger.log("Could not refresh quick server resource metadata for " + candidate.fileName() + ": " + cleanMessage(e));
                        }
                    }
                }
                ModMirrorDecision decision = resolveProviderSide(metadata, resource);
                if (decision.known()) {
                    return decision;
                }
                if (resource == null) {
                    return ModMirrorDecision.skip("Provider Metadata Unavailable");
                }
                if (isAmbiguousCurseForgeSide(metadata, resource)) {
                    LocalModSide localSide = resolveLocalModSide(candidate.path());
                    return localSide == LocalModSide.SERVER_COMPATIBLE ? ModMirrorDecision.server("Local Metadata") : ModMirrorDecision.skip("Ambiguous Provider Metadata");
                }
            }
        }
        LocalModSide localSide = resolveLocalModSide(candidate.path());
        return switch (localSide) {
            case SERVER_COMPATIBLE -> ModMirrorDecision.server("Local Metadata");
            case CLIENT_ONLY -> ModMirrorDecision.skip("Local Metadata");
            case UNKNOWN -> ModMirrorDecision.server("Local Metadata");
        };
    }

    private static ModMirrorDecision resolveProviderSide(ResourceMetadata metadata, OnlineResource resource) {
        if (resource == null) {
            return ModMirrorDecision.unknown();
        }
        String serverSide = normalizeSide(resource.serverSide);
        if ("unsupported".equals(serverSide)) {
            return ModMirrorDecision.skip("Provider Metadata");
        }
        boolean curseForge = metadata != null && "curseforge".equalsIgnoreCase(metadata.providerName);
        if (curseForge && "optional".equals(serverSide) && "optional".equals(normalizeSide(resource.clientSide))) {
            return ModMirrorDecision.unknown();
        }
        if ("required".equals(serverSide) || "optional".equals(serverSide)) {
            return ModMirrorDecision.server("Provider Metadata");
        }
        String clientSide = normalizeSide(resource.clientSide);
        if ("required".equals(clientSide) || "optional".equals(clientSide)) {
            return ModMirrorDecision.skip("Provider Metadata");
        }
        return ModMirrorDecision.unknown();
    }

    private static boolean isAmbiguousCurseForgeSide(ResourceMetadata metadata, OnlineResource resource) {
        return metadata != null
            && resource != null
            && "curseforge".equalsIgnoreCase(metadata.providerName)
            && "optional".equals(normalizeSide(resource.serverSide))
            && "optional".equals(normalizeSide(resource.clientSide));
    }

    private static String normalizeSide(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void logProviderMetadataFailure(IResourceProvider provider, Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        RebaseLogger.log("Quick server metadata lookup from " + provider.getName() + " failed: " + cleanMessage(cause));
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long calculateCurseForgeFingerprint(Path path) throws IOException {
        byte[] source = Files.readAllBytes(path);
        byte[] data = new byte[source.length];
        int length = 0;
        for (byte value : source) {
            int unsigned = value & 0xff;
            if (unsigned != 9 && unsigned != 10 && unsigned != 13 && unsigned != 32) {
                data[length++] = value;
            }
        }
        int hash = murmurHash2(data, length, 1);
        return Integer.toUnsignedLong(hash);
    }

    private static int murmurHash2(byte[] data, int length, int seed) {
        int m = 0x5bd1e995;
        int r = 24;
        int hash = seed ^ length;
        int offset = 0;
        int remaining = length;
        while (remaining >= 4) {
            int key = (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
            key *= m;
            key ^= key >>> r;
            key *= m;
            hash *= m;
            hash ^= key;
            offset += 4;
            remaining -= 4;
        }
        if (remaining == 3) {
            hash ^= (data[offset + 2] & 0xff) << 16;
        }
        if (remaining >= 2) {
            hash ^= (data[offset + 1] & 0xff) << 8;
        }
        if (remaining >= 1) {
            hash ^= data[offset] & 0xff;
            hash *= m;
        }
        hash ^= hash >>> 13;
        hash *= m;
        hash ^= hash >>> 15;
        return hash;
    }

    private static void syncSupportFiles(Instance instance, Notification notification) throws IOException {
        if (!quickServerMirrorMods() || instance.getModLoader() == ModLoader.VANILLA) {
            return;
        }
        Path gameDir = resolveSourceGameDir(instance);
        if (gameDir == null || !Files.isDirectory(gameDir)) {
            return;
        }
        List<String> previous = parseListSetting(instance.getSettings().getProperty(SUPPORT_SYNC_DIRS_KEY, ""));
        List<String> next = new ArrayList<>();
        int synced = 0;
        for (String directory : SUPPORT_SYNC_DIRS) {
            Path source = gameDir.resolve(directory);
            if (!Files.isDirectory(source)) {
                continue;
            }
            change(notification, "Syncing " + displayDirectory(directory), instance.getName(), Notification.Type.INFO, null, true);
            QuickServerSyncManager.syncWorld(source, Path.of(instance.getPath()).resolve(directory));
            next.add(directory);
            synced++;
        }
        for (String directory : previous) {
            if (!next.contains(directory) && SUPPORT_SYNC_DIRS.contains(directory)) {
                deleteDirectory(Path.of(instance.getPath()).resolve(directory));
            }
        }
        instance.getSettings().setProperty(SUPPORT_SYNC_DIRS_KEY, String.join(",", next));
        if (synced > 0) {
            instance.getSettings().setProperty(LAST_SUPPORT_SYNC_KEY, Instant.now().toString());
        }
    }

    private static List<String> parseListSetting(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path resolveSourceGameDir(Instance instance) {
        String configured = instance.getSettings().getProperty(SOURCE_GAME_DIR_KEY, "");
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        String sourceWorldPath = instance.getSettings().getProperty(SOURCE_WORLD_PATH_KEY, "");
        if (!sourceWorldPath.isBlank()) {
            Path path = resolveGameDirFromWorld(Path.of(sourceWorldPath));
            if (path != null && Files.isDirectory(path)) {
                return path;
            }
        }
        Path current = resolveCurrentGameDir();
        return current != null ? current : Path.of(".");
    }

    private static String displayDirectory(String directory) {
        return switch (directory) {
            case "config" -> "Config";
            case "defaultconfigs" -> "Default Configs";
            case "kubejs" -> "KubeJS";
            case "scripts" -> "Scripts";
            case "datapacks" -> "Datapacks";
            case "openloader" -> "OpenLoader";
            default -> directory;
        };
    }

    private static String displayWorldName(Path worldPath) {
        return worldPath != null && worldPath.getFileName() != null ? worldPath.getFileName().toString() : "Singleplayer World";
    }

    private static void prepareServer(Instance instance, Notification notification) throws IOException {
        InstanceFactory factory = new InstanceFactory();
        boolean preferredBuildResolved = resolvePreferredServerBuild(instance, factory);
        Path jar = instance.resolveServerJarPath();
        instance.getServerProperties().setProperty("eula", "true");
        instance.getServerProperties().setProperty("motd", "Remotely | Quick Server");
        change(notification, "Preparing Server", instance.getName(), Notification.Type.INFO, null, true);
        String runtimeSignature = serverRuntimeSignature(instance);
        boolean staleRuntime = !runtimeSignature.equals(instance.getSettings().getProperty(SERVER_RUNTIME_SIGNATURE_KEY, ""));
        if ((!Files.isRegularFile(jar) || staleRuntime) && requiresExactModdedBuild(instance) && !preferredBuildResolved) {
            throw new IOException("Could Not Resolve " + instance.getModLoader() + " " + instance.getModLoaderVersion());
        }
        CompletableFuture<Void> jarFuture = Files.isRegularFile(jar) && !staleRuntime
            ? CompletableFuture.completedFuture(null)
            : factory.downloadMissingServerJar(instance, notification);
        jarFuture.thenCompose(v -> InstanceRepairer.createStartScript(instance))
            .thenRun(() -> instance.getSettings().setProperty(SERVER_RUNTIME_SIGNATURE_KEY, serverRuntimeSignature(instance)))
            .thenCompose(v -> instance.saveServerProperties())
            .thenCompose(v -> instance.save())
            .join();
    }

    private static boolean resolvePreferredServerBuild(Instance instance, InstanceFactory factory) {
        if (instance == null || instance.getModLoader() == null || !ModLoader.isModded(instance.getModLoader())) {
            return true;
        }
        String loaderVersion = instance.getModLoaderVersion();
        if (loaderVersion == null || loaderVersion.isBlank()) {
            return true;
        }
        if (instance.getServerBuildNumber() != null && !instance.getServerBuildNumber().isBlank()) {
            return true;
        }
        try {
            List<InstanceFactory.ServerBuildDescriptor> builds = factory.getServerBuilds(instance.getServerSoftwareType(), instance.getVersionId()).join();
            for (InstanceFactory.ServerBuildDescriptor build : builds) {
                if (loaderVersion.equalsIgnoreCase(build.displayName())) {
                    instance.setServerBuildNumber(build.buildNumber());
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean requiresExactModdedBuild(Instance instance) {
        return instance != null
            && instance.getModLoader() != null
            && ModLoader.isModded(instance.getModLoader())
            && instance.getModLoaderVersion() != null
            && !instance.getModLoaderVersion().isBlank();
    }

    private static String serverRuntimeSignature(Instance instance) {
        return String.join("|",
            nullToBlank(instance.getServerSoftwareType()),
            nullToBlank(instance.getVersionId()),
            nullToBlank(instance.getModLoaderVersion()),
            nullToBlank(instance.getServerBuildNumber())
        );
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static void ensureDynamicServerPort(Instance instance, Notification notification) throws IOException {
        int currentPort = configuredPort(instance);
        if (currentPort > 0 && currentPort <= 65535 && isPortAvailable(currentPort)) {
            return;
        }
        int nextPort = resolvePort();
        if (nextPort <= 0 || nextPort > 65535 || !isPortAvailable(nextPort)) {
            throw new IOException("No Available Server Port");
        }
        ReProxyManager.stopQuietly(JvmReProxyConnectorCapability.server(instance), null);
        instance.getServerProperties().setProperty("server-port", String.valueOf(nextPort));
        instance.saveServerProperties().join();
        change(notification, "Binding Port", "Using " + nextPort, Notification.Type.INFO, null, true);
    }

    private static int configuredPort(Instance instance) {
        String value = instance.getServerProperties().getProperty("server-port");
        if (value != null && !value.isBlank()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return instance.getPort();
    }

    private static void startLocalServer(Instance instance, Notification notification) throws IOException {
        LocalServerControllerModels.StatusResponse status = LocalServerControllerClient.status(instance);
        if (status == null || !status.knownSession || status.pid <= 0 || !"RUNNING".equalsIgnoreCase(status.state)) {
            instance.setState(InstanceState.STARTING);
            LocalServerControllerClient.start(instance);
            activeQuickServers.add(instance.getInstanceId());
        }
        long deadline = System.currentTimeMillis() + 120_000L;
        boolean processStarted = false;
        int readyPings = 0;
        while (System.currentTimeMillis() < deadline) {
            status = LocalServerControllerClient.status(instance);
            if (status != null && status.pid > 0 && "RUNNING".equalsIgnoreCase(status.state)) {
                instance.setState(InstanceState.RUNNING);
                if (!processStarted) {
                    processStarted = true;
                    change(notification, "Waiting Server", instance.getName(), Notification.Type.INFO, null, true);
                }
                if (isMinecraftServerReady(instance)) {
                    readyPings++;
                    if (readyPings >= 2) {
                        return;
                    }
                } else {
                    readyPings = 0;
                }
            } else if (status != null && status.knownSession && isTerminalServerState(status.state)) {
                instance.setState("CRASHED".equalsIgnoreCase(status.state) ? InstanceState.CRASHED : InstanceState.STOPPED);
                throw new IOException("Server " + displayServerState(status.state));
            }
            sleep(500);
        }
        throw new IOException(processStarted ? "Server Is Still Starting" : "Server Did Not Start");
    }

    private static boolean isTerminalServerState(String state) {
        String normalized = normalizeServerStateKey(state);
        return normalized.equals("STOPPED") || normalized.equals("CRASHED");
    }

    private static String normalizeServerStateKey(String state) {
        return state == null || state.isBlank() ? "STOPPED" : state.trim().toUpperCase(Locale.ROOT);
    }

    private static String displayServerState(String state) {
        return "CRASHED".equalsIgnoreCase(state) ? "Crashed" : "Stopped";
    }

    private static boolean isLocalServerRunning(Instance instance) {
        LocalServerControllerModels.StatusResponse status = LocalServerControllerClient.status(instance);
        return status != null && status.knownSession && status.pid > 0 && "RUNNING".equalsIgnoreCase(status.state);
    }

    private static boolean isQuickServerManagedRunning(Instance instance) {
        return isLocalServerRunning(instance) || (ReProxyManager.isForwarded(JvmReProxyConnectorCapability.server(instance)) && isMinecraftServerReady(instance));
    }

    private static boolean isMinecraftServerReady(Instance instance) {
        int port = configuredPort(instance);
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(1500);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream handshakeOutput = new DataOutputStream(handshake);
            writeVarInt(handshakeOutput, 0);
            writeVarInt(handshakeOutput, SharedConstants.getProtocolVersion());
            writeString(handshakeOutput, "127.0.0.1");
            handshakeOutput.writeShort(port);
            writeVarInt(handshakeOutput, 1);
            writePacket(output, handshake.toByteArray());
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            DataOutputStream requestOutput = new DataOutputStream(request);
            writeVarInt(requestOutput, 0);
            writePacket(output, request.toByteArray());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = readVarInt(input);
            if (length <= 0) {
                return false;
            }
            int packetId = readVarInt(input);
            if (packetId != 0) {
                return false;
            }
            String response = readString(input, 32767);
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            return root.has("version") && root.has("description");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writePacket(OutputStream output, byte[] packet) throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
        output.flush();
    }

    private static void writeString(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxLength) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > maxLength * 4) {
            throw new IOException("Invalid Server Status");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Incomplete Server Status");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.length() > maxLength) {
            throw new IOException("Server Status Too Long");
        }
        return value;
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            output.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = input.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position >= 35) {
                throw new IOException("Invalid VarInt");
            }
        } while ((current & 0x80) != 0);
        return value;
    }

    private static void waitForStop(Instance instance) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            LocalServerControllerModels.StatusResponse status = LocalServerControllerClient.status(instance);
            if (status == null || !status.knownSession || status.pid <= 0 || "STOPPED".equalsIgnoreCase(status.state) || "CRASHED".equalsIgnoreCase(status.state)) {
                return;
            }
            sleep(500);
        }
    }

    private static Path resolveWorldPath(IntegratedServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    private static void saveCurrentWorld() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable saveTask = () -> {
            try {
                server.saveEverything(false, true, true);
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
            }
        };
        server.execute(saveTask);
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IOException("World Save Timed Out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("World Save Interrupted", e);
        }
        if (error.get() != null) {
            throw new IOException("World Save Failed", error.get());
        }
    }

    private static String resolveGameVersion() {
        //#if MC >= 1.21.6 || MC >= 26.1
        String currentVersion = extractGameVersion(SharedConstants.getCurrentVersion().name());
        //#else
        //$$ String currentVersion = extractGameVersion(SharedConstants.getCurrentVersion().getName());
        //#endif
        if (currentVersion != null) {
            return currentVersion;
        }
        try {
            if (RemotelyClient.INSTANCE != null && RemotelyClient.INSTANCE.getHost() != null) {
                String hostVersion = extractGameVersion(RemotelyClient.INSTANCE.getHost().getGameVersion());
                if (hostVersion != null) {
                    return hostVersion;
                }
            }
        } catch (Exception ignored) {
        }
        String launchedVersion = extractGameVersion(Minecraft.getInstance().getLaunchedVersion());
        if (launchedVersion != null) {
            return launchedVersion;
        }
        return "latest";
    }

    private static LocalModSide resolveLocalModSide(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                LocalModSide side = resolveFabricLikeSide(new String(zip.getInputStream(fabric).readAllBytes(), StandardCharsets.UTF_8));
                if (side != LocalModSide.UNKNOWN) {
                    return side;
                }
            }
            ZipEntry quilt = zip.getEntry("quilt.mod.json");
            if (quilt != null) {
                LocalModSide side = resolveFabricLikeSide(new String(zip.getInputStream(quilt).readAllBytes(), StandardCharsets.UTF_8));
                if (side != LocalModSide.UNKNOWN) {
                    return side;
                }
            }
            ZipEntry forge = zip.getEntry("META-INF/mods.toml");
            if (forge == null) {
                forge = zip.getEntry("META-INF/neoforge.mods.toml");
            }
            if (forge != null) {
                LocalModSide side = resolveForgeLikeSide(new String(zip.getInputStream(forge).readAllBytes(), StandardCharsets.UTF_8));
                if (side != LocalModSide.UNKNOWN) {
                    return side;
                }
            }
        } catch (IOException ignored) {
        }
        return LocalModSide.UNKNOWN;
    }

    private static LocalModSide resolveFabricLikeSide(String content) {
        try {
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (isClientOnlyModId(readJsonString(root, "id"))) {
                return LocalModSide.CLIENT_ONLY;
            }
            String environment = readJsonString(root, "environment");
            if (environment == null || environment.isBlank()) {
                return LocalModSide.UNKNOWN;
            }
            String normalized = environment.trim().toLowerCase(Locale.ROOT);
            if ("client".equals(normalized)) {
                return LocalModSide.CLIENT_ONLY;
            }
            if ("server".equals(normalized) || "*".equals(normalized)) {
                return LocalModSide.SERVER_COMPATIBLE;
            }
            return LocalModSide.UNKNOWN;
        } catch (Exception ignored) {
            String normalized = content.toLowerCase(Locale.ROOT);
            if (normalized.contains("\"environment\":\"client\"") || normalized.contains("\"environment\": \"client\"")) {
                return LocalModSide.CLIENT_ONLY;
            }
            if (normalized.contains("\"environment\":\"server\"") || normalized.contains("\"environment\": \"server\"")) {
                return LocalModSide.SERVER_COMPATIBLE;
            }
            return LocalModSide.UNKNOWN;
        }
    }

    private static LocalModSide resolveForgeLikeSide(String content) {
        if (CLIENT_SIDE_ONLY_PATTERN.matcher(content).find()) {
            return LocalModSide.CLIENT_ONLY;
        }
        TomlScanState state = new TomlScanState();
        TomlSection section = TomlSection.NONE;
        TomlDependency dependency = new TomlDependency();
        for (String rawLine : content.split("\\R")) {
            String line = stripTomlComment(rawLine).trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("[[") && line.endsWith("]]")) {
                if (dependency.isClientBaseDependency()) {
                    state.clientBaseDependency = true;
                }
                dependency = new TomlDependency();
                String sectionName = line.substring(2, line.length() - 2).trim().toLowerCase(Locale.ROOT);
                if ("mods".equals(sectionName)) {
                    section = TomlSection.MOD;
                    state.modSections++;
                } else if (sectionName.startsWith("dependencies.")) {
                    section = TomlSection.DEPENDENCY;
                } else {
                    section = TomlSection.NONE;
                }
                continue;
            }
            TomlKeyValue keyValue = parseTomlKeyValue(line);
            if (keyValue == null) {
                continue;
            }
            if (section == TomlSection.MOD) {
                if ("modid".equalsIgnoreCase(keyValue.key()) && isClientOnlyModId(keyValue.value())) {
                    return LocalModSide.CLIENT_ONLY;
                }
                if ("side".equalsIgnoreCase(keyValue.key())) {
                    state.modSideEntries++;
                    String side = keyValue.value().trim().toLowerCase(Locale.ROOT);
                    if ("client".equals(side)) {
                        state.clientModSideEntries++;
                    } else if ("server".equals(side) || "both".equals(side)) {
                        state.serverCompatibleModSideEntries++;
                    }
                }
            } else if (section == TomlSection.DEPENDENCY) {
                if ("modid".equalsIgnoreCase(keyValue.key())) {
                    dependency.modId = normalizeModId(keyValue.value());
                } else if ("side".equalsIgnoreCase(keyValue.key())) {
                    dependency.side = keyValue.value().trim();
                }
            }
        }
        if (dependency.isClientBaseDependency()) {
            state.clientBaseDependency = true;
        }
        if (state.clientBaseDependency || state.allDeclaredModsClientOnly()) {
            return LocalModSide.CLIENT_ONLY;
        }
        if (state.allDeclaredModsServerCompatible()) {
            return LocalModSide.SERVER_COMPATIBLE;
        }
        return LocalModSide.UNKNOWN;
    }

    private static String readJsonString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isClientOnlyModId(String modId) {
        String normalized = normalizeModId(modId);
        return normalized != null && CLIENT_ONLY_MOD_IDS.contains(normalized);
    }

    private static String normalizeModId(String modId) {
        if (modId == null || modId.isBlank()) {
            return null;
        }
        return modId.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String stripTomlComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (current == '#' && !quoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static TomlKeyValue parseTomlKeyValue(String line) {
        int equals = line.indexOf('=');
        if (equals <= 0) {
            return null;
        }
        String key = line.substring(0, equals).trim();
        String value = line.substring(equals + 1).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            while (end > 0 && value.charAt(end - 1) == '\\') {
                end = value.indexOf('"', end + 1);
            }
            if (end > 0) {
                value = value.substring(1, end);
            }
        } else {
            int space = value.indexOf(' ');
            if (space >= 0) {
                value = value.substring(0, space);
            }
        }
        if (key.isBlank() || value.isBlank()) {
            return null;
        }
        return new TomlKeyValue(key, value);
    }

    private static boolean isExcluded(Path relative, Set<String> excludes) {
        for (Path part : relative) {
            if (excludes.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static int resolvePort() {
        for (int port = 25565; port <= 25665; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ignored) {
            return -1;
        }
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String extractGameVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        Matcher matcher = GAME_VERSION_PATTERN.matcher(version);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static boolean quickServerPrecreate() {
        return quickServerPrecreate;
    }

    private static boolean quickServerKeepRunning() {
        return quickServerKeepRunning;
    }

    private static boolean quickServerAutoRestart() {
        return quickServerAutoRestart;
    }

    private static boolean quickServerMirrorMods() {
        return quickServerMirrorMods;
    }

    private static String stableWorldId(Path worldPath) {
        String input = worldPath.toAbsolutePath().normalize().toString();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(input.getBytes());
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String sanitizePathName(String value) {
        String sanitized = value == null ? "world" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return sanitized.isBlank() ? "world" : sanitized;
    }

    private static Properties readWorldMetadata(Path worldPath) {
        Properties properties = new Properties();
        Path file = worldPath.resolve(METADATA_FILE);
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            } catch (IOException ignored) {
            }
        }
        return properties;
    }

    private static void writeWorldMetadata(WorldContext world, Instance instance) {
        Properties properties = readWorldMetadata(world.worldPath());
        properties.setProperty("instanceId", instance.getInstanceId());
        properties.setProperty("instancePath", instance.getPath());
        properties.setProperty("worldId", world.worldId());
        try (var output = Files.newOutputStream(world.worldPath().resolve(METADATA_FILE))) {
            properties.store(output, "Remotely Quick Server");
        } catch (IOException ignored) {
        }
    }

    private static Map<String, ModEntry> readModManifest(Instance instance) {
        Path file = Path.of(instance.getPath()).resolve(MOD_MANIFEST_FILE);
        if (!Files.isRegularFile(file)) {
            return new HashMap<>();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, ModEntry> manifest = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                manifest.put(entry.getKey(), new ModEntry(value.get("size").getAsLong(), value.get("modified").getAsLong()));
            }
            return manifest;
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }

    private static void writeModManifest(Instance instance, Map<String, ModEntry> manifest) throws IOException {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, ModEntry> entry : manifest.entrySet()) {
            JsonObject value = new JsonObject();
            value.addProperty("size", entry.getValue().size());
            value.addProperty("modified", entry.getValue().modified());
            root.add(entry.getKey(), value);
        }
        Files.writeString(Path.of(instance.getPath()).resolve(MOD_MANIFEST_FILE), GSON.toJson(root));
    }

    private static Instance resolveInstance(String instanceId) {
        try {
            return InstanceManager.getInstance().getInstanceById(instanceId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void startReProxy(Instance instance, Runnable onComplete) {
        ReProxyManager.startQuietly(JvmReProxyConnectorCapability.server(instance), onComplete);
    }

    private static void connectOwnerToLocalServer(Instance instance) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || currentWorld() == null) {
            return;
        }
        if (!ownerTransferInProgress.compareAndSet(false, true)) {
            return;
        }
        String address = "127.0.0.1:" + instance.getPort();
        quickServerJoinCleanupUntilMillis = System.currentTimeMillis() + 120_000L;
        pendingOwnerTransferServer = server;
        pendingOwnerTransferAddress = address;
        pendingOwnerTransferDeadlineMillis = System.currentTimeMillis() + 60_000L;
        minecraft.execute(() -> beginOwnerTransfer(server));
    }

    private static void beginOwnerTransfer(IntegratedServer server) {
        Minecraft minecraft = Minecraft.getInstance();
        //#if MC >= 26.2
        minecraft.gui.setOverlay(null);
        minecraft.gui.setScreen(new QuickServerJoinScreen());
        //#else
        //$$ minecraft.setOverlay(null);
        //$$ minecraft.setScreen(new QuickServerJoinScreen());
        //#endif
        CompletableFuture.runAsync(() -> {
            try {
                server.halt(false);
            } catch (Exception e) {
                ownerTransferInProgress.set(false);
                pendingOwnerTransferServer = null;
                pendingOwnerTransferAddress = "";
                pendingOwnerTransferDeadlineMillis = 0L;
                pendingOwnerConnectAddress = "";
                pendingOwnerConnectAfterMillis = 0L;
                ownerConnectStarted.set(false);
                quickServerJoinCleanupUntilMillis = 0L;
                ScreenManager.getInstance().execute(() -> new Notification("Quick Server Join Failed", cleanMessage(e), Notification.Type.WARN));
            }
        });
    }

    private static void advanceOwnerTransfer() {
        IntegratedServer server = pendingOwnerTransferServer;
        if (!ownerTransferInProgress.get() || server == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!server.isShutdown()) {
            if (System.currentTimeMillis() > pendingOwnerTransferDeadlineMillis) {
                ownerTransferInProgress.set(false);
                pendingOwnerTransferServer = null;
                pendingOwnerTransferAddress = "";
                pendingOwnerTransferDeadlineMillis = 0L;
                pendingOwnerConnectAddress = "";
                pendingOwnerConnectAfterMillis = 0L;
                ownerConnectStarted.set(false);
                quickServerJoinCleanupUntilMillis = 0L;
                ScreenManager.getInstance().execute(() -> new Notification("Quick Server Join Failed", "World Did Not Close", Notification.Type.WARN));
            }
            return;
        }
        String address = pendingOwnerTransferAddress;
        pendingOwnerTransferServer = null;
        pendingOwnerTransferAddress = "";
        pendingOwnerTransferDeadlineMillis = 0L;
        MinecraftAccessor accessor = (MinecraftAccessor) minecraft;
        accessor.remotely$setSingleplayerServer(null);
        accessor.remotely$setLocalServer(false);
        pendingOwnerConnectAddress = address;
        pendingOwnerConnectAfterMillis = System.currentTimeMillis() + 250L;
        ownerConnectStarted.set(false);
        //#if MC >= 1.21.1
        minecraft.clearClientLevel(new QuickServerJoinScreen());
        //#else
        //$$ minecraft.clearLevel(new QuickServerJoinScreen());
        //#endif
    }

    private static void advanceOwnerConnect() {
        String address = pendingOwnerConnectAddress;
        if (address.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.getSingleplayerServer() != null || System.currentTimeMillis() < pendingOwnerConnectAfterMillis) {
            return;
        }
        if (!ownerConnectStarted.compareAndSet(false, true)) {
            return;
        }
        pendingOwnerConnectAddress = "";
        pendingOwnerConnectAfterMillis = 0L;
        connectFromJoinScreen(address);
    }

    private static void connectFromJoinScreen(String address) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            ServerAddress serverAddress = ServerAddress.parseString(address);
            //#if MC >= 1.21.1
            ServerData serverData = new ServerData("Quick Server", address, ServerData.Type.OTHER);
            //#else
            //$$ ServerData serverData = new ServerData("Quick Server", address, false);
            //#endif
            quickServerJoinCleanupUntilMillis = System.currentTimeMillis() + 120_000L;
            minecraft.prepareForMultiplayer();
            //#if MC >= 26.2
            minecraft.gui.setOverlay(null);
            //#else
            //$$ minecraft.setOverlay(null);
            //#endif
            //#if MC >= 1.21.1
            ConnectScreen.startConnecting(new TitleScreen(), minecraft, serverAddress, serverData, false, null);
            //#else
            //$$ ConnectScreen.startConnecting(new TitleScreen(), minecraft, serverAddress, serverData, false);
            //#endif
        } catch (Exception e) {
            ownerTransferInProgress.set(false);
            pendingOwnerConnectAddress = "";
            pendingOwnerConnectAfterMillis = 0L;
            ownerConnectStarted.set(false);
            quickServerJoinCleanupUntilMillis = 0L;
            ScreenManager.getInstance().execute(() -> new Notification("Quick Server Join Failed", cleanMessage(e), Notification.Type.WARN));
        }
    }

    private static void clearJoinHandoffScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pendingOwnerTransferServer != null || !pendingOwnerConnectAddress.isBlank()) {
            return;
        }
        if (ownerTransferInProgress.get() && !ownerConnectStarted.get()) {
            return;
        }
        if (!ownerTransferInProgress.get() && System.currentTimeMillis() > quickServerJoinCleanupUntilMillis) {
            return;
        }
        if (System.currentTimeMillis() > quickServerJoinCleanupUntilMillis) {
            ownerTransferInProgress.set(false);
            pendingOwnerConnectAddress = "";
            pendingOwnerConnectAfterMillis = 0L;
            ownerConnectStarted.set(false);
            quickServerJoinCleanupUntilMillis = 0L;
            return;
        }
        if (minecraft.level == null || minecraft.gameMode == null) {
            return;
        }
        //#if MC >= 26.2
        minecraft.gui.setOverlay(null);
        if (minecraft.gui.screen() != null) {
            minecraft.gui.setScreen(null);
        }
        //#else
        //$$ minecraft.setOverlay(null);
        //$$ if (minecraft.screen != null) {
        //$$     minecraft.setScreen(null);
        //$$ }
        //#endif
        ownerTransferInProgress.set(false);
        quickServerJoinCleanupUntilMillis = 0L;
    }

    private static String quickServerAddress(Instance instance) {
        String address = ReProxyManager.getForwardedAddress(JvmReProxyConnectorCapability.server(instance));
        if (address == null || address.isBlank()) {
            return "127.0.0.1:" + instance.getPort();
        }
        return address;
    }

    private static void copyAddress(String address) {
        if (address == null || address.isBlank()) {
            return;
        }
        ScreenManager.getInstance().execute(() -> RemotelyClient.INSTANCE.getHost().setClipboard(address));
    }

    private static void registerTerminalTab(Instance instance) {
        ScreenManager.getInstance().execute(() -> {
            var tabs = RemotelyClient.INSTANCE.getMultiTerminalTabs();
            boolean found = tabs.stream().anyMatch(tab -> tab instanceof Instance existing && existing.equals(instance));
            if (!found) {
                tabs.add(instance);
            }
            for (int i = 0; i < tabs.size(); i++) {
                Object tab = tabs.get(i);
                if (tab instanceof Instance existing && existing.equals(instance)) {
                    RemotelyClient.INSTANCE.setActiveMultiTerminalTabIndex(i);
                    return;
                }
            }
        });
    }

    private static void change(Notification notification, String message, String description, Notification.Type type, Runnable onClick, boolean loading) {
        ScreenManager.getInstance().execute(() -> {
            notification.change(message, description, type, onClick);
            notification.update().loading(loading).autoSlideOut(!loading);
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String cleanMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record WorldContext(String worldId, String worldName, Path worldPath) {
    }

    private record SourceRuntime(String versionId, ModLoader loader, String loaderVersion, Path instancePath, Path gameDir) {
    }

    private record ModEntry(long size, long modified) {
        private static ModEntry from(Path file) throws IOException {
            return new ModEntry(Files.size(file), Files.getLastModifiedTime(file).toMillis());
        }
    }

    private record ModCandidate(Path path, String fileName, String hash, long fingerprint) {
    }

    private record ModMirrorDecision(boolean serverCompatible, boolean known, String source) {
        private static ModMirrorDecision server(String source) {
            return new ModMirrorDecision(true, true, source);
        }

        private static ModMirrorDecision skip(String source) {
            return new ModMirrorDecision(false, true, source);
        }

        private static ModMirrorDecision unknown() {
            return new ModMirrorDecision(false, false, "Unknown");
        }
    }

    private enum LocalModSide {
        CLIENT_ONLY,
        SERVER_COMPATIBLE,
        UNKNOWN
    }

    private enum TomlSection {
        NONE,
        MOD,
        DEPENDENCY
    }

    private record TomlKeyValue(String key, String value) {
    }

    private static final class TomlScanState {
        private int modSections;
        private int modSideEntries;
        private int clientModSideEntries;
        private int serverCompatibleModSideEntries;
        private boolean clientBaseDependency;

        private boolean allDeclaredModsClientOnly() {
            return modSections > 0 && modSideEntries == modSections && clientModSideEntries == modSections;
        }

        private boolean allDeclaredModsServerCompatible() {
            return modSections > 0 && modSideEntries == modSections && serverCompatibleModSideEntries == modSections;
        }
    }

    private static final class TomlDependency {
        private String modId;
        private String side;

        private boolean isClientBaseDependency() {
            return modId != null && BASE_DEPENDENCY_MOD_IDS.contains(modId) && "client".equalsIgnoreCase(side);
        }
    }
}
