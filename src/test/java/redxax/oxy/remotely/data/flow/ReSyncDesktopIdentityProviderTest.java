package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncDesktopIdentityProviderTest {
    @TempDir
    Path temporary;

    @Test
    void installationIdentitySurvivesClientRecreationAndSeparatesAppsFromMods() {
        Path identity = temporary.resolve("resync-client-id");
        ReSyncDesktopIdentityProvider first = new ReSyncDesktopIdentityProvider(identity, "remotely-app");
        ReSyncDesktopIdentityProvider restarted = new ReSyncDesktopIdentityProvider(identity, "remotely-app");
        ReSyncDesktopIdentityProvider mod = new ReSyncDesktopIdentityProvider(identity, "remotely-mod");
        ReSyncDesktopIdentityProvider otherInstall = new ReSyncDesktopIdentityProvider(
            temporary.resolve("other-install"), "remotely-app");

        assertEquals(first.clientId("server"), first.clientId("server"));
        assertEquals(first.clientId("server"), restarted.clientId("server"));
        assertNotEquals(first.clientId("server"), mod.clientId("server"));
        assertNotEquals(first.clientId("server"), otherInstall.clientId("server"));
        assertNotEquals(first.clientId("server"), first.clientId("other"));
    }

    @Test
    void concurrentClientsShareOneCommittedIdentity() {
        Path identity = temporary.resolve("concurrent-id");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> clientIdAfter(start, identity));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> clientIdAfter(start, identity));

        start.countDown();

        assertEquals(first.join(), second.join());
    }

    @Test
    void invalidCommittedIdentityFailsClosed() throws IOException {
        Path identity = temporary.resolve("invalid-id");
        Files.writeString(identity, "invalid");

        assertThrows(IllegalStateException.class, () -> new ReSyncDesktopIdentityProvider(identity, "remotely-app"));
        assertEquals("invalid", Files.readString(identity));
    }

    private String clientIdAfter(CountDownLatch start, Path identity) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return new ReSyncDesktopIdentityProvider(identity, "remotely-app").clientId("server");
    }
}
