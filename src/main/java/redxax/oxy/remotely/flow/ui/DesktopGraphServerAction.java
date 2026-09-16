package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.FlowManager;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.ui.core.Screen;

import java.util.HashMap;
import java.util.Map;

public final class DesktopGraphServerAction {
    private DesktopGraphServerAction() {
    }

    public static void open(Screen parent, FlowManager manager, String serverId, ClientServerView server) {
        if (parent == null || manager == null || server == null || RemotelyClient.INSTANCE == null) {
            return;
        }
        Instance instance = (Instance) manager.findInstanceByServerId(serverId, server);
        if (instance == null) {
            instance = buildTemporaryInstance(server);
        }
        if (instance != null) {
            RemotelyClient.INSTANCE.openInstanceInTerminal(parent, instance);
        }
    }

    private static Instance buildTemporaryInstance(ClientServerView server) {
        Map<String, String> credentials = new HashMap<>();
        credentials.put("identifier", server.identifier);
        credentials.put("host", server.sftpIp);
        credentials.put("port", String.valueOf(server.sftpPort));
        credentials.put("user", server.sftpUser);
        credentials.put("password", "");
        credentials.put("installing", String.valueOf(server.isInstalling));
        credentials.put("suspended", String.valueOf(server.isSuspended));
        Instance instance = new Instance(server.name, "unknown", "");
        instance.setBackendConfig(new BackendConfig("RESTUDIO", credentials));
        instance.setServer(true);
        if (server.loader != null) {
            try {
                instance.setModLoader(ModLoader.valueOf(server.loader));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return instance;
    }
}
