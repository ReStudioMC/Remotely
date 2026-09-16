package redxax.oxy.remotely.flow.ui.studio;

import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.Objects;

public record StudioDocument(String type, String id, String title, FlowGraph graph,
                             CoreGraphEditorSession coreSession, ReSyncStudioView view,
                             StudioViewportState viewport) {
    public StudioDocument(String type, String id, String title, FlowGraph graph,
                          ReSyncStudioView view, StudioViewportState viewport) {
        this(type, id, title, graph, null, view, viewport);
    }

    public StudioDocument {
        if (graph != null && coreSession != null) {
            throw new IllegalArgumentException("Studio documents cannot combine legacy and Core graph state");
        }
        if (coreSession != null) {
            if (coreSession.resource() == null || !Objects.equals(type, coreSession.resource().resourceType().value())
                || !Objects.equals(id, coreSession.resource().id())) {
                throw new IllegalArgumentException("Core session identity does not match the Studio document");
            }
        }
    }

    public boolean isCoreDocument() {
        return coreSession != null;
    }

    public boolean isLegacyGraphDocument() {
        return graph != null;
    }

    public String key() {
        return ReSyncProjectMetadata.resourceKey(type, id);
    }

    public record EditorReadiness(boolean currentSessionBound, boolean projectionAvailable,
                                  boolean widgetTopologyAvailable, boolean selectionValid,
                                  long generation, String topologyChecksum) {
        public EditorReadiness {
            topologyChecksum = topologyChecksum == null ? "" : topologyChecksum;
            if (generation < 0L) {
                throw new IllegalArgumentException("Editor readiness generation cannot be negative");
            }
        }

        public static EditorReadiness unavailable() {
            return new EditorReadiness(false, false, false, false, 0L, "");
        }

        public boolean ready() {
            return currentSessionBound && projectionAvailable && widgetTopologyAvailable && selectionValid
                && generation > 0L && !topologyChecksum.isBlank();
        }
    }
}
