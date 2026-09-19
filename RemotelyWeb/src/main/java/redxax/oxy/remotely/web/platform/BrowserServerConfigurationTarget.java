package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.ui.server.ServerConfigurationTarget;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDocumentTarget;
import restudio.rebase.settings.controllers.VersionSettingsTarget;
import restudio.rebase.settings.controllers.ModpackSettingsTarget;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.restudio.api.models.ServerModels;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Collection;
import java.util.Set;

public final class BrowserServerConfigurationTarget implements ServerConfigurationTarget, ServerSettingsDocumentTarget, VersionSettingsTarget, ModpackSettingsTarget {
    private final ServerModels.ClientServerView server;
    private final Map<String, String> credentials = new LinkedHashMap<>();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private Object modLoader;
    private String modLoaderVersion;
    private String backendType;
    private String name;
    private String version;
    private String software;
    private String build;
    private String state;
    private boolean linkedModpack;
    private String modpackProvider;
    private String modpackProjectId;
    private String modpackVersionId;
    private String modpackVersionNumber;
    private final Set<String> softwareCategories = new LinkedHashSet<>();
    private final Set<String> softwareCompatibility = new LinkedHashSet<>();
    private boolean propertiesLoaded;

    public BrowserServerConfigurationTarget(ServerModels.ClientServerView server) {
        this.server = server == null ? new ServerModels.ClientServerView() : server;
        Map<String, String> environment = this.server.environment == null ? Map.of() : this.server.environment;
        name = value(this.server.name, "New Server");
        version = value(environment.get("VERSION"), value(this.server.version, "latest"));
        software = value(environment.get("SOFTWARE"), value(this.server.software, value(this.server.loader, "PAPER")));
        build = value(environment.get("BUILD"), "latest");
        state = this.server.isInstalling ? "INSTALLING" : "STOPPED";
        backendType = configuredBackend(this.server);
        this.server.backendType = backendType;
        modpackProvider = environment.get("MODPACK_PROVIDER");
        modpackProjectId = environment.get("MODPACK_PROJECT_ID");
        modpackVersionId = environment.get("MODPACK_VERSION_ID");
        modpackVersionNumber = environment.get("MODPACK_VERSION_NUMBER");
        linkedModpack = this.server.linkedModpack || modpackProjectId != null && !modpackProjectId.isBlank();
        String id = id();
        if (!id.isBlank()) credentials.put("identifier", id);
    }

    private BrowserServerConfigurationTarget(BrowserServerConfigurationTarget source, String copyName) {
        server = copyView(source.server);
        credentials.putAll(source.credentials);
        properties.putAll(source.properties);
        modLoader = source.modLoader;
        modLoaderVersion = source.modLoaderVersion;
        backendType = source.backendType;
        name = copyName == null || copyName.isBlank() ? source.name : copyName;
        version = source.version;
        software = source.software;
        build = source.build;
        state = source.state;
        linkedModpack = source.linkedModpack;
        modpackProvider = source.modpackProvider;
        modpackProjectId = source.modpackProjectId;
        modpackVersionId = source.modpackVersionId;
        modpackVersionNumber = source.modpackVersionNumber;
        softwareCategories.addAll(source.softwareCategories);
        softwareCompatibility.addAll(source.softwareCompatibility);
        propertiesLoaded = source.propertiesLoaded;
    }

    public BrowserServerConfigurationTarget copy(String copyName) {
        return new BrowserServerConfigurationTarget(this, copyName);
    }

    public ServerModels.ClientServerView view() {
        return server;
    }

    public static BrowserServerConfigurationTarget create() {
        return new BrowserServerConfigurationTarget(null);
    }

    @Override
    public Object raw() {
        return this;
    }

    @Override
    public String id() {
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        return server.uuid == null ? "" : server.uuid;
    }

    @Override
    public String catalogServerId() {
        return id();
    }

    @Override
    public String minecraftVersion() {
        return version == null ? "" : version.trim();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void name(String value) {
        name = value == null ? "" : value.trim();
        server.name = name;
    }

    @Override
    public String backendType() {
        return backendType;
    }

    @Override
    public Map<String, String> backendCredentials() {
        return Map.copyOf(credentials);
    }

    @Override
    public void backend(String type, Map<String, String> values) {
        backendType = type == null || type.isBlank() ? "" : type.trim().toUpperCase(Locale.ROOT);
        server.backendType = backendType;
        credentials.clear();
        if (values != null) credentials.putAll(values);
        if (!id().isBlank()) credentials.putIfAbsent("identifier", id());
    }

    @Override
    public ModLoader modLoader() {
        if (modLoader instanceof ModLoader value) return value;
        String configured = modLoader == null ? server.loader : String.valueOf(modLoader);
        if (configured == null || configured.isBlank()) return ModLoader.VANILLA;
        try {
            return ModLoader.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return ModLoader.VANILLA;
        }
    }

    @Override
    public void modLoader(Object value) {
        modLoader = value;
        String loader = value instanceof ModLoader configured ? configured.name() : value == null ? "" : String.valueOf(value).trim();
        server.loader = loader.isBlank() ? null : loader;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public String software() {
        return software;
    }

    @Override
    public String build() {
        return build;
    }

    @Override
    public boolean linkedModpack() {
        return linkedModpack || environment("MODPACK_PROJECT_ID") != null;
    }

    @Override
    public Map<String, String> properties() {
        return Map.copyOf(properties);
    }

    @Override
    public void property(String key, String value) {
        if (key == null || key.isBlank()) return;
        properties.put(key, value == null ? "" : value);
    }

    @Override
    public void removeProperty(String key) {
        if (key != null) properties.remove(key);
    }

    @Override
    public void state(String value) {
        state = value == null ? "" : value;
    }

    @Override
    public void log(String message) {
    }

    public void versionValue(String value) {
        version = value == null ? "" : value.trim();
        server.version = version;
        ensureEnvironment();
        putEnvironment("VERSION", version);
    }

    public void softwareValue(String value) {
        software = value == null ? "" : value.trim();
        server.software = software;
        ensureEnvironment();
        putEnvironment("SOFTWARE", software);
    }

    public void buildValue(String value) {
        build = value == null ? "" : value.trim();
        ensureEnvironment();
        putEnvironment("BUILD", build);
    }

    public String stateValue() {
        return state;
    }

    @Override
    public void replaceProperties(Map<String, String> values) {
        properties.clear();
        if (values != null) properties.putAll(values);
        propertiesLoaded = true;
    }

    public boolean propertiesLoaded() {
        return propertiesLoaded;
    }

    @Override
    public Collection<String> softwareTokens() {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addTokenVariants(tokens, software);
        addTokenVariants(tokens, server.loader);
        if (server.environment != null) {
            addTokenVariants(tokens, server.environment.get("SOFTWARE"));
            addTokenVariants(tokens, server.environment.get("SERVER_SOFTWARE"));
            addTokenVariants(tokens, server.environment.get("TYPE"));
        }
        tokens.addAll(softwareCategories);
        tokens.addAll(softwareCompatibility);
        return tokens;
    }

    @Override public boolean server() { return true; }
    @Override public String versionId() { return version; }
    @Override public void versionId(String value) { versionValue(value); }
    @Override public void modLoader(ModLoader value) { modLoader((Object) value); }
    @Override public String modLoaderVersion() { return modLoaderVersion; }
    @Override public void modLoaderVersion(String value) { modLoaderVersion = value; }
    @Override public String serverSoftwareType() { return software; }
    @Override public void serverSoftwareType(String value) { softwareValue(value); }
    @Override public Collection<String> serverSoftwareCategories() { return Set.copyOf(softwareCategories); }
    @Override public Collection<String> serverSoftwareCompatibility() { return Set.copyOf(softwareCompatibility); }
    @Override public void serverSoftware(String type, Collection<String> categories, Collection<String> compatibility) {
        softwareValue(type);
        softwareCategories.clear();
        softwareCompatibility.clear();
        if (categories != null) softwareCategories.addAll(categories);
        if (compatibility != null) softwareCompatibility.addAll(compatibility);
    }
    @Override public String serverBuildNumber() { return build; }
    @Override public void serverBuildNumber(String value) { buildValue(value == null ? "latest" : value); }
    @Override public boolean remote() { return !"LOCAL".equalsIgnoreCase(backendType); }
    @Override public boolean replaceableModpack() { return true; }
    @Override public String importPackName() { return ""; }
    @Override public String importPackVersion() { return ""; }
    @Override public String modpackProvider() { return value(environment("MODPACK_PROVIDER"), modpackProvider); }
    @Override public void modpackProvider(String value) { modpackProvider = value; ensureEnvironment(); putEnvironment("MODPACK_PROVIDER", value); }
    @Override public String modpackProjectId() { return value(environment("MODPACK_PROJECT_ID"), modpackProjectId); }
    @Override public void modpackProjectId(String value) {
        modpackProjectId = value;
        linkedModpack = value != null && !value.isBlank();
        ensureEnvironment();
        putEnvironment("MODPACK_PROJECT_ID", value);
    }
    @Override public String modpackVersionId() { return value(environment("MODPACK_VERSION_ID"), modpackVersionId); }
    @Override public void modpackVersionId(String value) { modpackVersionId = value; ensureEnvironment(); putEnvironment("MODPACK_VERSION_ID", value); }
    @Override public String modpackVersionNumber() { return value(environment("MODPACK_VERSION_NUMBER"), modpackVersionNumber); }
    @Override public void modpackVersionNumber(String value) { modpackVersionNumber = value; ensureEnvironment(); putEnvironment("MODPACK_VERSION_NUMBER", value); }
    public void linkModpack(String provider, String projectId, String versionId, String versionNumber) {
        linkedModpack = projectId != null && !projectId.isBlank();
        modpackProvider = provider;
        modpackProjectId = projectId;
        modpackVersionId = versionId;
        modpackVersionNumber = versionNumber;
        ensureEnvironment();
        putEnvironment("MODPACK_PROVIDER", provider);
        putEnvironment("MODPACK_PROJECT_ID", projectId);
        putEnvironment("MODPACK_VERSION_ID", versionId);
        putEnvironment("MODPACK_VERSION_NUMBER", versionNumber);
    }
    @Override public void copyServerSoftwareFrom(ModpackSettingsTarget source) {
        versionId(source.versionId());
        modLoader(source.modLoader());
        modLoaderVersion(source.modLoaderVersion());
        serverSoftwareType(source instanceof VersionSettingsTarget versionTarget ? versionTarget.serverSoftwareType() : software);
    }
    @Override public void clearModpackLink() {
        linkedModpack = false;
        modpackProvider = null;
        modpackProjectId = null;
        modpackVersionId = null;
        modpackVersionNumber = null;
        if (server.environment != null) {
            server.environment.remove("MODPACK_PROVIDER");
            server.environment.remove("MODPACK_PROJECT_ID");
            server.environment.remove("MODPACK_VERSION_ID");
            server.environment.remove("MODPACK_VERSION_NUMBER");
        }
    }

    @Override
    public String property(String key) {
        return properties.get(key);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void addTokenVariants(Collection<String> tokens, String token) {
        if (token == null || token.isBlank()) return;
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        tokens.add(normalized);
        tokens.add(normalized.replace('_', '-'));
        tokens.add(normalized.replace('-', '_'));
        tokens.add(normalized.replace(' ', '-'));
    }

    private String environment(String key) {
        if (server.environment == null) return null;
        String value = server.environment.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private void putEnvironment(String key, String value) {
        if (value == null || value.isBlank()) server.environment.remove(key);
        else server.environment.put(key, value);
    }

    private void ensureEnvironment() {
        if (server.environment == null) server.environment = new LinkedHashMap<>();
    }

    private static ServerModels.ClientServerView copyView(ServerModels.ClientServerView source) {
        ServerModels.ClientServerView copy = new ServerModels.ClientServerView();
        copy.identifier = source.identifier;
        copy.uuid = source.uuid;
        copy.name = source.name;
        copy.description = source.description;
        copy.ip = source.ip;
        copy.port = source.port;
        copy.ipAlias = source.ipAlias;
        copy.subdomain = source.subdomain;
        copy.fullDomain = source.fullDomain;
        copy.nodeName = source.nodeName;
        copy.sftpIp = source.sftpIp;
        copy.sftpPort = source.sftpPort;
        copy.sftpUser = source.sftpUser;
        copy.invocation = source.invocation;
        copy.dockerImage = source.dockerImage;
        copy.isSuspended = source.isSuspended;
        copy.isInstalling = source.isInstalling;
        copy.linkedModpack = source.linkedModpack;
        copy.loader = source.loader;
        copy.version = source.version;
        copy.software = source.software;
        copy.backendType = source.backendType;
        copy.environment = source.environment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.environment);
        if (source.limits != null) {
            copy.limits = new ServerModels.Limits();
            copy.limits.memory = source.limits.memory;
            copy.limits.swap = source.limits.swap;
            copy.limits.disk = source.limits.disk;
            copy.limits.io = source.limits.io;
            copy.limits.cpu = source.limits.cpu;
            copy.limits.threads = source.limits.threads;
        }
        return copy;
    }

    private static String configuredBackend(ServerModels.ClientServerView server) {
        if (server == null) return "";
        String configured = value(server.backendType, null);
        if (configured == null && server.environment != null) {
            configured = firstEnvironment(server.environment, "BACKEND_TYPE", "BACKEND", "TYPE");
        }
        return configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstEnvironment(Map<String, String> environment, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                if (key.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
