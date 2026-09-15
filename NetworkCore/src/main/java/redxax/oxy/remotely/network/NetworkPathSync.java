package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record NetworkPathSync(String id, String name, boolean enabled, Set<String> nodeIds, Set<String> paths, NetworkSharedDataPolicy.ConflictPolicy conflictPolicy, List<String> commands) {
    public NetworkPathSync {
        id = normalize(id).toLowerCase(Locale.ROOT);
        name = normalize(name);
        nodeIds = immutableSet(nodeIds);
        paths = normalizedPaths(paths);
        conflictPolicy = conflictPolicy == null ? NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS : conflictPolicy;
        commands = normalizedCommands(commands);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("Sync ID must use lowercase letters, numbers, dashes, or underscores");
        }
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("Sync name must be between 1 and 64 characters");
        }
        if (enabled && paths.isEmpty()) {
            throw new IllegalArgumentException("Add at least one file or folder");
        }
    }

    public NetworkPathSync withEnabled(boolean value) {
        return new NetworkPathSync(id, name, value, nodeIds, paths, conflictPolicy, commands);
    }

    private static Set<String> normalizedPaths(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = normalize(value).replace('\\', '/');
            while (candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            if (candidate.isBlank()) {
                continue;
            }
            candidate = normalizePath(candidate);
            normalized.add(candidate);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static List<String> normalizedCommands(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream().map(NetworkPathSync::normalize).map(command -> command.startsWith("/") ? command.substring(1).trim() : command).filter(command -> !command.isBlank()).toList();
        if (normalized.size() > 32 || normalized.stream().anyMatch(command -> command.length() > 2_048 || command.indexOf('\u0000') >= 0)) {
            throw new IllegalArgumentException("Use up to 32 commands with at most 2048 characters each");
        }
        return normalized;
    }

    private static Set<String> immutableSet(Set<String> values) {
        return values == null || values.isEmpty() ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values.stream().map(NetworkPathSync::normalize).filter(value -> !value.isBlank()).toList()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizePath(String value) {
        if (value.indexOf('\u0000') >= 0 || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Sync paths must stay inside the server directory");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isBlank() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("Sync paths must stay inside the server directory");
                }
                segments.removeLast();
                continue;
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? "." : String.join("/", segments);
    }
}
