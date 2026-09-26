package redxax.oxy.remotely.data.flow;

import restudio.rebase.restudio.ReStudio;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.collaboration.CollaborationService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class ReSyncDesktopIdentityProvider implements ReSyncIdentityProvider {
    private static volatile Identity resident;
    private final UUID installationIdentity;
    private final String applicationId;

    public ReSyncDesktopIdentityProvider() {
        Identity loaded = resident;
        if (loaded == null) throw new IllegalStateException("ReSync desktop identity is not initialized");
        installationIdentity = loaded.value();
        applicationId = applicationId(ReStudio.getInstance().getClientId());
    }

    ReSyncDesktopIdentityProvider(Path identityFile, String applicationId) {
        installationIdentity = readIdentity(identityFile);
        this.applicationId = applicationId(applicationId);
    }

    public static synchronized void initialize(Path applicationDirectory) {
        Path file = DesktopRemotelyPaths.dataDir(applicationDirectory).resolve("resync-client-id").toAbsolutePath().normalize();
        if (resident != null) {
            if (!resident.path().equals(file)) throw new IllegalStateException("ReSync desktop identity directory changed");
            return;
        }
        resident = new Identity(file, readIdentity(file));
    }

    @Override
    public String clientId(String serverId) {
        String seed = (serverId == null || serverId.isBlank() ? "default" : serverId) + ':'
            + applicationId + ':' + installationIdentity;
        return "remotely-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String applicationId(String value) {
        return value == null || value.isBlank() ? "remotely" : value;
    }

    private static synchronized UUID readIdentity(Path identityFile) {
        try {
            Files.createDirectories(identityFile.getParent());
            Path lockFile = identityFile.resolveSibling(identityFile.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                if (Files.exists(identityFile)) {
                    String stored = Files.readString(identityFile, StandardCharsets.UTF_8);
                    if (stored.length() != 36) throw new IllegalStateException("ReSync desktop identity is invalid");
                    UUID parsed = UUID.fromString(stored);
                    if (!parsed.toString().equals(stored)) throw new IllegalStateException("ReSync desktop identity is invalid");
                    return parsed;
                }
                UUID created = UUID.randomUUID();
                Path staged = Files.createTempFile(identityFile.getParent(), ".resync-client-id-", ".tmp");
                try {
                    try (FileChannel data = FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        ByteBuffer bytes = StandardCharsets.UTF_8.encode(created.toString());
                        while (bytes.hasRemaining()) data.write(bytes);
                        data.force(true);
                    }
                    Files.move(staged, identityFile, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(staged);
                }
                return created;
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("ReSync desktop identity is unavailable", exception);
        }
    }

    private record Identity(Path path, UUID value) {
    }

    @Override
    public CollaborationService.Identity collaborationIdentity(String fallbackClientId) {
        ReStudio studio = ReStudio.getInstance();
        String subjectId = studio.getUserId();
        String displayName = studio.getDisplayName();
        String resolvedSubject = subjectId != null && !subjectId.isBlank() ? subjectId : fallbackClientId;
        resolvedSubject = resolvedSubject == null ? "remotely" : resolvedSubject.trim();
        if (resolvedSubject.length() > 128) {
            resolvedSubject = resolvedSubject.substring(0, 128);
        }
        resolvedSubject = resolvedSubject.replaceAll("[^A-Za-z0-9._-]", "_");
        return new CollaborationService.Identity(resolvedSubject,
            displayName != null && !displayName.isBlank() ? displayName : "Collaborator",
            studio.getAvatarUrl() != null ? studio.getAvatarUrl() : "", "restudio");
    }
}
