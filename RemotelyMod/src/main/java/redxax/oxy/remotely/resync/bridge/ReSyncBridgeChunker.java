package redxax.oxy.remotely.resync.bridge;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ReSyncBridgeChunker {
    public interface PacketSink {
        void send(ReSyncBridgeEnvelope envelope);
    }

    private static final int CHUNK_SIZE = 24_000;
    private static final int MAX_REASSEMBLED_BYTES = 4_194_304;
    private static final int MAX_PENDING_SEQUENCES = 256;
    private static final long MAX_PENDING_BYTES = 8L * 1_024L * 1_024L;
    private static final long TIMEOUT_MS = 10_000;
    private final Map<String, PendingChunks> pending = new HashMap<>();
    private long pendingBytes;

    public void send(UUID sessionId, int sequence, byte type, byte[] payload, PacketSink sink) {
        byte[] data = payload == null ? new byte[0] : payload;
        if (data.length > MAX_REASSEMBLED_BYTES) {
            throw new IllegalArgumentException("Bridge payload too large");
        }
        int count = Math.max(1, (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK_SIZE;
            int end = Math.min(data.length, start + CHUNK_SIZE);
            byte[] chunk = new byte[end - start];
            System.arraycopy(data, start, chunk, 0, chunk.length);
            sink.send(new ReSyncBridgeEnvelope(ReSyncBridgeEnvelope.PROTOCOL, type, sessionId, sequence, index, count, chunk));
        }
    }

    public int chunkCount(int payloadBytes) {
        if (payloadBytes < 0 || payloadBytes > MAX_REASSEMBLED_BYTES) {
            throw new IllegalArgumentException("Bridge payload too large");
        }
        return Math.max(1, (payloadBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    public byte[] accept(ReSyncBridgeEnvelope envelope) {
        cleanup();
        if (envelope.chunkCount() <= 0 || envelope.chunkIndex() < 0 || envelope.chunkIndex() >= envelope.chunkCount()) {
            throw new IllegalArgumentException("Invalid bridge chunk");
        }
        if (envelope.chunkCount() > (MAX_REASSEMBLED_BYTES + CHUNK_SIZE - 1) / CHUNK_SIZE || envelope.payload().length > CHUNK_SIZE) {
            throw new IllegalArgumentException("Bridge payload too large");
        }
        if (envelope.chunkCount() == 1) {
            if (envelope.payload().length > MAX_REASSEMBLED_BYTES) {
                throw new IllegalArgumentException("Bridge payload too large");
            }
            return envelope.payload();
        }
        String key = envelope.sessionId() + ":" + envelope.sequence() + ":" + envelope.type();
        PendingChunks chunks = pending.get(key);
        if (chunks == null) {
            if (pending.size() >= MAX_PENDING_SEQUENCES) {
                throw new IllegalArgumentException("Too many pending bridge sequences");
            }
            chunks = new PendingChunks(envelope.chunkCount());
            pending.put(key, chunks);
        }
        if (chunks.count() != envelope.chunkCount()) {
            pending.remove(key);
            pendingBytes -= chunks.retainedBytes();
            throw new IllegalArgumentException("Invalid bridge chunk sequence");
        }
        long delta = chunks.retainedDelta(envelope.chunkIndex(), envelope.payload());
        if (pendingBytes + delta > MAX_PENDING_BYTES) {
            pending.remove(key);
            pendingBytes -= chunks.retainedBytes();
            throw new IllegalArgumentException("Pending bridge payload limit exceeded");
        }
        chunks.put(envelope.chunkIndex(), envelope.payload());
        pendingBytes += delta;
        if (!chunks.complete()) {
            return null;
        }
        pending.remove(key);
        pendingBytes -= chunks.retainedBytes();
        return chunks.join();
    }

    public void clear() {
        pending.clear();
        pendingBytes = 0L;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingChunks>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingChunks chunks = iterator.next().getValue();
            if (now - chunks.createdAt > TIMEOUT_MS) {
                pendingBytes -= chunks.retainedBytes();
                iterator.remove();
            }
        }
    }

    private static class PendingChunks {
        private final byte[][] chunks;
        private final long createdAt = System.currentTimeMillis();
        private int received;
        private long retainedBytes;

        private PendingChunks(int count) {
            chunks = new byte[count][];
        }

        private void put(int index, byte[] payload) {
            byte[] value = payload == null ? new byte[0] : payload;
            byte[] previous = chunks[index];
            if (chunks[index] == null) {
                received++;
            }
            retainedBytes += value.length - (previous == null ? 0 : previous.length);
            chunks[index] = value;
        }

        private long retainedDelta(int index, byte[] payload) {
            byte[] previous = chunks[index];
            return (payload == null ? 0 : payload.length) - (previous == null ? 0 : previous.length);
        }

        private boolean complete() {
            return received == chunks.length;
        }

        private int count() {
            return chunks.length;
        }

        private long retainedBytes() {
            return retainedBytes;
        }

        private byte[] join() {
            int total = 0;
            for (byte[] chunk : chunks) {
                total += chunk.length;
                if (total > MAX_REASSEMBLED_BYTES) {
                    throw new IllegalArgumentException("Bridge payload too large");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            for (byte[] chunk : chunks) {
                out.writeBytes(chunk);
            }
            return out.toByteArray();
        }
    }
}
