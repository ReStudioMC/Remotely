package redxax.oxy.remotely.metadata.catalog;

import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.metadata.MetadataRepository;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.metadata.CatalogId;
import restudio.resync.metadata.MinecraftRegistryBundle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ResolvedCatalogRepository {
    private final Map<Key, Snapshot> resident = new LinkedHashMap<>();
    private final Map<Context, Snapshot> active = new LinkedHashMap<>();
    private long generation;

    public synchronized Snapshot resolve(Input input) {
        Objects.requireNonNull(input, "Catalog input is required");
        Key key = new Key(input.catalogId(), input.stamp());
        Snapshot result = resident.get(key);
        if (result == null) {
            result = merge(input);
            resident.put(key, result);
        }
        Context context = new Context(input.catalogId(), input.live().serverIdentity());
        if (active.get(context) != result) {
            active.put(context, result);
            generation++;
        }
        return result;
    }

    public synchronized Snapshot snapshot(String catalogId, String serverIdentity) {
        return active.get(new Context(clean(catalogId), clean(serverIdentity)));
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized void invalidate(String catalogId) {
        String checked = clean(catalogId);
        boolean changed = active.keySet().removeIf(context -> context.catalogId().equals(checked));
        List<Key> keys = resident.keySet().stream().filter(key -> key.catalogId().equals(checked)).toList();
        for (Key key : keys) {
            resident.remove(key);
            changed = true;
        }
        if (changed) {
            generation++;
        }
    }

    public synchronized void invalidateServer(String serverIdentity) {
        String checked = clean(serverIdentity);
        Set<String> catalogs = new LinkedHashSet<>();
        for (Key key : resident.keySet()) {
            if (key.stamp().serverIdentity().equals(checked)) {
                catalogs.add(key.catalogId());
            }
        }
        if (catalogs.isEmpty()) {
            return;
        }
        resident.keySet().removeIf(key -> key.stamp().serverIdentity().equals(checked));
        active.keySet().removeIf(context -> context.serverIdentity().equals(checked));
        generation++;
    }

    public synchronized void invalidateAll() {
        if (resident.isEmpty() && active.isEmpty()) {
            return;
        }
        resident.clear();
        active.clear();
        generation++;
    }

    private static Snapshot merge(Input input) {
        LinkedHashMap<String, Item> baseline = byValue(input.baseline().items());
        LinkedHashMap<String, Item> live = byValue(input.live().items());
        LinkedHashSet<String> order = new LinkedHashSet<>(baseline.keySet());
        order.addAll(live.keySet());
        order.addAll(input.current().values());
        Set<String> current = new LinkedHashSet<>(input.current().values());
        List<ResolvedItem> items = new ArrayList<>(order.size());
        for (String value : order) {
            Item baselineItem = baseline.get(value);
            Item liveItem = live.get(value);
            items.add(resolveItem(value, baselineItem, liveItem, current.contains(value), input.baseline().state(), input.live().state()));
        }
        List<SourceDiagnostic> diagnostics = diagnostics(input.baseline(), input.live());
        ResolutionState state = state(input, items);
        return new Snapshot(input.catalogId(), input.stamp(), state, items, diagnostics);
    }

    private static ResolvedItem resolveItem(String value, Item baseline, Item live, boolean current,
                                            SourceState baselineState, SourceState liveState) {
        Item presentation = combine(value, baseline, live);
        ItemState state;
        if (live != null && live.available() && liveState == SourceState.AVAILABLE) {
            state = ItemState.AVAILABLE;
        } else if (baseline != null && baseline.available() && baselineState == SourceState.AVAILABLE) {
            state = live == null || liveState == SourceState.AVAILABLE ? ItemState.AVAILABLE : ItemState.STALE;
        } else if ((live != null && live.available()) || (baseline != null && baseline.available())) {
            state = ItemState.STALE;
        } else if (live != null || baseline != null) {
            state = ItemState.UNAVAILABLE;
        } else {
            state = ItemState.CURRENT_ONLY;
        }
        Set<Provenance> provenance = new LinkedHashSet<>();
        if (baseline != null) provenance.add(Provenance.BASELINE);
        if (live != null) provenance.add(Provenance.LIVE);
        if (current) provenance.add(Provenance.CURRENT);
        String diagnostic = live != null && !live.available() ? live.unavailableReason()
            : baseline != null && !baseline.available() ? baseline.unavailableReason() : "";
        return new ResolvedItem(presentation, state, provenance, baseline, live, diagnostic);
    }

    private static Item combine(String value, Item baseline, Item live) {
        if (baseline == null && live == null) {
            return Item.current(value);
        }
        if (baseline == null) {
            return live;
        }
        if (live == null) {
            return baseline;
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseline.metadata());
        metadata.putAll(live.metadata());
        return new Item(value, prefer(live.label(), baseline.label(), value), prefer(live.description(), baseline.description(), ""),
            prefer(live.icon(), baseline.icon(), ""), prefer(live.group(), baseline.group(), ""), metadata,
            live.available() || baseline.available(), prefer(live.unavailableReason(), baseline.unavailableReason(), ""));
    }

    private static ResolutionState state(Input input, List<ResolvedItem> items) {
        boolean selectable = items.stream().anyMatch(item -> item.state() == ItemState.AVAILABLE || item.state() == ItemState.STALE);
        if (selectable) {
            if (input.baseline().state() != SourceState.AVAILABLE || input.live().state() != SourceState.AVAILABLE) {
                return ResolutionState.STALE;
            }
            return ResolutionState.READY;
        }
        if (input.live().state() == SourceState.FAILED || input.baseline().state() == SourceState.FAILED) {
            return ResolutionState.FAILED;
        }
        if (input.live().state() == SourceState.LOADING || input.baseline().state() == SourceState.LOADING) {
            return ResolutionState.LOADING;
        }
        return ResolutionState.UNAVAILABLE;
    }

    private static List<SourceDiagnostic> diagnostics(Baseline baseline, Live live) {
        List<SourceDiagnostic> diagnostics = new ArrayList<>(2);
        if (baseline.state() != SourceState.AVAILABLE || !baseline.diagnostic().isBlank()) {
            diagnostics.add(new SourceDiagnostic(Provenance.BASELINE, baseline.state(), baseline.diagnostic()));
        }
        if (live.state() != SourceState.AVAILABLE || !live.diagnostic().isBlank()) {
            diagnostics.add(new SourceDiagnostic(Provenance.LIVE, live.state(), live.diagnostic()));
        }
        return List.copyOf(diagnostics);
    }

    private static LinkedHashMap<String, Item> byValue(List<Item> items) {
        LinkedHashMap<String, Item> distinct = new LinkedHashMap<>();
        for (Item item : items) {
            distinct.putIfAbsent(item.value(), item);
        }
        return distinct;
    }

    private static String prefer(String first, String second, String fallback) {
        return first != null && !first.isBlank() ? first : second != null && !second.isBlank() ? second : fallback;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Catalog identity is required");
        }
        return value;
    }

    private static Map<String, Object> immutableMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("Catalog metadata keys cannot be null");
            }
            copied.put(entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copied.put(String.valueOf(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copied = new ArrayList<>(collection.size());
            for (Object entry : collection) copied.add(immutableValue(entry));
            return Collections.unmodifiableList(copied);
        }
        List<Object> array = arrayValues(value);
        if (array != null) {
            List<Object> copied = new ArrayList<>(array.size());
            for (Object entry : array) copied.add(immutableValue(entry));
            return Collections.unmodifiableList(copied);
        }
        return value;
    }

    private static List<Object> arrayValues(Object value) {
        List<Object> values;
        if (value instanceof Object[] items) {
            values = new ArrayList<>(items.length);
            Collections.addAll(values, items);
        } else if (value instanceof int[] items) {
            values = new ArrayList<>(items.length);
            for (int item : items) values.add(item);
        } else if (value instanceof long[] items) {
            values = new ArrayList<>(items.length);
            for (long item : items) values.add(item);
        } else if (value instanceof double[] items) {
            values = new ArrayList<>(items.length);
            for (double item : items) values.add(item);
        } else if (value instanceof boolean[] items) {
            values = new ArrayList<>(items.length);
            for (boolean item : items) values.add(item);
        } else if (value instanceof float[] items) {
            values = new ArrayList<>(items.length);
            for (float item : items) values.add(item);
        } else if (value instanceof short[] items) {
            values = new ArrayList<>(items.length);
            for (short item : items) values.add(item);
        } else if (value instanceof byte[] items) {
            values = new ArrayList<>(items.length);
            for (byte item : items) values.add(item);
        } else if (value instanceof char[] items) {
            values = new ArrayList<>(items.length);
            for (char item : items) values.add(item);
        } else {
            return null;
        }
        return values;
    }

    private record Key(String catalogId, Stamp stamp) {
    }

    private record Context(String catalogId, String serverIdentity) {
    }

    public record Input(String catalogId, Baseline baseline, Live live, Current current) {
        public Input {
            catalogId = clean(catalogId);
            baseline = baseline == null ? Baseline.missing() : baseline;
            live = live == null ? Live.missing() : live;
            current = current == null ? Current.empty() : current;
        }

        public Stamp stamp() {
            return new Stamp(baseline.bundleIdentity(), baseline.catalogIdentity(), baseline.observationIdentity(),
                live.serverIdentity(), live.revision(), live.observationIdentity(), current.identity());
        }
    }

    public record Baseline(String bundleIdentity, String catalogIdentity, String observationIdentity, List<Item> items,
                           SourceState state, String diagnostic) {
        public Baseline {
            bundleIdentity = clean(bundleIdentity);
            catalogIdentity = clean(catalogIdentity);
            observationIdentity = clean(observationIdentity);
            items = items == null ? List.of() : List.copyOf(items);
            state = Objects.requireNonNull(state, "Baseline state is required");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public static Baseline available(String bundleIdentity, String catalogIdentity, List<Item> items) {
            return new Baseline(bundleIdentity, catalogIdentity, "available", items, SourceState.AVAILABLE, "");
        }

        public static Baseline from(MetadataRepository.Snapshot snapshot, CatalogId catalogId) {
            Objects.requireNonNull(snapshot, "Metadata snapshot is required");
            Objects.requireNonNull(catalogId, "Catalog ID is required");
            MetadataRepository.Bundle admitted = snapshot.bundle(MinecraftRegistryBundle.ARTIFACT_FAMILY);
            if (admitted == null || !(admitted.value() instanceof MinecraftRegistryBundle bundle)) {
                return missing(catalogId, "Hosted Registry Bundle Is Unavailable");
            }
            for (MinecraftRegistryBundle.Catalog catalog : bundle.catalogs()) {
                if (!catalog.id().equals(catalogId)) continue;
                List<Item> items = catalog.entries().stream().map(entry -> new Item(entry.value(), entry.label(),
                    entry.description(), entry.icon(), entry.group(), new LinkedHashMap<>(entry.metadata()), true, "")).toList();
                return new Baseline(admitted.descriptor().bundleId().canonicalText(), catalogId.canonicalText(),
                    snapshot.stamp(), items, SourceState.AVAILABLE, "");
            }
            return missing(catalogId, "Hosted Catalog Is Unavailable");
        }

        public static Baseline missing() {
            return new Baseline("bundled:none", "catalog:none", "missing", List.of(), SourceState.UNAVAILABLE,
                "Baseline Catalog Is Unavailable");
        }

        private static Baseline missing(CatalogId catalogId, String diagnostic) {
            return new Baseline("bundled:none", catalogId.canonicalText(), "missing", List.of(), SourceState.UNAVAILABLE,
                diagnostic);
        }
    }

    public record Live(String serverIdentity, String revision, String observationIdentity, List<Item> items,
                       SourceState state, String diagnostic) {
        public Live {
            serverIdentity = clean(serverIdentity);
            revision = clean(revision);
            observationIdentity = clean(observationIdentity);
            items = items == null ? List.of() : List.copyOf(items);
            state = Objects.requireNonNull(state, "Live catalog state is required");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public static Live from(String serverIdentity, String revision, OptionCatalogLoader.Snapshot snapshot) {
            Objects.requireNonNull(snapshot, "Live option catalog snapshot is required");
            LinkedHashMap<String, Item> items = new LinkedHashMap<>();
            for (String value : snapshot.values()) {
                if (value != null && !value.isBlank()) items.putIfAbsent(value, Item.available(value));
            }
            Set<String> rich = new LinkedHashSet<>();
            for (OptionCatalogItem item : snapshot.items()) {
                Item converted = item(item);
                if (rich.add(converted.value())) items.put(converted.value(), converted);
            }
            SourceState state = sourceState(snapshot.loading(), snapshot.status());
            return new Live(serverIdentity, revision, observation(state, snapshot.status(), snapshot.diagnostic()),
                List.copyOf(items.values()), state, snapshot.diagnostic());
        }

        public static Live from(String serverIdentity, String revision, OptionCatalogLoader.ResourceSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "Live resource catalog snapshot is required");
            List<Item> items = snapshot.items().stream().map(Live::item).toList();
            SourceState state = sourceState(snapshot.loading(), snapshot.status());
            return new Live(serverIdentity, revision, observation(state, snapshot.status(), snapshot.diagnostic()),
                items, state, snapshot.diagnostic());
        }

        public static Live from(String serverIdentity, OptionCatalogLoader.CoreSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "Live Core option catalog snapshot is required");
            String revision = snapshot.catalog() == null ? "missing" : Long.toString(snapshot.catalog().revision());
            List<Item> items = snapshot.items().stream().map(Live::item).toList();
            SourceState state = sourceState(snapshot.loading(), snapshot.status());
            return new Live(serverIdentity, revision, observation(state, snapshot.status(), snapshot.diagnostic()),
                items, state, snapshot.diagnostic());
        }

        public static Live missing() {
            return new Live("server:none", "missing", "missing", List.of(), SourceState.UNAVAILABLE,
                "Live Catalog Is Unavailable");
        }

        private static Item item(OptionCatalogItem item) {
            String value = item.isResource() ? item.getResource().canonicalText() : item.getValue();
            String fallbackLabel = item.isResource() ? item.getResource().id() : item.getValue();
            String label = item.getLabel().equals(fallbackLabel) ? "" : item.getLabel();
            return new Item(value, label, item.getDescription(), item.getIcon(), item.getGroup(), item.getMetadata(),
                item.isAvailable(), String.valueOf(item.getMetadata().getOrDefault("unavailableReason", "")));
        }

        private static Item item(OptionItem item) {
            return new Item(item.value().canonicalJson(), item.label(), item.description(), "", "", Map.of(),
                item.available(), item.reason());
        }

        private static SourceState sourceState(boolean loading, String status) {
            String normalized = status == null ? "" : status.trim().toLowerCase();
            if ("stale".equals(normalized)) return SourceState.STALE;
            if ("failed".equals(normalized) || "error".equals(normalized)) return SourceState.FAILED;
            if ("unavailable".equals(normalized) || "unsupported".equals(normalized) || "missing".equals(normalized)) {
                return loading ? SourceState.LOADING : SourceState.UNAVAILABLE;
            }
            return loading ? SourceState.LOADING : SourceState.AVAILABLE;
        }

        private static String observation(SourceState state, String status, String diagnostic) {
            return state.name() + "|" + (status == null ? "" : status) + "|" + (diagnostic == null ? "" : diagnostic);
        }
    }

    public record Current(String identity, List<String> values) {
        public Current {
            identity = clean(identity);
            if (values == null || values.isEmpty()) {
                values = List.of();
            } else {
                LinkedHashSet<String> copied = new LinkedHashSet<>();
                for (String value : values) copied.add(clean(value));
                values = List.copyOf(copied);
            }
        }

        public static Current empty() {
            return new Current("current:none", List.of());
        }
    }

    public record Stamp(String baselineBundleIdentity, String baselineCatalogIdentity,
                        String baselineObservationIdentity, String serverIdentity, String liveRevision,
                        String liveObservationIdentity, String currentIdentity) {
        public Stamp {
            baselineBundleIdentity = clean(baselineBundleIdentity);
            baselineCatalogIdentity = clean(baselineCatalogIdentity);
            baselineObservationIdentity = clean(baselineObservationIdentity);
            serverIdentity = clean(serverIdentity);
            liveRevision = clean(liveRevision);
            liveObservationIdentity = clean(liveObservationIdentity);
            currentIdentity = clean(currentIdentity);
        }
    }

    public record Item(String value, String label, String description, String icon, String group,
                       Map<String, Object> metadata, boolean available, String unavailableReason) {
        public Item {
            value = clean(value);
            label = label == null ? "" : label;
            description = description == null ? "" : description;
            icon = icon == null ? "" : icon;
            group = group == null ? "" : group;
            metadata = immutableMetadata(metadata);
            unavailableReason = unavailableReason == null ? "" : unavailableReason;
            if (!available && unavailableReason.isBlank()) {
                unavailableReason = "Unavailable In Current Catalog";
            }
        }

        public static Item available(String value) {
            return new Item(value, value, "", "", "", Map.of(), true, "");
        }

        private static Item current(String value) {
            return new Item(value, value, "", "", "", Map.of(), false, "Unavailable In Current Catalog");
        }
    }

    public record ResolvedItem(Item item, ItemState state, Set<Provenance> provenance, Item baselineFallback,
                               Item liveItem, String diagnostic) {
        public ResolvedItem {
            item = Objects.requireNonNull(item, "Resolved catalog item is required");
            state = Objects.requireNonNull(state, "Resolved catalog item state is required");
            provenance = provenance == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(provenance));
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public String value() {
            return item.value();
        }
    }

    public record SourceDiagnostic(Provenance source, SourceState state, String message) {
        public SourceDiagnostic {
            source = Objects.requireNonNull(source, "Catalog diagnostic source is required");
            state = Objects.requireNonNull(state, "Catalog diagnostic state is required");
            message = message == null ? "" : message;
        }
    }

    public record Snapshot(String catalogId, Stamp stamp, ResolutionState state, List<ResolvedItem> items,
                           List<SourceDiagnostic> diagnostics) {
        public Snapshot {
            catalogId = clean(catalogId);
            stamp = Objects.requireNonNull(stamp, "Resolved catalog stamp is required");
            state = Objects.requireNonNull(state, "Resolved catalog state is required");
            items = items == null ? List.of() : List.copyOf(items);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public ResolvedItem item(String value) {
            if (value == null) return null;
            for (ResolvedItem item : items) {
                if (item.value().equals(value)) return item;
            }
            return null;
        }
    }

    public enum SourceState {
        AVAILABLE,
        LOADING,
        STALE,
        UNAVAILABLE,
        FAILED
    }

    public enum ResolutionState {
        READY,
        LOADING,
        STALE,
        UNAVAILABLE,
        FAILED
    }

    public enum ItemState {
        AVAILABLE,
        STALE,
        CURRENT_ONLY,
        UNAVAILABLE
    }

    public enum Provenance {
        BASELINE,
        LIVE,
        CURRENT
    }
}
