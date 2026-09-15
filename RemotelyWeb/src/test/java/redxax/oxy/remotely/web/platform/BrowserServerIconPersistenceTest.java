package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import restudio.rescreen.util.Identifier;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrowserServerIconPersistenceTest {
    private Field activeSession;
    private Object previousSession;

    @BeforeEach
    void captureSession() throws Exception {
        activeSession = BrowserLaunchSession.class.getDeclaredField("activeSession");
        activeSession.setAccessible(true);
        previousSession = activeSession.get(null);
    }

    @AfterEach
    void restoreSession() throws Exception {
        activeSession.set(null, previousSession);
    }

    @Test
    void restoresIconAndTintAfterStoreRecreationAndIsolatesAccounts() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        authenticate("account-a");
        BrowserRemotelyConfigStore first = new BrowserRemotelyConfigStore(storage);
        Identifier image = Identifier.icon("paper.png");
        String source = "data:image/png;base64,aWNvbg==";
        first.setServerIcon("server-1", image, 0xFF55FFFF, source);

        BrowserRemotelyConfigStore.IconSelection restored = new BrowserRemotelyConfigStore(storage).getServerIcon("server-1");
        assertEquals(image, restored.image());
        assertEquals(0xFF55FFFF, restored.tint());
        assertEquals(source, restored.source());

        authenticate("account-b");
        assertNull(new BrowserRemotelyConfigStore(storage).getServerIcon("server-1"));

        authenticate("account-a");
        BrowserRemotelyConfigStore.IconSelection accountA = new BrowserRemotelyConfigStore(storage).getServerIcon("server-1");
        assertEquals(image, accountA.image());
        assertEquals(0xFF55FFFF, accountA.tint());
        assertEquals(source, accountA.source());
    }

    private void authenticate(String subjectId) throws Exception {
        activeSession.set(null, new BrowserLaunchSession.Metadata("grant", "ticket", "audience", Set.of(), "node", "", subjectId,
                subjectId, subjectId, "", "", "session"));
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
