package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientCoreActivationTest {
    private static final CatalogBinding ACTIVE = new CatalogBinding(55L, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final CatalogBinding OTHER = new CatalogBinding(54L, new ContentHash("a".repeat(64)),
        new ContentHash("c".repeat(64)));

    @Test
    void keepsActivationPendingUntilTheActiveAuthoringBindingIsObservable() {
        assertEquals(ReSyncFlowClient.CoreGraphActivationState.PENDING,
            ReSyncFlowClient.coreGraphBindingActivation(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION,
                null, ACTIVE));
        assertEquals(ReSyncFlowClient.CoreGraphActivationState.PENDING,
            ReSyncFlowClient.coreGraphBindingActivation(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION,
                ACTIVE, ACTIVE));
    }

    @Test
    void rejectsOnlyAnObservableUnequalAuthoringBinding() {
        assertEquals(ReSyncFlowClient.CoreGraphActivationState.LIVE,
            ReSyncFlowClient.coreGraphBindingActivation(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION,
                ACTIVE, ACTIVE));
        assertEquals(ReSyncFlowClient.CoreGraphActivationState.INCOMPATIBLE,
            ReSyncFlowClient.coreGraphBindingActivation(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION,
                ACTIVE, OTHER));
    }

    @Test
    void boundsAuthoringRediscoveryByAttemptCountAndRetryTime() {
        assertTrue(ReSyncFlowClient.admitsCoreAuthoringRediscovery(0, 1_000L, 0L));
        assertFalse(ReSyncFlowClient.admitsCoreAuthoringRediscovery(1, 999L, 1_000L));
        assertTrue(ReSyncFlowClient.admitsCoreAuthoringRediscovery(
            ReSyncFlowClient.MAX_CORE_AUTHORING_REDISCOVERY_ATTEMPTS - 1, 1_000L, 1_000L));
        assertFalse(ReSyncFlowClient.admitsCoreAuthoringRediscovery(
            ReSyncFlowClient.MAX_CORE_AUTHORING_REDISCOVERY_ATTEMPTS, 2_000L, 1_000L));
    }
}
