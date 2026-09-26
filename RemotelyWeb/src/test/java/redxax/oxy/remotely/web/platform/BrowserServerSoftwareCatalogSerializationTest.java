package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import redxax.oxy.remotely.settings.server.BrowserSafeYamlServerSettingsMetadataParser;
import redxax.oxy.remotely.settings.server.BundledServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.server.ServerConfigurationUiComposition;
import redxax.oxy.remotely.ui.server.ServerConfigurationUiPlatform;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.ui.settings.controllers.PlayerActionsFileProvider;
import redxax.oxy.remotely.ui.settings.controllers.PortManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerExtraSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerFeatureSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerGeneralSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerJvmSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerPlanSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.SubuserSettingsProvider;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDocumentDataController;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDocumentStore;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.settings.controllers.BackupSettingsProvider;
import restudio.rebase.settings.controllers.ModpackSettingsProvider;
import restudio.rebase.settings.controllers.ModpackSettingsTarget;
import restudio.rebase.settings.controllers.VersionSettingsCatalog;
import restudio.rebase.settings.controllers.VersionSettingsTarget;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.rescreen.ReScreen;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserServerSoftwareCatalogSerializationTest {
    @BeforeAll
    static void initializeUiRuntime() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void mapsSoftwareIntoTheCanonicalVersionSettingsCatalogModel() {
        var values = BrowserRemotelyServerApi.catalogSoftware("{\"paper\":{\"type\":\"paper\",\"categories\":[\"plugins\"],\"compatibility\":[\"bukkit\"]}}");

        assertEquals("paper", values.get("paper").type());
        assertEquals("plugins", values.get("paper").categories().getFirst());
        assertEquals("bukkit", values.get("paper").compatibility().getFirst());
    }

    @Test
    void preservesHostedRuntimeAndSensitiveStartupMetadata() {
        var value = BrowserRemotelyServerApi.hostedCatalog(BrowserJson.object("{\"defaultEggId\":7,\"eggs\":[{\"eggId\":7,\"name\":\"Minecraft\",\"runtimeImages\":{\"Java 21\":\"image:21\"},\"startup\":[{\"key\":\"API_TOKEN\",\"editable\":true,\"sensitive\":true,\"reinstallRequired\":false}],\"reinstallRequired\":true}],\"plans\":[]}"));

        assertEquals(7, value.defaultEggId());
        assertEquals("image:21", value.eggs().getFirst().runtimeImages().get("Java 21"));
        assertTrue(value.eggs().getFirst().startup().getFirst().sensitive());
        assertNull(value.eggs().getFirst().startup().getFirst().defaultValue());
    }

    @Test
    void usesTheSafeSoftwareIdentityForSettingsMatching() {
        var server = new ServerModels.ClientServerView();
        server.identifier = "abc123";
        server.loader = "VANILLA";
        server.software = "QUILT";

        var target = new BrowserServerConfigurationTarget(server);

        assertEquals("abc123", target.catalogServerId());
        assertEquals("QUILT", target.software());
        assertEquals("quilt", target.softwareTokens().iterator().next());
    }

    @Test
    void keepsServerSoftwareSeparateFromTheModLoader() {
        var server = new ServerModels.ClientServerView();
        server.identifier = "abc123";
        server.loader = "FABRIC";
        server.software = "PAPER";

        var target = new BrowserServerConfigurationTarget(server);

        assertEquals(ModLoader.FABRIC, target.modLoader());
        assertEquals("PAPER", target.software());

        target.serverSoftwareType("PURPUR");

        assertEquals("PURPUR", target.view().software);
        assertEquals("FABRIC", target.view().loader);
        assertEquals(ModLoader.FABRIC, target.modLoader());

        target.modLoader(ModLoader.QUILT);

        assertEquals("QUILT", target.view().loader);
        assertEquals("PURPUR", target.software());

        target.modLoader("FORGE");

        assertEquals("FORGE", target.view().loader);
        assertEquals(ModLoader.FORGE, target.modLoader());
        assertEquals("PURPUR", target.software());
    }

    @Test
    void derivesTheBrowserBackendFromTheTypedServerEnvironment() {
        var server = new ServerModels.ClientServerView();
        server.identifier = "abc123";
        server.environment = Map.of("backend", "local");

        var target = new BrowserServerConfigurationTarget(server);

        assertEquals("LOCAL", target.backendType());
        assertFalse(target.remote());
    }

    @Test
    void compositionExposesTheVisibleServerSoftwareCategory() {
        var target = BrowserServerConfigurationTarget.create();
        target.backend("RESTUDIO", Map.of());
        ServerConfigurationUiPlatform platform = new ServerConfigurationUiPlatform() {
            @Override public Object target() { return target; }
            @Override public Object originalTarget() { return target; }
            @Override public String name() { return target.name(); }
            @Override public boolean linkedModpack() { return false; }
            @Override public boolean managementCompatible() { return false; }
            @Override public boolean managementEnabled() { return false; }
            @Override public VersionSettingsTarget versionTarget() { return target; }
            @Override public VersionSettingsCatalog versionCatalog() { return null; }
            @Override public ModpackSettingsTarget modpackTarget() { return target; }
            @Override public ModpackSettingsTarget managedModpackTarget() { return target; }
            @Override public ModpackSettingsProvider modpackProvider() { return null; }
            @Override public DiscordRpcSettingsController.InstanceSettings discordSettings() { return null; }
            @Override public ServerJvmSettingsProvider jvmSettingsProvider() { return null; }
            @Override public ServerFeatureSettingsProvider featureSettingsProvider() { return null; }
            @Override public ServerGeneralSettingsProvider generalSettingsProvider() { return null; }
            @Override public ServerManagementSettingsProvider managementSettings() { return null; }
            @Override public ServerPlanSettingsProvider planSettingsProvider() { return null; }
            @Override public BackupSettingsProvider backupProvider() { return null; }
            @Override public PortManagementSettingsProvider portProvider() { return null; }
            @Override public SubuserSettingsProvider subuserProvider() { return null; }
            @Override public PlayerActionsFileProvider playerActionsFileProvider() { return null; }
            @Override public ServerLiveSettingsProvider liveSettingsProvider() { return null; }
            @Override public ServerExtraSettingsController.DocumentAccess documentAccess() { return null; }
        };
        ServerScreenHost.ConfigurationState state = new ServerScreenHost.ConfigurationState(target, target, null,
                false, true, false, target.id(), "");

        ServerScreenHost.ConfigurationUi composition = ServerConfigurationUiComposition.create(new TestScreen(), state,
                null, Map.of(), List.of(), () -> {}, () -> true, "", platform);

        assertTrue(composition.settings().containsKey("General"));
        assertEquals("General", composition.settings().keySet().iterator().next());
        assertFalse(composition.settings().containsKey("Server Software"));
        assertFalse(composition.settings().containsKey("Software Settings"));
        composition.cleanup().run();
    }

    private static final class TestScreen extends ReScreen {
        private TestScreen() {
            super();
        }
    }

    @Test
    void canonicalSettingsControllerKeepsSoftwareSettingsWithMissingOptionalFiles() {
        var server = new ServerModels.ClientServerView();
        server.identifier = "abc123";
        server.software = "PAPER";
        var target = new BrowserServerConfigurationTarget(server);
        var registry = ServerSettingsRegistry.empty();
        BundledServerSettingsRegistry.loadInto(registry, new BrowserSafeYamlServerSettingsMetadataParser());
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                return Async.completed(Document.missing());
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return Async.completed(null);
            }
        };
        var controller = new ServerSettingsDocumentDataController(target, registry.snapshot(), store);

        controller.load().join();

        assertTrue(controller.tabNames().contains("Software Settings"));
        assertFalse(controller.settings("Software Settings").isEmpty());
    }
}
