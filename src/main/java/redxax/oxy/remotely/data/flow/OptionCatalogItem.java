package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class OptionCatalogItem {
    private static final String CUSTOM_DATA = "minecraft:custom_data";
    private String value;
    private String label;
    private String description;
    private String icon;
    private String group;
    private Map<String, Object> metadata;
    private String resourceServerId;
    private String resourceOwnerId;
    private String resourceTypeId;
    private String resourceId;

    public void setValue(String value) {
        if (value != null && !value.isBlank() && hasResourceIdentity()) {
            throw new IllegalStateException("A resource option cannot also carry a string value");
        }
        this.value = value;
    }

    public void setResource(ServerResourceLocator resource) {
        if (resource == null) {
            resourceServerId = null;
            resourceOwnerId = null;
            resourceTypeId = null;
            resourceId = null;
            return;
        }
        if (value != null && !value.isBlank()) {
            throw new IllegalStateException("A string option cannot also carry a resource locator");
        }
        resourceServerId = resource.serverId().canonicalText();
        resourceOwnerId = resource.owner().canonicalText();
        resourceTypeId = resource.resourceType().value();
        resourceId = resource.id();
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getValue() {
        return value;
    }

    public ServerResourceLocator getResource() {
        if (!hasResourceIdentity()) {
            return null;
        }
        if (blank(resourceServerId) || blank(resourceOwnerId) || blank(resourceTypeId) || blank(resourceId)) {
            throw new IllegalStateException("A resource option requires server, owner, type, and ID");
        }
        return new ServerResourceLocator(ServerId.parseCanonicalText(resourceServerId),
            ContractRef.of(OwnerId.of(resourceOwnerId), ResourceTypeId.of(resourceTypeId)), resourceId);
    }

    public boolean isResource() {
        return getResource() != null;
    }

    public boolean isAvailable() {
        return !Boolean.FALSE.equals(getMetadata().get("available"));
    }

    public String getLabel() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        ServerResourceLocator resource = getResource();
        return value != null ? value : resource != null ? resource.id() : "";
    }

    public String getDescription() {
        return description != null ? description : "";
    }

    public String getIcon() {
        return icon != null ? icon : "";
    }

    public String getGroup() {
        return group != null ? group : "";
    }

    public Map<String, Object> getMetadata() {
        return metadata != null ? metadata : Map.of();
    }

    public OptionCatalogItem unavailable(String reason) {
        OptionCatalogItem copy = copy();
        Map<String, Object> unavailable = new LinkedHashMap<>(copy.getMetadata());
        unavailable.put("available", false);
        if (reason != null && !reason.isBlank()) {
            unavailable.put("unavailableReason", reason);
        }
        copy.setMetadata(unavailable);
        return copy;
    }

    public OptionCatalogItem copy() {
        OptionCatalogItem copy = new OptionCatalogItem();
        if (hasResourceIdentity()) {
            copy.setResource(getResource());
        } else {
            copy.setValue(value);
        }
        copy.setLabel(label);
        copy.setDescription(description);
        copy.setIcon(icon);
        copy.setGroup(group);
        copy.setMetadata(new LinkedHashMap<>(getMetadata()));
        return copy;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof OptionCatalogItem item)) {
            return false;
        }
        return Objects.equals(getValue(), item.getValue())
            && Objects.equals(getResource(), item.getResource())
            && Objects.equals(getLabel(), item.getLabel())
            && Objects.equals(getDescription(), item.getDescription())
            && Objects.equals(getIcon(), item.getIcon())
            && Objects.equals(getGroup(), item.getGroup())
            && Objects.equals(stableMetadata(), item.stableMetadata());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getValue(), getResource(), getLabel(), getDescription(), getIcon(), getGroup(), stableMetadata());
    }

    private boolean hasResourceIdentity() {
        return !blank(resourceServerId) || !blank(resourceOwnerId) || !blank(resourceTypeId) || !blank(resourceId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> stableMetadata() {
        Map<String, Object> stable = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getMetadata().entrySet()) {
            stable.put(entry.getKey(), stableValue(entry.getValue(), "components".equals(entry.getKey())));
        }
        return stable;
    }

    private Object stableValue(Object value, boolean components) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stable = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!components || !CUSTOM_DATA.equals(key)) {
                    stable.put(key, stableValue(entry.getValue(), false));
                }
            }
            return stable;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(entry -> stableValue(entry, false)).toList();
        }
        List<Object> arrayValues = arrayValues(value);
        if (arrayValues != null) {
            List<Object> stable = new ArrayList<>(arrayValues.size());
            for (Object entry : arrayValues) {
                stable.add(stableValue(entry, false));
            }
            return stable;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros();
            } catch (NumberFormatException ignored) {
                return number.doubleValue();
            }
        }
        return value;
    }

    private static List<Object> arrayValues(Object value) {
        if (value instanceof Object[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (Object item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof int[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (int item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof long[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (long item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof double[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (double item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof boolean[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (boolean item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof float[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (float item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof short[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (short item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof byte[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (byte item : items) {
                values.add(item);
            }
            return values;
        }
        if (value instanceof char[] items) {
            List<Object> values = new ArrayList<>(items.length);
            for (char item : items) {
                values.add(item);
            }
            return values;
        }
        return null;
    }
}
