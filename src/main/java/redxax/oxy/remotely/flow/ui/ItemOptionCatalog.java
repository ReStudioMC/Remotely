package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ItemOptionCatalog {
    public static final String SOURCE = "server:custom_content:recipe_item";
    private static final String MATERIAL_SOURCE = "server:minecraft:material";
    private static final OptionCatalogLoader.Profile CATALOGS = OptionCatalogLoader.profile(SOURCE, MATERIAL_SOURCE);

    private ItemOptionCatalog() {
    }

    public static void ensureLoaded(String serverId) {
        CATALOGS.preload(serverId);
    }

    public static void refresh(String serverId) {
        CATALOGS.refresh(serverId);
    }

    public static boolean isReady(String serverId) {
        return serverId != null
            && OptionCatalogCache.getInstance().hasCatalog(serverId, SOURCE)
            && OptionCatalogCache.getInstance().hasCatalog(serverId, MATERIAL_SOURCE);
    }

    public static boolean isRefreshing(String serverId) {
        if (serverId == null) {
            return false;
        }
        OptionCatalogCache cache = OptionCatalogCache.getInstance();
        return catalogRefreshing(cache, serverId, SOURCE) || catalogRefreshing(cache, serverId, MATERIAL_SOURCE);
    }

    public static List<String> mergedValues(String serverId) {
        ensureLoaded(serverId);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        List<String> serverValues = OptionCatalogCache.getInstance().getValues(serverId, SOURCE);
        for (String value : serverValues) {
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        for (String material : materialOptions(serverId)) {
            if (material != null && !material.isBlank()) {
                values.add(material);
            }
        }
        return new ArrayList<>(values);
    }

    public static ItemSelectorWidget.AsyncItemSnapshot selectorSnapshot(String serverId, Supplier<String> selectedSupplier,
        Consumer<String> onSelected, boolean includeNone) {
        ensureLoaded(serverId);
        List<String> values = mergedValues(serverId);
        Map<String, OptionCatalogItem> catalog = byValue(serverId);
        List<ItemSelectorWidget.AsyncItem> items = new ArrayList<>();
        if (includeNone) {
            items.add(new ItemSelectorWidget.AsyncItem("none", "", "none empty clear", onSelected != null ? () -> onSelected.accept("none") : null));
        }
        Map<String, List<String>> valuesByGroup = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank() || "Loading".equals(value)) {
                continue;
            }
            OptionCatalogItem item = catalog.get(value);
            String group = groupForValue(value, item);
            valuesByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(value);
        }
        for (Map.Entry<String, List<String>> entry : valuesByGroup.entrySet()) {
            String group = entry.getKey();
            for (String value : entry.getValue()) {
                OptionCatalogItem item = catalog.get(value);
                String label = item != null ? item.getLabel() : label(serverId, value);
                String icon = item != null ? item.getIcon() : "";
                String description = item != null ? item.getDescription() : "";
                String searchTerms = String.join(" ", value, group, description);
                items.add(new ItemSelectorWidget.AsyncItem(label, icon, description, searchTerms, group,
                    onSelected != null ? () -> onSelected.accept(value) : null));
            }
        }
        String selected = selectedSupplier != null ? selectedSupplier.get() : "";
        if (selected != null && !selected.isBlank() && !"none".equalsIgnoreCase(selected) && !"Loading".equals(selected)
            && values.stream().noneMatch(selected::equals)) {
            items.add(new ItemSelectorWidget.AsyncItem(label(serverId, selected), "", selected,
                onSelected != null ? () -> onSelected.accept(selected) : null));
        }
        return new ItemSelectorWidget.AsyncItemSnapshot(items, isRefreshing(serverId), "No Items");
    }

    public static ItemSelectorWidget.AsyncItemSource selectorSource(String serverId, Supplier<String> selectedSupplier,
        Consumer<String> onSelected, boolean includeNone) {
        return new ItemSelectorWidget.AsyncItemSource() {
            private long recipeRevision = Long.MIN_VALUE;
            private long materialRevision = Long.MIN_VALUE;
            private boolean loading;
            private String selected;
            private ItemSelectorWidget.AsyncItemSnapshot resident;

            @Override
            public ItemSelectorWidget.AsyncItemSnapshot snapshot() {
                ensureLoaded(serverId);
                OptionCatalogCache cache = OptionCatalogCache.getInstance();
                long nextRecipeRevision = cache.legacyRevision(serverId, SOURCE);
                long nextMaterialRevision = cache.legacyRevision(serverId, MATERIAL_SOURCE);
                boolean nextLoading = isRefreshing(serverId);
                String nextSelected = selectedSupplier != null ? selectedSupplier.get() : "";
                if (resident == null || recipeRevision != nextRecipeRevision || materialRevision != nextMaterialRevision
                    || loading != nextLoading || !Objects.equals(selected, nextSelected)) {
                    resident = selectorSnapshot(serverId, () -> nextSelected, onSelected, includeNone);
                    recipeRevision = nextRecipeRevision;
                    materialRevision = nextMaterialRevision;
                    loading = nextLoading;
                    selected = nextSelected;
                }
                return resident;
            }
        };
    }

    public static Map<String, OptionCatalogItem> byValue(String serverId) {
        Map<String, OptionCatalogItem> map = new LinkedHashMap<>();
        for (OptionCatalogItem item : OptionCatalogCache.getInstance().getItems(serverId, SOURCE)) {
            if (item == null) {
                continue;
            }
            String value = item.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            map.putIfAbsent(value, item);
        }
        for (OptionCatalogItem item : OptionCatalogCache.getInstance().getItems(serverId, MATERIAL_SOURCE)) {
            if (item == null) {
                continue;
            }
            String value = item.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            map.putIfAbsent(value, item);
        }
        return map;
    }

    public static String groupForValue(String value, OptionCatalogItem item) {
        if (item != null && !item.getGroup().isBlank()) {
            String group = item.getGroup();
            return group.contains("_") ? formatGroupLabel(group) : group;
        }
        if (value.startsWith("content:")) {
            return "ReSync";
        }
        if (value.startsWith("provider:")) {
            return providerGroupLabel(value);
        }
        return "Vanilla";
    }

    public static String label(String serverId, String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        for (OptionCatalogItem item : OptionCatalogCache.getInstance().getItems(serverId, SOURCE)) {
            if (value.equals(item.getValue())) {
                return item.getLabel();
            }
        }
        for (OptionCatalogItem item : OptionCatalogCache.getInstance().getItems(serverId, MATERIAL_SOURCE)) {
            if (value.equals(item.getValue())) {
                return item.getLabel();
            }
        }
        if (value.startsWith("provider:")) {
            int split = value.lastIndexOf(':');
            if (split > 0 && split < value.length() - 1) {
                return value.substring(split + 1);
            }
        }
        return formatOptionLabel(value);
    }

    public static String formatOptionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String cleaned = value.trim().replace("minecraft:", "").replace('_', ' ').replace('-', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? value : builder.toString();
    }

    private static String providerGroupLabel(String value) {
        String rest = value.substring("provider:".length());
        int split = rest.indexOf(':');
        if (split <= 0) {
            return "Providers";
        }
        return formatGroupLabel(rest.substring(0, split) + "_item");
    }

    private static String formatGroupLabel(String group) {
        String label = formatOptionLabel(group);
        return label + "s";
    }

    private static List<String> materialOptions(String serverId) {
        return catalogValues(serverId, MATERIAL_SOURCE);
    }

    private static List<String> catalogValues(String serverId, String source) {
        return OptionCatalogLoader.snapshot(serverId, source).values();
    }

    private static boolean catalogRefreshing(OptionCatalogCache cache, String serverId, String source) {
        return !cache.hasCatalog(serverId, source) || cache.isStale(serverId, source, "") || cache.isRequestInFlight(serverId, source);
    }
}
