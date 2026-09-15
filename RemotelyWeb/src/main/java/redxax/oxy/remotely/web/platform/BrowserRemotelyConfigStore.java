package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import redxax.oxy.remotely.config.RemotelyGroup;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyViewStateStore;
import redxax.oxy.remotely.ui.server.ServerIconProvider;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.settings.SettingsCapabilityProvider;
import restudio.rebase.settings.controllers.MinecraftAssetsSettingsProvider;
import restudio.rescreen.config.Config;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Sound;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class BrowserRemotelyConfigStore implements RemotelyConfigStore, RemotelyViewStateStore, SettingsCapabilityProvider,
        DeveloperCapabilityProvider.Settings, MinecraftAssetsSettingsProvider.Settings {
    private static final String PREFIX = "remotely.config.";
    private static final String LEGACY_RECENT_ITEMS_KEY = PREFIX + RemotelyConfigStore.RECENT_RESTUDIO_ITEMS_KEY;
    private static final String UNOWNED_RECENT_ITEMS_KEY = LEGACY_RECENT_ITEMS_KEY + ".unowned";
    private static final String GROUP_PREFIX = "remotely.desktopGroups.";
    private static final String ORDER_PREFIX = "remotely.order.";
    private static final String HIDDEN_SERVERS_KEY = "remotely.hidden.restudio";
    private static final String VIEW_STATE_PREFIX = "remotely.browser.view.";
    private static final String EXPLORER_PREFIX = "ui.explorer";
    private static final String ICON_PREFIX = "server.icon.";
    private static final String VIEW_STATE_HOST_KEY = VIEW_STATE_PREFIX + "host";
    private static final String VIEW_STATE_SERVER_KEY = VIEW_STATE_PREFIX + "server";
    private static final String VIEW_STATE_TAB_KEY = VIEW_STATE_PREFIX + "tab";
    private static final String VIEW_STATE_VIEW_KEY = VIEW_STATE_PREFIX + "view";
    private static final String VIEW_STATE_TERMINALS_KEY = VIEW_STATE_PREFIX + "terminals";
    private static final String VIEW_STATE_TERMINAL_INDEX_KEY = VIEW_STATE_PREFIX + "terminal-index";
    private final Storage storage;

    public BrowserRemotelyConfigStore() {
        this(new NativeStorage());
    }

    BrowserRemotelyConfigStore(Storage storage) {
        this.storage = storage;
    }
    static final String APPEARANCE_DESKTOP_MODE = "settings.appearance.desktop-mode";
    static final String APPEARANCE_COVER_PANORAMA = "settings.appearance.cover-panorama";
    static final String APPEARANCE_WALLPAPER = "settings.appearance.wallpaper";
    static final String APPEARANCE_BACKGROUND_SOURCE = "settings.appearance.background-source";
    static final String APPEARANCE_CUSTOM_BACKGROUND = "settings.appearance.custom-background";
    static final String APPEARANCE_RANDOM_PANORAMA = "settings.appearance.random-panorama";
    static final String APPEARANCE_PANORAMA_VERSION = "settings.appearance.panorama-version";
    static final String APPEARANCE_PANORAMA_SPEED = "settings.appearance.panorama-speed";
    static final String APPEARANCE_PANORAMA_BLUR = "settings.appearance.panorama-blur";
    static final String SERVERS_SCAN = "settings.servers.scan";
    static final String SERVERS_CUSTOM_PROXY = "settings.servers.custom-proxy";
    static final String SERVERS_PROXY_HOST = "settings.servers.proxy-host";
    static final String SERVERS_PROXY_USER = "settings.servers.proxy-user";
    static final String SERVERS_TERMINAL_SUGGESTIONS = "settings.servers.terminal-suggestions";
    static final String SERVERS_QUICK_PRECREATE = "settings.servers.quick-precreate";
    static final String SERVERS_QUICK_KEEP_RUNNING = "settings.servers.quick-keep-running";
    static final String SERVERS_QUICK_AUTO_RESTART = "settings.servers.quick-auto-restart";
    static final String SERVERS_QUICK_MIRROR_MODS = "settings.servers.quick-mirror-mods";
    static final String SERVERS_LOCAL_REMOTE_HIDDEN = "settings.servers.local-remote-hidden";
    static final String ABOUT_COLLABORATION_OVERRIDE = "settings.about.collaboration-override";
    static final String ABOUT_COLLABORATION_COLOR = "settings.about.collaboration-color";
    private static final Map<String, CapabilityDescriptor> SETTINGS_CAPABILITIES = Map.ofEntries(
            Map.entry(APPEARANCE, CapabilityDescriptor.supported(APPEARANCE)),
            Map.entry(THEME, CapabilityDescriptor.supported(THEME)),
            Map.entry(SOUNDS, CapabilityDescriptor.supported(SOUNDS)),
            Map.entry(SERVERS, CapabilityDescriptor.supported(SERVERS)),
            Map.entry(STORAGE, CapabilityDescriptor.unavailable(STORAGE, "Browser Storage Settings Are Unavailable")),
            Map.entry(RE_PROXY, CapabilityDescriptor.unavailable(RE_PROXY, "Browser ReProxy Settings Are Unavailable")),
            Map.entry(DISCORD, CapabilityDescriptor.unavailable(DISCORD, "Discord Settings Are Unavailable")),
            Map.entry(PACK_CONTENT, CapabilityDescriptor.supported(PACK_CONTENT)),
            Map.entry(JAVA, CapabilityDescriptor.unavailable(JAVA, "Java Settings Are Unavailable")),
            Map.entry(LSP, CapabilityDescriptor.supported(LSP)),
            Map.entry(MINECRAFT_ASSETS, CapabilityDescriptor.supported(MINECRAFT_ASSETS)),
            Map.entry(FILE_EXPLORER, CapabilityDescriptor.supported(FILE_EXPLORER)),
            Map.entry(BACKUPS, CapabilityDescriptor.unavailable(BACKUPS, "Backup Settings Are Unavailable")),
            Map.entry(PRESETS, CapabilityDescriptor.unavailable(PRESETS, "Preset Settings Are Unavailable")),
            Map.entry(ABOUT, CapabilityDescriptor.supported(ABOUT)),
            Map.entry(LOGS, CapabilityDescriptor.supported(LOGS)),
            Map.entry(DEVELOPMENT, CapabilityDescriptor.supported(DEVELOPMENT)));
    private static final Map<String, CapabilityDescriptor> SETTING_CONTROL_CAPABILITIES = Map.ofEntries(
            Map.entry(APPEARANCE_DESKTOP_MODE, CapabilityDescriptor.unavailable(APPEARANCE_DESKTOP_MODE, "Desktop Windows Are Unavailable In Browser")),
            Map.entry(APPEARANCE_COVER_PANORAMA, CapabilityDescriptor.unavailable(APPEARANCE_COVER_PANORAMA, "Minecraft Panorama Backgrounds Are Unavailable In Browser")),
            Map.entry(APPEARANCE_WALLPAPER, CapabilityDescriptor.unavailable(APPEARANCE_WALLPAPER, "Wallpaper Rendering Is Unavailable In Browser")),
            Map.entry(APPEARANCE_BACKGROUND_SOURCE, CapabilityDescriptor.unavailable(APPEARANCE_BACKGROUND_SOURCE, "Desktop Wallpaper Sources Are Unavailable In Browser")),
            Map.entry(APPEARANCE_CUSTOM_BACKGROUND, CapabilityDescriptor.unavailable(APPEARANCE_CUSTOM_BACKGROUND, "Local Background Files Are Unavailable In Browser")),
            Map.entry(APPEARANCE_RANDOM_PANORAMA, CapabilityDescriptor.unavailable(APPEARANCE_RANDOM_PANORAMA, "Minecraft Panorama Backgrounds Are Unavailable In Browser")),
            Map.entry(APPEARANCE_PANORAMA_VERSION, CapabilityDescriptor.unavailable(APPEARANCE_PANORAMA_VERSION, "Minecraft Panorama Backgrounds Are Unavailable In Browser")),
            Map.entry(APPEARANCE_PANORAMA_SPEED, CapabilityDescriptor.unavailable(APPEARANCE_PANORAMA_SPEED, "Minecraft Panorama Backgrounds Are Unavailable In Browser")),
            Map.entry(APPEARANCE_PANORAMA_BLUR, CapabilityDescriptor.unavailable(APPEARANCE_PANORAMA_BLUR, "Minecraft Panorama Backgrounds Are Unavailable In Browser")),
            Map.entry(SERVERS_SCAN, CapabilityDescriptor.unavailable(SERVERS_SCAN, "Local Server Scanning Is Unavailable In Browser")),
            Map.entry(SERVERS_CUSTOM_PROXY, CapabilityDescriptor.unavailable(SERVERS_CUSTOM_PROXY, "Custom Reverse Proxy Settings Are Unavailable In Browser")),
            Map.entry(SERVERS_PROXY_HOST, CapabilityDescriptor.unavailable(SERVERS_PROXY_HOST, "Custom Reverse Proxy Settings Are Unavailable In Browser")),
            Map.entry(SERVERS_PROXY_USER, CapabilityDescriptor.unavailable(SERVERS_PROXY_USER, "Custom Reverse Proxy Settings Are Unavailable In Browser")),
            Map.entry(SERVERS_TERMINAL_SUGGESTIONS, CapabilityDescriptor.unavailable(SERVERS_TERMINAL_SUGGESTIONS, "Terminal Code Suggestions Are Unavailable In Browser")),
            Map.entry(SERVERS_QUICK_PRECREATE, CapabilityDescriptor.unavailable(SERVERS_QUICK_PRECREATE, "Quick Servers Are Unavailable In Browser")),
            Map.entry(SERVERS_QUICK_KEEP_RUNNING, CapabilityDescriptor.unavailable(SERVERS_QUICK_KEEP_RUNNING, "Quick Servers Are Unavailable In Browser")),
            Map.entry(SERVERS_QUICK_AUTO_RESTART, CapabilityDescriptor.unavailable(SERVERS_QUICK_AUTO_RESTART, "Quick Servers Are Unavailable In Browser")),
            Map.entry(SERVERS_QUICK_MIRROR_MODS, CapabilityDescriptor.unavailable(SERVERS_QUICK_MIRROR_MODS, "Quick Servers Are Unavailable In Browser")),
            Map.entry(SERVERS_LOCAL_REMOTE_HIDDEN, CapabilityDescriptor.unavailable(SERVERS_LOCAL_REMOTE_HIDDEN, "Local And SSH Server Storage Is Unavailable In Browser")),
            Map.entry(ABOUT_COLLABORATION_OVERRIDE, CapabilityDescriptor.unavailable(ABOUT_COLLABORATION_OVERRIDE, "Collaboration Color Overrides Are Unavailable In Browser")),
            Map.entry(ABOUT_COLLABORATION_COLOR, CapabilityDescriptor.unavailable(ABOUT_COLLABORATION_COLOR, "Collaboration Color Overrides Are Unavailable In Browser")));

    @Override
    public Map<String, CapabilityDescriptor> settingsCapabilities() {
        return SETTINGS_CAPABILITIES;
    }

    CapabilityDescriptor settingControlCapability(String id) {
        if (id == null || id.isBlank()) {
            return CapabilityDescriptor.unavailable("settings.browser-control", "Browser Setting Is Unavailable");
        }
        return SETTING_CONTROL_CAPABILITIES.getOrDefault(id,
                CapabilityDescriptor.unavailable(id, "Browser Setting Is Unavailable"));
    }

    public String get(String key, String fallback) {
        if (RemotelyConfigStore.RECENT_RESTUDIO_ITEMS_KEY.equals(key)) {
            if (!hasAuthenticatedAccount()) {
                return fallback;
            }
            quarantineLegacyRecentItems();
            String scoped = storage.read(recentStorageKey());
            if (scoped != null) {
                return scoped;
            }
            return fallback;
        }
        String storageKey = scopedStorageKey(key);
        String value = storage.read(storageKey);
        if (value == null && accountScoped(key)) {
            String legacy = storage.read(PREFIX + key);
            if (legacy != null) {
                write(storageKey, legacy);
                erase(PREFIX + key);
                return legacy;
            }
        }
        if (value != null && accountScoped(key)) {
            erase(PREFIX + key);
        }
        return value == null ? fallback : value;
    }

    public void set(String key, String value) {
        boolean recentItems = RemotelyConfigStore.RECENT_RESTUDIO_ITEMS_KEY.equals(key);
        if (recentItems) {
            if (!hasAuthenticatedAccount()) {
                return;
            }
            quarantineLegacyRecentItems();
        }
        String storageKey = recentItems ? recentStorageKey() : scopedStorageKey(key);
        if (value == null) erase(storageKey);
        else write(storageKey, value);
        if (recentItems || accountScoped(key)) erase(PREFIX + key);
    }

    public void remove(String key) {
        boolean recentItems = RemotelyConfigStore.RECENT_RESTUDIO_ITEMS_KEY.equals(key);
        if (recentItems) {
            if (!hasAuthenticatedAccount()) {
                return;
            }
            quarantineLegacyRecentItems();
        }
        erase(recentItems ? recentStorageKey() : scopedStorageKey(key));
        if (recentItems || accountScoped(key)) erase(PREFIX + key);
    }

    public void save() {
    }

    public void apply() {
        Config.isDev = getIsDev();
        applyBrowserAppearance();
        Config.wallpaper = getWallpaper();
        Config.coverMinecraftPanorama = getCoverMinecraftPanorama();
        Config.backgroundSource = getBackgroundSource();
        Config.customBackgroundPath = getCustomBackgroundPath();
        Config.minecraftPanoramaVersion = getMinecraftPanoramaVersion();
        Config.minecraftPanoramaSpeed = getMinecraftPanoramaSpeed();
        Config.minecraftPanoramaBlur = getMinecraftPanoramaBlur();
        Config.randomMinecraftPanorama = getRandomMinecraftPanorama();
        Config.obfuscate = getObfuscate();
        Config.currentThemeName = getCurrentTheme();
        Sound.enableSFX = isSfxEnabled();
        Sound.pitchVariation = getPitchVariation();
        Sound.soundVolume = getSoundVolume();
        for (Sound sound : Sound.values()) applySound(sound, isSoundEnabled(sound));
        BrowserRemotelySettings.apply(this);
    }

    public void applyBrowserAppearance() {
        Config.shadow = getShadow();
        Config.globalExpandSpeed = getGlobalExpandSpeed();
        Config.scaleAnimationSpeed = getScaleAnimationSpeed();
        Config.globalScrollSpeed = getGlobalScrollSpeed();
        Config.globalMovementSpeed = getGlobalMovementSpeed();
        Config.consoleScrollSpeed = getConsoleScrollSpeed();
        Config.animationsEnabled = getAnimationsEnabled();
        Config.customMouse = getCustomMouse();
        Config.mouseSize = getMouseSize();
        Config.tailSize = getTailSize();
        Config.tailFollowSpeed = getTailFollowSpeed();
    }

    @Override
    public boolean lspEnabled() {
        return isLspEnabled();
    }

    @Override
    public void setLspEnabled(boolean enabled) {
        RemotelyConfigStore.super.setLspEnabled(enabled);
    }

    @Override
    public boolean lspManagedSupportEnabled() {
        return isLspManagedSupportEnabled();
    }

    @Override
    public void setLspManagedSupportEnabled(boolean enabled) {
        RemotelyConfigStore.super.setLspManagedSupportEnabled(enabled);
    }

    @Override
    public boolean lspAskBeforeDownload() {
        return isLspAskBeforeDownload();
    }

    @Override
    public void setLspAskBeforeDownload(boolean enabled) {
        RemotelyConfigStore.super.setLspAskBeforeDownload(enabled);
    }

    @Override
    public boolean lspShowSetupPrompts() {
        return isLspShowSetupPrompts();
    }

    @Override
    public void setLspShowSetupPrompts(boolean enabled) {
        RemotelyConfigStore.super.setLspShowSetupPrompts(enabled);
    }

    @Override
    public boolean lspShowStatusDetails() {
        return isLspShowStatusDetails();
    }

    @Override
    public void setLspShowStatusDetails(boolean enabled) {
        RemotelyConfigStore.super.setLspShowStatusDetails(enabled);
    }

    @Override
    public boolean lspShowProblemSummary() {
        return isLspShowProblemSummary();
    }

    @Override
    public void setLspShowProblemSummary(boolean enabled) {
        RemotelyConfigStore.super.setLspShowProblemSummary(enabled);
    }

    @Override
    public boolean enabled() {
        return isMinecraftAssetsEnabled();
    }

    @Override
    public void enabled(boolean enabled) {
        setMinecraftAssetsEnabled(enabled);
    }

    @Override
    public boolean managed() {
        return isMinecraftAssetsManagedEnabled();
    }

    @Override
    public void managed(boolean enabled) {
        setMinecraftAssetsManagedEnabled(enabled);
    }

    @Override
    public boolean autoDownload() {
        return isMinecraftAssetsAutoDownloadEnabled();
    }

    @Override
    public void autoDownload(boolean enabled) {
        setMinecraftAssetsAutoDownloadEnabled(enabled);
    }

    @Override
    public boolean askBeforeDownload() {
        return isMinecraftAssetsAskBeforeDownload();
    }

    @Override
    public void askBeforeDownload(boolean enabled) {
        setMinecraftAssetsAskBeforeDownload(enabled);
    }

    @Override
    public String versionTarget() {
        return getMinecraftAssetsVersionTarget();
    }

    @Override
    public void versionTarget(String version) {
        setMinecraftAssetsVersionTarget(version);
    }

    @Override
    public State getViewState() {
        if (!BrowserLaunchSession.authenticated() || BrowserLaunchSession.metadata().subjectId().isBlank()) {
            return State.empty();
        }
        return new State(get(VIEW_STATE_HOST_KEY, ""), get(VIEW_STATE_SERVER_KEY, ""),
                get(VIEW_STATE_TAB_KEY, ""), get(VIEW_STATE_VIEW_KEY, ""), terminalTabs(),
                integer(get(VIEW_STATE_TERMINAL_INDEX_KEY, "0")));
    }

    @Override
    public void setViewState(State state) {
        if (!BrowserLaunchSession.authenticated() || BrowserLaunchSession.metadata().subjectId().isBlank()) {
            return;
        }
        State resolved = state == null ? State.empty() : state;
        set(VIEW_STATE_HOST_KEY, resolved.hostKey());
        set(VIEW_STATE_SERVER_KEY, resolved.serverId());
        set(VIEW_STATE_TAB_KEY, resolved.tabId());
        set(VIEW_STATE_VIEW_KEY, resolved.viewId());
        set(VIEW_STATE_TERMINALS_KEY, resolved.terminalTabs().stream()
                .map(tab -> encode(tab.serverId()) + "." + encode(tab.name()))
                .collect(Collectors.joining(",")));
        set(VIEW_STATE_TERMINAL_INDEX_KEY, String.valueOf(resolved.terminalTabIndex()));
    }

    private List<TerminalTab> terminalTabs() {
        String stored = get(VIEW_STATE_TERMINALS_KEY, "");
        if (stored.isBlank()) return List.of();
        List<TerminalTab> tabs = new ArrayList<>();
        for (String entry : stored.split(",")) {
            int separator = entry.indexOf('.');
            if (separator < 0) continue;
            String serverId = decode(entry.substring(0, separator));
            if (serverId.isBlank()) continue;
            tabs.add(new TerminalTab(serverId, decode(entry.substring(separator + 1))));
        }
        return List.copyOf(tabs);
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public IconSelection getServerIcon(String serverId) {
        if (!hasAuthenticatedAccount() || serverId == null || serverId.isBlank()) {
            return null;
        }
        String prefix = iconPrefix(serverId);
        String namespace = get(prefix + "namespace", "");
        String path = get(prefix + "path", "");
        String type = get(prefix + "type", "");
        if (namespace.isBlank() || path.isBlank() || type.isBlank()) {
            return null;
        }
        try {
            return new IconSelection(new Identifier(namespace, path, Identifier.Type.valueOf(type)),
                    Integer.parseInt(get(prefix + "tint", String.valueOf(ServerIconProvider.ICON_TINTS.getFirst()))),
                    get(prefix + "source", ""));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void setServerIcon(String serverId, Identifier image, int tint) {
        setServerIcon(serverId, image, tint, "");
    }

    public void setServerIcon(String serverId, Identifier image, int tint, String source) {
        if (!hasAuthenticatedAccount() || serverId == null || serverId.isBlank() || image == null) {
            return;
        }
        String prefix = iconPrefix(serverId);
        set(prefix + "namespace", image.namespace());
        set(prefix + "path", image.path());
        set(prefix + "type", image.type().name());
        set(prefix + "tint", String.valueOf(tint));
        set(prefix + "source", source == null ? "" : source);
    }

    public record IconSelection(Identifier image, int tint, String source) {
        public IconSelection {
            source = source == null ? "" : source;
        }

        public IconSelection(Identifier image, int tint) {
            this(image, tint, "");
        }
    }

    private String recentStorageKey() {
        return PREFIX + RemotelyConfigStore.RECENT_RESTUDIO_ITEMS_KEY + "." + accountScope();
    }

    private boolean hasAuthenticatedAccount() {
        return BrowserLaunchSession.authenticated() && !BrowserLaunchSession.metadata().subjectId().isBlank();
    }

    private void quarantineLegacyRecentItems() {
        String legacy = storage.read(LEGACY_RECENT_ITEMS_KEY);
        if (legacy == null) {
            return;
        }
        write(UNOWNED_RECENT_ITEMS_KEY, legacy);
        erase(LEGACY_RECENT_ITEMS_KEY);
    }

    private String scopedStorageKey(String key) {
        return PREFIX + key + (accountScoped(key) ? "." + accountScope() : "");
    }

    private boolean accountScoped(String key) {
        return key != null && (HIDDEN_SERVERS_KEY.equals(key) || key.startsWith(ORDER_PREFIX) || key.startsWith(GROUP_PREFIX)
                || key.startsWith(VIEW_STATE_PREFIX) || key.startsWith(EXPLORER_PREFIX) || key.startsWith(ICON_PREFIX));
    }

    private String iconPrefix(String serverId) {
        return ICON_PREFIX + encode(serverId) + ".";
    }

    private String accountScope() {
        String subjectId = BrowserLaunchSession.authenticated() ? BrowserLaunchSession.metadata().subjectId() : "";
        return subjectId == null || subjectId.isBlank() ? "anonymous" : encode(subjectId);
    }

    public List<ServerScreenHost.GroupView> getInstanceGroupViews(String context) {
        String prefix = groupPrefix(context);
        List<ServerScreenHost.GroupView> result = new ArrayList<>();
        for (String id : values(get(prefix + "ids", ""))) {
            String name = decode(get(prefix + id + ".name", ""));
            List<String> members = values(get(prefix + id + ".members", "")).stream()
                    .map(this::decode)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (!members.isEmpty()) result.add(new ServerScreenHost.GroupView(id, name, members));
        }
        return result;
    }

    public void setInstanceGroupViews(String context, List<ServerScreenHost.GroupView> groups) {
        String prefix = groupPrefix(context);
        List<String> previousIds = values(get(prefix + "ids", ""));
        Set<String> activeIds = new LinkedHashSet<>();
        if (groups != null) {
            for (ServerScreenHost.GroupView group : groups) {
                if (group == null || group.id().isBlank() || group.members().isEmpty()) continue;
                activeIds.add(group.id());
                set(prefix + group.id() + ".name", encode(group.name()));
                set(prefix + group.id() + ".members", group.members().stream().distinct()
                        .map(this::encode)
                        .reduce((left, right) -> left + "," + right)
                        .orElse(""));
            }
        }
        previousIds.stream()
                .filter(id -> !activeIds.contains(id))
                .forEach(id -> {
                    remove(prefix + id + ".name");
                    remove(prefix + id + ".members");
                });
        set(prefix + "ids", String.join(",", activeIds));
    }

    @Override
    public List<RemotelyGroup> getInstanceGroups(String context) {
        return getInstanceGroupViews(context).stream()
                .map(group -> new RemotelyGroup(group.id(), group.name(), group.members()))
                .toList();
    }

    @Override
    public void setInstanceGroups(String context, List<RemotelyGroup> groups) {
        List<ServerScreenHost.GroupView> views = groups == null ? List.of() : groups.stream()
                .filter(group -> group != null)
                .map(group -> new ServerScreenHost.GroupView(group.id(), group.name(), group.members()))
                .toList();
        setInstanceGroupViews(context, views);
    }

    private String groupPrefix(String context) {
        return GROUP_PREFIX + encode(context == null ? "" : context) + ".";
    }

    private List<String> values(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static void applySound(Sound sound, boolean enabled) {
        switch (sound) {
            case CLICK -> Sound.soundCLICK = enabled;
            case HOVER -> Sound.soundHOVER = enabled;
            case RIGHTCLICK -> Sound.soundRIGHTCLICK = enabled;
            case SELECT -> Sound.soundSELECT = enabled;
            case START -> Sound.soundSTART = enabled;
            case STOP -> Sound.soundSTOP = enabled;
            case INFO -> Sound.soundINFO = enabled;
            case WARN -> Sound.soundWARN = enabled;
            case ERROR -> Sound.soundERROR = enabled;
            case SUCCESS -> Sound.soundSUCCESS = enabled;
            case CREATE -> Sound.soundCREATE = enabled;
            case DELETE -> Sound.soundDELETE = enabled;
            case COPY -> Sound.soundCOPY = enabled;
            case PASTE -> Sound.soundPASTE = enabled;
            case UNDO -> Sound.soundUNDO = enabled;
            case REDO -> Sound.soundREDO = enabled;
            case SEND -> Sound.soundSEND = enabled;
            case RECEIVE -> Sound.soundRECEIVE = enabled;
            case RECEIVEERROR -> Sound.soundRECEIVEERROR = enabled;
            case SWITCHTAB -> Sound.soundSWITCHTAB = enabled;
            case CLOSETAB -> Sound.soundCLOSETAB = enabled;
            case TERMINAL -> Sound.soundTERMINAL = enabled;
            case FILEEXPLORER -> Sound.soundFILEEXPLORER = enabled;
            case FILEEDITOR -> Sound.soundFILEEDITOR = enabled;
            case SERVERMANAGER -> Sound.soundSERVERMANAGER = enabled;
            case PANEL -> Sound.soundPANEL = enabled;
            case SEARCH -> Sound.soundSEARCH = enabled;
            case SAVE -> Sound.soundSAVE = enabled;
            case SCREEN -> Sound.soundSCREEN = enabled;
        }
    }

    interface Storage {
        String read(String key);

        boolean write(String key, String value);

        boolean erase(String key);
    }

    private static final class NativeStorage implements Storage {
        @Override
        public String read(String key) {
            return readNative(key);
        }

        @Override
        public boolean write(String key, String value) {
            return writeNative(key, value);
        }

        @Override
        public boolean erase(String key) {
            return eraseNative(key);
        }
    }

    private void write(String key, String value) {
        if (!storage.write(key, value)) throw new IllegalStateException("Browser Storage Write Failed");
    }

    private void erase(String key) {
        if (!storage.erase(key)) throw new IllegalStateException("Browser Storage Removal Failed");
    }

    @JSBody(params = "key", script = "return window.localStorage.getItem(key);")
    private static native String readNative(String key);

    @JSBody(params = {"key", "value"}, script = "try { window.localStorage.setItem(key, value); return true; } catch (e) { return false; }")
    private static native boolean writeNative(String key, String value);

    @JSBody(params = "key", script = "try { window.localStorage.removeItem(key); return true; } catch (e) { return false; }")
    private static native boolean eraseNative(String key);
}
