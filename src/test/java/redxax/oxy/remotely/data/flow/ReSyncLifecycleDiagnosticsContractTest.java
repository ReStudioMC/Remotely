package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReSyncLifecycleDiagnosticsContractTest {
    private static final Path SOURCE = Path.of(
        "src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java");
    private static final Path FLOW_MANAGER_SOURCE = Path.of(
        "src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");
    @TempDir
    Path tempDirectory;

    @Test
    void lifecycleDiagnosticsDefaultToRecoveryWithExplicitOverrides() throws IOException {
        String policy = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticPolicy.java"));
        assertTrue(policy.contains("CURRENT_MODE_PROPERTY = \"remotely.resync.diagnostics.lifecycle.mode\""));
        assertTrue(policy.contains("DEFAULT_MODE = DiagnosticSink.Mode.RECOVERY"));
        assertTrue(policy.contains("case \"off\", \"false\", \"no\", \"0\", \"disabled\" -> DiagnosticSink.Mode.OFF"));

        assertEquals(DiagnosticSink.Mode.RECOVERY,
            ReSyncLifecycleDiagnosticPolicy.resolveMode(null, null, null, null));
        assertEquals(DiagnosticSink.Mode.RECOVERY,
            ReSyncLifecycleDiagnosticPolicy.resolveMode(null, null, "true", null));
        assertEquals(DiagnosticSink.Mode.OFF,
            ReSyncLifecycleDiagnosticPolicy.resolveMode("off", null, "true", null));
        assertEquals(DiagnosticSink.Mode.OFF,
            ReSyncLifecycleDiagnosticPolicy.resolveMode(null, null, "false", null));
    }

    @Test
    void highCardinalityDiagnosticsStayBounded() throws IOException {
        String policy = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticPolicy.java"));
        String sink = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticFileSink.java"));
        assertTrue(policy.contains("PROGRESS_STAGES = Set.of("));
        assertTrue(policy.contains("\"authoritative_list_page\", \"catalog_frame_progress\", \"catalog_publication_progress\","));
        assertTrue(policy.contains("\"render_index_published\", \"render_index_rejected\""));
        assertTrue(policy.contains("MAX_THROTTLE_KEYS = 256"));
        assertTrue(policy.contains("PROGRESS_THROTTLE_NANOS = 250_000_000L"));
        assertTrue(sink.contains("new ArrayBlockingQueue<>(ReSyncLifecycleDiagnosticPolicy.CRITICAL_CAPACITY)"));
        assertTrue(sink.contains("new ArrayBlockingQueue<>(ReSyncLifecycleDiagnosticPolicy.NORMAL_CAPACITY)"));
        assertTrue(sink.contains("ReSyncLifecycleDiagnosticPolicy.healthyProgress(event) && throttled(event)"));
        assertTrue(sink.contains("if (!offerQueued(queue, queued))"));
        assertTrue(sink.contains("if (queue.offer(queued))"));
    }

    @Test
    void diagnosticHelperOmitsSensitiveFieldsAndBoundsText() throws IOException {
        String policy = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticPolicy.java"));
        String adapter = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticAdapter.java"));
        String sink = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticFileSink.java"));
        assertTrue(policy.contains("MAX_FIELDS = 32"));
        assertTrue(policy.contains("MAX_VALUE_CHARS = 240"));
        assertTrue(policy.contains("SENSITIVE_FIELDS = Set.of("));
        assertTrue(policy.contains("SENSITIVE_ASSIGNMENT"));
        assertTrue(policy.contains("ABSOLUTE_PATH"));
        assertTrue(policy.contains("SQL_TEXT"));
        assertTrue(policy.contains("STACK_TEXT"));
        assertTrue(adapter.contains("ReSyncLifecycleDiagnosticPolicy.sensitiveField(name)"));
        assertTrue(adapter.contains("safeValue(rawFields[index + 1], 0)"));
        assertTrue(sink.contains("ReSyncLifecycleDiagnosticPolicy.sensitiveField(entry.getKey())"));
        assertTrue(sink.contains("ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS"));
        assertTrue(sink.contains("ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_DEPTH"));
        assertTrue(sink.contains("ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES"));
    }

    @Test
    void clientRolloverRetriesPostMoveAndRetainsTheTriggeringRange() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        ReSyncLifecycleDiagnosticFileSink sink = new ReSyncLifecycleDiagnosticFileSink(tempDirectory,
            DiagnosticSink.Mode.VERBOSE, 9_000L, step -> {
                if (armed.get() && "post-move".equals(step) && failed.compareAndSet(false, true)) {
                    throw new IOException("injected");
                }
            });
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        armed.set(true);
        for (int index = 0; index < 80; index++) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(DiagnosticEvent.of("client_rollover_" + index,
                DiagnosticSink.Priority.NORMAL, DiagnosticIdentity.empty(), Map.of("detail", "x".repeat(240)), 0L)));
        }
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        DiagnosticSink.Status status = sink.status();
        sink.close();

        assertTrue(failed.get());
        assertEquals(DiagnosticSink.State.READY, status.state());
        assertTrue(status.reason().startsWith("recovered:"));
        Path directory = tempDirectory.resolve(ReSyncLifecycleDiagnosticFileSink.DIRECTORY);
        List<String> lines;
        try (var paths = Files.list(directory)) {
            lines = paths.sorted().flatMap(path -> {
                try {
                    return Files.readAllLines(path).stream();
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"stage\":\"client_rollover_79\"")));
        long previous = 0L;
        for (String line : lines) {
            if (!line.contains("\"kind\":\"event\"")) continue;
            int start = line.indexOf("\"sequence\":") + 11;
            int end = line.indexOf(',', start);
            long sequence = Long.parseLong(line.substring(start, end));
            assertTrue(sequence >= previous, sequence + " followed " + previous);
            previous = sequence;
        }
    }

    @Test
    void clientSinkRecordsReasonSpecificDropsAndProtectsTerminalEvidence() throws IOException {
        String sink = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnosticFileSink.java"));
        String helper = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/ReSyncLifecycleDiagnostics.java"));

        assertTrue(sink.contains("droppedNormalCapacity"));
        assertTrue(sink.contains("droppedCriticalCapacity"));
        assertTrue(sink.contains("droppedCriticalPreempted"));
        assertTrue(sink.contains("droppedTerminalCapacity"));
        assertTrue(sink.contains("candidate.event.priority() != Priority.TERMINAL"));
        assertTrue(sink.contains("MAX_RECOVERY_ATTEMPTS = 3"));
        assertTrue(helper.contains("warn(sink.status())"));
        assertTrue(helper.contains("System.err.println(\"Remotely ReSync lifecycle diagnostics warning:"));
    }

    @Test
    void deletePreflightDiagnosticsIdentifyEveryPreWireAuthorityBoundaryWithoutPayloads() throws IOException {
        String client = Files.readString(SOURCE);
        String manager = Files.readString(FLOW_MANAGER_SOURCE);

        assertTrue(client.contains("traceLifecycle(serverId, \"delete_preflight\""));
        assertTrue(client.contains("\"resourceState\", evidence.resourceState()"));
        assertTrue(client.contains("resolvedAssetHash == null ? \"missing\" : \"present\""));
        assertTrue(client.contains("\"activationState\""));
        assertTrue(client.contains("\"listAuthority\""));
        assertTrue(client.contains("\"generation_fence_changed\""));
        assertTrue(client.contains("\"publication_fence_unavailable\""));
        assertTrue(client.contains("\"authority_epoch_unavailable\""));
        assertTrue(client.contains("\"wire_dispatch_rejected\""));
        assertTrue(client.contains("\"revision_unavailable\""));
        assertTrue(manager.contains("\"membership_authority_missing\""));
        assertTrue(manager.contains("\"active_client_missing\""));
        assertTrue(manager.contains("\"client_dispatch_rejected\""));
    }
}
