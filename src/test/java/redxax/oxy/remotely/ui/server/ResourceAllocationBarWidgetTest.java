package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import restudio.rebase.storage.StorageBreakdownController;
import restudio.rescreen.theme.ThemeManager;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceAllocationBarWidgetTest {
    @Test
    void repeatedStorageNamesRemainIndividuallyVisibleAndRefreshable() {
        ThemeManager.initBrowserDefaults();
        ResourceAllocationBarWidget bar = new ResourceAllocationBarWidget("Storage", List.of(
                new ResourceAllocationBarWidget.StoragePart("Other Files And Folders", 1024),
                new ResourceAllocationBarWidget.StoragePart("Other Files And Folders", 2048)), BigInteger.valueOf(4096));
        bar.setPosition(0, 0);
        bar.setWidth(300);
        bar.setHeight(20);

        assertEquals(BigInteger.valueOf(1024), bar.segmentAt(50, 10).segment().amount());
        assertEquals(BigInteger.valueOf(2048), bar.segmentAt(180, 10).segment().amount());

        bar.updateStorage(List.of(new StorageBreakdownController.StoragePart("Other Files And Folders", 2048),
                new StorageBreakdownController.StoragePart("Other Files And Folders", 1024)), BigInteger.valueOf(4096));

        assertEquals(BigInteger.valueOf(2048), bar.segmentAt(50, 10).segment().amount());
        assertEquals(BigInteger.valueOf(1024), bar.segmentAt(180, 10).segment().amount());
        assertEquals(BigInteger.valueOf(1024), bar.segmentAt(250, 10).segment().amount());
    }
}
