package redxax.oxy.remotely.servers.reproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.network.ConfigurationFormat;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapter;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;
import redxax.oxy.remotely.settings.server.BrowserSafeYaml;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rescreen.platform.Sha256;
import restudio.rescreen.util.JsonTreeParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReProxyIntegrations {
    public enum Format {
        PROPERTIES, YAML, TOML, HOCON
    }

    public record When(List<String> present, List<String> absent, List<String> anyPresent, boolean negate) {
        public When {
            present = List.copyOf(present);
            absent = List.copyOf(absent);
            anyPresent = List.copyOf(anyPresent);
            for (List<String> keys : List.of(present, absent, anyPresent)) {
                if (keys.size() > 32 || new HashSet<>(keys).size() != keys.size()) throw invalid("Invalid Integration Conditions");
                for (String key : keys) key(key, Format.PROPERTIES);
            }
            if (present.stream().anyMatch(absent::contains)) throw invalid("Conflicting Integration Conditions");
        }

        private boolean matches(NetworkConfigurationAdapter.Reader reader) {
            boolean match = present.stream().allMatch(reader::contains) && absent.stream().noneMatch(reader::contains)
                    && (anyPresent.isEmpty() || anyPresent.stream().anyMatch(reader::contains));
            return negate != match;
        }
    }

    public record Edit(String key, String value, boolean quote, When when) {
        public Edit {
            ReProxyIntegrations.key(key, Format.PROPERTIES);
            template(value);
            Objects.requireNonNull(when, "when");
        }
    }

    public record Integration(String id, String name, String protocol, int defaultPort, boolean requiresStopped, List<String> paths,
                              List<String> aliases, Map<String, List<String>> projects, List<String> projectAliases, Format format, String portKey,
                              List<Integer> serverPortValues, String clonePortKey, String publicAddress, List<Edit> edits, Set<String> managedKeys, List<String> reloadCommands) {
        public Integration {
            paths = List.copyOf(paths);
            aliases = List.copyOf(aliases);
            Map<String, List<String>> copied = new LinkedHashMap<>();
            projects.forEach((provider, ids) -> copied.put(provider, List.copyOf(ids)));
            projects = Map.copyOf(copied);
            projectAliases = List.copyOf(projectAliases);
            serverPortValues = List.copyOf(serverPortValues);
            edits = List.copyOf(edits);
            managedKeys = Set.copyOf(managedKeys);
            reloadCommands = List.copyOf(reloadCommands);
            if (reloadCommands.size() > 8) throw invalid("Too Many Plugin Reload Commands");
            reloadCommands.forEach(ReProxyIntegrations::command);
            if (id == null || !id.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid("Invalid Integration Id");
            validText(name, 128);
            if (normalize(name).isEmpty()) throw invalid("Invalid Integration Name");
            if (!"TCP".equals(protocol) && !"UDP".equals(protocol)) throw invalid("Invalid Integration Protocol");
            if (paths.isEmpty() || paths.size() > 64 || new HashSet<>(paths).size() != paths.size()) throw invalid("Invalid Integration Configuration Paths");
            for (String path : paths) {
                relativePath(path);
                if (List.of(path.split("/")).stream().anyMatch(part -> part.equalsIgnoreCase(".remotely"))) throw invalid("Integration Configuration Path Is Reserved");
            }
            if (aliases.size() > 64) throw invalid("Too Many Integration Aliases");
            Set<String> normalized = new HashSet<>();
            for (String alias : aliases) {
                validText(alias, 128);
                if (normalize(alias).isEmpty() || !normalized.add(normalize(alias))) throw invalid("Duplicate Integration Alias");
            }
            if (projects.size() > 16) throw invalid("Too Many Integration Providers");
            if (projectAliases.size() > 16 || new HashSet<>(projectAliases).size() != projectAliases.size()) throw invalid("Invalid Integration Alias Providers");
            for (String provider : projectAliases) if (!provider.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid("Invalid Integration Provider");
            for (Map.Entry<String, List<String>> provider : projects.entrySet()) {
                if (!provider.getKey().matches("[a-z][a-z0-9_-]{0,63}") || provider.getValue().size() > 64) throw invalid("Invalid Integration Provider");
                Set<String> matches = new HashSet<>();
                for (String project : provider.getValue()) {
                    if (!project.matches("[a-z0-9_-]{1,128}") || !matches.add(project)) throw invalid("Invalid Integration Project");
                }
            }
            Objects.requireNonNull(format, "format");
            key(portKey, format);
            if (serverPortValues.size() > 2 || new HashSet<>(serverPortValues).size() != serverPortValues.size()
                    || serverPortValues.stream().anyMatch(number -> number != -1 && number != 0)) throw invalid("Invalid Server Port Value");
            if (defaultPort < -1 || defaultPort > 65535 || defaultPort < 1 && !serverPortValues.contains(defaultPort)) throw invalid("Invalid Default Port");
            if (!clonePortKey.isEmpty()) key(clonePortKey, format);
            template(publicAddress);
            if (edits.isEmpty() || edits.size() > 64) throw invalid("Invalid Integration Edits");
            Set<String> declared = new HashSet<>();
            for (Edit edit : edits) {
                key(edit.key(), format);
                for (List<String> keys : List.of(edit.when().present(), edit.when().absent(), edit.when().anyPresent())) {
                    for (String key : keys) key(key, format);
                }
                declared.add(edit.key());
            }
            if (!declared.contains(portKey) || !declared.equals(managedKeys)) throw invalid("Integration Must Update Its Declared Settings");
        }
    }

    public record Value(boolean present, String text) {
        public Value {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    public record Change(String path, String expectedContent, String content, Map<String, Value> original, Map<String, Value> desired,
                         String publicAddress, int targetPort) {
        public Change {
            original = Map.copyOf(original);
            desired = Map.copyOf(desired);
        }
    }

    public record Prepared(Integration integration, String path, String expectedContent, int targetPort) {
        public boolean requiresStopped() {
            return integration.requiresStopped();
        }

        public void requireEditable(boolean running) {
            if (running && requiresStopped()) throw new IllegalStateException("Stop The Server Before Connecting " + integration.name());
        }

        public Change connect(String publicHost, int publicPort, int allocatedTargetPort) {
            Integration policy = known(integration);
            host(publicHost);
            port(publicPort);
            int target = allocatedTargetPort > 0 ? allocatedTargetPort : targetPort;
            port(target);
            NetworkConfigurationAdapter adapter = adapter(policy);
            NetworkConfigurationAdapter.Reader reader = adapter.prepare(expectedContent);
            Map<String, String> values = new LinkedHashMap<>();
            for (Edit edit : policy.edits()) {
                if (!edit.when().matches(reader)) continue;
                String value = render(edit.value(), publicHost, publicPort, target);
                values.put(edit.key(), edit.quote() ? quote(value) : value);
            }
            Map<String, Value> original = new LinkedHashMap<>();
            String changed = expectedContent;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                original.put(entry.getKey(), new Value(reader.contains(entry.getKey()), reader.read(entry.getKey())));
                changed = adapter.apply(changed, entry.getKey(), entry.getValue());
            }
            NetworkConfigurationAdapter.Reader applied = adapter.prepare(changed);
            Set<String> selected = new HashSet<>();
            for (Edit edit : policy.edits()) if (edit.when().matches(applied)) selected.add(edit.key());
            if (!selected.equals(values.keySet())) throw invalid("Integration Conditions Must Remain Stable After Updating Settings");
            Map<String, Value> desired = new LinkedHashMap<>();
            for (String key : values.keySet()) desired.put(key, new Value(applied.contains(key), applied.read(key)));
            return new Change(path, expectedContent, changed, original, desired, render(policy.publicAddress(), publicHost, publicPort, target), target);
        }
    }

    private record Catalog(String source, String stamp, List<Integration> integrations, Map<String, Integration> ids,
                           Map<String, Integration> names, Map<String, Integration> projects) {
    }

    private static final When ALWAYS = new When(List.of(), List.of(), List.of(), false);
    private static volatile Catalog current = new Catalog("", "", List.of(), Map.of(), Map.of(), Map.of());
    private static final NetworkConfigurationAdapters ADAPTERS = new NetworkConfigurationAdapters(BrowserSafeYaml::parse);
    private static final NetworkConfigurationAdapter HOCON = new ReProxyHocon();

    private ReProxyIntegrations() {
    }

    public static synchronized void load(String json) {
        if (json != null && json.equals(current.source())) return;
        bounded(json);
        JsonObject root = object(JsonTreeParser.parse(json));
        fields(root, Set.of("version", "integrations"));
        if (integer(root.get("version")) != 1) throw invalid("Unsupported Integration Version");
        JsonArray items = array(root.get("integrations"), 128);
        List<Integration> integrations = new ArrayList<>();
        Map<String, Integration> ids = new LinkedHashMap<>();
        Map<String, Integration> names = new LinkedHashMap<>();
        Map<String, Integration> projects = new LinkedHashMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        for (JsonElement item : items) {
            Integration integration = definition(object(item));
            if (ids.putIfAbsent(integration.id(), integration) != null) throw invalid("Duplicate Integration Id");
            named(names, normalize(integration.name()), integration);
            for (String alias : integration.aliases()) named(names, normalize(alias), integration);
            for (Map.Entry<String, List<String>> provider : integration.projects().entrySet()) {
                for (String project : provider.getValue()) {
                    if (projects.putIfAbsent(provider.getKey() + ":" + project, integration) != null) throw invalid("Duplicate Integration Project");
                }
            }
            for (String path : integration.paths()) {
                String owner = paths.putIfAbsent(path.toLowerCase(Locale.ROOT), integration.id());
                if (owner != null && !owner.equals(integration.id())) throw invalid("Duplicate Integration Configuration Path");
            }
            integrations.add(integration);
        }
        for (Map.Entry<String, Integration> project : projects.entrySet()) {
            int split = project.getKey().indexOf(':');
            String provider = project.getKey().substring(0, split);
            Integration alias = names.get(normalize(project.getKey().substring(split + 1)));
            if (alias != null && alias.projectAliases().contains(provider) && !alias.id().equals(project.getValue().id())) throw invalid("Duplicate Integration Project Match");
        }
        current = new Catalog(json, Sha256.hex(json.getBytes(StandardCharsets.UTF_8)), List.copyOf(integrations), Map.copyOf(ids), Map.copyOf(names), Map.copyOf(projects));
    }

    public static String stamp() {
        return current.stamp();
    }

    public static List<Integration> catalog() {
        return current.integrations();
    }

    public static List<String> paths(Integration integration, boolean proxy) {
        Integration policy = known(integration);
        return switch (policy.id()) {
            case "voicechat" -> policy.paths().stream().filter(path -> path.endsWith(proxy ? "/voicechat-proxy.properties" : "/voicechat-server.properties")).toList();
            case "geyser" -> policy.paths().stream().filter(path -> {
                boolean proxyPath = path.startsWith("plugins/Geyser-Velocity/") || path.startsWith("plugins/Geyser-BungeeCord/") || path.startsWith("plugins/Geyser-Bungee/");
                return proxyPath == proxy || path.equals("plugins/Geyser/config.yml");
            }).toList();
            case "plasmovoice" -> {
                List<String> selected = new ArrayList<>();
                for (String path : List.of(proxy ? "plugins/plasmovoice/config.toml" : "plugins/PlasmoVoice/config.toml",
                        proxy ? "plugins/PlasmoVoice/config.toml" : "plugins/plasmovoice/config.toml")) {
                    if (policy.paths().contains(path)) selected.add(path);
                }
                if (!proxy) policy.paths().stream().filter(path -> path.startsWith("config/")).forEach(selected::add);
                yield List.copyOf(selected);
            }
            default -> policy.paths();
        };
    }

    public static String read(Integration integration, String content, String key) {
        Integration policy = known(integration);
        setting(key, policy.format());
        if (content == null) throw invalid("Plugin Configuration Is Unavailable");
        return adapter(policy).prepare(content).read(key);
    }

    public static String patch(Integration integration, String content, Map<String, String> values) {
        Integration policy = known(integration);
        if (content == null) throw invalid("Plugin Configuration Is Unavailable");
        Objects.requireNonNull(values, "values");
        NetworkConfigurationAdapter adapter = adapter(policy);
        String changed = content;
        for (Map.Entry<String, String> value : values.entrySet()) {
            setting(value.getKey(), policy.format());
            changed = adapter.apply(changed, value.getKey(), Objects.requireNonNull(value.getValue(), "value"));
        }
        return changed;
    }

    public static Integration find(ResourceContainerItem resource) {
        if (resource == null || resource.isModpack() || resource.getType() != ResourceType.PLUGIN && resource.getType() != ResourceType.MOD) return null;
        Catalog catalog = current;
        String provider = Objects.requireNonNullElse(resource.getProviderName(), "").toLowerCase(Locale.ROOT);
        String project = Objects.requireNonNullElse(resource.getProjectId(), "").toLowerCase(Locale.ROOT);
        Integration matched = catalog.projects().get(provider + ":" + project);
        if (matched == null) {
            Integration alias = catalog.names().get(normalize(project));
            if (alias != null && alias.projectAliases().contains(provider)) matched = alias;
        }
        Integration named = catalog.names().get(normalize(resource.getName()));
        Integration filename = catalog.names().get(normalize(resource.getFileName()));
        if (matched == null && named != null && filename != null && !named.equals(filename)) return null;
        return matched != null ? matched : named == null ? filename : named;
    }

    public static Prepared prepare(Integration integration, String relativePath, String content, int serverPort) {
        Integration known = known(integration);
        String path = relativePath(relativePath);
        if (!known.paths().contains(path)) throw new IllegalArgumentException("Configuration File Does Not Match " + known.name());
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Start The Server Once To Create The " + known.name() + " Configuration");
        NetworkConfigurationAdapter.Reader reader = adapter(known).prepare(content);
        String key = known.portKey();
        int target = known.defaultPort();
        if (reader.contains(key)) {
            try {
                target = Integer.parseInt(reader.read(key).trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Fix The " + known.name() + " Port In Its Configuration", failure);
            }
        }
        if (known.serverPortValues().contains(target)
                || !known.clonePortKey().isEmpty() && "true".equalsIgnoreCase(reader.read(known.clonePortKey()))) target = serverPort;
        port(target);
        return new Prepared(known, path, content, target);
    }

    public static String publicAddress(Integration integration, String publicHost, int publicPort, int targetPort) {
        host(publicHost);
        port(publicPort);
        port(targetPort);
        return render(known(integration).publicAddress(), publicHost, publicPort, targetPort);
    }

    public static String restore(Integration integration, String currentContent, Map<String, Value> original, Map<String, Value> desired) {
        Integration known = known(integration);
        if (currentContent == null) throw new IllegalArgumentException("Configuration File Is Unavailable");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(desired, "desired");
        if (!original.keySet().equals(desired.keySet()) || !known.managedKeys().containsAll(original.keySet())) {
            throw new IllegalArgumentException("Saved Configuration Changes Are Invalid");
        }
        NetworkConfigurationAdapter adapter = adapter(known);
        NetworkConfigurationAdapter.Reader reader = adapter.prepare(currentContent);
        String restored = currentContent;
        for (Map.Entry<String, Value> entry : original.entrySet()) {
            String key = entry.getKey();
            Value current = new Value(reader.contains(key), reader.read(key));
            if (!current.equals(desired.get(key))) continue;
            Value value = Objects.requireNonNull(entry.getValue(), "original value");
            restored = value.present() ? adapter.apply(restored, key, value.text()) : adapter.remove(restored, key);
        }
        return restored;
    }

    public static String relativePath(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || value.startsWith("/") || value.contains("\\")
                || value.contains(":") || value.chars().anyMatch(character -> character < 32) || value.matches(".*[?*\"<>|].*")) {
            throw new IllegalArgumentException("Configuration Path Must Be Relative To The Server Folder");
        }
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || !part.equals(part.strip()) || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Configuration Path Is Invalid");
        }
        return value;
    }

    public static Integration definition(JsonObject item) {
        fields(item, Set.of("id", "name", "protocol", "defaultPort", "requiresStopped", "paths", "aliases", "projects", "projectAliases", "format",
                "portKey", "serverPortValues", "clonePortKey", "publicAddress", "edits", "reloadCommands"));
        Format format;
        try { format = Format.valueOf(text(item.get("format"), 16)); } catch (IllegalArgumentException failure) { throw invalid("Invalid Configuration Format"); }
        List<String> paths = strings(item.get("paths"), 64, 512);
        List<String> aliases = item.has("aliases") ? strings(item.get("aliases"), 64, 128) : List.of();
        Map<String, List<String>> projects = new LinkedHashMap<>();
        if (item.has("projects")) {
            JsonObject providers = object(item.get("projects"));
            if (providers.size() > 16) throw invalid("Too Many Integration Providers");
            for (Map.Entry<String, JsonElement> provider : providers.entrySet()) {
                projects.put(provider.getKey(), strings(provider.getValue(), 64, 128).stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
            }
        }
        List<Integer> serverPortValues = new ArrayList<>();
        if (item.has("serverPortValues")) for (JsonElement value : array(item.get("serverPortValues"), 2)) serverPortValues.add(integer(value));
        List<Edit> edits = new ArrayList<>();
        Set<String> managedKeys = new HashSet<>();
        for (JsonElement value : array(item.get("edits"), 64)) {
            JsonObject edit = object(value);
            fields(edit, Set.of("key", "value", "quote", "when"));
            String key = text(edit.get("key"), 256);
            String replacement = text(edit.get("value"), 1024);
            edits.add(new Edit(key, replacement, bool(edit, "quote", false), edit.has("when") ? condition(object(edit.get("when"))) : ALWAYS));
            managedKeys.add(key);
        }
        return new Integration(text(item.get("id"), 64), text(item.get("name"), 128), text(item.get("protocol"), 3), integer(item.get("defaultPort")),
                bool(item, "requiresStopped", false), paths, aliases, projects,
                item.has("projectAliases") ? strings(item.get("projectAliases"), 16, 64) : List.of(), format, text(item.get("portKey"), 256), serverPortValues,
                item.has("clonePortKey") ? text(item.get("clonePortKey"), 256) : "", text(item.get("publicAddress"), 512), edits, managedKeys,
                item.has("reloadCommands") ? strings(item.get("reloadCommands"), 8, 512) : List.of());
    }

    public static JsonObject definition(Integration integration) {
        Integration known = known(integration);
        JsonObject result = new JsonObject();
        result.addProperty("id", known.id());
        result.addProperty("name", known.name());
        result.addProperty("protocol", known.protocol());
        result.addProperty("defaultPort", known.defaultPort());
        result.addProperty("requiresStopped", known.requiresStopped());
        result.add("reloadCommands", array(known.reloadCommands()));
        result.add("paths", array(known.paths()));
        result.add("aliases", array(known.aliases()));
        JsonObject projects = new JsonObject();
        known.projects().forEach((provider, ids) -> projects.add(provider, array(ids)));
        result.add("projects", projects);
        result.add("projectAliases", array(known.projectAliases()));
        result.addProperty("format", known.format().name());
        result.addProperty("portKey", known.portKey());
        JsonArray serverPortValues = new JsonArray();
        known.serverPortValues().forEach(serverPortValues::add);
        result.add("serverPortValues", serverPortValues);
        if (!known.clonePortKey().isEmpty()) result.addProperty("clonePortKey", known.clonePortKey());
        result.addProperty("publicAddress", known.publicAddress());
        JsonArray edits = new JsonArray();
        for (Edit edit : known.edits()) {
            JsonObject item = new JsonObject();
            item.addProperty("key", edit.key());
            item.addProperty("value", edit.value());
            item.addProperty("quote", edit.quote());
            JsonObject when = new JsonObject();
            when.add("present", array(edit.when().present()));
            when.add("absent", array(edit.when().absent()));
            when.add("anyPresent", array(edit.when().anyPresent()));
            when.addProperty("negate", edit.when().negate());
            item.add("when", when);
            edits.add(item);
        }
        result.add("edits", edits);
        return result;
    }

    public static String command(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || !value.equals(value.strip())
                || value.chars().anyMatch(character -> character < 32 || character == 127) || value.startsWith("/")) throw invalid("Invalid Plugin Reload Command");
        String action = value.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        String plain = action.substring(action.lastIndexOf(':') + 1);
        if (Set.of("start", "stop", "restart", "kill", "shutdown", "reload", "rl").contains(plain)) throw invalid("Use A Plugin Reload Command, Not A Server Power Command");
        return value;
    }

    private static When condition(JsonObject value) {
        fields(value, Set.of("present", "absent", "anyPresent", "negate"));
        return new When(value.has("present") ? strings(value.get("present"), 32, 256) : List.of(),
                value.has("absent") ? strings(value.get("absent"), 32, 256) : List.of(),
                value.has("anyPresent") ? strings(value.get("anyPresent"), 32, 256) : List.of(), bool(value, "negate", false));
    }

    private static String key(String value, Format format) {
        if (value == null || value.length() > 256 || !value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                || value.split("\\.").length > 16) throw invalid("Invalid Configuration Setting");
        if (format == Format.HOCON && value.contains(".")) throw invalid("HOCON Connections Require Direct Root Scalar Settings");
        return value;
    }

    private static String setting(String value, Format format) {
        if (format == Format.TOML && value != null && value.endsWith(".*")) {
            key(value.substring(0, value.length() - 2), format);
            return value;
        }
        return key(value, format);
    }

    private static void named(Map<String, Integration> names, String name, Integration integration) {
        if (name.isEmpty()) throw invalid("Invalid Integration Name");
        Integration previous = names.putIfAbsent(name, integration);
        if (previous != null && !previous.id().equals(integration.id())) throw invalid("Duplicate Integration Name");
    }

    private static String template(String value) {
        validText(value, 1024);
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '$') continue;
            int end = value.indexOf('}', index + 1);
            if (!value.startsWith("${", index) || end < 0 || !Set.of("host", "publicPort", "targetPort", "address", "url").contains(value.substring(index + 2, end))) {
                throw invalid("Invalid Integration Value Placeholder");
            }
            index = end;
        }
        return value;
    }

    private static String render(String template, String host, int publicPort, int targetPort) {
        String address = host + ":" + publicPort;
        return template.replace("${host}", host).replace("${publicPort}", Integer.toString(publicPort)).replace("${targetPort}", Integer.toString(targetPort))
                .replace("${address}", address).replace("${url}", "http://" + address + "/");
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid("Expected Integration Object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, int limit) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > limit) throw invalid("Invalid Integration List");
        return value.getAsJsonArray();
    }

    private static JsonArray array(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static List<String> strings(JsonElement value, int limit, int length) {
        List<String> result = new ArrayList<>();
        for (JsonElement item : array(value, limit)) {
            String text = text(item, length);
            if (result.contains(text)) throw invalid("Duplicate Integration Value");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String text(JsonElement value, int limit) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("Expected Integration Text");
        String text = value.getAsString();
        validText(text, limit);
        return text;
    }

    private static void validText(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit || text.chars().anyMatch(character -> character < 32)) throw invalid("Invalid Integration Text");
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("-?\\d{1,10}")) throw invalid("Expected Integration Integer");
        try { return Integer.parseInt(value.getAsString()); } catch (NumberFormatException failure) { throw invalid("Invalid Integration Integer"); }
    }

    private static boolean bool(JsonObject value, String key, boolean fallback) {
        if (!value.has(key)) return fallback;
        JsonElement element = value.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw invalid("Expected Integration Boolean");
        return element.getAsBoolean();
    }

    private static void fields(JsonObject value, Set<String> allowed) {
        for (String key : value.keySet()) if (!allowed.contains(key)) throw invalid("Unknown Integration Field: " + key);
    }

    private static void bounded(String json) {
        if (json == null || json.isBlank() || json.length() > 262144 || json.getBytes(StandardCharsets.UTF_8).length > 262144) throw invalid("Integration Catalog Is Too Large Or Empty");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '[' || character == '{') {
                if (++depth > 12) throw invalid("Integration Catalog Is Too Deep");
            } else if (character == ']' || character == '}') depth--;
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static Integration known(Integration integration) {
        if (integration == null) throw unknown();
        return integration;
    }

    private static NetworkConfigurationAdapter adapter(Integration integration) {
        return switch (integration.format()) {
            case HOCON -> HOCON;
            case PROPERTIES -> ADAPTERS.get(ConfigurationFormat.PROPERTIES);
            case YAML -> ADAPTERS.get(ConfigurationFormat.YAML);
            case TOML -> ADAPTERS.get(ConfigurationFormat.TOML);
        };
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT).replace("®", "");
        if (normalized.endsWith(".disabled")) normalized = normalized.substring(0, normalized.length() - 9);
        if (normalized.endsWith(".jar")) normalized = normalized.substring(0, normalized.length() - 4);
        normalized = normalized.replaceFirst("[-_ ](?:v?\\d).*", "");
        return normalized.replaceAll("[-_ ]", "");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void host(String value) {
        if (value == null || value.length() > 253 || !value.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) {
            throw new IllegalArgumentException("Public Address Is Invalid");
        }
        for (String label : value.split("\\.", -1)) {
            if (label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) throw new IllegalArgumentException("Public Address Is Invalid");
        }
    }

    private static void port(int value) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Server Port Is Invalid");
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Plugin Connection Is Unavailable");
    }
}
