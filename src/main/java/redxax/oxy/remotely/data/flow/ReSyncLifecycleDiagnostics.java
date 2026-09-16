package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;


public final class ReSyncLifecycleDiagnostics {
    private static final DiagnosticSink DISABLED = new DisabledSink();
    private static final BrowserSafeState.ReferenceValue<DiagnosticSink> SINK = new BrowserSafeState.ReferenceValue<>(DISABLED);
    private static final BrowserSafeState.ReferenceValue<String> LAST_WARNING = new BrowserSafeState.ReferenceValue<>("");

    private ReSyncLifecycleDiagnostics() {
    }

    public static boolean enabled() {
        return SINK.get().enabled();
    }

    public static DiagnosticSink.Status status() {
        return SINK.get().status();
    }

    public static void offer(String serverId, String stage, Object... fields) {
        DiagnosticSink sink = SINK.get();
        DiagnosticSink.Status status = sink.status();
        if (status.failed()) {
            ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG = false;
            warn(status);
            return;
        }
        if (!status.enabled()) {
            return;
        }
        try {
            DiagnosticEvent event = ReSyncLifecycleDiagnosticAdapter.event(stage, serverId, fields);
            DiagnosticSink.Offer offer = sink.offer(event);
            if (offer == DiagnosticSink.Offer.FAILED) {
                ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG = false;
                warn(sink.status());
            }
        } catch (RuntimeException exception) {
            warn("offer:" + TaskIdentities.failureName(exception));
        }
    }

    public static DiagnosticSink install(DiagnosticSink sink) {
        DiagnosticSink replacement = sink == null ? DISABLED : sink;
        DiagnosticSink previous = SINK.getAndSet(replacement);
        LAST_WARNING.set("");
        ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG = replacement.enabled();
        if (previous != replacement) {
            try {
                previous.close();
            } catch (RuntimeException ignored) {
            }
        }
        return replacement;
    }

    public static DiagnosticSink.Flush flush() {
        try {
            return SINK.get().flush();
        } catch (RuntimeException ignored) {
            return DiagnosticSink.Flush.FAILED;
        }
    }

    public static void close() {
        DiagnosticSink previous = SINK.getAndSet(DISABLED);
        ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG = false;
        if (previous != DISABLED) {
            try {
                previous.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static String lastWarning() {
        return LAST_WARNING.get();
    }

    private static void warn(DiagnosticSink.Status status) {
        warn("state=" + status.state() + " reason=" + status.reason() + " failures=" + status.failures()
            + " dropped=" + status.dropped());
    }

    private static void warn(String detail) {
        if (LAST_WARNING.compareAndSet("", detail)) {
            System.err.println("Remotely ReSync lifecycle diagnostics warning: " + detail);
        }
    }

    private static final class DisabledSink implements DiagnosticSink {
        @Override
        public Status status() {
            return Status.disabled();
        }

        @Override
        public Offer offer(DiagnosticEvent event) {
            return Offer.DISABLED;
        }

        @Override
        public Status pause() {
            return Status.disabled();
        }

        @Override
        public Status resume() {
            return Status.disabled();
        }

        @Override
        public Flush flush() {
            return Flush.DISABLED;
        }

        @Override
        public void close() {
        }
    }
}
