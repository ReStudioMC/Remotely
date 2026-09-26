package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Uint8Array;
import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BrowserCatalogPublicationWorker {
    private static final int ENTRIES_PER_PART = 8;
    private static final int MAX_PART_BYTES = 512 * 1024;
    private static final int NODE_CHUNK_BYTES = 128 * 1024;
    private static final CatalogCachePublicationCodec CODEC = new CatalogCachePublicationCodec();

    private BrowserCatalogPublicationWorker() {
    }

    public static void main(String[] args) {
        listen();
    }

    public static void decode(int requestId, Uint8Array input) {
        try {
            byte[] canonicalBytes = copy(input);
            CatalogCachePublicationCodec.ValidatedPublication validated = CODEC.decodeValidatedPublication(canonicalBytes);
            CatalogCachePublication publication = validated.publication();
            List<CatalogCachePublication.Entry> entries = publication.entries();
            List<byte[]> parts = new ArrayList<>();
            int first = 0;
            do {
                int last = Math.min(first + ENTRIES_PER_PART, entries.size());
                byte[] encoded;
                do {
                    CatalogCachePublication part = new CatalogCachePublication(publication.kind(), publication.key(),
                        publication.catalogBinding(), publication.revision(), entries.subList(first, last),
                        parts.isEmpty() ? publication.authoringPublication() : null,
                        parts.isEmpty() ? publication.unknown() : Map.of());
                    encoded = CODEC.encodeBytes(part);
                    if (encoded.length <= MAX_PART_BYTES || last == first) {
                        break;
                    }
                    last--;
                } while (true);
                if (encoded.length > MAX_PART_BYTES
                    || last == first && first < entries.size() && !parts.isEmpty()) {
                    throw new IllegalArgumentException("Catalog publication part exceeds browser preparation limit");
                }
                parts.add(encoded);
                first = last;
            } while (first < entries.size());
            for (int index = 0; index < parts.size(); index++) {
                sendPart(requestId, index, parts.size(), bytes(parts.get(index)));
            }
            if (publication.authoringPublication() != null) {
                byte[] node = CODEC.encodeBytes(publication.withAuthoringPublication(null));
                for (int offset = 0; offset < node.length; offset += NODE_CHUNK_BYTES) {
                    int length = Math.min(NODE_CHUNK_BYTES, node.length - offset);
                    Uint8Array chunk = Uint8Array.create(length);
                    for (int index = 0; index < length; index++) {
                        chunk.set(index, (short) (node[offset + index] & 0xff));
                    }
                    sendNode(requestId, offset, node.length, chunk);
                }
            }
            sendCompleted(requestId, bytes(CanonicalDigests.sha256(canonicalBytes)));
        } catch (RuntimeException exception) {
            sendFailed(requestId, exception.getMessage() == null ? "Catalog publication validation failed"
                : exception.getMessage());
        }
    }

    private static byte[] copy(Uint8Array input) {
        byte[] result = new byte[input.getLength()];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) input.get(index);
        }
        return result;
    }

    private static Uint8Array bytes(byte[] value) {
        Uint8Array result = Uint8Array.create(value.length);
        result.set(value);
        return result;
    }

    @JSBody(script = """
        self.onmessage = event => {
            const request = event.data;
            javaMethods.get('redxax.oxy.remotely.web.platform.BrowserCatalogPublicationWorker.decode(ILorg/teavm/jso/typedarrays/Uint8Array;)V')
                .invoke(request.id, request.bytes);
        };
        """)
    private static native void listen();

    @JSBody(params = {"requestId", "index", "total", "value"}, script = """
        self.postMessage({type: 'part', id: requestId, index: index, total: total, bytes: value}, [value.buffer]);
        """)
    private static native void sendPart(int requestId, int index, int total, Uint8Array value);

    @JSBody(params = {"requestId", "offset", "total", "value"}, script = """
        self.postMessage({type: 'node', id: requestId, offset: offset, total: total, bytes: value}, [value.buffer]);
        """)
    private static native void sendNode(int requestId, int offset, int total, Uint8Array value);

    @JSBody(params = {"requestId", "value"}, script = """
        self.postMessage({type: 'completed', id: requestId, sha256: value}, [value.buffer]);
        """)
    private static native void sendCompleted(int requestId, Uint8Array value);

    @JSBody(params = {"requestId", "reason"}, script = """
        self.postMessage({type: 'failed', id: requestId, reason: reason});
        """)
    private static native void sendFailed(int requestId, String reason);
}
