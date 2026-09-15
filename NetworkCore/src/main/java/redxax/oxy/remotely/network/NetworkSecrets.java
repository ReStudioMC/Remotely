package redxax.oxy.remotely.network;

public interface NetworkSecrets {
    String forwardingSecret(String reference);

    NetworkEnrollment enrollment(String networkId, String nodeId);
}
