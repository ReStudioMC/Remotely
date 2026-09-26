package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

final class PoolCreationPreview {
    private static final BigInteger MINIMUM = BigInteger.ONE;

    private ResourcePoolController.PoolView view;
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> otherValues = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final EnumMap<PoolAllocationEditor.Resource, BigInteger> capacityLimits = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private final BigInteger installerRam;
    private final BigInteger installerCpu;
    private BigInteger ram;
    private BigInteger cpu;
    private BigInteger disk;
    private BigInteger backup = BigInteger.ZERO;

    PoolCreationPreview(ResourcePoolController.PoolView view, ResourcePoolModels.DraftOptions options) {
        this.view = Objects.requireNonNull(view, "view");
        updateOther();
        Objects.requireNonNull(options, "options");
        installerRam = BigInteger.valueOf(Math.max(1, options.minimumInstallerRamMiB()));
        installerCpu = BigInteger.valueOf(Math.max(1, options.minimumInstallerCpuPercent()));
        ram = initial(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(2048));
        cpu = initial(PoolAllocationEditor.Resource.CPU, BigInteger.valueOf(200));
        disk = initial(PoolAllocationEditor.Resource.DISK, BigInteger.valueOf(10240));
    }

    PoolCreationPreview(ResourcePoolController.PoolView view) {
        this(view, new ResourcePoolModels.DraftOptions(List.of()));
    }

    ResourcePoolController.PoolView view() {
        return view;
    }

    void accept(ResourcePoolController.PoolView next) {
        Objects.requireNonNull(next, "next");
        if (!view.pool().id().equals(next.pool().id())) throw new IllegalArgumentException("Resource Pool Changed");
        view = next;
        capacityLimits.clear();
        updateOther();
        for (PoolAllocationEditor.Resource resource : PoolAllocationEditor.Resource.values()) {
            BigInteger available = available(resource);
            BigInteger current = value(resource);
            if (current.compareTo(available) > 0) set(resource, available);
        }
    }

    BigInteger value(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> ram;
            case CPU -> cpu;
            case DISK -> disk;
            case BACKUP -> backup;
        };
    }

    BigInteger available(PoolAllocationEditor.Resource resource) {
        BigInteger reported = PoolAllocationEditor.number(resourceValue(view.pool().balance().available(), resource));
        return reported.min(capacityLimits.getOrDefault(resource, reported));
    }

    void limit(PoolAllocationEditor.Resource resource, BigInteger capacity) {
        capacityLimits.put(resource, Objects.requireNonNull(capacity, "capacity").max(BigInteger.ZERO));
        if (value(resource).compareTo(available(resource)) > 0) set(resource, available(resource));
    }

    BigInteger committed(PoolAllocationEditor.Resource resource) {
        return PoolAllocationEditor.number(resourceValue(view.pool().balance().committed(), resource));
    }

    BigInteger total(PoolAllocationEditor.Resource resource) {
        return PoolAllocationEditor.number(resourceValue(view.pool().balance().entitled(), resource))
                .max(committed(resource).add(PoolAllocationEditor.number(resourceValue(view.pool().balance().available(), resource))));
    }

    BigInteger free(PoolAllocationEditor.Resource resource) {
        return available(resource).subtract(reserved(resource)).max(BigInteger.ZERO);
    }

    BigInteger reserved(PoolAllocationEditor.Resource resource) {
        return switch (resource) {
            case RAM -> ram.max(installerRam);
            case CPU -> cpu.max(installerCpu);
            case DISK -> disk;
            case BACKUP -> backup;
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
                        case BACKUP -> allocation.retained().backupMiB();
                    })).reduce(BigInteger.ZERO, BigInteger::add);
            otherValues.put(resource, committed(resource).subtract(visible).max(BigInteger.ZERO));
        }
    }

    boolean possible() {
        return ram.signum() > 0 && cpu.signum() > 0 && disk.signum() > 0
                && available(PoolAllocationEditor.Resource.RAM).compareTo(installerRam) >= 0
                && available(PoolAllocationEditor.Resource.CPU).compareTo(installerCpu) >= 0;
    }

    void propose(PoolAllocationEditor.Resource resource, double fraction) {
        BigInteger available = available(resource);
        BigInteger minimum = minimum(resource);
        if (available.compareTo(minimum) < 0) return;
        BigInteger span = available.subtract(minimum);
        BigInteger amount = new BigDecimal(span).multiply(BigDecimal.valueOf(Math.clamp(fraction, 0, 1)))
                .setScale(0, RoundingMode.HALF_UP).toBigInteger().add(minimum).min(available);
        set(resource, amount);
    }

    boolean set(PoolAllocationEditor.Resource resource, BigInteger amount) {
        Objects.requireNonNull(amount, "amount");
        BigInteger available = available(resource);
        if (amount.signum() < 0 || amount.compareTo(available) > 0
                || available.signum() > 0 && amount.compareTo(minimum(resource)) < 0) return false;
        switch (resource) {
            case RAM -> ram = amount;
            case CPU -> cpu = amount;
            case DISK -> disk = amount;
            case BACKUP -> backup = amount;
        }
        return true;
    }

    double fraction(PoolAllocationEditor.Resource resource) {
        BigInteger span = available(resource).subtract(minimum(resource));
        if (span.signum() <= 0) return 0;
        return new BigDecimal(value(resource).subtract(minimum(resource)))
                .divide(new BigDecimal(span), 16, RoundingMode.HALF_UP).doubleValue();
    }

    private BigInteger initial(PoolAllocationEditor.Resource resource, BigInteger recommended) {
        BigInteger available = available(resource);
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
            case BACKUP -> resources.backupMiB();
        };
    }
}
