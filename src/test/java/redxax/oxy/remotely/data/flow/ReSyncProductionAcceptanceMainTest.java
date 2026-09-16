package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.catalog.CatalogVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncProductionAcceptanceMainTest {

    @Test
    void directWebSocketOwnerIsRetainedWithoutNullFrameTransportIdentity() throws Exception {
        ServerId server = new ServerId(UUID.fromString("12121212-1212-4212-8212-121212121212"));
        ReSyncProductionAcceptanceMain.ReadOnlyProbeClient probe =
            new ReSyncProductionAcceptanceMain.ReadOnlyProbeClient();
        String wsUrl = "ws://127.0.0.1:1";
        String apiKey = "unused";
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), null, wsUrl, apiKey, probe);
        FlowManager manager = probe.getFlowManager();
        FlowManagerTestConnection.installDirectCurrent(manager, server.canonicalText(), client, wsUrl, apiKey);
        FlowManagerTestConnection.primeConnecting(client, 7);

        try {
            assertFalse(client.usesFrameTransport(null));
            assertTrue(client.usesDirectWebSocketTransport());
            assertSame(client, FlowManagerTestConnection.ensurePublicCurrent(manager, server.canonicalText()));
            assertEquals(7, FlowManagerTestConnection.atomicInt(client, "connectionGeneration"));
            assertEquals(7, FlowManagerTestConnection.atomicInt(client, "pendingHandshakeGeneration"));
            assertFalse(FlowManagerTestConnection.booleanValue(client, "shutdownRequested"));
        } finally {
            client.shutdown();
            probe.shutdownProbe();
        }
    }
    private static final OwnerId PROTOCOL_OWNER = new OwnerId("restudio.resync");
    private static final ProtocolEnvelopeCodec<Map<String, Object>> PROTOCOL_CODEC =
        new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());

    @Test
    void localTransportAcceptsExactTypedPublicationAcknowledgement(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("abababab-abab-4aba-8aba-abababababab"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        CatalogCachePublication publication = publication(server, 7);
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key()));
            transport.receivePublication(new CatalogCachePublicationCodec().encodeBytes(publication));
            ReSyncFlowClientTestHarness.drain(client);

            assertDoesNotThrow(() -> ReSyncProductionAcceptanceMain.awaitTypedGateState(client,
                server.canonicalText(), publication.key().canonicalText(), Duration.ofSeconds(2L)));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void localTransportRejectsWrongServerAndLegacyDowngrade(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("cdcdcdcd-cdcd-4cdc-8dcd-cdcdcdcdcdcd"));
        ServerId other = new ServerId(UUID.fromString("dededede-dede-4ede-8ede-dededededede"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        try {
            client.connect().join();
            CatalogCachePublication publication = publication(other, 7);
            transport.receiveHandshake(capabilities(publication.key()));
            transport.receivePublication(new CatalogCachePublicationCodec().encodeBytes(publication));
            ReSyncFlowClientTestHarness.drain(client);

            assertThrows(IllegalStateException.class, () -> ReSyncProductionAcceptanceMain.requireTypedGateState(
                client, server.canonicalText(), publication.key().canonicalText()));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void localTransportRejectsActualLegacyHandshakeDowngrade(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("45454545-4545-4454-8454-454545454545"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        try {
            client.connect().join();
            transport.receiveHandshake(identityCapabilities(server));
            ReSyncFlowClientTestHarness.drain(client);

            assertEquals(ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY, client.catalogAuthority());
            assertThrows(IllegalStateException.class, () -> ReSyncProductionAcceptanceMain.requireTypedGateState(
                client, server.canonicalText(), ""));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void missingPublicationFailsClosedAfterAHandshakeTimeout(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("efefefef-efef-4fef-8fef-efefefefefef"));
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new CapturingTransport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        try {
            assertThrows(IllegalStateException.class, () -> ReSyncProductionAcceptanceMain.awaitTypedGateState(
                client, server.canonicalText(), "", Duration.ofMillis(50)));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void localTransportCountsErrorsAndReconnects(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("fefefefe-fefe-4fef-8fef-fefefefefefe"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CatalogCachePublication publication = publication(server, 7);
        client.setConnectionListener(connections::incrementAndGet);
        client.setDisconnectListener(disconnects::incrementAndGet);
        client.setErrorListener((nodeId, message) -> errors.incrementAndGet());
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key()));
            transport.receivePublication(new CatalogCachePublicationCodec().encodeBytes(publication));
            transport.receiveError();
            ReSyncFlowClientTestHarness.drain(client);
            transport.closeTransport();
            ReSyncFlowClientTestHarness.drain(client);
            transport.reopen();
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key()));
            ReSyncFlowClientTestHarness.drain(client);

            assertEquals(2, connections.get());
            assertTrue(disconnects.get() >= 1);
            assertTrue(errors.get() >= 1);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void liveHarnessRetainsTheAttachedManagerAcrossReconnect(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("34343434-3434-4434-8434-343434343434"));
        CatalogCachePublication firstPublication = publication(server, 3, 7, "a");
        CatalogCachePublication secondPublication = publication(server, 4, 8, "c");
        CapturingTransport transport = new CapturingTransport();
        ReSyncProductionAcceptanceMain.ReadOnlyProbeClient probe =
            new ReSyncProductionAcceptanceMain.ReadOnlyProbeClient();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        ReSyncProductionAcceptanceMain.SessionTracker sessions =
            new ReSyncProductionAcceptanceMain.SessionTracker(client);
        FlowManager manager = probe.getFlowManager();
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        FlowManagerTestConnection.installCurrent(manager, server.canonicalText(), client, transport);
        FlowManagerTestConnection.observeCurrent(manager, client, ignored -> sessions.authenticated(),
            ignored -> disconnects.incrementAndGet(), (nodeId, message) -> errors.incrementAndGet());
        try {
            assertSame(client, FlowManagerTestConnection.connectCurrent(manager, server.canonicalText()));
            transport.receiveHandshake(capabilities(firstPublication.key()));
            awaitSession(client, sessions, 1);
            transport.receivePublication(new CatalogCachePublicationCodec().encodeBytes(firstPublication));
            ReSyncFlowClientTestHarness.drain(client);

            assertSame(manager, probe.getFlowManager());
            assertNotNull(manager.getServerCapabilities(server.canonicalText()));
            int firstRequestMarker = transport.frameCount();
            client.requestResource(ReSyncResourceType.COMMAND, "old-command", false);
            assertNotNull(transport.requestEnvelope(ResourceOperationKind.LOAD, "old-command", firstRequestMarker));

            transport.closeTransport();
            awaitCount(client, disconnects, 1);
            transport.reopen();
            assertSame(client, FlowManagerTestConnection.connectCurrent(manager, server.canonicalText()));
            transport.receiveHandshake(capabilities(secondPublication.key()));
            awaitSession(client, sessions, 2);
            transport.receivePublication(new CatalogCachePublicationCodec().encodeBytes(secondPublication));
            ReSyncFlowClientTestHarness.drain(client);

            assertSame(manager, probe.getFlowManager());
            assertNotNull(manager.getServerCapabilities(server.canonicalText()));
            int secondRequestMarker = transport.frameCount();
            client.requestResource(ReSyncResourceType.COMMAND, "old-command", false);
            assertNotNull(transport.requestEnvelope(ResourceOperationKind.LOAD, "old-command", secondRequestMarker));
            assertTrue(disconnects.get() >= 1);
            assertEquals(0, errors.get());
        } finally {
            client.shutdown();
            probe.shutdownProbe();
        }
    }

    @Test
    void resourceRevisionAndHashMismatchFailsClosed() {
        assertFalse(ReSyncProductionAcceptanceMain.resourceMatchesForTest(7, "expected", 6,
            "expected", true));
        assertFalse(ReSyncProductionAcceptanceMain.resourceMatchesForTest(7, "expected", 7,
            "observed", true));
        assertFalse(ReSyncProductionAcceptanceMain.resourceMatchesForTest(7, "expected", 7,
            "expected", false));
        assertTrue(ReSyncProductionAcceptanceMain.resourceMatchesForTest(7, "expected", 7,
            "expected", true));
    }

    @Test
    void tombstoneRevisionMismatchFailsClosed() {
        ServerId server = new ServerId(UUID.fromString("12121212-1212-4212-8212-121212121212"));
        ContractRef<NodeId> node = ContractRef.of(new OwnerId("acceptance.fixture"), new NodeId("removed"));
        CatalogCacheKey key = new CatalogCacheKey(server, 4, new ContentHash("b".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 9,
            List.of(CatalogCachePublication.Entry.tombstone(node, 9)));
        ReSyncCatalogPublicationProjection projection = ReSyncCatalogPublicationProjection
            .forConfiguredServerId(server.canonicalText());
        assertTrue(projection.apply(publication, new CatalogCachePublicationCodec().encodeBytes(publication)));
        ReSyncCatalogPublicationProjection.Snapshot snapshot = projection.active().orElseThrow();

        assertTrue(ReSyncProductionAcceptanceMain.tombstoneMatches(snapshot, node.canonicalText(), 9));
        assertFalse(ReSyncProductionAcceptanceMain.tombstoneMatches(snapshot, node.canonicalText(), 8));
        assertFalse(ReSyncProductionAcceptanceMain.tombstoneMatches(snapshot, "acceptance.fixture/other", 9));
    }

    @Test
    void graphAndRuntimeUnsupportedOrFailedFixturesFailClosed() {
        JsonObject missingCapabilities = new JsonObject();
        assertFalse(ReSyncProductionAcceptanceMain.graphSupported(ReSyncResourceType.FLOW, missingCapabilities));
        assertFalse(ReSyncProductionAcceptanceMain.runtimeSupported(missingCapabilities));

        JsonObject graphOnly = new JsonObject();
        JsonObject graphContract = new JsonObject();
        JsonArray negotiated = new JsonArray();
        negotiated.add("resources");
        graphContract.add("negotiated", negotiated);
        graphOnly.add("flowContract", graphContract);
        assertTrue(ReSyncProductionAcceptanceMain.graphSupported(ReSyncResourceType.FLOW, graphOnly));
        assertFalse(ReSyncProductionAcceptanceMain.runtimeSupported(graphOnly));

        JsonObject failedResult = new JsonObject();
        JsonObject failedPayload = new JsonObject();
        failedPayload.addProperty("passed", false);
        failedResult.add("result", failedPayload);
        assertFalse(ReSyncProductionAcceptanceMain.runtimePassed(failedResult, Map.of()));
    }

    @Test
    void postReconnectStateMustBeFreshAcrossAllGateFixtures() {
        assertFalse(ReSyncProductionAcceptanceMain.freshSessionConvergedForTest(1, 2, 2,
            1, 2, 2, true, true, true));
        assertFalse(ReSyncProductionAcceptanceMain.freshSessionConvergedForTest(1, 2, 2,
            2, 2, 1, true, true, true));
        assertTrue(ReSyncProductionAcceptanceMain.freshSessionConvergedForTest(1, 2, 2,
            2, 2, 2, true, true, true));
    }

    @Test
    void gate3bRequiresAtLeastOneReconnect() {
        assertThrows(IllegalArgumentException.class,
            () -> ReSyncProductionAcceptanceMain.validateExpectedReconnectsForTest(true, 0));
        assertEquals(0, ReSyncProductionAcceptanceMain.validateExpectedReconnectsForTest(false, 0));
        assertEquals(1, ReSyncProductionAcceptanceMain.validateExpectedReconnectsForTest(true, 1));
    }

    @Test
    void canonicalReportContainsOnlySafeAcceptanceFields() {
        String report = ReSyncProductionAcceptanceMain.canonicalReportForTest("live", "fixture-server");
        JsonObject root = JsonParser.parseString(report).getAsJsonObject();

        assertEquals("passed", root.get("status").getAsString());
        assertEquals("live", root.get("mode").getAsString());
        assertEquals("fixture-server", root.get("serverId").getAsString());
        assertTrue(root.has("observed"));
        assertTrue(root.has("checks"));
        assertTrue(root.has("failures"));
        assertFalse(report.contains("api-secret"));
        assertFalse(report.contains("wss://private.example"));
        assertFalse(report.contains("secret-fixture"));
    }

    @Test
    void realSecretsDoNotEnterReportOrCatalogCache(@TempDir Path tempDirectory) throws Exception {
        String apiKey = "gate3b-api-key-secret";
        String wsUrl = "wss://gate3b-private.example/live";
        String report = ReSyncProductionAcceptanceMain.canonicalReportForTest("live", "fixture-server");
        assertFalse(report.contains(apiKey));
        assertFalse(report.contains(wsUrl));

        ServerId server = new ServerId(UUID.fromString("56565656-5656-4656-8656-565656565656"));
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json")));
        CatalogCachePublication publication = publication(server, 7);
        byte[] encoded = new CatalogCachePublicationCodec().encodeBytes(publication);
        assertTrue(cache.store(server, publication, encoded, publication));
        String cached = cache.storage().read("payload");
        assertFalse(cached.contains(apiKey));
        assertFalse(cached.contains(wsUrl));
    }

    @Test
    void hermeticFixtureCoversTypedResourceGraphRuntimeAndReconnect(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("67676767-6767-4676-8676-676767676767"));
        CatalogCachePublication publication = publication(server, 7);
        CatalogCachePublication reconnectedPublication = publication(server, 4, 8, "c");
        byte[] publicationBytes = new CatalogCachePublicationCodec().encodeBytes(publication);
        byte[] reconnectedPublicationBytes = new CatalogCachePublicationCodec().encodeBytes(reconnectedPublication);
        CapturingTransport transport = new CapturingTransport();
        FixtureProbe probe = new FixtureProbe();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))));
        FlowManagerTestConnection.installCurrent(probe.flowManager, server.canonicalText(), client, transport);
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key()));
            transport.receivePublication(publicationBytes);
            ReSyncFlowClientTestHarness.drain(client);
            ReSyncProductionAcceptanceMain.awaitTypedGateState(client, server.canonicalText(),
                publication.key().canonicalText(), Duration.ofSeconds(2L));

            int firstRequestMarker = transport.frameCount();
            client.requestResource(ReSyncResourceType.FLOW, "fixture-flow", false);
            ProtocolEnvelope<Map<String, Object>> firstRequest = transport.requestEnvelope(
                ResourceOperationKind.LOAD, "fixture-flow", firstRequestMarker);
            transport.receiveEnvelope(PROTOCOL_CODEC.encodeBytes(documentResponse(firstRequest, 6,
                publication.key())));
            awaitGraphRevision(client, probe.getFlowManager(), server.canonicalText(), 6);
            AtomicReference<JsonObject> firstRuntimeResult = new AtomicReference<>();
            CountDownLatch firstRuntime = requestRuntime(client, probe, server.canonicalText(), firstRuntimeResult,
                transport);
            assertTrue(firstRuntime.await(2, TimeUnit.SECONDS));
            assertTrue(firstRuntimeResult.get().getAsJsonObject("result").get("passed").getAsBoolean());

            transport.closeTransport();
            transport.reopen();
            client.connect().join();
            transport.receiveHandshake(capabilities(reconnectedPublication.key()));
            transport.receivePublication(reconnectedPublicationBytes);
            ReSyncFlowClientTestHarness.drain(client);
            ReSyncProductionAcceptanceMain.awaitTypedGateState(client, server.canonicalText(),
                reconnectedPublication.key().canonicalText(), Duration.ofSeconds(2L));
            int secondRequestMarker = transport.frameCount();
            client.requestResource(ReSyncResourceType.FLOW, "fixture-flow", false);
            ProtocolEnvelope<Map<String, Object>> secondRequest = transport.requestEnvelope(
                ResourceOperationKind.LOAD, "fixture-flow", secondRequestMarker);
            transport.receiveEnvelope(PROTOCOL_CODEC.encodeBytes(documentResponse(secondRequest, 7,
                reconnectedPublication.key())));
            awaitGraphRevision(client, probe.getFlowManager(), server.canonicalText(), 7);
            AtomicReference<JsonObject> secondRuntimeResult = new AtomicReference<>();
            CountDownLatch secondRuntime = requestRuntime(client, probe, server.canonicalText(), secondRuntimeResult,
                transport);
            assertTrue(secondRuntime.await(2, TimeUnit.SECONDS));
            assertTrue(secondRuntimeResult.get().getAsJsonObject("result").get("passed").getAsBoolean());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    private static void awaitGraphRevision(ReSyncFlowClient client, FlowManager manager, String serverId,
                                           long expectedRevision) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        FlowGraph graph = null;
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(client);
            graph = manager.getGraph(serverId, ReSyncResourceType.FLOW, "fixture-flow");
            if (graph != null && graph.getResourceRevision() == expectedRevision) {
                return;
            }
            Thread.sleep(1L);
        }
        assertEquals(expectedRevision, graph == null ? -1L : graph.getResourceRevision());
    }

    private static void awaitSession(ReSyncFlowClient client,
                                     ReSyncProductionAcceptanceMain.SessionTracker sessions,
                                     int expectedSession) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(client);
            if (sessions.current() == expectedSession) {
                return;
            }
            Thread.sleep(1L);
        }
        assertEquals(expectedSession, sessions.current());
    }

    private static void awaitCount(ReSyncFlowClient client, AtomicInteger counter, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(client);
            if (counter.get() >= expected) {
                return;
            }
            Thread.sleep(1L);
        }
        assertTrue(counter.get() >= expected);
    }

    private static CountDownLatch requestRuntime(ReSyncFlowClient client, FixtureProbe probe, String serverId,
                                                 AtomicReference<JsonObject> resultReference,
                                                 CapturingTransport transport) {
        CountDownLatch result = new CountDownLatch(1);
        FlowGraph graph = probe.getFlowManager().getGraph(serverId, ReSyncResourceType.FLOW, "fixture-flow");
        client.requestFunctionTest(graph, "Gate 3B Fixture", Map.of(), Map.of("value", 1), Map.of(), "", "UTC",
            1000, response -> {
                resultReference.set(response);
                result.countDown();
            });
        transport.receiveFlowPayload(ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT,
            functionResult(transport.lastFunctionTestRequest().payload()));
        return result;
    }

    private static ProtocolEnvelope<Map<String, Object>> documentResponse(
        ProtocolEnvelope<Map<String, Object>> request, long revision, CatalogCacheKey key) {
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), request.resource(), revision,
            key.catalogBinding(), Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        UUID mutationId = UUID.randomUUID();
        Map<String, Object> payload = graphAssetPayload(graph, mutationId);
        ResourceDocument<Map<String, Object>> document = ResourceDocument.live(request.resource(), revision,
            mutationId, ResourcePayloadCodecs.json().canonicalize(payload), ResourceActivationState.ACTIVE, "server");
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.ACK, request.contractVersion(), UUID.randomUUID(),
            request.requestId(), request.correlationId(), request.traceId(), request.serverId(), request.resource(),
            revision, request.authorityEpoch(), mutationId, ContractRef.of(PROTOCOL_OWNER,
            OperationId.of("resource.load")), request.capabilities(), ContractRef.of(PROTOCOL_OWNER,
            ResourceTypeId.of("resource.document")), null, document.payloadHash(), false, null, null, null, null,
            null, request.sequence(), ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.LOAD, document));
    }

    private static Map<String, Object> graphAssetPayload(GraphDocument graph, UUID mutationId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, graph.resource().resourceType().value());
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION,
            CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, graph.revision());
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutationId.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, ResourceActivationState.ACTIVE.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION,
            CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        GraphDocumentCodec.INSTANCE.encode(graph).fields().forEach((name, value) -> fields.put(name, value.toJava()));
        fields.put(CoreGraphResourceProjection.ASSET_HASH,
            new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields)).canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private static byte[] functionResult(byte[] requestPayload) {
        JsonObject request = JsonParser.parseString(new String(requestPayload, 1,
            requestPayload.length - 1, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject result = new JsonObject();
        result.addProperty("requestId", request.get("requestId").getAsString());
        JsonObject payload = new JsonObject();
        payload.addProperty("passed", true);
        JsonObject outputs = new JsonObject();
        outputs.addProperty("value", 1);
        payload.add("outputs", outputs);
        result.add("result", payload);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static CatalogCachePublication publication(ServerId server, long revision) {
        return publication(server, 3, revision, "a");
    }

    private static CatalogCachePublication publication(ServerId server, long generation, long revision,
                                                       String hashCharacter) {
        CatalogCacheKey key = new CatalogCacheKey(server, generation, new ContentHash(hashCharacter.repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        ContractRef<NodeId> node = ContractRef.of(new OwnerId("acceptance.fixture"), new NodeId("read-only"));
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(node, revision,
            CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of("{\"id\":\"read-only\"}".getBytes(StandardCharsets.UTF_8)));
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            authoringSection(CatalogAuthoringPublication.Section.TYPES),
            authoringSection(CatalogAuthoringPublication.Section.EDITORS),
            authoringSection(CatalogAuthoringPublication.Section.PREVIEWS),
            authoringSection(CatalogAuthoringPublication.Section.CAPABILITIES));
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(key.catalogBinding(),
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), sections, Set.of());
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, key.catalogBinding(), revision,
            List.of(entry), authoring, Map.of());
    }

    private static CatalogAuthoringPublication.SectionProjection authoringSection(
        CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE,
            List.of());
    }

    private static String capabilities(CatalogCacheKey key) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", key.serverId().canonicalText());
        root.addProperty("authorityEpoch", 1L);
        root.addProperty("catalogPublicationKey", key.canonicalText());
        JsonObject contract = new JsonObject();
        contract.addProperty("version", ReSyncProtocolContract.FLOW_CONTRACT.version());
        contract.addProperty("minimumClientVersion", 0);
        JsonArray supported = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().forEach(supported::add);
        contract.add("supported", supported);
        JsonArray negotiated = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.negotiate(ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities())
            .forEach(negotiated::add);
        contract.add("negotiated", negotiated);
        root.add("flowContract", contract);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject operations = new JsonObject();
        JsonArray reads = new JsonArray();
        reads.add("list");
        reads.add("query");
        reads.add("load");
        operations.add("read", reads);
        operations.add("mutate", new JsonArray());
        protocol.add("resourceOperations", operations);
        JsonObject version = new JsonObject();
        version.addProperty("generation", 1);
        version.addProperty("minor", 1);
        protocol.add("resourceContractVersion", version);
        root.add("protocolEnvelope", protocol);
        return root.toString();
    }

    private static String identityCapabilities(ServerId serverId) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", serverId.canonicalText());
        return root.toString();
    }

    private static byte[] handshakeResponse(String capabilities) {
        byte[] capabilityBytes = capabilities.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4 + Integer.BYTES * 3 + capabilityBytes.length);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(capabilityBytes.length);
        payload.put(capabilityBytes);
        return payload.array();
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
        private final List<ReSyncDecodedFrame> sentFrames = new ArrayList<>();
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;
        private boolean open = true;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            frameHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public synchronized void send(byte[] frame) {
            ReSyncDecodedFrame decoded = codec.decode(frame, null);
            sentFrames.add(decoded);
        }

        @Override
        public void close() {
            closeTransport();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        private void receiveHandshake(String capabilities) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                handshakeResponse(capabilities), (short) 0, 1));
        }

        private void receivePublication(byte[] publication) {
            ByteBuffer payload = ByteBuffer.allocate(1 + publication.length);
            payload.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
            payload.put(publication);
            receiveFlowPayload(payload.array());
        }

        private void receiveFlowPayload(byte packet, byte[] payload) {
            ByteBuffer buffer = ByteBuffer.allocate(1 + payload.length);
            buffer.put(packet);
            buffer.put(payload);
            receiveFlowPayload(buffer.array());
        }

        private void receiveFlowPayload(byte[] payload) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_DATA, payload,
                ReSyncProtocolContract.CHANNEL_FLOW_ID, 2));
        }

        private void receiveEnvelope(byte[] payload) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, payload,
                ReSyncProtocolContract.CHANNEL_CONTROL_ID, 2));
        }

        private void receiveError() {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_ERROR,
                new byte[0], (short) 0, 3));
        }

        private void closeTransport() {
            open = false;
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        private void reopen() {
            open = true;
        }

        private ReSyncDecodedFrame lastFunctionTestRequest() {
            for (int index = sentFrames.size() - 1; index >= 0; index--) {
                ReSyncDecodedFrame frame = sentFrames.get(index);
                if (frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 0
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_REQUEST) {
                    return frame;
                }
            }
            throw new IllegalStateException("Function test request was not sent");
        }

        private synchronized int frameCount() {
            return sentFrames.size();
        }

        private ProtocolEnvelope<Map<String, Object>> requestEnvelope(ResourceOperationKind operation, String id,
                                                                       int firstFrame) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                synchronized (this) {
                    for (int index = sentFrames.size() - 1; index >= firstFrame; index--) {
                        ReSyncDecodedFrame frame = sentFrames.get(index);
                        if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                            continue;
                        }
                        ProtocolEnvelope<Map<String, Object>> envelope = PROTOCOL_CODEC.decodeBytes(frame.payload());
                        if (envelope.body() instanceof ProtocolBody.ResourceRequest request
                            && request.operation().kind() == operation && envelope.resource() != null
                            && id.equals(envelope.resource().id())) {
                            return envelope;
                        }
                    }
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            throw new IllegalStateException("Core graph request was not sent");
        }
    }

    private static final class FixtureProbe extends RemotelyClient {
        private final FlowManager flowManager;

        private FixtureProbe() {
            super(null);
            flowManager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return flowManager;
        }

        private void close() {
            flowManager.shutdown();
        }
    }
}
