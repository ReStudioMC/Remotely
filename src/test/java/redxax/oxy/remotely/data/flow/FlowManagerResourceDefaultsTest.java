package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerResourceDefaultsTest {
    @Test
    void tradeProfileDefaultUsesEmptyCanonicalHooks() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        try {
            Method factory = FlowManager.class.getDeclaredMethod("defaultJsonResource", ReSyncResourceType.class,
                String.class, String.class);
            factory.setAccessible(true);

            JsonObject resource = (JsonObject) factory.invoke(manager, ReSyncResourceType.TRADE_PROFILE,
                "default-trade", "Trades");
            JsonObject hooks = resource.getAsJsonObject("hooks");

            assertNotNull(hooks);
            assertTrue(hooks.entrySet().isEmpty());
            assertFalse(hooks.has("openFlow"));
            assertFalse(hooks.has("completeFlow"));
            assertFalse(hooks.has("deniedFlow"));
        } finally {
            manager.shutdown();
        }
    }
}
