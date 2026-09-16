package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCatalogPublicationReceiptOutboxTest {
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));
    private static final CatalogCacheKey KEY = new CatalogCacheKey(
        new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222")), 4,
        new ContentHash("b".repeat(64)), BINDING_HASH, CatalogProjectionVersion.current());

    @Test
    void retainsBothStagesUntilEachTransportSendSucceeds() {
        ReSyncCatalogPublicationReceiptOutbox outbox = new ReSyncCatalogPublicationReceiptOutbox(4);
        byte[] bytes = "canonical".getBytes(StandardCharsets.UTF_8);

        assertTrue(outbox.stageReceived(KEY, 7, bytes));
        assertTrue(outbox.stageTerminal(KEY, 7, bytes, ReSyncCatalogPublicationReceiptOutbox.Terminal.APPLIED, ""));
        ReSyncCatalogPublicationReceiptOutbox.Pending pending = outbox.pending().getFirst();
        assertFalse(pending.receivedDelivered());
        assertFalse(pending.terminalDelivered());

        assertTrue(outbox.markReceivedDelivered(pending));
        pending = outbox.pending().getFirst();
        assertTrue(pending.receivedDelivered());
        assertFalse(pending.terminalDelivered());
        assertTrue(outbox.markTerminalDelivered(pending));
        assertEquals(0, outbox.size());
    }

    @Test
    void preservesConflictingSameRevisionAsRejectedIdentity() {
        ReSyncCatalogPublicationReceiptOutbox outbox = new ReSyncCatalogPublicationReceiptOutbox(4);
        byte[] active = "active".getBytes(StandardCharsets.UTF_8);
        byte[] conflict = "conflict".getBytes(StandardCharsets.UTF_8);

        assertTrue(outbox.stageReceived(KEY, 9, active));
        assertTrue(outbox.stageReceived(KEY, 9, active));
        assertTrue(outbox.stageTerminal(KEY, 9, active, ReSyncCatalogPublicationReceiptOutbox.Terminal.APPLIED, ""));
        assertTrue(outbox.stageReceived(KEY, 9, conflict));
        assertTrue(outbox.stageTerminal(KEY, 9, conflict, ReSyncCatalogPublicationReceiptOutbox.Terminal.REJECTED,
            "CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED"));

        List<ReSyncCatalogPublicationReceiptOutbox.Pending> pending = outbox.pending();
        assertEquals(2, pending.size());
        assertTrue(pending.stream().anyMatch(entry -> entry.terminal() == ReSyncCatalogPublicationReceiptOutbox.Terminal.APPLIED));
        assertTrue(pending.stream().anyMatch(entry -> entry.terminal() == ReSyncCatalogPublicationReceiptOutbox.Terminal.REJECTED));
    }

    @Test
    void staleRejectedPendingCannotDeliverUpgradedAppliedTerminal() {
        ReSyncCatalogPublicationReceiptOutbox outbox = new ReSyncCatalogPublicationReceiptOutbox(4);
        byte[] bytes = "canonical".getBytes(StandardCharsets.UTF_8);

        assertTrue(outbox.stageReceived(KEY, 10, bytes));
        assertTrue(outbox.stageTerminal(KEY, 10, bytes, ReSyncCatalogPublicationReceiptOutbox.Terminal.REJECTED,
            "CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED"));
        ReSyncCatalogPublicationReceiptOutbox.Pending rejected = outbox.pending().getFirst();

        assertTrue(outbox.stageTerminal(KEY, 10, bytes, ReSyncCatalogPublicationReceiptOutbox.Terminal.APPLIED, ""));
        assertFalse(outbox.markTerminalDelivered(rejected));
        ReSyncCatalogPublicationReceiptOutbox.Pending applied = outbox.pending().getFirst();
        assertEquals(ReSyncCatalogPublicationReceiptOutbox.Terminal.APPLIED, applied.terminal());
        assertFalse(applied.terminalDelivered());

        assertTrue(outbox.markTerminalDelivered(applied));
        assertTrue(outbox.markReceivedDelivered(applied));
        assertEquals(0, outbox.size());
    }

    @Test
    void refusesNewEntriesWhenBoundedCapacityIsFull() {
        ReSyncCatalogPublicationReceiptOutbox outbox = new ReSyncCatalogPublicationReceiptOutbox(1);
        byte[] bytes = "canonical".getBytes(StandardCharsets.UTF_8);

        assertTrue(outbox.stageReceived(KEY, 1, bytes));
        assertFalse(outbox.stageReceived(KEY, 2, bytes));
        assertEquals(1, outbox.size());
    }

    @Test
    void unpublishedReservationGuaranteesCapacityWithoutExposingAReceipt() {
        ReSyncCatalogPublicationReceiptOutbox outbox = new ReSyncCatalogPublicationReceiptOutbox(1);
        ReSyncCatalogPublicationReceiptOutbox.PublicationIdentity identity =
            ReSyncCatalogPublicationReceiptOutbox.PublicationIdentity.of(
                "canonical".getBytes(StandardCharsets.UTF_8));

        ReSyncCatalogPublicationReceiptOutbox.Reservation reservation = outbox.reserveReceived(KEY, 12, identity);
        assertTrue(reservation != null);
        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, outbox.size());
        assertTrue(outbox.reserveReceived(KEY, 13, identity) == null);
        assertTrue(outbox.release(reservation));
        assertEquals(0, outbox.size());

        reservation = outbox.reserveReceived(KEY, 12, identity);
        assertTrue(outbox.publishReceived(reservation));
        assertEquals(1, outbox.pending().size());
        assertFalse(outbox.release(reservation));
    }
}
