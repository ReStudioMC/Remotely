package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.util.TaskSchedulers;

import redxax.oxy.remotely.util.AsyncTools;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.ui.server.ServerManagerScreen;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.util.Notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public class ServerClientSettingsController {

    private static final Duration RESTUDIO_INVENTORY_RETRY_DELAY = Duration.ofSeconds(2);
    private final RemotelyConfigStore configManager;
    private final ServerClientSettingsProvider provider;
    private List<ServerModels.ClientServerView> restudioInventory = List.of();
    private boolean restudioInventoryLoaded;
    private boolean restudioInventoryRequestInFlight;
    private boolean restudioInventoryAuthenticated;
    private String restudioInventoryAccount = "";
    private long restudioInventoryRequestGeneration;
    private boolean restudioInventoryRetryScheduled;
    private boolean restudioInventoryRetryUsed;
    private long restudioInventoryRetryGeneration;
    private List<Setting> residentSettings;
    private Setting quickSetting;
    private Setting hiddenSetting;
    private List<PopupWidget.PopupRow> quickOptions = List.of();
    private final Map<String, PopupWidget.PopupRow> quickRows = new LinkedHashMap<>();
    private final Map<String, PopupWidget.PopupRow> hiddenRows = new LinkedHashMap<>();

    public ServerClientSettingsController(RemotelyConfigStore configManager, ServerClientSettingsProvider provider) {
        this.configManager = configManager;
        this.provider = provider;
    }

    public List<Setting> getSettings() {
        if (residentSettings != null) {
            reconcileServers();
            return List.copyOf(residentSettings);
        }
        List<Setting> settings = new ArrayList<>();
        Setting.Builder servers = new Setting.Builder("Servers");

        servers.addOption(ConfigOption.<Boolean>builder("Scan For Servers")
                .description("Scan Your Remotely Instances.")
                .bind(configManager::getScanServers, configManager::setScanServers)
                .defaultValue(true)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.SCAN))
                .build());

        ConfigOption<Boolean> customProxy = ConfigOption.<Boolean>builder("Use Custom Reverse Proxy")
                .description("Enable connection via a custom reverse proxy server.")
                .bind(configManager::getCustomReverseProxy, configManager::setCustomReverseProxy)
                .defaultValue(false)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.CUSTOM_PROXY))
                .build();
        servers.addOption(customProxy);

        servers.addOption(ConfigOption.<String>builder("Reverse Proxy Host")
                .description("The hostname of reverse proxy.")
                .bind(configManager::getProxyHost, configManager::setProxyHost)
                .defaultValue("RedxAx.net")
                .dependsOn(() -> customProxy.get() && provider.available(ServerClientSettingsProvider.PROXY_HOST))
                .build());

        servers.addOption(ConfigOption.<String>builder("Reverse Proxy User")
                .description("The username for reverse proxy connection.")
                .bind(configManager::getProxyUser, configManager::setProxyUser)
                .defaultValue("tunnel")
                .dependsOn(() -> customProxy.get() && provider.available(ServerClientSettingsProvider.PROXY_USER))
                .build());

        settings.add(servers.build());

        Setting.Builder terminal = new Setting.Builder("Terminal");
        terminal.addOption(ConfigOption.<Boolean>builder("Terminal Code Suggestions")
                .description("Show Code Suggestions While Typing In A Terminal.")
                .bind(configManager::getTerminalCodeSuggestions, configManager::setTerminalCodeSuggestions)
                .defaultValue(false)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.TERMINAL_SUGGESTIONS))
                .build());
        settings.add(terminal.build());

        Setting.Builder quickServer = new Setting.Builder("Quick Server");
        quickServer.addOption(ConfigOption.<Boolean>builder("Precreate Quick Server")
                .description("Prepare The World Server When Opening A World.")
                .bind(configManager::getQuickServerPrecreate, configManager::setQuickServerPrecreate)
                .defaultValue(false)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.QUICK_PRECREATE))
                .build());
        quickServer.addOption(ConfigOption.<Boolean>builder("Keep Running")
                .description("Keep Quick Servers Running After Minecraft Closes.")
                .bind(configManager::getQuickServerKeepRunning, configManager::setQuickServerKeepRunning)
                .defaultValue(false)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.QUICK_KEEP_RUNNING))
                .build());
        quickServer.addOption(ConfigOption.<Boolean>builder("Auto Restart")
                .description("Restart Quick Servers After A Crash.")
                .bind(configManager::getQuickServerAutoRestart, configManager::setQuickServerAutoRestart)
                .defaultValue(false)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.QUICK_AUTO_RESTART))
                .build());
        quickServer.addOption(ConfigOption.<Boolean>builder("Mirror Mods")
                .description("Copy Server Compatible Mods Into Quick Servers.")
                .bind(configManager::getQuickServerMirrorMods, configManager::setQuickServerMirrorMods)
                .defaultValue(true)
                .dependsOn(() -> provider.available(ServerClientSettingsProvider.QUICK_MIRROR_MODS))
                .build());
        quickSetting = quickServer.build();
        quickOptions = quickSetting.getRows();
        settings.add(quickSetting);
        residentSettings = settings;
        reconcileServers();
        return List.copyOf(residentSettings);
    }

    private void reconcileServers() {
        List<PopupWidget.PopupRow> quick = new ArrayList<>(quickOptions);
        Map<String, PopupWidget.PopupRow> nextQuick = new LinkedHashMap<>();
        Map<String, Integer> quickOccurrences = new LinkedHashMap<>();
        for (ServerClientSettingsProvider.QuickServer server : provider.quickServers()) {
            String identity = server.name();
            int occurrence = quickOccurrences.merge(identity, 1, Integer::sum);
            String key = "quick:" + identity + ":" + occurrence;
            PopupWidget.PopupRow row = quickRows.get(key);
            if (row == null) row = new PopupWidget.PopupRow.Builder("", createQuickServerWidget(server)).id(key).build();
            updateQuickServerWidget((MountableButtonWidget) row.getWidgets().getFirst(), server);
            nextQuick.put(key, row);
            quick.add(row);
        }
        quickRows.clear();
        quickRows.putAll(nextQuick);
        quickSetting.setRows(quick);

        List<PopupWidget.PopupRow> hidden = new ArrayList<>();
        Map<String, PopupWidget.PopupRow> nextHidden = new LinkedHashMap<>();
        Map<String, Integer> hiddenOccurrences = new LinkedHashMap<>();
        for (ServerClientSettingsProvider.HiddenServer server : getHiddenServers()) {
            String identity = server.location() + ":" + server.name();
            int occurrence = hiddenOccurrences.merge(identity, 1, Integer::sum);
            String key = "hidden:" + identity + ":" + occurrence;
            PopupWidget.PopupRow row = hiddenRows.get(key);
            if (row == null) row = new PopupWidget.PopupRow.Builder("", createHiddenServerWidget(server)).id(key).build();
            updateHiddenServerWidget((MountableButtonWidget) row.getWidgets().getFirst(), server);
            nextHidden.put(key, row);
            hidden.add(row);
        }
        hiddenRows.clear();
        hiddenRows.putAll(nextHidden);
        if (hidden.isEmpty()) {
            if (hiddenSetting != null) residentSettings.remove(hiddenSetting);
            hiddenSetting = null;
            hiddenRows.clear();
        } else {
            if (hiddenSetting == null) hiddenSetting = new Setting.Builder("Hidden Servers").build();
            hiddenSetting.setRows(hidden);
            if (!residentSettings.contains(hiddenSetting)) residentSettings.add(hiddenSetting);
        }
    }

    private void updateQuickServerWidget(MountableButtonWidget widget, ServerClientSettingsProvider.QuickServer server) {
        String visibility = server.hidden() ? "Hidden" : "Visible";
        widget.setName(server.name());
        widget.setHiddenText(server.version() == null || server.version().isBlank() ? visibility : visibility + " | " + server.version());
        IconButton visibilityButton = (IconButton) widget.mountedWidgets.get(0);
        visibilityButton.setIcon(server.hidden() ? "add.png" : "hide.png");
        visibilityButton.setHint(server.hidden() ? "Show Quick Server" : "Hide Quick Server");
        visibilityButton.setOnClick(server.toggleVisibility());
        ((IconButton) widget.mountedWidgets.get(1)).setOnClick(() -> deleteQuickServer(server));
    }

    private void updateHiddenServerWidget(MountableButtonWidget widget, ServerClientSettingsProvider.HiddenServer server) {
        widget.setName(server.name());
        widget.setDescription(server.location());
        widget.setHiddenText("Version: " + (server.version() == null ? "" : server.version()));
        ((IconButton) widget.mountedWidgets.getFirst()).setOnClick(server.show());
    }

    private List<ServerClientSettingsProvider.HiddenServer> getHiddenServers() {
        List<ServerClientSettingsProvider.HiddenServer> hidden = new ArrayList<>(provider.hiddenServers());
        if (!provider.desktopInventory()) {
            return hidden;
        }
        List<String> hiddenRestudio = configManager.getHiddenRestudioServers();
        for (ServerModels.ClientServerView server : restudioInventory()) {
            if (!isHiddenRestudioServer(hiddenRestudio, server)) {
                continue;
            }
            String identifier = restudioIdentifier(server);
            String name = restudioName(server).isBlank() ? "ReStudio Server" : restudioName(server);
            String version = server.version == null ? "" : server.version;
            hidden.add(new ServerClientSettingsProvider.HiddenServer(name, "ReStudio", version, () -> {
                unhideRestudioServer(identifier, name);
                refreshSettings();
            }));
        }
        return hidden;
    }

    private MountableButtonWidget createQuickServerWidget(ServerClientSettingsProvider.QuickServer server) {
        IconButton visibilityButton = new IconButton.Builder()
            .imagePath(server.hidden() ? "add.png" : "hide.png")
            .onClick(server.toggleVisibility())
            .hint(server.hidden() ? "Show Quick Server" : "Hide Quick Server")
            .size(18, 18)
            .build();

        IconButton deleteButton = new IconButton.Builder()
            .imagePath("delete.png")
            .accentType(ThemeManager.getAccent("danger"))
            .onClick(() -> deleteQuickServer(server))
            .hint("Delete Quick Server")
            .size(18, 18)
            .build();

        String visibility = server.hidden() ? "Hidden" : "Visible";
        String versionInfo = server.version() == null || server.version().isBlank() ? visibility : visibility + " | " + server.version();

        var button = new MountableButtonWidget.Builder(server.name())
            .description("Quick Server")
            .hiddenText(versionInfo)
            .addWidget(visibilityButton)
            .addWidget(deleteButton)
            .build();
        button.xOffset = 2;
        return button;
    }

    private void deleteQuickServer(ServerClientSettingsProvider.QuickServer server) {
        Notification notification = new Notification.Builder()
            .message("Deleting Quick Server")
            .description(server.name())
            .type(Notification.Type.INFO)
            .loading(true)
            .autoSlideOut(false)
            .build();
        server.delete().get()
            .whenComplete((unused, throwable) -> ScreenManager.getInstance().execute(() -> {
                if (throwable != null) {
                    Throwable error = throwable.getCause() != null ? throwable.getCause() : throwable;
                    notification.update()
                        .message("Delete Failed")
                        .description(error.getMessage() == null ? server.name() : error.getMessage())
                        .type(Notification.Type.ERROR)
                        .loading(false)
                        .autoSlideOut(true)
                        .commit();
                    return;
                }
                notification.update()
                    .message("Quick Server Deleted")
                    .description(server.name())
                    .type(Notification.Type.SUCCESS)
                    .loading(false)
                    .autoSlideOut(true)
                    .commit();
                refreshSettings();
            }));
    }

    private MountableButtonWidget createHiddenServerWidget(ServerClientSettingsProvider.HiddenServer server) {
        IconButton unhideButton = new IconButton.Builder().imagePath("add.png").onClick(server.show())
                .hint("Unhide this server").size(18, 18).build();
        MountableButtonWidget button = new MountableButtonWidget.Builder(server.name()).description(server.location())
                .hiddenText("Version: " + (server.version() == null ? "" : server.version())).addWidget(unhideButton).build();
        button.xOffset = 2;
        return button;
    }

    private void refreshSettings() {
        for (SettingsScreen settingsScreen : openSettingsScreens()) {
            settingsScreen.refreshTab("Servers");
        }
    }

    private Set<SettingsScreen> openSettingsScreens() {
        ScreenManager screenManager = ScreenManager.getInstance();
        Set<SettingsScreen> settingsScreens = new LinkedHashSet<>();
        if (screenManager.getCurrentScreen() instanceof SettingsScreen settingsScreen) {
            settingsScreens.add(settingsScreen);
        }
        var overlay = screenManager.getDesktopWindowsOverlay();
        if (overlay != null) {
            for (ScreenWindowWidget window : overlay.getWindows()) {
                if (window.getScreen() instanceof SettingsScreen settingsScreen) {
                    settingsScreens.add(settingsScreen);
                }
            }
        }
        return settingsScreens;
    }

    private List<ServerModels.ClientServerView> restudioInventory() {
        RemotelyClient client = RemotelyClient.INSTANCE;
        if (client == null || client.getHost() == null) {
            setRestudioInventoryAccount(false, "");
            return List.of();
        }
        ServerScreenHost host = client.getHost().serverScreenHost(client);
        ServerScreenHost.AccountIdentity account = host.accountIdentity();
        boolean authenticated = account != null && account.authenticated();
        String accountKey = restudioAccountKey(account);
        setRestudioInventoryAccount(authenticated, accountKey);
        if (!authenticated) {
            return List.of();
        }
        ServerManagerScreen manager = resolveServerManagerScreen();
        if (manager != null) {
            List<ServerModels.ClientServerView> managerInventory = manager.getRestudioInstances();
            if (!managerInventory.isEmpty()) {
                return managerInventory;
            }
        }
        if (restudioInventoryLoaded || restudioInventoryRequestInFlight) {
            return restudioInventory;
        }
        long requestGeneration = restudioInventoryRequestGeneration;
        String requestAccount = accountKey;
        restudioInventoryRequestInFlight = true;
        Async<List<ServerModels.ClientServerView>> request = host.restudioServers();
        List<ServerModels.ClientServerView> current = request.getNow(List.of());
        request.whenComplete((servers, failure) -> ScreenManager.getInstance().execute(() -> {
            if (requestGeneration != restudioInventoryRequestGeneration) {
                return;
            }
            restudioInventoryRequestInFlight = false;
            if (failure != null) {
                scheduleRestudioInventoryRetry(requestGeneration, requestAccount);
                return;
            }
            RemotelyClient currentClient = RemotelyClient.INSTANCE;
            ServerScreenHost currentHost = currentClient == null || currentClient.getHost() == null
                    ? null : currentClient.getHost().serverScreenHost(currentClient);
            ServerScreenHost.AccountIdentity currentAccount = currentHost == null ? null : currentHost.accountIdentity();
            boolean currentAuthenticated = currentAccount != null && currentAccount.authenticated();
            String currentAccountKey = restudioAccountKey(currentAccount);
            if (currentAuthenticated != authenticated || !requestAccount.equals(currentAccountKey)) {
                setRestudioInventoryAccount(currentAuthenticated, currentAccountKey);
                return;
            }
            restudioInventory = servers == null ? List.of() : List.copyOf(servers);
            restudioInventoryLoaded = true;
            resetRestudioInventoryRetry();
            refreshSettings();
        }));
        return current;
    }

    private void setRestudioInventoryAccount(boolean authenticated, String accountKey) {
        if (restudioInventoryAuthenticated == authenticated && restudioInventoryAccount.equals(accountKey)) {
            return;
        }
        restudioInventory = List.of();
        restudioInventoryLoaded = false;
        restudioInventoryRequestInFlight = false;
        restudioInventoryAuthenticated = authenticated;
        restudioInventoryAccount = accountKey;
        restudioInventoryRequestGeneration++;
        resetRestudioInventoryRetry();
    }

    private void scheduleRestudioInventoryRetry(long requestGeneration, String requestAccount) {
        if (restudioInventoryRetryUsed || restudioInventoryRetryScheduled || openSettingsScreens().isEmpty()) {
            return;
        }
        restudioInventoryRetryUsed = true;
        restudioInventoryRetryScheduled = true;
        long retryGeneration = restudioInventoryRetryGeneration;
        AsyncTools.schedule(TaskSchedulers.current(), RESTUDIO_INVENTORY_RETRY_DELAY, () ->
                ScreenManager.getInstance().execute(() -> {
                    if (retryGeneration != restudioInventoryRetryGeneration) {
                        return;
                    }
                    restudioInventoryRetryScheduled = false;
                    if (requestGeneration != restudioInventoryRequestGeneration
                            || !restudioInventoryAuthenticated
                            || !requestAccount.equals(restudioInventoryAccount)
                            || openSettingsScreens().isEmpty()) {
                        return;
                    }
                    refreshSettings();
                }));
    }

    private void resetRestudioInventoryRetry() {
        restudioInventoryRetryScheduled = false;
        restudioInventoryRetryUsed = false;
        restudioInventoryRetryGeneration++;
    }

    private String restudioAccountKey(ServerScreenHost.AccountIdentity account) {
        if (account == null || !account.authenticated()) {
            return "";
        }
        if (!account.subjectId().isBlank()) {
            return "subject:" + account.subjectId();
        }
        return "name:" + account.displayName();
    }

    private ServerManagerScreen resolveServerManagerScreen() {
        if (ScreenManager.currentScreen instanceof ServerManagerScreen manager) {
            return manager;
        }
        return ScreenManager.getInstance().getDesktopSuperScreen() instanceof ServerManagerScreen manager ? manager : null;
    }


    private boolean isHiddenRestudioServer(List<String> hiddenServers, ServerModels.ClientServerView server) {
        if (server == null || hiddenServers == null || hiddenServers.isEmpty()) {
            return false;
        }
        String identifier = restudioIdentifier(server);
        if (!identifier.isBlank() && hiddenServers.contains(identifier)) {
            return true;
        }
        String name = restudioName(server);
        return !name.isBlank() && hiddenServers.contains(name);
    }

    private void unhideRestudioServer(String identifier, String name) {
        String primary = identifier.isBlank() ? name : identifier;
        if (!primary.isBlank()) {
            configManager.unhideRestudioServer(primary);
        }
        if (!identifier.isBlank() && !name.isBlank() && !identifier.equals(name)
                && configManager.getHiddenRestudioServers().contains(name)) {
            configManager.unhideRestudioServer(name);
        }
    }

    private String restudioIdentifier(ServerModels.ClientServerView server) {
        if (server == null) {
            return "";
        }
        if (server.identifier != null && !server.identifier.isBlank()) {
            return server.identifier;
        }
        return server.uuid == null ? "" : server.uuid;
    }

    private String restudioName(ServerModels.ClientServerView server) {
        return server == null || server.name == null ? "" : server.name;
    }

}
