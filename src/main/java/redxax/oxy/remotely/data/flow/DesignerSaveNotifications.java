package redxax.oxy.remotely.data.flow;

import java.time.Duration;
import redxax.oxy.remotely.util.BrowserWork;
import java.util.Deque;
import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.flow.ui.GraphEditorScreen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.UUID;

public final class DesignerSaveNotifications {
    private static final Object pendingLock = new Object();
    private static final Map<String, PendingSave> pendingByKey = BrowserSafeState.map();
    private static final Map<String, Deque<String>> pendingKeysByResource = BrowserSafeState.map();
    private static final Map<String, String> pendingKeyByRequest = BrowserSafeState.map();
    private static final Map<String, String> pendingKeyByMutation = BrowserSafeState.map();
    private static final Map<String, Long> recentlyHandledErrors = BrowserSafeState.map();
    private static final Set<String> suppressedRequestIds = BrowserSafeState.set();
    private static final ThreadLocal<Integer> automaticNotificationSuppression = ThreadLocal.withInitial(() -> 0);
    private static final BrowserSafeState.LongValue pendingSequence = new BrowserSafeState.LongValue();
    private static final long ERROR_DEDUPLICATION_MS = 3000L;
    private static final long SAVE_TIMEOUT_SECONDS = 30L;

    private DesignerSaveNotifications() {
    }

    public static void start(String serverId, ReSyncResourceType type, String id, String name) {
        begin(serverId, type, id, name);
    }

    public static SaveTicket startExact(String serverId, ReSyncResourceType type, String id, String name) {
        PendingSave pending = beginPending(serverId, type, id, name, false, null, null);
        return pending == null ? null : new SaveTicket(pending);
    }

    public static SaveTicket startResumableExact(String serverId, ReSyncResourceType type, String id, String name,
                                                 UUID requestId, UUID mutationId) {
        PendingSave pending = beginPending(serverId, type, id, name, true, requestId, mutationId, true);
        return pending == null ? null : new SaveTicket(pending);
    }

    public static SaveTicket startSilentResumableExact(String serverId, ReSyncResourceType type, String id, String name,
                                                       UUID requestId, UUID mutationId) {
        PendingSave pending = beginPending(serverId, type, id, name, true, requestId, mutationId, false);
        return pending == null ? null : new SaveTicket(pending);
    }

    public static Async<Boolean> track(String serverId, ReSyncResourceType type, String id, String name) {
        return begin(serverId, type, id, name);
    }

    public static <T> T withoutAutomaticNotifications(Supplier<T> action) {
        automaticNotificationSuppression.set(automaticNotificationSuppression.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = automaticNotificationSuppression.get() - 1;
            if (depth == 0) automaticNotificationSuppression.remove();
            else automaticNotificationSuppression.set(depth);
        }
    }

    public static boolean consumeAutomaticNotificationSuppression(String requestId) {
        return requestId != null && !requestId.isBlank() && suppressedRequestIds.remove(requestId);
    }

    private static Async<Boolean> begin(String serverId, ReSyncResourceType type, String id, String name) {
        PendingSave pending = beginPending(serverId, type, id, name, false, null, null);
        return pending == null ? Async.completed(false) : pending.completion;
    }

    private static PendingSave beginPending(String serverId, ReSyncResourceType type, String id, String name,
                                            boolean resumable, UUID requestId, UUID mutationId) {
        return beginPending(serverId, type, id, name, resumable, requestId, mutationId, true);
    }

    private static PendingSave beginPending(String serverId, ReSyncResourceType type, String id, String name,
                                            boolean resumable, UUID requestId, UUID mutationId, boolean visible) {
        if (!shouldTrack(serverId, type, id)) {
            return null;
        }
        String resourceKey = key(serverId, type, id);
        long sequence = pendingSequence.incrementAndGet();
        String pendingKey = pendingKey(resourceKey, sequence);
        PendingSave pending = new PendingSave(serverId, type, id, resourceKey, sequence, visible);
        synchronized (pendingLock) {
            pendingByKey.put(pendingKey, pending);
            pendingKeysByResource.computeIfAbsent(resourceKey, ignored -> BrowserSafeState.deque()).add(pendingKey);
            if (requestId != null) {
                String requestKey = requestId.toString();
                if (pendingKeyByRequest.putIfAbsent(requestKey, pendingKey) != null) {
                    pendingByKey.remove(pendingKey);
                    removePendingKey(pendingKey, resourceKey);
                    return null;
                }
                pending.requestId = requestKey;
            }
            if (mutationId != null) {
                String mutationKey = mutationKey(serverId, type, id, mutationId.toString());
                if (pendingKeyByMutation.putIfAbsent(mutationKey, pendingKey) != null) {
                    pendingByKey.remove(pendingKey);
                    removePendingKey(pendingKey, resourceKey);
                    if (requestId != null) {
                        pendingKeyByRequest.remove(requestId.toString(), pendingKey);
                    }
                    return null;
                }
                pending.mutationId = mutationId.toString();
            }
        }
        pending.resumable = resumable;
        pending.name = cleanName(name, id);
        GraphEditorScreen studioScreen = GraphEditorScreen.getStudioScreen(serverId);
        if (studioScreen != null) {
            studioScreen.markStudioDocumentSaving(type.typeId(), id, sequence);
        }
        long timeoutToken = pending.nextTimeoutToken();
        BrowserWork.schedule(Duration.ofSeconds(SAVE_TIMEOUT_SECONDS), () -> timeout(pendingKey, timeoutToken));
        trace(pending, "notification_started", "pending");
        ScreenManager.getInstance().execute(pending::showSaving);
        return pending;
    }

    public static void attachRequestId(String serverId, ReSyncResourceType type, String id, String requestId) {
        if (!shouldTrack(serverId, type, id) || requestId == null || requestId.isBlank()) {
            return;
        }
        if (automaticNotificationSuppression.get() > 0 && suppressedRequestIds.add(requestId)) {
            BrowserWork.schedule(Duration.ofSeconds(SAVE_TIMEOUT_SECONDS), () -> suppressedRequestIds.remove(requestId));
        }
        synchronized (pendingLock) {
            if (pendingKeyByRequest.containsKey(requestId)) {
                return;
            }
            String resourceKey = key(serverId, type, id);
            String pendingKey = findUnboundPendingKey(resourceKey);
            if (pendingKey == null) {
                return;
            }
            PendingSave pending = pendingByKey.get(pendingKey);
            if (pending == null || pending.finished) {
                return;
            }
            pending.requestId = requestId;
            pendingKeyByRequest.put(requestId, pendingKey);
        }
    }

    public static boolean attachRequestId(SaveTicket ticket, String requestId) {
        if (ticket == null || requestId == null || requestId.isBlank()) {
            return false;
        }
        boolean suppress = automaticNotificationSuppression.get() > 0;
        synchronized (pendingLock) {
            if (pendingKeyByRequest.containsKey(requestId)) {
                return false;
            }
            String exactKey = pendingKey(key(ticket.serverId(), ticket.type(), ticket.id()), ticket.sequence());
            PendingSave pending = pendingByKey.get(exactKey);
            if (pending == null || pending.finished || pending.requestId != null && !pending.requestId.isBlank()) {
                return false;
            }
            pending.requestId = requestId;
            pendingKeyByRequest.put(requestId, exactKey);
            if (suppress && suppressedRequestIds.add(requestId)) {
                BrowserWork.schedule(Duration.ofSeconds(SAVE_TIMEOUT_SECONDS), () -> suppressedRequestIds.remove(requestId));
            }
            trace(pending, "notification_request_attached", "pending");
            return true;
        }
    }

    public static void attachMutationId(String serverId, ReSyncResourceType type, String id, String mutationId) {
        if (!shouldTrack(serverId, type, id) || mutationId == null || mutationId.isBlank()) {
            return;
        }
        synchronized (pendingLock) {
            String mutationKey = mutationKey(serverId, type, id, mutationId);
            String resourceKey = key(serverId, type, id);
            String pendingKey = findUnboundPendingKey(resourceKey);
            if (pendingKey == null) {
                return;
            }
            PendingSave pending = pendingByKey.get(pendingKey);
            if (pending == null || pending.finished) {
                return;
            }
            if (pending.mutationId != null && !mutationId.equals(pending.mutationId)) {
                return;
            }
            String previous = pendingKeyByMutation.putIfAbsent(mutationKey, pendingKey);
            if (previous != null && !previous.equals(pendingKey)) {
                return;
            }
            pending.mutationId = mutationId;
        }
    }

    public static boolean attachMutationId(SaveTicket ticket, String mutationId) {
        if (ticket == null || mutationId == null || mutationId.isBlank()) {
            return false;
        }
        synchronized (pendingLock) {
            String exactKey = pendingKey(key(ticket.serverId(), ticket.type(), ticket.id()), ticket.sequence());
            PendingSave pending = pendingByKey.get(exactKey);
            if (pending == null || pending.finished || pending.mutationId != null && !pending.mutationId.isBlank()) {
                return false;
            }
            String mutationKey = mutationKey(ticket.serverId(), ticket.type(), ticket.id(), mutationId);
            String previous = pendingKeyByMutation.putIfAbsent(mutationKey, exactKey);
            if (previous != null && !previous.equals(exactKey)) {
                return false;
            }
            pending.mutationId = mutationId;
            trace(pending, "notification_mutation_attached", "pending");
            return true;
        }
    }

    public static boolean isTrackedRequest(String serverId, ReSyncResourceType type, String id, String requestId) {
        if (!shouldTrack(serverId, type, id) || requestId == null || requestId.isBlank()) {
            return false;
        }
        synchronized (pendingLock) {
            String pendingKey = pendingKeyByRequest.get(requestId);
            PendingSave pending = pendingKey == null ? null : pendingByKey.get(pendingKey);
            return pending != null && !pending.finished && requestId.equals(pending.requestId)
                && serverId.equals(pending.serverId) && type == pending.type && id.equals(pending.id);
        }
    }

    public static boolean isResumableRequest(String serverId, String requestId) {
        if (serverId == null || serverId.isBlank() || requestId == null || requestId.isBlank()) {
            return false;
        }
        synchronized (pendingLock) {
            String pendingKey = pendingKeyByRequest.get(requestId);
            PendingSave pending = pendingKey == null ? null : pendingByKey.get(pendingKey);
            return pending != null && !pending.finished && pending.resumable && serverId.equals(pending.serverId)
                && requestId.equals(pending.requestId);
        }
    }

    public static boolean isCurrentSequence(String serverId, ReSyncResourceType type, String id, long sequence) {
        if (!shouldTrack(serverId, type, id) || sequence < 1L) {
            return false;
        }
        synchronized (pendingLock) {
            return !hasNewerPending(key(serverId, type, id), sequence);
        }
    }

    public static SaveTarget complete(String serverId, ReSyncResourceType type, String id, String requestId) {
        synchronized (pendingLock) {
            if (!isTrackedRequest(serverId, type, id, requestId)) {
                return null;
            }
            String pendingKey = pendingKeyByRequest.get(requestId);
            return pendingKey == null ? null
                : finishExact(pendingKey, type.displayName() + " Saved", "ID: " + id, Notification.Type.SUCCESS, null);
        }
    }

    public static SaveTarget complete(String serverId, ReSyncResourceType type, String id, String requestId,
                                      boolean currentAtFinish) {
        synchronized (pendingLock) {
            if (!isTrackedRequest(serverId, type, id, requestId)) {
                return null;
            }
            String pendingKey = pendingKeyByRequest.get(requestId);
            return pendingKey == null ? null
                : finishExact(pendingKey, type.displayName() + " Saved", "ID: " + id, Notification.Type.SUCCESS, null,
                    currentAtFinish);
        }
    }

    public static SaveTarget completeMutation(String serverId, ReSyncResourceType type, String id, String mutationId) {
        synchronized (pendingLock) {
            PendingSave pending = pendingForMutation(serverId, type, id, mutationId);
            String pendingKey = pendingKeyByMutation.get(mutationKey(serverId, type, id, mutationId));
            if (pending == null || pendingKey == null) {
                return null;
            }
            return finishExact(pendingKey,
                type.displayName() + " Saved", "ID: " + id, Notification.Type.SUCCESS, null);
        }
    }

    public static SaveTarget failResource(String serverId, ReSyncResourceType type, String id, String message) {
        synchronized (pendingLock) {
            String resourceKey = key(serverId, type, id);
            String pendingKey = firstPendingKey(resourceKey);
            return finishExact(pendingKey, type.displayName() + " Save Failed", cleanMessage(message),
                Notification.Type.ERROR, cleanMessage(message));
        }
    }

    public static SaveTarget failExact(SaveTicket ticket, String message) {
        if (ticket == null) {
            return null;
        }
        synchronized (pendingLock) {
            String resourceKey = key(ticket.serverId(), ticket.type(), ticket.id());
            String exactKey = pendingKey(resourceKey, ticket.sequence());
            PendingSave pending = pendingByKey.get(exactKey);
            if (pending == null || pending.finished || !ticket.serverId().equals(pending.serverId)
                || ticket.type() != pending.type || !ticket.id().equals(pending.id)
                || ticket.sequence() != pending.sequence) {
                return null;
            }
            return finishExact(exactKey, ticket.type().displayName() + " Save Failed", cleanMessage(message),
                Notification.Type.ERROR, cleanMessage(message));
        }
    }

    public static boolean detachResumable(SaveTicket ticket) {
        if (ticket == null) {
            return false;
        }
        synchronized (pendingLock) {
            String resourceKey = key(ticket.serverId(), ticket.type(), ticket.id());
            String exactKey = pendingKey(resourceKey, ticket.sequence());
            PendingSave pending = pendingByKey.get(exactKey);
            if (pending == null || pending.finished || !pending.resumable || !ticket.serverId().equals(pending.serverId)
                || ticket.type() != pending.type || !ticket.id().equals(pending.id)
                || ticket.sequence() != pending.sequence) {
                return false;
            }
            pendingByKey.remove(exactKey, pending);
            removePendingKey(exactKey, pending.resourceKey);
            pendingKeyByRequest.values().removeIf(exactKey::equals);
            pendingKeyByMutation.values().removeIf(exactKey::equals);
            pending.finished = true;
            return true;
        }
    }

    public static String detachResumableMutation(String serverId, ReSyncResourceType type, String id,
                                                 String mutationId) {
        if (!shouldTrack(serverId, type, id) || mutationId == null || mutationId.isBlank()) {
            return null;
        }
        synchronized (pendingLock) {
            PendingSave pending = pendingForMutation(serverId, type, id, mutationId);
            String mutationKey = mutationKey(serverId, type, id, mutationId);
            String exactKey = pendingKeyByMutation.get(mutationKey);
            if (pending == null || exactKey == null || pending.finished || !pending.resumable
                || pending.requestId == null || pending.requestId.isBlank()) {
                return null;
            }
            pendingByKey.remove(exactKey, pending);
            removePendingKey(exactKey, pending.resourceKey);
            pendingKeyByRequest.values().removeIf(exactKey::equals);
            pendingKeyByMutation.values().removeIf(exactKey::equals);
            pending.finished = true;
            return pending.requestId;
        }
    }

    public static boolean isPending(SaveTicket ticket) {
        if (ticket == null) {
            return false;
        }
        synchronized (pendingLock) {
            String exactKey = pendingKey(key(ticket.serverId(), ticket.type(), ticket.id()), ticket.sequence());
            PendingSave pending = pendingByKey.get(exactKey);
            return pending != null && !pending.finished && ticket.serverId().equals(pending.serverId)
                && ticket.type() == pending.type && ticket.id().equals(pending.id)
                && ticket.sequence() == pending.sequence;
        }
    }

    public static boolean isResumable(SaveTicket ticket) {
        if (ticket == null) {
            return false;
        }
        synchronized (pendingLock) {
            String exactKey = pendingKey(key(ticket.serverId(), ticket.type(), ticket.id()), ticket.sequence());
            PendingSave pending = pendingByKey.get(exactKey);
            return pending != null && !pending.finished && pending.resumable;
        }
    }

    public static SaveTarget failRequest(String serverId, String requestId, String message) {
        if (serverId == null || serverId.isBlank() || requestId == null || requestId.isBlank()) {
            return null;
        }
        synchronized (pendingLock) {
            String key = pendingKeyByRequest.get(requestId);
            if (key == null) {
                return null;
            }
            PendingSave pending = pendingByKey.get(key);
            if (pending == null || pending.finished || !requestId.equals(pending.requestId)
                || !serverId.equals(pending.serverId)) {
                return null;
            }
            String title = pending.type.displayName() + " Save Failed";
            return finishExact(key, title, cleanMessage(message), Notification.Type.ERROR, cleanMessage(message));
        }
    }

    public static SaveTarget failMutation(String serverId, ReSyncResourceType type, String id, String mutationId,
                                          String message) {
        synchronized (pendingLock) {
            PendingSave pending = pendingForMutation(serverId, type, id, mutationId);
            if (pending == null) {
                return null;
            }
            String pendingKey = pendingKeyByMutation.get(mutationKey(serverId, type, id, mutationId));
            if (pendingKey == null) {
                return null;
            }
            return finishExact(pendingKey, type.displayName() + " Save Failed", cleanMessage(message), Notification.Type.ERROR,
                cleanMessage(message));
        }
    }

    public static List<String> pendingRequestIdsForServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        synchronized (pendingLock) {
            return pendingByKey.values().stream()
                .filter(pending -> pending != null && !pending.finished && serverId.equals(pending.serverId)
                    && pending.requestId != null && !pending.requestId.isBlank())
                .map(pending -> pending.requestId)
                .distinct()
                .toList();
        }
    }

    public static List<SaveTarget> failTrackedForServer(String serverId, String message) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        List<String> requestIds = pendingKeyByRequest.entrySet().stream()
            .filter(entry -> {
                PendingSave pending = pendingByKey.get(entry.getValue());
                return pending != null && !pending.finished && serverId.equals(pending.serverId);
            })
            .map(Map.Entry::getKey)
            .toList();
        List<SaveTarget> failed = new ArrayList<>();
        for (String requestId : requestIds) {
            SaveTarget target = failRequest(serverId, requestId, message);
            if (target != null) {
                failed.add(target);
            }
        }
        return List.copyOf(failed);
    }

    public static SaveTarget failAnyForServer(String serverId, String message) {
        return failAnyForServer(serverId, "", message);
    }

    public static SaveTarget failAnyForServer(String serverId, String title, String message) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        Map.Entry<String, PendingSave> pending = pendingByKey.entrySet().stream()
            .filter(entry -> entry.getValue() != null && serverId.equals(entry.getValue().serverId) && !entry.getValue().finished)
            .max(Comparator.comparingLong(entry -> entry.getValue().updatedAt))
            .orElse(null);
        if (pending == null || pending.getValue() == null) {
            return null;
        }
        PendingSave value = pending.getValue();
        String notificationTitle = title != null && !title.isBlank() ? title : value.type.displayName() + " Save Failed";
        return finishExact(pending.getKey(), notificationTitle, cleanMessage(message), Notification.Type.ERROR,
            cleanMessage(message));
    }

    public static List<SaveTarget> failAllForServer(String serverId, String message) {
        return failAllForServer(serverId, "", message);
    }

    public static List<SaveTarget> failAllForServer(String serverId, String title, String message) {
        return failAllForServer(serverId, title, message, true);
    }

    public static List<SaveTarget> failNonResumableForServer(String serverId, String message) {
        return failAllForServer(serverId, "", message, false);
    }

    private static List<SaveTarget> failAllForServer(String serverId, String title, String message,
                                                     boolean includeResumable) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        List<SaveTarget> failed = new ArrayList<>();
        while (true) {
            List<Map.Entry<String, PendingSave>> pending = pendingByKey.entrySet().stream()
                .filter(entry -> entry.getValue() != null && serverId.equals(entry.getValue().serverId)
                    && !entry.getValue().finished && (includeResumable || !entry.getValue().resumable))
                .sorted(Comparator.comparingLong(entry -> entry.getValue().sequence))
                .toList();
            if (pending.isEmpty()) {
                return List.copyOf(failed);
            }
            boolean finished = false;
            for (Map.Entry<String, PendingSave> entry : pending) {
                PendingSave value = pendingByKey.get(entry.getKey());
                if (value == null || value.finished || !serverId.equals(value.serverId)) {
                    continue;
                }
                String notificationTitle = title != null && !title.isBlank()
                    ? title : value.type.displayName() + " Save Failed";
                SaveTarget target = finishExact(entry.getKey(), notificationTitle, cleanMessage(message),
                    Notification.Type.ERROR, cleanMessage(message));
                if (target != null) {
                    failed.add(target);
                    finished = true;
                }
            }
            if (!finished) {
                return List.copyOf(failed);
            }
        }
    }

    public static boolean consumeRecentError(String serverId, String message) {
        String key = errorKey(serverId, message);
        Long handledAt = recentlyHandledErrors.remove(key);
        return handledAt != null && System.currentTimeMillis() - handledAt <= ERROR_DEDUPLICATION_MS;
    }

    private static SaveTarget finishExact(String key, String title, String description, Notification.Type type,
                                          String handledError) {
        return finishExact(key, title, description, type, handledError, null);
    }

    private static SaveTarget finishExact(String key, String title, String description, Notification.Type type,
                                          String handledError, Boolean currentAtFinishOverride) {
        if (key == null || key.isBlank()) {
            return null;
        }
        synchronized (pendingLock) {
            PendingSave pending = pendingByKey.remove(key);
            if (pending == null) {
                return null;
            }
            boolean shouldUpdateResourceState = currentAtFinishOverride == null
                ? !hasNewerPending(pending.resourceKey, pending.sequence)
                : currentAtFinishOverride && !hasNewerPending(pending.resourceKey, pending.sequence);
            pending.shouldUpdateResourceState = shouldUpdateResourceState;
            removePendingKey(key, pending.resourceKey);
            String finishedKey = key;
            pendingKeyByRequest.values().removeIf(finishedKey::equals);
            pendingKeyByMutation.values().removeIf(finishedKey::equals);
            pending.finished = true;
            pending.finalTitle = title;
            pending.finalDescription = description;
            pending.finalType = type;
            pending.completion.complete(type == Notification.Type.SUCCESS);
            if (handledError != null && !handledError.isBlank()) {
                recentlyHandledErrors.put(errorKey(pending.serverId, handledError), System.currentTimeMillis());
            }
            trace(pending, "notification_settled", type == Notification.Type.SUCCESS ? "saved" : "failed");
            ScreenManager.getInstance().execute(pending::showFinished);
            return new SaveTarget(pending.type, pending.id, shouldUpdateResourceState, pending.sequence);
        }
    }

    private static void timeout(String key, long timeoutToken) {
        PendingSave pending = pendingByKey.get(key);
        if (pending != null && !pending.finished && pending.timeoutToken == timeoutToken && pending.resumable) {
            long nextTimeoutToken;
            synchronized (pendingLock) {
                if (pending.finished || pending.timeoutToken != timeoutToken) {
                    return;
                }
                nextTimeoutToken = pending.nextTimeoutToken();
                if (!pending.timeoutLogged) {
                    pending.timeoutLogged = true;
                    trace(pending, "notification_timeout_deferred", "resumable");
                }
            }
            BrowserWork.schedule(Duration.ofSeconds(SAVE_TIMEOUT_SECONDS), () -> timeout(key, nextTimeoutToken));
            return;
        }
        if (pending != null && !pending.finished && pending.timeoutToken == timeoutToken) {
            SaveTarget target = finishExact(key, pending.type.displayName() + " Save Failed", "Save Timed Out",
                Notification.Type.ERROR, "Save Timed Out");
            FlowManager manager = FlowManager.getInstance();
            if (target != null && target.shouldUpdateResourceState() && manager != null) {
                manager.markResourceSaveFailed(pending.serverId, target.type(), target.id());
            }
        }
    }

    private static boolean shouldTrack(String serverId, ReSyncResourceType type, String id) {
        return serverId != null && !serverId.isBlank()
            && type != null
            && id != null
            && !id.isBlank();
    }

    private static String key(String serverId, ReSyncResourceType type, String id) {
        return serverId + ":" + type.typeId() + ":" + id;
    }

    private static String mutationKey(String serverId, ReSyncResourceType type, String id, String mutationId) {
        return key(serverId, type, id) + "\n" + mutationId;
    }

    private static PendingSave pendingForMutation(String serverId, ReSyncResourceType type, String id,
                                                  String mutationId) {
        if (!shouldTrack(serverId, type, id) || mutationId == null || mutationId.isBlank()) {
            return null;
        }
        String pendingKey = pendingKeyByMutation.get(mutationKey(serverId, type, id, mutationId));
        PendingSave pending = pendingKey == null ? null : pendingByKey.get(pendingKey);
        return pending != null && !pending.finished && mutationId.equals(pending.mutationId)
            && serverId.equals(pending.serverId) && type == pending.type && id.equals(pending.id) ? pending : null;
    }

    private static String pendingKey(String resourceKey, long sequence) {
        return resourceKey + "\n" + sequence;
    }

    private static String findUnboundPendingKey(String resourceKey) {
        Deque<String> keys = pendingKeysByResource.get(resourceKey);
        if (keys == null) {
            return null;
        }
        String candidate = null;
        long candidateSequence = Long.MAX_VALUE;
        for (String key : keys) {
            PendingSave pending = pendingByKey.get(key);
            if (pending != null && !pending.finished
                && (pending.requestId == null || pending.requestId.isBlank())
                && (pending.mutationId == null || pending.mutationId.isBlank())) {
                if (pending.sequence < candidateSequence) {
                    candidate = key;
                    candidateSequence = pending.sequence;
                }
            }
        }
        return candidate;
    }

    private static String firstPendingKey(String resourceKey) {
        Deque<String> keys = pendingKeysByResource.get(resourceKey);
        if (keys == null) {
            return null;
        }
        while (true) {
            String key = keys.peekFirst();
            if (key == null) {
                pendingKeysByResource.remove(resourceKey, keys);
                return null;
            }
            PendingSave pending = pendingByKey.get(key);
            if (pending != null && !pending.finished) {
                return key;
            }
            keys.pollFirst();
        }
    }

    private static void removePendingKey(String pendingKey, String resourceKey) {
        Deque<String> keys = pendingKeysByResource.get(resourceKey);
        if (keys == null) {
            return;
        }
        keys.remove(pendingKey);
        if (keys.isEmpty()) {
            pendingKeysByResource.remove(resourceKey, keys);
        }
    }

    private static boolean hasNewerPending(String resourceKey, long sequence) {
        Deque<String> keys = pendingKeysByResource.get(resourceKey);
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            PendingSave pending = pendingByKey.get(key);
            if (pending != null && !pending.finished && pending.sequence > sequence) {
                return true;
            }
        }
        return false;
    }

    private static String errorKey(String serverId, String message) {
        return (serverId == null ? "" : serverId) + "\n" + cleanMessage(message);
    }

    private static String cleanName(String name, String fallback) {
        String value = name == null ? "" : name.trim();
        return value.isBlank() ? fallback : value;
    }

    private static String cleanMessage(String message) {
        String value = message == null ? "" : message.trim();
        return value.isBlank() ? "Failed" : value;
    }

    private static void trace(PendingSave pending, String stage, String outcome) {
        if (pending == null) {
            return;
        }
        ReSyncFlowClient.traceLifecycle(pending.serverId, stage,
            "serverId", pending.serverId,
            "type", pending.type.typeId(),
            "id", bounded(pending.id),
            "requestId", bounded(pending.requestId),
            "mutationId", bounded(pending.mutationId),
            "sequence", pending.sequence,
            "outcome", outcome,
            "elapsedMs", BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - pending.startedAtNanos)));
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    public record SaveTarget(ReSyncResourceType type, String id, boolean shouldUpdateResourceState, long sequence) {
    }

    public static final class SaveTicket {
        private final PendingSave pending;

        private SaveTicket(PendingSave pending) {
            this.pending = pending;
        }

        public String serverId() {
            return pending.serverId;
        }

        public ReSyncResourceType type() {
            return pending.type;
        }

        public String id() {
            return pending.id;
        }

        public long sequence() {
            return pending.sequence;
        }

        public String requestId() {
            return pending.requestId;
        }

        public String mutationId() {
            return pending.mutationId;
        }

        public void whenFinished(BiConsumer<Boolean, Boolean> handler) {
            if (handler != null) {
                pending.completion.thenAccept(saved -> handler.accept(saved, pending.shouldUpdateResourceState));
            }
        }
    }

    private static final class PendingSave {
        private final String serverId;
        private final ReSyncResourceType type;
        private final String id;
        private final String resourceKey;
        private final long sequence;
        private final boolean visible;
        private final long startedAtNanos = System.nanoTime();
        private final Async<Boolean> completion = Async.pending();
        private long updatedAt = System.currentTimeMillis();
        private long timeoutToken;
        private String requestId;
        private String mutationId;
        private String name;
        private Notification notification;
        private boolean finished;
        private boolean resumable;
        private boolean timeoutLogged;
        private String finalTitle;
        private String finalDescription;
        private Notification.Type finalType;
        private boolean shouldUpdateResourceState;

        private PendingSave(String serverId, ReSyncResourceType type, String id, String resourceKey, long sequence,
                            boolean visible) {
            this.serverId = serverId;
            this.type = type;
            this.id = id;
            this.resourceKey = resourceKey;
            this.sequence = sequence;
            this.visible = visible;
            this.name = id;
        }

        private long nextTimeoutToken() {
            updatedAt = System.currentTimeMillis();
            return ++timeoutToken;
        }

        private void showSaving() {
            if (!visible) {
                return;
            }
            if (finished) {
                showFinished();
                return;
            }
            String description = name == null || name.isBlank() ? id : name;
            if (notification == null) {
                notification = new Notification.Builder()
                    .message("Saving " + type.displayName())
                    .description(description)
                    .type(Notification.Type.INFO)
                    .loading(true)
                    .autoSlideOut(false)
                    .build();
                return;
            }
            notification.update()
                .message("Saving " + type.displayName())
                .description(description)
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false);
        }

        private void showFinished() {
            if (!visible) {
                return;
            }
            if (notification == null) {
                notification = new Notification.Builder()
                    .message(finalTitle)
                    .description(finalDescription)
                    .type(finalType)
                    .build();
                return;
            }
            notification.change(finalTitle, finalDescription, finalType, null);
        }
    }
}
