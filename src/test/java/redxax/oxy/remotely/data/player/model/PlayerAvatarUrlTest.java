package redxax.oxy.remotely.data.player.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerAvatarUrlTest {
    @Test
    void playerNameWinsAcrossDifferentServerUuids() {
        UUID onlineId = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        UUID offlineId = UUID.fromString("5627dd98-e6be-3c21-b8a8-e92344183641");

        assertEquals(PlayerAvatarUrl.resolve("DumbFridge", onlineId, 64), PlayerAvatarUrl.resolve("DumbFridge", offlineId, 64));
        assertEquals("https://mc-heads.net/avatar/DumbFridge/64.png", PlayerAvatarUrl.resolve("DumbFridge", offlineId, 64));
    }

    @Test
    void uuidIsUsedOnlyWhenPlayerNameIsUnavailable() {
        UUID playerId = UUID.fromString("5627dd98-e6be-3c21-b8a8-e92344183641");

        assertEquals("https://mc-heads.net/avatar/5627dd98-e6be-3c21-b8a8-e92344183641/32.png", PlayerAvatarUrl.resolve(" ", playerId, 32));
        assertEquals("", PlayerAvatarUrl.resolve(null, (UUID) null, 64));
    }

    @Test
    void unsafeSubjectsAreNormalizedAndSizeIsBounded() {
        assertEquals("https://mc-heads.net/avatar/Player_Name/512.png", PlayerAvatarUrl.resolve(" Player Name ", "", 900));
    }
}
