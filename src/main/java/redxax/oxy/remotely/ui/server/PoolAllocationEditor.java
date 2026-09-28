package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class PoolAllocationEditor {
    enum Resource {
        RAM, CPU, DISK, BACKUP
    }

    record Change(String serverId, String revision, BigInteger ram, BigInteger cpu, BigInteger disk, boolean reduction) {}

    private static final class Proposal {
        private ResourcePoolModels.Allocation allocation;
        private BigInteger desiredRam;
        private BigInteger desiredCpu;
        private BigInteger desiredDisk;
        private BigInteger reservedRam;
        private BigInteger reservedCpu;
        private BigInteger reservedDisk;
        private BigInteger ram;
        private BigInteger cpu;
        private BigInteger disk;
        private boolean busy;
        private boolean submitted;
        private String sentRevision;

        private Proposal(ResourcePoolModels.Allocation allocation) {
            this.allocation = allocation;
            readAllocation();
            reset();
        }

        private boolean sameAuthority(ResourcePoolModels.Allocation next) {
            return allocation.revision().equals(next.revision()) && allocation.nodeId().equals(next.nodeId())
                    && allocation.desired().equals(next.desired()) && allocation.retained().equals(next.retained());
        }

        private void accept(ResourcePoolModels.Allocation next) {
            if (allocation.equals(next)) return;
            allocation = next;
            readAllocation();
        }

        private void readAllocation() {
            desiredRam = number(allocation.desired().ramMiB());
            desiredCpu = number(allocation.desired().cpuQuotaPercent());
            desiredDisk = number(allocation.retained().diskMiB());
            reservedRam = number(allocation.reserved().ramMiB());
            reservedCpu = number(allocation.reserved().cpuQuotaPercent());
            reservedDisk = desiredDisk;
        }

        private void reset() {
            ram = desiredRam;
            cpu = desiredCpu;
            disk = desiredDisk;
            busy = false;
            submitted = false;
            sentRevision = null;
        }

        private BigInteger value(Resource resource) {
            return switch (resource) {
                case RAM -> ram;
                case CPU -> cpu;
                case DISK -> disk;
                case BACKUP -> number(allocation.retained().backupMiB());
            };
        }

        private BigInteger desired(Resource resource) {
            return switch (resource) {
                case RAM -> desiredRam;
                case CPU -> desiredCpu;
                case DISK -> desiredDisk;
                case BACKUP -> number(allocation.retained().backupMiB());
            };
        }

        private BigInteger reserved(Resource resource) {
            return switch (resource) {
                case RAM -> reservedRam;
                case CPU -> reservedCpu;
                case DISK -> reservedDisk;
                case BACKUP -> number(allocation.retained().backupMiB());
            };
        }

        private BigInteger delta(Resource resource) {
            return resource == Resource.BACKUP || value(resource).equals(desired(resource))
                    && allocation.state() != ResourcePoolModels.AllocationState.PENDING ? BigInteger.ZERO
                    : value(resource).subtract(reserved(resource));
        }

        private boolean changed() {
            return !ram.equals(desiredRam) || !cpu.equals(desiredCpu) || !disk.equals(desiredDisk);
        }
    }

    private ResourcePoolController.PoolView view;
    private final Map<String, Proposal> proposals = new HashMap<>();
    private final EnumMap<Resource, BigInteger> otherValues = new EnumMap<>(Resource.class);
    private final EnumMap<Resource, BigInteger> increases = new EnumMap<>(Resource.class);
    private final EnumMap<Resource, BigInteger> releases = new EnumMap<>(Resource.class);
    private ResourcePoolModels.Allocation selected;
    private BigInteger heldRam = BigInteger.ZERO;
    private BigInteger heldCpu = BigInteger.ZERO;
    private BigInteger heldDisk = BigInteger.ZERO;
    private int changedCount;
    private boolean locked;

    void accept(ResourcePoolController.PoolView next) {
        Objects.requireNonNull(next, "next");
        if (view != null && !view.pool().id().equals(next.pool().id())) throw new IllegalArgumentException("Resource Pool Changed");
        view = next;
        Map<String, Proposal> current = new HashMap<>();
        for (ResourcePoolModels.Allocation allocation : next.allocations()) {
            Proposal proposal = proposals.get(allocation.serverId());
            if (proposal == null || !proposal.sameAuthority(allocation)) proposal = new Proposal(allocation);
            else proposal.accept(allocation);
            current.put(allocation.serverId(), proposal);
        }
        proposals.clear();
        proposals.putAll(current);
        selected = selected == null ? null : next.allocations().stream()
                .filter(allocation -> allocation.serverId().equals(selected.serverId())).findFirst().orElse(null);
        if (selected == null && !next.allocations().isEmpty()) {
            selected = next.allocations().stream()
                    .filter(allocation -> allocation.state() == ResourcePoolModels.AllocationState.ACTIVE)
                    .findFirst().orElse(next.allocations().getFirst());
        }
        for (Resource resource : Resource.values()) {
            BigInteger visible = next.allocations().stream().map(allocation -> proposals.get(allocation.serverId()).reserved(resource))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            otherValues.put(resource, number(resourceValue(next.pool().balance().committed(), resource))
                    .subtract(visible).max(BigInteger.ZERO));
        }
        updateTotals();
    }

    void select(String serverId) {
        if (view == null) return;
        Proposal proposal = proposals.get(serverId);
        if (proposal != null) selected = proposal.allocation;
    }

    ResourcePoolController.PoolView view() {
        return view;
    }

    ResourcePoolModels.Allocation selected() {
        return selected;
    }

    private Proposal current() {
        return selected == null ? null : proposals.get(selected.serverId());
    }

    BigInteger value(Resource resource) {
        Proposal proposal = current();
        return proposal == null ? BigInteger.ZERO : proposal.value(resource);
    }

    BigInteger limit(Resource resource) {
        Proposal proposal = current();
        if (proposal == null) return BigInteger.ZERO;
        BigInteger held = held(resource);
        BigInteger otherIncreases = increases.getOrDefault(resource, BigInteger.ZERO)
                .subtract(proposal.delta(resource).max(BigInteger.ZERO));
        BigInteger otherReleases = releases.getOrDefault(resource, BigInteger.ZERO)
                .subtract(proposal.delta(resource).negate().max(BigInteger.ZERO));
        return proposal.reserved(resource).add(available(resource).subtract(held)
                .subtract(otherIncreases).add(otherReleases).max(BigInteger.ZERO));
    }

    private BigInteger applyLimit(Resource resource, Proposal proposal) {
        return proposal.reserved(resource).add(available(resource).subtract(held(resource))
                .max(BigInteger.ZERO));
    }

    private BigInteger held(Resource resource) {
        return switch (resource) {
            case RAM -> heldRam;
            case CPU -> heldCpu;
            case DISK -> heldDisk;
            case BACKUP -> BigInteger.ZERO;
        };
    }

    void hold(BigInteger ram, BigInteger cpu, BigInteger disk) {
        heldRam = Objects.requireNonNull(ram, "ram").max(BigInteger.ZERO);
        heldCpu = Objects.requireNonNull(cpu, "cpu").max(BigInteger.ZERO);
        heldDisk = Objects.requireNonNull(disk, "disk").max(BigInteger.ZERO);
    }

    BigInteger total(Resource resource) {
        if (view == null) return BigInteger.ZERO;
        ResourcePoolModels.Balance balance = view.pool().balance();
        BigInteger committed = number(resourceValue(balance.committed(), resource));
        BigInteger available = number(resourceValue(balance.available(), resource));
        return number(resourceValue(balance.entitled(), resource)).max(committed.add(available));
    }

    BigInteger available(Resource resource) {
        return view == null ? BigInteger.ZERO : number(resourceValue(view.pool().balance().available(), resource));
    }

    BigInteger reserved(Resource resource) {
        Proposal proposal = current();
        return proposal == null ? BigInteger.ZERO : proposal.reserved(resource);
    }

    BigInteger allocation(ResourcePoolModels.Allocation allocation, Resource resource) {
        Proposal proposal = proposals.get(allocation.serverId());
        if (proposal != null) return proposal.delta(resource).signum() == 0
                ? proposal.reserved(resource) : proposal.value(resource);
        return number(switch (resource) {
            case RAM -> allocation.reserved().ramMiB();
            case CPU -> allocation.reserved().cpuQuotaPercent();
            case DISK -> allocation.retained().diskMiB();
            case BACKUP -> allocation.retained().backupMiB();
        });
    }

    BigInteger other(Resource resource) {
        return otherValues.getOrDefault(resource, BigInteger.ZERO);
    }

    BigInteger pendingRelease(Resource resource) {
        BigInteger plannedTransfer = increases.getOrDefault(resource, BigInteger.ZERO).add(held(resource))
                .subtract(available(resource)).max(BigInteger.ZERO);
        return releases.getOrDefault(resource, BigInteger.ZERO).subtract(plannedTransfer).max(BigInteger.ZERO);
    }

    BigInteger previewAvailable(Resource resource) {
        return available(resource).subtract(increases.getOrDefault(resource, BigInteger.ZERO)).max(BigInteger.ZERO);
    }

    boolean propose(Resource resource, BigInteger next) {
        Proposal proposal = current();
        if (proposal == null || locked || proposal.busy || proposal.submitted || resource == Resource.BACKUP) return false;
        if (proposal.allocation.state() != ResourcePoolModels.AllocationState.ACTIVE
                && proposal.allocation.state() != ResourcePoolModels.AllocationState.DISABLED) return false;
        BigInteger maximum = limit(resource);
        if (maximum.signum() <= 0) return false;
        BigInteger bounded = next.max(BigInteger.ONE).min(maximum);
        boolean wasChanged = proposal.changed();
        BigInteger oldDelta = proposal.delta(resource);
        switch (resource) {
            case RAM -> proposal.ram = bounded;
            case CPU -> proposal.cpu = bounded;
            case DISK -> proposal.disk = bounded;
            case BACKUP -> { return false; }
        }
        adjustTotal(resource, oldDelta, proposal.delta(resource));
        if (wasChanged != proposal.changed()) changedCount += proposal.changed() ? 1 : -1;
        return true;
    }

    void reset() {
        Proposal proposal = current();
        if (proposal == null || locked || proposal.busy || proposal.submitted) return;
        proposal.reset();
        updateTotals();
    }

    void discardAll() {
        if (locked) return;
        proposals.values().stream().filter(proposal -> !proposal.busy && !proposal.submitted).forEach(Proposal::reset);
        updateTotals();
    }

    void lock(boolean value) {
        locked = value;
    }

    boolean locked() {
        return locked;
    }

    List<Change> changes() {
        List<Change> changes = new ArrayList<>();
        proposals.forEach((id, proposal) -> {
            if (!proposal.changed()) return;
            boolean reduction = proposal.ram.compareTo(proposal.reservedRam) <= 0
                    && proposal.cpu.compareTo(proposal.reservedCpu) <= 0
                    && proposal.disk.compareTo(proposal.reservedDisk) <= 0;
            changes.add(new Change(id, proposal.allocation.revision(), proposal.ram, proposal.cpu, proposal.disk, reduction));
        });
        changes.sort(Comparator.comparing(Change::reduction).reversed().thenComparing(Change::serverId));
        return List.copyOf(changes);
    }

    boolean matches(Change change) {
        Proposal proposal = proposals.get(change.serverId());
        return proposal != null && proposal.allocation.revision().equals(change.revision())
                && proposal.ram.equals(change.ram()) && proposal.cpu.equals(change.cpu()) && proposal.disk.equals(change.disk());
    }

    boolean pending(Change change, UUID requestId) {
        ResourcePoolModels.Operation operation = ownedOperation(change, requestId);
        if (operation == null || operation.state() != ResourcePoolModels.OperationState.PENDING
                && operation.state() != ResourcePoolModels.OperationState.SETTLED) return false;
        ResourcePoolModels.Allocation allocation = proposals.get(change.serverId()).allocation;
        return allocation.state() == ResourcePoolModels.AllocationState.PENDING
                && requestId.equals(allocation.currentRequestId()) && allocation.reserved().equals(operation.reserved());
    }

    boolean unknown(Change change, UUID requestId) {
        ResourcePoolModels.Operation operation = ownedOperation(change, requestId);
        return operation != null && operation.state() == ResourcePoolModels.OperationState.UNKNOWN;
    }

    private ResourcePoolModels.Operation ownedOperation(Change change, UUID requestId) {
        Proposal proposal = proposals.get(change.serverId());
        if (proposal == null || requestId == null) return null;
        ResourcePoolModels.Allocation allocation = proposal.allocation;
        ResourcePoolModels.Progress progress = view.progress().get(requestId);
        if (progress == null || allocation.currentRequestId() != null
                && !requestId.equals(allocation.currentRequestId())) return null;
        ResourcePoolModels.Operation operation = progress.operation();
        boolean owned = operation.requestId().equals(requestId) && operation.serverId().equals(change.serverId())
                && operation.action() == ResourcePoolModels.Action.ASSIGN
                && allocation.nodeId().equals(operation.nodeId())
                && allocation.revision().equals(operation.allocationRevision())
                && !allocation.revision().equals(change.revision())
                && allocation.desired().equals(operation.desired())
                && number(operation.desired().ramMiB()).equals(change.ram())
                && number(operation.desired().cpuQuotaPercent()).equals(change.cpu())
                && number(operation.retained().diskMiB()).equals(change.disk())
                && allocation.retained().backupMiB().equals(operation.retained().backupMiB())
                && number(allocation.retained().diskMiB()).compareTo(change.disk()) >= 0;
        return owned ? operation : null;
    }

    boolean applied(Change change) {
        Proposal proposal = proposals.get(change.serverId());
        return proposal != null && !proposal.allocation.revision().equals(change.revision())
                && proposal.desiredRam.equals(change.ram()) && proposal.desiredCpu.equals(change.cpu())
                && proposal.desiredDisk.equals(change.disk());
    }

    boolean reductionSettled(Change change) {
        if (!change.reduction() || !applied(change)) return false;
        ResourcePoolModels.Allocation allocation = proposals.get(change.serverId()).allocation;
        return (allocation.state() == ResourcePoolModels.AllocationState.ACTIVE
                || allocation.state() == ResourcePoolModels.AllocationState.DISABLED)
                && number(allocation.reserved().ramMiB()).compareTo(change.ram()) <= 0
                && number(allocation.reserved().cpuQuotaPercent()).compareTo(change.cpu()) <= 0
                && number(allocation.effective().ramMiB()).compareTo(change.ram()) <= 0
                && number(allocation.effective().cpuQuotaPercent()).compareTo(change.cpu()) <= 0
                && number(allocation.retained().diskMiB()).compareTo(change.disk()) <= 0;
    }

    boolean changed() {
        Proposal proposal = current();
        return proposal != null && proposal.changed();
    }

    boolean changed(String serverId) {
        Proposal proposal = proposals.get(serverId);
        return proposal != null && proposal.changed();
    }

    int changeCount() {
        return changedCount;
    }

    boolean hasChanges() {
        return changedCount > 0;
    }

    boolean hasPending() {
        return proposals.values().stream().anyMatch(proposal -> proposal.busy || proposal.submitted);
    }

    boolean canApply() {
        return selected != null && canApply(selected.serverId());
    }

    boolean canApply(String serverId) {
        Proposal proposal = proposals.get(serverId);
        return proposal != null && proposal.changed() && !hasPending()
                && (proposal.allocation.state() == ResourcePoolModels.AllocationState.ACTIVE
                || proposal.allocation.state() == ResourcePoolModels.AllocationState.DISABLED)
                && proposal.ram.signum() > 0 && proposal.cpu.signum() > 0 && proposal.disk.signum() > 0
                && !waitingForCapacity(proposal);
    }

    boolean independentCapacity() {
        if (view == null) return false;
        for (Resource resource : List.of(Resource.RAM, Resource.CPU, Resource.DISK)) {
            if (increases.getOrDefault(resource, BigInteger.ZERO).add(held(resource)).compareTo(available(resource)) > 0) return false;
        }
        return true;
    }

    boolean waitingForCapacity(String serverId) {
        Proposal proposal = proposals.get(serverId);
        return proposal != null && proposal.changed() && waitingForCapacity(proposal);
    }

    private boolean waitingForCapacity(Proposal proposal) {
        return proposal.ram.compareTo(applyLimit(Resource.RAM, proposal)) > 0
                || proposal.cpu.compareTo(applyLimit(Resource.CPU, proposal)) > 0
                || proposal.disk.compareTo(applyLimit(Resource.DISK, proposal)) > 0;
    }

    boolean busy() {
        Proposal proposal = current();
        return proposal != null && proposal.busy;
    }

    boolean submitted() {
        Proposal proposal = current();
        return proposal != null && proposal.submitted;
    }

    boolean busy(String serverId) {
        Proposal proposal = proposals.get(serverId);
        return proposal != null && proposal.busy;
    }

    boolean submitted(String serverId) {
        Proposal proposal = proposals.get(serverId);
        return proposal != null && proposal.submitted;
    }

    boolean begin() {
        return selected != null && begin(selected.serverId());
    }

    boolean begin(String serverId) {
        if (!canApply(serverId)) return false;
        return markBusy(serverId);
    }

    boolean beginReduction(String serverId) {
        Proposal proposal = proposals.get(serverId);
        if (proposal == null || proposal.busy || proposal.submitted || !proposal.changed()
                || proposal.ram.signum() <= 0 || proposal.cpu.signum() <= 0
                || proposal.disk.signum() <= 0 || waitingForCapacity(proposal)
                || proposal.ram.compareTo(proposal.reservedRam) > 0
                || proposal.cpu.compareTo(proposal.reservedCpu) > 0
                || proposal.disk.compareTo(proposal.reservedDisk) > 0
                || proposal.allocation.state() != ResourcePoolModels.AllocationState.ACTIVE
                && proposal.allocation.state() != ResourcePoolModels.AllocationState.DISABLED) return false;
        return markBusy(serverId);
    }

    boolean beginIndependent(String serverId) {
        Proposal proposal = proposals.get(serverId);
        if (proposal == null || !independentCapacity() || proposal.busy || proposal.submitted || !proposal.changed()
                || proposal.ram.signum() <= 0 || proposal.cpu.signum() <= 0 || proposal.disk.signum() <= 0
                || waitingForCapacity(proposal)
                || proposal.allocation.state() != ResourcePoolModels.AllocationState.ACTIVE
                && proposal.allocation.state() != ResourcePoolModels.AllocationState.DISABLED) return false;
        return markBusy(serverId);
    }

    private boolean markBusy(String serverId) {
        Proposal proposal = proposals.get(serverId);
        proposal.busy = true;
        proposal.sentRevision = proposal.allocation.revision();
        return true;
    }

    ResourcePoolModels.Allocation allocation(String serverId) {
        Proposal proposal = proposals.get(serverId);
        return proposal == null ? null : proposal.allocation;
    }

    void finish(String serverId, boolean success) {
        Proposal proposal = proposals.get(serverId);
        if (proposal == null || proposal.sentRevision == null) return;
        proposal.busy = false;
        proposal.submitted = success && proposal.allocation.revision().equals(proposal.sentRevision);
        proposal.sentRevision = null;
    }

    void finish(boolean success) {
        if (selected != null) finish(selected.serverId(), success);
    }

    private void updateTotals() {
        changedCount = (int) proposals.values().stream().filter(Proposal::changed).count();
        for (Resource resource : Resource.values()) {
            BigInteger increase = BigInteger.ZERO;
            BigInteger release = BigInteger.ZERO;
            for (Proposal proposal : proposals.values()) {
                BigInteger delta = proposal.delta(resource);
                increase = increase.add(delta.max(BigInteger.ZERO));
                release = release.add(delta.negate().max(BigInteger.ZERO));
            }
            increases.put(resource, increase);
            releases.put(resource, release);
        }
    }

    private void adjustTotal(Resource resource, BigInteger before, BigInteger after) {
        increases.put(resource, increases.getOrDefault(resource, BigInteger.ZERO)
                .subtract(before.max(BigInteger.ZERO)).add(after.max(BigInteger.ZERO)));
        releases.put(resource, releases.getOrDefault(resource, BigInteger.ZERO)
                .subtract(before.negate().max(BigInteger.ZERO)).add(after.negate().max(BigInteger.ZERO)));
    }

    static BigInteger number(String value) {
        return new BigInteger(value);
    }

    private static String resourceValue(ResourcePoolModels.Resources resources, Resource resource) {
        return switch (resource) {
            case RAM -> resources.ramMiB();
            case CPU -> resources.cpuQuotaPercent();
            case DISK -> resources.diskMiB();
            case BACKUP -> resources.backupMiB();
        };
    }
}
