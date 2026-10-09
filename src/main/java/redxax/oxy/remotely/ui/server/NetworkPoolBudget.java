package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;

import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class NetworkPoolBudget {
    private ResourcePoolController.PoolView view;
    private final ResourcePoolModels.DraftOptions options;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> totals = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> committed = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> available = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> used = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> free = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private Map<String, PoolCreationPreview> active = Map.of();
    private Runnable changed = () -> {};
    private boolean refreshing;
    private boolean possible = true;

    private static final class Entry {
        private final PoolCreationPreview preview;
        private boolean active = true;

        private Entry(PoolCreationPreview preview) {
            this.preview = preview;
        }
    }

    NetworkPoolBudget(ResourcePoolController.PoolView view, ResourcePoolModels.DraftOptions options) {
        this.view = Objects.requireNonNull(view, "view");
        this.options = Objects.requireNonNull(options, "options");
        readBalance();
    }

    void accept(ResourcePoolController.PoolView next) {
        Objects.requireNonNull(next, "next");
        if (!view.pool().id().equals(next.pool().id())) throw new IllegalArgumentException("Resource Pool Changed");
        if (view.equals(next)) return;
        view = next;
        readBalance();
        for (Entry entry : entries.values()) entry.preview.acceptShared(next);
        refresh();
    }

    private void readBalance() {
        ResourcePoolModels.Balance balance = view.pool().balance();
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            BigInteger assigned = number(balance.committed(), resource);
            BigInteger capacity = number(balance.available(), resource);
            committed.put(resource, assigned);
            available.put(resource, capacity);
            totals.put(resource, number(balance.entitled(), resource).max(assigned.add(capacity)));
            used.putIfAbsent(resource, BigInteger.ZERO);
            free.putIfAbsent(resource, capacity);
        }
    }

    PoolCreationPreview create(String id, int shares) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("Server Identity Is Required");
        Entry present = entries.get(id);
        if (present != null) return present.preview;
        PoolCreationPreview preview = new PoolCreationPreview(view, options, shares);
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            preview.limit(resource, free(resource));
        }
        entries.put(id, new Entry(preview));
        preview.onAllocationChanged(this::refresh);
        refresh();
        return preview;
    }

    void setActive(String id, boolean value) {
        Entry entry = entries.get(id);
        if (entry == null || entry.active == value) return;
        entry.active = value;
        refresh();
    }

    void remove(String id) {
        Entry entry = entries.remove(id);
        if (entry == null) return;
        entry.preview.onAllocationChanged(null);
        refresh();
    }

    void onChanged(Runnable listener) {
        changed = listener == null ? () -> {} : listener;
    }

    ResourcePoolController.PoolView view() {
        return view;
    }

    Map<String, PoolCreationPreview> previews() {
        return active;
    }

    BigInteger total(PoolAllocationEditor.Resource resource) {
        return totals.get(resource);
    }

    BigInteger committed(PoolAllocationEditor.Resource resource) {
        return committed.get(resource);
    }

    BigInteger used(PoolAllocationEditor.Resource resource) {
        return used.get(resource);
    }

    BigInteger free(PoolAllocationEditor.Resource resource) {
        return free.get(resource);
    }

    boolean possible() {
        return possible;
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            Map<String, PoolCreationPreview> next = new LinkedHashMap<>();
            entries.forEach((id, entry) -> {
                if (entry.active) next.put(id, entry.preview);
            });
            if (!active.equals(next)) active = Collections.unmodifiableMap(next);
            possible = true;
            for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
                BigInteger requested = BigInteger.ZERO;
                for (PoolCreationPreview preview : active.values()) requested = requested.add(preview.reserved(resource));
                used.put(resource, requested);
                free.put(resource, available.get(resource).subtract(requested).max(BigInteger.ZERO));
                for (PoolCreationPreview preview : active.values()) {
                    BigInteger others = requested.subtract(preview.reserved(resource));
                    preview.limit(resource, available.get(resource).subtract(others).max(BigInteger.ZERO), false);
                }
                if (requested.compareTo(available.get(resource)) > 0) possible = false;
            }
            for (PoolCreationPreview preview : active.values()) {
                if (!preview.possible()) possible = false;
            }
            for (PoolCreationPreview preview : active.values()) preview.notifyChanged();
            changed.run();
        } finally {
            refreshing = false;
        }
    }

    private static BigInteger number(ResourcePoolModels.Resources resources, PoolAllocationEditor.Resource resource) {
        return PoolAllocationEditor.number(switch (resource) {
            case RAM -> resources.ramMiB();
            case CPU -> resources.cpuQuotaPercent();
            case DISK -> resources.diskMiB();
            case BACKUP -> "0";
        });
    }
}
