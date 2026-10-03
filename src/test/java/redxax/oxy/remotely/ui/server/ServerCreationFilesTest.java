package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCreationFilesTest {
    @Test
    void keepsEditedPropertiesAndEscapesTargetOverridesWithoutPublishingPrivatePort() throws IOException {
        String edited = "# Player settings\r\nmax-players=12\r\nserver-port=25565\r\nop-me=true\r\n";
        Map<String, String> target = new LinkedHashMap<>();
        target.put("motd", "Welcome: build\\test\nNext line");
        target.put("custom:key", " leading value");
        target.put("server-port", "25570");
        target.put("op-me", "true");

        String merged = ServerCreationFiles.serverProperties(edited, target);
        Properties properties = new Properties();
        properties.load(new StringReader(merged));

        assertTrue(merged.startsWith("# Player settings\r\nmax-players=12\r\n"));
        assertEquals("12", properties.getProperty("max-players"));
        assertEquals("Welcome: build\\test\nNext line", properties.getProperty("motd"));
        assertEquals(" leading value", properties.getProperty("custom:key"));
        assertFalse(properties.containsKey("server-port"));
        assertFalse(properties.containsKey("op-me"));
    }
}
