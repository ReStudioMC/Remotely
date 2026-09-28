package redxax.oxy.remotely.settings.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.ui.settings.data.DesktopServerSettingsPackMatcher;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSettingsRegistryTest {
    @Test
    void hostedSettingsPublishOnlyWhenSelectedPacksChange() {
        try (ServerSettingsRegistry registry = ServerSettingsRegistry.empty()) {
            Object owner = new Object();
            AtomicInteger publications = new AtomicInteger();
            registry.addListener(ignored -> publications.incrementAndGet());
            registry.replaceHosted(owner, Map.of("hosted", new ServerSettingsMetadata("hosted", 10,
                    List.of(pack("shared", "First", 0, "paper")))));
            registry.replaceHosted(owner, Map.of("hosted", new ServerSettingsMetadata("hosted", 10,
                    List.of(pack("shared", "First", 0, "paper")))));
            assertEquals(1, publications.get());
            registry.replaceHosted(owner, Map.of("hosted", new ServerSettingsMetadata("hosted", 10,
                    List.of(pack("shared", "Updated", 0, "paper")))));
            assertEquals(2, publications.get());
        }
    }

    @Test
    void higherPriorityExternalPackOverridesProgrammaticPackWithoutDeletingIt(@TempDir Path directory) throws Exception {
        ServerSettingsRegistry registry = ServerSettingsRegistry.empty();
        registry.installStorage(new DesktopServerSettingsRegistry(registry, false));
        ServerSettingsPack programmatic = pack("shared", "Programmatic", 0, "paper");
        registry.register("programmatic", 10, List.of(programmatic));

        Path external = directory.resolve("external.yml");
        Files.writeString(external, """
                providerId: external
                priority: 20
                packs:
                  - id: shared
                    name: External
                    description: External metadata.
                    applicableSoftwareIds: [paper]
                    documents:
                      - path: external.yml
                        format: YAML
                        fields: []
                """);
        registry.loadExternalFile(external);

        assertEquals("External", registry.snapshot().packs().getFirst().name());
        registry.unregister("programmatic");
        assertEquals("External", registry.snapshot().packs().getFirst().name());
    }

    @Test
    void filtersByLoaderTypeCompatibilityCategoriesAndWildcards() {
        ServerSettingsPack pack = pack("paper", "Paper", 0, "paper*");
        Instance instance = new Instance("Paper", "1.21", "paper");
        instance.setServer(true);
        instance.setModLoader(ModLoader.VANILLA);
        instance.setServerSoftwareType("Paper-Preview");
        instance.setServerSoftwareCategories(List.of("plugins"));
        instance.setServerSoftwareCompatibility(List.of("Paper-Preview"));

        assertTrue(DesktopServerSettingsPackMatcher.matches(pack, instance));
        assertTrue(pack.appliesTo(List.of("PAPER-DEV")));
    }

    @Test
    void keepsLastValidExternalMetadataUntilTheFileIsDeleted(@TempDir Path directory) throws Exception {
        Path external = directory.resolve("plugin.yml");
        Files.writeString(external, metadata("Plugin Settings"));
        try (ServerSettingsRegistry registry = ServerSettingsRegistry.empty()) {
            registry.installStorage(new DesktopServerSettingsRegistry(registry, false));
            registry.watchExternalDirectory(directory, 5_000);
            assertEquals("Plugin Settings", registry.snapshot().packs().getFirst().name());

            Files.writeString(external, "packs: invalid");
            registry.reloadExternalDirectory();
            assertEquals("Plugin Settings", registry.snapshot().packs().getFirst().name());

            Files.delete(external);
            registry.reloadExternalDirectory();
            assertTrue(registry.snapshot().packs().isEmpty());
        }
    }

    @Test
    void builtInsAggregateAvailablePacksIncludingMinecraftServerProperties() {
        try (ServerSettingsRegistry registry = ServerSettingsRegistry.empty()) {
            registry.installStorage(new DesktopServerSettingsRegistry(registry, true));
            ServerSettingsPack purpur = registry.packs().stream().filter(pack -> pack.id().equals("purpur")).findFirst().orElseThrow();
            assertEquals(List.of("purpur"), purpur.applicableSoftwareIds());
            ServerSettingsDocument purpurDocument = purpur.documents().getFirst();
            assertEquals("purpur.yml", purpurDocument.relativePath());
            assertTrue(purpurDocument.createIfMissing());
            assertTrue(purpurDocument.fields().stream().anyMatch(field -> field.key().equals("settings.use-alternate-keepalive")));
            assertTrue(purpurDocument.fields().stream().noneMatch(field -> field.key().equals("config-version")));
            assertTrue(purpurDocument.fields().stream().anyMatch(field -> field.key().equals("world-settings.default.hunger.starvation-damage")));
            assertTrue(purpurDocument.fields().stream().anyMatch(field -> field.key().equals("world-settings.*") && field.type() == ServerSettingsFieldType.MAP));
            assertTrue(registry.packs().stream().anyMatch(pack -> pack.id().equals("minecraft-server-properties")));
            assertTrue(registry.packs().stream().flatMap(pack -> pack.documents().stream())
                    .anyMatch(document -> document.relativePath().equals("server.properties")));
        }
    }

    @Test
    void purpurPackOnlyAppliesToPurpurSoftware() {
        try (ServerSettingsRegistry registry = ServerSettingsRegistry.empty()) {
            registry.installStorage(new DesktopServerSettingsRegistry(registry, true));
            ServerSettingsPack purpur = registry.packs().stream()
                    .filter(pack -> pack.id().equals("purpur"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(purpur.appliesTo(List.of("purpur")));
            assertFalse(purpur.appliesTo(List.of("paper")));
            assertFalse(purpur.appliesTo(List.of("spigot")));
        }
    }

    private static String metadata(String name) {
        return """
                providerId: plugin
                packs:
                  - id: plugin
                    name: %s
                    description: Plugin metadata.
                    software: [paper]
                    documents:
                      - path: plugins/Plugin/config.yml
                        format: yaml
                        fields: []
                """.formatted(name);
    }

    private static ServerSettingsPack pack(String id, String name, int priority, String software) {
        return new ServerSettingsPack(id, name, "A test settings pack.", priority, List.of(software), List.of(
                new ServerSettingsDocument("config/settings.yml", ServerSettingsFormat.YAML, false, false)
        ));
    }
}
