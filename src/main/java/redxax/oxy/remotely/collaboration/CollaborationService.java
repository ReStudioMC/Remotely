package redxax.oxy.remotely.collaboration;

import redxax.oxy.remotely.util.BrowserSafeState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class CollaborationService {
    private static final BrowserSafeState.LongValue ACTIVITY_SEQUENCE = new BrowserSafeState.LongValue();
    private static final Channel DISCONNECTED = new Channel() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void publishPresence(PresenceUpdate update) {
        }

        @Override
        public void publishMessage(MessageDraft message) {
        }
    };

    private final Map<String, ResourceChange> changes = BrowserSafeState.map();
    private final Map<String, CachedMessage> messages = BrowserSafeState.map();
    private final Set<Consumer<Message>> messageListeners = BrowserSafeState.set();
    private final Set<Consumer<List<Presence>>> presenceListeners = BrowserSafeState.set();
    private final Set<Consumer<ResourceChange>> resourceChangeListeners = BrowserSafeState.set();
    private final Set<Runnable> activityListeners = BrowserSafeState.set();
    private volatile long activityRevision;
    private volatile Channel channel = DISCONNECTED;
    private volatile PresenceState presenceState = PresenceState.empty();
    private volatile Identity localIdentity;
    private volatile PresenceUpdate localPresence = PresenceUpdate.inactive();
    private volatile Consumer<Runnable> listenerDelivery = Runnable::run;
    private volatile BooleanSupplier mutationAdmission = () -> true;
    private boolean mutationAdmissionBound;

    public CollaborationService(String clientId) {
        String subjectId = clientId != null ? clientId.trim() : "";
        localIdentity = subjectId.isBlank() ? null : new Identity(subjectId, "Collaborator", "", "client");
    }

    public void bind(Channel channel) {
        if (!mutationsAllowed()) return;
        this.channel = channel != null ? channel : DISCONNECTED;
    }

    public synchronized void bindMutationAdmission(BooleanSupplier mutationAdmission) {
        if (mutationAdmissionBound) {
            throw new IllegalStateException("Collaboration mutation admission is already bound");
        }
        this.mutationAdmission = Objects.requireNonNull(mutationAdmission, "Mutation admission is required");
        mutationAdmissionBound = true;
    }

    public void setListenerDelivery(Consumer<Runnable> listenerDelivery) {
        if (!mutationsAllowed()) return;
        this.listenerDelivery = listenerDelivery != null ? listenerDelivery : Runnable::run;
    }

    public Channel channel() {
        return channel;
    }

    public boolean publishPresence(String resourceType, String resourceId, String viewId,
                                   double x, double y, boolean active, boolean typing) {
        return publishPresence(new Target(resourceType, resourceId, viewId), x, y, active, typing);
    }

    public boolean publishPresence(Target target, double x, double y, boolean active, boolean typing) {
        if (!mutationsAllowed()) return false;
        PresenceUpdate update = new PresenceUpdate(target, x, y, active, typing);
        localPresence = update;
        if (!channel.available()) {
            return false;
        }
        channel.publishPresence(update);
        return true;
    }

    public boolean publishMessage(String message) {
        return publishMessage(localPresence.target(), message);
    }

    public boolean publishMessage(Target target, String message) {
        if (!mutationsAllowed()) return false;
        String text = message != null ? message.trim() : "";
        if (text.isBlank() || !channel.available()) {
            return false;
        }
        channel.publishMessage(new MessageDraft(target, text));
        return true;
    }

    public PresenceUpdate localPresence() {
        return localPresence;
    }

    public void identify(Identity identity) {
        if (!mutationsAllowed()) return;
        localIdentity = hasSubject(identity) ? identity : null;
    }

    public boolean acceptSnapshot(String nextSelfSessionId, List<Presence> nextCollaborators) {
        return acceptSnapshot(nextSelfSessionId, null, List.of(), nextCollaborators);
    }

    public boolean acceptSnapshot(String nextSelfSessionId, Identity nextSelfIdentity, List<String> nextOwnSessionIds,
                                  List<Presence> nextCollaborators) {
        if (!mutationsAllowed()) return false;
        PresenceState previous = presenceState;
        String selfSessionId = nextSelfSessionId != null ? nextSelfSessionId : "";
        ArrayList<Presence> received = new ArrayList<>();
        if (nextCollaborators != null) {
            nextCollaborators.stream()
                .filter(Objects::nonNull)
                .filter(collaborator -> !collaborator.sessionId().isBlank())
                .forEach(received::add);
        }
        Identity selfIdentity = hasSubject(nextSelfIdentity) ? nextSelfIdentity : received.stream()
            .filter(collaborator -> selfSessionId.equals(collaborator.sessionId()))
            .map(Presence::identity)
            .filter(this::hasSubject)
            .findFirst()
            .orElse(localIdentity);
        LinkedHashSet<String> ownSessionIds = new LinkedHashSet<>();
        if (!selfSessionId.isBlank()) {
            ownSessionIds.add(selfSessionId);
        }
        if (nextOwnSessionIds != null) {
            nextOwnSessionIds.stream().filter(Objects::nonNull).filter(id -> !id.isBlank()).forEach(ownSessionIds::add);
        }
        LinkedHashMap<String, Presence> collaborators = new LinkedHashMap<>();
        for (Presence collaborator : received) {
            if (!ownSessionIds.contains(collaborator.sessionId())) {
                collaborators.put(collaborator.sessionId(), collaborator);
            }
        }
        ArrayList<Presence> snapshot = new ArrayList<>(collaborators.values());
        snapshot.sort(Comparator.comparing(value -> value.identity() != null ? value.identity().displayName() : "",
            String.CASE_INSENSITIVE_ORDER));
        PresenceState current = PresenceState.of(collaborators, snapshot, ownSessionIds, selfIdentity);
        presenceState = current;
        boolean changed = !previous.activity().equals(current.activity());
        if (changed) {
            activityRevision = ACTIVITY_SEQUENCE.incrementAndGet();
            activityListeners.forEach(this::notifyListener);
        }
        presenceListeners.forEach(listener -> notifyListener(() -> listener.accept(current.snapshot())));
        return changed;
    }

    public List<Presence> snapshot() {
        return presenceState.snapshot();
    }

    public long activityRevision() {
        return activityRevision;
    }

    public List<Presence> at(String resourceType, String resourceId) {
        return snapshot().stream()
            .filter(presence -> presence.target().matches(resourceType, resourceId))
            .toList();
    }

    public Presence presence(String sessionId) {
        return sessionId != null ? presenceState.collaborators().get(sessionId) : null;
    }

    public boolean isSelf(Presence presence) {
        if (presence == null) {
            return false;
        }
        return presenceState.ownSessionIds().contains(presence.sessionId());
    }

    public boolean isOwnSession(String sessionId) {
        return sessionId != null && presenceState.ownSessionIds().contains(sessionId);
    }

    public boolean isOwnChange(ResourceChange change) {
        return change != null && isOwnSession(change.authorSessionId());
    }

    private boolean hasSubject(Identity identity) {
        return identity != null && identity.subjectId() != null && !identity.subjectId().isBlank();
    }

    public void acceptResourceChange(ResourceChange change) {
        if (!mutationsAllowed()) return;
        if (change != null && !change.target().resourceType().isBlank() && !change.target().resourceId().isBlank()) {
            changes.put(change.target().key(), change);
            resourceChangeListeners.forEach(listener -> notifyListener(() -> listener.accept(change)));
        }
    }

    public ResourceChange resourceChange(String resourceType, String resourceId) {
        return changes.get(new Target(resourceType, resourceId, "").key());
    }

    public boolean acceptMessage(Message message) {
        if (!mutationsAllowed()) return false;
        if (message == null || message.id().isBlank() || message.authorSessionId().isBlank()
            || message.message().isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        messages.entrySet().removeIf(entry -> now - entry.getValue().receivedAt() > 10_000L);
        if (messages.putIfAbsent(message.id(), new CachedMessage(message, now)) != null) {
            return false;
        }
        messageListeners.forEach(listener -> notifyListener(() -> listener.accept(message)));
        return true;
    }

    public void addMessageListener(Consumer<Message> listener) {
        if (!mutationsAllowed()) return;
        if (listener == null) {
            return;
        }
        messageListeners.add(listener);
        long now = System.currentTimeMillis();
        messages.values().stream()
            .filter(message -> now - message.receivedAt() <= 10_000L)
            .sorted(Comparator.comparingLong(value -> value.message().sentAt()))
            .forEach(message -> notifyListener(() -> listener.accept(message.message())));
    }

    public void removeMessageListener(Consumer<Message> listener) {
        if (!mutationsAllowed()) return;
        messageListeners.remove(listener);
    }

    public void addPresenceListener(Consumer<List<Presence>> listener) {
        if (!mutationsAllowed()) return;
        if (listener != null) {
            presenceListeners.add(listener);
            notifyListener(() -> listener.accept(snapshot()));
        }
    }

    public void removePresenceListener(Consumer<List<Presence>> listener) {
        if (!mutationsAllowed()) return;
        presenceListeners.remove(listener);
    }

    public void addResourceChangeListener(Consumer<ResourceChange> listener) {
        if (!mutationsAllowed()) return;
        if (listener != null) {
            resourceChangeListeners.add(listener);
        }
    }

    public void removeResourceChangeListener(Consumer<ResourceChange> listener) {
        if (!mutationsAllowed()) return;
        resourceChangeListeners.remove(listener);
    }

    public void addActivityListener(Runnable listener) {
        if (!mutationsAllowed()) return;
        if (listener != null) {
            activityListeners.add(listener);
        }
    }

    public void removeActivityListener(Runnable listener) {
        if (!mutationsAllowed()) return;
        activityListeners.remove(listener);
    }

    public void connectionReady() {
        if (!mutationsAllowed()) return;
        if (channel.available()) {
            channel.publishPresence(localPresence);
        }
    }

    public void connectionLost() {
        clearTransientState(false);
    }

    public void clear() {
        clearTransientState(true);
    }

    private void clearTransientState(boolean resetLocalPresence) {
        presenceState = PresenceState.empty();
        changes.clear();
        messages.clear();
        if (resetLocalPresence) {
            localPresence = PresenceUpdate.inactive();
        }
        activityRevision = ACTIVITY_SEQUENCE.incrementAndGet();
        activityListeners.forEach(this::notifyListener);
        presenceListeners.forEach(listener -> notifyListener(() -> listener.accept(List.of())));
    }

    private void notifyListener(Runnable notification) {
        listenerDelivery.accept(() -> {
            try {
                notification.run();
            } catch (RuntimeException ignored) {
            }
        });
    }

    private boolean mutationsAllowed() {
        return mutationAdmission.getAsBoolean();
    }

    public interface Channel {
        boolean available();

        void publishPresence(PresenceUpdate update);

        void publishMessage(MessageDraft message);
    }

    public record Target(String resourceType, String resourceId, String viewId) {
        public Target {
            resourceType = resourceType != null ? resourceType : "";
            resourceId = resourceId != null ? resourceId : "";
            viewId = viewId != null ? viewId : "";
        }

        public boolean matches(String type, String id) {
            return Objects.equals(resourceType, type) && Objects.equals(resourceId, id);
        }

        public String key() {
            return resourceType + '\u0000' + resourceId;
        }
    }

    public record PresenceUpdate(Target target, double x, double y, boolean active, boolean typing) {
        public PresenceUpdate {
            target = target != null ? target : new Target("", "", "");
            x = Math.clamp(x, 0.0, 1.0);
            y = Math.clamp(y, 0.0, 1.0);
        }

        public static PresenceUpdate inactive() {
            return new PresenceUpdate(new Target("", "", ""), 0.0, 0.0, false, false);
        }
    }

    public record MessageDraft(Target target, String message) {
        public MessageDraft {
            target = target != null ? target : new Target("", "", "");
            message = message != null ? message.trim() : "";
        }
    }

    public record Identity(String subjectId, String displayName, String avatar, String source) {
        public Identity {
            subjectId = subjectId != null ? subjectId : "";
            displayName = displayName != null && !displayName.isBlank() ? displayName : "Collaborator";
            avatar = avatar != null ? avatar : "";
            source = source != null ? source : "";
        }
    }

    public record Presence(String sessionId, String clientId, Identity identity, String resourceType, String resourceId,
                           String viewId, double x, double y, boolean active, boolean typing, int color, boolean customColor, long updatedAt) {
        public Presence {
            sessionId = sessionId != null ? sessionId : "";
            clientId = clientId != null ? clientId : "";
            resourceType = resourceType != null ? resourceType : "";
            resourceId = resourceId != null ? resourceId : "";
            viewId = viewId != null ? viewId : "";
        }

        public Target target() {
            return new Target(resourceType, resourceId, viewId);
        }
    }

    public record ResourceChange(String type, String resourceId, String authorSessionId, Identity author,
                                 long changedAt, boolean deleted) {
        public ResourceChange {
            type = type != null ? type : "";
            resourceId = resourceId != null ? resourceId : "";
            authorSessionId = authorSessionId != null ? authorSessionId : "";
        }

        public Target target() {
            return new Target(type, resourceId, "");
        }
    }

    public record Message(String id, String authorSessionId, Identity author, String resourceType, String resourceId,
                          int color, String message, long sentAt) {
        public Message {
            id = id != null ? id : "";
            authorSessionId = authorSessionId != null ? authorSessionId : "";
            resourceType = resourceType != null ? resourceType : "";
            resourceId = resourceId != null ? resourceId : "";
            message = message != null ? message : "";
        }

        public Target target() {
            return new Target(resourceType, resourceId, "");
        }
    }

    private record CachedMessage(Message message, long receivedAt) {
    }

    private record CollaboratorActivity(Target target, boolean active, boolean typing, String displayName) {
        private static CollaboratorActivity from(Presence presence) {
            Identity identity = presence.identity();
            return new CollaboratorActivity(presence.target(), presence.active(), presence.typing(),
                identity != null ? identity.displayName() : "");
        }
    }

    private record ActivitySignature(Map<String, CollaboratorActivity> collaborators) {
        private static ActivitySignature from(Map<String, Presence> collaborators) {
            LinkedHashMap<String, CollaboratorActivity> activity = new LinkedHashMap<>();
            collaborators.forEach((sessionId, presence) -> activity.put(sessionId, CollaboratorActivity.from(presence)));
            return new ActivitySignature(Map.copyOf(activity));
        }

        private static ActivitySignature empty() {
            return new ActivitySignature(Map.of());
        }
    }

    private record PresenceState(Map<String, Presence> collaborators, List<Presence> snapshot, Set<String> ownSessionIds,
                                 Identity selfIdentity, ActivitySignature activity) {
        private static PresenceState of(Map<String, Presence> collaborators, List<Presence> snapshot,
                                        Set<String> ownSessionIds, Identity selfIdentity) {
            Map<String, Presence> collaboratorSnapshot = Map.copyOf(collaborators);
            return new PresenceState(collaboratorSnapshot, List.copyOf(snapshot), Set.copyOf(ownSessionIds), selfIdentity,
                ActivitySignature.from(collaboratorSnapshot));
        }

        private static PresenceState empty() {
            return new PresenceState(Map.of(), List.of(), Set.of(), null, ActivitySignature.empty());
        }
    }
}
