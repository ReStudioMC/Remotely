package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import restudio.rebase.resource.ResourcePoolModels;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolAllocationEditorTest {
    @Test
    void manyServerTransferWaitsForSettlementAndPreservesCreationCapacity() {
        UUID poolId = UUID.randomUUID();
        List<ResourcePoolModels.Allocation> initial = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            initial.add(allocation(poolId, "release-" + index, "1", "2048", "200", "2048", "200"));
            initial.add(allocation(poolId, "claim-" + index, "1", "1024", "100", "1024", "100"));
        }
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, initial, "25600", "2500", "24576", "2400", "1024", "100"));
        editor.hold(BigInteger.valueOf(512), BigInteger.ZERO, BigInteger.ZERO);

        for (int index = 0; index < 8; index++) {
            editor.select("release-" + index);
            assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(1024)));
            editor.select("claim-" + index);
            assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(2048)));
        }
        assertEquals(16, editor.changeCount());
        assertEquals(BigInteger.valueOf(512), editor.pendingRelease(PoolAllocationEditor.Resource.RAM));
        assertEquals(BigInteger.ZERO, editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
        assertFalse(editor.independentCapacity());
        for (int index = 0; index < 8; index++) {
            assertTrue(editor.beginReduction("release-" + index));
            assertFalse(editor.canApply("claim-" + index));
        }
        for (int index = 0; index < 8; index++) editor.finish("release-" + index, true);

        List<ResourcePoolModels.Allocation> pending = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ResourcePoolModels.Allocation release = initial.get(index * 2);
            pending.add(new ResourcePoolModels.Allocation(release.serverId(), poolId, "node", "2",
                    new ResourcePoolModels.Compute("1024", "200"), release.reserved(), release.effective(), release.retained(),
                    ResourcePoolModels.AllocationState.PENDING, null, null));
            pending.add(initial.get(index * 2 + 1));
        }
        editor.accept(view(poolId, pending, "25600", "2500", "24576", "2400", "1024", "100"));
        assertEquals(8, editor.changeCount());
        for (int index = 0; index < 8; index++) assertTrue(editor.waitingForCapacity("claim-" + index));

        List<ResourcePoolModels.Allocation> settled = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            settled.add(allocation(poolId, "release-" + index, "3", "1024", "200", "1024", "200"));
            settled.add(initial.get(index * 2 + 1));
        }
        editor.accept(view(poolId, settled, "25600", "2500", "16384", "2400", "9216", "100"));
        assertTrue(editor.independentCapacity());
        for (int index = 0; index < 8; index++) assertTrue(editor.beginIndependent("claim-" + index));
        editor.discardAll();
        assertEquals(8, editor.changeCount());
        for (int index = 0; index < 8; index++) editor.finish("claim-" + index, true);
        assertTrue(editor.hasPending());

        List<ResourcePoolModels.Allocation> complete = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            complete.add(settled.get(index * 2));
            complete.add(allocation(poolId, "claim-" + index, "2", "2048", "100", "2048", "100"));
        }
        editor.accept(view(poolId, complete, "25600", "2500", "24576", "2400", "1024", "100"));
        assertFalse(editor.hasChanges());
        assertFalse(editor.hasPending());
        assertEquals(BigInteger.valueOf(1024), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void independentReductionsCanBeginTogetherButAnIncreaseCannotUseTheirCapacityEarly() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second), "3072", "300", "3072", "300", "0", "0"));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(1024)));
        editor.select("second");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(512)));
        editor.lock(true);

        assertTrue(editor.beginReduction("first"));
        assertTrue(editor.beginReduction("second"));
        assertFalse(editor.beginReduction("first"));
        assertTrue(editor.hasPending());

        editor.finish("first", true);
        editor.finish("second", true);
        assertFalse(editor.canApply("second"));
        assertEquals(BigInteger.ZERO, editor.available(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void independentIncreasesCanBeginTogetherOnlyWhenTheirCombinedClaimFitsFreeCapacity() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "1024", "100", "1024", "100");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second), "4096", "300", "2048", "200", "2048", "100"));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(1536)));
        editor.select("second");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(1536)));
        editor.lock(true);
        assertTrue(editor.independentCapacity());
        assertTrue(editor.beginIndependent("first"));
        assertTrue(editor.beginIndependent("second"));
        assertFalse(editor.beginIndependent("first"));
    }

    @Test
    void switchingServersPreservesBothProposalsAndAccountsForUnloadedReservations() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second), "8192", "800", "4096", "400", "3072", "300"));

        assertEquals(new BigInteger("1024"), editor.other(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("100"), editor.other(PoolAllocationEditor.Resource.CPU));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("6000")));
        assertEquals(new BigInteger("5120"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(BigInteger.ZERO, editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("1024"), editor.allocation(second, PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.changed());

        editor.select("second");
        assertEquals(new BigInteger("1024"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertFalse(editor.changed());
        assertTrue(editor.propose(PoolAllocationEditor.Resource.CPU, new BigInteger("150")));
        assertEquals(new BigInteger("250"), editor.previewAvailable(PoolAllocationEditor.Resource.CPU));
        assertEquals(new BigInteger("5120"), editor.allocation(first, PoolAllocationEditor.Resource.RAM));
        assertEquals(2, editor.changeCount());
        editor.select("first");
        assertEquals(new BigInteger("5120"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.changed());
    }

    @Test
    void previewsShareFreeCapacityAndOnlyChangedRevisionsAreInvalidated() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second), "8192", "800", "4096", "400", "4096", "400"));
        editor.hold(new BigInteger("1024"), BigInteger.ZERO, BigInteger.ZERO);

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("4096")));
        editor.select("second");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("3000")));
        assertEquals(new BigInteger("2048"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("1024"), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
        editor.select("first");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        assertEquals(new BigInteger("1024"), editor.pendingRelease(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("3072"), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));

        ResourcePoolModels.Allocation updatedFirst = allocation(poolId, "first", "2", "1024", "200", "2048", "200");
        editor.accept(view(poolId, List.of(updatedFirst, second), "8192", "800", "4096", "400", "4096", "400"));

        assertEquals(1, editor.changeCount());
        editor.select("second");
        assertEquals(new BigInteger("2048"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.canApply());
    }

    @Test
    void transferCanBePreviewedButWaitsForAuthoritativeFreeCapacityBeforeApply() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second), "3072", "300", "3072", "300", "0", "0"));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        PoolAllocationEditor.Change reduction = editor.changes().getFirst();
        editor.select("second");
        assertEquals(new BigInteger("2048"), editor.limit(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("2048")));
        assertEquals(BigInteger.ZERO, editor.pendingRelease(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.waitingForCapacity("second"));
        assertFalse(editor.canApply());
        editor.select("first");
        assertTrue(editor.canApply());

        ResourcePoolModels.Compute reduced = new ResourcePoolModels.Compute("1024", "200");
        ResourcePoolModels.Allocation pending = new ResourcePoolModels.Allocation("first", poolId, "node", "2",
                reduced, first.reserved(), first.effective(), first.retained(),
                ResourcePoolModels.AllocationState.PENDING, null, null);
        editor.accept(view(poolId, List.of(pending, second), "3072", "300", "3072", "300", "0", "0"));
        assertTrue(editor.applied(reduction));
        assertFalse(editor.reductionSettled(reduction));
        editor.select("second");
        assertEquals(new BigInteger("2048"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.waitingForCapacity("second"));

        ResourcePoolModels.Allocation settled = allocation(poolId, "first", "2", "1024", "200", "1024", "200");
        editor.accept(view(poolId, List.of(settled, second), "3072", "300", "2048", "300", "1024", "0"));
        assertTrue(editor.reductionSettled(reduction));
        assertFalse(editor.waitingForCapacity("second"));
        assertTrue(editor.canApply());
    }

    @Test
    void onePreviewCanApplyFromActualFreeCapacityWhileAnotherWaitsForRelease() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        ResourcePoolModels.Allocation third = allocation(poolId, "third", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second, third), "6144", "500", "4096", "400", "1024", "100"));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        editor.select("second");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("2048")));
        editor.select("third");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("2048")));
        assertTrue(editor.canApply());
        assertTrue(editor.begin());
        editor.select("second");
        assertFalse(editor.canApply());
        editor.finish("third", true);
        assertFalse(editor.canApply());

        ResourcePoolModels.Allocation updatedThird = allocation(poolId, "third", "2", "2048", "100", "2048", "100");
        editor.accept(view(poolId, List.of(first, second, updatedThird), "6144", "500", "5120", "400", "0", "100"));
        assertTrue(editor.waitingForCapacity("second"));
        assertEquals(new BigInteger("2048"), editor.value(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void changeSnapshotKeepsBothServersAndDiscardClearsEveryPreview() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        ResourcePoolModels.Allocation second = allocation(poolId, "second", "1", "1024", "100", "1024", "100");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first, second), "4096", "400", "3072", "300", "1024", "100"));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        editor.select("second");
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("2048")));

        List<PoolAllocationEditor.Change> changes = editor.changes();
        assertEquals(List.of("first", "second"), changes.stream().map(PoolAllocationEditor.Change::serverId).toList());
        editor.lock(true);
        assertFalse(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1500")));
        editor.discardAll();
        assertEquals(2, editor.changeCount());
        editor.lock(false);
        editor.discardAll();
        assertFalse(editor.hasChanges());
        assertEquals(new BigInteger("1024"), editor.value(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void changedAuthorityOnlyCompletesTheMatchingSubmittedChange() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation first = allocation(poolId, "first", "1", "2048", "200", "2048", "200");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(first), "4096", "400", "2048", "200", "2048", "200"));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        PoolAllocationEditor.Change change = editor.changes().getFirst();
        assertTrue(editor.matches(change));
        assertFalse(editor.applied(change));

        ResourcePoolModels.Allocation different = allocation(poolId, "first", "2", "1536", "200", "1536", "200");
        editor.accept(view(poolId, List.of(different), "4096", "400", "1536", "200", "2560", "200"));
        assertFalse(editor.matches(change));
        assertFalse(editor.applied(change));

        ResourcePoolModels.Allocation matching = allocation(poolId, "first", "3", "1024", "200", "1536", "200");
        editor.accept(view(poolId, List.of(matching), "4096", "400", "1536", "200", "2560", "200"));
        assertTrue(editor.applied(change));
    }

    @Test
    void committedRevisionInvalidatesAnOutstandingProposal() {
        UUID poolId = UUID.randomUUID();
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(allocation(poolId, "first", "1", "1024", "100", "1024", "100")),
                "2048", "200", "1024", "100", "1024", "100"));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1536")));
        assertTrue(editor.begin());
        editor.finish(true);
        assertTrue(editor.submitted());

        editor.accept(view(poolId, List.of(allocation(poolId, "first", "2", "1536", "100", "1536", "100")),
                "2048", "200", "1536", "100", "512", "100"));

        assertFalse(editor.submitted());
        assertFalse(editor.changed());
        assertEquals(new BigInteger("1536"), editor.value(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void refreshedRevisionWinsWhenItArrivesBeforeTheRequestCallback() {
        UUID poolId = UUID.randomUUID();
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(allocation(poolId, "first", "1", "1024", "100", "1024", "100")),
                "2048", "200", "1024", "100", "1024", "100"));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1536")));
        assertTrue(editor.begin());

        editor.accept(view(poolId, List.of(allocation(poolId, "first", "2", "1536", "100", "1536", "100")),
                "2048", "200", "1536", "100", "512", "100"));
        editor.finish(true);

        assertFalse(editor.submitted());
        assertFalse(editor.changed());
    }

    @Test
    void pendingRestartShowsCurrentFreeCapacityUntilAChangeIsProposed() {
        UUID poolId = UUID.randomUUID();
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(allocation(poolId, "first", "1", "1536", "100", "1024", "100")),
                "2048", "200", "1024", "100", "1024", "100"));

        assertEquals(new BigInteger("1024"), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("2048")));
        assertEquals(BigInteger.ZERO, editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void selectsAnActiveServerWhenDisabledRowsArriveFirst() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation active = allocation(poolId, "paper", "1", "2048", "150", "2048", "150");
        ResourcePoolModels.Allocation disabled = new ResourcePoolModels.Allocation("draft", poolId, "node", "1",
                new ResourcePoolModels.Compute("1024", "100"), new ResourcePoolModels.Compute("0", "0"),
                new ResourcePoolModels.Compute("0", "0"), new ResourcePoolModels.Storage("1000", "500"),
                ResourcePoolModels.AllocationState.DISABLED, null, null);
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(disabled, active), "2048", "150", "2048", "150", "0", "0"));

        assertEquals("paper", editor.selected().serverId());
    }

    @Test
    void expiredGrantDoesNotBecomeFreeWhenAnAllocationShrinks() {
        UUID poolId = UUID.randomUUID();
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(allocation(poolId, "paper", "1", "2048", "150", "2048", "150")),
                "0", "0", "2048", "150", "0", "0"));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        assertEquals(BigInteger.ZERO, editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void reductionStaysPendingAndCreationReservationLimitsAnIncrease() {
        UUID poolId = UUID.randomUUID();
        ResourcePoolModels.Allocation server = allocation(poolId, "paper", "1", "2048", "150", "2048", "150");
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view(poolId, List.of(server), "4096", "300", "2048", "150", "2048", "150"));
        editor.hold(new BigInteger("1024"), new BigInteger("50"), new BigInteger("1000"));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("1024")));
        assertEquals(new BigInteger("1024"), editor.pendingRelease(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("2048"), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));

        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, new BigInteger("4000")));
        assertEquals(new BigInteger("3072"), editor.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("1024"), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
    }

    private static ResourcePoolController.PoolView view(UUID poolId, List<ResourcePoolModels.Allocation> allocations,
                                                        String totalRam, String totalCpu, String usedRam, String usedCpu,
                                                        String freeRam, String freeCpu) {
        ResourcePoolModels.Resources entitled = new ResourcePoolModels.Resources(totalRam, totalCpu, "20000", "10000");
        ResourcePoolModels.Resources committed = new ResourcePoolModels.Resources(usedRam, usedCpu, "10000", "5000");
        ResourcePoolModels.Resources available = new ResourcePoolModels.Resources(freeRam, freeCpu, "10000", "5000");
        ResourcePoolModels.Resources zero = new ResourcePoolModels.Resources("0", "0", "0", "0");
        ResourcePoolModels.Pool pool = new ResourcePoolModels.Pool(poolId, new ResourcePoolModels.Domain("stage", "shared"),
                new ResourcePoolModels.Balance(entitled, committed, available, zero));
        return new ResourcePoolController.PoolView(pool, List.of(), allocations, Map.of());
    }

    private static ResourcePoolModels.Allocation allocation(UUID poolId, String id, String revision,
                                                             String ram, String cpu, String reservedRam, String reservedCpu) {
        ResourcePoolModels.Compute desired = new ResourcePoolModels.Compute(ram, cpu);
        ResourcePoolModels.Compute reserved = new ResourcePoolModels.Compute(reservedRam, reservedCpu);
        return new ResourcePoolModels.Allocation(id, poolId, "node", revision, desired, reserved, reserved,
                new ResourcePoolModels.Storage("1000", "500"), ResourcePoolModels.AllocationState.ACTIVE, null, null);
    }
}
