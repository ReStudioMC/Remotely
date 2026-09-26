package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Uint8Array;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationDecoder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class BrowserCatalogPublicationDecoder implements ReSyncCatalogPublicationDecoder {
    private static final Map<Integer, BrowserCatalogPublicationDecoder> ACTIVE = new HashMap<>();
    private static int nextWorkerId = 1;

    private final Map<Integer, Callback> pending = new HashMap<>();
    private final int workerId;
    private int nextRequestId = 1;
    private boolean closed;

    public BrowserCatalogPublicationDecoder() {
        workerId = nextWorkerId++;
        if (!createWorker(workerId)) {
            throw new IllegalStateException("Catalog publication worker could not start");
        }
        ACTIVE.put(workerId, this);
    }

    @Override
    public void decode(byte[] canonicalBytes, Callback callback) {
        Objects.requireNonNull(canonicalBytes, "Canonical publication bytes are required");
        Objects.requireNonNull(callback, "Catalog publication callback is required");
        if (closed) {
            callback.failed("Catalog publication worker is closed");
            return;
        }
        int requestId = nextRequestId++;
        Uint8Array input = Uint8Array.create(canonicalBytes.length);
        input.set(canonicalBytes);
        pending.put(requestId, callback);
        if (!post(workerId, requestId, input)) {
            pending.remove(requestId);
            callback.failed("Catalog publication worker could not accept the publication");
        }
    }

    @Override
    public void cancelPending() {
        if (closed || pending.isEmpty()) {
            return;
        }
        terminate(workerId);
        ArrayList<Callback> callbacks = new ArrayList<>(pending.values());
        pending.clear();
        if (!createWorker(workerId)) {
            closed = true;
            ACTIVE.remove(workerId);
        }
        callbacks.forEach(callback -> callback.failed("Catalog publication work was retired"));
    }

    @Override
    public void close() {
        close("Catalog publication worker closed");
    }

    private void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        ACTIVE.remove(workerId);
        terminate(workerId);
        ArrayList<Callback> callbacks = new ArrayList<>(pending.values());
        pending.clear();
        callbacks.forEach(callback -> callback.failed(reason));
    }

    public static void part(int workerId, int requestId, int index, int total, Uint8Array bytes) {
        BrowserCatalogPublicationDecoder decoder = ACTIVE.get(workerId);
        if (decoder == null) {
            return;
        }
        Callback callback = decoder.pending.get(requestId);
        if (callback != null) {
            callback.part(index, total, copy(bytes));
        }
    }

    public static void completed(int workerId, int requestId, Uint8Array sha256) {
        BrowserCatalogPublicationDecoder decoder = ACTIVE.get(workerId);
        if (decoder == null) {
            return;
        }
        Callback callback = decoder.pending.remove(requestId);
        if (callback != null) {
            callback.completed(copy(sha256));
        }
    }

    public static void node(int workerId, int requestId, int offset, int total, Uint8Array bytes) {
        BrowserCatalogPublicationDecoder decoder = ACTIVE.get(workerId);
        if (decoder == null) {
            return;
        }
        Callback callback = decoder.pending.get(requestId);
        if (callback != null) {
            callback.node(offset, total, copy(bytes));
        }
    }

    public static void failed(int workerId, int requestId, String reason) {
        BrowserCatalogPublicationDecoder decoder = ACTIVE.get(workerId);
        if (decoder == null) {
            return;
        }
        Callback callback = decoder.pending.remove(requestId);
        if (callback != null) {
            callback.failed(reason);
        }
    }

    public static void workerFailed(int workerId, String reason) {
        BrowserCatalogPublicationDecoder decoder = ACTIVE.get(workerId);
        if (decoder != null) {
            decoder.close(reason == null ? "Catalog publication worker failed" : reason);
        }
    }

    private static byte[] copy(Uint8Array input) {
        byte[] result = new byte[input.getLength()];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) input.get(index);
        }
        return result;
    }

    @JSBody(params = "workerId", script = """
        let worker;
        try {
            const workers = window.__remotelyCatalogWorkers || (window.__remotelyCatalogWorkers = Object.create(null));
            worker = new Worker(new URL('js/remotely-catalog-worker-bootstrap.js', document.baseURI),
                {name: 'Reactor Catalog'});
            worker.onmessage = event => {
                const message = event.data;
                if (message.type === 'part') {
                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserCatalogPublicationDecoder.part(IIIILorg/teavm/jso/typedarrays/Uint8Array;)V')
                        .invoke(workerId, message.id, message.index, message.total, message.bytes);
                } else if (message.type === 'node') {
                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserCatalogPublicationDecoder.node(IIIILorg/teavm/jso/typedarrays/Uint8Array;)V')
                        .invoke(workerId, message.id, message.offset, message.total, message.bytes);
                } else if (message.type === 'completed') {
                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserCatalogPublicationDecoder.completed(IILorg/teavm/jso/typedarrays/Uint8Array;)V')
                        .invoke(workerId, message.id, message.sha256);
                } else if (message.type === 'failed') {
                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserCatalogPublicationDecoder.failed(IILjava/lang/String;)V')
                        .invoke(workerId, message.id, message.reason);
                }
            };
            worker.onerror = event => {
                javaMethods.get('redxax.oxy.remotely.web.platform.BrowserCatalogPublicationDecoder.workerFailed(ILjava/lang/String;)V')
                    .invoke(workerId, event.message || 'Catalog publication worker failed');
            };
            workers[workerId] = worker;
            return true;
        } catch (error) {
            if (worker) worker.terminate();
            return false;
        }
        """)
    private static native boolean createWorker(int workerId);

    @JSBody(params = {"workerId", "requestId", "input"}, script = """
        const workers = window.__remotelyCatalogWorkers;
        const worker = workers && workers[workerId];
        if (!worker) return false;
        try {
            worker.postMessage({id: requestId, bytes: input}, [input.buffer]);
            return true;
        } catch (error) {
            return false;
        }
        """)
    private static native boolean post(int workerId, int requestId, Uint8Array input);

    @JSBody(params = "workerId", script = """
        const workers = window.__remotelyCatalogWorkers;
        const worker = workers && workers[workerId];
        if (worker) {
            worker.terminate();
            delete workers[workerId];
        }
        """)
    private static native void terminate(int workerId);
}
