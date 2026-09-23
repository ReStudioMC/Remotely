package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.world.WorldProfileSettings;
import redxax.oxy.remotely.data.flow.world.WorldRegistryEntry;
import restudio.rescreen.util.JsonTreeParser;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDesignerJsonContractTest {
    @Test
    void saveProfilePreservesSettingsOutsideTheDesignerForm() {
        WorldProfileSettings current = new WorldProfileSettings();
        current.setEntryFeeEnabled(true);
        current.setEntryFee(19.95);
        WorldProfileSettings replacement = new WorldProfileSettings();

        WorldDesignerScreen.preserveUneditedProfileSettings(current, replacement);

        assertTrue(replacement.isEntryFeeEnabled());
        assertEquals(19.95, replacement.getEntryFee());
    }

    @Test
    void explicitWorldDocumentRoundTripPreservesEverySettingAndExactLongs() {
        WorldProfileSettings profile = new WorldProfileSettings();
        profile.setAlias("Builders");
        profile.setHidden(true);
        profile.setAccessPermission("world.enter");
        profile.setBypassPermission("world.bypass");
        profile.setRespawnWorld("spawn");
        profile.setForceGameMode(true);
        profile.setGameMode("ADVENTURE");
        profile.setCustomSpawnEnabled(true);
        profile.setSpawnX(12.25);
        profile.setSpawnY(-32.5);
        profile.setSpawnZ(4096.75);
        profile.setSpawnYaw(127.5F);
        profile.setSpawnPitch(-45.25F);
        profile.setEntryFeeEnabled(true);
        profile.setEntryFee(19.95);
        profile.setPvpEnabled(false);
        profile.setKeepSpawnLoaded(false);
        profile.setAutoSaveEnabled(false);
        profile.setAnimalSpawnsEnabled(false);
        profile.setMonsterSpawnsEnabled(false);
        profile.setHungerEnabled(false);
        profile.setAutoHealEnabled(false);
        profile.setBedRespawnEnabled(false);
        profile.setAnchorRespawnEnabled(false);
        profile.setArrivalMessage("Welcome");
        profile.setDenyMessage("Denied");
        profile.setInventoryGroupId("shared");
        profile.setLinkedNetherWorld("builders_nether");
        profile.setLinkedEndWorld("builders_end");
        profile.setLinkedOverworld("builders");
        profile.setNetherScale(4.5);
        profile.setEndScale(2.25);
        profile.setAutoLinkNetherPortal(false);
        profile.setAutoLinkEndPortal(false);
        profile.setNonLivingEntitySpawnsEnabled(false);

        WorldRegistryEntry world = new WorldRegistryEntry();
        world.setWorldName("builders");
        world.setEnvironment("NORMAL");
        world.setGenerator("custom");
        world.setGeneratorConfig("preset=large");
        world.setDifficulty("HARD");
        world.setLoaded(true);
        world.setIsolatedPlayerState(true);
        world.setTimeLockEnabled(true);
        world.setLockedTime(9_007_199_254_740_993L);
        world.setWeatherLockEnabled(true);
        world.setLockedStorm(true);
        world.setLockedThundering(true);
        world.setProfileSettings(profile);
        LinkedHashMap<String, String> gameRules = new LinkedHashMap<>();
        gameRules.put("doDaylightCycle", "false");
        gameRules.put("playersSleepingPercentage", "25");
        world.setGameRules(gameRules);
        world.setUpdatedAt(Long.MAX_VALUE - 2);

        JsonObject encoded = WorldDesignerScreen.encodeWorldDocument(world);
        assertEquals(Set.of("worldName", "environment", "generator", "generatorConfig", "difficulty", "loaded",
            "isolatedPlayerState", "timeLockEnabled", "lockedTime", "weatherLockEnabled", "lockedStorm",
            "lockedThundering", "profileSettings", "gameRules", "updatedAt"), encoded.keySet());
        assertEquals(Set.of("alias", "hidden", "accessPermission", "bypassPermission", "respawnWorld",
            "forceGameMode", "gameMode", "customSpawnEnabled", "spawnX", "spawnY", "spawnZ", "spawnYaw",
            "spawnPitch", "entryFeeEnabled", "entryFee", "pvpEnabled", "keepSpawnLoaded", "autoSaveEnabled",
            "animalSpawnsEnabled", "monsterSpawnsEnabled", "hungerEnabled", "autoHealEnabled",
            "bedRespawnEnabled", "anchorRespawnEnabled", "arrivalMessage", "denyMessage", "inventoryGroupId",
            "linkedNetherWorld", "linkedEndWorld", "linkedOverworld", "netherScale", "endScale",
            "autoLinkNetherPortal", "autoLinkEndPortal", "nonLivingEntitySpawnsEnabled"),
            encoded.getAsJsonObject("profileSettings").keySet());

        String payload = JsonTreeParser.write(encoded);
        WorldRegistryEntry decoded = WorldDesignerScreen.decodeWorldDocument(
            WorldDesignerScreen.parseWorldDocument(payload));

        assertEquals(9_007_199_254_740_993L, decoded.getLockedTime());
        assertEquals(Long.MAX_VALUE - 2, decoded.getUpdatedAt());
        assertEquals(encoded, WorldDesignerScreen.encodeWorldDocument(decoded));
    }
}
