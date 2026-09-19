package redxax.oxy.remotely.metadata;

import restudio.rescreen.platform.Async;
import restudio.resync.metadata.MetadataBundleId;

public interface MetadataStorage {
    Async<byte[]> read(MetadataBundleId bundleId);

    Async<Void> write(MetadataBundleId bundleId, byte[] bytes);

    static MetadataStorage none() {
        return new MetadataStorage() {
            @Override
            public Async<byte[]> read(MetadataBundleId bundleId) {
                return Async.completed(null);
            }

            @Override
            public Async<Void> write(MetadataBundleId bundleId, byte[] bytes) {
                return Async.completed(null);
            }
        };
    }
}
