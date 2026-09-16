package redxax.oxy.remotely.flow.sync;

import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogCanonicalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NodePluginPayload {
    private String pluginId;
    private String version;
    private String description;
    private String checksum;
    private Map<String, Object> opaqueData = Map.of();
    private List<NodeDefinition> nodes = new ArrayList<>();

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public Map<String, Object> getOpaqueData() {
        return opaqueData != null ? opaqueData : Map.of();
    }

    public void setOpaqueData(Map<String, Object> opaqueData) {
        this.opaqueData = copyMap(opaqueData);
    }

    public boolean hasCanonicalCatalogPayload() {
        return opaqueData != null && (opaqueData.containsKey(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY)
            || opaqueData.get("catalogMetadata") instanceof Map<?, ?> metadata
            && metadata.containsKey(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY));
    }

    public boolean hasValidCanonicalCatalogPayload() {
        if (!hasCanonicalCatalogPayload()) {
            return true;
        }
        List<Object> candidates = new ArrayList<>();
        if (opaqueData.containsKey(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY)) {
            candidates.add(opaqueData.get(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY));
        }
        Object metadata = opaqueData.get("catalogMetadata");
        if (metadata instanceof Map<?, ?> values && values.containsKey(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY)) {
            candidates.add(values.get(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY));
        }
        String content = null;
        try {
            for (Object candidate : candidates) {
                if (!(candidate instanceof String value) || value.isBlank()
                    || content != null && !content.equals(value)) {
                    return false;
                }
                content = value;
            }
            Object parsed = CanonicalJson.parse(content);
            if (!(parsed instanceof Map<?, ?> document)
                || !CanonicalJson.canonicalize(parsed).equals(content)) {
                return false;
            }
            Object checksum = document.get("contentChecksum");
            return checksum instanceof String value
                && value.equals(CatalogCanonicalizer.checksumForCanonicalContent(content).canonicalText());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public List<NodeDefinition> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeDefinition> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Plugin metadata keys must be non-blank strings");
            }
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException("Plugin metadata map keys must be non-blank strings");
                }
                copy.put(key, copyValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Object[] items) {
            List<Object> copy = new ArrayList<>(items.length);
            for (Object item : items) {
                copy.add(copyValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof int[] items) {
            List<Object> copy = new ArrayList<>(items.length);
            for (int item : items) {
                copy.add(item);
            }
            return Collections.unmodifiableList(copy);
        }
        return String.valueOf(value);
    }
}
