package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientProjectionRetryTest {
    @Test
    void genericProjectionRetryCarriesBoundedDiagnosticIdentityAndSaveState() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java"));

        assertTrue(source.contains("private enum ProjectionRetryReason"));
        assertTrue(source.contains("FLOW_UNAVAILABLE"));
        assertTrue(source.contains("FENCE_CHANGED"));
        assertTrue(source.contains("DELETE_CAS"));
        assertTrue(source.contains("CACHE_CAS"));
        assertTrue(source.contains("SAVE_CAS"));
        assertTrue(source.contains("ACTIVATION_CAS"));
        assertTrue(source.contains("POST_FENCE"));
        assertTrue(source.contains("COMMIT"));
        assertTrue(source.contains("EXCEPTION"));
        assertTrue(source.contains("captureProjectionRetrySaveState"));
        assertTrue(source.contains("draftVersion"));
        assertTrue(source.contains("saveGeneration"));
        assertTrue(source.contains("activeGeneration"));
        assertTrue(source.contains("correlationId"));
        assertTrue(source.contains("traceId"));
        assertTrue(source.contains("documentDeleted"));
        assertTrue(source.contains("Deferred generic resource reply after projection failure"));
        assertTrue(source.contains("markPreparedResourceSavedAuthoritative"));
        assertTrue(source.contains("admission, true, null, pending"));
        assertTrue(source.contains("pending != null && pending.mutation(), null, pending"));
    }
}
