package redxax.oxy.remotely.data.flow;

import restudio.rebase.Rebase;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class DesktopReSyncLocalInstances {
    private DesktopReSyncLocalInstances() {
    }

    public static void install() {
        ReSyncLocalInstances.access = new ReSyncLocalInstances.Access() {
            @Override
            public Object manager() {
                try {
                    return Rebase.get().getInstanceManager();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }

            @Override
            public void addListener(Runnable listener) {
                Object manager = manager();
                if (manager instanceof InstanceManager instanceManager && listener != null) {
                    instanceManager.addChangeListener(listener);
                }
            }

            @Override
            public void removeListener(Runnable listener) {
                Object manager = manager();
                if (manager instanceof InstanceManager instanceManager && listener != null) {
                    instanceManager.removeChangeListener(listener);
                }
            }

            @Override
            public Object find(String serverId, ClientServerView server) {
                if (serverId == null || serverId.isBlank()) {
                    return null;
                }
                try {
                    InstanceManager instanceManager = Rebase.get().getInstanceManager();
                    List<Instance> instances = new ArrayList<>(instanceManager.getLocalInstances());
                    for (var host : instanceManager.getRemoteHosts()) {
                        instances.addAll(instanceManager.getRemoteInstances(host));
                    }
                    for (Instance instance : instances) {
                        if (instance == null) {
                            continue;
                        }
                        if (serverId.equalsIgnoreCase(instance.getInstanceId())) {
                            return instance;
                        }
                        BackendConfig backendConfig = instance.getBackendConfig();
                        if (backendConfig != null && backendConfig.credentials != null) {
                            String identifier = backendConfig.credentials.get("identifier");
                            if (identifier != null && identifier.equals(serverId)) {
                                return instance;
                            }
                        }
                    }
                    if (server != null && server.name != null) {
                        for (Instance instance : instances) {
                            if (instance != null && server.name.equalsIgnoreCase(instance.getName())) {
                                return instance;
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                }
                return null;
            }

            @Override
            public String instanceId(Object instance) {
                return instance instanceof Instance value && value.getInstanceId() != null ? value.getInstanceId() : "";
            }

            @Override
            public String instanceName(Object instance) {
                return instance instanceof Instance value && value.getName() != null ? value.getName() : "";
            }

            @Override
            public Object backendConfig(Object instance) {
                return instance instanceof Instance value ? value.getBackendConfig() : null;
            }

            @Override
            public Map<String, String> credentials(Object backendConfig) {
                if (backendConfig instanceof BackendConfig config && config.credentials != null) {
                    return config.credentials;
                }
                return Map.of();
            }

            @Override
            public String backendType(Object backendConfig) {
                return backendConfig instanceof BackendConfig config && config.type != null ? config.type : "";
            }

            @Override
            public String backendHost(Object instance) {
                if (!(instance instanceof Instance value)) {
                    return "";
                }
                BackendConfig config = value.getBackendConfig();
                if (config == null || config.credentials == null) {
                    return "";
                }
                String host = config.credentials.get("host");
                return host == null ? "" : host;
            }

            @Override
            public String instancePath(Object instance) {
                return instance instanceof Instance value && value.getPath() != null ? value.getPath().toString() : "";
            }

            @Override
            public Object backend(Object instance) {
                return instance instanceof Instance value ? value.getBackend() : null;
            }

            @Override
            public boolean backendConnected(Object backend) {
                return backend instanceof ServerBackend value && value.isConnected();
            }

            @Override
            public void connectBackend(Object backend) {
                if (backend instanceof ServerBackend value) {
                    value.connect();
                }
            }

            @Override
            public Object fileSystem(Object backend) {
                return backend instanceof ServerBackend value ? value.getFileSystem() : null;
            }

            @Override
            public ReSyncLocalInstances.LocalProfile readLocalProfile(Object instance) {
                return readLocalReSyncProfile(instance);
            }

            @Override
            public ReSyncLocalInstances.LocalProfile readBackendProfile(Object instance, String instancePath, String backendHost) {
                return readBackendReSyncProfile(instance, instancePath, backendHost);
            }
        };
    }

    public static Boolean fileExists(Object fileSystem, Path path) throws Exception {
        if (!(fileSystem instanceof FileSystemProvider provider)) {
            return false;
        }
        return provider.exists(path).get(5, TimeUnit.SECONDS);
    }

    public static String readFile(Object fileSystem, Path path) throws Exception {
        if (!(fileSystem instanceof FileSystemProvider provider)) {
            return null;
        }
        return provider.read(path).get(10, TimeUnit.SECONDS);
    }

    static ReSyncLocalInstances.LocalProfile readLocalReSyncProfile(Object instance) {
        try {
            Path pluginsPath = Path.of(ReSyncLocalInstances.access.instancePath(instance)).resolve("plugins");
            Path configPath = resolveReSyncConfigPath(pluginsPath, path -> Files.isRegularFile(path), Files::readString);
            if (configPath == null || !Files.isRegularFile(configPath)) {
                return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncNotConfigured", false);
            }
            Path serverIdPath = configPath.resolveSibling("server-id").normalize();
            if (!serverIdPath.getParent().equals(configPath.getParent()) || !Files.isRegularFile(serverIdPath)) {
                return ReSyncLocalInstances.LocalProfile.missing("ReSyncServerIdMissing", true);
            }
            return ReSyncLocalInstances.LocalProfile.found(Files.readString(configPath), "127.0.0.1",
                Files.readString(serverIdPath));
        } catch (Exception error) {
            return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncConfigurationUnavailable", true);
        }
    }

    static ReSyncLocalInstances.LocalProfile readBackendReSyncProfile(Object instance, String instancePath, String backendHost) {
        try {
            Object backend = ReSyncLocalInstances.access.backend(instance);
            if (backend == null) {
                return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncConfigurationUnavailable", false);
            }
            if (!ReSyncLocalInstances.access.backendConnected(backend)) {
                ReSyncLocalInstances.access.connectBackend(backend);
            }
            Object fs = ReSyncLocalInstances.access.fileSystem(backend);
            if (fs == null) {
                return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncConfigurationUnavailable", false);
            }
            Path pluginsPath = Path.of(instancePath == null ? "" : instancePath).resolve("plugins");
            Path configPath = resolveReSyncConfigPath(pluginsPath,
                path -> Boolean.TRUE.equals(fileExists(fs, path)),
                path -> readFile(fs, path));
            if (configPath == null) {
                return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncNotConfigured", false);
            }
            if (!Boolean.TRUE.equals(fileExists(fs, configPath))) {
                return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncNotConfigured", false);
            }
            String content = readFile(fs, configPath);
            Path serverIdPath = configPath.resolveSibling("server-id").normalize();
            if (!serverIdPath.getParent().equals(configPath.getParent())
                || !Boolean.TRUE.equals(fileExists(fs, serverIdPath))) {
                return ReSyncLocalInstances.LocalProfile.missing("ReSyncServerIdMissing", true);
            }
            return ReSyncLocalInstances.LocalProfile.found(content, backendHost, readFile(fs, serverIdPath));
        } catch (IllegalStateException error) {
            return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncProfileResolutionTimedOut", false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncProfileResolutionTimedOut", false);
        } catch (Exception error) {
            return ReSyncLocalInstances.LocalProfile.unavailable("ReSyncConfigurationUnavailable", false);
        }
    }

    private static Path resolveReSyncConfigPath(Path pluginsPath, CheckedPathPredicate exists,
                                                CheckedPathReader reader) throws Exception {
        Path plugins = pluginsPath.toAbsolutePath().normalize();
        Path coordination = plugins.resolve(".resync-coordination").normalize();
        Path pointer = coordination.resolve("restore-control").resolve("active-root").normalize();
        if (exists.test(pointer)) {
            Path activeRoot = resolveActiveRoot(coordination, reader.read(pointer));
            Path activeConfig = activeRoot.resolve("config.properties").normalize();
            if (!activeConfig.startsWith(activeRoot) || !exists.test(activeConfig)) {
                throw new IllegalArgumentException("ReSync active configuration is unavailable");
            }
            return activeConfig;
        }
        Path legacyConfig = plugins.resolve("ReSync").resolve("config.properties").normalize();
        return exists.test(legacyConfig) ? legacyConfig : null;
    }

    private static Path resolveActiveRoot(Path coordinationRoot, String pointer) {
        if (pointer == null) {
            throw new IllegalArgumentException("ReSync active root pointer is unavailable");
        }
        String[] lines = pointer.strip().split("\\R", -1);
        if (lines.length != 3 || !"format=1".equals(lines[0]) || !lines[1].startsWith("root=")
            || !lines[2].startsWith("hash=")) {
            throw new IllegalArgumentException("ReSync active root pointer is invalid");
        }
        String encoded = lines[1].substring("root=".length());
        String relative = decodeCanonicalPath(encoded);
        String expectedHash = lines[2].substring("hash=".length());
        if (!expectedHash.matches("[0-9a-fA-F]{64}") || !sha256(relative).equalsIgnoreCase(expectedHash)) {
            throw new IllegalArgumentException("ReSync active root pointer hash is invalid");
        }
        Path relativePath = Path.of(relative);
        Path scope = coordinationRoot.toAbsolutePath().normalize();
        Path activeRoot = scope.resolve(relativePath).normalize();
        Path controlRoot = scope.resolve("restore-control").normalize();
        if (relativePath.isAbsolute() || !activeRoot.startsWith(scope) || activeRoot.startsWith(controlRoot)) {
            throw new IllegalArgumentException("ReSync active root pointer escapes its scope");
        }
        return activeRoot;
    }

    private static String decodeCanonicalPath(String encoded) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            if (!Base64.getUrlEncoder().withoutPadding()
                .encodeToString(decoded.getBytes(StandardCharsets.UTF_8))
                .equals(encoded)) {
                throw new IllegalArgumentException("ReSync active root pointer is not canonical");
            }
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw new IllegalArgumentException("ReSync active root pointer is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    private interface CheckedPathPredicate {
        boolean test(Path path) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedPathReader {
        String read(Path path) throws Exception;
    }
}
