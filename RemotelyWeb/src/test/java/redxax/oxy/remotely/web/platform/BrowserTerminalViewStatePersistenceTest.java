package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.config.RemotelyViewStateStore;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrowserTerminalViewStatePersistenceTest {
    private Field activeSession;
    private Object previousSession;

    @BeforeEach
    void captureSession() throws Exception {
        activeSession = BrowserLaunchSession.class.getDeclaredField("activeSession");
        activeSession.setAccessible(true);
        previousSession = activeSession.get(null);
        activeSession.set(null, new BrowserLaunchSession.Metadata("grant", "ticket", "audience", Set.of(), "node", "", "account",
                "account", "account", "", "", "session"));
    }

    @AfterEach
    void restoreSession() throws Exception {
        activeSession.set(null, previousSession);
    }

    @Test
    void restoresTerminalDescriptorsNamesOrderAndSelection() {
        MemoryStorage storage = new MemoryStorage();
        BrowserRemotelyConfigStore first = new BrowserRemotelyConfigStore(storage);
        first.setViewState(new RemotelyViewStateStore.State("host", "server", "tab", "Terminal", List.of(
                new RemotelyViewStateStore.TerminalTab("server-b", "Renamed, Server"),
                new RemotelyViewStateStore.TerminalTab("server-a", "Server A")), 1));

        RemotelyViewStateStore.State restored = new BrowserRemotelyConfigStore(storage).getViewState();

        assertEquals(List.of(new RemotelyViewStateStore.TerminalTab("server-b", "Renamed, Server"),
                new RemotelyViewStateStore.TerminalTab("server-a", "Server A")), restored.terminalTabs());
        assertEquals(1, restored.terminalTabIndex());
    }

    private static final class MemoryStorage implements BrowserRemotelyConfigStore.Storage {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public boolean write(String key, String value) {
            values.put(key, value);
            return true;
        }

        @Override
        public boolean erase(String key) {
            values.remove(key);
            return true;
        }
    }
}
