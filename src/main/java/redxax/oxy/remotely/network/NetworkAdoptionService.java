package redxax.oxy.remotely.network;

import redxax.oxy.remotely.libs.snakeyaml.LoaderOptions;
import redxax.oxy.remotely.libs.snakeyaml.Yaml;
import redxax.oxy.remotely.libs.snakeyaml.constructor.SafeConstructor;
import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;

public class NetworkAdoptionService {
    public Async<NetworkAdoptionReport> scan(Instance proxy, Collection<Instance> instances, Collection<NetworkDefinition> networks) {
        if (proxy == null) {
            return Async.failed(new IllegalArgumentException("Proxy is required"));
        }
        if (!isVelocity(proxy)) {
            return Async.failed(new IllegalArgumentException("Import Network Requires Velocity"));
        }
        Path config = resolve(proxy, Path.of("velocity.toml"));
        return readConfiguration(proxy, config, "Velocity Config").thenApply(content -> parse(proxy, content, instances, networks));
    }

    public Async<NetworkAdoptionReport> scanLegacyMigration(Instance proxy, Collection<Instance> instances, Collection<NetworkDefinition> networks) {
        if (proxy == null) {
            return Async.failed(new IllegalArgumentException("Proxy is required"));
        }
        if (!isLegacyProxy(proxy)) {
            return Async.failed(new IllegalArgumentException("Velocity Migration Requires Waterfall Or BungeeCord"));
        }
        Path config = resolve(proxy, Path.of("config.yml"));
        return readConfiguration(proxy, config, "Proxy Config").thenApply(content -> parseLegacy(proxy, content, instances, networks));
    }

    private Async<String> readConfiguration(Instance proxy, Path path, String name) {
        return JvmAsyncBridge.fromFuture(InstanceApi.of(proxy).files().read(path)).handle((content, failure) -> {
            if (failure == null) return content;
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof NoSuchFileException) throw new IllegalStateException(name + " Is Missing From " + proxy.getName());
            throw cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
        });
    }

    public Async<String> readForwardingSecret(Instance proxy, NetworkAdoptionReport report) {
        if (report.forwardingMode() == ForwardingMode.NONE || report.forwardingMode() == ForwardingMode.LEGACY) {
            return Async.completed("");
        }
        if (report.secretFile().isBlank()) {
            return Async.failed(new IllegalStateException("Velocity forwarding secret file is not configured"));
        }
        Path path = resolve(proxy, safeRelativePath(report.secretFile()));
        return JvmAsyncBridge.fromFuture(InstanceApi.of(proxy).files().read(path)).thenApply(value -> value == null ? "" : value.trim()).thenApply(value -> {
            if (value.isBlank()) {
                throw new IllegalStateException("Velocity forwarding secret is empty");
            }
            return value;
        });
    }

    public NetworkAdoptionReport resolveRoute(NetworkAdoptionReport report, String routeName, Instance instance, Collection<Instance> instances, Collection<NetworkDefinition> networks) {
        if (report == null || instance == null) {
            throw new IllegalArgumentException("Route and server are required");
        }
        NetworkAdoptionRoute route = report.routes().stream().filter(candidate -> candidate.routeName().equals(routeName)).findFirst().orElseThrow(() -> new IllegalArgumentException("Velocity route is unavailable: " + routeName));
        if (instance.getInstanceId().equals(report.proxyInstanceId()) || instance.isProxyServer()) {
            throw new IllegalArgumentException("A proxy cannot be assigned to a backend route");
        }
        if (!atomicConfiguration(instance)) {
            throw new IllegalArgumentException("This Backend Cannot Be Managed Safely. Mark Its Route As External And Verify Forwarding Manually");
        }
        boolean available = instances != null && instances.stream().filter(candidate -> candidate != null).anyMatch(candidate -> candidate.getInstanceId().equals(instance.getInstanceId()));
        if (!available) {
            throw new IllegalArgumentException("Server is no longer available");
        }
        boolean managed = networks != null && networks.stream().flatMap(network -> network.members().stream()).anyMatch(member -> member.instanceId().equals(instance.getInstanceId()));
        if (managed) {
            throw new IllegalArgumentException("Server already belongs to a managed network");
        }
        boolean assigned = report.routes().stream().filter(candidate -> !candidate.routeName().equals(route.routeName())).anyMatch(candidate -> candidate.instanceId().equals(instance.getInstanceId()));
        if (assigned) {
            throw new IllegalArgumentException("Server is already assigned to another Velocity route");
        }
        List<NetworkAdoptionRoute> routes = report.routes().stream().map(candidate -> candidate.routeName().equals(route.routeName()) ? new NetworkAdoptionRoute(candidate.routeName(), candidate.address(), candidate.port(), instance.getInstanceId(), "Assigned " + instance.getName()) : candidate).toList();
        List<NetworkValidationIssue> issues = report.issues().stream().filter(issue -> !issue.subject().equals(route.routeName()) || !issue.code().startsWith("adoption.route.")).toList();
        return new NetworkAdoptionReport(report.proxyInstanceId(), report.bindAddress(), report.entryPort(), report.proxyOnlineMode(), report.forwardingMode(), report.secretFile(), routes, report.fallbackRoutes(), report.forcedHosts(), issues);
    }

    public NetworkAdoptionReport resolveExternalRoute(NetworkAdoptionReport report, String routeName) {
        if (report == null) {
            throw new IllegalArgumentException("Adoption report is required");
        }
        NetworkAdoptionRoute route = report.routes().stream().filter(candidate -> candidate.routeName().equals(routeName)).findFirst().orElseThrow(() -> new IllegalArgumentException("Proxy route is unavailable: " + routeName));
        String externalId = route.management() == NetworkMemberManagement.EXTERNAL && !route.instanceId().isBlank() ? route.instanceId() : "external:" + UUID.randomUUID();
        List<NetworkAdoptionRoute> routes = report.routes().stream().map(candidate -> candidate.routeName().equals(route.routeName()) ? new NetworkAdoptionRoute(candidate.routeName(), candidate.address(), candidate.port(), externalId, "Externally Managed • Forwarding And Security Require Manual Verification", NetworkMemberManagement.EXTERNAL) : candidate).toList();
        List<NetworkValidationIssue> issues = new ArrayList<>(report.issues().stream().filter(issue -> !issue.subject().equals(route.routeName()) || !issue.code().startsWith("adoption.route.")).toList());
        issues.add(warning("adoption.route.external", route.routeName(), "External backend configuration, forwarding, lifecycle, and firewall policy remain operator-managed"));
        return new NetworkAdoptionReport(report.proxyInstanceId(), report.bindAddress(), report.entryPort(), report.proxyOnlineMode(), report.forwardingMode(), report.secretFile(), routes, report.fallbackRoutes(), report.forcedHosts(), issues);
    }

    NetworkAdoptionReport parse(Instance proxy, String content, Collection<Instance> instances, Collection<NetworkDefinition> networks) {
        ParsedVelocity source = parseVelocity(content);
        List<NetworkValidationIssue> issues = new ArrayList<>();
        boolean stockConfig = isStockVelocityConfig(source);
        ParsedVelocity parsed = stockConfig ? new ParsedVelocity(source.bindAddress(), source.entryPort(), source.proxyOnlineMode(), source.forwardingMode(), source.secretFile(), Map.of(), List.of(), Map.of()) : source;
        if (stockConfig) {
            issues.add(warning("adoption.stock-config", proxy.getInstanceId(), "Velocity Only Contains Example Routes. Choose Backend Servers To Set Up The Network"));
        } else if (parsed.routes().isEmpty()) {
            issues.add(warning("adoption.routes.empty", proxy.getInstanceId(), "This Proxy Has No Backend Routes. Choose Existing Servers To Set Up The Network"));
        }
        if (parsed.entryPort() == 0) {
            issues.add(error("adoption.bind.invalid", proxy.getInstanceId(), "Velocity bind address is invalid"));
        }
        if ((parsed.forwardingMode() == ForwardingMode.MODERN || parsed.forwardingMode() == ForwardingMode.BUNGEEGUARD) && parsed.secretFile().isBlank()) {
            issues.add(error("adoption.secret.missing", proxy.getInstanceId(), "Velocity forwarding requires a secret file"));
        }
        Set<String> managedInstances = new LinkedHashSet<>();
        if (networks != null) {
            networks.forEach(network -> network.members().forEach(member -> managedInstances.add(member.instanceId())));
        }
        if (managedInstances.contains(proxy.getInstanceId())) {
            issues.add(error("adoption.proxy.managed", proxy.getInstanceId(), "Proxy already belongs to a managed network"));
        }
        List<NetworkAdoptionRoute> routes = new ArrayList<>();
        Set<String> matchedInstances = new LinkedHashSet<>();
        for (Map.Entry<String, Address> route : parsed.routes().entrySet()) {
            List<Instance> matches = match(route.getValue(), proxy, instances, managedInstances);
            if (matches.size() == 1 && matchedInstances.add(matches.getFirst().getInstanceId())) {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), matches.getFirst().getInstanceId(), "Matched " + matches.getFirst().getName()));
            } else if (matches.size() > 1) {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), "", "Multiple Remotely servers match this route"));
                issues.add(error("adoption.route.ambiguous", route.getKey(), "Multiple Remotely servers match " + route.getValue().render()));
            } else if (!matches.isEmpty()) {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), "", "Server is already matched by another route"));
                issues.add(error("adoption.route.duplicate-instance", route.getKey(), "A Remotely server cannot own multiple Velocity routes"));
            } else {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), "", "No Remotely server matches this route"));
                issues.add(error("adoption.route.unknown", route.getKey(), "Register or import the backend at " + route.getValue().render()));
            }
        }
        for (String fallback : parsed.fallbackRoutes()) {
            if (!parsed.routes().containsKey(fallback)) {
                issues.add(error("adoption.fallback.unknown", fallback, "Velocity fallback references an unknown route"));
            }
        }
        parsed.forcedHosts().forEach((host, routeNames) -> routeNames.stream().filter(routeName -> !parsed.routes().containsKey(routeName)).forEach(routeName -> issues.add(error("adoption.forced-host.unknown", host, "Forced host references unknown route " + routeName))));
        return new NetworkAdoptionReport(proxy.getInstanceId(), parsed.bindAddress(), parsed.entryPort(), parsed.proxyOnlineMode(), parsed.forwardingMode(), parsed.secretFile(), routes, parsed.fallbackRoutes(), parsed.forcedHosts(), issues);
    }

    NetworkAdoptionReport parseLegacy(Instance proxy, String content, Collection<Instance> instances, Collection<NetworkDefinition> networks) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(50);
        Object loaded = new Yaml(new SafeConstructor(options)).load(content == null ? "" : content);
        Map<String, Object> root = objectMap(loaded);
        Map<String, Address> parsedRoutes = new LinkedHashMap<>();
        objectMap(root.get("servers")).forEach((name, value) -> {
            Map<String, Object> server = objectMap(value);
            Address address = address(stringValue(server.isEmpty() ? value : server.get("address")));
            if (address.port() > 0) {
                parsedRoutes.put(routeName(name), address);
            }
        });
        List<Object> listeners = objectList(root.get("listeners"));
        Map<String, Object> listener = listeners.isEmpty() ? Map.of() : objectMap(listeners.getFirst());
        Address bind = address(stringValue(listener.get("host")));
        List<String> fallbackRoutes = stringList(listener.get("priorities")).stream().map(this::routeName).toList();
        Map<String, List<String>> forcedHosts = new LinkedHashMap<>();
        objectMap(listener.get("forced_hosts")).forEach((host, value) -> {
            List<String> routes = value instanceof Collection<?> ? stringList(value).stream().map(this::routeName).toList() : List.of(routeName(stringValue(value)));
            forcedHosts.put(host, routes.stream().filter(route -> !route.isBlank()).toList());
        });
        List<NetworkValidationIssue> issues = new ArrayList<>();
        if (parsedRoutes.isEmpty()) {
            issues.add(error("migration.routes.empty", proxy.getInstanceId(), "Legacy proxy does not define any backend routes"));
        }
        if (bind.port() == 0) {
            issues.add(error("migration.bind.invalid", proxy.getInstanceId(), "Legacy proxy listener address is invalid"));
        }
        if (listeners.size() > 1) {
            issues.add(warning("migration.listeners.multiple", proxy.getInstanceId(), "Migration uses the first listener; review additional legacy listeners"));
        }
        if (!booleanValue(root.get("ip_forward"), false)) {
            issues.add(warning("migration.forwarding.disabled", proxy.getInstanceId(), "Legacy IP forwarding is disabled and will be replaced by modern forwarding"));
        }
        issues.add(warning("migration.plugins.review", proxy.getInstanceId(), "Review legacy proxy plugins for Velocity replacements before cutover"));
        Set<String> managedInstances = new LinkedHashSet<>();
        if (networks != null) {
            networks.forEach(network -> network.members().forEach(member -> managedInstances.add(member.instanceId())));
        }
        if (managedInstances.contains(proxy.getInstanceId())) {
            issues.add(error("migration.proxy.managed", proxy.getInstanceId(), "Legacy proxy already belongs to a managed network"));
        }
        List<NetworkAdoptionRoute> routes = new ArrayList<>();
        Set<String> matchedInstances = new LinkedHashSet<>();
        for (Map.Entry<String, Address> route : parsedRoutes.entrySet()) {
            List<Instance> matches = match(route.getValue(), proxy, instances, managedInstances);
            if (matches.size() == 1 && matchedInstances.add(matches.getFirst().getInstanceId())) {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), matches.getFirst().getInstanceId(), "Matched " + matches.getFirst().getName()));
            } else if (matches.size() > 1) {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), "", "Multiple Remotely servers match this route"));
                issues.add(error("adoption.route.ambiguous", route.getKey(), "Multiple Remotely servers match " + route.getValue().render()));
            } else if (!matches.isEmpty()) {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), "", "Server is already matched by another route"));
                issues.add(error("adoption.route.duplicate-instance", route.getKey(), "A Remotely server cannot own multiple proxy routes"));
            } else {
                routes.add(new NetworkAdoptionRoute(route.getKey(), route.getValue().host(), route.getValue().port(), "", "No Remotely server matches this route"));
                issues.add(error("adoption.route.unknown", route.getKey(), "Register or import the backend at " + route.getValue().render()));
            }
        }
        fallbackRoutes.stream().filter(route -> !parsedRoutes.containsKey(route)).forEach(route -> issues.add(error("migration.fallback.unknown", route, "Legacy priority references an unknown route")));
        forcedHosts.forEach((host, routeNames) -> routeNames.stream().filter(route -> !parsedRoutes.containsKey(route)).forEach(route -> issues.add(error("migration.forced-host.unknown", host, "Forced host references unknown route " + route))));
        String bindAddress = bind.host().isBlank() ? "0.0.0.0" : bind.host();
        return new NetworkAdoptionReport(proxy.getInstanceId(), bindAddress, bind.port(), booleanValue(root.get("online_mode"), true), ForwardingMode.LEGACY, "", routes, fallbackRoutes, forcedHosts, issues);
    }

    private ParsedVelocity parseVelocity(String content) {
        String section = "";
        Map<String, Address> routes = new LinkedHashMap<>();
        List<String> fallbackRoutes = List.of();
        Map<String, List<String>> forcedHosts = new LinkedHashMap<>();
        String bind = "0.0.0.0:25565";
        boolean onlineMode = true;
        ForwardingMode forwardingMode = ForwardingMode.NONE;
        String secretFile = "";
        String[] sourceLines = (content == null ? "" : content).split("\\R");
        for (int lineIndex = 0; lineIndex < sourceLines.length; lineIndex++) {
            String sourceLine = sourceLines[lineIndex];
            String line = stripComment(sourceLine).trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = unquote(line.substring(1, line.length() - 1).trim());
                continue;
            }
            int equals = assignmentIndex(line);
            if (equals < 0) {
                continue;
            }
            String key = unquote(line.substring(0, equals).trim());
            String value = line.substring(equals + 1).trim();
            if (value.startsWith("[") && !value.endsWith("]")) {
                StringBuilder array = new StringBuilder(value);
                while (++lineIndex < sourceLines.length) {
                    String continuation = stripComment(sourceLines[lineIndex]).trim();
                    if (!continuation.isBlank()) {
                        array.append(' ').append(continuation);
                    }
                    if (continuation.endsWith("]")) {
                        break;
                    }
                }
                value = array.toString();
            }
            if (section.isBlank()) {
                if (key.equals("bind")) bind = unquote(value);
                if (key.equals("online-mode")) onlineMode = Boolean.parseBoolean(value);
                if (key.equals("player-info-forwarding-mode")) forwardingMode = forwardingMode(unquote(value));
                if (key.equals("forwarding-secret-file")) secretFile = unquote(value);
            } else if (section.equals("servers")) {
                if (key.equals("try")) {
                    fallbackRoutes = array(value);
                } else {
                    Address address = address(unquote(value));
                    if (address.port() > 0) routes.put(routeName(key), address);
                }
            } else if (section.equals("forced-hosts")) {
                forcedHosts.put(key, array(value));
            }
        }
        Address entry = address(bind);
        return new ParsedVelocity(entry.host(), entry.port(), onlineMode, forwardingMode, secretFile, routes, fallbackRoutes, forcedHosts);
    }

    private boolean isStockVelocityConfig(ParsedVelocity parsed) {
        if (!parsed.routes().keySet().equals(Set.of("lobby", "factions", "minigames"))) {
            return false;
        }
        Address lobby = parsed.routes().get("lobby");
        Address factions = parsed.routes().get("factions");
        Address minigames = parsed.routes().get("minigames");
        return loopback(lobby.host()) && lobby.port() == 30066 && loopback(factions.host()) && factions.port() == 30067 && loopback(minigames.host()) && minigames.port() == 30068;
    }

    private List<Instance> match(Address route, Instance proxy, Collection<Instance> instances, Set<String> managedInstances) {
        if (instances == null) {
            return List.of();
        }
        String proxyScope = NetworkHostScope.resolve(proxy);
        return instances.stream().filter(instance -> instance != null && !instance.getInstanceId().equals(proxy.getInstanceId())
            && !instance.isProxyServer() && !managedInstances.contains(instance.getInstanceId()) && atomicConfiguration(instance))
            .filter(instance -> observedPort(instance) == route.port()).filter(instance -> {
            if (loopback(route.host())) {
                return NetworkHostScope.resolve(instance).equals(proxyScope);
            }
            String host = instance.getBackendConfig() == null ? "" : instance.getBackendConfig().credentials.getOrDefault("host", "");
            return route.host().equalsIgnoreCase(host);
        }).toList();
    }

    private boolean atomicConfiguration(Instance instance) {
        try {
            ServerBackend backend = instance.getBackend();
            return backend != null && backend.getFileSystem() != null && backend.getFileSystem().supportsAtomicWrites();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private int observedPort(Instance instance) {
        try {
            return Integer.parseInt(instance.getServerProperties().getProperty("server-port", "0"));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private Address address(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("[")) {
            int close = normalized.lastIndexOf(']');
            if (close > 0 && close + 2 <= normalized.length() && normalized.charAt(close + 1) == ':') {
                return new Address(normalized.substring(1, close), parsePort(normalized.substring(close + 2)));
            }
        }
        int separator = normalized.lastIndexOf(':');
        if (separator <= 0) {
            return new Address(normalized, 0);
        }
        return new Address(normalized.substring(0, separator), parsePort(normalized.substring(separator + 1)));
    }

    private int parsePort(String value) {
        try {
            return Math.clamp(Integer.parseInt(value.trim()), 0, 65535);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private int assignmentIndex(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && quoted) {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == '=' && !quoted) {
                return index;
            }
        }
        return -1;
    }

    private String stripComment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && quoted) {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == '#' && !quoted) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private List<String> array(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("[") || !normalized.endsWith("]")) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (char character : normalized.substring(1, normalized.length() - 1).toCharArray()) {
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\' && quoted) {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                addArrayValue(values, current);
            } else {
                current.append(character);
            }
        }
        addArrayValue(values, current);
        return List.copyOf(values);
    }

    private void addArrayValue(List<String> values, StringBuilder current) {
        String value = unquote(current.toString().trim());
        if (!value.isBlank()) {
            values.add(routeName(value));
        }
        current.setLength(0);
    }

    private ForwardingMode forwardingMode(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "modern" -> ForwardingMode.MODERN;
            case "bungeeguard" -> ForwardingMode.BUNGEEGUARD;
            case "legacy" -> ForwardingMode.LEGACY;
            default -> ForwardingMode.NONE;
        };
    }

    private String unquote(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            return normalized.substring(1, normalized.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return normalized;
    }

    private boolean loopback(String value) {
        return value.equalsIgnoreCase("localhost") || value.equals("127.0.0.1") || value.equals("::1");
    }

    private String routeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private boolean isVelocity(Instance instance) {
        return instance.getModLoader() == ModLoader.VELOCITY || instance.getServerSoftwareCompatibility().stream().anyMatch(value -> value.equalsIgnoreCase("velocity"));
    }

    private boolean isLegacyProxy(Instance instance) {
        return instance.getModLoader() == ModLoader.WATERFALL || instance.getModLoader() == ModLoader.BUNGEECORD || instance.getServerSoftwareCompatibility().stream().anyMatch(value -> value.equalsIgnoreCase("waterfall") || value.equalsIgnoreCase("bungeecord"));
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Object> objectList(Object value) {
        return value instanceof Collection<?> collection ? new ArrayList<>(collection) : List.of();
    }

    private List<String> stringList(Object value) {
        return objectList(value).stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private Path safeRelativePath(String value) {
        Path path = Path.of(value.replace('\\', '/')).normalize();
        if (path.getRoot() != null || path.startsWith("..")) {
            throw new IllegalArgumentException("Unsafe Velocity secret path");
        }
        return path;
    }

    private Path resolve(Instance instance, Path relativePath) {
        Path relative = safeRelativePath(relativePath.toString());
        return Path.of(instance.getPath()).resolve(relative).normalize();
    }

    private NetworkValidationIssue error(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, code, subject, message);
    }

    private NetworkValidationIssue warning(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.WARNING, code, subject, message);
    }

    private record Address(String host, int port) {
        private String render() {
            return host + ":" + port;
        }
    }

    private record ParsedVelocity(String bindAddress, int entryPort, boolean proxyOnlineMode, ForwardingMode forwardingMode, String secretFile, Map<String, Address> routes, List<String> fallbackRoutes, Map<String, List<String>> forcedHosts) {
    }
}
