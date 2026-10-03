package redxax.oxy.remotely.ui.server;

import restudio.rescreen.platform.Async;

import java.util.List;
import java.util.UUID;

public interface NewTerminalTargetProvider {
    NewTerminalTargetProvider LOCAL = targets -> Async.completed(UUID.randomUUID().toString());

    record Shell(String id, String hostId, String name) {
        public Shell {
            if (id == null || id.isBlank() || hostId == null || hostId.isBlank()) {
                throw new IllegalArgumentException("SSH Terminal Identity Is Required");
            }
            name = name == null || name.isBlank() ? hostId : name;
        }
    }

    record Tab(Object target, String name) {
        public Tab {
            name = name == null ? "" : name.trim();
        }
    }

    record State(List<Tab> tabs, int activeIndex) {
        public State {
            tabs = tabs == null ? List.of() : tabs.stream().filter(tab -> tab != null && tab.target() != null).toList();
            activeIndex = tabs.isEmpty() ? 0 : Math.clamp(activeIndex, 0, tabs.size() - 1);
        }
    }

    Async<Object> newTarget(List<Object> openTargets);

    default boolean supports(Object target) {
        return !(target instanceof Shell);
    }

    default Async<Object> resolve(Object target) {
        return supports(target) ? Async.completed(target) : Async.completed(null);
    }

    default Async<State> restore(State current) {
        return Async.completed(current == null ? new State(List.of(), 0) : current);
    }

    default void persist(State state) {
    }

    static NewTerminalTargetProvider local() {
        return LOCAL;
    }
}
