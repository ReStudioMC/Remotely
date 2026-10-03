package redxax.oxy.remotely.demo;

import redxax.oxy.remotely.RemotelyServerApi.Player;
import redxax.oxy.remotely.ui.server.ServerConfigurationTarget;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class ReactorDemoPreview implements AutoCloseable {
    private final List<Player> players;
    private final List<Resource> resources;
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();
    private final Map<String, Async<String>> documents = new LinkedHashMap<>();
    private ServerConfigurationTarget configuration;
    private ServerModels.StartupSettings startup;
    private Async<ServerModels.StartupSettings> startupLoad;
    private int revision;
    private boolean closed;

    public ReactorDemoPreview(List<Player> players, List<Resource> resources) {
        this.players = List.copyOf(players);
        this.resources = List.copyOf(resources);
    }

    public List<Player> players() {
        requireOpen();
        return players;
    }

    public List<ServerModels.PteroFileObjectAttributes> files() {
        requireOpen();
        List<ServerModels.PteroFileObjectAttributes> files = new ArrayList<>();
        for (Resource resource : resources) {
            ServerModels.PteroFileObjectAttributes file = new ServerModels.PteroFileObjectAttributes();
            file.name = resource.name() + (enabled.getOrDefault(resource.name(), true) ? "" : ".disabled");
            file.isFile = true;
            file.size = resource.size();
            file.mimetype = "application/java-archive";
            file.sha1 = resource.sha1();
            file.provider = "Modrinth";
            file.projectId = resource.projectId();
            file.versionId = resource.versionId();
            file.version = resource.version();
            file.title = resource.title();
            file.iconUrl = resource.iconUrl();
            file.pageUrl = resource.pageUrl();
            files.add(file);
        }
        return List.copyOf(files);
    }

    public Resource resource(String path) {
        requireOpen();
        String value = normalized(path);
        return resources.stream().filter(resource -> value.equals("plugins/" + resource.name())
                || value.equals("plugins/" + resource.name() + ".disabled")).findFirst().orElse(null);
    }

    public void toggle(String path, boolean value) {
        Resource resource = resource(path);
        if (resource == null) throw new IllegalArgumentException("Sample Plugin Was Not Found");
        enabled.put(resource.name(), value);
    }

    public ServerConfigurationTarget configuration(Supplier<ServerConfigurationTarget> create) {
        requireOpen();
        if (configuration == null) configuration = Objects.requireNonNull(create.get());
        return configuration;
    }

    public void configuration(ServerConfigurationTarget value) {
        requireOpen();
        configuration = Objects.requireNonNull(value);
    }

    public Async<String> read(String path, Supplier<Async<String>> fetch) {
        requireOpen();
        String key = normalized(path);
        Async<String> value = documents.get(key);
        if (value == null) {
            value = fetch.get();
            documents.put(key, value);
            Async<String> admitted = value;
            value.whenComplete((content, failure) -> {
                if (failure != null) documents.remove(key, admitted);
            });
        }
        return value.thenApply(content -> {
            requireOpen();
            return content;
        });
    }

    public void write(String path, String content) {
        requireOpen();
        documents.put(normalized(path), Async.completed(content));
    }

    public Async<ServerModels.StartupSettings> startup(Supplier<Async<ServerModels.ClientServerView>> fetch) {
        requireOpen();
        if (startup != null) return Async.completed(startup);
        if (startupLoad != null) return startupLoad;
        Async<ServerModels.StartupSettings> load = fetch.get().thenApply(server -> {
            requireOpen();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("VERSION", Objects.requireNonNullElse(server.version, "latest"));
            values.put("SOFTWARE", Objects.requireNonNullElse(server.software, "PAPER"));
            values.put("BUILD", "latest");
            values.put("MAXIMUM_MEMORY", "3072");
            values.put("MAXIMUM_RAM", "65");
            startup = new ServerModels.StartupSettings("preview-" + revision, values);
            return startup;
        });
        startupLoad = load;
        load.whenComplete((value, failure) -> {
            if (failure != null && startupLoad == load) startupLoad = null;
        });
        return load;
    }

    public void startup(String expectedRevision, Map<String, String> values) {
        requireOpen();
        if (startup == null || !startup.revision().equals(expectedRevision)) {
            throw new IllegalStateException("Configuration Preview Changed. Reopen It And Try Again");
        }
        startup = new ServerModels.StartupSettings("preview-" + ++revision, values);
    }

    @Override
    public void close() {
        closed = true;
        documents.clear();
        enabled.clear();
        configuration = null;
        startup = null;
        startupLoad = null;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Demo Session Ended");
    }

    private static String normalized(String path) {
        String value = Objects.requireNonNullElse(path, "").replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    public record Resource(String name, long size, String sha1, String projectId, String versionId, String version,
                           String title, String iconUrl, String pageUrl) {
    }
}
