package redxax.oxy.remotely.ui.server;

import restudio.rebase.backend.impl.CalagopusBackend;
import restudio.rebase.backend.impl.CraftyBackend;
import restudio.rebase.backend.impl.PteroBackend;
import restudio.rebase.hosting.RemoteHost;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;

import java.util.List;
import java.util.Locale;

public final class DesktopPanelServerProvider implements PanelServerProvider {
    private static final DesktopPanelServerProvider INSTANCE = new DesktopPanelServerProvider();

    private DesktopPanelServerProvider() {
    }

    public static PanelServerProvider instance() {
        return INSTANCE;
    }

    @Override
    public boolean isPanelType(String type) {
        return PteroBackend.isPanelType(type);
    }

    @Override
    public String normalizePanelUrl(String value) {
        return PteroBackend.normalizePanelUrl(value);
    }

    @Override
    public String normalizePanelOrigin(String value) {
        return PteroBackend.normalizePanelOrigin(value);
    }

    @Override
    public Async<List<?>> listServers(Object host) {
        if (!(host instanceof RemoteHost remoteHost)) {
            return Async.failed(new IllegalArgumentException("A Remote Host Is Required"));
        }
        Async<? extends List<?>> request = switch (remoteHost.getType().toUpperCase(Locale.ROOT)) {
            case "CALAGOPUS" -> JvmAsyncBridge.fromFuture(CalagopusBackend.listServers(remoteHost.getIp(), remoteHost.getApiKey()));
            case "CRAFTY" -> JvmAsyncBridge.fromFuture(CraftyBackend.listServers(remoteHost.getIp(), remoteHost.getApiKey()));
            default -> JvmAsyncBridge.fromFuture(PteroBackend.listServers(remoteHost.getIp(), remoteHost.getApiKey()));
        };
        return request.thenApply(value -> (List<?>) value);
    }
}
