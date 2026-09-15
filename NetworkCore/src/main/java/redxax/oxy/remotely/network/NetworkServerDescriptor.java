package redxax.oxy.remotely.network;

import java.util.LinkedHashMap;
import java.util.Map;

public record NetworkServerDescriptor(String serverId, String version, Map<String, String> properties, NetworkBackendForwardingAdapter forwardingAdapter) {
    public NetworkServerDescriptor {
        serverId = serverId == null ? "" : serverId.trim();
        version = version == null ? "" : version.trim();
        properties = properties == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(properties));
        forwardingAdapter = forwardingAdapter == null ? NetworkBackendForwardingAdapter.UNSUPPORTED : forwardingAdapter;
    }

    public String property(String key, String fallback) {
        return properties.getOrDefault(key, fallback);
    }
}
