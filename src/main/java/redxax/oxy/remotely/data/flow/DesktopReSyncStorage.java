package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowDataTypeAdapter;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.DesktopRemotelyPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class DesktopReSyncStorage implements ReSyncStorage {
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
        .registerTypeAdapter(NodeDefinition.NodeCategory.class, new TypeAdapter<NodeDefinition.NodeCategory>() {
            @Override
            public void write(JsonWriter out, NodeDefinition.NodeCategory value) throws IOException {
                out.value(value != null ? value.getId() : null);
            }

            @Override
            public NodeDefinition.NodeCategory read(JsonReader in) throws IOException {
                return NodeDefinition.NodeCategory.fromString(in.nextString());
            }
        })
        .create();
    private static final Map<String, DesktopReSyncStorage> INTERN = new HashMap<>();
    private final Path path;

    private DesktopReSyncStorage(Path path) {
        this.path = path;
    }

    public static ReSyncStorage fromKey(Object key) {
        if (key instanceof ReSyncStorage value) {
            return value;
        }
        Path path;
        if (key instanceof Path value) {
            path = value.toAbsolutePath().normalize();
        } else {
            String name = String.valueOf(key).replace(':', '_');
            path = DesktopRemotelyPaths.appDir().resolve("data").resolve("flow").resolve(name + ".json");
        }
        synchronized (INTERN) {
            return INTERN.computeIfAbsent(path.toString(), ignored -> new DesktopReSyncStorage(path));
        }
    }

    Path path() {
        return path;
    }

    @Override
    public String read(String key) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void write(String key, String value) {
        Path temporary = null;
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, value == null ? "" : value);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignoredCleanup) {
                }
            }
        }
    }

    @Override
    public void remove(String key) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    @Override
    public <T> T readObject(String key, Class<T> type) {
        String json = read(key);
        if (json == null || json.isBlank() || type == null) return null;
        try {
            return GSON.fromJson(json, type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public void writeObject(String key, Object value) {
        write(key, value == null ? null : GSON.toJson(value));
    }
}
