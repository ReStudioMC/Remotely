package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.protocol.OptionInvalidation;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientOptionTransportTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
    private static final OwnerId OWNER = OwnerId.of("restudio.test");
    private static final OwnerId PROTOCOL_OWNER = OwnerId.of("restudio.resync");
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final CatalogBinding BINDING = new CatalogBinding(7L,
        new ContentHash("4".repeat(64)), new ContentHash("5".repeat(64)));

    @Test
    void automaticDocumentRequestDropsResourceForEmptyContextAndSendsOptionQuery(@TempDir Path tempDirectory)
        throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog-publication-cache.json"))));
        CatalogCachePublication publication = publication();
        ContractRef<InspectorFieldId> source = ContractRef.of(OWNER, InspectorFieldId.of("resources"));
        ServerResourceLocator document = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("flow")), "current-flow");
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key()));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
            awaitAuthoring(client);

            Optional<OptionCatalogLoader.CoreRequest> explicit = client.coreOptionRequest(source, document, Map.of(),
                Map.of(), null);
            OptionCatalogLoader.CoreRequest automatic = OptionCatalogLoader.automaticCoreRequest(client, source,
                document, Map.of(), Map.of(), null).orElseThrow();

            assertTrue(explicit.isEmpty());
            assertNull(automatic.resource());
            assertTrue(automatic.automaticSupported());
            assertEquals(source, automatic.sourceReference());
            assertEquals(source, automatic.key().sourceReference());
            assertTrue(client.requestCoreOptionCatalog(automatic, true));

            ProtocolEnvelope<Map<String, Object>> envelope = lastEnvelope(transport);
            assertEquals(ProtocolEnvelope.Kind.REQUEST, envelope.kind());
            assertEquals(new CatalogVersion(1, 3), envelope.contractVersion());
            assertEquals(ContractRef.of(PROTOCOL_OWNER, OperationId.of("option.query")), envelope.operation());
            assertEquals(Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY), envelope.capabilities());
            assertTrue(envelope.body() instanceof ProtocolBody.OptionQueryRequest);
            OptionQuery query = ((ProtocolBody.OptionQueryRequest) envelope.body()).query();
            assertNull(query.resource());
            assertEquals(source, query.sourceRef());
            assertEquals(automatic.source().capability(), query.query());
            assertEquals(automatic.authorityEpoch(), envelope.authorityEpoch());
            assertEquals(automatic.publicationContractVersion(), envelope.selectedVersion());
            assertEquals(automatic.catalogBinding().catalogChecksum(), envelope.catalogChecksum());
            assertEquals(automatic.catalogBinding().bindingManifestHash(), envelope.bindingManifestHash());
            OptionPage page = page(automatic, "single", null, true, 1L, automatic.source().invalidationKey());
            ProtocolEnvelope<Map<String, Object>> canonicalResponse = roundTrip(
                response(envelope, page, automatic.authorityEpoch()));
            assertEquals(0L, canonicalResponse.revision());
            assertEquals(page.revision(),
                ((ProtocolBody.OptionPageResponse) canonicalResponse.body()).page().revision());
            transport.receiveEnvelope(canonicalResponse, 3);
            ReSyncFlowClientTestHarness.drain(client);
            OptionCatalogCache.CoreCatalogSnapshot snapshot = OptionCatalogCache.getInstance()
                .coreSnapshot(automatic.key());
            assertEquals("available", snapshot.status());
            assertEquals(page.items(), snapshot.catalog().items());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void optionQueryIsNotSentWhenTheGenericContractNegotiatesVersionOneTwo(@TempDir Path tempDirectory)
        throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("option-version-cache.json"))));
        CatalogCachePublication publication = publication();
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key(), false, new CatalogVersion(1, 2)));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
            awaitAuthoring(client);
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            long before = protocolEnvelopeCount(transport);

            assertFalse(client.requestCoreOptionCatalog(request, true));
            assertEquals(before, protocolEnvelopeCount(transport));
            assertEquals(0, pendingCoreOptionRequestCount(client));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void qualifiedOnlyOptionQueryCapabilityIsNotAccepted(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("qualified-option-query-cache.json"))));
        try {
            client.connect().join();
            transport.receiveHandshake(qualifiedOnlyCapabilities(publication().key()));
            ReSyncFlowClientTestHarness.drain(client);

            assertEquals(ReSyncFlowClient.HandshakeStage.FAILED, client.handshakeObservation().stage());
            assertEquals("protocol_error", client.handshakeObservation().failureDiagnostic());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void multiplePagesAggregateUnderOneCorrelation(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(), tempDirectory.resolve("pages-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> firstRequest = lastEnvelope(transport);
            OptionPage first = page(request, "first", "next", false, 5L, "catalog-5");
            transport.receiveEnvelope(response(firstRequest, first, request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);

            ProtocolEnvelope<Map<String, Object>> secondRequest = lastEnvelope(transport);
            assertNotEquals(firstRequest.requestId(), secondRequest.requestId());
            assertEquals(firstRequest.correlationId(), secondRequest.correlationId());
            OptionQuery continuation = ((ProtocolBody.OptionQueryRequest) secondRequest.body()).query();
            assertEquals("next", continuation.cursor());
            assertEquals(5L, continuation.revision());
            assertEquals("catalog-5", continuation.invalidationKey());

            OptionPage second = page(request, "second", null, true, 5L, "catalog-5");
            transport.receiveEnvelope(response(secondRequest, second, request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(client);

            OptionCatalogCache.CoreCatalogSnapshot snapshot = OptionCatalogCache.getInstance().coreSnapshot(request.key());
            assertEquals("available", snapshot.status());
            assertEquals(List.of(first.items().getFirst(), second.items().getFirst()), snapshot.catalog().items());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void fullWirePageAndContinuationAggregateBeyondWirePageLimit(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(BINDING, 500, "resources"),
            tempDirectory.resolve("full-page-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> firstRequest = lastEnvelope(transport);
            OptionPage first = page(request, items(request, "first-", 500), "next", false, 5L, "catalog-5");
            transport.receiveEnvelope(response(firstRequest, first, request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);

            ProtocolEnvelope<Map<String, Object>> secondRequest = lastEnvelope(transport);
            OptionPage second = page(request, items(request, "last-0", 1), null, true, 5L, "catalog-5");
            transport.receiveEnvelope(response(secondRequest, second, request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(client);

            OptionCatalogCache.CompletedCoreCatalog completed = OptionCatalogCache.getInstance()
                .coreSnapshot(request.key()).catalog();
            assertEquals(501, completed.items().size());
            assertEquals("first-0", completed.items().getFirst().label());
            assertEquals("last-0", completed.items().getLast().label());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void duplicateAndDriftingContinuationPagesFailClosed(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport duplicateTransport = new ScriptedReSyncTransport();
        ReSyncFlowClient duplicateClient = connected(duplicateTransport, publication(),
            tempDirectory.resolve("duplicate-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(duplicateClient, "resources");
            assertTrue(duplicateClient.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> firstRequest = lastEnvelope(duplicateTransport);
            OptionPage first = page(request, "duplicate", "next", false, 8L, "catalog-8");
            duplicateTransport.receiveEnvelope(response(firstRequest, first, request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(duplicateClient);
            ProtocolEnvelope<Map<String, Object>> secondRequest = lastEnvelope(duplicateTransport);
            OptionPage duplicate = page(request, "duplicate", null, true, 8L, "catalog-8");
            duplicateTransport.receiveEnvelope(response(secondRequest, duplicate, request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(duplicateClient);
            assertEquals("unavailable", OptionCatalogCache.getInstance().coreSnapshot(request.key()).status());
        } finally {
            duplicateClient.shutdown();
        }

        ScriptedReSyncTransport driftTransport = new ScriptedReSyncTransport();
        ReSyncFlowClient driftClient = connected(driftTransport, publication(), tempDirectory.resolve("drift-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(driftClient, "resources");
            assertTrue(driftClient.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> firstRequest = lastEnvelope(driftTransport);
            driftTransport.receiveEnvelope(response(firstRequest,
                page(request, "first", "next", false, 9L, "catalog-9"), request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(driftClient);
            ProtocolEnvelope<Map<String, Object>> secondRequest = lastEnvelope(driftTransport);
            driftTransport.receiveEnvelope(response(secondRequest,
                page(request, "second", null, true, 10L, "catalog-10"), request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(driftClient);
            assertEquals("unavailable", OptionCatalogCache.getInstance().coreSnapshot(request.key()).status());
        } finally {
            driftClient.shutdown();
        }

        ScriptedReSyncTransport cursorTransport = new ScriptedReSyncTransport();
        ReSyncFlowClient cursorClient = connected(cursorTransport, publication(),
            tempDirectory.resolve("cursor-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(cursorClient, "resources");
            assertTrue(cursorClient.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> firstRequest = lastEnvelope(cursorTransport);
            cursorTransport.receiveEnvelope(response(firstRequest,
                page(request, "first", "repeated", false, 11L, "catalog-11"), request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(cursorClient);
            ProtocolEnvelope<Map<String, Object>> secondRequest = lastEnvelope(cursorTransport);
            cursorTransport.receiveEnvelope(response(secondRequest,
                page(request, "second", "repeated", false, 11L, "catalog-11"), request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(cursorClient);
            assertEquals("unavailable", OptionCatalogCache.getInstance().coreSnapshot(request.key()).status());
        } finally {
            cursorClient.shutdown();
        }
    }

    @Test
    void rejectionAndWrongAuthorityFailTheCorrelatedRequest(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(), tempDirectory.resolve("rejection-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> rejectedRequest = lastEnvelope(transport);
            transport.receiveEnvelope(rejection(rejectedRequest, request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);
            assertEquals("unavailable", OptionCatalogCache.getInstance().coreSnapshot(request.key()).status());

            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> wrongAuthorityRequest = lastEnvelope(transport);
            transport.receiveEnvelope(response(wrongAuthorityRequest,
                page(request, "wrong-authority", null, true, 1L, request.source().invalidationKey()),
                request.authorityEpoch() + 1L), 4);
            ReSyncFlowClientTestHarness.drain(client);
            assertEquals("unavailable", OptionCatalogCache.getInstance().coreSnapshot(request.key()).status());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void disconnectRetainsCompletedPageAsStaleAndFailsRefresh(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(), tempDirectory.resolve("disconnect-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> initialRequest = lastEnvelope(transport);
            transport.receiveEnvelope(response(initialRequest,
                page(request, "retained", null, true, 2L, request.source().invalidationKey()),
                request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);
            OptionCatalogCache.CompletedCoreCatalog retained = OptionCatalogCache.getInstance()
                .coreSnapshot(request.key()).catalog();

            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> refreshRequest = lastEnvelope(transport);
            transport.close();
            OptionCatalogCache.CoreCatalogSnapshot snapshot = OptionCatalogCache.getInstance().coreSnapshot(request.key());
            assertSame(retained, snapshot.catalog());
            assertEquals("unavailable", snapshot.status());
            transport.receiveEnvelope(response(refreshRequest,
                page(request, "stale-generation", null, true, 3L, request.source().invalidationKey()),
                request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(client);
            OptionCatalogCache.CoreCatalogSnapshot afterLateResponse = OptionCatalogCache.getInstance()
                .coreSnapshot(request.key());
            assertSame(retained, afterLateResponse.catalog());
            assertEquals("unavailable", afterLateResponse.status());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void publicationReplacementPurgesOldCatalogAndIgnoresItsLateResponse(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        CatalogCachePublication initial = publication();
        ReSyncFlowClient client = connected(transport, initial, tempDirectory.resolve("replacement-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> initialRequest = lastEnvelope(transport);
            transport.receiveEnvelope(response(initialRequest,
                page(request, "old-publication", null, true, 2L, request.source().invalidationKey()),
                request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);
            assertEquals("available", OptionCatalogCache.getInstance().coreSnapshot(request.key()).status());

            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> pending = lastEnvelope(transport);
            CatalogBinding replacementBinding = new CatalogBinding(8L, new ContentHash("6".repeat(64)),
                new ContentHash("7".repeat(64)));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(
                publication(replacementBinding, "resources")), 4);
            OptionCatalogCache.CoreCatalogSnapshot replaced = awaitCoreOptionStatus(client, request, "missing");
            assertNull(replaced.catalog());

            transport.receiveEnvelope(response(pending,
                page(request, "late", null, true, 3L, request.source().invalidationKey()), request.authorityEpoch()), 5);
            ReSyncFlowClientTestHarness.drain(client);
            assertNull(OptionCatalogCache.getInstance().coreSnapshot(request.key()).catalog());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void publicationReplacementCannotOvertakePhysicalOptionDispatch(@TempDir Path tempDirectory) throws Exception {
        BlockingOptionTransport transport = new BlockingOptionTransport();
        CatalogCachePublication initial = publication();
        ReSyncFlowClient client = connected(transport, initial, tempDirectory.resolve("dispatch-fence-cache.json"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            transport.blockNextOptionQuery();
            Future<Boolean> dispatch = executor.submit(() -> client.requestCoreOptionCatalog(request, true));
            assertTrue(transport.awaitOptionQuery());
            ProtocolEnvelope<Map<String, Object>> pending = transport.blockedOptionQuery();
            CatalogBinding replacementBinding = new CatalogBinding(8L, new ContentHash("6".repeat(64)),
                new ContentHash("7".repeat(64)));
            transport.observeNextCatalogPublication();
            Future<?> replacement = executor.submit(() -> {
                transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(
                    publication(replacementBinding, "resources")), 3);
                ReSyncFlowClientTestHarness.drain(client);
                return null;
            });
            assertTrue(transport.awaitCatalogPublication());
            assertThrows(TimeoutException.class, () -> replacement.get(100L, TimeUnit.MILLISECONDS));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());

            transport.releaseOptionQuery();
            assertTrue(dispatch.get(2L, TimeUnit.SECONDS));
            replacement.get(2L, TimeUnit.SECONDS);
            OptionCatalogCache.CoreCatalogSnapshot replaced = awaitCoreOptionStatus(client, request, "missing");
            assertFalse(replaced.loading());
            assertNull(replaced.catalog());
            assertFalse(pendingCoreOptionRequest(client, pending.requestId()));

            transport.receiveEnvelope(response(pending,
                page(request, "late", null, true, 3L, request.source().invalidationKey()), request.authorityEpoch()), 4);
            ReSyncFlowClientTestHarness.drain(client);
            OptionCatalogCache.CoreCatalogSnapshot afterLateResponse = OptionCatalogCache.getInstance()
                .coreSnapshot(request.key());
            assertNull(afterLateResponse.catalog());
            assertEquals("missing", afterLateResponse.status());
        } finally {
            transport.releaseOptionQuery();
            executor.shutdownNow();
            client.shutdown();
        }
    }

    @Test
    void failedPhysicalOptionDispatchClearsItsReservation(@TempDir Path tempDirectory) throws Exception {
        RejectingOptionTransport transport = new RejectingOptionTransport();
        ReSyncFlowClient client = connected(transport, publication(), tempDirectory.resolve("dispatch-failure-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            transport.rejectNextOptionQuery();

            assertFalse(client.requestCoreOptionCatalog(request, true));
            assertTrue(transport.rejectedOptionQuery());
            assertEquals(0, pendingCoreOptionRequestCount(client));
            OptionCatalogCache.CoreCatalogSnapshot snapshot = OptionCatalogCache.getInstance()
                .coreSnapshot(request.key());
            assertEquals("unavailable", snapshot.status());
            assertFalse(snapshot.loading());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void sharedCapabilitySourcesSendIndependentSourceReferences(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(BINDING, "first", "second"),
            tempDirectory.resolve("shared-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "first");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> envelope = lastEnvelope(transport);
            OptionQuery query = ((ProtocolBody.OptionQueryRequest) envelope.body()).query();

            assertEquals(request.sourceReference(), query.sourceRef());
            assertEquals(request.source().capability(), query.query());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void nullAndEmptySearchDoNotCoalesce(@TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(), tempDirectory.resolve("search-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest absent = automaticRequest(client, "resources", null);
            OptionCatalogLoader.CoreRequest empty = automaticRequest(client, "resources", "");
            long before = protocolEnvelopeCount(transport);

            assertTrue(client.requestCoreOptionCatalog(absent, true));
            assertTrue(client.requestCoreOptionCatalog(empty, true));

            assertNotEquals(absent.key(), empty.key());
            assertEquals(before + 2L, protocolEnvelopeCount(transport));
            assertEquals(2, pendingCoreOptionRequestCount(client));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void invalidationRetiresMatchingPendingAndReplayedSequenceCannotRetireItsReplacement(
        @TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(), tempDirectory.resolve("invalidation-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest request = automaticRequest(client, "resources");
            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> initialRequest = lastEnvelope(transport);
            OptionPage initial = page(request, "initial", null, true, 2L, "catalog-2");
            transport.receiveEnvelope(response(initialRequest, initial, request.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);
            OptionCatalogCache.CompletedCoreCatalog retained = OptionCatalogCache.getInstance()
                .coreSnapshot(request.key()).catalog();

            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> retiredRequest = lastEnvelope(transport);
            OptionInvalidation changed = new OptionInvalidation(request.sourceReference(), request.source().capability(),
                request.serverId(), null, 1L, "catalog-1", Set.of());
            ProtocolEnvelope<Map<String, Object>> changedEvent = invalidation(retiredRequest, changed, 17L);
            transport.receiveEnvelope(changedEvent, 4);
            ReSyncFlowClientTestHarness.drain(client);

            OptionCatalogCache.CoreCatalogSnapshot stale = OptionCatalogCache.getInstance().coreSnapshot(request.key());
            assertSame(retained, stale.catalog());
            assertEquals("stale", stale.status());
            assertFalse(stale.loading());
            assertFalse(pendingCoreOptionRequest(client, retiredRequest.requestId()));

            transport.receiveEnvelope(response(retiredRequest,
                page(request, "late", null, true, 3L, "catalog-3"), request.authorityEpoch()), 5);
            ReSyncFlowClientTestHarness.drain(client);
            assertSame(retained, OptionCatalogCache.getInstance().coreSnapshot(request.key()).catalog());

            assertTrue(client.requestCoreOptionCatalog(request, true));
            ProtocolEnvelope<Map<String, Object>> replacement = lastEnvelope(transport);
            transport.receiveEnvelope(changedEvent, 6);
            ReSyncFlowClientTestHarness.drain(client);
            assertTrue(pendingCoreOptionRequest(client, replacement.requestId()));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void invalidationSequencesAreIndependentAcrossAndScopesAndZeroAlwaysApplies(
        @TempDir Path tempDirectory) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = connected(transport, publication(BINDING, "first", "second"),
            tempDirectory.resolve("invalidation-scopes-cache.json"));
        try {
            OptionCatalogLoader.CoreRequest first = automaticRequest(client, "first");
            assertTrue(client.requestCoreOptionCatalog(first, true));
            ProtocolEnvelope<Map<String, Object>> firstEnvelope = lastEnvelope(transport);
            transport.receiveEnvelope(response(firstEnvelope,
                page(first, "initial", null, true, 1L, first.source().invalidationKey()), first.authorityEpoch()), 3);
            ReSyncFlowClientTestHarness.drain(client);

            ServerResourceLocator resource = new ServerResourceLocator(first.serverId(),
                ContractRef.of(OWNER, ResourceTypeId.of("resource")), "narrow");
            transport.receiveEnvelope(invalidation(firstEnvelope, invalidation(first, null, Set.of(), 2L), 10L), 4);
            transport.receiveEnvelope(invalidation(firstEnvelope, invalidation(first, resource, Set.of(), 3L), 9L), 5);
            transport.receiveEnvelope(invalidation(firstEnvelope, invalidation(first, null, Set.of("depA"), 4L), 10L), 6);
            transport.receiveEnvelope(invalidation(firstEnvelope, invalidation(first, null, Set.of("depB"), 5L), 9L), 7);
            ReSyncFlowClientTestHarness.drain(client);
            assertEquals(4, invalidationSequenceCount(client));

            assertTrue(client.requestCoreOptionCatalog(first, true));
            ProtocolEnvelope<Map<String, Object>> zeroPending = lastEnvelope(transport);
            transport.receiveEnvelope(invalidation(firstEnvelope, invalidation(first, null, Set.of(), 6L), 0L), 8);
            ReSyncFlowClientTestHarness.drain(client);
            assertFalse(pendingCoreOptionRequest(client, zeroPending.requestId()));
            assertEquals(4, invalidationSequenceCount(client));

            OptionCatalogLoader.CoreRequest second = automaticRequest(client, "second");
            assertTrue(client.requestCoreOptionCatalog(second, true));
            ProtocolEnvelope<Map<String, Object>> reversePending = lastEnvelope(transport);
            transport.receiveEnvelope(invalidation(reversePending, invalidation(second, resource, Set.of(), 7L), 10L), 9);
            ReSyncFlowClientTestHarness.drain(client);
            assertTrue(pendingCoreOptionRequest(client, reversePending.requestId()));
            transport.receiveEnvelope(invalidation(reversePending, invalidation(second, null, Set.of(), 8L), 9L), 10);
            ReSyncFlowClientTestHarness.drain(client);
            assertFalse(pendingCoreOptionRequest(client, reversePending.requestId()));
            assertEquals(6, invalidationSequenceCount(client));
        } finally {
            client.shutdown();
        }
    }

    private static ReSyncFlowClient connected(ScriptedReSyncTransport transport,
                                               CatalogCachePublication publication, Path cachePath) throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(cachePath)));
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities(publication.key()));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
            awaitAuthoring(client);
            return client;
        } catch (Exception failure) {
            client.shutdown();
            throw failure;
        }
    }

    private static OptionCatalogLoader.CoreRequest automaticRequest(ReSyncFlowClient client, String sourceId) {
        return automaticRequest(client, sourceId, null);
    }

    private static OptionCatalogLoader.CoreRequest automaticRequest(ReSyncFlowClient client, String sourceId,
                                                                     String search) {
        ContractRef<InspectorFieldId> source = ContractRef.of(OWNER, InspectorFieldId.of(sourceId));
        ServerResourceLocator document = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("flow")), "current-flow");
        return OptionCatalogLoader.automaticCoreRequest(client, source, document, Map.of(), Map.of(), search)
            .orElseThrow();
    }

    private static ProtocolEnvelope<Map<String, Object>> lastEnvelope(ScriptedReSyncTransport transport) {
        return new ProtocolEnvelopeCodec<Map<String, Object>>(ResourcePayloadCodecs.json())
            .decodeBytes(transport.lastProtocolEnvelope().payload());
    }

    private static ProtocolEnvelope<Map<String, Object>> response(ProtocolEnvelope<Map<String, Object>> request,
                                                                   OptionPage page, long authorityEpoch) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.RESPONSE,
            request.contractVersion(),
            UUID.randomUUID(),
            request.requestId(),
            request.correlationId(),
            request.traceId(),
            request.serverId(),
            null,
            0L,
            authorityEpoch,
            null,
            request.operation(),
            request.capabilities(),
            request.payloadType(),
            null,
            null,
            false,
            request.selectedVersion(),
            request.catalogChecksum(),
            request.bindingManifestHash(),
            null,
            null,
            0L,
            ProtocolEnvelope.Status.OK,
            page.diagnostics(),
            Map.of(),
            new ProtocolBody.OptionPageResponse(page)
        );
    }

    private static ProtocolEnvelope<Map<String, Object>> rejection(ProtocolEnvelope<Map<String, Object>> request,
                                                                    long authorityEpoch) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.RESPONSE,
            request.contractVersion(),
            UUID.randomUUID(),
            request.requestId(),
            request.correlationId(),
            request.traceId(),
            request.serverId(),
            null,
            0L,
            authorityEpoch,
            null,
            request.operation(),
            request.capabilities(),
            request.payloadType(),
            null,
            null,
            false,
            request.selectedVersion(),
            request.catalogChecksum(),
            request.bindingManifestHash(),
            null,
            null,
            0L,
            ProtocolEnvelope.Status.REJECTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ControlResponse("resource.rejection", Map.of("message", "Rejected"))
        );
    }

    private static OptionPage page(OptionCatalogLoader.CoreRequest request, String id, String nextCursor,
                                   boolean complete, long revision, String invalidationKey) {
        return page(request, items(request, id, 1), nextCursor, complete, revision, invalidationKey);
    }

    private static OptionPage page(OptionCatalogLoader.CoreRequest request, List<OptionItem> items,
                                   String nextCursor, boolean complete, long revision, String invalidationKey) {
        return new OptionPage(request.sourceReference(), request.source().capability(), revision, invalidationKey,
            items, nextCursor, complete, List.of());
    }

    private static List<OptionItem> items(OptionCatalogLoader.CoreRequest request, String prefix, int count) {
        ContractRef<ResourceTypeId> resourceType = ContractRef.of(OWNER, ResourceTypeId.of("resource"));
        TypeExpr.ResourceType type = new TypeExpr.ResourceType(
            new TypeReference(resourceType.owner().canonicalText(), resourceType.id().value()));
        List<OptionItem> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String id = count == 1 ? prefix : prefix + index;
            TypedValue value = TypedValue.locator(type,
                new ServerResourceLocator(request.serverId(), resourceType, id));
            items.add(new OptionItem(value, id, id + " description", true, null));
        }
        return List.copyOf(items);
    }

    private static ProtocolEnvelope<Map<String, Object>> invalidation(
        ProtocolEnvelope<Map<String, Object>> request, OptionInvalidation invalidation, long sequence) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT, request.contractVersion(), UUID.randomUUID(), null,
            UUID.randomUUID(), UUID.randomUUID(), request.serverId(), null, 0L,
            request.authorityEpoch(), null, request.operation(), request.capabilities(), request.payloadType(), null,
            null, false, request.selectedVersion(), request.catalogChecksum(), request.bindingManifestHash(), null, null,
            sequence, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.OptionInvalidationEvent(invalidation));
    }

    private static OptionInvalidation invalidation(OptionCatalogLoader.CoreRequest request,
                                                     ServerResourceLocator resource, Set<String> dependencies,
                                                     long revision) {
        return new OptionInvalidation(request.sourceReference(), request.source().capability(), request.serverId(),
            resource, revision, "catalog-" + revision, dependencies);
    }

    private static long protocolEnvelopeCount(ScriptedReSyncTransport transport) {
        return transport.sentFrames().stream()
            .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE)
            .count();
    }

    private static ProtocolEnvelope<Map<String, Object>> roundTrip(ProtocolEnvelope<Map<String, Object>> envelope) {
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
        return codec.decodeBytes(codec.encodeBytes(envelope));
    }

    private static boolean pendingCoreOptionRequest(ReSyncFlowClient client, UUID requestId) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingCoreOptionRequests");
        field.setAccessible(true);
        Object value = field.get(client);
        return value instanceof Map<?, ?> pending && pending.containsKey(requestId);
    }

    private static int pendingCoreOptionRequestCount(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingCoreOptionRequests");
        field.setAccessible(true);
        Object value = field.get(client);
        return value instanceof Map<?, ?> pending ? pending.size() : -1;
    }

    private static int invalidationSequenceCount(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("coreOptionInvalidationSequences");
        field.setAccessible(true);
        Object value = field.get(client);
        return value instanceof Map<?, ?> sequences ? sequences.size() : -1;
    }

    private static OptionCatalogCache.CoreCatalogSnapshot awaitCoreOptionStatus(ReSyncFlowClient client,
                                                                                 OptionCatalogLoader.CoreRequest request,
                                                                                 String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        OptionCatalogCache.CoreCatalogSnapshot snapshot = OptionCatalogCache.getInstance().coreSnapshot(request.key());
        while (System.nanoTime() < deadline && !expectedStatus.equals(snapshot.status())) {
            ReSyncFlowClientTestHarness.drain(client);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            snapshot = OptionCatalogCache.getInstance().coreSnapshot(request.key());
        }
        assertEquals(expectedStatus, snapshot.status());
        return snapshot;
    }

    private static CatalogCachePublication publication() {
        return publication(BINDING, "resources");
    }

    private static CatalogCachePublication publication(CatalogBinding binding, String... sourceIds) {
        return publication(binding, 25, sourceIds);
    }

    private static CatalogCachePublication publication(CatalogBinding binding, int pageLimit, String... sourceIds) {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, binding, VERSION);
        ContractRef<NodeId> node = ContractRef.of(OWNER, NodeId.of("node"));
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(node, 3L,
            CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of("{\"id\":\"node\"}".getBytes(StandardCharsets.UTF_8)));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, binding, 3L,
            List.of(entry), authoring(binding, pageLimit, sourceIds), Map.of());
    }

    private static CatalogAuthoringPublication authoring(CatalogBinding binding, int pageLimit, String... sourceIds) {
        List<CatalogAuthoringPublication.Entry> entries = new ArrayList<>();
        for (String sourceId : sourceIds) {
            String data = "{\"capability\":{\"localId\":\"resource-options\",\"ownerId\":\"restudio.test\"},"
                + "\"description\":\"Provides selectable resources.\",\"id\":\""
                + sourceId + "\",\"invalidationKey\":\"" + sourceId
                + "\",\"pageLimit\":" + pageLimit + ",\"querySchema\":{\"context\":{},\"dependencies\":{},\"resource\":null,"
                + "\"schemaVersion\":1},\"title\":\"Resources\",\"valueType\":{\"kind\":\"resource\","
                + "\"resourceType\":{\"localId\":\"resource\",\"ownerId\":\"restudio.test\"}}}";
            entries.add(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.OPTION_SOURCES,
                ContractRef.of(OWNER, CapabilityId.of(sourceId)).canonicalText(), CatalogCacheState.ACTIVE, Set.of(),
                false, CatalogCacheOpaque.of(data.getBytes(StandardCharsets.UTF_8))));
        }
        CatalogAuthoringPublication.SectionProjection options = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.OPTION_SOURCES, true, true, CatalogCacheState.ACTIVE, entries);
        return new CatalogAuthoringPublication(binding, new CatalogVersion(1, 3), VERSION, List.of(options), Set.of());
    }

    private static String capabilities(CatalogCacheKey key) {
        return capabilities(key, false, new CatalogVersion(1, 3));
    }

    private static String qualifiedOnlyCapabilities(CatalogCacheKey key) {
        return capabilities(key, true, new CatalogVersion(1, 3));
    }

    private static String capabilities(CatalogCacheKey key, boolean qualifiedOnly, CatalogVersion genericVersion) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", key.serverId().canonicalText());
        root.addProperty("catalogPublicationKey", key.canonicalText());
        root.addProperty("authorityEpoch", 1L);
        JsonObject authoring = new JsonObject();
        authoring.addProperty("capability", "restudio.resync/catalog_authoring");
        authoring.addProperty("available", true);
        authoring.addProperty("projectionVersion", VERSION.canonicalText());
        root.add("catalogAuthoring", authoring);
        JsonObject contract = new JsonObject();
        contract.addProperty("version", ReSyncProtocolContract.FLOW_CONTRACT.version());
        contract.addProperty("minimumClientVersion", 0);
        List<String> supportedCapabilities = new ArrayList<>(ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities());
        List<String> negotiatedCapabilities = new ArrayList<>(ReSyncProtocolContract.FLOW_CONTRACT.negotiate(
            ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities()));
        if (qualifiedOnly) {
            String capability = ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY.id().value();
            String qualifiedCapability = ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY.canonicalText();
            supportedCapabilities.remove(capability);
            negotiatedCapabilities.remove(capability);
            supportedCapabilities.add(qualifiedCapability);
            negotiatedCapabilities.add(qualifiedCapability);
        }
        JsonArray supported = new JsonArray();
        supportedCapabilities.forEach(supported::add);
        contract.add("supported", supported);
        JsonArray negotiated = new JsonArray();
        negotiatedCapabilities.forEach(negotiated::add);
        contract.add("negotiated", negotiated);
        root.add("flowContract", contract);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject genericContract = new JsonObject();
        JsonObject genericContractVersion = new JsonObject();
        genericContractVersion.addProperty("generation", genericVersion.generation());
        genericContractVersion.addProperty("minor", genericVersion.minor());
        genericContract.add("version", genericContractVersion);
        protocol.add("genericResourceContract", genericContract);
        root.add("protocolEnvelope", protocol);
        return root.toString();
    }

    private static void awaitAuthoring(ReSyncFlowClient client) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(client);
            Optional<ContentHash> checksum = client.activeCatalogAuthoringChecksum();
            if (checksum.isPresent()
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
        assertTrue(client.activeCatalogAuthoringChecksum().isPresent());
    }

    private static final class BlockingOptionTransport extends ScriptedReSyncTransport {
        private final CountDownLatch optionQueryEntered = new CountDownLatch(1);
        private final CountDownLatch optionQueryRelease = new CountDownLatch(1);
        private final CountDownLatch catalogPublicationEntered = new CountDownLatch(1);
        private volatile boolean blockNextOptionQuery;
        private volatile boolean observeNextCatalogPublication;
        private volatile ProtocolEnvelope<Map<String, Object>> blockedOptionQuery;

        @Override
        void receiveCatalogPublication(byte[] publication, int sequence) {
            if (observeNextCatalogPublication) {
                observeNextCatalogPublication = false;
                catalogPublicationEntered.countDown();
            }
            super.receiveCatalogPublication(publication, sequence);
        }

        @Override
        public void send(byte[] frame) {
            if (blockNextOptionQuery) {
                ReSyncDecodedFrame decoded = new ReSyncFrameCodec().decode(frame, null);
                if (decoded.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                    ProtocolEnvelope<Map<String, Object>> envelope =
                        new ProtocolEnvelopeCodec<Map<String, Object>>(ResourcePayloadCodecs.json())
                            .decodeBytes(decoded.payload());
                    if (envelope.body() instanceof ProtocolBody.OptionQueryRequest) {
                        blockedOptionQuery = envelope;
                        blockNextOptionQuery = false;
                        optionQueryEntered.countDown();
                        try {
                            optionQueryRelease.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while blocking option query dispatch", exception);
                        }
                    }
                }
            }
            super.send(frame);
        }

        private void blockNextOptionQuery() {
            blockNextOptionQuery = true;
        }

        private void observeNextCatalogPublication() {
            observeNextCatalogPublication = true;
        }

        private boolean awaitOptionQuery() throws InterruptedException {
            return optionQueryEntered.await(2L, TimeUnit.SECONDS);
        }

        private boolean awaitCatalogPublication() throws InterruptedException {
            return catalogPublicationEntered.await(2L, TimeUnit.SECONDS);
        }

        private void releaseOptionQuery() {
            optionQueryRelease.countDown();
        }

        private ProtocolEnvelope<Map<String, Object>> blockedOptionQuery() {
            return blockedOptionQuery;
        }
    }

    private static final class RejectingOptionTransport extends ScriptedReSyncTransport {
        private volatile boolean rejectNextOptionQuery;
        private volatile boolean rejectedOptionQuery;

        @Override
        public boolean trySend(byte[] frame) {
            if (rejectNextOptionQuery) {
                ReSyncDecodedFrame decoded = new ReSyncFrameCodec().decode(frame, null);
                if (decoded.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                    ProtocolEnvelope<Map<String, Object>> envelope =
                        new ProtocolEnvelopeCodec<Map<String, Object>>(ResourcePayloadCodecs.json())
                            .decodeBytes(decoded.payload());
                    if (envelope.body() instanceof ProtocolBody.OptionQueryRequest) {
                        rejectNextOptionQuery = false;
                        rejectedOptionQuery = true;
                        return false;
                    }
                }
            }
            send(frame);
            return true;
        }

        private void rejectNextOptionQuery() {
            rejectNextOptionQuery = true;
        }

        private boolean rejectedOptionQuery() {
            return rejectedOptionQuery;
        }
    }
}
