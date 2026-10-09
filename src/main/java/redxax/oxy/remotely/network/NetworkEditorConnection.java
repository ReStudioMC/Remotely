package redxax.oxy.remotely.network;

public record NetworkEditorConnection(String endpoint, String credential, String enrollmentToken) {
    public NetworkEditorConnection {
        endpoint = endpoint == null ? "" : endpoint.trim();
        credential = credential == null ? "" : credential.trim();
        enrollmentToken = enrollmentToken == null ? "" : enrollmentToken.trim();
        if (endpoint.isBlank() || endpoint.length() > 2048) throw new IllegalArgumentException("Secure Hub Endpoint Is Required");
        if (credential.isBlank() && enrollmentToken.isBlank()) throw new IllegalArgumentException("Operator Credential Or Enrollment Token Is Required");
        if (credential.length() > 128 || enrollmentToken.length() > 512) throw new IllegalArgumentException("Network Credentials Are Too Long");
    }

    @Override
    public String toString() {
        return "NetworkEditorConnection";
    }
}
