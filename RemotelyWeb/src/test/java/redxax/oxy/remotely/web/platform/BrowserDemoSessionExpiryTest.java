package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyCapabilityException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserDemoSessionExpiryTest {
    @Test
    void onlyTreatsStructuredDemoExpiryAsSessionExpiry() {
        IllegalStateException expiry = BrowserRemotelyServerApi.capabilityFailure(403,
                "{\"code\":\"reactor_demo_session_expired\",\"message\":\"Demo Ended\"}");
        assertTrue(BrowserServerScreenHost.isSessionExpired(expiry));
        assertFalse(BrowserServerScreenHost.isSessionExpired(new RemotelyCapabilityException(403, "reactor_demo_denied", "Access Denied")));
    }

    @Test
    void hostingConflictKeepsItsCauseInsteadOfClaimingAWorkspaceFileChanged() {
        IllegalStateException failure = BrowserRemotelyServerApi.capabilityFailure(409,
                "{\"error\":\"Hosting Conflict\",\"message\":\"Provider Owner Identity Or Availability Does Not Match\"}");
        assertEquals("Provider Owner Identity Or Availability Does Not Match", failure.getMessage());
    }

    @Test
    void poolConflictKeepsItsRejectionReasonAndCode() {
        RemotelyCapabilityException failure = (RemotelyCapabilityException) BrowserRemotelyServerApi.capabilityFailure(409,
                "{\"code\":\"resource_pool_conflict\",\"message\":\"Server Address Is Unavailable\"}");
        assertEquals("Server Address Is Unavailable", failure.getMessage());
        assertEquals("resource_pool_conflict", failure.code());
    }
    @Test
    void resourceValidationShowsTheServerReasonWithoutLeakingUnstructuredResponses() {
        IllegalStateException failure = BrowserRemotelyServerApi.resourcePoolFailure(400,
                "{\"error\":\"Bad Request\",\"message\":\"Discount Code Is Unavailable\"}");
        assertEquals("Discount Code Is Unavailable", failure.getMessage());
        assertEquals("Browser Capability Failed With Status 400",
                BrowserRemotelyServerApi.resourcePoolFailure(400, "<html>Upstream Failure</html>").getMessage());
    }

}
