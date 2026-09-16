package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.identity.ServerId;

import java.util.Optional;

public record ReSyncLiveServerSession(String serverId, String displayName, ReSyncFrameTransport transport) {
    public Optional<ServerId> peerServerId() {
        return transport == null ? Optional.empty() : transport.peerServerId();
    }
}
