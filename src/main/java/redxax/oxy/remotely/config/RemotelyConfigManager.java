package redxax.oxy.remotely.config;

import restudio.rescreen.ui.desktop.DesktopGroup;
import restudio.rescreen.ui.desktop.ConfigGroupStore;
import restudio.rescreen.ui.desktop.GroupStore;

import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.packcontent.GlyphPreviewMode;
import restudio.rebase.config.DesktopRebaseConfigManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static restudio.rescreen.config.Config.consoleScrollSpeed;

public class RemotelyConfigManager extends DesktopRebaseConfigManager implements RemotelyConfigStore {
    private final GroupStore groups;

    public RemotelyConfigManager(Path applicationDir) {
        super(applicationDir);
        groups = new ConfigGroupStore(this, "remotely.desktopGroups");
        if (getInstancesDir().equals(DesktopRemotelyPaths.legacyAppDir())) {
            setInstancesDir(DesktopRemotelyPaths.instancesDir());
        }
        properties.remove("remotely.background");
        migrateReSyncKeybind();
    }

    @Override
    public void apply() {
        super.apply();
        Config.wallpaper = getWallpaper();
        Config.customReverseProxy = getCustomReverseProxy();
        Config.proxyHost = getProxyHost();
        Config.proxyUser = getProxyUser();
        Config.isDev = isDev();
        Config.enableDebugTools = getEnableDebugTools();
        Config.mainMenuStyle = getMainMenuStyle();
        Config.redesignMainMenu = getRedesignMainMenu();
        Config.scanServers = getScanServers();
        Config.quickServerPrecreate = getQuickServerPrecreate();
        Config.quickServerKeepRunning = getQuickServerKeepRunning();
        Config.quickServerAutoRestart = getQuickServerAutoRestart();
        Config.quickServerMirrorMods = getQuickServerMirrorMods();
        Config.obfuscate = getObfuscate();
        Config.resyncKeyCode = getReSyncKeyCode();
        Config.resyncKeyModifiers = getReSyncKeyModifiers();
        consoleScrollSpeed = getConsoleScrollSpeed();
    }

    public boolean getWallpaper() { return Boolean.parseBoolean(properties.getProperty("remotely.wallpaper", "false")); }
    public void setWallpaper(boolean value) { properties.setProperty("remotely.wallpaper", String.valueOf(value)); save(); apply(); }

    public boolean getCustomReverseProxy() { return Boolean.parseBoolean(properties.getProperty("remotely.customReverseProxy", "false")); }
    public void setCustomReverseProxy(boolean value) { properties.setProperty("remotely.customReverseProxy", String.valueOf(value)); save(); apply(); }

    public String getProxyHost() { return properties.getProperty("remotely.proxyHost", "RedxAx.net"); }
    public void setProxyHost(String value) { properties.setProperty("remotely.proxyHost", value); save(); apply(); }

    public String getProxyUser() { return properties.getProperty("remotely.proxyUser", "tunnel"); }
    public void setProxyUser(String value) { properties.setProperty("remotely.proxyUser", value); save(); apply(); }

    public boolean isDev() { return Boolean.parseBoolean(properties.getProperty("remotely.isDev", "false")); }
    public void setDev(boolean value) { properties.setProperty("remotely.isDev", String.valueOf(value)); save(); apply(); }

    public boolean getEnableDebugTools() { return Boolean.parseBoolean(properties.getProperty("remotely.enableDebugTools", "false")); }
    public void setEnableDebugTools(boolean value) { properties.setProperty("remotely.enableDebugTools", String.valueOf(value)); save(); apply(); }

    public boolean getCollaborationColorOverrideEnabled() { return Boolean.parseBoolean(properties.getProperty("remotely.collaborationColorOverride", "false")); }
    public void setCollaborationColorOverrideEnabled(boolean value) { properties.setProperty("remotely.collaborationColorOverride", String.valueOf(value)); save(); }

    public int getCollaborationColor() {
        try {
            return 0xFF000000 | Integer.parseUnsignedInt(properties.getProperty("remotely.collaborationColor", "4E8CFF"), 16) & 0x00FFFFFF;
        } catch (NumberFormatException ignored) {
            return 0xFF4E8CFF;
        }
    }

    public void setCollaborationColor(int value) {
        properties.setProperty("remotely.collaborationColor", String.format("%06X", value & 0x00FFFFFF));
        save();
    }

    public Integer getCollaborationColorOverride() {
        return getCollaborationColorOverrideEnabled() ? getCollaborationColor() : null;
    }

    public String getMainMenuStyle() { return properties.getProperty("remotely.mainMenuStyle", "Minimal"); }
    public void setMainMenuStyle(String value) { properties.setProperty("remotely.mainMenuStyle", value); save(); apply(); }

    public boolean getRedesignMainMenu() { return Boolean.parseBoolean(properties.getProperty("remotely.redesignMainMenu", "false")); }
    public void setRedesignMainMenu(boolean value) { properties.setProperty("remotely.redesignMainMenu", String.valueOf(value)); save(); apply(); }

    public boolean getScanServers() { return Boolean.parseBoolean(properties.getProperty("remotely.scanServers", "true")); }
    public void setScanServers(boolean value) { properties.setProperty("remotely.scanServers", String.valueOf(value)); save(); apply(); }

    public boolean getQuickServerPrecreate() { return Boolean.parseBoolean(properties.getProperty("remotely.quickServer.precreate", "false")); }
    public void setQuickServerPrecreate(boolean value) { properties.setProperty("remotely.quickServer.precreate", String.valueOf(value)); save(); apply(); }

    public boolean getQuickServerKeepRunning() { return Boolean.parseBoolean(properties.getProperty("remotely.quickServer.keepRunning", "false")); }
    public void setQuickServerKeepRunning(boolean value) { properties.setProperty("remotely.quickServer.keepRunning", String.valueOf(value)); save(); apply(); }

    public boolean getQuickServerAutoRestart() { return Boolean.parseBoolean(properties.getProperty("remotely.quickServer.autoRestart", "false")); }
    public void setQuickServerAutoRestart(boolean value) { properties.setProperty("remotely.quickServer.autoRestart", String.valueOf(value)); save(); apply(); }

    public boolean getQuickServerMirrorMods() { return Boolean.parseBoolean(properties.getProperty("remotely.quickServer.mirrorMods", "true")); }
    public void setQuickServerMirrorMods(boolean value) { properties.setProperty("remotely.quickServer.mirrorMods", String.valueOf(value)); save(); apply(); }

    public boolean getObfuscate() { return Boolean.parseBoolean(properties.getProperty("remotely.showIp", "true")); }
    public void setObfuscate(boolean value) { properties.setProperty("remotely.showIp", String.valueOf(value)); save(); apply(); }

    public int getReSyncKeyCode() { return Integer.parseInt(properties.getProperty("remotely.resyncKeyCode", "71")); }
    public void setReSyncKeyCode(int value) { properties.setProperty("remotely.resyncKeyCode", String.valueOf(value)); save(); apply(); }

    public int getReSyncKeyModifiers() { return Integer.parseInt(properties.getProperty("remotely.resyncKeyModifiers", "4")); }
    public void setReSyncKeyModifiers(int value) { properties.setProperty("remotely.resyncKeyModifiers", String.valueOf(value)); save(); apply(); }

    private void migrateReSyncKeybind() {
        String keyCode = properties.getProperty("remotely.resyncKeyCode", "71");
        String modifiers = properties.getProperty("remotely.resyncKeyModifiers", "4");
        if (!"82".equals(keyCode)) {
            return;
        }
        if (!"4".equals(modifiers) && !"6".equals(modifiers)) {
            return;
        }
        properties.setProperty("remotely.resyncKeyCode", "71");
        properties.setProperty("remotely.resyncKeyModifiers", "4");
        save();
    }

    public float getConsoleScrollSpeed() { return Float.parseFloat(properties.getProperty("ui.consoleScrollSpeed", "7.0")); }
    public void setConsoleScrollSpeed(float speed) { properties.setProperty("ui.consoleScrollSpeed", String.valueOf(speed)); save(); apply(); }

    public GlyphPreviewMode getGlyphPreviewMode() { return GlyphPreviewMode.fromConfig(properties.getProperty("remotely.glyphPreviews", "Inline + Hover")); }
    public void setGlyphPreviewMode(GlyphPreviewMode mode) { properties.setProperty("remotely.glyphPreviews", (mode != null ? mode : GlyphPreviewMode.INLINE_HOVER).displayName()); save(); apply(); }

    public List<String> getInstanceOrder(String context) {
        String val = properties.getProperty("remotely.order." + context, "");
        if (val.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(val.split(",")));
    }

    public void setInstanceOrder(String context, List<String> order) {
        properties.setProperty("remotely.order." + context, String.join(",", order));
        save();
    }

    public List<RemotelyGroup> getInstanceGroups(String context) {
        return groups.load(context).stream()
                .map(group -> new RemotelyGroup(group.id(), group.name(), group.members()))
                .toList();
    }

    public void setInstanceGroups(String context, List<RemotelyGroup> groups) {
        List<DesktopGroup> desktopGroups = groups == null ? List.of() : groups.stream()
                .filter(group -> group != null)
                .map(group -> new DesktopGroup(group.id(), group.name(), group.members()))
                .toList();
        this.groups.save(context, desktopGroups);
        save();
    }

    public List<String> getHiddenRestudioServers() {
        String val = properties.getProperty("remotely.hidden.restudio", "");
        if (val.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(val.split(",")));
    }

    public void setHiddenRestudioServers(List<String> serverIds) {
        properties.setProperty("remotely.hidden.restudio", String.join(",", serverIds));
        save();
    }

    public void hideRestudioServer(String serverId) {
        List<String> hidden = getHiddenRestudioServers();
        if (!hidden.contains(serverId)) {
            hidden.add(serverId);
            setHiddenRestudioServers(hidden);
        }
    }

    public void unhideRestudioServer(String serverId) {
        List<String> hidden = getHiddenRestudioServers();
        hidden.remove(serverId);
        setHiddenRestudioServers(hidden);
    }

    public List<String> getRecentFlowNodes() {
        return flowNodeIds("remotely.flow.nodeRecents", 24);
    }

    public void recordRecentFlowNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        recordRecentFlowNodes(List.of(nodeId));
    }

    public void recordRecentFlowNodes(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return;
        }
        List<String> recent = getRecentFlowNodes();
        for (String nodeId : nodeIds) {
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            recent.removeIf(nodeId::equalsIgnoreCase);
            recent.addFirst(nodeId);
        }
        properties.setProperty("remotely.flow.nodeRecents", String.join(",", normalizedFlowNodeIds(recent, 24)));
        save();
    }

    private List<String> flowNodeIds(String key, int maximum) {
        String value = properties.getProperty(key, "");
        return normalizedFlowNodeIds(value.isBlank() ? List.of() : Arrays.asList(value.split(",")), maximum);
    }

    private List<String> normalizedFlowNodeIds(List<String> nodeIds, int maximum) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (nodeIds != null) {
            for (String nodeId : nodeIds) {
                if (nodeId == null || nodeId.isBlank()) {
                    continue;
                }
                normalized.add(nodeId.trim());
                if (normalized.size() >= maximum) {
                    break;
                }
            }
        }
        return new ArrayList<>(normalized);
    }

}
