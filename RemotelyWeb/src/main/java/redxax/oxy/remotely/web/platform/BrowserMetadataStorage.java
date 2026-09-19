package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.metadata.MetadataStorage;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.KeyValueStore;
import restudio.rescreen.platform.browser.BrowserKeyValueStore;
import restudio.resync.metadata.MetadataBundleId;

import java.nio.charset.StandardCharsets;

public final class BrowserMetadataStorage implements MetadataStorage {
    private final KeyValueStore store = BrowserKeyValueStore.local("remotely:metadata:v1");

    @Override
    public Async<byte[]> read(MetadataBundleId bundleId) {
        String value = store.read(bundleId.canonicalText());
        return Async.completed(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Async<Void> write(MetadataBundleId bundleId, byte[] bytes) {
        store.write(bundleId.canonicalText(), new String(bytes, StandardCharsets.UTF_8));
        return Async.completed(null);
    }
}
