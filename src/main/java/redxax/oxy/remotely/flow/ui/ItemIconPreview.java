package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import restudio.rescreen.game.MinecraftGameItems;
import restudio.rescreen.game.MinecraftRenderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ItemIconPreview {
    private static final String MATERIAL_OPTIONS_SOURCE = "server:minecraft:material";
    private static final String CUSTOM_CONTENT_ASSET_SOURCE = "server:custom_content:asset";
    private static final int PROJECTION_CACHE_MAX_ENTRIES = 512;
    private static final long PROJECTION_CACHE_MAX_BYTES = 1_048_576L;
    private static final long PROJECTION_CACHE_TTL_MS = 300_000L;
    private static final int PROJECTION_CACHE_VALUE_MAX_CHARS = 512;
    private static final BoundedAssetCache<ProjectionKey, Projection> PROJECTION_CACHE = new BoundedAssetCache<>(
        PROJECTION_CACHE_MAX_ENTRIES, PROJECTION_CACHE_MAX_BYTES, PROJECTION_CACHE_TTL_MS, ignored -> {
        });

    public record Preview(String material, Integer customModelData, Map<String, Object> components) {
        public Preview {
            material = material != null && !material.isBlank() ? material : "stone";
            components = components != null ? Map.copyOf(components) : Map.of();
        }

        public MinecraftRenderItem toRenderItem(String name) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", material);
            if (customModelData != null) {
                map.put("customModelData", customModelData);
            }
            if (!components.isEmpty()) {
                map.put("tag", Map.of("components", components));
            }
            MinecraftRenderItem item = MinecraftGameItems.fromMap(map);
            if (item != null) {
                return item;
            }
            return MinecraftGameItems.fromVisual(material, 1, name, List.of(), customModelData);
        }
    }

    public record Projection(Preview preview, String label) {
        public Projection {
            preview = preview != null ? preview : new Preview("stone", null, Map.of());
            label = label != null && !label.isBlank() ? label : preview.material();
        }

        public MinecraftRenderItem toRenderItem() {
            return preview.toRenderItem(label);
        }
    }

    private ItemIconPreview() {
    }

    public static Preview resolve(String serverId, String value) {
        return project(serverId, value).preview();
    }

    public static String label(String serverId, String value) {
        return project(serverId, value).label();
    }

    public static Projection project(String serverId, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return new Projection(new Preview("stone", null, Map.of()), "none");
        }
        CatalogState catalogs = catalogState(serverId);
        ProjectionKey key = new ProjectionKey(serverId, normalized, catalogs.revision(), customContentRevision(serverId, normalized));
        if (normalized.length() <= PROJECTION_CACHE_VALUE_MAX_CHARS) {
            Projection cached = PROJECTION_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Projection projection = resolveProjection(serverId, normalized, catalogs);
        if (normalized.length() <= PROJECTION_CACHE_VALUE_MAX_CHARS) {
            PROJECTION_CACHE.put(key, projection, projectionBytes(projection));
        }
        return projection;
    }

    private static long customContentRevision(String serverId, String value) {
        if (serverId == null || !value.startsWith("content:")) {
            return 0L;
        }
        String contentId = value.substring("content:".length());
        if (contentId.isBlank()) {
            return 0L;
        }
        FlowManager manager = FlowManager.getInstance();
        return manager != null ? manager.getCustomContentRevision(serverId, contentId) : 0L;
    }

    private static Projection resolveProjection(String serverId, String value, CatalogState catalogs) {
        OptionCatalogItem catalogItem = catalogs.find(value);
        Preview preview = catalogItem == null ? null : fromCatalogItem(catalogItem);
        String label = catalogItem == null ? fallbackLabel(value) : catalogItem.getLabel();
        if (preview != null) {
            return new Projection(preview, label);
        }
        if (value.startsWith("content:")) {
            preview = fromContent(serverId, value.substring("content:".length()), catalogs);
        } else if (value.startsWith("provider:")) {
            preview = fromProviderReference(value, catalogs);
        } else {
            preview = fromVanilla(value);
        }
        return new Projection(preview, label);
    }

    private static CatalogState catalogState(String serverId) {
        OptionCatalogCache cache = OptionCatalogCache.getInstance();
        OptionCatalogCache.CatalogLookup recipe = cache.lookup(serverId, ItemOptionCatalog.SOURCE);
        OptionCatalogCache.CatalogLookup material = cache.lookup(serverId, MATERIAL_OPTIONS_SOURCE);
        OptionCatalogCache.CatalogLookup custom = cache.lookupAcrossContexts(serverId, CUSTOM_CONTENT_ASSET_SOURCE);
        long revision = mixRevision(recipe.revision(), material.revision(), custom.revision());
        return new CatalogState(revision, recipe, material, custom);
    }

    private static long mixRevision(long first, long second, long third) {
        long result = 17L;
        result = 31L * result + first;
        result = 31L * result + second;
        return 31L * result + third;
    }

    private static Preview fromCatalogItem(OptionCatalogItem item) {
        Preview metadata = fromMetadata(item.getMetadata());
        if (metadata != null) {
            return metadata;
        }
        String icon = item.getIcon();
        return icon.isBlank() ? null : fromVanilla(icon);
    }

    private static Map<String, Object> componentMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> components = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                components.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return components;
    }

    private static Object firstPresent(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("value");
            if (nested == null) {
                nested = map.get("model");
            }
            if (nested == null) {
                nested = map.get("data");
            }
            if (nested == null) {
                nested = map.get("floats");
            }
            if (nested == null) {
                nested = map.get("values");
            }
            return integer(nested);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : integer(list.getFirst());
        }
        return null;
    }

    private static Preview fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object materialValue = firstPresent(metadata, "material", "item", "id", "minecraftMaterial", "baseMaterial");
        if (materialValue == null || materialValue.toString().isBlank()) {
            return null;
        }
        Integer customModelData = integer(firstPresent(metadata, "customModelData", "custom_model_data", "modelData", "model_data", "cmd"));
        Map<String, Object> components = componentMap(metadata.get("components"));
        return new Preview(materialValue.toString(), customModelData, components);
    }

    private static Preview fromContent(String serverId, String contentId, CatalogState catalogs) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null) {
            return new Preview("BARRIER", null, Map.of());
        }
        CustomContentDefinition content = manager.getCustomContent(serverId, contentId);
        if (content == null) {
            return new Preview("BARRIER", null, Map.of());
        }
        String provider = content.getProvider();
        String externalId = content.getExternalId();
        if (provider != null && !provider.isBlank() && externalId != null && !externalId.isBlank()) {
            String providerReference = "provider:" + provider.toLowerCase(Locale.ROOT) + ":" + externalId;
            OptionCatalogItem linkedItem = catalogs.find(providerReference);
            Preview linked = linkedItem == null ? null : fromCatalogItem(linkedItem);
            if (linked != null) {
                return linked;
            }
        }
        String material = content.getMaterial();
        if (material == null || material.isBlank()) {
            material = "BARRIER";
        }
        return new Preview(material, content.getCustomModelData(), Map.of());
    }

    private static Preview fromProviderReference(String value, CatalogState catalogs) {
        OptionCatalogItem item = catalogs.find(value);
        Preview preview = item == null ? null : fromCatalogItem(item);
        return preview != null ? preview : new Preview("PAPER", null, Map.of());
    }

    private static Preview fromVanilla(String value) {
        String material = value.trim();
        if (material.startsWith("minecraft:")) {
            material = material.substring("minecraft:".length());
        }
        if (material.contains(":")) {
            return new Preview(material, null, Map.of());
        }
        return new Preview(material.toUpperCase(Locale.ROOT), null, Map.of());
    }

    private static String fallbackLabel(String value) {
        if (value.startsWith("provider:")) {
            int split = value.lastIndexOf(':');
            if (split > 0 && split < value.length() - 1) {
                return value.substring(split + 1);
            }
        }
        return ItemOptionCatalog.formatOptionLabel(value);
    }

    private static long projectionBytes(Projection projection) {
        long bytes = stringBytes(projection.label()) + stringBytes(projection.preview().material());
        bytes += projection.preview().components().size() * 64L;
        return Math.max(1L, Math.min(Long.MAX_VALUE, bytes));
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : (long) value.length() * Character.BYTES;
    }

    private record CatalogState(long revision, OptionCatalogCache.CatalogLookup recipe,
                                OptionCatalogCache.CatalogLookup material, OptionCatalogCache.CatalogLookup custom) {
        private OptionCatalogItem find(String value) {
            OptionCatalogItem item = recipe.get(value);
            if (item == null) {
                item = custom.get(value);
            }
            if (item == null) {
                item = material.get(value);
            }
            return item;
        }
    }

    private record ProjectionKey(String serverId, String value, long catalogRevision, long contentRevision) {
    }
}
