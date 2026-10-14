package redxax.oxy.remotely.ui.server;

import restudio.rebase.backend.feature.ServerSettingsFeature;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.util.VersionUtil;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsPack;
import redxax.oxy.remotely.settings.server.ServerSettingsDocument;
import redxax.oxy.remotely.settings.server.ServerSettingsField;
import restudio.rescreen.ui.desktop.StartEntry;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ServerStartEntries {
    private record Section(String tab, String terms) {
    }

    private static final List<Section> GLOBAL = List.of(
            new Section("Appearance", "ui scale animations font desktop wallpaper windows taskbar cursor borders shadows"),
            new Section("Theme", "colors accents background text dark light theme presets"),
            new Section("Sounds", "audio volume mute notification sound"),
            new Section("Servers", "server defaults automatic refresh terminal console polling scan for servers use custom reverse proxy host user terminal code suggestions precreate quick server keep running auto restart mirror mods hidden servers"),
            new Section("Storage", "paths folders storage location disk cache"),
            new Section("ReProxy", "domain address ports tunnel forwarding network"),
            new Section("Discord", "rich presence activity privacy rpc"),
            new Section("Pack Content", "resource packs plugins mods server content"),
            new Section("Java", "jvm java executable runtime installations arguments memory ram"),
            new Section("LSP", "language servers completion diagnostics editor"),
            new Section("Minecraft Assets", "minecraft assets textures metadata download cache"),
            new Section("File Explorer", "files hidden files sorting quick access clipboard transfers editor"),
            new Section("Backups", "backup restore retention archive compression"),
            new Section("Presets", "server presets templates defaults"),
            new Section("Account", "account sign in login sharing collaboration tokens privacy"),
            new Section("About", "version updates license information"),
            new Section("Resources", "resources marketplace providers downloads plugins mods modpacks catalogs"),
            new Section("Logs", "logs logging debug diagnostics"),
            new Section("Development", "developer testing debugging"));
    private static final List<Section> SERVER = List.of(
            new Section("General", "name version software loader location modpack updates resources memory cpu disk"),
            new Section("Features", "keep running auto restart restart delay restart attempts auto start reproxy enable msmp enable backend api enable standard provider global priority files priority console priority players priority player data interval eula management resync telemetry"),
            new Section("Java", "jvm java runtime memory ram xmx xms arguments garbage collector executable"),
            new Section("Discord", "discord presence privacy rpc"),
            new Section("Backups", "backup restore retention compression archive"),
            new Section("Schedules", "schedule timer cron backup restart tasks"),
            new Section("Player Actions", "player commands actions moderation kick ban whitelist operator"),
            new Section("Extra Files", "configuration config files yaml json properties settings"),
            new Section("Storage Breakdown", "storage disk usage space folder size"));

    private static final Map<String, List<String>> GLOBAL_OPTIONS = Map.ofEntries(
            Map.entry("Appearance", List.of("Text Shadow", "Desktop Mode", "Cover Minecraft Panorama", "Enable Wallpaper", "Wallpaper Source", "Custom Image Path", "Random Panoramas", "Panorama Version", "Panorama Speed", "Panorama Blur", "Enable Animations", "Scroll Speed", "Console Scroll Speed", "Movement Speed", "Scale Speed", "Scale Factor", "Expand Speed", "Enable Custom Mouse", "Mouse Size", "Tail Size", "Tail Follow Speed")),
            Map.entry("Theme", List.of("Current Theme", "Control Style", "Theme Actions", "Preview", "Default Accent")),
            Map.entry("Sounds", List.of("Sound Effects", "Master Volume", "Pitch Variation")),
            Map.entry("Servers", List.of("Scan For Servers", "Use Custom Reverse Proxy", "Reverse Proxy Host", "Reverse Proxy User", "Terminal Code Suggestions", "Precreate Quick Server", "Keep Running", "Auto Restart", "Mirror Mods")),
            Map.entry("Storage", List.of("Instances Path", "Migrate Existing Instances")),
            Map.entry("Discord", List.of("Enabled", "Idle Timeout Minutes", "Details Override", "State Override")),
            Map.entry("Pack Content", List.of("Glyph Previews")),
            Map.entry("LSP", List.of("Enable LSP", "Managed Support", "Ask Before Download", "Prompt When Needed", "Show Status Details", "Show Problem Summary")),
            Map.entry("Minecraft Assets", List.of("Enable Minecraft Assets", "Managed Assets", "Auto Download", "Ask Before Download", "Version Target")),
            Map.entry("File Explorer", List.of("Use Custom File Explorer", "Default Sort", "Remember Last Sort", "Sort Per Tab", "Open On Double Click")),
            Map.entry("Resources", List.of("Preferred Provider", "Auto Identification", "Provider Fallback", "Remember Last Provider", "Default Sort", "Remember Last Sort", "Index Scope")),
            Map.entry("Development", List.of("Developer Mode", "Enable Debug Tools", "Obfuscate Sensitive Info")));

    private static final Map<String, List<String>> SERVER_OPTIONS = Map.ofEntries(
            Map.entry("General", List.of("Server Name", "Op Me", "Whitelist")),
            Map.entry("Features", List.of("Keep Running", "Auto Restart", "Restart Delay", "Restart Attempts", "Auto Start ReProxy", "Enable MSMP", "Enable Backend API", "Enable Standard Provider", "Global Priority", "Files Priority", "Console Priority", "Players Priority", "Player Data Interval")),
            Map.entry("Java", List.of("Remote Java Runtime", "Manual Remote Java Path", "Java Runtime", "Memory (MB)", "Additional JVM Arguments")),
            Map.entry("Management", List.of("Enable Management API", "Management Host", "Management Port", "Secret (40 chars)", "Enable TLS (SSL)", "Keystore Path", "Keystore Password")));

    private ServerStartEntries() {
    }

    static List<StartEntry> global(Screen screen, ServerScreenHost host) {
        if (!host.supports(ServerScreenHost.Action.HOST_SETTINGS)) return List.of();
        List<StartEntry> entries = new ArrayList<>();
        entries.add(new StartEntry("settings:global", "Global Settings", "Customize Remotely", StartEntry.Category.SETTINGS,
                Identifier.icon("settings.png"), () -> host.openSettings(screen), List.of("preferences", "options", "configuration")));
        for (Section section : GLOBAL) {
            entries.add(new StartEntry("settings:global:" + section.tab(), section.tab(), "Global Settings", StartEntry.Category.SETTINGS,
                    Identifier.icon("edit.png"), () -> host.openSettings(screen, section.tab(), section.tab()), List.of(section.terms())));
        }
        for (Section section : GLOBAL) {
            String tab = section.tab();
            for (String label : GLOBAL_OPTIONS.getOrDefault(tab, List.of())) entries.add(new StartEntry("settings:global:" + tab + ":" + label, label, "Global Settings / " + tab,
                    StartEntry.Category.SETTINGS, Identifier.icon("edit.png"), () -> host.openSettings(screen, tab, label), List.of(tab, label)));
        }
        for (String tab : host.extraGlobalSettingTabs()) {
            entries.add(new StartEntry("settings:global:" + tab, tab, "Global Settings", StartEntry.Category.SETTINGS,
                    Identifier.icon("edit.png"), () -> host.openSettings(screen, tab, tab), List.of(tab, "connections", "hosts", "panel", "ssh")));
        }
        return List.copyOf(entries);
    }

    static List<StartEntry> server(Screen screen, ServerScreenHost host, ServerModels.ClientServerView server, String key) {
        List<StartEntry> entries = new ArrayList<>();
        String name = host.identity(server).name();
        Object source = host.iconTarget(server);
        ServerSettingsFeature.Access access = host.configurationAccess(source);
        if (!host.serverActionAvailability(server, ServerScreenHost.Action.SERVER_CONFIGURATION).available()) return List.of();
        entries.add(new StartEntry(key + ":settings", name + " Settings", "Server Settings", StartEntry.Category.SETTINGS,
                Identifier.icon("edit.png"), () -> host.openServerConfiguration(screen, server), List.of(name, "configuration", "options")));
        if (access == ServerSettingsFeature.Access.PROVIDER) {
            entries.add(section(screen, host, server, key, name, new Section("Panel", "startup resources memory cpu disk name environment limits")));
            entries.add(section(screen, host, server, key, name, new Section("Storage Breakdown", "storage disk usage space folder size")));
            return List.copyOf(entries);
        }
        for (Section section : SERVER) entries.add(section(screen, host, server, key, name, section));
        if ("RESTUDIO".equalsIgnoreCase(host.identity(server).backendType())) {
            entries.add(section(screen, host, server, key, name, new Section("Software Settings", "startup environment update install executable")));
            entries.add(section(screen, host, server, key, name, new Section("Network", "ports addresses domain subdomain allocations")));
            entries.add(section(screen, host, server, key, name, new Section("Subusers", "sharing team users access permissions collaboration")));
        }
        if (host.supportsReProxy(source)) entries.add(section(screen, host, server, key, name, new Section("ReProxy", "domain ports addresses tunnel forwarding")));
        ServerConfigurationTarget target = host.configurationTarget(source);
        if (VersionUtil.isMSMPCompatible(target.version())) {
            entries.add(section(screen, host, server, key, name, new Section("Management", "management server enabled address rcon password port")));
            if (Boolean.parseBoolean(target.properties().get("management-server-enabled"))) {
                entries.add(section(screen, host, server, key, name, new Section("Live Settings", "game rules gamerules difficulty weather time whitelist players")));
            }
        }
        SERVER_OPTIONS.forEach((tab, labels) -> {
            if (tab.equals("Management") && !VersionUtil.isMSMPCompatible(target.version())) return;
            for (String label : labels) {
                boolean local = "LOCAL".equalsIgnoreCase(host.identity(server).backendType());
                if (tab.equals("Java") && ((label.contains("Remote") && local) || (label.equals("Java Runtime") && !local))) continue;
                entries.add(new StartEntry(key + ":option:" + tab + ":" + label, label, name + " / " + tab, StartEntry.Category.SETTINGS,
                        Identifier.icon("edit.png"), () -> host.openServerConfiguration(screen, server, tab, null, label), List.of(tab, label)));
            }
        });
        var tokens = host.settingsSoftwareTokens(source);
        for (ServerSettingsPack pack : ServerSettingsRegistry.getInstance().snapshotFor(server).packs()) {
            if (!pack.appliesTo(tokens)) continue;
            for (ServerSettingsDocument document : pack.documents()) {
                for (ServerSettingsField field : document.fields()) {
                    entries.add(new StartEntry(key + ":field:" + pack.id() + ":" + document.relativePath() + ":" + field.id(), field.name(),
                            name + " / " + field.tab(), StartEntry.Category.SETTINGS, Identifier.icon("edit.png"),
                            () -> host.openServerConfiguration(screen, server, field.tab(), null, field.name()),
                            List.of(name, field.key(), field.group(), field.description(), document.relativePath())));
                }
            }
        }
        return List.copyOf(entries);
    }

    private static StartEntry section(Screen screen, ServerScreenHost host, ServerModels.ClientServerView server, String key, String name, Section section) {
        return new StartEntry(key + ":settings:" + section.tab(), section.tab(), name + " Settings", StartEntry.Category.SETTINGS,
                Identifier.icon("edit.png"), () -> host.openServerConfiguration(screen, server, section.tab(), null, section.tab()), List.of(name, section.terms()));
    }
}
