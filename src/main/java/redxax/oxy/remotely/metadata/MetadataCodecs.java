package redxax.oxy.remotely.metadata;

import restudio.resync.metadata.MetadataArtifactFamily;
import restudio.resync.metadata.MinecraftRegistryBundle;
import restudio.resync.metadata.MinecraftRegistryBundleCodec;
import restudio.resync.metadata.ServerSettingsBundle;
import restudio.resync.metadata.ServerSettingsBundleCodec;
import restudio.resync.metadata.ServerSoftwareBundle;
import restudio.resync.metadata.ServerSoftwareBundleCodec;

import java.util.Map;

public final class MetadataCodecs {
    private MetadataCodecs() {
    }

    public static Map<MetadataArtifactFamily, MetadataBundleCodec<?>> standard() {
        return Map.of(
            MinecraftRegistryBundle.ARTIFACT_FAMILY, new MinecraftRegistry(),
            ServerSettingsBundle.ARTIFACT_FAMILY, new ServerSettings(),
            ServerSoftwareBundle.ARTIFACT_FAMILY, new ServerSoftware());
    }

    private static final class MinecraftRegistry implements MetadataBundleCodec<MinecraftRegistryBundle> {
        private final MinecraftRegistryBundleCodec codec = new MinecraftRegistryBundleCodec();

        @Override
        public MetadataArtifactFamily artifactFamily() {
            return MinecraftRegistryBundle.ARTIFACT_FAMILY;
        }

        @Override
        public int formatVersion() {
            return MinecraftRegistryBundle.CURRENT_FORMAT_VERSION;
        }

        @Override
        public MinecraftRegistryBundle decode(byte[] bytes) {
            return codec.decodeBytes(bytes);
        }

        @Override
        public byte[] encode(MinecraftRegistryBundle value) {
            return codec.encodeBytes(value);
        }
    }

    private static final class ServerSettings implements MetadataBundleCodec<ServerSettingsBundle> {
        private final ServerSettingsBundleCodec codec = new ServerSettingsBundleCodec();

        @Override
        public MetadataArtifactFamily artifactFamily() {
            return ServerSettingsBundle.ARTIFACT_FAMILY;
        }

        @Override
        public int formatVersion() {
            return ServerSettingsBundle.CURRENT_FORMAT_VERSION;
        }

        @Override
        public ServerSettingsBundle decode(byte[] bytes) {
            return codec.decodeBytes(bytes);
        }

        @Override
        public byte[] encode(ServerSettingsBundle value) {
            return codec.encodeBytes(value);
        }
    }

    private static final class ServerSoftware implements MetadataBundleCodec<ServerSoftwareBundle> {
        private final ServerSoftwareBundleCodec codec = new ServerSoftwareBundleCodec();

        @Override
        public MetadataArtifactFamily artifactFamily() {
            return ServerSoftwareBundle.ARTIFACT_FAMILY;
        }

        @Override
        public int formatVersion() {
            return ServerSoftwareBundle.CURRENT_FORMAT_VERSION;
        }

        @Override
        public ServerSoftwareBundle decode(byte[] bytes) {
            return codec.decodeBytes(bytes);
        }

        @Override
        public byte[] encode(ServerSoftwareBundle value) {
            return codec.encodeBytes(value);
        }
    }
}
