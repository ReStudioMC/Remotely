package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncLifecycleEvidenceTest {
    @TempDir
    Path directory;

    @Test
    void typedIdentitySurvivesPrivacyFilteringAndFileEncoding() throws Exception {
        String server = UUID.randomUUID().toString();
        UUID request = UUID.randomUUID();
        ResourceKey key = new ResourceKey(new ContractRef<>(new OwnerId("restudio.resync"),
            new ResourceTypeId("flow")), "example");
        DiagnosticEvent event = ReSyncLifecycleDiagnosticAdapter.event("resource_terminal", server,
            "typedKey", key, "requestId", request, "outcome", "failed", "elapsedMs", 487L);
        assertEquals(key, event.identity().resource().key());
        assertEquals(request, event.identity().requestId());
        try (ReSyncLifecycleDiagnosticFileSink sink = new ReSyncLifecycleDiagnosticFileSink(directory,
            DiagnosticSink.Mode.RECOVERY, 64_000L, null)) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event));
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            String line = Files.readString(sink.activePath());
            assertTrue(line.contains(event.identity().resource().canonicalText()));
            assertTrue(line.contains(request.toString()));
            assertTrue(line.contains("\"elapsedMs\":487"));
        }
    }

    @Test
    void oversizedFailureRetainsStageTimingIdentityAndOutcome() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < 25; index++) values.put("detail" + index, "x".repeat(240));
        values.put("outcome", "failed");
        values.put("diagnosticCode", "RESOURCE.CONFLICT");
        values.put("reason", "revision_conflict");
        DiagnosticEvent event = DiagnosticEvent.of("resource_terminal", DiagnosticSink.Priority.TERMINAL,
            DiagnosticIdentity.empty(), values, 487L);
        try (ReSyncLifecycleDiagnosticFileSink sink = new ReSyncLifecycleDiagnosticFileSink(directory,
            DiagnosticSink.Mode.RECOVERY, 64_000L, null)) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event));
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            String line = Files.readAllLines(sink.activePath()).stream()
                .filter(value -> value.contains("\"kind\":\"event\"")).findFirst().orElseThrow();
            assertTrue(line.contains("\"stage\":\"resource_terminal\""));
            assertTrue(line.contains("\"elapsedMs\":487"));
            assertTrue(line.contains("\"outcome\":\"failed\""));
            assertTrue(line.contains("RESOURCE.CONFLICT"));
            assertTrue(line.contains("\"truncated\":true"));
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length < ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES);
        }
    }

    @Test
    void unrelatedCountersSurviveWhileCredentialFieldsRemainPrivate() {
        assertFalse(ReSyncLifecycleDiagnosticPolicy.sensitiveField("descriptorCount"));
        assertFalse(ReSyncLifecycleDiagnosticPolicy.sensitiveField("ownershipValidationMs"));
        assertFalse(ReSyncLifecycleDiagnosticPolicy.sensitiveField("participantCount"));
        assertFalse(ReSyncLifecycleDiagnosticPolicy.sensitiveField("pipelineMs"));
        assertTrue(ReSyncLifecycleDiagnosticPolicy.sensitiveField("accessToken"));
        assertTrue(ReSyncLifecycleDiagnosticPolicy.sensitiveField("password"));
        assertTrue(ReSyncLifecycleDiagnosticPolicy.sensitiveField("absolutePath"));
        assertEquals("[redacted]", ReSyncLifecycleDiagnosticPolicy.safeText("C:/Users/private/file"));
    }
    @Test
    void healthyConversionsAreSampledAcrossDescriptorsButSlowAndFailedWorkIsRetained() {
        DiagnosticEvent first = ReSyncLifecycleDiagnosticAdapter.event("generic_widget_conversion", "",
            "resourceKey", "widget:first", "outcome", "converted", "elapsedMs", 1L);
        DiagnosticEvent second = ReSyncLifecycleDiagnosticAdapter.event("generic_widget_conversion", "",
            "resourceKey", "widget:second", "outcome", "converted", "elapsedMs", 1L);
        DiagnosticEvent slow = ReSyncLifecycleDiagnosticAdapter.event("generic_widget_conversion", "",
            "outcome", "converted", "elapsedMs", 300L);
        DiagnosticEvent failed = ReSyncLifecycleDiagnosticAdapter.event("generic_widget_conversion", "",
            "outcome", "empty", "reason", "conversion_exception:IllegalStateException");
        assertTrue(ReSyncLifecycleDiagnosticPolicy.healthyProgress(first));
        assertEquals(ReSyncLifecycleDiagnosticPolicy.progressKey(first), ReSyncLifecycleDiagnosticPolicy.progressKey(second));
        assertFalse(ReSyncLifecycleDiagnosticPolicy.healthyProgress(slow));
        assertFalse(ReSyncLifecycleDiagnosticPolicy.healthyProgress(failed));
    }

    @Test
    void criticalEventArrivingDuringNormalPollKeepsPhysicalSequenceOrder() throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean intercepted = new AtomicBoolean();
        try (ReSyncLifecycleDiagnosticFileSink sink = new ReSyncLifecycleDiagnosticFileSink(directory,
            DiagnosticSink.Mode.RECOVERY, 64_000L, step -> {
                if (!step.equals("normal-poll") || !intercepted.compareAndSet(false, true)) return;
                polling.countDown();
                try {
                    if (!release.await(2L, TimeUnit.SECONDS)) throw new IOException("Timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            })) {
            try {
                assertTrue(polling.await(2L, TimeUnit.SECONDS));
                assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(DiagnosticEvent.of("first_critical",
                    DiagnosticSink.Priority.IMPORTANT, DiagnosticIdentity.empty(), Map.of(), 0L)));
                assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(DiagnosticEvent.of("second_normal",
                    DiagnosticSink.Priority.NORMAL, DiagnosticIdentity.empty(), Map.of(), 0L)));
            } finally {
                release.countDown();
            }
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            String lines = Files.readString(sink.activePath());
            assertTrue(lines.indexOf("first_critical") >= 0);
            assertTrue(lines.indexOf("first_critical") < lines.indexOf("second_normal"));
        }
    }

    @Test
    void writerFailureReportsEarliestUnconfirmedBufferedOrBatchedEvent() throws Exception {
        for (String failedStep : List.of("post-normal-poll", "flush")) {
            CountDownLatch polling = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean intercepted = new AtomicBoolean();
            AtomicBoolean armed = new AtomicBoolean();
            AtomicInteger flushes = new AtomicInteger();
            try (ReSyncLifecycleDiagnosticFileSink sink = new ReSyncLifecycleDiagnosticFileSink(
                directory.resolve(failedStep), DiagnosticSink.Mode.RECOVERY, 64_000L, step -> {
                    if (step.equals("normal-poll") && intercepted.compareAndSet(false, true)) {
                        polling.countDown();
                        try {
                            if (!release.await(2L, TimeUnit.SECONDS)) throw new IOException("Timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IOException(failure);
                        }
                        armed.set(true);
                    }
                    if (armed.get() && step.equals(failedStep)
                        && (!step.equals("flush") || flushes.incrementAndGet() == 2)) {
                        throw new IllegalStateException("injected");
                    }
                })) {
                try {
                    assertTrue(polling.await(2L, TimeUnit.SECONDS));
                    for (int index = 1; index <= 3; index++) {
                        assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(DiagnosticEvent.of("pending_" + index,
                            DiagnosticSink.Priority.NORMAL, DiagnosticIdentity.empty(), Map.of(), 0L)));
                    }
                } finally {
                    release.countDown();
                }
                assertEquals(DiagnosticSink.Flush.FAILED, sink.flush());
                String expectedRange = failedStep.equals("flush") ? "lost=2-3" : "lost=1-3";
                assertTrue(sink.status().reason().endsWith(expectedRange), sink.status().reason());
            }
        }
    }

}
