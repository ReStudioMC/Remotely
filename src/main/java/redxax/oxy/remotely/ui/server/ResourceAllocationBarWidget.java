package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.storage.StorageBreakdownController;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.Theme;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static restudio.rescreen.config.Config.animationsEnabled;
import static restudio.rescreen.config.Config.deltaTime;
import static restudio.rescreen.config.Config.globalExpandSpeed;

final class ResourceAllocationBarWidget extends MountableButtonWidget implements StorageBreakdownController.StorageBar {
    private static final int LABEL_SIZE = 18;
    private static final int ICON_SIZE = 16;
    private static final Identifier RAM_ICON = Identifier.image("textures/icons/ram.png");
    private static final Identifier CPU_ICON = Identifier.image("textures/icons/cpu.png");
    private static final Identifier DISK_ICON = Identifier.image("textures/icons/disk.png");
    private static final String[] ACCENTS = {
            "calm", "copper", "diamond", "silver", "midnight", "love", "oxidize", "obsidian"
    };

    record HoveredServer(String serverId, Accent accent) {}

    record StoragePart(String name, long bytes) {}

    private record Part(String name, BigInteger amount, int background, int border, int bottom, int outer,
                        String serverId, Accent accent, String status, boolean disabled, boolean restartRequired) {}

    private final PoolAllocationEditor editor;
    private final PoolCreationPreview creation;
    private final PoolAllocationEditor.Resource resource;
    private List<StoragePart> storageParts;
    private BigInteger storageCapacity;
    private List<ResourcePoolModels.Allocation> allocations;
    private Set<String> editableServers = Set.of();
    private ResourcePoolController.PoolView accentView;
    private Map<String, Integer> accentIndexes = Map.of();
    private final Function<String, String> names;
    private final String unit;
    private final String title;
    private final Identifier icon;
    private List<Part> parts = List.of();
    private float[] currentEnds = new float[0];
    private float[] targetEnds = new float[0];
    private int[] drawnEnds = new int[0];
    private BigInteger scale = BigInteger.ONE;
    private Theme theme;
    private String hoveredPart = "";
    private int hoverX;
    private Runnable onChange = () -> {};
    private Consumer<HoveredServer> onHover = ignored -> {};
    private boolean dragging;
    private String draggedServerId;
    private BigInteger dragBase = BigInteger.ZERO;
    private BigInteger dragStartValue = BigInteger.ZERO;
    private double dragStartX;

    ResourceAllocationBarWidget(PoolAllocationEditor editor, PoolAllocationEditor.Resource resource,
                                List<ResourcePoolModels.Allocation> allocations, Function<String, String> names) {
        super(title(resource), null, null, new ArrayList<>(), null);
        this.editor = editor;
        this.creation = null;
        this.resource = resource;
        this.storageParts = null;
        this.storageCapacity = null;
        this.allocations = ordered(allocations);
        updateEditableServers();
        this.names = names;
        title = title(resource);
        icon = icon(resource);
        unit = unit(resource);
        setHint(title + " Allocation");
        refresh();
    }

    ResourceAllocationBarWidget(PoolCreationPreview creation, PoolAllocationEditor.Resource resource,
                                Function<String, String> names) {
        this(creation, null, resource, names);
    }

    ResourceAllocationBarWidget(PoolCreationPreview creation, PoolAllocationEditor editor,
                                PoolAllocationEditor.Resource resource, Function<String, String> names) {
        super(title(resource), null, null, new ArrayList<>(), null);
        this.editor = editor;
        this.creation = creation;
        this.resource = resource;
        this.storageParts = null;
        this.storageCapacity = null;
        this.allocations = ordered(creation.view().allocations());
        updateEditableServers();
        this.names = names;
        title = title(resource);
        icon = icon(resource);
        unit = unit(resource);
        setHint(title + " Allocation");
        refresh();
    }

    ResourceAllocationBarWidget(String title, List<StoragePart> parts, BigInteger capacityBytes) {
        super(title, null, null, new ArrayList<>(), null);
        editor = null;
        creation = null;
        resource = PoolAllocationEditor.Resource.DISK;
        storageParts = List.copyOf(parts);
        storageCapacity = capacityBytes.max(BigInteger.ZERO);
        allocations = List.of();
        names = ignored -> "";
        this.title = title;
        icon = DISK_ICON;
        unit = " bytes";
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
        theme = ThemeManager.getCurrentTheme();
        if (storageParts != null) {
            refreshStorage();
            return;
        }
        List<Part> next = new ArrayList<>(allocations.size() + 3);
        Map<String, Accent> accents = ThemeManager.getRegisteredAccents();
        Theme defaults = ThemeManager.getTheme("default");
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
                    accentIndexes.getOrDefault(allocation.serverId(), i), accents, defaults, status,
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
                    existingIndex == null ? draftIndex++ : existingIndex, accents, defaults,
                    "Server Draft", false, false));
            shownDrafts = shownDrafts.add(amount);
        }
        used = used.add(shownDrafts);
        BigInteger other = creation == null ? editor.other(resource) : creation.other(resource);
        other = other.subtract(shownDrafts).max(BigInteger.ZERO);
        used = used.add(other);
        next.add(new Part("Other Servers", other,
                ThemeManager.getColor(ThemeColor.elementHoverBackground),
                ThemeManager.getColor(ThemeColor.innerBorder),
                ThemeManager.getColor(ThemeColor.inClickableBackground),
                ThemeManager.getColor(ThemeColor.globalOuterBorder), null, null, "", false, false));
        BigInteger pendingRelease = editor == null ? BigInteger.ZERO : editor.pendingRelease(resource);
        if (pendingRelease.signum() > 0) {
            next.add(new Part("Pending Release", pendingRelease,
                    ThemeManager.getColor(ThemeColor.elementHoverBackground),
                    ThemeManager.getColor(ThemeColor.elementHoverBorder),
                    ThemeManager.getColor(ThemeColor.elementBackground),
                    ThemeManager.getColor(ThemeColor.globalOuterBorder), null, null, "Not Free Yet", false, restartRelease));
            used = used.add(pendingRelease);
        }
        if (creation != null) {
            Accent accent = accents.get("nice");
            if (accent == null && defaults != null) accent = defaults.getAccent("nice");
            if (accent == null) accent = ThemeManager.getDefaultAccent();
            BigInteger requested = creation.reserved(resource);
            next.add(new Part("New Server", requested, accent.getAccentDarkColor(), accent.getAccentColor(),
                    accent.getBottomColor(), accent.getOuterBorderColor(), null, null, "Preview", false, false));
            used = used.add(requested);
        }
        BigInteger free = creation == null ? editor.previewAvailable(resource) : creation.free(resource);
        BigInteger scale = (creation == null ? editor.total(resource) : creation.total(resource)).max(used.add(free)).max(BigInteger.ONE);
        this.scale = scale;
        BigInteger unavailable = scale.subtract(used).subtract(free).max(BigInteger.ZERO);
        next.add(new Part("Free", free,
                ThemeManager.getColor(ThemeColor.inClickableBackground),
                ThemeManager.getColor(ThemeColor.inClickableBorder),
                ThemeManager.getColor(ThemeColor.inClickableBackground),
                ThemeManager.getColor(ThemeColor.globalOuterBorder), null, null, "Available Now", false, false));
        next.add(new Part("Unavailable", unavailable,
                ThemeManager.getColor(ThemeColor.innerBackground),
                ThemeManager.getColor(ThemeColor.innerBorder),
                ThemeManager.getColor(ThemeColor.innerBackground),
                ThemeManager.getColor(ThemeColor.globalOuterBorder), null, null, "Not Available", false, false));
        float[] nextTargets = new float[next.size()];
        BigInteger running = BigInteger.ZERO;
        for (int i = 0; i < next.size(); i++) {
            running = running.add(next.get(i).amount());
            nextTargets[i] = new BigDecimal(running).divide(new BigDecimal(scale), 8, RoundingMode.HALF_UP).floatValue();
        }
        if (currentEnds.length != nextTargets.length) currentEnds = nextTargets.clone();
        targetEnds = nextTargets;
        drawnEnds = new int[nextTargets.length];
        parts = List.copyOf(next);
    }

    private void refreshStorage() {
        List<Part> next = new ArrayList<>(storageParts.size() + 1);
        Map<String, Accent> accents = ThemeManager.getRegisteredAccents();
        Theme defaults = ThemeManager.getTheme("default");
        BigInteger used = BigInteger.ZERO;
        for (int index = 0; index < storageParts.size(); index++) {
            StoragePart item = storageParts.get(index);
            if (item.bytes() < 0) continue;
            Part colored = serverPart(null, item.name(), BigInteger.valueOf(item.bytes()), index, accents, defaults, "", false, false);
            next.add(colored);
            used = used.add(colored.amount());
        }
        if (storageCapacity.compareTo(used) > 0) {
            next.add(new Part("Unused Allocation", storageCapacity.subtract(used),
                    ThemeManager.getColor(ThemeColor.inClickableBackground),
                    ThemeManager.getColor(ThemeColor.inClickableBorder),
                    ThemeManager.getColor(ThemeColor.inClickableBackground),
                    ThemeManager.getColor(ThemeColor.globalOuterBorder), null, null, "", false, false));
        }
        scale = storageCapacity.max(used).max(BigInteger.ONE);
        float[] nextTargets = new float[next.size()];
        BigInteger running = BigInteger.ZERO;
        for (int index = 0; index < next.size(); index++) {
            running = running.add(next.get(index).amount());
            nextTargets[index] = new BigDecimal(running).divide(new BigDecimal(scale), 8, RoundingMode.HALF_UP).floatValue();
        }
        if (currentEnds.length != nextTargets.length) currentEnds = nextTargets.clone();
        targetEnds = nextTargets;
        drawnEnds = new int[nextTargets.length];
        parts = List.copyOf(next);
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

    private static Part serverPart(String serverId, String name, BigInteger amount, int index,
                                   Map<String, Accent> accents, Theme defaults, String status,
                                   boolean disabled, boolean restartRequired) {
        String accentName = ACCENTS[index % ACCENTS.length];
        Accent accent = accents.get(accentName);
        if (accent == null && defaults != null) accent = defaults.getAccent(accentName);
        if (accent == null) accent = ThemeManager.getDefaultAccent();
        boolean brighter = (index / ACCENTS.length) % 2 == 1;
        return new Part(name, amount, brighter ? accent.getAccentDarkHoverColor() : accent.getAccentDarkColor(),
                brighter ? accent.getAccentHoverColor() : accent.getAccentColor(),
                brighter ? accent.getBottomHoverColor() : accent.getBottomColor(), accent.getOuterBorderColor(),
                serverId, accent, status, disabled, restartRequired);
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
        if (startServerDrag(event.x())) return event.finish(true);
        if (creation == null || creation.available(resource).compareTo(PoolCreationPreview.minimum(resource)) < 0) {
            return super.mouseClicked(event);
        }
        int left = chartLeft();
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
        dragBase = parts.subList(0, newIndex).stream().map(Part::amount).reduce(BigInteger.ZERO, BigInteger::add);
        dragStartValue = creation.value(resource);
        dragStartX = event.x();
        draggedServerId = null;
        dragging = true;
        if (!event.modifiers().shift()) moveBoundary(event.x(), false);
        return event.finish(true);
    }

    private boolean startServerDrag(double mouseX) {
        if (editor == null || editor.locked()) return false;
        int bestIndex = -1;
        double nearest = 7;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.serverId() == null || part.amount().signum() <= 0) continue;
            double distance = Math.abs(mouseX - boundaryAt(i));
            if (distance < nearest) {
                nearest = distance;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) return false;
        String serverId = parts.get(bestIndex).serverId();
        ResourcePoolController.PoolView view = creation == null ? editor.view() : creation.view();
        ResourcePoolModels.Allocation allocation = view.allocations().stream()
                .filter(value -> value.serverId().equals(serverId)).findFirst().orElse(null);
        if (allocation == null || allocation.state() != ResourcePoolModels.AllocationState.ACTIVE
                && allocation.state() != ResourcePoolModels.AllocationState.DISABLED) return false;
        if (editor.busy(serverId) || editor.submitted(serverId)) return false;
        if (editor.selected() == null || !editor.selected().serverId().equals(serverId)) editor.select(serverId);
        draggedServerId = serverId;
        dragBase = parts.subList(0, bestIndex).stream().map(Part::amount).reduce(BigInteger.ZERO, BigInteger::add);
        dragStartValue = editor.value(resource);
        dragStartX = mouseX;
        dragging = true;
        onChange.run();
        return true;
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (!dragging || event.button() != ReMouseButton.LEFT) return super.mouseDragged(event);
        moveBoundary(event.x(), event.modifiers().shift());
        return event.finish(true);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        draggedServerId = null;
        return event.finish(true);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        dragging = false;
        draggedServerId = null;
        super.onRelease(mouseX, mouseY);
    }

    private void moveBoundary(double mouseX, boolean fine) {
        int left = chartLeft();
        int span = Math.max(1, getX() + getWidth() - 1 - left);
        BigInteger amount = fine ? dragStartValue.add(BigInteger.valueOf(Math.round(mouseX - dragStartX)))
                : new BigDecimal(scale).multiply(BigDecimal.valueOf(Math.clamp(mouseX - left, 0, span)))
                .divide(BigDecimal.valueOf(span), 0, RoundingMode.HALF_UP).toBigInteger().subtract(dragBase);
        if (draggedServerId != null) {
            if (editor.selected() == null || !draggedServerId.equals(editor.selected().serverId())) return;
            BigInteger previous = editor.value(resource);
            if (editor.propose(resource, amount) && !previous.equals(editor.value(resource))) onChange.run();
        } else if (creation != null) {
            BigInteger previous = creation.value(resource);
            BigInteger bounded = amount.max(PoolCreationPreview.minimum(resource)).min(creation.available(resource));
            if (creation.set(resource, bounded) && !previous.equals(creation.value(resource))) onChange.run();
        }
    }

    private int chartLeft() {
        return getX() + LABEL_SIZE;
    }

    private int boundaryAt(int index) {
        if (drawnEnds[index] != 0) return drawnEnds[index];
        int left = chartLeft();
        return left + Math.round(targetEnds[index] * Math.max(1, getX() + getWidth() - 1 - left));
    }

    @Override
    protected void drawSurface(IDrawContext ctx) {
        if (theme != ThemeManager.getCurrentTheme()) refresh();
        super.drawSurface(ctx);
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        if (width <= 2 || height <= 2) return;
        int left = chartLeft();
        int right = x + width - 1;
        int top = y + 1;
        int bottom = y + height - 1;
        float factor = dragging || !animationsEnabled ? 1f : Math.min(1f, globalExpandSpeed * deltaTime);
        int start = left;
        Part previous = null;
        for (int i = 0; i < parts.size(); i++) {
            currentEnds[i] += (targetEnds[i] - currentEnds[i]) * factor;
            if (Math.abs(currentEnds[i] - targetEnds[i]) < 0.0005f) currentEnds[i] = targetEnds[i];
            int end = i == parts.size() - 1 ? right : Math.clamp(left + Math.round(currentEnds[i] * (right - left)), start, right);
            drawnEnds[i] = end;
            if (end > start) {
                Part part = parts.get(i);
                int outerStart = start == left ? left - 1 : start;
                int outerEnd = end == right ? x + width + 1 : end;
                ctx.fill(outerStart, y - 1, outerEnd, y, part.outer());
                ctx.fill(start, y, end, top, part.border());
                ctx.fill(start, top, end, bottom - 1, part.background());
                if (part.disabled()) {
                    for (int mark = start + 3; mark < end - 2; mark += 8) {
                        ctx.fill(mark, top + 2, Math.min(mark + 2, end), bottom - 2, part.border());
                    }
                }
                if (part.restartRequired() && end - start >= 13) {
                    ctx.drawText("*", start + 3, y + 3, ThemeManager.getColor(ThemeColor.text), true);
                }
                ctx.fill(start, bottom - 1, end, bottom, part.bottom());
                ctx.fill(start, bottom, end, y + height, part.border());
                ctx.fill(start, y + height, end, y + height + 2, part.bottom());
                ctx.fill(outerStart, y + height + 2, outerEnd, y + height + 3, part.outer());
                if (start == left) {
                    ctx.fill(left - 1, y, left, y + height, part.border());
                }
                if (end == right) {
                    ctx.fill(right, y, x + width, y + height, part.border());
                    ctx.fill(x + width, y, x + width + 1, y + height + 2, part.outer());
                }
                if (previous != null) {
                    ctx.fill(start - 1, y, start, y + height, previous.border());
                    ctx.fill(start, y, start + 1, y + height, part.border());
                }
                start = end;
                previous = part;
            }
        }
    }

    @Override
    protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();
        int height = getHeight();
        ctx.drawPixelArt(icon, x + 1, y + (height - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE);
        String hover = title + " Allocation";
        HoveredServer hoveredServer = null;
        hoverX = mouseX;
        int start = chartLeft();
        for (int i = 0; i < parts.size(); i++) {
            int end = drawnEnds[i];
            if (mouseX >= start && mouseX < end && mouseY >= y && mouseY < y + height) {
                Part part = parts.get(i);
                hover = part.name() + "  " + (storageParts == null ? part.amount() + unit : storageSize(part.amount()));
                if (!part.status().isEmpty()) hover += "  •  " + part.status();
                if (part.restartRequired()) hover += "  •  * Stop Or Restart To Free The Reduction";
                if (part.serverId() != null) hoveredServer = new HoveredServer(part.serverId(), part.accent());
                if (part.serverId() != null && editor != null && editableServers.contains(part.serverId())) {
                    hover += "  •  Drag Divider To Preview";
                }
                if (creation != null && ("New Server".equals(part.name()) || "Free".equals(part.name()))) {
                    hover += "  •  Drag To Allocate";
                }
                break;
            }
            start = end;
        }
        if (!hover.equals(hoveredPart)) {
            hoveredPart = hover;
            setHint(hover);
        }
        onHover.accept(hoveredServer);
    }

    @Override
    protected float hintAnchorX() {
        return Math.clamp(hoverX, getX() + 4, getX() + getWidth() - 4);
    }

    @Override
    protected float hintAnchorWidth() {
        return 0;
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
