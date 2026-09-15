package redxax.oxy.remotely.network;

import restudio.rebase.util.CredentialsManager;
import restudio.rescreen.platform.Sha256;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class NetworkSecretStore implements NetworkSecrets {
    private static final String FORWARDING_SERVICE = "remotely_network_forwarding";
    private static final String ENROLLMENT_SERVICE = "remotely_network_enrollment";
    private static final String RUNTIME_CREDENTIAL_SERVICE = "remotely_network_runtime_credential";
    private static final String RESTORE_VALUE_SERVICE = "remotely_network_restore_value";
    private static final SecureRandom RANDOM = new SecureRandom();

    public Secret createForwardingSecret() {
        String reference = UUID.randomUUID().toString();
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        CredentialsManager.setPassword(FORWARDING_SERVICE, reference, encoded);
        return new Secret(reference, encoded);
    }

    public String resolveForwardingSecret(String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        String value = CredentialsManager.getPassword(FORWARDING_SERVICE, reference.trim());
        return value == null ? "" : value;
    }

    @Override
    public String forwardingSecret(String reference) {
        return resolveForwardingSecret(reference);
    }

    public Secret importForwardingSecret(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Forwarding secret is required");
        }
        String reference = UUID.randomUUID().toString();
        CredentialsManager.setPassword(FORWARDING_SERVICE, reference, normalized);
        return new Secret(reference, normalized);
    }

    public void restoreForwardingSecret(String reference, String value) {
        String normalizedReference = reference == null ? "" : reference.trim();
        String normalizedValue = value == null ? "" : value.trim();
        if (normalizedReference.isBlank() || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("Forwarding Secret Reference And Value Are Required");
        }
        CredentialsManager.setPassword(FORWARDING_SERVICE, normalizedReference, normalizedValue);
    }

    public void deleteForwardingSecret(String reference) {
        if (reference != null && !reference.isBlank()) {
            CredentialsManager.deletePassword(FORWARDING_SERVICE, reference.trim());
        }
    }

    public String getOrCreateEnrollmentToken(String networkId, String nodeId) {
        String account = enrollmentAccount(networkId, nodeId);
        String existing = CredentialsManager.getPassword(ENROLLMENT_SERVICE, account);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        CredentialsManager.setPassword(ENROLLMENT_SERVICE, account, encoded);
        return encoded;
    }

    @Override
    public NetworkEnrollment enrollment(String networkId, String nodeId) {
        String token = getOrCreateEnrollmentToken(networkId, nodeId);
        String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(Sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        return new NetworkEnrollment(token, hash);
    }

    public String resolveEnrollmentToken(String networkId, String nodeId) {
        String value = CredentialsManager.getPassword(ENROLLMENT_SERVICE, enrollmentAccount(networkId, nodeId));
        return value == null ? "" : value;
    }

    public void deleteEnrollmentToken(String networkId, String nodeId) {
        CredentialsManager.deletePassword(ENROLLMENT_SERVICE, enrollmentAccount(networkId, nodeId));
    }

    public void deleteEnrollmentTokens(NetworkDefinition network) {
        if (network == null) {
            return;
        }
        network.members().stream().filter(member -> !member.isProxy()).forEach(member -> deleteEnrollmentToken(network.networkId(), member.nodeId()));
        deleteEnrollmentToken(network.networkId(), NetworkRuntimeIdentity.operatorNodeId(network.networkId()));
    }

    public String resolveRuntimeCredential(String networkId, String nodeId) {
        String value = CredentialsManager.getPassword(RUNTIME_CREDENTIAL_SERVICE, enrollmentAccount(networkId, nodeId));
        return value == null ? "" : value;
    }

    public void saveRuntimeCredential(String networkId, String nodeId, String credential) {
        String normalized = credential == null ? "" : credential.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Network Runtime Credential Is Required");
        }
        CredentialsManager.setPassword(RUNTIME_CREDENTIAL_SERVICE, enrollmentAccount(networkId, nodeId), normalized);
    }

    public void deleteRuntimeCredential(String networkId, String nodeId) {
        CredentialsManager.deletePassword(RUNTIME_CREDENTIAL_SERVICE, enrollmentAccount(networkId, nodeId));
    }

    public String saveRestoreValue(String value) {
        String reference = UUID.randomUUID().toString();
        String encoded = Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        CredentialsManager.setPassword(RESTORE_VALUE_SERVICE, reference, "v1:" + encoded);
        return reference;
    }

    public String resolveRestoreValue(String reference) {
        String value = CredentialsManager.getPassword(RESTORE_VALUE_SERVICE, reference == null ? "" : reference.trim());
        if (value == null || !value.startsWith("v1:")) throw new IllegalStateException("Original sensitive configuration is unavailable");
        try {
            return new String(Base64.getDecoder().decode(value.substring(3)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Original sensitive configuration is invalid", exception);
        }
    }

    public boolean canResolveRestoreValue(String reference) {
        try {
            resolveRestoreValue(reference);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    public void deleteRestoreValue(String reference) {
        if (reference != null && !reference.isBlank()) CredentialsManager.deletePassword(RESTORE_VALUE_SERVICE, reference.trim());
    }

    private String enrollmentAccount(String networkId, String nodeId) {
        String network = networkId == null ? "" : networkId.trim();
        String node = nodeId == null ? "" : nodeId.trim();
        if (network.isBlank() || node.isBlank()) {
            throw new IllegalArgumentException("Network ID And Node ID Are Required");
        }
        return network + ":" + node;
    }

    public record Secret(String reference, String value) {
    }
}
