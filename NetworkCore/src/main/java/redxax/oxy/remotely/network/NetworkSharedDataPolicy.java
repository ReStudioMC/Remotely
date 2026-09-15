package redxax.oxy.remotely.network;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record NetworkSharedDataPolicy(SelectionMode chatChannelMode, Set<String> chatChannels, long chatRetentionMillis, SelectionMode resourceTypeMode, Set<String> resourceTypes, List<NetworkPathSync> pathSyncs, ConflictPolicy resourceConflictPolicy, int maximumPayloadBytes) {
    public static final int DEFAULT_MAXIMUM_PAYLOAD_BYTES = 500_000;

    public NetworkSharedDataPolicy {
        chatChannelMode = chatChannelMode == null ? SelectionMode.ALL : chatChannelMode;
        chatChannels = normalized(chatChannels);
        chatRetentionMillis = Math.clamp(chatRetentionMillis, 1_000, 31_536_000_000L);
        resourceTypeMode = resourceTypeMode == null ? SelectionMode.ALL : resourceTypeMode;
        resourceTypes = normalized(resourceTypes);
        pathSyncs = normalizedPathSyncs(pathSyncs);
        resourceConflictPolicy = resourceConflictPolicy == null ? ConflictPolicy.NETWORK_WINS : resourceConflictPolicy;
        maximumPayloadBytes = Math.clamp(maximumPayloadBytes, 1_024, 500_000);
    }

    public NetworkSharedDataPolicy(SelectionMode chatChannelMode, Set<String> chatChannels, long chatRetentionMillis, SelectionMode resourceTypeMode, Set<String> resourceTypes, ConflictPolicy resourceConflictPolicy, int maximumPayloadBytes) {
        this(chatChannelMode, chatChannels, chatRetentionMillis, resourceTypeMode, resourceTypes, List.of(), resourceConflictPolicy, maximumPayloadBytes);
    }

    public static NetworkSharedDataPolicy defaults() {
        return new NetworkSharedDataPolicy(SelectionMode.ALL, Set.of(), 120_000, SelectionMode.ALL, Set.of(), List.of(), ConflictPolicy.NETWORK_WINS, DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream().map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT)).filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<NetworkPathSync> normalizedPathSyncs(List<NetworkPathSync> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<NetworkPathSync> normalized = List.copyOf(values);
        Set<String> ids = new LinkedHashSet<>();
        for (NetworkPathSync sync : normalized) {
            if (sync == null) {
                throw new IllegalArgumentException("Path Sync entry is required");
            }
            if (!ids.add(sync.id())) {
                throw new IllegalArgumentException("Path Sync IDs must be unique");
            }
        }
        for (int first = 0; first < normalized.size(); first++) {
            NetworkPathSync left = normalized.get(first);
            if (!left.enabled()) {
                continue;
            }
            for (int second = first + 1; second < normalized.size(); second++) {
                NetworkPathSync right = normalized.get(second);
                if (right.enabled() && left.nodeIds().stream().anyMatch(right.nodeIds()::contains) && overlaps(left.paths(), right.paths())) {
                    throw new IllegalArgumentException("Enabled syncs cannot use overlapping paths on the same server");
                }
            }
        }
        return normalized;
    }

    private static boolean overlaps(Set<String> first, Set<String> second) {
        if (first.contains(".") || second.contains(".")) {
            return true;
        }
        return first.stream().anyMatch(left -> second.stream().anyMatch(right -> sameOrChild(left, right) || sameOrChild(right, left)));
    }

    private static boolean sameOrChild(String path, String parent) {
        return path.equals(parent) || ".".equals(parent) || path.startsWith(parent + "/");
    }

    public enum SelectionMode {
        ALL,
        ALLOW_LIST,
        DENY_LIST
    }

    public enum ConflictPolicy {
        NETWORK_WINS,
        LOCAL_WINS
    }
}
