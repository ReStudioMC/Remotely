package redxax.oxy.remotely.network.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;

public sealed interface NetworkMemberSource permits NetworkMemberSource.ExistingServer, NetworkMemberSource.Draft, NetworkMemberSource.External {
    Kind kind();

    enum Kind {
        EXISTING_SERVER,
        DRAFT,
        EXTERNAL
    }

    record ExistingServer(String serverId) implements NetworkMemberSource {
        public ExistingServer {
            serverId = text(serverId, 255, "serverId");
        }

        @Override
        public Kind kind() {
            return Kind.EXISTING_SERVER;
        }
    }

    record Draft(String poolId, String draftId, String createRequestId, String activationRequestId,
                 long expectedRevision, DraftMetadata metadata, Compute installer, Compute runtime,
                 Storage retained) implements NetworkMemberSource {
        public Draft {
            poolId = uuid(poolId, "poolId");
            draftId = uuid(draftId, "draftId");
            createRequestId = normalize(createRequestId);
            if (!createRequestId.isBlank()) {
                createRequestId = uuid(createRequestId, "createRequestId");
            }
            activationRequestId = uuid(activationRequestId, "activationRequestId");
            if (expectedRevision <= 0) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            if (!createRequestId.isBlank() && expectedRevision != 1) {
                throw new IllegalArgumentException("An inline draft must expect revision 1");
            }
            if (createRequestId.equals(activationRequestId)) {
                throw new IllegalArgumentException("Draft create and activation requests must be distinct");
            }
            if (metadata == null || installer == null || runtime == null || retained == null) {
                throw new IllegalArgumentException("Draft metadata and resources are required");
            }
        }

        @Override
        public Kind kind() {
            return Kind.DRAFT;
        }
    }

    record DraftMetadata(String name, String gameId, String profileId, Map<String, String> settings,
                         Map<String, String> initialFiles, String subdomain) {
        public DraftMetadata(String name, String gameId, String profileId, Map<String, String> settings, Map<String, String> initialFiles) {
            this(name, gameId, profileId, settings, initialFiles, null);
        }
        public DraftMetadata {
            name = text(name, 80, "name");
            gameId = text(gameId, 128, "gameId");
            profileId = text(profileId, 128, "profileId");
            if (!gameId.matches("[a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*")
                    || !profileId.matches("[a-z0-9][a-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("Draft game or profile is invalid");
            }
            settings = NetworkMemberSource.settings(settings);
            initialFiles = NetworkMemberSource.initialFiles(initialFiles);
            subdomain = subdomain == null || subdomain.isBlank() ? null : subdomain.strip().toLowerCase(Locale.ROOT);
            if (subdomain != null && (!subdomain.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") || subdomain.contains("--"))) {
                throw new IllegalArgumentException("Draft Subdomain Is Invalid");
            }
        }
    }

    record Compute(long ramMiB, long cpuQuotaPercent) {
        public Compute {
            if (ramMiB <= 0 || cpuQuotaPercent <= 0) {
                throw new IllegalArgumentException("Draft compute must be positive");
            }
        }
    }

    record Storage(long diskMiB, long backupMiB) {
        public Storage {
            if (diskMiB <= 0 || backupMiB < 0) {
                throw new IllegalArgumentException("Draft storage is invalid");
            }
        }
    }

    record External(String externalId, String name, String address) implements NetworkMemberSource {
        public External {
            externalId = text(externalId, 255, "externalId");
            name = text(name, 80, "name");
            address = text(address, 255, "address");
        }

        @Override
        public Kind kind() {
            return Kind.EXTERNAL;
        }
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String uuid(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static String text(String value, int maximum, String field) {
        String normalized = required(value, field);
        if (normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static Map<String, String> settings(Map<String, String> values) {
        if (values == null || values.size() > 128) {
            throw new IllegalArgumentException("Draft settings are invalid");
        }
        Map<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Z_][A-Z0-9_]{0,127}") || value == null || value.length() > 4096
                    || value.indexOf(0) >= 0) {
                throw new IllegalArgumentException("Draft setting is invalid");
            }
            sorted.put(key, value);
        });
        return Collections.unmodifiableMap(sorted);
    }

    private static Map<String, String> initialFiles(Map<String, String> values) {
        if (values == null || values.size() > 64) {
            throw new IllegalArgumentException("Draft initial files are invalid");
        }
        Map<String, String> sorted = new TreeMap<>();
        long bytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            if (path == null || path.isEmpty() || path.length() > 255 || path.startsWith("/") || path.contains("\\")
                    || path.contains(":") || path.chars().anyMatch(character -> character < 32 || character == 127)
                    || content == null) {
                throw new IllegalArgumentException("Draft initial file is invalid");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Draft initial file is invalid");
                }
            }
            bytes = Math.addExact(bytes, content.getBytes(StandardCharsets.UTF_8).length);
            if (bytes > 1_048_576) {
                throw new IllegalArgumentException("Draft initial files are too large");
            }
            sorted.put(path, content);
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
