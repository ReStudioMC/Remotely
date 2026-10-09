package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import redxax.oxy.remotely.network.NetworkEditorConnection;
import redxax.oxy.remotely.network.NetworkRuntimeIdentity;
import redxax.oxy.remotely.network.NetworkRuntimeMonitor;
import redxax.oxy.remotely.network.NetworkRuntimePolicy;
import restudio.rescreen.platform.Async;
import restudio.resync.network.NetworkCredentials;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

final class BrowserNetworkRuntimeAccess implements NetworkRuntimeMonitor.Access {
    private final Map<String, Entry> connections = new LinkedHashMap<>();
    private long generation;

    synchronized void configure(String networkId, NetworkEditorConnection connection) {
        if (connection == null) throw new IllegalArgumentException("Network Connection Is Required");
        String endpoint = connection.endpoint().trim();
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Secure Hub Endpoint Is Invalid"); }
        if (endpoint.length() > 2048 || !"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Secure Hub Endpoint Must Be A WSS Address Without Credentials");
        }
        endpoint = "wss" + endpoint.substring(3);
        String credential = connection.credential().trim();
        String token = connection.enrollmentToken().trim();
        if (credential.length() > 128 || token.length() > 512 || credential.isBlank() && token.isBlank()) {
            throw new IllegalArgumentException("Operator Credential Or Enrollment Token Is Required");
        }
        Entry current = connections.get(networkId);
        if (current != null && current.endpoint.equals(endpoint) && current.credential.equals(credential) && current.token.equals(token)) return;
        generation++;
        connections.put(networkId, new Entry(endpoint, credential, token, Long.toString(generation)));
    }

    synchronized boolean configured(String networkId) {
        return connections.containsKey(networkId);
    }

    synchronized void clear() {
        connections.clear();
    }

    @Override
    public synchronized String endpointStamp(String networkId) {
        Entry entry = connections.get(networkId);
        return entry == null ? "" : entry.stamp;
    }

    @Override
    public synchronized boolean ready(String networkId, NetworkRuntimePolicy runtime) {
        return configured(networkId);
    }

    @Override
    public synchronized Async<NetworkRuntimeMonitor.Endpoint> open(String networkId, NetworkRuntimePolicy runtime) {
        Entry entry = connections.get(networkId);
        return entry == null ? Async.failed(new IllegalStateException("Configure The Network Connection First"))
                : Async.completed(new NetworkRuntimeMonitor.Endpoint(entry.endpoint, () -> { }));
    }

    @Override
    public synchronized String credential(String networkId, String nodeId) {
        return entry(networkId, nodeId).credential;
    }

    @Override
    public String generateCredential() {
        if (!secureRandomAvailable()) throw new IllegalStateException("Secure Network Credentials Are Unavailable In This Browser");
        return NetworkCredentials.generate();
    }

    @JSBody(script = "return typeof globalThis.crypto !== 'undefined' && typeof globalThis.crypto.getRandomValues === 'function';")
    private static native boolean secureRandomAvailable();

    @Override
    public synchronized String enrollmentToken(String networkId, String nodeId) {
        return entry(networkId, nodeId).token;
    }

    @Override
    public synchronized void saveCredential(String networkId, String nodeId, String credential) {
        if (credential == null || credential.isBlank() || credential.length() > 128) throw new IllegalArgumentException("Operator Credential Is Invalid");
        entry(networkId, nodeId).credential = credential;
    }

    @Override
    public synchronized boolean saveCredential(String networkId, String nodeId, String credential, String expectedStamp) {
        Entry current = connections.get(networkId);
        if (current == null || !current.stamp.equals(expectedStamp)) return false;
        saveCredential(networkId, nodeId, credential);
        return true;
    }

    private Entry entry(String networkId, String nodeId) {
        if (!NetworkRuntimeIdentity.operatorNodeId(networkId).equals(nodeId)) throw new IllegalArgumentException("Operator Identity Does Not Match");
        Entry entry = connections.get(networkId);
        if (entry == null) throw new IllegalStateException("Configure The Network Connection First");
        return entry;
    }

    private static final class Entry {
        private final String endpoint;
        private final String token;
        private final String stamp;
        private String credential;

        private Entry(String endpoint, String credential, String token, String stamp) {
            this.endpoint = endpoint;
            this.credential = credential;
            this.token = token;
            this.stamp = stamp;
        }
    }
}
