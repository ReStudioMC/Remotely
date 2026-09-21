package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class OptionCatalogSelector {
    private OptionCatalogSelector() {
    }

    public static Runnable refreshAction(String serverId, String source) {
        return refreshAction(serverId, source, Map.of());
    }

    public static Runnable refreshAction(String serverId, String source, Map<String, Object> context) {
        Map<String, Object> safeContext = OptionCatalogLoader.request(source, context).context();
        return () -> OptionCatalogLoader.refresh(serverId, source, safeContext);
    }

    public static Runnable refreshAction(OptionCatalogLoader.CoreRequest request) {
        return () -> OptionCatalogLoader.refresh(request);
    }

    public static Runnable refreshAction(Supplier<Optional<OptionCatalogLoader.CoreRequest>> requestSupplier) {
        return () -> request(requestSupplier).ifPresent(OptionCatalogLoader::refresh);
    }

    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(
        Supplier<Optional<OptionCatalogLoader.CoreRequest>> requestSupplier, Supplier<TypedValue> selectedSupplier,
        Consumer<TypedValue> onSelected, String emptyMessage) {
        OptionCatalogLoader.CoreRequest request = request(requestSupplier).orElse(null);
        return request == null
            ? new ItemSelectorWidget.AsyncItemSnapshot(List.of(), true, "Loading Options")
            : snapshot(request, selectedSupplier, onSelected, emptyMessage);
    }

    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(OptionCatalogLoader.CoreRequest request,
        Supplier<TypedValue> selectedSupplier, Consumer<TypedValue> onSelected, String emptyMessage) {
        return snapshot(OptionCatalogLoader.snapshot(request), selectedSupplier, onSelected, emptyMessage);
    }

    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(OptionCatalogLoader.CoreSnapshot catalog,
        Supplier<TypedValue> selectedSupplier, Consumer<TypedValue> onSelected, String emptyMessage) {
        Map<TypedValue, OptionItem> options = new LinkedHashMap<>();
        if (catalog != null) {
            for (OptionItem item : catalog.items()) {
                options.putIfAbsent(item.value(), item);
            }
        }
        List<ItemSelectorWidget.AsyncItem> items = new ArrayList<>();
        for (OptionItem item : options.values()) {
            TypedValue value = item.value();
            String identity = value.canonicalJson();
            String hint = item.description();
            if (!item.available() && item.reason() != null) {
                hint = hint.isBlank() ? item.reason() : hint + " | " + item.reason();
            }
            Runnable action = item.available() && onSelected != null ? () -> onSelected.accept(value) : null;
            items.add(new ItemSelectorWidget.AsyncItem(item.label(), "", hint,
                item.label() + " " + item.description() + " " + identity, 0,
                item.available() ? "" : "Unavailable", "", action));
        }
        boolean loading = catalog != null && catalog.loading();
        TypedValue selected = selectedSupplier != null ? selectedSupplier.get() : null;
        if (selected != null && !options.containsKey(selected)) {
            items.add(loading ? selected(selected) : unavailable(typedLabel(selected), selected.canonicalJson()));
        }
        String message = emptyMessage != null && !emptyMessage.isBlank() ? emptyMessage : "No Options";
        if (!loading && (catalog == null || !"available".equals(catalog.status()) && !"missing".equals(catalog.status()))) {
            String diagnostic = catalog != null ? catalog.diagnostic() : "";
            message = diagnostic != null && !diagnostic.isBlank() ? diagnostic : "Option Source Unavailable";
        }
        return new ItemSelectorWidget.AsyncItemSnapshot(items, loading, message);
    }

    @Deprecated
    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(String serverId, String source, Supplier<String> selectedSupplier,
        Consumer<String> onSelected) {
        return snapshot(serverId, source, Map.of(), List::of, selectedSupplier, onSelected, "No Options");
    }

    @Deprecated
    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(String serverId, String source, Map<String, Object> context,
        Supplier<? extends Collection<String>> fallbackSupplier, Supplier<String> selectedSupplier, Consumer<String> onSelected,
        String emptyMessage) {
        OptionCatalogLoader.Snapshot catalog = OptionCatalogLoader.snapshot(serverId, source, context);
        Map<String, OptionCatalogItem> richByValue = new LinkedHashMap<>();
        for (OptionCatalogItem item : catalog.items()) {
            if (real(item.getValue())) {
                richByValue.putIfAbsent(item.getValue(), item);
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String value : catalog.values()) {
            if (real(value)) {
                values.putIfAbsent(value, value);
            }
        }
        Collection<String> fallback = fallbackSupplier != null ? fallbackSupplier.get() : List.of();
        if (fallback != null) {
            for (String value : fallback) {
                if (real(value)) {
                    values.putIfAbsent(value, value);
                }
            }
        }
        List<ItemSelectorWidget.AsyncItem> items = new ArrayList<>();
        for (OptionCatalogItem item : richByValue.values()) {
            String value = item.getValue();
            Object aliases = item.getMetadata().get("aliases");
            String searchTerms = String.join(" ", value, item.getLabel(), item.getDescription(), item.getGroup(),
                aliases != null ? aliases.toString() : "");
            items.add(asyncItem(item, label(item, value), searchTerms,
                item.isAvailable() && onSelected != null ? () -> onSelected.accept(value) : null));
            values.remove(value);
        }
        for (String value : values.keySet()) {
            items.add(new ItemSelectorWidget.AsyncItem(value, "", value, onSelected != null ? () -> onSelected.accept(value) : null));
        }
        String selected = selectedSupplier != null ? selectedSupplier.get() : "";
        boolean selectedIncluded = richByValue.containsKey(selected) || values.containsKey(selected);
        if (real(selected) && !selectedIncluded) {
            items.add(unavailable(selected, selected));
        }
        String message = emptyMessage != null && !emptyMessage.isBlank() ? emptyMessage : "No Options";
        if (!catalog.loading() && !"available".equals(catalog.status()) && !"missing".equals(catalog.status())) {
            message = "Catalog Unavailable";
        }
        return new ItemSelectorWidget.AsyncItemSnapshot(items, catalog.loading(), message);
    }

    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(OptionCatalogLoader.ResourceRequest request,
        Supplier<ServerResourceLocator> selectedSupplier, Consumer<ServerResourceLocator> onSelected, String emptyMessage) {
        return snapshot(OptionCatalogLoader.snapshot(request), selectedSupplier, onSelected, emptyMessage);
    }

    public static ItemSelectorWidget.AsyncItemSnapshot snapshot(OptionCatalogLoader.ResourceSnapshot catalog,
        Supplier<ServerResourceLocator> selectedSupplier, Consumer<ServerResourceLocator> onSelected, String emptyMessage) {
        Map<ServerResourceLocator, OptionCatalogItem> richByResource = new LinkedHashMap<>();
        if (catalog != null) {
            for (OptionCatalogItem item : catalog.items()) {
                ServerResourceLocator resource = item.getResource();
                if (resource != null) {
                    richByResource.putIfAbsent(resource, item);
                }
            }
        }
        List<ItemSelectorWidget.AsyncItem> items = new ArrayList<>();
        for (Map.Entry<ServerResourceLocator, OptionCatalogItem> entry : richByResource.entrySet()) {
            ServerResourceLocator resource = entry.getKey();
            OptionCatalogItem item = entry.getValue();
            Object aliases = item.getMetadata().get("aliases");
            String searchTerms = String.join(" ", resource.id(), resource.owner().canonicalText(),
                resource.resourceType().value(), item.getLabel(), item.getDescription(), item.getGroup(),
                aliases != null ? aliases.toString() : "");
            items.add(asyncItem(item, label(item, resource.id()), searchTerms,
                item.isAvailable() && onSelected != null ? () -> onSelected.accept(resource) : null));
        }
        ServerResourceLocator selected = selectedSupplier != null ? selectedSupplier.get() : null;
        if (selected != null && !richByResource.containsKey(selected)) {
            items.add(unavailable(selected.id(), selected.canonicalText()));
        }
        String message = emptyMessage != null && !emptyMessage.isBlank() ? emptyMessage : "No Options";
        boolean loading = catalog == null || catalog.loading();
        if (!loading && !"available".equals(catalog.status()) && !"missing".equals(catalog.status())) {
            message = "Catalog Unavailable";
        }
        return new ItemSelectorWidget.AsyncItemSnapshot(items, loading, message);
    }

    public static String label(String serverId, String source, String value) {
        return label(serverId, source, Map.of(), value);
    }

    public static String label(String serverId, String source, Map<String, Object> context, String value) {
        if (!real(value)) {
            return value != null ? value : "";
        }
        return OptionCatalogLoader.snapshot(serverId, source, context).items().stream()
            .filter(item -> value.equals(item.getValue()))
            .map(item -> label(item, value))
            .findFirst()
            .orElse(value);
    }

    public static String label(OptionCatalogLoader.ResourceSnapshot catalog, ServerResourceLocator resource) {
        if (resource == null) {
            return "";
        }
        if (catalog == null) {
            return resource.id();
        }
        return catalog.items().stream()
            .filter(item -> resource.equals(item.getResource()))
            .map(item -> label(item, resource.id()))
            .findFirst()
            .orElse(resource.id());
    }

    private static ItemSelectorWidget.AsyncItem asyncItem(OptionCatalogItem item, String label, String searchTerms,
                                                           Runnable action) {
        String badge = item.isAvailable() ? "" : "Unavailable";
        String hint = item.getDescription();
        Object reason = item.getMetadata().get("unavailableReason");
        if (!item.isAvailable() && reason != null && !reason.toString().isBlank()) {
            hint = hint.isBlank() ? reason.toString() : hint + " | " + reason;
        }
        return new ItemSelectorWidget.AsyncItem(label, item.getIcon(), hint, searchTerms, 0, badge, item.getGroup(), action);
    }

    private static ItemSelectorWidget.AsyncItem unavailable(String label, String searchTerms) {
        return new ItemSelectorWidget.AsyncItem(label, "", "Selected Value Is Unavailable", searchTerms, 0,
            "Unavailable", "", null);
    }

    private static ItemSelectorWidget.AsyncItem selected(TypedValue value) {
        return new ItemSelectorWidget.AsyncItem(typedLabel(value), "", "Current Value", value.canonicalJson(), 0,
            "Selected", "", null);
    }

    private static String typedLabel(TypedValue value) {
        if (value == null) {
            return "";
        }
        return value.locator() != null ? value.locator().id() : switch (value.state()) {
            case ABSENT -> "Absent";
            case NULL -> "Null";
            case VALUE -> value.value() instanceof String text ? text : String.valueOf(value.value());
            case OPAQUE -> "Unavailable";
            case LOCATOR -> value.locator().id();
        };
    }

    private static String label(OptionCatalogItem item, String fallback) {
        return item != null && item.getLabel() != null && !item.getLabel().isBlank() ? item.getLabel() : fallback;
    }

    private static boolean real(String value) {
        return value != null && !value.isBlank() && !"Loading".equals(value) && !"No Options".equals(value);
    }

    private static Optional<OptionCatalogLoader.CoreRequest> request(
        Supplier<Optional<OptionCatalogLoader.CoreRequest>> requestSupplier) {
        if (requestSupplier == null) {
            return Optional.empty();
        }
        Optional<OptionCatalogLoader.CoreRequest> request = requestSupplier.get();
        return request != null ? request : Optional.empty();
    }
}
