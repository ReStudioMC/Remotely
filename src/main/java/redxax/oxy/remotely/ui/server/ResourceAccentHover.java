package redxax.oxy.remotely.ui.server;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

final class ResourceAccentHover {
    private static final long ENTRY_DELAY_MS = 120;
    private static final long EXIT_DELAY_MS = 300;

    private final Map<Object, ResourceAllocationBarWidget.HoveredServer> bars = new IdentityHashMap<>();
    private ResourceAllocationBarWidget.HoveredServer target;
    private ResourceAllocationBarWidget.HoveredServer shown;
    private long targetSince;

    ResourceAllocationBarWidget.HoveredServer shown() {
        return shown;
    }

    ResourceAllocationBarWidget.HoveredServer update(Object bar, ResourceAllocationBarWidget.HoveredServer hover, long now) {
        if (hover == null) bars.remove(bar);
        else bars.put(bar, hover);
        ResourceAllocationBarWidget.HoveredServer next = hover;
        if (next == null && !bars.isEmpty()) next = bars.values().iterator().next();
        if (!Objects.equals(target, next)) {
            target = next;
            targetSince = now;
        }
        if (Objects.equals(shown, target)) return shown;
        long delay = target == null ? EXIT_DELAY_MS
                : shown != null && shown.serverId().equals(target.serverId()) ? 0 : ENTRY_DELAY_MS;
        if (now - targetSince >= delay) shown = target;
        return shown;
    }

    void clear() {
        bars.clear();
        target = null;
        shown = null;
        targetSince = 0;
    }
}
