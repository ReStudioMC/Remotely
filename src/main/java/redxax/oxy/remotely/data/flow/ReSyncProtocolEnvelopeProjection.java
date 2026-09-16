package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReSyncProtocolEnvelopeProjection {
    private static final String CORE_CAPABILITY_OWNER = "restudio.resync";
    private static final String RESOURCE_CAPABILITY = "resources";
    private static final String RESOURCE_ACTIVATION_CAPABILITY = "resource_activation";
    private static final String RESOURCE_ACTIVATION_REFERENCE = CORE_CAPABILITY_OWNER + "/" + RESOURCE_ACTIVATION_CAPABILITY;

    private ReSyncProtocolEnvelopeProjection() {
    }

    public static Projection project(ProtocolEnvelope<Map<String, Object>> envelope, Collection<String> supportedCapabilities) {
        Objects.requireNonNull(envelope, "Envelope is required");
        Set<String> supported = normalizeCapabilities(supportedCapabilities);
        Set<ContractRef<CapabilityId>> capabilities = copyCapabilities(envelope.capabilities());
        Set<ContractRef<CapabilityId>> unsupported = new LinkedHashSet<>();
        for (ContractRef<CapabilityId> capability : capabilities) {
            if (!isSupported(capability, supported)) {
                unsupported.add(capability);
            }
        }
        Map<String, Object> unknownBody = envelope.body() == null ? Map.of() : envelope.body().unknown();
        return new Projection(envelope, capabilities, unsupported, envelope.unknown(), unknownBody, document(envelope.body()));
    }

    public static CoreGraphResourceProjection.Projection projectCoreGraph(
        ProtocolEnvelope<Map<String, Object>> envelope, Collection<String> supportedCapabilities) {
        return CoreGraphResourceProjection.project(envelope, supportedCapabilities);
    }

    public static CoreGraphResourceProjection.PageProjection projectCoreGraphPage(
        ProtocolEnvelope<Map<String, Object>> envelope, Collection<String> supportedCapabilities) {
        return CoreGraphResourceProjection.projectPage(envelope, supportedCapabilities);
    }

    public static Set<String> genericResourceCapabilities(Collection<String> supportedCapabilities) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>(normalizeCapabilities(supportedCapabilities));
        capabilities.add(RESOURCE_CAPABILITY);
        capabilities.add(RESOURCE_ACTIVATION_CAPABILITY);
        capabilities.add(RESOURCE_ACTIVATION_REFERENCE);
        return Collections.unmodifiableSet(capabilities);
    }

    private static ResourceDocument<Map<String, Object>> document(ProtocolBody body) {
        if (body == null) {
            return null;
        }
        ResourceDocument<?> candidate = switch (body) {
            case ProtocolBody.ResourceDocumentResponse response -> response.document();
            case ProtocolBody.ConflictResponse response -> response.current();
            default -> null;
        };
        if (candidate == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        ResourceDocument<Map<String, Object>> document = (ResourceDocument<Map<String, Object>>) (ResourceDocument<?>) candidate;
        return document;
    }

    private static Set<String> normalizeCapabilities(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<ContractRef<CapabilityId>> copyCapabilities(Collection<ContractRef<CapabilityId>> values) {
        LinkedHashSet<ContractRef<CapabilityId>> copy = new LinkedHashSet<>();
        if (values != null) {
            copy.addAll(values);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static boolean isSupported(ContractRef<CapabilityId> capability, Set<String> supported) {
        return supported.contains(capability.canonicalText())
            || CORE_CAPABILITY_OWNER.equals(capability.owner().canonicalText()) && supported.contains(capability.id().value());
    }

    private static Map<String, Object> copyUnknown(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Unknown field names must be non-blank");
            }
            copy.put(key, copyValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Unknown object keys must be text");
                }
                copy.put(stringKey, copyValue(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }

    public record Projection(ProtocolEnvelope<Map<String, Object>> envelope,
                             Set<ContractRef<CapabilityId>> capabilities,
                             Set<ContractRef<CapabilityId>> unsupportedCapabilities,
                             Map<String, Object> unknownEnvelopeFields,
                             Map<String, Object> unknownBodyFields,
                             ResourceDocument<Map<String, Object>> resourceDocument) {
        public Projection {
            envelope = Objects.requireNonNull(envelope, "Envelope is required");
            capabilities = copyCapabilities(capabilities);
            unsupportedCapabilities = copyCapabilities(unsupportedCapabilities);
            if (!capabilities.containsAll(unsupportedCapabilities)) {
                throw new IllegalArgumentException("Unsupported capabilities must be present in the envelope capability set");
            }
            unknownEnvelopeFields = copyUnknown(unknownEnvelopeFields);
            unknownBodyFields = copyUnknown(unknownBodyFields);
        }

        public boolean hasUnsupportedCapabilities() {
            return !unsupportedCapabilities.isEmpty();
        }

        public boolean hasResourceDocument() {
            return resourceDocument != null;
        }

        public boolean canApplyTypedResource() {
            return hasResourceDocument() && !hasUnsupportedCapabilities();
        }
    }
}
