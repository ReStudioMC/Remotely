package redxax.oxy.remotely.data.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.player.management.PlayerOperation;
import redxax.oxy.remotely.data.player.model.BanInfo;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class PlayerFiles {
    private static final int MAX_CONTENT_LENGTH = 4 * 1024 * 1024;
    private static final DateTimeFormatter BAN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneOffset.UTC);
    private final Map<String, Document> documents = new LinkedHashMap<>();
    private Snapshot snapshot = Snapshot.empty();

    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    public synchronized void clear() {
        snapshot = Snapshot.empty();
        documents.clear();
    }

    public synchronized void clearWhitelist() {
        snapshot = Snapshot.empty();
        documents.remove("whitelist.json");
    }

    public synchronized void properties(String content) {
        requireContent(content);
        if (content.equals(snapshot.properties())) return;
        Properties values = new Properties();
        try {
            values.load(new StringReader(content));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Server Properties Could Not Be Read", failure);
        }
        boolean enabled = Boolean.parseBoolean(values.getProperty("white-list", "false").trim());
        boolean changed = enabled != snapshot.enabled();
        snapshot = new Snapshot(content, changed ? null : snapshot.content(), enabled, changed ? Map.of() : snapshot.players());
    }

    public synchronized void members(String content) {
        requireContent(content);
        if (content.equals(snapshot.content())) return;
        accept("whitelist.json", true, content);
        JsonArray value = documents.get("whitelist.json").values();
        Map<UUID, String> players = new LinkedHashMap<>();
        for (JsonElement element : value) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Whitelist Entry Is Invalid");
            var entry = element.getAsJsonObject();
            if (!entry.has("uuid") || !entry.has("name")) throw new IllegalArgumentException("Whitelist Entry Is Invalid");
            players.put(UUID.fromString(entry.get("uuid").getAsString()), entry.get("name").getAsString());
        }
        snapshot = new Snapshot(snapshot.properties(), content, snapshot.enabled(), Map.copyOf(players));
    }

    public static boolean editable(PlayerOperation.Type type) {
        return type == PlayerOperation.Type.BAN || type == PlayerOperation.Type.UNBAN || type == PlayerOperation.Type.OP
                || type == PlayerOperation.Type.DEOP || type == PlayerOperation.Type.WHITELIST || type == PlayerOperation.Type.UNWHITELIST;
    }

    public static String path(PlayerOperation operation) {
        return switch (operation.type()) {
            case BAN, UNBAN -> operation.flag() ? "banned-ips.json" : "banned-players.json";
            case OP, DEOP -> "ops.json";
            case WHITELIST, UNWHITELIST -> "whitelist.json";
            default -> throw new IllegalArgumentException("Player Action Needs A Running Server");
        };
    }

    public synchronized boolean contains(PlayerOperation operation, UnifiedPlayer player) {
        Document document = documents.get(path(operation));
        if (document == null) return false;
        return "banned-ips.json".equals(path(operation)) ? document.addresses().containsKey(address(player.getIp().getValue()).toLowerCase(Locale.ROOT))
                : document.identities().containsKey(player.getUuid());
    }

    public synchronized void accept(String path, boolean exists, String content) {
        requireContent(content);
        Document previous = documents.get(path);
        if (previous != null && previous.exists() == exists && previous.content().equals(content)) return;
        JsonElement parsed = JsonParser.parseString(content);
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("Player File Must Contain An Array");
        JsonArray values = parsed.getAsJsonArray();
        Map<UUID, JsonObject> identities = new LinkedHashMap<>();
        Map<String, JsonObject> addresses = new LinkedHashMap<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("Player File Entry Is Invalid");
            JsonObject entry = value.getAsJsonObject();
            String id = text(entry, "uuid");
            if (!id.isBlank()) identities.put(UUID.fromString(id), entry);
            String ip = text(entry, "ip");
            if (!ip.isBlank()) addresses.put(ip.toLowerCase(Locale.ROOT), entry);
        }
        documents.put(path, new Document(exists, content, values, Map.copyOf(identities), Map.copyOf(addresses)));
    }

    public synchronized Edit edit(PlayerOperation operation, UnifiedPlayer player, boolean exists, String content) {
        String path = path(operation);
        accept(path, exists, content);
        JsonArray values = documents.get(path).values().deepCopy();
        boolean ip = "banned-ips.json".equals(path);
        String key = ip ? "ip" : "uuid";
        String identity = ip ? address(player.getIp().getValue()) : player.getUuid().toString();
        if (identity.isBlank()) throw new IllegalArgumentException("Player IP Is Unavailable");
        boolean add = operation.type() == PlayerOperation.Type.BAN || operation.type() == PlayerOperation.Type.OP
                || operation.type() == PlayerOperation.Type.WHITELIST;
        boolean present = false;
        for (int index = values.size() - 1; index >= 0; index--) {
            if (!identity.equalsIgnoreCase(text(values.get(index).getAsJsonObject(), key))) continue;
            present = true;
            if (!add) values.remove(index);
        }
        if (add && !present) {
            JsonObject entry = new JsonObject();
            entry.addProperty(key, identity);
            if (!ip) entry.addProperty("name", player.getName());
            if (operation.type() == PlayerOperation.Type.OP) {
                entry.addProperty("level", 4);
                entry.addProperty("bypassesPlayerLimit", false);
            } else if (operation.type() == PlayerOperation.Type.BAN) {
                entry.addProperty("created", BAN_DATE.format(Instant.now()));
                entry.addProperty("source", "Remotely");
                entry.addProperty("expires", "forever");
                entry.addProperty("reason", operation.text().isBlank() ? "Banned By Operator" : operation.text());
            }
            values.add(entry);
        }
        return new Edit(path, exists, content, add == present ? content : values.toString());
    }

    public synchronized void project(PlayerService service) {
        Map<UUID, JsonObject> ops = identities("ops.json");
        Map<UUID, JsonObject> bans = identities("banned-players.json");
        PlayerUpdateBatch batch = new PlayerUpdateBatch("player-files", 1_000);
        Map<UUID, String> names = new LinkedHashMap<>();
        names.putAll(snapshot.players());
        ops.forEach((id, entry) -> names.put(id, text(entry, "name")));
        bans.forEach((id, entry) -> names.put(id, text(entry, "name")));
        service.getRegistry().getAll().forEach(player -> names.putIfAbsent(player.getUuid(), player.getName()));
        names.forEach((id, name) -> {
            PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(id, name);
            if (documents.containsKey("ops.json")) update.setOp(ops.containsKey(id));
            JsonObject ban = bans.get(id);
            if (ban != null) {
                update.setBan(new BanInfo(id.toString(), name, text(ban, "created"), text(ban, "source"), text(ban, "expires"), text(ban, "reason")));
            } else {
                UnifiedPlayer player = service.getRegistry().get(id);
                JsonObject ipBan = player == null ? null : ipBan(player.getIp().getValue());
                if (ipBan != null) {
                    update.setBan(new BanInfo(id.toString(), name, text(ipBan, "created"), "IP Ban", text(ipBan, "expires"), text(ipBan, "reason")));
                } else if (documents.containsKey("banned-players.json") && documents.containsKey("banned-ips.json")) update.clearBan();
            }
            batch.add(update);
        });
        service.submitUpdate(batch);
    }

    private Map<UUID, JsonObject> identities(String path) {
        Document document = documents.get(path);
        return document == null ? Map.of() : document.identities();
    }

    private JsonObject ipBan(String ip) {
        Document document = documents.get("banned-ips.json");
        if (document == null || ip == null) return null;
        return document.addresses().get(address(ip).toLowerCase(Locale.ROOT));
    }

    public static String address(String ip) {
        if (ip == null) return "";
        String value = ip.trim().replaceFirst("^/", "");
        if (value.startsWith("[") && value.contains("]")) value = value.substring(1, value.indexOf(']'));
        else if (value.matches("[0-9.]+:[0-9]+")) value = value.substring(0, value.indexOf(':'));
        return value.matches("[0-9a-fA-F:.]+") ? value : "";
    }

    private static String text(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private record Document(boolean exists, String content, JsonArray values, Map<UUID, JsonObject> identities, Map<String, JsonObject> addresses) {
    }

    public record Edit(String path, boolean existed, String before, String after) {
    }

    private static void requireContent(String content) {
        if (content == null || content.length() > MAX_CONTENT_LENGTH) throw new IllegalArgumentException("Whitelist Data Is Unavailable");
    }

    public record Snapshot(String properties, String content, boolean enabled, Map<UUID, String> players) {
        private static Snapshot empty() {
            return new Snapshot(null, null, false, Map.of());
        }

        public boolean ready() {
            return properties != null && content != null;
        }
    }
}
