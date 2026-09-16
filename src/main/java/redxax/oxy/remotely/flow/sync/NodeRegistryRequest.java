package redxax.oxy.remotely.flow.sync;

import java.util.HashMap;
import java.util.Map;

public class NodeRegistryRequest {
    public static final String LEGACY_COMPATIBILITY_CAPABILITY = "legacy_node_registry_compatibility";
    private int contractVersion;
    private String registryChecksum = "";
    private Map<String, String> pluginChecksums = new HashMap<>();
    private String compatibilityCapability = "";

    public int getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(int contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getRegistryChecksum() {
        return registryChecksum != null ? registryChecksum : "";
    }

    public void setRegistryChecksum(String registryChecksum) {
        this.registryChecksum = registryChecksum != null ? registryChecksum : "";
    }

    public Map<String, String> getPluginChecksums() {
        return pluginChecksums;
    }

    public void setPluginChecksums(Map<String, String> pluginChecksums) {
        this.pluginChecksums = pluginChecksums != null ? pluginChecksums : new HashMap<>();
    }

    public String getCompatibilityCapability() {
        return compatibilityCapability != null ? compatibilityCapability : "";
    }

    public void setCompatibilityCapability(String compatibilityCapability) {
        this.compatibilityCapability = compatibilityCapability != null ? compatibilityCapability : "";
    }

    public boolean requestsLegacyCompatibility() {
        return LEGACY_COMPATIBILITY_CAPABILITY.equals(getCompatibilityCapability());
    }
}
