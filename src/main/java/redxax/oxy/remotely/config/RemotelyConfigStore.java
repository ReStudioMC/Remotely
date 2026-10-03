package redxax.oxy.remotely.config;

import redxax.oxy.remotely.packcontent.GlyphPreviewMode;
import restudio.rebase.config.RebaseConfigStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public interface RemotelyConfigStore extends RebaseConfigStore {
    String RECENT_RESTUDIO_ITEMS_KEY = "remotely.recent.restudioItems";

    default String readPendingNetwork(String key) { throw new UnsupportedOperationException("Pending Network Storage Is Unavailable"); }
    default void writePendingNetwork(String key, String body) { throw new UnsupportedOperationException("Pending Network Storage Is Unavailable"); }
    default void removePendingNetwork(String key) { throw new UnsupportedOperationException("Pending Network Storage Is Unavailable"); }

    default boolean getWallpaper() { return bool("remotely.wallpaper", false); }
    default void setWallpaper(boolean value) { set("remotely.wallpaper", String.valueOf(value)); save(); apply(); }
    default boolean getCustomReverseProxy() { return bool("remotely.customReverseProxy", false); }
    default void setCustomReverseProxy(boolean value) { set("remotely.customReverseProxy", String.valueOf(value)); save(); apply(); }
    default String getProxyHost() { return get("remotely.proxyHost", "RedxAx.net"); }
    default void setProxyHost(String value) { set("remotely.proxyHost", value); save(); apply(); }
    default String getProxyUser() { return get("remotely.proxyUser", "tunnel"); }
    default void setProxyUser(String value) { set("remotely.proxyUser", value); save(); apply(); }
    default boolean isDev() { return bool("remotely.isDev", false); }
    default void setDev(boolean value) { set("remotely.isDev", String.valueOf(value)); save(); apply(); }
    default boolean getEnableDebugTools() { return bool("remotely.enableDebugTools", false); }
    default void setEnableDebugTools(boolean value) { set("remotely.enableDebugTools", String.valueOf(value)); save(); apply(); }
    default boolean getCollaborationColorOverrideEnabled() { return bool("remotely.collaborationColorOverride", false); }
    default void setCollaborationColorOverrideEnabled(boolean value) { set("remotely.collaborationColorOverride", String.valueOf(value)); save(); }
    default int getCollaborationColor() {
        try {
            return 0xFF000000 | parseUnsignedHex(get("remotely.collaborationColor", "4E8CFF")) & 0x00FFFFFF;
        } catch (RuntimeException ignored) {
            return 0xFF4E8CFF;
        }
    }
    default void setCollaborationColor(int value) { set("remotely.collaborationColor", String.format("%06X", value & 0x00FFFFFF)); save(); }
    default Integer getCollaborationColorOverride() { return getCollaborationColorOverrideEnabled() ? getCollaborationColor() : null; }
    default String getMainMenuStyle() { return get("remotely.mainMenuStyle", "Minimal"); }
    default void setMainMenuStyle(String value) { set("remotely.mainMenuStyle", value); save(); apply(); }
    default boolean getRedesignMainMenu() { return bool("remotely.redesignMainMenu", false); }
    default void setRedesignMainMenu(boolean value) { set("remotely.redesignMainMenu", String.valueOf(value)); save(); apply(); }
    default boolean getScanServers() { return bool("remotely.scanServers", true); }
    default void setScanServers(boolean value) { set("remotely.scanServers", String.valueOf(value)); save(); apply(); }
    default boolean getQuickServerPrecreate() { return bool("remotely.quickServer.precreate", false); }
    default void setQuickServerPrecreate(boolean value) { set("remotely.quickServer.precreate", String.valueOf(value)); save(); apply(); }
    default boolean getQuickServerKeepRunning() { return bool("remotely.quickServer.keepRunning", false); }
    default void setQuickServerKeepRunning(boolean value) { set("remotely.quickServer.keepRunning", String.valueOf(value)); save(); apply(); }
    default boolean getQuickServerAutoRestart() { return bool("remotely.quickServer.autoRestart", false); }
    default void setQuickServerAutoRestart(boolean value) { set("remotely.quickServer.autoRestart", String.valueOf(value)); save(); apply(); }
    default boolean getQuickServerMirrorMods() { return bool("remotely.quickServer.mirrorMods", true); }
    default void setQuickServerMirrorMods(boolean value) { set("remotely.quickServer.mirrorMods", String.valueOf(value)); save(); apply(); }
    default boolean getObfuscate() { return bool("remotely.showIp", true); }
    default void setObfuscate(boolean value) { set("remotely.showIp", String.valueOf(value)); save(); apply(); }
    default int getReSyncKeyCode() { return integer("remotely.resyncKeyCode", 71); }
    default void setReSyncKeyCode(int value) { set("remotely.resyncKeyCode", String.valueOf(value)); save(); apply(); }
    default int getReSyncKeyModifiers() { return integer("remotely.resyncKeyModifiers", 4); }
    default void setReSyncKeyModifiers(int value) { set("remotely.resyncKeyModifiers", String.valueOf(value)); save(); apply(); }
    default float getConsoleScrollSpeed() { return decimal("ui.consoleScrollSpeed", 7.0f); }
    default void setConsoleScrollSpeed(float value) { set("ui.consoleScrollSpeed", String.valueOf(value)); save(); apply(); }
    default boolean getTerminalCodeSuggestions() { return bool("ui.terminalCodeSuggestions", false); }
    default void setTerminalCodeSuggestions(boolean value) { set("ui.terminalCodeSuggestions", String.valueOf(value)); save(); apply(); }
    default GlyphPreviewMode getGlyphPreviewMode() { return GlyphPreviewMode.fromConfig(get("remotely.glyphPreviews", "Inline + Hover")); }
    default void setGlyphPreviewMode(GlyphPreviewMode value) { set("remotely.glyphPreviews", (value == null ? GlyphPreviewMode.INLINE_HOVER : value).displayName()); save(); apply(); }
    default List<String> getInstanceOrder(String context) { return values(get("remotely.order." + context, "")); }
    default void setInstanceOrder(String context, List<String> order) { set("remotely.order." + context, String.join(",", order)); save(); }
    List<RemotelyGroup> getInstanceGroups(String context);
    void setInstanceGroups(String context, List<RemotelyGroup> groups);
    default List<String> getHiddenRestudioServers() { return values(get("remotely.hidden.restudio", "")); }
    default void setHiddenRestudioServers(List<String> values) { set("remotely.hidden.restudio", String.join(",", values)); save(); }
    default void hideRestudioServer(String id) {
        List<String> hidden = getHiddenRestudioServers();
        if (!hidden.contains(id)) {
            hidden.add(id);
            setHiddenRestudioServers(hidden);
        }
    }
    default void unhideRestudioServer(String id) { List<String> hidden = getHiddenRestudioServers(); hidden.remove(id); setHiddenRestudioServers(hidden); }
    default List<String> getRecentFlowNodes() { return normalizedFlowNodeIds(values(get("remotely.flow.nodeRecents", "")), 24); }
    default void recordRecentFlowNode(String id) { recordRecentFlowNodes(List.of(id)); }
    default void recordRecentFlowNodes(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        List<String> recent = getRecentFlowNodes();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            recent.removeIf(id::equalsIgnoreCase);
            recent.addFirst(id);
        }
        set("remotely.flow.nodeRecents", String.join(",", normalizedFlowNodeIds(recent, 24)));
        save();
    }

    default List<RemotelyRecentItem> getRecentRestudioItems() {
        String encoded = get(RECENT_RESTUDIO_ITEMS_KEY, "");
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<RemotelyRecentItem> result = new ArrayList<>();
        for (String entry : encoded.split(",")) {
            RemotelyRecentItem item = decodeRecentItem(entry);
            if (item != null && item.valid()) {
                result.add(item);
            }
        }
        return List.copyOf(normalizeRecentItems(result));
    }

    default void setRecentRestudioItems(List<RemotelyRecentItem> items) {
        List<RemotelyRecentItem> normalized = normalizeRecentItems(items);
        set(RECENT_RESTUDIO_ITEMS_KEY, normalized.stream().map(RemotelyConfigStore::encodeRecentItem).reduce((left, right) -> left + "," + right).orElse(""));
        save();
    }

    default void recordRecentRestudioItem(RemotelyRecentItem item) {
        if (item == null || !item.valid()) {
            return;
        }
        List<RemotelyRecentItem> recent = new ArrayList<>();
        recent.add(item);
        recent.addAll(getRecentRestudioItems());
        setRecentRestudioItems(recent);
    }

    private static String encodeRecentItem(RemotelyRecentItem item) {
        return encodeRecentValue(item.kind().name()) + "~" + encodeRecentValue(item.serverId()) + "~"
                + encodeRecentValue(item.path()) + "~" + encodeRecentValue(item.name());
    }

    private static RemotelyRecentItem decodeRecentItem(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String[] parts = value.split("~", -1);
            if (parts.length != 4) {
                return null;
            }
            RemotelyRecentItem.Kind kind = RemotelyRecentItem.Kind.valueOf(decodeRecentValue(parts[0]));
            return new RemotelyRecentItem(kind, decodeRecentValue(parts[1]), decodeRecentValue(parts[2]), decodeRecentValue(parts[3]));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String encodeRecentValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeRecentValue(String value) {
        return value == null || value.isBlank() ? "" : new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int parseUnsignedHex(String value) {
        long parsed = Long.parseLong(value, 16);
        if (parsed < 0 || parsed > 0xFFFFFFFFL) {
            throw new NumberFormatException(value);
        }
        return (int) parsed;
    }

    private static List<RemotelyRecentItem> normalizeRecentItems(List<RemotelyRecentItem> items) {
        LinkedHashMap<String, RemotelyRecentItem> unique = new LinkedHashMap<>();
        if (items != null) {
            for (RemotelyRecentItem item : items) {
                if (item == null || !item.valid()) {
                    continue;
                }
                unique.putIfAbsent(item.key(), item);
                if (unique.size() >= 24) {
                    break;
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static List<String> values(String value) {
        return value == null || value.isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split(",")));
    }

    private static List<String> normalizedFlowNodeIds(List<String> ids, int maximum) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isBlank()) continue;
                normalized.add(id.trim());
                if (normalized.size() >= maximum) break;
            }
        }
        return new ArrayList<>(normalized);
    }
}
