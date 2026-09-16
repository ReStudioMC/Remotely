package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface ReSyncCollaborativeView {
    final class CollaborationDocumentSnapshot {
        private final ReSyncCollaborativeView owner;
        private final long lifecycle;
        private final long editVersion;
        private final JsonObject document;
        private final RuntimeException failure;

        public CollaborationDocumentSnapshot(ReSyncCollaborativeView owner, long lifecycle, long editVersion,
                                             JsonObject document, RuntimeException failure) {
            this(owner, lifecycle, editVersion, document, failure, true);
        }

        private CollaborationDocumentSnapshot(ReSyncCollaborativeView owner, long lifecycle, long editVersion,
                                              JsonObject document, RuntimeException failure, boolean copyDocument) {
            this.owner = Objects.requireNonNull(owner);
            this.lifecycle = lifecycle;
            this.editVersion = editVersion;
            this.document = copyDocument && document != null ? document.deepCopy() : document;
            this.failure = failure;
        }

        public ReSyncCollaborativeView owner() {
            return owner;
        }

        public long lifecycle() {
            return lifecycle;
        }

        public long editVersion() {
            return editVersion;
        }

        public JsonObject document() {
            return document;
        }

        public RuntimeException failure() {
            return failure;
        }

        public boolean successful() {
            return failure == null && document != null;
        }

        public boolean failed() {
            return !successful();
        }

        public CollaborationDocumentSnapshot ownedBy(ReSyncCollaborativeView nextOwner) {
            return new CollaborationDocumentSnapshot(nextOwner, lifecycle, editVersion, document, failure);
        }

        public CollaborationDocumentSnapshot transferredTo(ReSyncCollaborativeView nextOwner) {
            return new CollaborationDocumentSnapshot(nextOwner, lifecycle, editVersion, document, failure, false);
        }
    }

    record CollaborationDocumentApplyResult(ReSyncCollaborativeView owner, long lifecycle,
                                             long beforeEditVersion, long afterEditVersion, boolean applied,
                                             RuntimeException failure) {
        public CollaborationDocumentApplyResult {
            owner = Objects.requireNonNull(owner);
        }

        public boolean successful() {
            return applied && failure == null;
        }

        public boolean failed() {
            return !successful();
        }

        public CollaborationDocumentApplyResult ownedBy(ReSyncCollaborativeView nextOwner) {
            return new CollaborationDocumentApplyResult(nextOwner, lifecycle, beforeEditVersion, afterEditVersion,
                applied, failure);
        }
    }

    default boolean supportsCollaboration() {
        return true;
    }

    default long collaborationLifecycle() {
        return 0L;
    }

    default long collaborationEditVersion() {
        return -1L;
    }

    default boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        try {
            completion.accept(new CollaborationDocumentSnapshot(this, collaborationLifecycle(), -1L, null,
                new IllegalStateException("Collaboration Snapshot Unsupported")));
        } catch (RuntimeException | Error ignored) {
        }
        return false;
    }

    default boolean applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                               Consumer<CollaborationDocumentApplyResult> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = 0L;
        long beforeEditVersion = -1L;
        long afterEditVersion = -1L;
        try {
            lifecycle = collaborationLifecycle();
            beforeEditVersion = collaborationEditVersion();
            applyCollaborationDocument(document, patches);
            afterEditVersion = collaborationEditVersion();
            completeApply(completion, new CollaborationDocumentApplyResult(this, lifecycle, beforeEditVersion,
                afterEditVersion, true, null));
            return true;
        } catch (RuntimeException | Error exception) {
            RuntimeException failure = exception instanceof RuntimeException runtime
                ? runtime : new IllegalStateException(exception);
            completeApply(completion, new CollaborationDocumentApplyResult(this, lifecycle, beforeEditVersion,
                afterEditVersion, false, failure));
            return false;
        }
    }

    static void completeApply(Consumer<CollaborationDocumentApplyResult> completion,
                              CollaborationDocumentApplyResult result) {
        try {
            completion.accept(result);
        } catch (RuntimeException | Error ignored) {
        }
    }

    JsonObject collaborationDocument();

    void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches);

    default void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
    }
}
