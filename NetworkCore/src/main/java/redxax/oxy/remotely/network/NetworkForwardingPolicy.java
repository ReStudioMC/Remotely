package redxax.oxy.remotely.network;

public record NetworkForwardingPolicy(ForwardingMode mode, boolean proxyOnlineMode, String secretReference, boolean firewallVerified) {
    public NetworkForwardingPolicy {
        mode = mode == null ? ForwardingMode.MODERN : mode;
        secretReference = normalize(secretReference);
    }

    public static NetworkForwardingPolicy secureDefault(String secretReference) {
        return new NetworkForwardingPolicy(ForwardingMode.MODERN, true, secretReference, false);
    }

    public boolean needsRepair() {
        return mode == ForwardingMode.NONE || mode == ForwardingMode.MODERN && secretReference.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
