package redxax.oxy.remotely.network;

public record NetworkRuntimePolicy(boolean enabled, String hubAddress, int hubPort, NetworkTransportSecurity security, boolean transportReady) {
    public NetworkRuntimePolicy {
        hubAddress = hubAddress == null ? "" : hubAddress.trim();
        security = security == null ? NetworkTransportSecurity.LOOPBACK : security;
        if (!enabled) {
            hubAddress = "";
            hubPort = 0;
            transportReady = false;
        }
    }

    public static NetworkRuntimePolicy disabled() {
        return new NetworkRuntimePolicy(false, "", 0, NetworkTransportSecurity.LOOPBACK, false);
    }

    public String hubUrl() {
        if (!enabled || hubAddress.isBlank() || hubPort < 1) {
            return "";
        }
        String scheme = security == NetworkTransportSecurity.WSS ? "wss" : "ws";
        String address = hubAddress.contains(":") && !hubAddress.startsWith("[") ? "[" + hubAddress + "]" : hubAddress;
        return scheme + "://" + address + ":" + hubPort;
    }
}
