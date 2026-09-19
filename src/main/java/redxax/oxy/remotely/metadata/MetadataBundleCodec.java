package redxax.oxy.remotely.metadata;

import restudio.resync.metadata.MetadataArtifactFamily;

public interface MetadataBundleCodec<T> {
    MetadataArtifactFamily artifactFamily();

    int formatVersion();

    T decode(byte[] bytes);

    byte[] encode(T value);
}
