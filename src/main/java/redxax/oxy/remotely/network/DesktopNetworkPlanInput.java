package redxax.oxy.remotely.network;

import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class DesktopNetworkPlanInput {
    private static final Set<String> PAPER_SOFTWARE = Set.of("paper", "folia", "purpur", "leaf", "pufferfish", "canvas", "aspaper", "divinemc");
    private static final Set<String> FABRIC_BRIDGES = Set.of("velocity-modern", "fabricproxy-lite", "fabric-proxy-lite");
    private static final Set<String> FORGE_BRIDGES = Set.of("velocity-modern", "proxy-compatible-forge", "proxycompatibleforge");

    private DesktopNetworkPlanInput() {
    }

    static NetworkPlanInput from(NetworkDiscoveryResult discovery) {
        Map<String, NetworkServerDescriptor> servers = new LinkedHashMap<>();
        discovery.instancesById(Instance.class).forEach((id, instance) -> servers.put(id, describe(instance)));
        return new NetworkPlanInput(discovery.network(), servers, discovery.issues());
    }

    private static NetworkServerDescriptor describe(Instance instance) {
        Map<String, String> properties = new LinkedHashMap<>();
        Properties serverProperties = instance.getServerProperties();
        serverProperties.stringPropertyNames().forEach(name -> properties.put(name, serverProperties.getProperty(name)));
        return new NetworkServerDescriptor(instance.getInstanceId(), instance.getVersionId(), properties, forwardingAdapter(instance));
    }

    static NetworkBackendForwardingAdapter forwardingAdapter(Instance instance) {
        ModLoader loader = instance.getModLoader();
        String software = normalize(instance.getServerSoftwareType());
        if (PAPER_SOFTWARE.contains(software) || loader == ModLoader.PAPER || loader == ModLoader.FOLIA || loader == ModLoader.PURPUR || loader == ModLoader.LEAF) {
            return NetworkBackendForwardingAdapter.PAPER;
        }
        String bridge = normalize(instance.getSettings().getProperty("network.forwarding.bridge", ""));
        if ((loader == ModLoader.FABRIC || loader == ModLoader.QUILT) && FABRIC_BRIDGES.contains(bridge)) {
            return NetworkBackendForwardingAdapter.FABRIC_PROXY_LITE;
        }
        if ((loader == ModLoader.FORGE || loader == ModLoader.NEOFORGE) && FORGE_BRIDGES.contains(bridge)) {
            return NetworkBackendForwardingAdapter.PROXY_COMPATIBLE_FORGE;
        }
        return NetworkBackendForwardingAdapter.UNSUPPORTED;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
