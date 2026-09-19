package redxax.oxy.remotely.ui.settings.data;

import java.util.Collection;
import java.util.Map;

public interface ServerSettingsDocumentTarget {
    default String catalogServerId() {
        return "";
    }

    default String minecraftVersion() {
        return "";
    }

    Collection<String> softwareTokens();

    String property(String key);

    void property(String key, String value);

    void removeProperty(String key);

    void replaceProperties(Map<String, String> properties);
}
