package redxax.oxy.remotely.network;

public record NetworkEnrollment(String token, String hash) {
    public NetworkEnrollment {
        token = token == null ? "" : token;
        hash = hash == null ? "" : hash;
    }
}
