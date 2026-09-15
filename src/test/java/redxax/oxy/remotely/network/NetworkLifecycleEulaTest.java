package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.network.config.PropertiesConfigurationAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkLifecycleEulaTest {
    @Test
    void createsAnAcceptedEulaWhenMissing() {
        assertEquals("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\neula=true\n", NetworkStartPreparation.acceptMinecraftEula(""));
    }

    @Test
    void changesARejectedEulaWithoutDiscardingItsHeader() {
        assertEquals("# Minecraft EULA\neula=true\n", NetworkStartPreparation.acceptMinecraftEula("# Minecraft EULA\neula=false\n"));
    }

    @Test
    void leavesAnAcceptedEulaUntouched() {
        String accepted = "# Existing\r\neula=TRUE\r\n";
        assertSame(accepted, NetworkStartPreparation.acceptMinecraftEula(accepted));
    }

    @Test
    void updatesOnlyTheEffectiveDuplicateEulaSetting() {
        assertEquals("eula=true\neula=true\n", NetworkStartPreparation.acceptMinecraftEula("eula=true\neula=false\n"));
        assertEquals("eula=false\neula=true\n", NetworkStartPreparation.acceptMinecraftEula("eula=false\neula=false\n"));
        String accepted = "eula=false\neula=true\n";
        assertSame(accepted, NetworkStartPreparation.acceptMinecraftEula(accepted));
    }

    @Test
    void followsEffectiveJavaPropertiesSyntaxAndTerminatesAContinuedFile() {
        assertEquals("eula=true\neula=true\n", NetworkStartPreparation.acceptMinecraftEula("eula=true\neula=garbage\n"));
        assertEquals("eula=true\neula:true\n", NetworkStartPreparation.acceptMinecraftEula("eula=true\neula:false\n"));
        String continued = "other=value\\\n";
        String prepared = NetworkStartPreparation.acceptMinecraftEula(continued);

        assertTrue(prepared.startsWith(continued));
        assertEquals("true", new PropertiesConfigurationAdapter().read(prepared, "eula"));
    }

    @Test
    void appendsTheSettingWhenItIsAbsent() {
        assertEquals("# Existing\r\neula=true\r\n", NetworkStartPreparation.acceptMinecraftEula("# Existing\r\n"));
    }
}
