package redxax.oxy.remotely.ui.server;

public record NetworkCreationContext(boolean reactor, ServerScreenHost.HostView host) {
    public NetworkCreationContext {
        if (reactor && host != null) throw new IllegalArgumentException("A Reactor Network Cannot Use A Local Or SSH Host");
    }

    public static NetworkCreationContext from(ServerScreenHost host, Object tab) {
        boolean reactor = "RESTUDIO_MARKER".equals(tab) || host.serverManagerMode() == ServerScreenHost.ServerManagerMode.REACTOR_ONLY;
        return new NetworkCreationContext(reactor, !reactor && tab instanceof ServerScreenHost.HostView remote && !remote.panel() ? remote : null);
    }
}
