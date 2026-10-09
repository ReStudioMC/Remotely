package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class PoolCreationPreview {
    private static final BigInteger MINIMUM = BigInteger.ONE;

    private ResourcePoolController.PoolView view;
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> otherValues = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> capacityLimits = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> availableValues = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> committedValues = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> totalValues = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private BigInteger installerRam;
    private BigInteger installerCpu;
    private BigInteger ram;
    private BigInteger cpu;
    private BigInteger disk;
    private final List<Runnable> watches = new ArrayList<>();
    private Runnable allocationChanged;

    PoolCreationPreview(ResourcePoolController.PoolView view, ResourcePoolModels.DraftOptions options) {
        this(view, options, 1);
    }

    PoolCreationPreview(ResourcePoolController.PoolView view, ResourcePoolModels.DraftOptions options, int shares) {
        this.view = Objects.requireNonNull(view, "view");
        if (shares < 1) throw new IllegalArgumentException("Pool Shares Must Be Positive");
        readBalance();
        updateOther();
        Objects.requireNonNull(options, "options");
        installerRam = BigInteger.valueOf(Math.max(1, options.minimumInstallerRamMiB()));
        installerCpu = BigInteger.valueOf(Math.max(1, options.minimumInstallerCpuPercent()));
        ram = initial(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(2048), shares);
        cpu = initial(PoolAllocationEditor.Resource.CPU, BigInteger.valueOf(200), shares);
        disk = initial(PoolAllocationEditor.Resource.DISK, BigInteger.valueOf(10240), shares);
    }

    PoolCreationPreview(ResourcePoolController.PoolView view) {
        this(view, new ResourcePoolModels.DraftOptions(List.of()));
    }

    ResourcePoolController.PoolView view() {
        return view;
    }

    void acceptOptions(ResourcePoolModels.DraftOptions options) {
        Objects.requireNonNull(options, "options");
        BigInteger nextRam = BigInteger.valueOf(Math.max(1, options.minimumInstallerRamMiB()));
        BigInteger nextCpu = BigInteger.valueOf(Math.max(1, options.minimumInstallerCpuPercent()));
        if (installerRam.equals(nextRam) && installerCpu.equals(nextCpu)) return;
        installerRam = nextRam;
        installerCpu = nextCpu;
        notifyChanged();
    }

    void accept(ResourcePoolController.PoolView next) {
        Objects.requireNonNull(next, "next");
        if (!view.pool().id().equals(next.pool().id())) throw new IllegalArgumentException("Resource Pool Changed");
        if (view.equals(next)) return;
        view = next;
        capacityLimits.clear();
        readBalance();
        updateOther();
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            BigInteger available = available(resource);
            BigInteger current = value(resource);
            if (current.compareTo(available) > 0) set(resource, available);
        }
        notifyChanged();
    }

    void acceptShared(ResourcePoolController.PoolView next) {
        Objects.requireNonNull(next, "next");
        if (!view.pool().id().equals(next.pool().id())) throw new IllegalArgumentException("Resource Pool Changed");
        if (view.equals(next)) return;
        view = next;
        readBalance();
        updateOther();
    }

    BigInteger value(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> ram;
            case CPU -> cpu;
            case DISK -> disk;
            case BACKUP -> BigInteger.ZERO;
        };
    }

    BigInteger available(PoolAllocationEditor.Resource resource) {
        BigInteger reported = availableValues.get(resource);
        return reported.min(capacityLimits.getOrDefault(resource, reported));
    }

    void limit(PoolAllocationEditor.Resource resource, BigInteger capacity) {
        limit(resource, capacity, true);
    }

    void limit(PoolAllocationEditor.Resource resource, BigInteger capacity, boolean clamp) {
        BigInteger next = Objects.requireNonNull(capacity, "capacity").max(BigInteger.ZERO);
        BigInteger previous = capacityLimits.put(resource, next);
        if (!clamp) return;
        if (value(resource).compareTo(available(resource)) > 0) set(resource, available(resource));
        else if (!next.equals(previous)) notifyChanged();
    }

    Runnable watch(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        watches.add(listener);
        listener.run();
        return () -> watches.remove(listener);
    }

    void onAllocationChanged(Runnable listener) {
        allocationChanged = listener;
    }

    void notifyChanged() {
        for (Runnable listener : List.copyOf(watches)) listener.run();
    }

    BigInteger committed(PoolAllocationEditor.Resource resource) {
        return committedValues.get(resource);
    }

    BigInteger total(PoolAllocationEditor.Resource resource) {
        return totalValues.get(resource);
    }

    BigInteger free(PoolAllocationEditor.Resource resource) {
        return available(resource).subtract(reserved(resource)).max(BigInteger.ZERO);
    }

    BigInteger reserved(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> ram.max(installerRam);
            case CPU -> cpu.max(installerCpu);
            case DISK -> disk;
            case BACKUP -> BigInteger.ZERO;
        };
    }

    BigInteger installerMinimum(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> installerRam;
            case CPU -> installerCpu;
            case DISK -> MINIMUM;
            case BACKUP -> BigInteger.ZERO;
        };
    }

    BigInteger other(PoolAllocationEditor.Resource resource) {
        return otherValues.getOrDefault(resource, BigInteger.ZERO);
    }

    private void updateOther() {
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            BigInteger visible = view.allocations().stream()
                    .map(allocation -> PoolAllocationEditor.number(switch (resource) {
                        case RAM -> allocation.reserved().ramMiB();
                        case CPU -> allocation.reserved().cpuQuotaPercent();
                        case DISK -> allocation.retained().diskMiB();
                        case BACKUP -> "0";
                    })).reduce(BigInteger.ZERO, BigInteger::add);
            otherValues.put(resource, committed(resource).subtract(visible).max(BigInteger.ZERO));
        }
    }

    private void readBalance() {
        ResourcePoolModels.Balance balance = view.pool().balance();
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            BigInteger available = PoolAllocationEditor.number(resourceValue(balance.available(), resource));
            BigInteger committed = PoolAllocationEditor.number(resourceValue(balance.committed(), resource));
            availableValues.put(resource, available);
            committedValues.put(resource, committed);
            totalValues.put(resource, PoolAllocationEditor.number(resourceValue(balance.entitled(), resource)).max(committed.add(available)));
        }
    }

    boolean possible() {
        if (ram.signum() <= 0 || cpu.signum() <= 0 || disk.signum() <= 0) return false;
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            if (reserved(resource).compareTo(available(resource)) > 0) return false;
        }
        return true;
    }

    void propose(PoolAllocationEditor.Resource resource, double fraction) {
        BigInteger available = editLimit(resource);
        BigInteger minimum = minimum(resource);
        if (available.compareTo(minimum) < 0) return;
        BigInteger span = available.subtract(minimum);
        BigInteger amount = new BigDecimal(span).multiply(BigDecimal.valueOf(Math.clamp(fraction, 0, 1)))
                .setScale(0, RoundingMode.HALF_UP).toBigInteger().add(minimum).min(available);
        set(resource, amount);
    }

    boolean set(PoolAllocationEditor.Resource resource, BigInteger amount) {
        Objects.requireNonNull(amount, "amount");
        if (resource == PoolAllocationEditor.Resource.BACKUP) return amount.signum() == 0;
        BigInteger available = available(resource);
        boolean reduction = allocationChanged != null && amount.compareTo(value(resource)) < 0;
        if (amount.signum() < 0 || amount.compareTo(available) > 0 && !reduction
                || allocationChanged != null && amount.compareTo(minimum(resource)) < 0
                || available.signum() > 0 && amount.compareTo(minimum(resource)) < 0) return false;
        if (value(resource).equals(amount)) return true;
        switch (resource) {
            case RAM -> ram = amount;
            case CPU -> cpu = amount;
            case DISK -> disk = amount;
            case BACKUP -> { return false; }
        }
        if (allocationChanged == null) notifyChanged();
        else allocationChanged.run();
        return true;
    }

    double fraction(PoolAllocationEditor.Resource resource) {
        BigInteger span = editLimit(resource).subtract(minimum(resource));
        if (span.signum() <= 0) return 0;
        return new BigDecimal(value(resource).subtract(minimum(resource)))
                .divide(new BigDecimal(span), 16, RoundingMode.HALF_UP).doubleValue();
    }

    BigInteger editLimit(PoolAllocationEditor.Resource resource) {
        return allocationChanged == null ? available(resource) : available(resource).max(value(resource));
    }

    private BigInteger initial(PoolAllocationEditor.Resource resource, BigInteger recommended, int shares) {
        BigInteger available = available(resource).divide(BigInteger.valueOf(shares));
        return available.signum() == 0 ? BigInteger.ZERO : available.min(recommended);
    }

    static BigInteger minimum(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM, CPU, DISK -> MINIMUM;
            case BACKUP -> BigInteger.ZERO;
        };
    }

    private static String resourceValue(ResourcePoolModels.Resources resources, PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> resources.ramMiB();
            case CPU -> resources.cpuQuotaPercent();
            case DISK -> resources.diskMiB();
            case BACKUP -> "0";
        };
    }
}
