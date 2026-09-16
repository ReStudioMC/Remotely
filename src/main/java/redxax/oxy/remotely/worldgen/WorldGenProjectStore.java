package redxax.oxy.remotely.worldgen;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.contract.canonical.CanonicalDigests;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

final class WorldGenProjectStore {
    private final Map<String, WorldGenProject> activeProjects = BrowserSafeState.map();
    private final Map<String, Map<String, ProjectTransfer>> draftProjects = BrowserSafeState.map();
    private final Map<String, Map<String, ProjectSnapshot>> projectCache = BrowserSafeState.map();
    private final Map<String, List<String>> projectLists = BrowserSafeState.map();
    private final Map<String, String> pendingDuplicateIds = BrowserSafeState.map();
    private final Map<String, Long> projectRevisions = BrowserSafeState.map();
    private final Map<String, Long> projectListRevisions = BrowserSafeState.map();
    private final Map<String, RevisionIdentity> projectRevisionIdentities = BrowserSafeState.map();
    private final Map<String, RevisionIdentity> projectListRevisionIdentities = BrowserSafeState.map();
    private final Map<String, ProjectState> projectStates = BrowserSafeState.map();
    private final Map<String, Long> authorityEpochs = BrowserSafeState.map();
    private final ProjectIdentityCache projectContentIdentities = new ProjectIdentityCache();
    private final BrowserSafeState.LongValue projectStateSequence = new BrowserSafeState.LongValue();

    WorldGenProject getOrCreateProject(String serverId, Supplier<WorldGenProject> defaultProjectSupplier) {
        return activeProjects.computeIfAbsent(key(serverId), id -> defaultProjectSupplier.get());
    }

    WorldGenProject setActiveProject(String serverId, WorldGenProject project) {
        if (serverId == null || project == null) {
            return null;
        }
        String normalizedServerId = key(serverId);
        cacheProject(normalizedServerId, project);
        String projectId = project.getId();
        WorldGenProject draft = projectId == null ? null : draftProject(normalizedServerId, projectId);
        WorldGenProject active = draft != null ? draft : project;
        activeProjects.put(normalizedServerId, active);
        return active;
    }

    void setDraftProject(String serverId, WorldGenProject project) {
        if (serverId == null || project == null) {
            return;
        }
        String normalizedServerId = key(serverId);
        String projectId = project.getId();
        if (projectId == null || projectId.isBlank()) {
            activeProjects.put(normalizedServerId, project);
            return;
        }
        setDraftProject(normalizedServerId, transferProject(project, () -> project));
    }

    void setDraftProject(String serverId, ProjectTransfer transfer) {
        if (serverId == null || transfer == null || transfer.project() == null) {
            return;
        }
        String normalizedServerId = key(serverId);
        String projectId = transfer.project().getId();
        if (projectId == null || projectId.isBlank()) {
            activeProjects.put(normalizedServerId, transfer.source());
            return;
        }
        draftProjects.computeIfAbsent(normalizedServerId, ignored -> BrowserSafeState.map()).put(projectId, transfer);
        activeProjects.put(normalizedServerId, transfer.source());
    }

    synchronized void promoteDraftProject(String serverId, ProjectTransfer transfer, long authorityRevision) {
        if (transfer == null || transfer.project() == null) {
            return;
        }
        String normalizedServerId = key(serverId);
        String projectId = transfer.project().getId();
        if (projectId != null) {
            Map<String, ProjectTransfer> serverDrafts = draftProjects.get(normalizedServerId);
            if (serverDrafts != null) {
                serverDrafts.remove(projectId);
                if (serverDrafts.isEmpty()) {
                    draftProjects.remove(normalizedServerId, serverDrafts);
                }
            }
        }
        String revisionKey = projectRevisionKey(normalizedServerId, projectId);
        long currentRevision = projectRevisions.getOrDefault(revisionKey, 0L);
        long acceptedRevision = Math.max(currentRevision, authorityRevision);
        projectRevisions.put(revisionKey, acceptedRevision);
        projectRevisionIdentities.put(revisionKey, transfer.identity());
        cacheProject(normalizedServerId, projectId, transfer.identity(), acceptedRevision);
        activeProjects.put(normalizedServerId, transfer.source());
    }

    WorldGenProject copyProject(WorldGenProject project, Supplier<WorldGenProject> fallbackSupplier) {
        ProjectTransfer transfer = transferProject(project, fallbackSupplier);
        return transfer == null ? null : transfer.project();
    }

    ProjectTransfer transferProject(WorldGenProject project, Supplier<WorldGenProject> fallbackSupplier) {
        WorldGenProject source = project == null ? fallbackSupplier.get() : project;
        if (source == null) {
            return null;
        }
        String canonicalContent = canonicalProject(source);
        RevisionIdentity identity = RevisionIdentity.of(source.getId(), canonicalContent);
        WorldGenProject copy = WorldGenSerializer.deserializeProject(canonicalContent);
        projectContentIdentities.put(source, identity);
        projectContentIdentities.put(copy, identity);
        return new ProjectTransfer(source, copy, identity);
    }

    ProjectTransfer transferPreparedProject(WorldGenProject project, String serializedContent,
                                            JsonObject workspaceDocument, String expectedCanonicalHash) {
        if (project == null || serializedContent == null || serializedContent.isBlank() || workspaceDocument == null) {
            return null;
        }
        String canonicalContent = canonicalJson(serializedContent);
        String projectContent = canonicalProject(project);
        String workspaceContent = canonicalJson(workspaceDocument.toString());
        if (!canonicalContent.equals(projectContent) || !canonicalContent.equals(workspaceContent)) {
            return null;
        }
        RevisionIdentity identity = RevisionIdentity.of(project.getId(), canonicalContent);
        if (expectedCanonicalHash != null && !expectedCanonicalHash.isBlank()
            && !expectedCanonicalHash.equals(identity.canonicalHash())) {
            return null;
        }
        projectContentIdentities.put(project, identity);
        return new ProjectTransfer(project, project, identity);
    }

    String preparedProjectHash(WorldGenProject project, String serializedContent, JsonObject workspaceDocument) {
        ProjectTransfer transfer = transferPreparedProject(project, serializedContent, workspaceDocument, "");
        return transfer == null ? "" : transfer.identity().canonicalHash();
    }

    WorldGenProject cachedProject(String serverId, String projectId) {
        Map<String, ProjectSnapshot> serverCache = projectCache.get(key(serverId));
        ProjectSnapshot snapshot = serverCache == null ? null : serverCache.get(projectId);
        if (snapshot == null) {
            return null;
        }
        WorldGenProject project = WorldGenSerializer.deserializeProject(snapshot.canonicalContent());
        projectContentIdentities.put(project, snapshot.identity());
        return project;
    }

    void cacheProject(String serverId, WorldGenProject project) {
        if (serverId == null || project == null || project.getId() == null || project.getId().isBlank()) {
            return;
        }
        ProjectTransfer transfer = projectTransfer(project);
        cacheProject(serverId, project.getId(), transfer.identity(),
            projectRevision(serverId, project.getId()));
    }

    void removeCachedProject(String serverId, String projectId) {
        Map<String, ProjectSnapshot> serverCache = projectCache.get(key(serverId));
        if (serverCache != null) {
            serverCache.remove(projectId);
        }
    }

    void removeProject(String serverId, String projectId) {
        removeProject(serverId, projectId, projectRevision(serverId, projectId));
    }

    synchronized void removeProject(String serverId, String projectId, long revision) {
        String normalizedServerId = key(serverId);
        Map<String, ProjectTransfer> serverDrafts = draftProjects.get(normalizedServerId);
        if (serverDrafts != null) {
            serverDrafts.remove(projectId);
            if (serverDrafts.isEmpty()) {
                draftProjects.remove(normalizedServerId, serverDrafts);
            }
        }
        activeProjects.computeIfPresent(normalizedServerId, (ignored, active) ->
            active.getId() != null && active.getId().equals(projectId) ? null : active);
        removeCachedProject(normalizedServerId, projectId);
        String revisionKey = projectRevisionKey(normalizedServerId, projectId);
        long currentRevision = projectRevisions.getOrDefault(revisionKey, 0L);
        long acceptedRevision = Math.max(currentRevision, revision);
        if (acceptedRevision > 0L) {
            projectRevisions.put(revisionKey, acceptedRevision);
        }
        projectRevisionIdentities.remove(revisionKey);
        updateProjectState(revisionKey, acceptedRevision, new RevisionIdentity(projectId, "", ""), false);
    }

    synchronized EpochDecision observeAuthorityEpoch(String serverId, long authorityEpoch) {
        String normalizedServerId = key(serverId);
        if (authorityEpoch < 1L) {
            return new EpochDecision(EpochOutcome.INVALID, authorityEpochs.getOrDefault(normalizedServerId, 0L), authorityEpoch);
        }
        long current = authorityEpochs.getOrDefault(normalizedServerId, 0L);
        if (authorityEpoch < current) {
            return new EpochDecision(EpochOutcome.STALE, current, authorityEpoch);
        }
        if (authorityEpoch == current) {
            return new EpochDecision(EpochOutcome.CURRENT, current, authorityEpoch);
        }
        EpochTransition transition = prepareAuthorityEpoch(normalizedServerId, authorityEpoch);
        if (transition == null || !commitAuthorityEpoch(transition)) {
            return new EpochDecision(EpochOutcome.STALE, current, authorityEpoch);
        }
        return new EpochDecision(EpochOutcome.ADVANCED, current, authorityEpoch);
    }

    synchronized EpochTransition prepareAuthorityEpoch(String serverId, long authorityEpoch) {
        String normalizedServerId = key(serverId);
        if (normalizedServerId.isBlank() || authorityEpoch < 1L) {
            return null;
        }
        long current = authorityEpochs.getOrDefault(normalizedServerId, 0L);
        if (authorityEpoch <= current) {
            return null;
        }
        String revisionPrefix = normalizedServerId + ":";
        return new EpochTransition(normalizedServerId, current, authorityEpoch,
            activeProjects.containsKey(normalizedServerId), activeProjects.get(normalizedServerId),
            draftProjects.containsKey(normalizedServerId), copyNested(draftProjects.get(normalizedServerId)),
            projectCache.containsKey(normalizedServerId), copyNested(projectCache.get(normalizedServerId)),
            projectLists.containsKey(normalizedServerId), projectLists.get(normalizedServerId),
            entries(projectRevisions, revisionPrefix), projectListRevisions.containsKey(normalizedServerId),
            projectListRevisions.get(normalizedServerId), entries(pendingDuplicateIds, revisionPrefix),
            entries(projectRevisionIdentities, revisionPrefix),
            entries(projectStates, revisionPrefix),
            projectListRevisionIdentities.containsKey(normalizedServerId)
                ? Map.of(normalizedServerId, projectListRevisionIdentities.get(normalizedServerId)) : Map.of());
    }

    synchronized boolean commitAuthorityEpoch(EpochTransition transition) {
        if (transition == null || !transition.serverId().equals(key(transition.serverId()))
            || authorityEpochs.getOrDefault(transition.serverId(), 0L) != transition.previousEpoch()) {
            return false;
        }
        try {
            clearAuthoritative(transition.serverId());
            authorityEpochs.put(transition.serverId(), transition.authorityEpoch());
            return true;
        } catch (RuntimeException | Error exception) {
            try {
                rollbackAuthorityEpoch(transition);
            } catch (RuntimeException | Error ignored) {
            }
            return false;
        }
    }

    synchronized void rollbackAuthorityEpoch(EpochTransition transition) {
        if (transition == null) {
            return;
        }
        long currentEpoch = authorityEpochs.getOrDefault(transition.serverId(), 0L);
        if (currentEpoch != transition.authorityEpoch() && currentEpoch != transition.previousEpoch()) {
            return;
        }
        restoreNested(draftProjects, transition.serverId(), transition.draftsPresent(), transition.drafts());
        restoreNested(projectCache, transition.serverId(), transition.cachePresent(), transition.cache());
        if (transition.activePresent()) {
            activeProjects.put(transition.serverId(), transition.activeProject());
        } else {
            activeProjects.remove(transition.serverId());
        }
        if (transition.projectListPresent()) {
            projectLists.put(transition.serverId(), transition.projectList());
        } else {
            projectLists.remove(transition.serverId());
        }
        restoreEntries(projectRevisions, transition.serverId() + ":", transition.projectRevisions());
        restoreEntries(projectRevisionIdentities, transition.serverId() + ":", transition.projectRevisionIdentities());
        restoreEntries(projectStates, transition.serverId() + ":", transition.projectStates());
        if (transition.projectListRevisionPresent()) {
            projectListRevisions.put(transition.serverId(), transition.projectListRevision());
        } else {
            projectListRevisions.remove(transition.serverId());
        }
        projectListRevisionIdentities.remove(transition.serverId());
        projectListRevisionIdentities.putAll(transition.projectListRevisionIdentities());
        restoreEntries(pendingDuplicateIds, transition.serverId() + ":", transition.pendingDuplicateIds());
        authorityEpochs.put(transition.serverId(), transition.previousEpoch());
    }

    long authorityEpoch(String serverId) {
        return authorityEpochs.getOrDefault(key(serverId), 0L);
    }

    void clearAuthoritative(String serverId) {
        String normalizedServerId = key(serverId);
        WorldGenProject active = activeProjects.get(normalizedServerId);
        Map<String, ProjectTransfer> drafts = draftProjects.get(normalizedServerId);
        ProjectTransfer retainedDraft = active == null || drafts == null ? null : drafts.get(active.getId());
        if (retainedDraft != null) {
            activeProjects.put(normalizedServerId, retainedDraft.source());
        } else {
            activeProjects.remove(normalizedServerId);
        }
        projectCache.remove(normalizedServerId);
        projectLists.remove(normalizedServerId);
        projectRevisions.keySet().removeIf(entry -> entry.startsWith(normalizedServerId + ":"));
        projectRevisionIdentities.keySet().removeIf(entry -> entry.startsWith(normalizedServerId + ":"));
        projectStates.keySet().removeIf(entry -> entry.startsWith(normalizedServerId + ":"));
        projectListRevisions.remove(normalizedServerId);
        projectListRevisionIdentities.remove(normalizedServerId);
        pendingDuplicateIds.keySet().removeIf(entry -> entry.startsWith(normalizedServerId + ":"));
    }

    void clearServer(String serverId) {
        clearServer(serverId, false);
    }

    void clearServer(String serverId, boolean preserveAuthorityEpoch) {
        String normalizedServerId = key(serverId);
        if (!preserveAuthorityEpoch) {
            activeProjects.remove(normalizedServerId);
            draftProjects.remove(normalizedServerId);
        }
        clearAuthoritative(normalizedServerId);
        if (!preserveAuthorityEpoch) {
            authorityEpochs.remove(normalizedServerId);
        }
        projectRevisionIdentities.keySet().removeIf(entry -> entry.startsWith(normalizedServerId + ":"));
        projectStates.keySet().removeIf(entry -> entry.startsWith(normalizedServerId + ":"));
        projectListRevisionIdentities.remove(normalizedServerId);
        pendingDuplicateIds.keySet().removeIf(key -> key.startsWith(normalizedServerId + ":"));
    }

    void setProjectList(String serverId, List<String> ids) {
        String normalizedServerId = key(serverId);
        projectLists.put(normalizedServerId, List.copyOf(ids != null ? ids : List.of()));
        projectListRevisionIdentities.remove(normalizedServerId);
    }

    boolean acceptProjectList(String serverId, List<String> ids, long revision) {
        String normalizedServerId = key(serverId);
        long current = projectListRevisions.getOrDefault(normalizedServerId, 0L);
        if (revision < current || (revision == 0L && current > 0L)) {
            return false;
        }
        List<String> normalizedIds = List.copyOf(ids != null ? ids : List.of());
        String canonicalContent = canonicalList(normalizedIds);
        if (revision == current && revision > 0L) {
            RevisionIdentity previous = projectListRevisionIdentities.get(normalizedServerId);
            return previous != null && previous.matches(normalizedServerId, canonicalContent);
        }
        projectLists.put(normalizedServerId, normalizedIds);
        if (revision > 0L) {
            projectListRevisions.put(normalizedServerId, revision);
            projectListRevisionIdentities.put(normalizedServerId,
                RevisionIdentity.of(normalizedServerId, canonicalContent));
        }
        return true;
    }

    boolean acceptProjectRevision(String serverId, String projectId, long revision) {
        if (projectId == null || projectId.isBlank()) {
            return true;
        }
        String revisionKey = projectRevisionKey(serverId, projectId);
        long current = projectRevisions.getOrDefault(revisionKey, 0L);
        if (revision < 1L) {
            return current == 0L;
        }
        if (revision < current) {
            return false;
        }
        if (revision == current && revision > 0L) {
            return true;
        }
        projectRevisions.put(revisionKey, revision);
        projectRevisionIdentities.remove(revisionKey);
        return true;
    }

    synchronized boolean acceptProjectContentRevision(String serverId, WorldGenProject project, long revision) {
        if (project == null || project.getId() == null || project.getId().isBlank()) {
            return false;
        }
        String normalizedServerId = key(serverId);
        String projectId = project.getId();
        String revisionKey = projectRevisionKey(normalizedServerId, projectId);
        long current = projectRevisions.getOrDefault(revisionKey, 0L);
        if (revision < 1L || revision < current) {
            return false;
        }
        String canonicalContent = canonicalProject(project);
        if (revision == current) {
            RevisionIdentity previous = projectRevisionIdentities.get(revisionKey);
            return previous != null && previous.matches(projectId, canonicalContent);
        }
        projectRevisions.put(revisionKey, revision);
        projectRevisionIdentities.put(revisionKey, RevisionIdentity.of(projectId, canonicalContent));
        return true;
    }

    synchronized WorldGenProject acceptAndSetActiveProject(String serverId, WorldGenProject project, long revision) {
        if (project == null || project.getId() == null || project.getId().isBlank()) {
            return null;
        }
        String normalizedServerId = key(serverId);
        String projectId = project.getId();
        String revisionKey = projectRevisionKey(normalizedServerId, projectId);
        long current = projectRevisions.getOrDefault(revisionKey, 0L);
        if (revision < 1L || revision < current) {
            return null;
        }
        String canonicalContent = canonicalProject(project);
        RevisionIdentity identity = projectRevisionIdentities.get(revisionKey);
        if (revision == current && identity != null && !identity.matches(projectId, canonicalContent)) {
            return null;
        }
        if (revision == current && identity == null && current > 0L) {
            return null;
        }
        if (revision > current) {
            identity = RevisionIdentity.of(projectId, canonicalContent);
            projectRevisions.put(revisionKey, revision);
            projectRevisionIdentities.put(revisionKey, identity);
        }
        if (identity == null) {
            identity = RevisionIdentity.of(projectId, canonicalContent);
            projectRevisionIdentities.put(revisionKey, identity);
        }
        projectContentIdentities.put(project, identity);
        cacheProject(normalizedServerId, projectId, identity, revision);
        WorldGenProject draft = draftProject(normalizedServerId, projectId);
        WorldGenProject active = draft != null ? draft : project;
        activeProjects.put(normalizedServerId, active);
        return project;
    }

    long projectRevision(String serverId, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return 0L;
        }
        return projectRevisions.getOrDefault(projectRevisionKey(serverId, projectId), 0L);
    }

    ProjectState projectState(String serverId, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return ProjectState.ABSENT;
        }
        return projectStates.getOrDefault(projectRevisionKey(serverId, projectId), ProjectState.ABSENT);
    }

    boolean sameProjectContent(WorldGenProject left, WorldGenProject right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        RevisionIdentity leftIdentity = contentIdentity(left);
        RevisionIdentity rightIdentity = contentIdentity(right);
        return leftIdentity == rightIdentity || leftIdentity.canonicalHash().equals(rightIdentity.canonicalHash())
            && leftIdentity.canonicalContent().equals(rightIdentity.canonicalContent());
    }

    boolean knownSameProjectContent(WorldGenProject left, WorldGenProject right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        RevisionIdentity leftIdentity = projectContentIdentities.get(left);
        RevisionIdentity rightIdentity = projectContentIdentities.get(right);
        return leftIdentity != null && rightIdentity != null
            && leftIdentity.canonicalHash().equals(rightIdentity.canonicalHash())
            && leftIdentity.canonicalContent().equals(rightIdentity.canonicalContent());
    }

    String knownCanonicalHash(WorldGenProject project) {
        RevisionIdentity identity = project == null ? null : projectContentIdentities.get(project);
        return identity == null ? "" : identity.canonicalHash();
    }

    void invalidateProjectContent(WorldGenProject project) {
        if (project != null) {
            projectContentIdentities.remove(project);
        }
    }

    List<String> getProjectIds(String serverId) {
        return projectLists.getOrDefault(key(serverId), List.of());
    }

    long projectListRevision(String serverId) {
        return projectListRevisions.getOrDefault(key(serverId), 0L);
    }

    boolean hasProjectList(String serverId) {
        return projectLists.containsKey(key(serverId));
    }

    void setPendingDuplicateId(String serverId, String sourceProjectId, String targetProjectId) {
        pendingDuplicateIds.put(key(serverId) + ":" + sourceProjectId, targetProjectId);
    }

    String removePendingDuplicateId(String serverId, String sourceProjectId) {
        return pendingDuplicateIds.remove(key(serverId) + ":" + sourceProjectId);
    }

    private String key(String serverId) {
        return WorldGenCatalogProjection.normalizeBaseServerId(serverId);
    }

    private WorldGenProject draftProject(String serverId, String projectId) {
        Map<String, ProjectTransfer> serverDrafts = draftProjects.get(serverId);
        ProjectTransfer transfer = serverDrafts == null ? null : serverDrafts.get(projectId);
        return transfer == null ? null : transfer.source();
    }

    private String projectRevisionKey(String serverId, String projectId) {
        return key(serverId) + ":" + projectId;
    }

    private ProjectTransfer projectTransfer(WorldGenProject project) {
        String canonicalContent = canonicalProject(project);
        RevisionIdentity identity = RevisionIdentity.of(project.getId(), canonicalContent);
        projectContentIdentities.put(project, identity);
        return new ProjectTransfer(project, project, identity);
    }

    private RevisionIdentity contentIdentity(WorldGenProject project) {
        RevisionIdentity existing = projectContentIdentities.get(project);
        if (existing != null) {
            return existing;
        }
        String canonicalContent = canonicalProject(project);
        RevisionIdentity created = RevisionIdentity.of(project.getId(), canonicalContent);
        projectContentIdentities.put(project, created);
        return created;
    }

    private void cacheProject(String serverId, String projectId, RevisionIdentity identity, long authorityRevision) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        String normalizedServerId = key(serverId);
        String revisionKey = projectRevisionKey(normalizedServerId, projectId);
        ProjectState state = updateProjectState(revisionKey, authorityRevision, identity, true);
        projectCache.computeIfAbsent(normalizedServerId, ignored -> BrowserSafeState.map())
            .put(projectId, new ProjectSnapshot(identity, state));
    }

    private ProjectState updateProjectState(String revisionKey, long authorityRevision, RevisionIdentity identity,
                                            boolean present) {
        ProjectState current = projectStates.get(revisionKey);
        if (current != null && current.authorityRevision() == authorityRevision && current.present() == present
            && current.canonicalHash().equals(identity.canonicalHash())
            && current.canonicalContent().equals(identity.canonicalContent())) {
            return current;
        }
        ProjectState next = new ProjectState(projectStateSequence.incrementAndGet(), authorityRevision,
            identity.canonicalHash(), identity.canonicalContent(), present);
        projectStates.put(revisionKey, next);
        return next;
    }

    private static <V> Map<String, V> copyNested(Map<String, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static <V> Map<String, V> entries(Map<String, V> values, String prefix) {
        Map<String, V> result = new HashMap<>();
        values.forEach((entry, value) -> {
            if (entry.startsWith(prefix)) {
                result.put(entry, value);
            }
        });
        return Map.copyOf(result);
    }

    private static String canonicalProject(WorldGenProject project) {
        return canonicalJson(WorldGenSerializer.serializeProject(project));
    }

    private static String canonicalList(List<String> ids) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append('"').append(escapeJson(ids.get(index))).append('"');
        }
        return result.append(']').toString();
    }

    private static String canonicalJson(String value) {
        if (value == null || value.isBlank()) {
            return "null";
        }
        try {
            return canonicalElement(JsonParser.parseString(value));
        } catch (RuntimeException exception) {
            return value.trim();
        }
    }

    private static String canonicalElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            Map<String, JsonElement> sorted = new TreeMap<>();
            element.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append('"').append(escapeJson(entry.getKey())).append("\":")
                    .append(canonicalElement(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(canonicalElement(child));
            }
            return result.append(']').toString();
        }
        return element.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t");
    }

    private static <V> void restoreNested(Map<String, Map<String, V>> values, String key,
                                          boolean present, Map<String, V> snapshot) {
        if (present) {
            values.put(key, new HashMap<>(snapshot));
        } else {
            values.remove(key);
        }
    }

    private static <V> void restoreEntries(Map<String, V> values, String prefix, Map<String, V> snapshot) {
        values.keySet().removeIf(entry -> entry.startsWith(prefix));
        values.putAll(snapshot);
    }

    record EpochTransition(String serverId, long previousEpoch, long authorityEpoch,
                           boolean activePresent, WorldGenProject activeProject,
                           boolean draftsPresent, Map<String, ProjectTransfer> drafts,
                           boolean cachePresent, Map<String, ProjectSnapshot> cache,
                           boolean projectListPresent, List<String> projectList,
                           Map<String, Long> projectRevisions,
                           boolean projectListRevisionPresent, Long projectListRevision,
                           Map<String, String> pendingDuplicateIds,
                           Map<String, RevisionIdentity> projectRevisionIdentities,
                           Map<String, ProjectState> projectStates,
                           Map<String, RevisionIdentity> projectListRevisionIdentities) {
    }

    record ProjectTransfer(WorldGenProject source, WorldGenProject project, RevisionIdentity identity) {
        ProjectTransfer {
            source = source == null ? project : source;
            identity = identity == null ? new RevisionIdentity("", "", "") : identity;
        }
    }

    record ProjectSnapshot(RevisionIdentity identity, ProjectState state) {
        ProjectSnapshot {
            identity = identity == null ? new RevisionIdentity("", "", "") : identity;
            state = state == null ? ProjectState.ABSENT : state;
        }

        String canonicalContent() {
            return identity.canonicalContent();
        }
    }

    record ProjectState(long stateRevision, long authorityRevision, String canonicalHash, String canonicalContent,
                        boolean present) {
        private static final ProjectState ABSENT = new ProjectState(0L, 0L, "", "", false);

        ProjectState {
            canonicalHash = canonicalHash == null ? "" : canonicalHash;
            canonicalContent = canonicalContent == null ? "" : canonicalContent;
        }
    }

    record RevisionIdentity(String identity, String canonicalHash, String canonicalContent) {
        RevisionIdentity {
            identity = identity == null ? "" : identity;
            canonicalHash = canonicalHash == null ? "" : canonicalHash;
            canonicalContent = canonicalContent == null ? "" : canonicalContent;
        }

        private static RevisionIdentity of(String identity, String canonicalContent) {
            return new RevisionIdentity(identity, sha256(canonicalContent), canonicalContent);
        }

        private boolean matches(String expectedIdentity, String expectedContent) {
            return identity.equals(expectedIdentity) && canonicalHash.equals(sha256(expectedContent))
                && canonicalContent.equals(expectedContent);
        }

        private static String sha256(String value) {
            return CanonicalDigests.hex(CanonicalDigests.sha256(value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static final class ProjectIdentityCache {
        private final ReferenceQueue<WorldGenProject> collected = new ReferenceQueue<>();
        private final Map<ProjectIdentityReference, RevisionIdentity> identities = new HashMap<>();

        private synchronized RevisionIdentity get(WorldGenProject project) {
            drain();
            return identities.get(new ProjectIdentityReference(project));
        }

        private synchronized void put(WorldGenProject project, RevisionIdentity identity) {
            drain();
            identities.put(new ProjectIdentityReference(project, collected), identity);
        }

        private synchronized void remove(WorldGenProject project) {
            drain();
            identities.remove(new ProjectIdentityReference(project));
        }

        private void drain() {
            ProjectIdentityReference reference;
            while ((reference = (ProjectIdentityReference) collected.poll()) != null) {
                identities.remove(reference);
            }
        }
    }

    private static final class ProjectIdentityReference extends WeakReference<WorldGenProject> {
        private final int identityHash;

        private ProjectIdentityReference(WorldGenProject project) {
            super(project);
            identityHash = System.identityHashCode(project);
        }

        private ProjectIdentityReference(WorldGenProject project, ReferenceQueue<WorldGenProject> collected) {
            super(project, collected);
            identityHash = System.identityHashCode(project);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            return value instanceof ProjectIdentityReference reference && get() != null && get() == reference.get();
        }
    }

    enum EpochOutcome {
        CURRENT,
        ADVANCED,
        STALE,
        INVALID
    }

    record EpochDecision(EpochOutcome outcome, long previousEpoch, long authorityEpoch) {
        boolean accepted() {
            return outcome == EpochOutcome.CURRENT || outcome == EpochOutcome.ADVANCED;
        }

        boolean advanced() {
            return outcome == EpochOutcome.ADVANCED;
        }
    }
}
