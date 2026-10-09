package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.servers.reproxy.ReProxyIntegrations;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.KeyValueStore;
import restudio.rescreen.platform.browser.BrowserKeyValueStore;

public final class BrowserIntegrationCatalog {
    private static Async<Void> initialized;

    private BrowserIntegrationCatalog() {
    }

    public static Async<Void> initialize() {
        if (initialized != null) return initialized;
        Async<Void> result = Async.pending();
        initialized = result;
        result.whenComplete((ignored, failure) -> {
            if (failure != null && initialized == result) initialized = null;
        });
        KeyValueStore storage = BrowserKeyValueStore.local("remotely.reproxy");
        String custom = storage.read("integrations.json");
        if (custom != null) {
            try {
                ReProxyIntegrations.load(custom);
                result.complete(null);
                return result;
            } catch (RuntimeException failure) {
                warn(failure.getMessage());
            }
        }
        fetch((content, error) -> {
            if (error != null && !error.isBlank()) {
                result.fail(new IllegalStateException(error));
                return;
            }
            try {
                ReProxyIntegrations.load(content);
                result.complete(null);
            } catch (RuntimeException failure) {
                result.fail(failure);
            }
        });
        return result;
    }

    @JSFunctor
    private interface Loaded extends JSObject {
        void accept(String content, String error);
    }

    @JSBody(params = {"loaded"}, script = """
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 10000);
            fetch(new URL('assets/remotely/reproxy/integrations.json', document.baseURI), {signal: controller.signal, cache: 'no-cache'})
                .then(response => {
                    if (!response.ok) throw new Error('Could Not Load Plugin Integrations');
                    const length = Number(response.headers.get('content-length') || 0);
                    if (length > 262144) throw new Error('Plugin Integration Catalog Is Too Large');
                    const reader = response.body.getReader();
                    const decoder = new TextDecoder();
                    let bytes = 0;
                    let content = '';
                    function read() {
                        return reader.read().then(chunk => {
                            if (chunk.done) return content + decoder.decode();
                            bytes += chunk.value.length;
                            if (bytes > 262144) {
                                return reader.cancel().then(() => { throw new Error('Plugin Integration Catalog Is Too Large'); });
                            }
                            content += decoder.decode(chunk.value, {stream: true});
                            return read();
                        });
                    }
                    return read();
                })
                .then(content => loaded(content, ''), error => loaded('', String(error && error.message || 'Could Not Load Plugin Integrations')))
                .finally(() => clearTimeout(timer));
            """)
    private static native void fetch(Loaded loaded);

    @JSBody(params = {"message"}, script = "console.warn('Custom Plugin Integrations Could Not Load. Using Bundled Defaults; The Custom Data Was Preserved', message);")
    private static native void warn(String message);
}
