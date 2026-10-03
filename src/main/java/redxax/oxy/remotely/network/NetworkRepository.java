package redxax.oxy.remotely.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NetworkRepository implements NetworkStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Path directory;

    public NetworkRepository(Path applicationDirectory) {
        if (applicationDirectory == null) {
            throw new IllegalArgumentException("Application directory is required");
        }
        this.directory = applicationDirectory.resolve("networks").toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<NetworkDefinition> loadAll() {
        return locked(() -> {
            List<NetworkDefinition> networks = new ArrayList<>();
            Map<String, Path> ids = new LinkedHashMap<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(this::isNetworkFile).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                    NetworkDefinition network = read(file);
                    Path duplicate = ids.putIfAbsent(network.networkId(), file);
                    if (duplicate != null) {
                        throw new NetworkPersistenceException("Duplicate network ID " + network.networkId() + " in " + duplicate + " and " + file);
                    }
                    networks.add(network);
                }
            } catch (IOException exception) {
                throw new NetworkPersistenceException("Failed to list network definitions in " + directory, exception);
            }
            return List.copyOf(networks);
        });
    }

    @Override
    public synchronized void save(NetworkDefinition network) {
        NetworkValidator.requireValid(network);
        locked(() -> {
            Path target = pathFor(network.networkId());
            if (Files.isRegularFile(target)) {
                NetworkDefinition persisted = read(target);
                if (network.revision() < persisted.revision()) {
                    throw new NetworkPersistenceException("A Newer Network Revision Is Already Saved");
                }
                if (network.revision() == persisted.revision() && !network.equals(persisted)) {
                    throw new NetworkPersistenceException("Network Changed In Another Remotely Session");
                }
                if (network.revision() > persisted.revision() + 1) {
                    throw new NetworkPersistenceException("Network Revision Is Missing Intermediate Changes");
                }
                if (network.equals(persisted)) {
                    return null;
                }
            }
            Path temporary = null;
            try {
                temporary = Files.createTempFile(directory, network.networkId() + ".", ".tmp");
                Files.writeString(temporary, GSON.toJson(network) + System.lineSeparator(), StandardCharsets.UTF_8);
                moveAtomically(temporary, target);
            } catch (IOException exception) {
                throw new NetworkPersistenceException("Failed to save network " + network.name(), exception);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                    }
                }
            }
            return null;
        });
    }

    @Override
    public synchronized void delete(NetworkDefinition network) {
        locked(() -> {
            Path target = pathFor(network.networkId());
            if (!Files.isRegularFile(target)) {
                return null;
            }
            NetworkDefinition persisted = read(target);
            if (!network.equals(persisted)) {
                throw new NetworkPersistenceException("Network Changed In Another Remotely Session");
            }
            try {
                Files.delete(target);
            } catch (IOException exception) {
                throw new NetworkPersistenceException("Failed to delete network " + network.networkId(), exception);
            }
            return null;
        });
    }

    public Path getDirectory() {
        return directory;
    }

    private NetworkDefinition read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
            if (schemaVersion > NetworkDefinition.CURRENT_SCHEMA_VERSION) {
                throw new NetworkPersistenceException("Unsupported network schema " + schemaVersion + " in " + file);
            }
            migratePathSync(root, schemaVersion);
            NetworkDefinition network = GSON.fromJson(root, NetworkDefinition.class).migrated();
            List<NetworkValidationIssue> errors = NetworkValidator.validate(network).stream().filter(NetworkValidationIssue::blocksPersistence)
                    .filter(issue -> !issue.code().equals("forwarding.secret.missing") || !network.forwarding().needsRepair()).toList();
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst().message());
            String expectedName = network.networkId() + ".json";
            if (!file.getFileName().toString().equals(expectedName)) {
                throw new NetworkPersistenceException("Network file name must match its ID: " + file);
            }
            return network;
        } catch (NetworkPersistenceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NetworkPersistenceException("Failed to read network definition " + file, exception);
        }
    }

    private Path pathFor(String networkId) {
        try {
            String normalizedId = UUID.fromString(networkId).toString();
            Path target = directory.resolve(normalizedId + ".json").toAbsolutePath().normalize();
            if (!target.getParent().equals(directory)) {
                throw new NetworkPersistenceException("Unsafe network path for " + networkId);
            }
            return target;
        } catch (IllegalArgumentException exception) {
            throw new NetworkPersistenceException("Invalid network ID " + networkId, exception);
        }
    }

    private void migratePathSync(JsonObject root, int schemaVersion) {
        if (schemaVersion >= 5 || !root.has("sharedDataPolicy") || !root.get("sharedDataPolicy").isJsonObject()) {
            return;
        }
        JsonObject policy = root.getAsJsonObject("sharedDataPolicy");
        if (!policy.has("syncPaths") || !policy.get("syncPaths").isJsonArray() || policy.getAsJsonArray("syncPaths").size() == 0 || policy.has("pathSyncs")) {
            return;
        }
        JsonObject sync = new JsonObject();
        sync.addProperty("id", "default");
        sync.addProperty("name", "Path Sync");
        JsonArray nodeIds = new JsonArray();
        if (root.has("members") && root.get("members").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("members")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject member = element.getAsJsonObject();
                boolean proxy = member.has("role") && "PROXY".equals(member.get("role").getAsString());
                boolean managed = !member.has("management") || "MANAGED".equals(member.get("management").getAsString());
                boolean reSyncEnabled = member.has("resyncEnabled") && member.get("resyncEnabled").getAsBoolean();
                if (!proxy && managed && reSyncEnabled && member.has("nodeId")) {
                    nodeIds.add(member.get("nodeId").getAsString());
                }
            }
        }
        boolean enabled = nodeIds.size() >= 2 && root.has("features") && root.getAsJsonObject("features").has(NetworkDefinition.FEATURE_PATH_SYNC) && root.getAsJsonObject("features").get(NetworkDefinition.FEATURE_PATH_SYNC).getAsBoolean();
        sync.addProperty("enabled", enabled);
        sync.add("nodeIds", nodeIds);
        sync.add("paths", policy.getAsJsonArray("syncPaths").deepCopy());
        sync.addProperty("conflictPolicy", policy.has("resourceConflictPolicy") ? policy.get("resourceConflictPolicy").getAsString() : NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS.name());
        sync.add("commands", new JsonArray());
        JsonArray pathSyncs = new JsonArray();
        pathSyncs.add(sync);
        policy.add("pathSyncs", pathSyncs);
    }

    private boolean isNetworkFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json");
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new NetworkPersistenceException("Failed to create network directory " + directory, exception);
        }
    }

    private <T> T locked(RepositoryOperation<T> operation) {
        ensureDirectory();
        Path lockPath = directory.resolve(".repository.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.run();
        } catch (NetworkPersistenceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NetworkPersistenceException("Failed to access network storage", exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    private interface RepositoryOperation<T> {
        T run() throws Exception;
    }
}
