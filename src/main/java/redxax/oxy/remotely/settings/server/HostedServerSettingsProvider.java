package redxax.oxy.remotely.settings.server;

import redxax.oxy.remotely.metadata.MetadataRepository;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.ServerSettingsBundle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class HostedServerSettingsProvider implements AutoCloseable {
    private static final int SOURCE_SCHEMA_VERSION = 1;
    private final ServerSettingsRegistry registry;
    private final ServerSettingsMetadataReader parser;
    private final Map<MetadataBundleId, Admission> admissions = new LinkedHashMap<>();
    private State state;
    private boolean closed;

    public HostedServerSettingsProvider(ServerSettingsRegistry registry) {
        this(registry, new BrowserSafeYamlServerSettingsMetadataParser());
    }

    public HostedServerSettingsProvider(ServerSettingsRegistry registry, ServerSettingsMetadataReader parser) {
        this.registry = Objects.requireNonNull(registry, "Server settings registry is required");
        this.parser = Objects.requireNonNull(parser, "Server settings parser is required");
    }

    public synchronized State apply(MetadataRepository.Snapshot repositorySnapshot) {
        Objects.requireNonNull(repositorySnapshot, "Metadata repository snapshot is required");
        if (closed) {
            throw new IllegalStateException("Hosted server settings provider is closed");
        }
        MetadataRepository.Bundle admitted = repositorySnapshot.bundle(ServerSettingsBundle.ARTIFACT_FAMILY);
        if (admitted == null) {
            throw new IllegalArgumentException("Metadata snapshot does not contain server settings");
        }
        if (!(admitted.value() instanceof ServerSettingsBundle bundle)) {
            throw new IllegalArgumentException("Metadata snapshot contains invalid server settings");
        }
        MetadataBundleId bundleId = admitted.descriptor().bundleId();
        if (state != null && state.bundleId().equals(bundleId)) {
            if (!state.repositoryStamp().equals(repositorySnapshot.stamp())) {
                state = new State(repositorySnapshot.stamp(), bundleId, state.sources());
            }
            return state;
        }
        Admission admission = admissions.get(bundleId);
        if (admission == null) {
            LinkedHashMap<String, ServerSettingsMetadata> parsed = new LinkedHashMap<>();
            try {
                for (ServerSettingsBundle.Source source : bundle.sources()) {
                    parsed.put(source.id(), parse(source));
                }
                admission = Admission.success(parsed);
            } catch (RuntimeException failure) {
                admission = Admission.failure(failure);
            }
            admissions.put(bundleId, admission);
        }
        if (admission.failure() != null) {
            throw admission.failure();
        }
        State previous = state;
        State candidate = new State(repositorySnapshot.stamp(), bundleId, admission.sources());
        state = candidate;
        try {
            registry.replaceHosted(this, admission.sources());
        } catch (RuntimeException failure) {
            state = previous;
            throw failure;
        }
        return candidate;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized void clear() {
        state = null;
        admissions.clear();
        registry.clearHosted(this);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        clear();
    }

    private ServerSettingsMetadata parse(ServerSettingsBundle.Source source) {
        if (source.schemaVersion() != SOURCE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported hosted server settings schema: " + source.schemaVersion());
        }
        byte[] content = source.content().getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return parser.parse(input, "hosted:" + source.id());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read hosted server settings: " + source.id(), exception);
        }
    }

    private record Admission(Map<String, ServerSettingsMetadata> sources, RuntimeException failure) {
        private static Admission success(Map<String, ServerSettingsMetadata> sources) {
            return new Admission(Collections.unmodifiableMap(new LinkedHashMap<>(sources)), null);
        }

        private static Admission failure(RuntimeException failure) {
            return new Admission(Map.of(), Objects.requireNonNull(failure, "Hosted settings failure is required"));
        }
    }

    public record State(String repositoryStamp, MetadataBundleId bundleId, Map<String, ServerSettingsMetadata> sources) {
        public State {
            if (repositoryStamp == null || repositoryStamp.isBlank()) {
                throw new IllegalArgumentException("Metadata repository stamp is required");
            }
            bundleId = Objects.requireNonNull(bundleId, "Server settings bundle ID is required");
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("Hosted server settings need at least one source");
            }
        }

        public int packCount() {
            return sources.values().stream().mapToInt(metadata -> metadata.packs().size()).sum();
        }
    }
}
