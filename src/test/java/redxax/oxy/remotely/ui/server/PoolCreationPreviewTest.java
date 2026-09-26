package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.ThemeManager;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolCreationPreviewTest {
    @Test
    void draggingAProposedServerReallocatesOnlyCurrentFreeCapacity() {
        PoolCreationPreview preview = preview("4096", "300", "2048", "100", "8192", "2048");
        assertTrue(preview.possible());
        assertEquals(new BigInteger("2048"), preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("2048"), preview.free(PoolAllocationEditor.Resource.RAM));

        preview.propose(PoolAllocationEditor.Resource.RAM, 1);
        preview.propose(PoolAllocationEditor.Resource.CPU, 1);
        assertEquals(new BigInteger("4096"), preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(BigInteger.ZERO, preview.free(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("300"), preview.value(PoolAllocationEditor.Resource.CPU));
        assertEquals(new BigInteger("2048"), preview.committed(PoolAllocationEditor.Resource.RAM));

        preview.propose(PoolAllocationEditor.Resource.RAM, 0);
        assertEquals(BigInteger.ONE, preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("4095"), preview.free(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void expiredCapacityCannotBeOfferedToANewServer() {
        PoolCreationPreview preview = preview("0", "0", "2048", "150", "0", "0");
        assertFalse(preview.possible());
        preview.propose(PoolAllocationEditor.Resource.RAM, 1);
        assertEquals(BigInteger.ZERO, preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(BigInteger.ZERO, preview.free(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void setupBudgetStaysReservedWhenRuntimeIsSmaller() {
        PoolCreationPreview preview = preview("512", "100", "0", "0", "1024", "0", 256, 50);
        preview.propose(PoolAllocationEditor.Resource.RAM, 0);
        preview.propose(PoolAllocationEditor.Resource.CPU, 0);
        assertEquals(BigInteger.ONE, preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("256"), preview.reserved(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("256"), preview.free(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("50"), preview.reserved(PoolAllocationEditor.Resource.CPU));
        assertEquals(new BigInteger("50"), preview.free(PoolAllocationEditor.Resource.CPU));
        assertTrue(preview.possible());
        assertFalse(preview("249", "49", "0", "0", "1024", "0", 256, 50).possible());
    }

    @Test
    void refreshedPoolPreservesTheProposalAndClampsItToReportedCapacity() {
        PoolCreationPreview preview = preview("4096", "300", "2048", "100", "8192", "2048");
        assertTrue(preview.set(PoolAllocationEditor.Resource.RAM, new BigInteger("3500")));
        ResourcePoolController.PoolView old = preview.view();
        ResourcePoolModels.Balance previous = old.pool().balance();
        ResourcePoolModels.Resources available = new ResourcePoolModels.Resources("3000", "300", "8192", "2048");
        ResourcePoolModels.Pool pool = new ResourcePoolModels.Pool(old.pool().id(), old.pool().domain(),
                new ResourcePoolModels.Balance(previous.entitled(), previous.committed(), available, previous.deficit()));

        preview.accept(new ResourcePoolController.PoolView(pool, old.drafts(), old.allocations(), old.progress()));

        assertEquals(new BigInteger("3000"), preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(BigInteger.ZERO, preview.free(PoolAllocationEditor.Resource.RAM));
    }

    @Test
    void pendingAndDisabledAllocationsUseTheSamePoolBaselineAsResources() {
        PoolCreationPreview preview = preview("4096", "300", "2048", "100", "8192", "0");
        ResourcePoolController.PoolView previous = preview.view();
        ResourcePoolModels.Compute pendingCompute = new ResourcePoolModels.Compute("1024", "100");
        ResourcePoolModels.Compute zeroCompute = new ResourcePoolModels.Compute("0", "0");
        ResourcePoolModels.Allocation pending = new ResourcePoolModels.Allocation("pending", previous.pool().id(), "node", "1",
                pendingCompute, pendingCompute, pendingCompute, new ResourcePoolModels.Storage("512", "0"),
                ResourcePoolModels.AllocationState.PENDING, null, null);
        ResourcePoolModels.Allocation disabled = new ResourcePoolModels.Allocation("disabled", previous.pool().id(), "node", "1",
                zeroCompute, zeroCompute, zeroCompute, new ResourcePoolModels.Storage("512", "0"),
                ResourcePoolModels.AllocationState.DISABLED, null, null);
        ResourcePoolController.PoolView view = new ResourcePoolController.PoolView(previous.pool(), List.of(),
                List.of(pending, disabled), Map.of());
        preview.accept(view);
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view);

        for (PoolAllocationEditor.Resource resource : List.of(PoolAllocationEditor.Resource.RAM,
                PoolAllocationEditor.Resource.CPU, PoolAllocationEditor.Resource.DISK)) {
            assertEquals(editor.other(resource), preview.other(resource));
            assertEquals(editor.total(resource), preview.total(resource));
        }
    }

    @Test
    void serverChangesReduceTheCapacityOfferedDuringCreationWithoutShrinkingThePool() {
        PoolCreationPreview preview = preview("4096", "300", "2048", "100", "8192", "0");
        ResourcePoolController.PoolView previous = preview.view();
        ResourcePoolModels.Compute compute = new ResourcePoolModels.Compute("2048", "100");
        ResourcePoolModels.Allocation allocation = new ResourcePoolModels.Allocation("existing", previous.pool().id(), "node", "1",
                compute, compute, compute, new ResourcePoolModels.Storage("1024", "0"),
                ResourcePoolModels.AllocationState.ACTIVE, null, null);
        ResourcePoolController.PoolView view = new ResourcePoolController.PoolView(previous.pool(), List.of(), List.of(allocation), Map.of());
        preview.accept(view);
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(view);
        assertTrue(editor.propose(PoolAllocationEditor.Resource.RAM, BigInteger.valueOf(3072)));

        BigInteger total = preview.total(PoolAllocationEditor.Resource.RAM);
        preview.limit(PoolAllocationEditor.Resource.RAM, editor.previewAvailable(PoolAllocationEditor.Resource.RAM));

        assertEquals(new BigInteger("3072"), preview.available(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("2048"), preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("1024"), preview.free(PoolAllocationEditor.Resource.RAM));
        assertEquals(total, preview.total(PoolAllocationEditor.Resource.RAM));
        assertFalse(preview.set(PoolAllocationEditor.Resource.RAM, new BigInteger("3500")));
    }

    @Test
    void draggingTheCapacityBoundaryUpdatesTheSameProposal() {
        ThemeManager.initBrowserDefaults();
        PoolCreationPreview preview = preview("4096", "300", "2048", "100", "8192", "2048");
        ResourceAllocationBarWidget bar = new ResourceAllocationBarWidget(preview, PoolAllocationEditor.Resource.RAM, id -> id);
        bar.setPosition(0, 0);
        bar.setWidth(300);
        bar.setHeight(20);
        AtomicInteger changes = new AtomicInteger();
        bar.onChange(changes::incrementAndGet);

        assertTrue(bar.mouseClicked(mouse(bar, ReMouseEvent.Action.PRESSED, 200)));
        assertTrue(bar.mouseDragged(mouse(bar, ReMouseEvent.Action.DRAGGED, 250)));
        assertTrue(preview.value(PoolAllocationEditor.Resource.RAM).compareTo(new BigInteger("2048")) > 0);
        bar.mouseReleased(mouse(bar, ReMouseEvent.Action.RELEASED, 250));
        BigInteger released = preview.value(PoolAllocationEditor.Resource.RAM);
        assertFalse(bar.mouseDragged(mouse(bar, ReMouseEvent.Action.DRAGGED, 150)));
        assertEquals(released, preview.value(PoolAllocationEditor.Resource.RAM));
        assertTrue(changes.get() >= 2);
    }

    @Test
    void draggingAnExistingDividerPreviewsItWithoutSpendingReleasedCapacity() {
        ThemeManager.initBrowserDefaults();
        PoolCreationPreview preview = preview("4096", "200", "1024", "100", "8192", "2048");
        ResourcePoolController.PoolView previous = preview.view();
        ResourcePoolModels.Compute compute = new ResourcePoolModels.Compute("1024", "100");
        ResourcePoolModels.Allocation allocation = new ResourcePoolModels.Allocation("test", previous.pool().id(), "node", "1",
                compute, compute, compute, new ResourcePoolModels.Storage("1000", "500"),
                ResourcePoolModels.AllocationState.ACTIVE, null, null);
        preview.accept(new ResourcePoolController.PoolView(previous.pool(), List.of(), List.of(allocation), Map.of()));
        PoolAllocationEditor editor = new PoolAllocationEditor();
        editor.accept(preview.view());
        editor.hold(preview.reserved(PoolAllocationEditor.Resource.RAM), preview.reserved(PoolAllocationEditor.Resource.CPU),
                preview.reserved(PoolAllocationEditor.Resource.DISK));
        ResourceAllocationBarWidget bar = new ResourceAllocationBarWidget(preview, editor, PoolAllocationEditor.Resource.RAM, id -> id);
        bar.setPosition(0, 0);
        bar.setWidth(300);
        bar.setHeight(20);
        int left = 18;
        int divider = left + Math.round(1024f / 5120f * (299 - left));
        bar.onChange(bar::refresh);

        assertTrue(bar.mouseClicked(mouse(bar, ReMouseEvent.Action.PRESSED, divider)));
        assertTrue(bar.mouseDragged(mouse(bar, ReMouseEvent.Action.DRAGGED, divider - 15)));
        bar.mouseReleased(mouse(bar, ReMouseEvent.Action.RELEASED, divider - 15));

        assertTrue(editor.value(PoolAllocationEditor.Resource.RAM).compareTo(new BigInteger("1024")) < 0);
        assertEquals(new BigInteger("2048"), preview.value(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("2048"), preview.free(PoolAllocationEditor.Resource.RAM));
        assertEquals(new BigInteger("4096"), editor.previewAvailable(PoolAllocationEditor.Resource.RAM));
        assertTrue(editor.pendingRelease(PoolAllocationEditor.Resource.RAM).signum() > 0);
    }

    private static ReMouseEvent mouse(ResourceAllocationBarWidget bar, ReMouseEvent.Action action, double x) {
        return new ReMouseEvent(bar, bar, 0, ReModifierState.none(), action, x, 10, 0, 0, ReMouseButton.LEFT, 0, 1);
    }

    private static PoolCreationPreview preview(String freeRam, String freeCpu, String usedRam, String usedCpu,
                                               String freeDisk, String freeBackup) {
        return preview(freeRam, freeCpu, usedRam, usedCpu, freeDisk, freeBackup, 0, 0);
    }

    private static PoolCreationPreview preview(String freeRam, String freeCpu, String usedRam, String usedCpu,
                                               String freeDisk, String freeBackup, int setupRam, int setupCpu) {
        UUID id = UUID.randomUUID();
        ResourcePoolModels.Resources committed = new ResourcePoolModels.Resources(usedRam, usedCpu, "1024", "0");
        ResourcePoolModels.Resources available = new ResourcePoolModels.Resources(freeRam, freeCpu, freeDisk, freeBackup);
        ResourcePoolModels.Resources entitled = new ResourcePoolModels.Resources(
                new BigInteger(usedRam).add(new BigInteger(freeRam)).toString(),
                new BigInteger(usedCpu).add(new BigInteger(freeCpu)).toString(),
                new BigInteger("1024").add(new BigInteger(freeDisk)).toString(), freeBackup);
        ResourcePoolModels.Resources zero = new ResourcePoolModels.Resources("0", "0", "0", "0");
        ResourcePoolModels.Pool pool = new ResourcePoolModels.Pool(id, new ResourcePoolModels.Domain("stage", "shared"),
                new ResourcePoolModels.Balance(entitled, committed, available, zero));
        return new PoolCreationPreview(new ResourcePoolController.PoolView(pool, List.of(), List.of(), Map.of()),
                new ResourcePoolModels.DraftOptions(List.of(), setupRam, setupCpu));
    }
}
