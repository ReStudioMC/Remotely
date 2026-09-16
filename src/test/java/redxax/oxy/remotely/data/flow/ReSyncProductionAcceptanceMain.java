package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReSyncProductionAcceptanceMain {
    private static final String REPORT_NAME = "resync-production-acceptance.json";
    private static final Gson GSON = new Gson();

    private ReSyncProductionAcceptanceMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = environment("RESYNC_ACCEPTANCE_MODE", "live").toLowerCase(Locale.ROOT);
        String serverId = environment("RESYNC_SERVER_ID", "local-paper");
        int minimumNodes = positiveOrZero("RESYNC_ACCEPTANCE_MINIMUM_NODES", 1000);
        boolean gate3b = Boolean.parseBoolean(environment("RESYNC_ACCEPTANCE_GATE3B", "true"));
        AcceptanceRun run = new AcceptanceRun(mode, serverId);
        try {
            AcceptanceOptions options = AcceptanceOptions.fromEnvironment(gate3b && !"cache".equals(mode), serverId);
            if ("cache".equals(mode)) {
                runCacheAcceptance(run, serverId, minimumNodes);
            } else {
                runLiveAcceptance(run, serverId, minimumNodes, options);
            }
        } catch (Throwable failure) {
            run.failures.clear();
            run.failures.add(safeFailureName(failure));
            run.status = "failed";
        }
        Path report = writeReport(run);
        printResult(run, report);
        if (!"passed".equals(run.status)) {
            throw new IllegalStateException("ReSync production acceptance failed; report=" + report);
        }
    }

    private static void runLiveAcceptance(AcceptanceRun run, String serverId, int minimumNodes,
                                          AcceptanceOptions options) throws Exception {
        String wsUrl = requiredEnvironment("RESYNC_WS_URL");
        String apiKey = requiredEnvironment("RESYNC_API_KEY");
        int expectedReconnects = positiveOrZero("RESYNC_ACCEPTANCE_EXPECT_RECONNECTS",
            options.requireGate3bFixtures ? 1 : 0);
        validateExpectedReconnects(options.requireGate3bFixtures, expectedReconnects);
        long timeoutSeconds = positiveOrZero("RESYNC_ACCEPTANCE_TIMEOUT_SECONDS", expectedReconnects > 0 ? 120 : 30);
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        NodeRegistry registry = new NodeRegistry();
        ReadOnlyProbeClient probe = new ReadOnlyProbeClient();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, null, wsUrl, apiKey, probe);
        SessionTracker sessions = new SessionTracker(client);
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        FlowManager manager = probe.getFlowManager();
        FlowManagerTestConnection.installDirectCurrent(manager, serverId, client, wsUrl, apiKey);
        FlowManagerTestConnection.observeCurrent(manager, client, ignored -> sessions.authenticated(),
            ignored -> disconnects.incrementAndGet(), (nodeId, message) -> errors.incrementAndGet());
        Instant deadline = Instant.now().plus(timeout);
        try {
            if (FlowManagerTestConnection.connectCurrent(manager, serverId) != client) {
                throw new IllegalStateException("Acceptance client ownership was not retained");
            }
            while (Instant.now().isBefore(deadline)) {
                boolean connected = client.isConnectedState();
                int session = sessions.current();
                if (connected && session > 0) {
                    if (options.resourceType != null) {
                        if (sessions.requestResource(session)) {
                            client.requestResource(options.resourceType, options.resourceId, false);
                        }
                    }
                    if (options.graphType != null) {
                        if (sessions.requestGraph(session)) {
                            client.requestResource(options.graphType, options.graphId, false);
                        }
                    }
                }
                if (options.graphType != null && sessions.graphRequested(session)
                    && graphReady(probe.getFlowManager(), serverId, options.graphType, options.graphId)
                    && options.runRuntime && runtimeSupported(probe.getFlowManager(), serverId)) {
                    CountDownLatch latch = new CountDownLatch(1);
                    if (!sessions.requestRuntime(session, latch)) {
                        continue;
                    }
                    FlowGraph graph = probe.getFlowManager().getGraph(serverId, options.graphType, options.graphId);
                    client.requestFunctionTest(graph, options.runtimeName, options.runtimeInputs,
                        options.runtimeExpectedOutputs, options.runtimeServerContext, options.runtimeClockInstant,
                        options.runtimeZoneId, options.runtimeTimeoutMillis, result -> {
                            sessions.acceptRuntimeResult(session, result);
                            latch.countDown();
                        });
                }
                GateEvaluation evaluation = evaluateLive(run, client, probe, registry, serverId, minimumNodes,
                    expectedReconnects, sessions, disconnects.get(), errors.get(), options, connected);
                if (evaluation.ready()) {
                    run.status = "passed";
                    return;
                }
                Thread.sleep(100L);
            }
            evaluateLive(run, client, probe, registry, serverId, minimumNodes, expectedReconnects,
                sessions, disconnects.get(), errors.get(), options, client.isConnectedState());
            run.failures.add("timeout");
            run.status = "failed";
        } finally {
            client.shutdown();
            probe.shutdownProbe();
        }
    }

    private static void runCacheAcceptance(AcceptanceRun run, String serverId, int minimumNodes) {
        boolean expectEmpty = Boolean.parseBoolean(environment("RESYNC_ACCEPTANCE_EXPECT_EMPTY", "false"));
        NodeRegistry registry = new NodeRegistry();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, null, "ws://127.0.0.1:1", "unused", null);
        try {
            NodeRegistry.RegistrySessionMetadata metadata = registry.getRegistrySessionMetadata(serverId);
            int registryNodeCount = registry.getAllDefinitions(serverId).size();
            run.observed.put("registryNodeCount", registryNodeCount);
            run.observed.put("registryChecksum", metadata != null ? metadata.checksum() : "");
            run.observed.put("contractVersion", metadata != null ? metadata.contractVersion() : 0);
            boolean passed;
            if (expectEmpty) {
                passed = metadata == null && registryNodeCount == 0;
            } else {
                passed = metadata != null && !metadata.checksum().isBlank() && registryNodeCount >= minimumNodes;
            }
            addCheck(run, "registryCache", passed, passed ? "ready" : "unavailable_or_incomplete");
            if (!passed) {
                run.failures.add("registryCache");
            }
            run.effectiveNodeCount = registryNodeCount;
            run.registryNodeCount = registryNodeCount;
            run.contractVersion = metadata != null ? metadata.contractVersion() : 0;
            run.catalogChecksum = "";
            run.status = run.failures.isEmpty() ? "passed" : "failed";
        } finally {
            client.shutdown();
        }
    }

    private static GateEvaluation evaluateLive(AcceptanceRun run, ReSyncFlowClient client, ReadOnlyProbeClient probe,
                                               NodeRegistry registry, String serverId, int minimumNodes,
                                               int expectedReconnects, SessionTracker sessions, int disconnects,
                                               int errors, AcceptanceOptions options, boolean connected) {
        run.disconnects = disconnects;
        run.reconnects = sessions.reconnects();
        run.errors = errors;
        ReSyncFlowClient.HandshakeObservation handshake = client.handshakeObservation();
        run.handshakeStage = handshake.stage().name();
        run.handshakeDiagnostic = handshake.diagnostic();
        run.handshakeFailureDiagnostic = handshake.failureDiagnostic();
        run.handshakeGeneration = handshake.generation();
        run.handshakeActiveGeneration = handshake.activeGeneration();
        run.handshakePendingGeneration = handshake.pendingGeneration();
        run.handshakeAuthenticated = handshake.authenticated();
        run.handshakeConnecting = handshake.connecting();
        run.handshakeStageElapsedMillis = handshake.stageElapsedMillis();
        run.handshakeElapsedMillis = handshake.handshakeElapsedMillis();
        run.observed.put("minimumNodes", minimumNodes);
        run.observed.put("expectedReconnects", expectedReconnects);
        run.observed.put("expectedTombstoneCount", options.tombstones.size());
        run.observed.put("resourceType", options.resourceType != null ? options.resourceType.typeId() : "");
        run.observed.put("resourceId", options.resourceId);
        run.registryNodeCount = registry.getAllDefinitions(serverId).size();
        NodeRegistry.RegistrySessionMetadata metadata = registry.getRegistrySessionMetadata(serverId);
        run.observed.put("registryChecksum", metadata != null ? metadata.checksum() : "");
        run.observed.put("registryContractVersion", metadata != null ? metadata.contractVersion() : 0);
        Optional<ReSyncCatalogPublicationProjection.Snapshot> active = client.catalogPublicationProjection().active();
        ReSyncCatalogPublicationProjection.Snapshot snapshot = active.orElse(null);
        CatalogCacheKey activeKey = snapshot != null ? snapshot.publication().key() : null;
        CatalogCacheKey acknowledgedKey = client.catalogPublicationProjection().acknowledgedKey().orElse(null);
        run.catalogNodeCount = snapshot == null ? 0 : (int) snapshot.entries().values().stream()
            .filter(CatalogCachePublication.Entry::present).count();
        run.effectiveNodeCount = client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            ? run.catalogNodeCount : run.registryNodeCount;
        run.catalogKey = activeKey != null ? activeKey.canonicalText() : "";
        run.acknowledgedCatalogKey = acknowledgedKey != null ? acknowledgedKey.canonicalText() : "";
        run.catalogChecksum = activeKey != null ? activeKey.snapshotChecksum().canonicalText() : "";
        run.catalogProjectionVersion = activeKey != null ? activeKey.projectionVersion().canonicalText() : "";
        run.catalogGeneration = activeKey != null ? activeKey.catalogGeneration() : 0L;
        run.contractVersion = contractVersion(probe.getFlowManager().getServerCapabilities(serverId));
        run.observed.put("catalogAuthority", client.catalogAuthority().name());
        run.observed.put("catalogAuthorityDiagnostic", client.catalogAuthorityDiagnostic().orElse(""));
        run.observed.put("publicationIdentityDiagnostic", client.catalogPublicationIdentityDiagnostic().orElse(""));
        if (snapshot != null) {
            sessions.observePublication();
        }
        run.resourceObservation = observeResource(probe.getFlowManager(), serverId, options.resourceType,
            options.resourceId);
        run.graphObservation = observeGraph(probe.getFlowManager(), serverId, options);

        List<String> failures = new ArrayList<>();
        addCheck(run, failures, "connection", connected, "not_connected");
        addCheck(run, failures, "typedCatalogAuthority",
            client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION,
            String.valueOf(client.catalogAuthority()));
        addCheck(run, failures, "exactAcknowledgedPublicationKey",
            activeKey != null && acknowledgedKey != null && activeKey.equals(acknowledgedKey),
            activeKey == null ? "publication_missing" : acknowledgedKey == null ? "acknowledgement_missing" : "key_mismatch");
        addCheck(run, failures, "publicationServerId",
            activeKey != null && serverId.equals(activeKey.serverId().canonicalText())
                && serverId.equals(client.getServerId()), "server_id_mismatch");
        addCheck(run, failures, "noLegacyDowngrade",
            client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY
                && client.typedCatalogAuthorityAdvertised(), "legacy_compatibility_or_typed_advertisement_missing");
        addCheck(run, failures, "catalogChecksum",
            activeKey != null && !activeKey.snapshotChecksum().canonicalText().isBlank()
                && (options.expectedCatalogChecksum.isBlank()
                || options.expectedCatalogChecksum.equals(activeKey.snapshotChecksum().canonicalText())),
            activeKey == null ? "publication_missing" : "checksum_mismatch");
        addCheck(run, failures, "catalogProjectionVersion",
            activeKey != null && CatalogProjectionVersion.current().equals(activeKey.projectionVersion()),
            activeKey == null ? "publication_missing" : "projection_version_mismatch");
        JsonObject capabilities = probe.getFlowManager().getServerCapabilities(serverId);
        addCheck(run, failures, "flowContract",
            flowContractMatches(capabilities, options.expectedContractVersion), contractDiagnostic(capabilities));
        boolean legacyRegistryReady = metadata != null && !metadata.checksum().isBlank()
            && run.registryNodeCount >= minimumNodes;
        boolean typedAuthority = client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION;
        addCheck(run, failures, "legacyRegistryChecks", typedAuthority || legacyRegistryReady,
            typedAuthority ? "not_authoritative_under_typed_catalog" : "unavailable_or_incomplete");
        addCheck(run, failures, "nodeCount", run.effectiveNodeCount >= minimumNodes,
            "expected_at_least_" + minimumNodes + "_observed_" + run.effectiveNodeCount);
        addCheck(run, failures, "reconnectCount", run.reconnects >= expectedReconnects,
            "expected_at_least_" + expectedReconnects + "_observed_" + run.reconnects);
        addCheck(run, failures, "protocolErrors", errors == 0, "observed_" + errors);
        if (!options.expectedCatalogKey.isBlank()) {
            addCheck(run, failures, "expectedPublicationKey", options.expectedCatalogKey.equals(run.catalogKey),
                "expected_key_mismatch");
        } else {
            addCheck(run, "expectedPublicationKey", true, "not_configured");
        }
        if (options.resourceType != null) {
            boolean resourcePassed = resourceMatches(options.resourceRevision, options.expectedResourceHash,
                run.resourceObservation);
            addCheck(run, failures, "resourceRevisionConvergence", resourcePassed,
                resourceDiagnostic(options.resourceRevision, options.expectedResourceHash, run.resourceObservation));
        } else {
            addCheck(run, "resourceRevisionConvergence", !options.requireGate3bFixtures, "not_configured");
            if (options.requireGate3bFixtures) {
                failures.add("resourceRevisionConvergence");
            }
        }
        if (snapshot != null) {
            List<String> tombstoneFailures = tombstoneFailures(snapshot, options.tombstones);
            addCheck(run, failures, "tombstoneConvergence", tombstoneFailures.isEmpty(),
                tombstoneFailures.isEmpty() ? "ready" : String.join(",", tombstoneFailures));
        } else {
            addCheck(run, failures, "tombstoneConvergence", false, "publication_missing");
        }
        if (options.graphType != null) {
            boolean graphRoundTrip = run.graphObservation.loaded() && run.graphObservation.roundTrip();
            boolean graphSupported = graphSupported(options.graphType, capabilities);
            addCheck(run, failures, "readOnlyGraphRoundTrip",
                graphRoundTrip || !options.requireGraphRoundTrip && !graphSupported,
                graphSupported ? "graph_missing_or_not_lossless" : "unsupported");
            if (options.runRuntime) {
                boolean runtimeIsSupported = graphSupported && runtimeSupported(capabilities);
                JsonObject runtimeResult = sessions.runtimeResult();
                boolean runtimePassed = runtimePassed(runtimeResult, options.runtimeExpectedOutputs);
                addCheck(run, failures, "readOnlyRuntimeRoundTrip",
                    runtimePassed || !options.requireRuntime && !runtimeIsSupported,
                    runtimeIsSupported ? runtimeDiagnostic(runtimeResult, sessions.runtimeLatch()) : "unsupported");
            } else {
                addCheck(run, failures, "readOnlyRuntimeRoundTrip", !options.requireRuntime, "not_configured");
            }
        } else {
            addCheck(run, "readOnlyGraphRoundTrip", !options.requireGraphRoundTrip, "not_configured");
            if (options.requireGraphRoundTrip) {
                failures.add("readOnlyGraphRoundTrip");
            }
            addCheck(run, "readOnlyRuntimeRoundTrip", !options.requireRuntime, "not_configured");
            if (options.requireRuntime) {
                failures.add("readOnlyRuntimeRoundTrip");
            }
        }
        boolean freshSession = freshSessionConverged(expectedReconnects, sessions, options);
        addCheck(run, failures, "freshAuthenticatedSessionConvergence", freshSession,
            freshSession ? "ready" : "required_state_not_observed_after_current_handshake");
        run.observed.put("authenticatedSessions", sessions.current());
        run.observed.put("publicationSession", sessions.publicationSession());
        run.observed.put("resourceRequestSession", sessions.resourceRequestSession());
        run.observed.put("graphRequestSession", sessions.graphRequestSession());
        run.observed.put("runtimeResultSession", sessions.runtimeResultSession());
        run.failures.clear();
        run.failures.addAll(failures.stream().distinct().toList());
        return new GateEvaluation(run.failures.isEmpty(), run.failures);
    }

    private static void addCheck(AcceptanceRun run, String name, boolean passed, String detail) {
        addCheck(run, null, name, passed, detail);
    }

    private static void addCheck(AcceptanceRun run, List<String> failures, String name, boolean passed, String detail) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("passed", passed);
        check.put("detail", detail == null ? "" : detail);
        run.checks.put(name, check);
        if (!passed && failures != null) {
            failures.add(name);
        }
    }

    private static List<String> tombstoneFailures(ReSyncCatalogPublicationProjection.Snapshot snapshot,
                                                   List<TombstoneExpectation> expectations) {
        List<String> failures = new ArrayList<>();
        for (TombstoneExpectation expectation : expectations) {
            if (!tombstoneMatches(snapshot, expectation.key().canonicalText(), expectation.revision())) {
                failures.add(expectation.key().canonicalText() + "@" + expectation.revision());
            }
        }
        return failures;
    }

    static boolean tombstoneMatches(ReSyncCatalogPublicationProjection.Snapshot snapshot, String key,
                                    long revision) {
        if (snapshot == null || key == null || key.isBlank() || revision <= 0L) {
            return false;
        }
        ContractRef<NodeId> parsed;
        try {
            parsed = ContractRef.parseCanonicalText(key, NodeId::new);
        } catch (RuntimeException exception) {
            return false;
        }
        CatalogCachePublication.Entry entry = snapshot.entries().get(parsed);
        return entry != null && entry.tombstone() && entry.revision() == revision;
    }

    static boolean resourceMatchesForTest(long expectedRevision, String expectedHash, long observedRevision,
                                          String observedHash, boolean loaded) {
        return loaded && expectedRevision > 0L && observedRevision == expectedRevision
            && (expectedHash == null || expectedHash.isBlank() || expectedHash.equals(observedHash));
    }

    private static boolean resourceMatches(long expectedRevision, String expectedHash, ResourceObservation observed) {
        return observed != null && resourceMatchesForTest(expectedRevision, expectedHash, observed.revision(),
            observed.hash(), observed.loaded());
    }

    private static String resourceDiagnostic(long expectedRevision, String expectedHash, ResourceObservation observed) {
        String hash = observed == null ? "" : observed.hash();
        long revision = observed == null ? 0L : observed.revision();
        boolean loaded = observed != null && observed.loaded();
        return "expected_revision_" + expectedRevision + "_observed_revision_" + revision
            + "_expected_hash_" + (expectedHash == null ? "" : expectedHash)
            + "_observed_hash_" + hash + "_loaded_" + loaded;
    }

    private static ResourceObservation observeResource(FlowManager manager, String serverId,
                                                        ReSyncResourceType type, String id) {
        if (manager == null || type == null || id == null || id.isBlank()) {
            return ResourceObservation.empty();
        }
        if (type.isGraph()) {
            FlowGraph graph = manager.getGraph(serverId, type, id);
            return graph == null ? ResourceObservation.empty() : new ResourceObservation(graph.getResourceRevision(),
                graph.getResourceHash(), true);
        }
        JsonObject resource = manager.getJsonResourcesForServer(serverId, type).get(id);
        if (resource == null) {
            return ResourceObservation.empty();
        }
        long revision = longProperty(resource, "resourceRevision", "revision");
        String hash = stringProperty(resource, "resourceHash", "hash");
        return new ResourceObservation(revision, hash, true);
    }

    private static GraphObservation observeGraph(FlowManager manager, String serverId, AcceptanceOptions options) {
        if (manager == null || options.graphType == null || options.graphId.isBlank()) {
            return GraphObservation.empty();
        }
        FlowGraph graph = manager.getGraph(serverId, options.graphType, options.graphId);
        if (graph == null) {
            return GraphObservation.empty();
        }
        try {
            String serialized = FlowSerializer.serialize(graph);
            FlowGraph restored = FlowSerializer.deserialize(serialized);
            String sourceCanonical = CanonicalJson.canonicalizeJson(serialized.getBytes(StandardCharsets.UTF_8));
            String restoredCanonical = CanonicalJson.canonicalizeJson(FlowSerializer.serialize(restored)
                .getBytes(StandardCharsets.UTF_8));
            return new GraphObservation(true, sourceCanonical.equals(restoredCanonical), graph.getResourceRevision());
        } catch (RuntimeException exception) {
            return new GraphObservation(true, false, graph.getResourceRevision());
        }
    }

    private static boolean graphReady(FlowManager manager, String serverId, ReSyncResourceType type, String id) {
        return manager != null && manager.getGraph(serverId, type, id) != null;
    }

    static boolean graphSupported(ReSyncResourceType type, JsonObject capabilities) {
        return type != null && type.isGraph() && runtimeSupported(capabilities, "resources");
    }

    private static boolean runtimeSupported(FlowManager manager, String serverId) {
        return manager != null && runtimeSupported(manager.getServerCapabilities(serverId));
    }

    static boolean runtimeSupported(JsonObject capabilities) {
        return runtimeSupported(capabilities, "function_tests");
    }

    private static boolean runtimeSupported(JsonObject capabilities, String capability) {
        if (capabilities == null || !capabilities.has("flowContract") || !capabilities.get("flowContract").isJsonObject()) {
            return false;
        }
        JsonObject contract = capabilities.getAsJsonObject("flowContract");
        for (String property : List.of("negotiated", "capabilities")) {
            if (!contract.has(property) || !contract.get(property).isJsonArray()) {
                continue;
            }
            for (JsonElement element : contract.getAsJsonArray(property)) {
                if (element.isJsonPrimitive() && capability.equals(element.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean runtimePassed(JsonObject result, Map<String, Object> expectedOutputs) {
        if (result == null || !result.has("result") || !result.get("result").isJsonObject()) {
            return false;
        }
        JsonObject payload = result.getAsJsonObject("result");
        if (!payload.has("passed") || !payload.get("passed").getAsBoolean()) {
            return false;
        }
        if (expectedOutputs == null || expectedOutputs.isEmpty()) {
            return true;
        }
        if (!payload.has("outputs")) {
            return false;
        }
        String expected = CanonicalJson.canonicalize(expectedOutputs);
        String actual = CanonicalJson.canonicalizeJson(payload.get("outputs").toString().getBytes(StandardCharsets.UTF_8));
        return expected.equals(actual);
    }

    private static String runtimeDiagnostic(JsonObject result, CountDownLatch latch) {
        if (result == null) {
            return latch != null && latch.getCount() > 0 ? "pending" : "missing_result";
        }
        if (result.has("error") && result.get("error").isJsonObject()) {
            JsonObject error = result.getAsJsonObject("error");
            return error.has("code") ? error.get("code").getAsString() : "runtime_error";
        }
        return "runtime_result_failed";
    }

    private static int contractVersion(JsonObject capabilities) {
        if (capabilities == null || !capabilities.has("flowContract") || !capabilities.get("flowContract").isJsonObject()) {
            return 0;
        }
        JsonObject contract = capabilities.getAsJsonObject("flowContract");
        return contract.has("version") ? contract.get("version").getAsInt() : 0;
    }

    private static boolean flowContractMatches(JsonObject capabilities, int expectedVersion) {
        if (capabilities == null || !capabilities.has("flowContract") || !capabilities.get("flowContract").isJsonObject()) {
            return false;
        }
        JsonObject contract = capabilities.getAsJsonObject("flowContract");
        if (!contract.has("version") || contract.get("version").getAsInt() != expectedVersion) {
            return false;
        }
        int minimum = contract.has("minimumClientVersion") ? contract.get("minimumClientVersion").getAsInt() : 0;
        if (minimum > ReSyncProtocolContract.FLOW_CONTRACT.version()) {
            return false;
        }
        if (!contract.has("negotiated") || !contract.get("negotiated").isJsonArray()) {
            return false;
        }
        List<String> negotiated = new ArrayList<>();
        for (JsonElement element : contract.getAsJsonArray("negotiated")) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                return false;
            }
            negotiated.add(element.getAsString());
        }
        return ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities().stream().allMatch(negotiated::contains);
    }

    private static String contractDiagnostic(JsonObject capabilities) {
        if (capabilities == null || !capabilities.has("flowContract")) {
            return "missing";
        }
        return "version_" + contractVersion(capabilities);
    }

    private static long longProperty(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                try {
                    return object.get(name).getAsLong();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return 0L;
    }

    private static String stringProperty(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                try {
                    return object.get(name).getAsString();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return "";
    }

    private static Path writeReport(AcceptanceRun run) throws IOException {
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        Files.createDirectories(home);
        Path report = home.resolve(REPORT_NAME);
        Path temporary = home.resolve(REPORT_NAME + ".tmp");
        String json = CanonicalJson.canonicalize(run.report());
        Files.writeString(temporary, json + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        }
        return report;
    }

    static String canonicalReportForTest(String mode, String serverId) {
        AcceptanceRun run = new AcceptanceRun(mode, serverId);
        run.status = "passed";
        return CanonicalJson.canonicalize(run.report());
    }

    private static void printResult(AcceptanceRun run, Path report) {
        System.out.println("RESYNC_ACCEPTANCE_RESULT mode=" + run.mode + " serverId=" + run.serverId
            + " nodes=" + run.effectiveNodeCount + " checksum=" + run.catalogChecksum
            + " contractVersion=" + run.contractVersion + " disconnects=" + run.disconnects
            + " reconnects=" + run.reconnects + " errors=" + run.errors + " handshakeStage=" + run.handshakeStage
            + " handshakeDiagnostic=" + run.handshakeDiagnostic + " status=" + run.status + " report=" + report);
    }

    private static String safeFailureName(Throwable failure) {
        if (failure == null) {
            return "unknown_failure";
        }
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "acceptance_failure" : name;
    }

    static void requireTypedGateState(ReSyncFlowClient client, String serverId, String expectedCatalogKey) {
        if (client == null || client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            || !client.typedCatalogAuthorityAdvertised()) {
            throw new IllegalStateException("typed catalog authority is unavailable");
        }
        CatalogCacheKey active = client.catalogPublicationProjection().active()
            .map(ReSyncCatalogPublicationProjection.Snapshot::publication).map(CatalogCachePublication::key).orElse(null);
        CatalogCacheKey acknowledged = client.catalogPublicationProjection().acknowledgedKey().orElse(null);
        if (active == null || acknowledged == null || !active.equals(acknowledged)
            || !serverId.equals(active.serverId().canonicalText())
            || !expectedCatalogKey.isBlank() && !expectedCatalogKey.equals(active.canonicalText())) {
            throw new IllegalStateException("typed catalog publication acknowledgement mismatch");
        }
    }

    static void awaitTypedGateState(ReSyncFlowClient client, String serverId, String expectedCatalogKey,
                                    Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        IllegalStateException last = new IllegalStateException("typed catalog authority is unavailable");
        while (Instant.now().isBefore(deadline)) {
            try {
                requireTypedGateState(client, serverId, expectedCatalogKey);
                return;
            } catch (IllegalStateException failure) {
                last = failure;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("typed catalog acceptance interrupted");
            }
        }
        throw last;
    }

    private record GateEvaluation(boolean ready, List<String> failures) {
    }

    static boolean freshSessionConvergedForTest(int expectedReconnects, int authenticatedSessions,
                                                int publicationSession, int resourceRequestSession,
                                                int graphRequestSession, int runtimeResultSession,
                                                boolean requireResource, boolean requireGraph,
                                                boolean requireRuntime) {
        if (expectedReconnects < 0 || authenticatedSessions < expectedReconnects + 1
            || authenticatedSessions <= 0 || publicationSession != authenticatedSessions) {
            return false;
        }
        if (requireResource && resourceRequestSession != authenticatedSessions) {
            return false;
        }
        if (requireGraph && graphRequestSession != authenticatedSessions) {
            return false;
        }
        return !requireRuntime || runtimeResultSession == authenticatedSessions;
    }

    static int validateExpectedReconnectsForTest(boolean gate3b, int expectedReconnects) {
        return validateExpectedReconnects(gate3b, expectedReconnects);
    }

    private static int validateExpectedReconnects(boolean gate3b, int expectedReconnects) {
        if (expectedReconnects < 0 || gate3b && expectedReconnects == 0) {
            throw new IllegalArgumentException("Gate 3B requires at least one reconnect");
        }
        return expectedReconnects;
    }

    private static boolean freshSessionConverged(int expectedReconnects, SessionTracker sessions,
                                                 AcceptanceOptions options) {
        return freshSessionConvergedForTest(expectedReconnects, sessions.current(), sessions.publicationSession(),
            sessions.resourceRequestSession(), sessions.graphRequestSession(), sessions.runtimeResultSession(),
            options.resourceType != null, options.graphType != null && options.requireGraphRoundTrip,
            options.requireRuntime);
    }

    static final class SessionTracker {
        private final ReSyncFlowClient client;
        private int authenticatedSessions;
        private int publicationSession;
        private int resourceRequestSession;
        private int graphRequestSession;
        private int runtimeRequestSession;
        private int runtimeResultSession;
        private JsonObject runtimeResult;
        private CountDownLatch runtimeLatch;

        SessionTracker(ReSyncFlowClient client) {
            this.client = client;
        }

        synchronized void authenticated() {
            authenticatedSessions++;
            publicationSession = 0;
            resourceRequestSession = 0;
            graphRequestSession = 0;
            runtimeRequestSession = 0;
            runtimeResultSession = 0;
            runtimeResult = null;
            runtimeLatch = null;
            client.catalogPublicationProjection().clear();
            client.requestCatalogPublication(true);
        }

        synchronized int current() {
            return authenticatedSessions;
        }

        private synchronized int reconnects() {
            return Math.max(0, authenticatedSessions - 1);
        }

        private synchronized boolean requestResource(int session) {
            if (session <= 0 || session != authenticatedSessions || resourceRequestSession == session) {
                return false;
            }
            resourceRequestSession = session;
            return true;
        }

        private synchronized boolean requestGraph(int session) {
            if (session <= 0 || session != authenticatedSessions || graphRequestSession == session) {
                return false;
            }
            graphRequestSession = session;
            return true;
        }

        private synchronized boolean graphRequested(int session) {
            return session > 0 && session == authenticatedSessions && graphRequestSession == session;
        }

        private synchronized boolean requestRuntime(int session, CountDownLatch latch) {
            if (session <= 0 || session != authenticatedSessions || runtimeRequestSession == session) {
                return false;
            }
            runtimeRequestSession = session;
            runtimeLatch = latch;
            return true;
        }

        private synchronized void acceptRuntimeResult(int session, JsonObject result) {
            if (session == authenticatedSessions && runtimeRequestSession == session) {
                runtimeResult = result;
                runtimeResultSession = session;
            }
        }

        private synchronized void observePublication() {
            if (authenticatedSessions > 0) {
                publicationSession = authenticatedSessions;
            }
        }

        private synchronized int publicationSession() {
            return publicationSession;
        }

        private synchronized int resourceRequestSession() {
            return resourceRequestSession;
        }

        private synchronized int graphRequestSession() {
            return graphRequestSession;
        }

        private synchronized int runtimeResultSession() {
            return runtimeResultSession;
        }

        private synchronized JsonObject runtimeResult() {
            return runtimeResult;
        }

        private synchronized CountDownLatch runtimeLatch() {
            return runtimeLatch;
        }
    }

    private record ResourceObservation(long revision, String hash, boolean loaded) {
        private static ResourceObservation empty() {
            return new ResourceObservation(0L, "", false);
        }
    }

    private record GraphObservation(boolean loaded, boolean roundTrip, long revision) {
        private static GraphObservation empty() {
            return new GraphObservation(false, false, 0L);
        }
    }

    private record TombstoneExpectation(ContractRef<NodeId> key, long revision) {
    }

    private record AcceptanceOptions(boolean requireGate3bFixtures, String expectedCatalogKey,
                                     String expectedCatalogChecksum, int expectedContractVersion,
                                     ReSyncResourceType resourceType, String resourceId, long resourceRevision,
                                     String expectedResourceHash, List<TombstoneExpectation> tombstones,
                                     ReSyncResourceType graphType, String graphId, boolean requireGraphRoundTrip,
                                     boolean runRuntime, boolean requireRuntime, String runtimeName,
                                     Map<String, Object> runtimeInputs, Map<String, Object> runtimeExpectedOutputs,
                                     Map<String, Object> runtimeServerContext, String runtimeClockInstant,
                                     String runtimeZoneId, long runtimeTimeoutMillis) {
        private static AcceptanceOptions fromEnvironment(boolean requireGate3bFixtures, String serverId) {
            String expectedCatalogKey = environment("RESYNC_ACCEPTANCE_EXPECT_CATALOG_KEY", "");
            String expectedCatalogChecksum = environment("RESYNC_ACCEPTANCE_EXPECT_CATALOG_CHECKSUM", "");
            int expectedContractVersion = positiveOrZero("RESYNC_ACCEPTANCE_EXPECT_CONTRACT_VERSION",
                ReSyncProtocolContract.FLOW_CONTRACT.version());
            String resourceTypeText = environment("RESYNC_ACCEPTANCE_RESOURCE_TYPE", "");
            String resourceId = environment("RESYNC_ACCEPTANCE_RESOURCE_ID", "");
            long resourceRevision = longEnvironment("RESYNC_ACCEPTANCE_EXPECT_RESOURCE_REVISION", 0L);
            ReSyncResourceType resourceType = resourceTypeText.isBlank() ? null : ReSyncResourceType.byTypeId(resourceTypeText);
            String tombstoneText = environment("RESYNC_ACCEPTANCE_EXPECT_CATALOG_TOMBSTONES",
                environment("RESYNC_ACCEPTANCE_EXPECT_TOMBSTONES", ""));
            List<TombstoneExpectation> tombstones = parseTombstones(tombstoneText);
            String graphTypeText = environment("RESYNC_ACCEPTANCE_READ_ONLY_GRAPH_TYPE", "");
            String graphId = environment("RESYNC_ACCEPTANCE_READ_ONLY_GRAPH_ID", "");
            ReSyncResourceType graphType = graphTypeText.isBlank() ? null : ReSyncResourceType.byTypeId(graphTypeText);
            if (graphType == null && resourceType != null && resourceType.isGraph() && graphTypeText.isBlank()) {
                graphType = resourceType;
                graphId = resourceId;
            }
            boolean runRuntime = Boolean.parseBoolean(environment("RESYNC_ACCEPTANCE_RUN_READ_ONLY_RUNTIME", "false"));
            boolean requireGraphRoundTrip = Boolean.parseBoolean(environment("RESYNC_ACCEPTANCE_REQUIRE_GRAPH_ROUND_TRIP", "false"));
            boolean requireRuntime = Boolean.parseBoolean(environment("RESYNC_ACCEPTANCE_REQUIRE_READ_ONLY_RUNTIME", "false"));
            String runtimeName = environment("RESYNC_ACCEPTANCE_RUNTIME_NAME", "Gate 3B Read Only Fixture");
            Map<String, Object> runtimeInputs = jsonMap("RESYNC_ACCEPTANCE_RUNTIME_INPUTS_JSON", "{}");
            Map<String, Object> runtimeExpectedOutputs = jsonMap("RESYNC_ACCEPTANCE_RUNTIME_EXPECTED_OUTPUTS_JSON", "{}");
            Map<String, Object> runtimeServerContext = jsonMap("RESYNC_ACCEPTANCE_RUNTIME_SERVER_CONTEXT_JSON", "{}");
            String runtimeClockInstant = environment("RESYNC_ACCEPTANCE_RUNTIME_CLOCK_INSTANT", "");
            String runtimeZoneId = environment("RESYNC_ACCEPTANCE_RUNTIME_ZONE_ID", "UTC");
            long runtimeTimeoutMillis = longEnvironment("RESYNC_ACCEPTANCE_RUNTIME_TIMEOUT_MILLIS", 5000L);
            if (requireGate3bFixtures && (resourceType == null || resourceId.isBlank() || resourceRevision <= 0L)) {
                throw new IllegalArgumentException("Gate 3B requires resource type, resource ID, and positive resource revision");
            }
            if (requireGate3bFixtures && tombstones.isEmpty()) {
                throw new IllegalArgumentException("Gate 3B requires catalog tombstone fixtures");
            }
            if (requireGate3bFixtures && expectedCatalogKey.isBlank()) {
                throw new IllegalArgumentException("Gate 3B requires the expected catalog publication key");
            }
            String expectedResourceHash = readExpectedResourceHash();
            if (requireGate3bFixtures && expectedResourceHash.isBlank()) {
                throw new IllegalArgumentException("Gate 3B requires the expected resource hash");
            }
            if (!expectedCatalogKey.isBlank()) {
                try {
                    CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(expectedCatalogKey);
                    if (!serverId.equals(key.serverId().canonicalText())) {
                        throw new IllegalArgumentException("Expected catalog publication key belongs to another server");
                    }
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Expected catalog publication key is invalid", exception);
                }
            }
            if (!resourceTypeText.isBlank() && resourceType == null) {
                throw new IllegalArgumentException("Unknown ReSync resource type: " + resourceTypeText);
            }
            if (!graphTypeText.isBlank() && (graphType == null || !graphType.isGraph() || graphId.isBlank())) {
                throw new IllegalArgumentException("Read-only graph fixture must name a graph resource type and ID");
            }
            if (runRuntime && graphType == null) {
                throw new IllegalArgumentException("Read-only runtime requires a graph fixture");
            }
            if (requireGate3bFixtures) {
                if (graphType == null || !graphType.isGraph() || graphId.isBlank()) {
                    throw new IllegalArgumentException("Gate 3B requires a graph resource type and ID");
                }
                if (!runRuntime || !hasEnvironment("RESYNC_ACCEPTANCE_RUNTIME_EXPECTED_OUTPUTS_JSON")) {
                    throw new IllegalArgumentException("Gate 3B requires the enabled read-only runtime fixture and expected outputs");
                }
                requireGraphRoundTrip = true;
                requireRuntime = true;
            }
            return new AcceptanceOptions(requireGate3bFixtures, expectedCatalogKey, expectedCatalogChecksum,
                expectedContractVersion, resourceType, resourceId, resourceRevision, expectedResourceHash, tombstones,
                graphType, graphId, requireGraphRoundTrip, runRuntime, requireRuntime, runtimeName, runtimeInputs,
                runtimeExpectedOutputs, runtimeServerContext, runtimeClockInstant, runtimeZoneId,
                Math.clamp(runtimeTimeoutMillis, 1L, 30000L));
        }

        private static String readExpectedResourceHash() {
            return environment("RESYNC_ACCEPTANCE_EXPECT_RESOURCE_HASH", "");
        }

        private static List<TombstoneExpectation> parseTombstones(String text) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            List<TombstoneExpectation> result = new ArrayList<>();
            for (String raw : text.split(",")) {
                String value = raw.trim();
                int separator = value.lastIndexOf('@');
                if (separator <= 0 || separator == value.length() - 1) {
                    throw new IllegalArgumentException("Catalog tombstone fixtures must use owner/node@revision");
                }
                try {
                    long revision = Long.parseLong(value.substring(separator + 1));
                    if (revision <= 0L) {
                        throw new IllegalArgumentException("Catalog tombstone revision must be positive");
                    }
                    ContractRef<NodeId> key = ContractRef.parseCanonicalText(value.substring(0, separator), NodeId::new);
                    result.add(new TombstoneExpectation(key, revision));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid catalog tombstone fixture", exception);
                }
            }
            return List.copyOf(result);
        }

        private static Map<String, Object> jsonMap(String name, String fallback) {
            String value = environment(name, fallback);
            try {
                JsonElement parsed = JsonParser.parseString(value);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException(name + " must be a JSON object");
                }
                Map<String, Object> result = GSON.fromJson(parsed, new TypeToken<LinkedHashMap<String, Object>>() {
                }.getType());
                return result == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(result));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(name + " is invalid JSON", exception);
            }
        }
    }

    private static final class AcceptanceRun {
        private final String mode;
        private final String serverId;
        private final Map<String, Object> observed = new LinkedHashMap<>();
        private final Map<String, Object> checks = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private String status = "failed";
        private int effectiveNodeCount;
        private int registryNodeCount;
        private int catalogNodeCount;
        private int contractVersion;
        private int disconnects;
        private int reconnects;
        private int errors;
        private int handshakeGeneration;
        private int handshakeActiveGeneration = -1;
        private int handshakePendingGeneration;
        private long catalogGeneration;
        private long handshakeStageElapsedMillis;
        private long handshakeElapsedMillis;
        private boolean handshakeAuthenticated;
        private boolean handshakeConnecting;
        private String handshakeStage = ReSyncFlowClient.HandshakeStage.IDLE.name();
        private String handshakeDiagnostic = "idle";
        private String handshakeFailureDiagnostic = "";
        private String catalogChecksum = "";
        private String catalogKey = "";
        private String acknowledgedCatalogKey = "";
        private String catalogProjectionVersion = "";
        private ResourceObservation resourceObservation = ResourceObservation.empty();
        private GraphObservation graphObservation = GraphObservation.empty();

        private AcceptanceRun(String mode, String serverId) {
            this.mode = mode;
            this.serverId = serverId;
        }

        private Map<String, Object> report() {
            observed.put("effectiveNodeCount", effectiveNodeCount);
            observed.put("registryNodeCount", registryNodeCount);
            observed.put("catalogNodeCount", catalogNodeCount);
            observed.put("contractVersion", contractVersion);
            observed.put("catalogGeneration", catalogGeneration);
            observed.put("catalogChecksum", catalogChecksum);
            observed.put("catalogKey", catalogKey);
            observed.put("acknowledgedCatalogKey", acknowledgedCatalogKey);
            observed.put("catalogProjectionVersion", catalogProjectionVersion);
            observed.put("resourceRevision", resourceObservation.revision());
            observed.put("resourceHash", resourceObservation.hash());
            observed.put("resourceLoaded", resourceObservation.loaded());
            observed.put("graphLoaded", graphObservation.loaded());
            observed.put("graphRoundTrip", graphObservation.roundTrip());
            observed.put("disconnects", disconnects);
            observed.put("reconnects", reconnects);
            observed.put("errors", errors);
            observed.put("handshakeStage", handshakeStage);
            observed.put("handshakeDiagnostic", handshakeDiagnostic);
            observed.put("handshakeFailureDiagnostic", handshakeFailureDiagnostic);
            observed.put("handshakeGeneration", handshakeGeneration);
            observed.put("handshakeActiveGeneration", handshakeActiveGeneration);
            observed.put("handshakePendingGeneration", handshakePendingGeneration);
            observed.put("handshakeAuthenticated", handshakeAuthenticated);
            observed.put("handshakeConnecting", handshakeConnecting);
            observed.put("handshakeStageElapsedMillis", handshakeStageElapsedMillis);
            observed.put("handshakeElapsedMillis", handshakeElapsedMillis);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 2);
            report.put("status", status);
            report.put("mode", mode);
            report.put("serverId", serverId);
            report.put("observed", observed);
            report.put("checks", checks);
            report.put("failures", failures.stream().distinct().sorted().toList());
            return report;
        }
    }

    static final class ReadOnlyProbeClient extends RemotelyClient {
        private final FlowManager flowManager;

        ReadOnlyProbeClient() {
            super(null);
            flowManager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return flowManager;
        }

        synchronized void shutdownProbe() {
            flowManager.shutdown();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static boolean hasEnvironment(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static int positiveOrZero(String name, int fallback) {
        long value = longEnvironment(name, fallback);
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
        return (int) value;
    }

    private static long longEnvironment(String name, long fallback) {
        String value = environment(name, Long.toString(fallback));
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) {
                throw new IllegalArgumentException(name + " must be non-negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be numeric", exception);
        }
    }
}
