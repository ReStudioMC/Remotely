package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.config.GlobalSettingsProviders;
import redxax.oxy.remotely.config.SettingsScreenRuntime;
import redxax.oxy.remotely.ui.settings.controllers.CollaborationSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.PackContentSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ReProxySettingsCapability;
import redxax.oxy.remotely.ui.settings.controllers.ServerClientSettingsProvider;
import redxax.oxy.remotely.ui.server.ServerManagerScreen;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rebase.restudio.community.ReStudioAccountManagementProvider;
import restudio.rebase.restudio.community.ReStudioCommunityProvider;
import restudio.rebase.restudio.community.ReStudioCommunityProviders;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rebase.settings.controllers.AppearanceSettingsProvider;
import restudio.rebase.settings.controllers.BackupSettingsProvider;
import restudio.rebase.settings.controllers.InstanceStorageSettingsProvider;
import restudio.rebase.settings.controllers.JavaSettingsProvider;
import restudio.rebase.settings.controllers.LspSettingsProvider;
import restudio.rebase.settings.controllers.MinecraftAssetsSettingsProvider;
import restudio.rebase.settings.controllers.LogSettingsProvider;
import restudio.rebase.settings.controllers.PresetSettingsProvider;
import restudio.rebase.settings.controllers.ReStudioAccountSettingsProvider;
import restudio.rebase.settings.controllers.SettingsActionCapability;
import restudio.rebase.settings.controllers.ThemeSettingsProvider;
import restudio.rebase.preset.OptionsPreset;
import restudio.rebase.preset.ResourceCollectionEntry;
import restudio.rebase.preset.ResourceList;
import restudio.rescreen.platform.Async;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.logging.ConsolePolicy;
import restudio.rescreen.logging.LogEvent;
import restudio.rescreen.logging.LogType;
import restudio.rescreen.logging.ReLog;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.Theme;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class BrowserGlobalSettingsProviders {
    private BrowserGlobalSettingsProviders() {
    }

    static GlobalSettingsProviders create(ReScreen parent, BrowserRemotelyConfigStore config, BrowserRemotelyServerApi api) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(config, "config");
        BrowserSettingsRuntime runtime = new BrowserSettingsRuntime(parent);
        String storageReason = "Instance Storage Paths And Migration Require Local Filesystem Access.";
        return new GlobalSettingsProviders(
                AppearanceSettingsProvider.browser(),
                browserThemeSettings(config),
                browserServerSettings(config, runtime),
                InstanceStorageSettingsProvider.unavailable("Browser Storage", "Browser Storage", storageReason),
                false,
                ReProxySettingsCapability.unavailable(BrowserLaunchSession.authenticated(),
                        "ReProxy Account Operations Are Not Exposed By The Browser Session."),
                null,
                SettingsActionCapability.unavailable("settings.discord-rpc", "Discord Activity Is Unavailable In Browser."),
                PackContentSettingsProvider.unavailable("Pack Content Refreshes Automatically For Open Server Workspaces."),
                JavaSettingsProvider.browser("Remotely"),
                LspSettingsProvider.browser(config),
                new BrowserMinecraftAssetsSettingsProvider(config),
                "The Browser Always Uses The Integrated File Explorer.",
                BackupSettingsProvider.unavailable(true, "Backup Storage Requires Local Filesystem Access."),
                browserPresetSettings(),
                browserAccountSettings(runtime),
                new CollaborationSettingsProvider() {
                    public boolean available(String control) { return false; }
                },
                browserLogSettings(config),
                new BrowserConnectionSettings(api)::getSettings,
                runtime);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String currentSubjectScope() {
        if (!BrowserLaunchSession.authenticated()) {
            return "anonymous";
        }
        BrowserLaunchSession.Metadata metadata = BrowserLaunchSession.metadata();
        ReStudioCommunityProvider provider = ReStudioCommunityProviders.current();
        String subject = firstNonBlank(metadata.subjectId(), provider == null ? "" : provider.userId());
        subject = firstNonBlank(subject, metadata.username());
        subject = firstNonBlank(subject, provider == null ? "" : provider.username());
        return firstNonBlank(subject, "anonymous");
    }

    private static String currentSessionScope() {
        if (!BrowserLaunchSession.authenticated()) {
            return "";
        }
        BrowserLaunchSession.Metadata metadata = BrowserLaunchSession.metadata();
        return firstNonBlank(metadata.grantId(), metadata.ticket());
    }

    private static ServerClientSettingsProvider browserServerSettings(BrowserRemotelyConfigStore config, BrowserSettingsRuntime runtime) {
        return new ServerClientSettingsProvider() {
            @Override
            public boolean available(String control) {
                return false;
            }

            @Override
            public boolean desktopInventory() {
                return false;
            }

            @Override
            public List<HiddenServer> hiddenServers() {
                return config.getHiddenRestudioServers().stream().map(id -> {
                    ServerModels.ClientServerView server = runtime.server(id);
                    String name = server == null || server.name == null || server.name.isBlank() ? "ReStudio Server" : server.name;
                    String version = server == null || server.version == null ? "" : server.version;
                    return new HiddenServer(name, "ReStudio", version, () -> {
                        config.unhideRestudioServer(id);
                        runtime.refreshTab("Servers");
                    });
                }).toList();
            }
        };
    }

    private static PresetSettingsProvider browserPresetSettings() {
        String reason = "Preset Storage And Local Instance Import Are Unavailable In Browser.";
        return new PresetSettingsProvider() {
            public Screen currentScreen() { return ScreenManager.getInstance().getCurrentScreen(); }
            public void execute(Runnable action) { ScreenManager.getInstance().execute(action); }
            public List<ResourceList> resourceLists() { return List.of(); }
            public void addResourceList(ResourceList list) { }
            public void removeResourceList(ResourceList list) { }
            public void saveResourceLists() { }
            public List<OptionsPreset> optionsPresets() { return List.of(); }
            public void addOptionsPreset(OptionsPreset preset) { }
            public void removeOptionsPreset(OptionsPreset preset) { }
            public void saveOptionsPresets() { }
            public List<PresetInstance> instances() { return List.of(); }
            public Async<List<String>> importResources(PresetInstance instance) { return Async.failed(new UnsupportedOperationException(reason)); }
            public Async<String> importOptions(PresetInstance instance) { return Async.failed(new UnsupportedOperationException(reason)); }
            public void browseResources(Screen parent, Consumer<List<ResourceCollectionEntry>> selected) { }
            public void editResourceList(Screen parent, ResourceList list, Runnable closed) { }
            public Accent accent(String name) { return ThemeManager.getAccent(name); }
            public CapabilityDescriptor capability(String id) { return CapabilityDescriptor.unavailable(id, reason); }
        };
    }

    private static LogSettingsProvider browserLogSettings(BrowserRemotelyConfigStore config) {
        return new LogSettingsProvider() {
            public List<LogType> types() { return ReLog.registeredTypes(); }
            public List<LogEvent> recent(int limit) { return ReLog.recent(limit); }
            public ConsolePolicy consolePolicy(LogType type) {
                if (type == null) return ConsolePolicy.OFF;
                try {
                    return ConsolePolicy.valueOf(config.get("logs.console." + type.id(), type.consolePolicy().name()));
                } catch (RuntimeException ignored) {
                    return type.consolePolicy();
                }
            }
            public void consolePolicy(LogType type, ConsolePolicy policy) {
                if (type == null || policy == null) return;
                config.set("logs.console." + type.id(), policy.name());
                config.save();
            }
            public void copy(String value) { ScreenManager.getInstance().clipboardHandler().setClipboard(value); }
            public void openFolder() { }
            public int textColor() { return ThemeManager.getColor(ThemeColor.text); }
            public CapabilityDescriptor capability(String id) {
                return LogSettingsProvider.OPEN_FOLDER.equals(id)
                        ? CapabilityDescriptor.unavailable(id, "Local Log Folders Are Unavailable In Browser.")
                        : CapabilityDescriptor.supported(id);
            }
        };
    }

    private static ReStudioAccountSettingsProvider browserAccountSettings(BrowserSettingsRuntime runtime) {
        return new ReStudioAccountSettingsProvider() {
            public ReStudioCommunityProvider community() { return ReStudioCommunityProviders.current(); }
            public ReStudioAccountManagementProvider accountManagement() {
                ReStudioCommunityProvider provider = community();
                return provider instanceof ReStudioAccountManagementProvider management ? management : null;
            }
            public String applicationName() { return "Remotely"; }
            public String applicationVersion() { return "Browser"; }
            public String updateChannel() { return "Browser"; }
            public void setUpdateChannel(String channel) { throw new UnsupportedOperationException("Browser Updates Are Unavailable"); }
            public String updateMode() { return "Web"; }
            public String runtimeName() { return "Browser"; }
            public String runtimeVendor() { return "Web Runtime"; }
            public String systemName() { return "Browser"; }
            public String systemArchitecture() { return "Web"; }
            public String sessionStatus() { return BrowserLaunchSession.authenticated() ? "Signed In" : "Signed Out"; }
            public boolean updatesAvailable() { return false; }
            public void checkForUpdates() { }
            public boolean current(Screen screen) {
                return runtime.active && runtime.screen == screen && runtime.lifecycleGeneration > 0
                        && ScreenManager.getInstance().getCurrentScreen() == screen
                        && Objects.equals(runtime.initialSubjectScope, currentSubjectScope())
                        && Objects.equals(runtime.initialSessionScope, currentSessionScope());
            }
        };
    }

    private static ThemeSettingsProvider browserThemeSettings(BrowserRemotelyConfigStore config) {
        restoreBrowserThemes(config);
        return new ThemeSettingsProvider() {
            public Screen currentScreen() { return ScreenManager.getInstance().getCurrentScreen(); }
            public Theme currentTheme() { return ThemeManager.getCurrentTheme(); }
            public Theme theme(String name) { return ThemeManager.getTheme(name); }
            public List<String> themeNames() { return new ArrayList<>(ThemeManager.getRegisteredThemes().keySet()); }
            public Accent accent(String name) { return ThemeManager.getAccent(name); }
            public int registeredAccentCount() { return ThemeManager.getRegisteredAccents().size(); }
            public boolean textShadow() { return config.getShadow(); }
            public void registerTheme(Theme theme) { ThemeManager.registerTheme(theme); }
            public void removeTheme(String name) { ThemeManager.removeTheme(name); }
            public void selectTheme(String name) { config.setCurrentTheme(name); ThemeManager.setCurrentTheme(name); }
            public void save() { saveBrowserThemes(config); }
            public Identifier colorPreview(int color) { return ScreenManager.getInstance().imageAssets().registerSolidColor(color); }
            public CapabilityDescriptor capability(String id) {
                return CapabilityDescriptor.unavailable(id, "Theme File Import And Export Require Desktop File Access.");
            }
            public void importTheme(SettingsScreen screen, Consumer<String> imported) { }
            public void exportTheme(Theme theme) { }
        };
    }

    private static void saveBrowserThemes(BrowserRemotelyConfigStore config) {
        JsonArray themes = new JsonArray();
        ThemeManager.getRegisteredThemes().values().forEach(theme -> {
            JsonObject value = new JsonObject();
            value.addProperty("name", theme.getName());
            value.addProperty("defaultAccent", theme.getDefaultAccentName());
            JsonObject colors = new JsonObject();
            theme.getColors().forEach((key, color) -> colors.addProperty(key.name(), color));
            value.add("colors", colors);
            JsonArray accents = new JsonArray();
            theme.getAccents().values().forEach(accent -> {
                JsonObject item = new JsonObject();
                item.addProperty("name", accent.getName());
                item.addProperty("accent", accent.getAccentColor());
                item.addProperty("accentHover", accent.getAccentHoverColor());
                item.addProperty("accentDark", accent.getAccentDarkColor());
                item.addProperty("accentDarkHover", accent.getAccentDarkHoverColor());
                item.addProperty("bottom", accent.getBottomColor());
                item.addProperty("bottomHover", accent.getBottomHoverColor());
                item.addProperty("outerBorder", accent.getOuterBorderColor());
                accents.add(item);
            });
            value.add("accents", accents);
            themes.add(value);
        });
        config.set("ui.browserThemes", themes.toString());
        config.save();
    }

    private static void restoreBrowserThemes(BrowserRemotelyConfigStore config) {
        String serialized = config.get("ui.browserThemes", "");
        if (serialized.isBlank()) return;
        try {
            JsonElement parsed = BrowserJson.parse(serialized);
            if (!parsed.isJsonArray()) return;
            parsed.getAsJsonArray().forEach(element -> {
                if (!element.isJsonObject()) return;
                JsonObject value = element.getAsJsonObject();
                String name = BrowserJson.string(value, "name");
                if (name.isBlank()) return;
                Theme theme = new Theme(name);
                JsonElement colors = BrowserJson.element(value, "colors");
                if (colors != null && colors.isJsonObject()) colors.getAsJsonObject().entrySet().forEach(entry -> {
                    try {
                        theme.setColor(ThemeColor.valueOf(entry.getKey()), entry.getValue().getAsInt());
                    } catch (RuntimeException ignored) {
                    }
                });
                JsonElement accents = BrowserJson.element(value, "accents");
                if (accents != null && accents.isJsonArray()) accents.getAsJsonArray().forEach(item -> {
                    if (!item.isJsonObject()) return;
                    JsonObject accent = item.getAsJsonObject();
                    theme.addAccent(new Accent(BrowserJson.string(accent, "name"), BrowserJson.integer(accent, "accentHover", 0),
                            BrowserJson.integer(accent, "accent", 0), BrowserJson.integer(accent, "accentDarkHover", 0),
                            BrowserJson.integer(accent, "accentDark", 0), BrowserJson.integer(accent, "bottomHover", 0),
                            BrowserJson.integer(accent, "bottom", 0), BrowserJson.integer(accent, "outerBorder", 0)));
                });
                theme.setDefaultAccentName(BrowserJson.string(value, "defaultAccent"));
                ThemeManager.registerTheme(theme);
            });
            ThemeManager.setCurrentTheme(config.getCurrentTheme());
        } catch (RuntimeException ignored) {
        }
    }

    private static final class BrowserSettingsRuntime implements SettingsScreenRuntime {
        private final ReScreen parent;
        private SettingsScreen screen;
        private String initialSubjectScope = "anonymous";
        private String initialSessionScope = "";
        private List<ServerModels.ClientServerView> serverInventory = List.of();
        private Async<List<ServerModels.ClientServerView>> serverInventoryRequest;
        private ServerScreenHost serverHost;
        private long serverInventoryGeneration;
        private final Runnable serverInventoryListener = this::loadServerInventory;
        private long lifecycleGeneration;
        private boolean active;
        private boolean listenersRegistered;
        private final Runnable authStateListener = () -> {
            SettingsScreen target = screen;
            long generation = lifecycleGeneration;
            ScreenManager.getInstance().execute(() -> handleAuthStateChange(target, generation));
        };
        private final Runnable sessionExpiryListener = () -> {
            SettingsScreen target = screen;
            long generation = lifecycleGeneration;
            ScreenManager.getInstance().execute(() -> handleSessionExpiry(target, generation));
        };

        private BrowserSettingsRuntime(ReScreen parent) {
            this.parent = parent;
        }

        @Override
        public void opened() {
            active = true;
            lifecycleGeneration++;
            initialSubjectScope = currentSubjectScope();
            initialSessionScope = currentSessionScope();
        }

        @Override
        public void displayed(SettingsScreen screen) {
            this.screen = screen;
            RemotelyClient client = RemotelyClient.INSTANCE;
            serverHost = client == null || client.getHost() == null ? null : client.getHost().serverScreenHost(client);
            if (serverHost != null) serverHost.addInstanceChangeListener(serverInventoryListener);
            loadServerInventory();
            if (!listenersRegistered) {
                BrowserLaunchSession.addAuthStateListener(authStateListener);
                BrowserLaunchSession.addSessionExpiryListener(sessionExpiryListener);
                listenersRegistered = true;
            }
        }

        @Override
        public void cleanup() {
            active = false;
            lifecycleGeneration++;
            serverInventoryGeneration++;
            if (serverHost != null) serverHost.removeInstanceChangeListener(serverInventoryListener);
            serverHost = null;
            Async<List<ServerModels.ClientServerView>> request = serverInventoryRequest;
            serverInventoryRequest = null;
            if (request != null && !request.isDone()) request.cancel();
            serverInventory = List.of();
            if (listenersRegistered) {
                BrowserLaunchSession.removeAuthStateListener(authStateListener);
                BrowserLaunchSession.removeSessionExpiryListener(sessionExpiryListener);
                listenersRegistered = false;
            }
            screen = null;
        }

        private ServerModels.ClientServerView server(String id) {
            if (id == null || id.isBlank()) return null;
            for (ServerModels.ClientServerView server : serverInventory) {
                if (server == null) continue;
                if (id.equals(server.identifier) || id.equals(server.uuid) || id.equals(server.name)) return server;
            }
            return null;
        }

        private void loadServerInventory() {
            if (!active || screen == null || ScreenManager.getInstance().getCurrentScreen() != screen
                    || !BrowserLaunchSession.authenticated()) return;
            if (parent instanceof ServerManagerScreen manager && !manager.getRestudioInstances().isEmpty()) {
                updateServerInventory(manager.getRestudioInstances());
                return;
            }
            if (serverHost == null || serverInventoryRequest != null && !serverInventoryRequest.isDone()) return;
            long requestGeneration = ++serverInventoryGeneration;
            long generation = lifecycleGeneration;
            String subject = currentSubjectScope();
            String session = currentSessionScope();
            Async<List<ServerModels.ClientServerView>> request = serverHost.restudioServers();
            serverInventoryRequest = request;
            request.whenComplete((servers, failure) -> ScreenManager.getInstance().execute(() -> {
                if (requestGeneration != serverInventoryGeneration) return;
                serverInventoryRequest = null;
                if (failure != null || !active || screen == null || lifecycleGeneration != generation
                        || ScreenManager.getInstance().getCurrentScreen() != screen || !BrowserLaunchSession.authenticated()
                        || !Objects.equals(subject, currentSubjectScope()) || !Objects.equals(session, currentSessionScope())) return;
                updateServerInventory(servers);
            }));
        }

        private void updateServerInventory(List<ServerModels.ClientServerView> servers) {
            List<ServerModels.ClientServerView> resolved = servers == null ? List.of() : List.copyOf(servers);
            if (sameServerInventory(serverInventory, resolved)) return;
            serverInventory = resolved;
            refreshTab("Servers");
        }

        private boolean sameServerInventory(List<ServerModels.ClientServerView> first, List<ServerModels.ClientServerView> second) {
            if (first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++) {
                ServerModels.ClientServerView left = first.get(index);
                ServerModels.ClientServerView right = second.get(index);
                if (left == right) continue;
                if (left == null || right == null || !Objects.equals(left.identifier, right.identifier)
                        || !Objects.equals(left.uuid, right.uuid) || !Objects.equals(left.name, right.name)
                        || !Objects.equals(left.version, right.version)) return false;
            }
            return true;
        }

        private void refreshTab(String tab) {
            if (active && screen != null && ScreenManager.getInstance().getCurrentScreen() == screen) {
                screen.refreshTab(tab);
            }
        }

        private void handleAuthStateChange(SettingsScreen target, long generation) {
            if (active && screen == target && lifecycleGeneration == generation && ScreenManager.getInstance().getCurrentScreen() == target
                    && !Objects.equals(initialSubjectScope, currentSubjectScope())) {
                ScreenManager.getInstance().setScreen(parent);
            }
        }

        private void handleSessionExpiry(SettingsScreen target, long generation) {
            if (active && screen == target && lifecycleGeneration == generation && ScreenManager.getInstance().getCurrentScreen() == target) {
                ScreenManager.getInstance().setScreen(parent);
            }
        }
    }

}
