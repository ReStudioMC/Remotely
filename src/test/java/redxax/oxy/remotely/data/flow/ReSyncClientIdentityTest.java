package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.collaboration.CollaborationService;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReSyncClientIdentityTest {
    @Test
    void handshakeUsesTheSuppliedClientAndCollaborationIdentity() {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncIdentityProvider identity = new ReSyncIdentityProvider() {
            @Override
            public String clientId(String serverId) {
                return "browser-session-one";
            }

            @Override
            public CollaborationService.Identity collaborationIdentity(String fallbackClientId) {
                return new CollaborationService.Identity("account-one", "Test User", "", "restudio-web");
            }
        };
        ReSyncFlowClient client = new ReSyncFlowClient("11111111-1111-4111-8111-111111111111", transport,
            "bridge", ReSyncFlowClientContext.defaults(), TaskScheduler.direct(), Clock.system(), identity,
            ReSyncCredentialProvider.apiKey());
        try {
            client.connect().join();
            ByteBuffer payload = ByteBuffer.wrap(transport.sentFrames().getFirst().payload());
            readString(payload);
            assertEquals("browser-session-one", readString(payload));
            payload.getInt();
            readString(payload);
            readString(payload);
            var profile = JsonParser.parseString(readString(payload)).getAsJsonObject();
            assertEquals("account-one", profile.get("subjectId").getAsString());
            assertEquals("Test User", profile.get("displayName").getAsString());
        } finally {
            ReSyncFlowClientTestHarness.closeClient(client);
        }
    }

    private String readString(ByteBuffer payload) {
        byte[] bytes = new byte[payload.getInt()];
        payload.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
