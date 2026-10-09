package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.storage.StorageBreakdownController;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.ui.widgets.ResourceBarWidget;
import restudio.rescreen.util.Identifier;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ResourceAllocationBarWidget extends ResourceBarWidget implements StorageBreakdownController.StorageBar {
    private static final Identifier RAM_ICON = Identifier.image("textures/icons/ram.png");
    private static final Identifier CPU_ICON = Identifier.image("textures/icons/cpu.png");
    private static final Identifier DISK_ICON = Identifier.image("textures/icons/disk.png");

    record HoveredServer(String serverId, Accent accent) {}

    record StoragePart(String name, long bytes) {}

    private final PoolAllocationEditor editor;
    private final PoolCreationPreview creation;
    private NetworkPoolBudget network;
    private BooleanSupplier editable = () -> true;
    private final PoolAllocationEditor.Resource resource;
    private List<StoragePart> storageParts;
    private BigInteger storageCapacity;
    private List<ResourcePoolModels.Allocation> allocations;
    private Set<String> editableServers = Set.of();
    private ResourcePoolController.PoolView accentView;
    private Map<String, Integer> accentIndexes = Map.of();
    private final Function<String, String> names;
    private Runnable onChange = () -> {};
    private Consumer<HoveredServer> onHover = ignored -> {};
    private HoveredSegment lastHover;
    private HoveredServer lastHoveredServer;
    private boolean dragging;
    private String draggedServerId;
    private String draggedPreviewId;
    private BigInteger dragBase = BigInteger.ZERO;
    private BigInteger dragStartValue = BigInteger.ZERO;
    private double dragStartX;

    ResourceAllocationBarWidget(PoolAllocationEditor editor, PoolAllocationEditor.Resource resource,
                                List<ResourcePoolModels.Allocation> allocations, Function<String, String> names) {
        super(title(resource), icon(resource), unit(resource));
        this.editor = editor;
        this.creation = null;
        this.resource = resource;
        this.storageParts = null;
        this.storageCapacity = null;
        this.allocations = ordered(allocations);
        updateEditableServers();
        this.names = names;
        initializeHover();
        refresh();
    }

    ResourceAllocationBarWidget(PoolCreationPreview creation, PoolAllocationEditor.Resource resource,
                                Function<String, String> names) {
        this(creation, null, resource, names);
    }

    ResourceAllocationBarWidget(PoolCreationPreview creation, PoolAllocationEditor editor,
                                PoolAllocationEditor.Resource resource, Function<String, String> names) {
        super(title(resource), icon(resource), unit(resource));
        this.editor = editor;
        this.creation = creation;
        this.resource = resource;
        this.storageParts = null;
        this.storageCapacity = null;
        this.allocations = ordered(creation.view().allocations());
        updateEditableServers();
        this.names = names;
        initializeHover();
        refresh();
    }

    ResourceAllocationBarWidget(PoolAllocationEditor.Resource resource, Function<String, String> names) {
        super(title(resource), icon(resource), unit(resource));
        editor = null;
        creation = null;
        this.resource = resource;
        allocations = List.of();
        this.names = names;
        initializeHover();
    }

    void setNetwork(NetworkPoolBudget budget) {
        if (network != budget) {
            dragging = false;
            draggedPreviewId = null;
            setImmediate(false);
            network = budget;
        }
        refresh();
    }

    void editable(BooleanSupplier value) {
        editable = value;
    }

    ResourceAllocationBarWidget(String title, List<StoragePart> parts, BigInteger capacityBytes) {
        super(title, DISK_ICON, " bytes");
        editor = null;
        creation = null;
        resource = PoolAllocationEditor.Resource.DISK;
        storageParts = List.copyOf(parts);
        storageCapacity = capacityBytes.max(BigInteger.ZERO);
        allocations = List.of();
        names = ignored -> "";
        setAmountFormatter(ResourceAllocationBarWidget::storageSize);
        initializeHover();
        setHint(title + " Storage");
        refresh();
    }

    @Override
    public void updateStorage(List<StorageBreakdownController.StoragePart> values, BigInteger capacityBytes) {
        List<StoragePart> next = values.stream().map(value -> new StoragePart(value.name(), value.bytes())).toList();
        BigInteger capacity = capacityBytes.max(BigInteger.ZERO);
        if (next.equals(storageParts) && capacity.equals(storageCapacity)) return;
        storageParts = next;
        storageCapacity = capacity;
        refresh();
    }

    private static String title(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> "RAM";
            case CPU -> "CPU";
            case DISK -> "Disk";
            case BACKUP -> "Backup Storage";
        };
    }

    private static String unit(PoolAllocationEditor.Resource resource) {
        return resource == PoolAllocationEditor.Resource.CPU ? "%" : " MiB";
    }

    private static Identifier icon(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> RAM_ICON;
            case CPU -> CPU_ICON;
            case DISK, BACKUP -> DISK_ICON;
        };
    }

    private static BigInteger allocationAmount(ResourcePoolModels.Allocation allocation, PoolAllocationEditor.Resource resource) {
        return PoolAllocationEditor.number(switch (resource) {
            case RAM -> allocation.reserved().ramMiB();
            case CPU -> allocation.reserved().cpuQuotaPercent();
            case DISK -> allocation.retained().diskMiB();
            case BACKUP -> allocation.retained().backupMiB();
        });
    }

    private static List<ResourcePoolModels.Allocation> ordered(List<ResourcePoolModels.Allocation> values) {
        return values.stream().sorted(Comparator.comparingInt(ResourceAllocationBarWidget::order)
                .thenComparing(ResourcePoolModels.Allocation::serverId)).toList();
    }

    private static int order(ResourcePoolModels.Allocation allocation) {
        return allocation.state() == ResourcePoolModels.AllocationState.DISABLED ? 1 : 0;
    }

    void refresh() {
        if (storageParts != null) {
            refreshStorage();
            return;
        }
        if (network != null) {
            refreshNetwork();
            return;
        }
        if (creation == null && editor == null) {
            setSegments(List.of(), BigInteger.ONE);
            return;
        }
        List<Segment> next = new ArrayList<>(allocations.size() + 3);
        ResourcePoolController.PoolView view = creation == null ? editor.view() : creation.view();
        if (accentView != view) {
            Map<String, Integer> nextIndexes = new HashMap<>();
            view.allocations().stream().map(ResourcePoolModels.Allocation::serverId).sorted()
                    .forEach(serverId -> nextIndexes.put(serverId, nextIndexes.size()));
            accentIndexes = Map.copyOf(nextIndexes);
            accentView = view;
        }
        Map<String, Integer> accentIndexes = this.accentIndexes;
        int draftIndex = accentIndexes.size();
        BigInteger used = BigInteger.ZERO;
        boolean restartRelease = false;
        Set<String> shownServers = new HashSet<>();
        for (int i = 0; i < allocations.size(); i++) {
            ResourcePoolModels.Allocation allocation = allocations.get(i);
            shownServers.add(allocation.serverId());
            BigInteger amount = (editor == null ? allocationAmount(allocation, resource)
                    : editor.allocation(allocation, resource)).max(BigInteger.ZERO);
            boolean restartRequired = resource == PoolAllocationEditor.Resource.RAM
                    && (allocation.state() == ResourcePoolModels.AllocationState.PENDING
                    && PoolAllocationEditor.number(allocation.desired().ramMiB())
                    .compareTo(PoolAllocationEditor.number(allocation.effective().ramMiB())) < 0
                    || editor != null && editor.changed(allocation.serverId()) && amount.compareTo(
                    PoolAllocationEditor.number(allocation.effective().ramMiB())) < 0);
            restartRelease |= restartRequired;
            String status = switch (allocation.state()) {
                case ACTIVE -> "Active";
                case DISABLED -> resource == PoolAllocationEditor.Resource.DISK || resource == PoolAllocationEditor.Resource.BACKUP
                        ? "Disabled • Storage Retained" : "Disabled";
                case PENDING -> "Change Pending";
                case DISABLE_PENDING -> "Disabling";
            };
            next.add(serverPart(allocation.serverId(), names.apply(allocation.serverId()), amount,
                    accentIndexes.getOrDefault(allocation.serverId(), i), status,
                    allocation.state() == ResourcePoolModels.AllocationState.DISABLED, restartRequired));
            used = used.add(amount);
        }
        BigInteger shownDrafts = BigInteger.ZERO;
        List<ResourcePoolModels.Draft> drafts = creation == null ? editor.view().drafts() : creation.view().drafts();
        for (ResourcePoolModels.Draft draft : drafts) {
            if (draft.reserved() == null || !shownServers.add(draft.serverId())
                    || draft.state() == ResourcePoolModels.DraftState.ABANDONED) continue;
            BigInteger amount = PoolAllocationEditor.number(switch (resource) {
                case RAM -> draft.reserved().ramMiB();
                case CPU -> draft.reserved().cpuQuotaPercent();
                case DISK -> draft.retained().diskMiB();
                case BACKUP -> draft.retained().backupMiB();
            }).max(BigInteger.ZERO);
            if (amount.signum() == 0) continue;
            Integer existingIndex = accentIndexes.get(draft.serverId());
            next.add(serverPart(draft.serverId(), draft.metadata().name(), amount,
                    existingIndex == null ? draftIndex++ : existingIndex,
                    "Server Draft", false, false));
            shownDrafts = shownDrafts.add(amount);
        }
        used = used.add(shownDrafts);
        BigInteger other = creation == null ? editor.other(resource) : creation.other(resource);
        other = other.subtract(shownDrafts).max(BigInteger.ZERO);
        used = used.add(other);
        next.add(new Segment("other", "Other Servers", other,
                Style.theme(ThemeColor.elementHoverBackground, ThemeColor.innerBorder, ThemeColor.inClickableBackground, ThemeColor.globalOuterBorder),
                "", false, false, ""));
        BigInteger pendingRelease = editor == null ? BigInteger.ZERO : editor.pendingRelease(resource);
        if (pendingRelease.signum() > 0) {
            next.add(new Segment("pending-release", "Pending Release", pendingRelease,
                    Style.theme(ThemeColor.elementHoverBackground, ThemeColor.elementHoverBorder, ThemeColor.elementBackground, ThemeColor.globalOuterBorder),
                    "Not Free Yet", false, restartRelease, ""));
            used = used.add(pendingRelease);
        }
        if (creation != null) {
            BigInteger requested = creation.reserved(resource);
            next.add(new Segment("new-server", "New Server", requested, Style.namedAccent("nice"), "Preview", false, false, "Drag To Allocate"));
            used = used.add(requested);
        }
        BigInteger free = creation == null ? editor.previewAvailable(resource) : creation.free(resource);
        BigInteger scale = (creation == null ? editor.total(resource) : creation.total(resource)).max(used.add(free)).max(BigInteger.ONE);
        BigInteger unavailable = scale.subtract(used).subtract(free).max(BigInteger.ZERO);
        next.add(freePart(free, creation == null ? "" : "Drag To Allocate"));
        next.add(unavailablePart(unavailable));
        setSegments(next, scale);
    }

    private void refreshNetwork() {
        List<Segment> next = new ArrayList<>(network.previews().size() + 3);
        BigInteger used = network.committed(resource).add(network.used(resource));
        next.add(new Segment("other", "Other Servers", network.committed(resource),
                Style.theme(ThemeColor.elementHoverBackground, ThemeColor.innerBorder, ThemeColor.inClickableBackground, ThemeColor.globalOuterBorder),
                "", false, false, ""));
        int index = 0;
        for (Map.Entry<String, PoolCreationPreview> entry : network.previews().entrySet()) {
            next.add(new Segment("preview:" + entry.getKey(), names.apply(entry.getKey()), entry.getValue().reserved(resource),
                    Style.accent(index++), "Preview", false, false, "Drag Divider To Allocate"));
        }
        BigInteger free = network.free(resource);
        BigInteger scale = network.total(resource).max(used.add(free)).max(BigInteger.ONE);
        next.add(freePart(free, ""));
        next.add(unavailablePart(scale.subtract(used).subtract(free).max(BigInteger.ZERO)));
        setSegments(next, scale);
    }

    private static Segment freePart(BigInteger amount, String hint) {
        return new Segment("free", "Free", amount,
                Style.theme(ThemeColor.inClickableBackground, ThemeColor.inClickableBorder, ThemeColor.inClickableBackground, ThemeColor.globalOuterBorder),
                "Available Now", false, false, hint);
    }

    private static Segment unavailablePart(BigInteger amount) {
        return new Segment("unavailable", "Unavailable", amount,
                Style.theme(ThemeColor.innerBackground, ThemeColor.innerBorder, ThemeColor.innerBackground, ThemeColor.globalOuterBorder),
                "Not Available", false, false, "");
    }

    private void refreshStorage() {
        List<Segment> next = new ArrayList<>(storageParts.size() + 1);
        Map<String, Integer> occurrences = new HashMap<>();
        BigInteger used = BigInteger.ZERO;
        for (int index = 0; index < storageParts.size(); index++) {
            StoragePart item = storageParts.get(index);
            if (item.bytes() < 0) continue;
            int occurrence = occurrences.merge(item.name(), 1, Integer::sum);
            String key = occurrence == 1 ? "storage:" + item.name() : "storage-repeat:" + item.name().length() + ":" + item.name() + ":" + occurrence;
            Segment colored = new Segment(key, item.name(), BigInteger.valueOf(item.bytes()), Style.accent(index), "", false, false, "");
            next.add(colored);
            used = used.add(colored.amount());
        }
        if (storageCapacity.compareTo(used) > 0) {
            next.add(new Segment("unused", "Unused Allocation", storageCapacity.subtract(used),
                    Style.theme(ThemeColor.inClickableBackground, ThemeColor.inClickableBorder, ThemeColor.inClickableBackground, ThemeColor.globalOuterBorder),
                    "", false, false, ""));
        }
        setSegments(next, storageCapacity.max(used).max(BigInteger.ONE));
    }

    void setAllocations(List<ResourcePoolModels.Allocation> next) {
        allocations = ordered(next);
        updateEditableServers();
        refresh();
    }

    private void updateEditableServers() {
        editableServers = allocations.stream()
                .filter(allocation -> allocation.state() == ResourcePoolModels.AllocationState.ACTIVE
                        || allocation.state() == ResourcePoolModels.AllocationState.DISABLED)
                .map(ResourcePoolModels.Allocation::serverId).collect(Collectors.toUnmodifiableSet());
    }

    private Segment serverPart(String serverId, String name, BigInteger amount, int index, String status, boolean disabled, boolean restartRequired) {
        String key = serverId == null ? "storage:" + name : "server:" + serverId;
        String hint = serverId != null && editor != null && editableServers.contains(serverId) ? "Drag Divider To Preview" : "";
        return new Segment(key, name, amount, Style.accent(index), status, disabled, restartRequired, hint);
    }

    private static String serverId(Segment segment) {
        return segment.key().startsWith("server:") ? segment.key().substring("server:".length()) : null;
    }

    private void initializeHover() {
        setOnHover(hover -> {
            if (lastHover != hover) {
                lastHover = hover;
                String serverId = hover == null ? null : serverId(hover.segment());
                lastHoveredServer = serverId == null ? null : new HoveredServer(serverId, hover.accent());
            }
            onHover.accept(lastHoveredServer);
        });
    }

    void onChange(Runnable action) {
        onChange = action == null ? () -> {} : action;
    }

    void onHover(Consumer<HoveredServer> action) {
        onHover = action == null ? ignored -> {} : action;
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (event.button() != ReMouseButton.LEFT || !isMouseOver(event.x(), event.y())) {
            return super.mouseClicked(event);
        }
        if (!editable.getAsBoolean()) return super.mouseClicked(event);
        if (network != null) return startNetworkDrag(event.x()) ? event.finish(true) : super.mouseClicked(event);
        if (startServerDrag(event.x())) return event.finish(true);
        if (creation == null || creation.available(resource).compareTo(PoolCreationPreview.minimum(resource)) < 0) {
            return super.mouseClicked(event);
        }
        int left = chartLeft();
        List<Segment> parts = segments();
        int newIndex = -1;
        int freeIndex = -1;
        for (int i = 0; i < parts.size(); i++) {
            if ("New Server".equals(parts.get(i).name())) newIndex = i;
            if ("Free".equals(parts.get(i).name())) freeIndex = i;
        }
        if (newIndex < 0 || freeIndex < 0) return super.mouseClicked(event);
        double first = (newIndex == 0 ? left : boundaryAt(newIndex - 1)) - 6;
        double last = boundaryAt(freeIndex) + 2;
        if (event.x() < first || event.x() > last) return super.mouseClicked(event);
        dragBase = parts.subList(0, newIndex).stream().map(Segment::amount).reduce(BigInteger.ZERO, BigInteger::add);
        dragStartValue = creation.value(resource);
        dragStartX = event.x();
        draggedServerId = null;
        dragging = true;
        setImmediate(true);
        if (!event.modifiers().shift()) moveBoundary(event.x(), false);
        return event.finish(true);
    }

    private boolean startNetworkDrag(double mouseX) {
        int bestIndex = -1;
        double nearest = 7;
        List<Segment> parts = segments();
        for (int i = 0; i < parts.size(); i++) {
            Segment part = parts.get(i);
            if (!part.key().startsWith("preview:") || part.amount().signum() <= 0) continue;
            double distance = Math.abs(mouseX - boundaryAt(i));
            if (distance < nearest) {
                nearest = distance;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) return false;
        String id = parts.get(bestIndex).key().substring("preview:".length());
        PoolCreationPreview preview = network.previews().get(id);
        if (preview == null) return false;
        draggedPreviewId = id;
        dragBase = parts.subList(0, bestIndex).stream().map(Segment::amount).reduce(BigInteger.ZERO, BigInteger::add);
        dragStartValue = preview.value(resource);
        dragStartX = mouseX;
        dragging = true;
        setImmediate(true);
        return true;
    }

    private boolean startServerDrag(double mouseX) {
        if (editor == null || editor.locked()) return false;
        int bestIndex = -1;
        double nearest = 7;
        List<Segment> parts = segments();
        for (int i = 0; i < parts.size(); i++) {
            Segment part = parts.get(i);
            if (serverId(part) == null || part.amount().signum() <= 0) continue;
            double distance = Math.abs(mouseX - boundaryAt(i));
            if (distance < nearest) {
                nearest = distance;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) return false;
        String serverId = serverId(parts.get(bestIndex));
        ResourcePoolController.PoolView view = creation == null ? editor.view() : creation.view();
        ResourcePoolModels.Allocation allocation = view.allocations().stream()
                .filter(value -> value.serverId().equals(serverId)).findFirst().orElse(null);
        if (allocation == null || allocation.state() != ResourcePoolModels.AllocationState.ACTIVE
                && allocation.state() != ResourcePoolModels.AllocationState.DISABLED) return false;
        if (editor.busy(serverId) || editor.submitted(serverId)) return false;
        if (editor.selected() == null || !editor.selected().serverId().equals(serverId)) editor.select(serverId);
        draggedServerId = serverId;
        dragBase = parts.subList(0, bestIndex).stream().map(Segment::amount).reduce(BigInteger.ZERO, BigInteger::add);
        dragStartValue = editor.value(resource);
        dragStartX = mouseX;
        dragging = true;
        setImmediate(true);
        onChange.run();
        return true;
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (!dragging || event.button() != ReMouseButton.LEFT || !editable.getAsBoolean()) return super.mouseDragged(event);
        moveBoundary(event.x(), event.modifiers().shift());
        return event.finish(true);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        setImmediate(false);
        draggedServerId = null;
        draggedPreviewId = null;
        return event.finish(true);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        dragging = false;
        setImmediate(false);
        draggedServerId = null;
        draggedPreviewId = null;
        super.onRelease(mouseX, mouseY);
    }

    private void moveBoundary(double mouseX, boolean fine) {
        int left = chartLeft();
        int span = Math.max(1, getX() + getWidth() - 1 - left);
        BigInteger amount = fine ? dragStartValue.add(BigInteger.valueOf(Math.round(mouseX - dragStartX)))
                : new BigDecimal(capacity()).multiply(BigDecimal.valueOf(Math.clamp(mouseX - left, 0, span)))
                .divide(BigDecimal.valueOf(span), 0, RoundingMode.HALF_UP).toBigInteger().subtract(dragBase);
        if (draggedPreviewId != null && network != null) {
            PoolCreationPreview preview = network.previews().get(draggedPreviewId);
            if (preview == null) return;
            BigInteger previous = preview.value(resource);
            BigInteger bounded = amount.max(PoolCreationPreview.minimum(resource)).min(preview.editLimit(resource));
            if (preview.set(resource, bounded) && !previous.equals(preview.value(resource))) {
                refresh();
                onChange.run();
            }
        } else if (draggedServerId != null) {
            if (editor.selected() == null || !draggedServerId.equals(editor.selected().serverId())) return;
            BigInteger previous = editor.value(resource);
            if (editor.propose(resource, amount) && !previous.equals(editor.value(resource))) onChange.run();
        } else if (creation != null) {
            BigInteger previous = creation.value(resource);
            BigInteger bounded = amount.max(PoolCreationPreview.minimum(resource)).min(creation.available(resource));
            if (creation.set(resource, bounded) && !previous.equals(creation.value(resource))) onChange.run();
        }
    }

    static String storageSize(BigInteger bytes) {
        BigInteger gib = BigInteger.valueOf(1024L * 1024 * 1024);
        BigInteger mib = BigInteger.valueOf(1024L * 1024);
        if (bytes.compareTo(gib) >= 0) return new BigDecimal(bytes).divide(new BigDecimal(gib), 2, RoundingMode.HALF_UP) + " GiB";
        if (bytes.compareTo(mib) >= 0) return new BigDecimal(bytes).divide(new BigDecimal(mib), 2, RoundingMode.HALF_UP) + " MiB";
        if (bytes.compareTo(BigInteger.valueOf(1024)) >= 0) {
            return new BigDecimal(bytes).divide(BigDecimal.valueOf(1024), 2, RoundingMode.HALF_UP) + " KiB";
        }
        return bytes + " bytes";
    }
}
