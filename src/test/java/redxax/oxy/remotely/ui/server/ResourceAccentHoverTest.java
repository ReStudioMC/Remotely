package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceAccentHoverTest {
    @Test
    void crossingResourceBarsKeepsTheSameServerHighlighted() {
        ResourceAccentHover hover = new ResourceAccentHover();
        Object ram = new Object();
        Object cpu = new Object();
        Object disk = new Object();
        ResourceAllocationBarWidget.HoveredServer server = new ResourceAllocationBarWidget.HoveredServer("server", null);

        assertNull(hover.update(ram, server, 0));
        assertNull(hover.update(ram, server, 119));
        assertEquals(server, hover.update(ram, server, 120));
        assertEquals(server, hover.update(ram, null, 220));
        assertEquals(server, hover.update(cpu, server, 240));
        assertEquals(server, hover.update(cpu, null, 400));
        assertEquals(server, hover.update(disk, server, 500));
        assertEquals(server, hover.update(disk, null, 600));
        assertEquals(server, hover.update(disk, null, 899));
        assertNull(hover.update(disk, null, 900));
    }

    @Test
    void differentServerTakesOverOnlyAfterAStableHover() {
        ResourceAccentHover hover = new ResourceAccentHover();
        Object ram = new Object();
        Object cpu = new Object();
        ResourceAllocationBarWidget.HoveredServer first = new ResourceAllocationBarWidget.HoveredServer("first", null);
        ResourceAllocationBarWidget.HoveredServer second = new ResourceAllocationBarWidget.HoveredServer("second", null);

        hover.update(ram, first, 0);
        assertEquals(first, hover.update(ram, first, 120));
        assertEquals(first, hover.update(ram, null, 210));
        assertEquals(first, hover.update(cpu, second, 220));
        assertEquals(first, hover.update(cpu, second, 339));
        assertEquals(second, hover.update(cpu, second, 340));
    }
}
