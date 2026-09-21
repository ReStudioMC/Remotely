package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.flow.ui.FlowEditorScreen;
import redxax.oxy.remotely.flow.ui.ReSyncProvisioningService;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class FlowManagerStudioOpenLifecycleTest {
    @Test
    void completingPreparationStillOpensStudioAfterTheLaunchingSurfaceChanges() {
        ApplicationHost previousHost = ApplicationHostRegistry.current();
        RemotelyClient previousClient = RemotelyClient.INSTANCE;
        TestHost host = new TestHost();
        RemotelyClient client = new RemotelyClient(RemotelyComposition.browser(host).build());
        FlowManager manager = new FlowManager(client, null);
        Screen launchSurface = new Screen();
        Screen settledSurface = new Screen();
        ClientServerView server = new ClientServerView();
        server.identifier = "instance-id";
        server.name = "Test Server";
        host.screen = launchSurface;

        try {
            manager.openReSyncStudio(server.identifier, server, "fabric", server.name);
            host.screen = settledSurface;
            host.preparation.complete(new ReSyncProvisioningService.StartupProbeResult(
                ReSyncProvisioningService.StartupStatus.READY, false, false));
            ScreenManager.getInstance().processTasks();
            ScreenManager.getInstance().processTasks();

            FlowEditorScreen studio = assertInstanceOf(FlowEditorScreen.class, host.screen);
            assertNotSame(settledSurface, studio);
            assertEquals(server.identifier, studio.getServerId());
        } finally {
            manager.shutdown();
            ApplicationHostRegistry.install(previousHost);
            RemotelyClient.INSTANCE = previousClient;
        }
    }

    private static final class TestHost implements ApplicationHost {
        private final Async<ReSyncProvisioningService.StartupProbeResult> preparation = Async.pending();
        private Screen screen;

        @Override
        public void setScreen(Screen screen) {
            this.screen = screen;
        }

        @Override
        public Screen getCurrentScreen() {
            return screen;
        }

        @Override
        public Async<ReSyncProvisioningService.StartupProbeResult> prepareReSyncServerContextAsync(
                String serverId, ClientServerView server, String loaderHint) {
            return preparation;
        }

        @Override
        public void ensureTextRenderer() {
        }

        @Override
        public MinecraftGameAssets getGameAssets() {
            return null;
        }

        @Override
        public Object getFontIdentifier(String namespace, String path) {
            return null;
        }

        @Override
        public void openParentScreen(Screen currentScreen, Object parent) {
        }

        @Override
        public void setClipboard(String text) {
        }

        @Override
        public boolean shouldCloseRootScreen() {
            return false;
        }

        @Override
        public String getGameVersion() {
            return "";
        }

        @Override
        public String getGameUserName() {
            return "";
        }

        @Override
        public String getGameUUID() {
            return "";
        }
    }
}
