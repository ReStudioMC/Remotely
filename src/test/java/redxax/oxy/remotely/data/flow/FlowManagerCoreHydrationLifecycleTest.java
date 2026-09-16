package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerCoreHydrationLifecycleTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");
    private static final ServerId SERVER = ServerId.deterministic("flow-manager-current-editable-baseline");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "retained-flow");
    private static final CatalogBinding GENERATION_53 = new CatalogBinding(53, "5".repeat(64), "3".repeat(64));
    private static final CatalogBinding GENERATION_54 = new CatalogBinding(54, "5".repeat(64), "4".repeat(64));

    @Test
    void staleReadOnlyGenerationRequiresOneLoadBeforeGeneration54SessionBecomesCurrent() {
        CatalogAuthoringPublication publication53 = publication(GENERATION_53);
        CatalogAuthoringPublication publication54 = publication(GENERATION_54);
        CoreGraphUiProjection.Baseline readOnly53 = baseline(53L, GENERATION_53,
            CoreGraphUiProjection.Status.READ_ONLY);
        CoreGraphUiProjection.Baseline live53 = baseline(53L, GENERATION_53, CoreGraphUiProjection.Status.LIVE);
        CoreGraphUiProjection.Baseline live54 = baseline(54L, GENERATION_54, CoreGraphUiProjection.Status.LIVE);
        CoreGraphEditorSession retained53 = session(53L, GENERATION_53, publication53);
        CoreGraphEditorSession retained54 = session(54L, GENERATION_54, publication54);

        assertFalse(FlowManager.currentEditableCoreBaseline(readOnly53, publication53, null, false));
        assertFalse(FlowManager.currentEditableCoreBaseline(live53, publication54, null, false));
        assertFalse(FlowManager.currentEditableCoreBaseline(live54, publication54, retained53, false));

        FlowManager.CoreGraphHydrationTracker<String> tracker = new FlowManager.CoreGraphHydrationTracker<>();
        assertTrue(tracker.begin("flow:retained-flow", 54L, 54L, 0L).dispatch());
        assertFalse(tracker.begin("flow:retained-flow", 54L, 54L, 0L).dispatch());
        assertEquals(1, tracker.lifecycle("flow:retained-flow").attempts());

        assertTrue(FlowManager.currentEditableCoreBaseline(live54, publication54, null, false));
        assertTrue(FlowManager.currentEditableCoreBaseline(live54, publication54, retained54, false));
        assertFalse(FlowManager.currentEditableCoreBaseline(live54, publication54, retained54, true));
        tracker.complete("flow:retained-flow", 54L, 54L, true);
        assertEquals("complete", tracker.lifecycle("flow:retained-flow").terminalReason());
    }

    @Test
    void repeatedWorkspaceRefreshBoundsAndDeduplicatesTwoStaleTabsWithoutSelection() throws IOException {
        FlowManager.CoreGraphHydrationTracker<String> tracker = new FlowManager.CoreGraphHydrationTracker<>();

        assertTrue(tracker.begin("flow:alpha", 7L, 11L, 0L).dispatch());
        assertTrue(tracker.begin("command:beta", 7L, 11L, 0L).dispatch());
        for (long now = SECOND / 10L; now < 15L * SECOND; now += SECOND / 10L) {
            boolean alpha = tracker.begin("flow:alpha", 7L, 11L, now).dispatch();
            boolean beta = tracker.begin("command:beta", 7L, 11L, now).dispatch();
            assertEquals(now % (3L * SECOND) == 0L, alpha);
            assertEquals(now % (3L * SECOND) == 0L, beta);
        }

        FlowManager.CoreGraphHydrationDecision alphaTerminal = tracker.begin(
            "flow:alpha", 7L, 11L, 15L * SECOND);
        FlowManager.CoreGraphHydrationDecision betaTerminal = tracker.begin(
            "command:beta", 7L, 11L, 15L * SECOND);
        assertFalse(alphaTerminal.dispatch());
        assertTrue(alphaTerminal.becameTerminal());
        assertEquals("deadline_exceeded", alphaTerminal.reason());
        assertFalse(betaTerminal.dispatch());
        assertTrue(betaTerminal.becameTerminal());
        assertEquals(5, tracker.lifecycle("flow:alpha").attempts());
        assertEquals(5, tracker.lifecycle("command:beta").attempts());

        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String refresh = methodBody(source, "private void rehydrateRetainedCoreGraphSessions(");
        assertFalse(refresh.contains("selectStudioDocument"));
        assertFalse(refresh.contains("openStudio"));
    }

    @Test
    void unreconcilableRepliesPreserveTheFirstAttemptAndDeadline() {
        FlowManager.CoreGraphHydrationTracker<String> tracker = new FlowManager.CoreGraphHydrationTracker<>();
        long started = 27L * SECOND;

        assertTrue(tracker.begin("function:gamma", 4L, 9L, started).dispatch());
        FlowManager.CoreGraphHydrationLifecycle initial = tracker.lifecycle("function:gamma");
        tracker.complete("function:gamma", 4L, 9L, false);
        tracker.complete("function:gamma", 4L, 9L, false);
        assertSame(initial, tracker.lifecycle("function:gamma"));

        assertTrue(tracker.begin("function:gamma", 4L, 9L, started + 3L * SECOND).dispatch());
        FlowManager.CoreGraphHydrationLifecycle retried = tracker.lifecycle("function:gamma");
        assertEquals(initial.firstAttemptNanos(), retried.firstAttemptNanos());
        assertEquals(initial.deadlineNanos(), retried.deadlineNanos());
        assertEquals(2, retried.attempts());
    }

    @Test
    void terminalLifecycleRequiresExplicitActivationOrNewerEpoch() {
        FlowManager.CoreGraphHydrationTracker<String> tracker = new FlowManager.CoreGraphHydrationTracker<>();
        long started = 40L * SECOND;

        assertTrue(tracker.begin("flow:delta", 3L, 5L, started).dispatch());
        assertTrue(tracker.begin("flow:delta", 3L, 5L, started + 15L * SECOND).becameTerminal());
        assertFalse(tracker.begin("flow:delta", 3L, 5L, started + 18L * SECOND).dispatch());

        assertTrue(tracker.begin("flow:delta", 3L, 6L, started + 18L * SECOND).dispatch());
        FlowManager.CoreGraphHydrationLifecycle authorityRetry = tracker.lifecycle("flow:delta");
        assertEquals(6L, authorityRetry.authorityEpoch());
        assertEquals(started + 18L * SECOND, authorityRetry.firstAttemptNanos());

        tracker.complete("flow:delta", 3L, 6L, true);
        FlowManager.CoreGraphHydrationLifecycle complete = tracker.lifecycle("flow:delta");
        tracker.complete("flow:delta", 3L, 6L, true);
        assertSame(complete, tracker.lifecycle("flow:delta"));
        assertEquals("complete", complete.terminalReason());

        tracker.activate("flow:delta", 3L, 6L, started + 19L * SECOND);
        assertTrue(tracker.begin("flow:delta", 3L, 6L, started + 19L * SECOND).dispatch());
        assertTrue(tracker.begin("flow:delta", 4L, 1L, started + 20L * SECOND).dispatch());
        assertEquals(4L, tracker.lifecycle("flow:delta").transportGeneration());
    }

    @Test
    void publicationMismatchIsReportedImmediatelyWithoutRediscoveringTheSameCatalog() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String peek = methodBody(source, "public ReSyncFlowClient.CoreGraphActivationOutcome peekCoreGraphActivation(");
        String notify = methodBody(source, "void notifyCoreGraphOpenIncompatibility(");
        String hydrate = methodBody(source, "private void hydrateCoreGraphProjection(");
        String refreshRequired = methodBody(source, "private boolean coreGraphPublicationRefreshRequired(");
        int refresh = hydrate.indexOf("flowClient.ensureCatalogPublication(true)");
        int load = hydrate.indexOf("flowClient.requestResource(type, id, false)");

        assertTrue(peek.contains("return flowClient.peekCoreGraphActivation(type, id);"));
        assertFalse(peek.contains("Core graph authoring publication is refreshing."));
        assertTrue(notify.contains("new Notification(\"Core Graph Open Failed\""));
        assertFalse(notify.contains("pendingCorePublicationTransition"));
        assertTrue(refreshRequired.contains("return flowClient.activeAuthoringPublication().isEmpty();"));
        assertFalse(refreshRequired.contains("CORE_GRAPH_PUBLICATION_MISMATCH"));
        assertTrue(hydrate.contains("hydration.attempts() == 1"));
        assertTrue(refresh >= 0 && load > refresh);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new IllegalStateException(signature);
    }

    private static CatalogAuthoringPublication publication(CatalogBinding binding) {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            section(CatalogAuthoringPublication.Section.TYPES),
            section(CatalogAuthoringPublication.Section.EDITORS),
            section(CatalogAuthoringPublication.Section.PREVIEWS),
            section(CatalogAuthoringPublication.Section.CAPABILITIES));
        return new CatalogAuthoringPublication(binding, new CatalogVersion((int) binding.generation(), 0),
            CatalogProjectionVersion.current(), sections, Set.of());
    }

    private static CatalogAuthoringPublication.SectionProjection section(
        CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE,
            List.of());
    }

    private static CoreGraphUiProjection.Baseline baseline(long revision, CatalogBinding binding,
                                                            CoreGraphUiProjection.Status status) {
        GraphDocument document = document(revision, binding);
        FlowGraph graph = new FlowGraph(RESOURCE.id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
        ContentHash hash = new ContentHash(Long.toHexString(revision).repeat(64).substring(0, 64));
        return new CoreGraphUiProjection.Baseline(new CoreGraphUiProjection.Key(SERVER.canonicalText(),
            ReSyncResourceType.FLOW, RESOURCE.id()), graph, new JsonObject(), document, null, status, revision,
            UUID.nameUUIDFromBytes((RESOURCE.id() + revision).getBytes()), hash, hash,
            ResourceActivationState.ACTIVE, "");
    }

    private static CoreGraphEditorSession session(long revision, CatalogBinding binding,
                                                  CatalogAuthoringPublication publication) {
        return new CoreGraphEditorSession(document(revision, binding),
            CatalogCachePublicationCodec.authoringPublicationChecksum(publication), Set.of(), Set.of());
    }

    private static GraphDocument document(long revision, CatalogBinding binding) {
        return new GraphDocument(RESOURCE, revision, binding, List.of(), List.of());
    }
}
