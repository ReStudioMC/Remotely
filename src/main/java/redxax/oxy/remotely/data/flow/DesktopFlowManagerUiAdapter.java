package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonElement;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.data.flow.world.WorldOperationResult;
import redxax.oxy.remotely.flow.ui.DesktopGraphServerAction;
import redxax.oxy.remotely.flow.ui.FlowEditorScreen;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.ui.widgets.management.PlayerDataPopup;
import redxax.oxy.remotely.ui.widgets.management.PlayerManagerController;
import restudio.rebase.instance.Instance;
import restudio.rebase.minecraft.MinecraftPlayerLocation;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rebase.ui.worldmap.WorldMapScreen;
import restudio.rescreen.config.Config;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.screens.DesktopWindowsOverlay;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.util.Notification;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class DesktopFlowManagerUiAdapter implements FlowManagerUiAdapter {
    private final Map<String, ArrayDeque<Notification>> pendingWorldSaveNotifications = new HashMap<>();

    @Override
    public boolean activate(ApplicationHost host, Screen studioScreen, boolean fullEditor) {
        ScreenManager screenManager = ScreenManager.getInstance();
        if (!(studioScreen instanceof FlowEditorScreen flowScreen)) return false;
        if (Config.desktopMode && fullEditor) {
            DesktopWindowsOverlay overlay = screenManager.getDesktopWindowsOverlay();
            ScreenWindowWidget window = overlay == null ? null : overlay.getWindowForScreen(flowScreen);
            if (window != null) overlay.closeWindow(window);
            host.setScreen(flowScreen);
            return true;
        }
        if (Config.desktopMode) {
            DesktopWindowsOverlay overlay = screenManager.getDesktopWindowsOverlay();
            ScreenWindowWidget window = overlay == null ? null : overlay.getWindowForScreen(flowScreen);
            if (window != null) {
                overlay.restoreWindow(window);
                revealStudioHost(host, flowScreen);
                return true;
            }
            if (screenManager.getDesktopSuperScreen() == flowScreen) {
                if (overlay != null) {
                    for (ScreenWindowWidget openWindow : overlay.getWindows()) overlay.minimizeWindow(openWindow);
                }
                revealStudioHost(host, flowScreen);
                return true;
            }
        }
        if (host.getCurrentScreen() != flowScreen) host.setScreen(flowScreen);
        else revealStudioHost(host, flowScreen);
        return true;
    }

    @Override
    public Object resolveDesignerParent(ApplicationHost host, Object parent) {
        if (parent != null) return parent;
        ScreenManager screenManager = ScreenManager.getInstance();
        Screen current = screenManager.getCurrentScreen();
        Screen desktopSuperScreen = screenManager.getDesktopSuperScreen();
        if (current != null && current.isDesktopWindow() && desktopSuperScreen != null) return desktopSuperScreen;
        return current != null ? current : host.getCurrentScreen();
    }

    @Override
    public Object captureCurrentParent(ApplicationHost host) {
        return resolveDesignerParent(host, null);
    }

    @Override
    public boolean closeStudio(ApplicationHost host, Screen studioScreen, Object parent, boolean saved) {
        ScreenManager screenManager = ScreenManager.getInstance();
        Screen current = screenManager.getCurrentScreen();
        Screen desktopSuperScreen = screenManager.getDesktopSuperScreen();
        if (studioScreen == null || (current != studioScreen && desktopSuperScreen != studioScreen && host.getCurrentScreen() != studioScreen)) {
            return true;
        }
        if (desktopSuperScreen == studioScreen) screenManager.setDesktopSuperScreen(null);
        if (!saved && parent != null && parent != studioScreen) host.openParentScreen(studioScreen, parent);
        else host.openParentScreen(studioScreen, null);
        return true;
    }

    @Override
    public boolean openWorldMap(FlowManager manager, ApplicationHost host, String serverId, ClientServerView server,
                               String worldName, Object parent) {
        String actualServerId = ReSyncServerIdentity.from(serverId, server).serverId();
        Instance instance = (Instance) manager.findInstanceByServerId(actualServerId, server);
        if (instance == null) {
            host.notify("World Map", "Instance Unavailable", ReSyncNotificationLevel.ERROR);
            return true;
        }
        Screen parentScreen = parent instanceof Screen screen ? screen : host.getCurrentScreen();
        WorldMapScreen mapScreen = createWorldMapScreen(parentScreen, instance, worldName, location -> selectPlayer(host, instance, location));
        host.setScreen(mapScreen);
        return true;
    }

    @Override
    public void onWorldAuditSnapshot(ApplicationHost host, String serverId, Object data) {
        if (!(data instanceof JsonElement element)) {
            return;
        }
        ScreenManager.getInstance().execute(() -> FlowEditorScreen.handleWorldAuditSnapshotForServer(serverId, element));
    }

    @Override
    public void onWorldOperationResult(ApplicationHost host, String serverId, WorldOperationResult result) {
        ScreenManager.getInstance().execute(() -> FlowEditorScreen.handleWorldOperationResultForServer(serverId, result));
    }

    @Override
    public void onWorldSaveStarted(ApplicationHost host, String serverId, String targetName, String title, long sequence) {
        ScreenManager.getInstance().execute(() -> {
            if (sequence > 0) {
                FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
                if (studioScreen != null) {
                    studioScreen.markStudioDocumentSaving(ReSyncResourceDragPayload.WORLD, targetName, sequence);
                }
            }
            Notification notification = new Notification.Builder()
                .message(title)
                .description(targetName)
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false)
                .build();
            pendingWorldSaveNotifications.computeIfAbsent(saveKey(serverId, targetName, sequence), ignored -> new ArrayDeque<>()).add(notification);
        });
    }

    @Override
    public void onWorldSaveFinished(ApplicationHost host, String serverId, String targetName, String title, String message,
                                    ReSyncNotificationLevel level, long sequence) {
        ScreenManager.getInstance().execute(() -> {
            if (sequence > 0 && level == ReSyncNotificationLevel.SUCCESS) {
                FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
                if (studioScreen != null) {
                    studioScreen.markStudioDocumentSaved(ReSyncResourceDragPayload.WORLD, targetName, sequence);
                }
            }
            String key = saveKey(serverId, targetName, sequence);
            ArrayDeque<Notification> notifications = pendingWorldSaveNotifications.get(key);
            Notification notification = notifications == null || notifications.isEmpty() ? null : notifications.poll();
            if (notifications != null && notifications.isEmpty()) {
                pendingWorldSaveNotifications.remove(key);
            }
            if (notification == null) {
                new Notification.Builder().message(title).description(message).type(notificationType(level)).build();
            } else {
                notification.change(title, message, notificationType(level), null);
            }
        });
    }

    @Override
    public void openServerScreen(FlowManager manager, ApplicationHost host, Screen parent, String serverId,
                                 ClientServerView server) {
        DesktopGraphServerAction.open(parent, manager, serverId, server);
    }

    private void revealStudioHost(ApplicationHost host, Screen studioScreen) {
        if (host.getCurrentScreen() == null) host.setScreen(studioScreen);
    }

    private void selectPlayer(ApplicationHost host, Instance instance, MinecraftPlayerLocation location) {
        if (location == null || location.uuid() == null) return;
        PlayerManagerController controller = PlayerManagerController.getOrCreate(instance);
        controller.requestPlayerDossier(location.uuid());
        UnifiedPlayer player = new UnifiedPlayer(location.uuid(), location.name());
        new PlayerDataPopup(host.getCurrentScreen(), player, controller);
    }

    private WorldMapScreen createWorldMapScreen(Screen parentScreen, Instance instance, String worldName,
                                                 Consumer<MinecraftPlayerLocation> onPlayerSelected) {
        try {
            return WorldMapScreen.class.getConstructor(Screen.class, Instance.class, String.class, Consumer.class)
                .newInstance(parentScreen, instance, worldName, onPlayerSelected);
        } catch (Exception ignored) {
            return new WorldMapScreen(parentScreen, instance, onPlayerSelected);
        }
    }

    private String saveKey(String serverId, String targetName, long sequence) {
        return String.valueOf(serverId) + ":" + sequence + ":" + String.valueOf(targetName);
    }

    private Notification.Type notificationType(ReSyncNotificationLevel level) {
        return switch (level == null ? ReSyncNotificationLevel.INFO : level) {
            case WARN -> Notification.Type.WARN;
            case ERROR -> Notification.Type.ERROR;
            case SUCCESS -> Notification.Type.SUCCESS;
            case INFO -> Notification.Type.INFO;
        };
    }
}
