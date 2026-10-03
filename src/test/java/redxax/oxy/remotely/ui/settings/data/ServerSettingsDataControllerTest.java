package redxax.oxy.remotely.ui.settings.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.settings.server.ServerSettingsDocument;
import redxax.oxy.remotely.settings.server.BrowserSafeYaml;
import redxax.oxy.remotely.settings.server.ServerSettingsField;
import redxax.oxy.remotely.settings.server.ServerSettingsFieldType;
import redxax.oxy.remotely.settings.server.ServerSettingsFormat;
import redxax.oxy.remotely.settings.server.ServerSettingsPack;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.BackendFactory;
import restudio.rebase.backend.BackendFeature;
import restudio.rebase.backend.ExecutionProvider;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.instance.Instance;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingEntryWidget;
import restudio.rescreen.ui.settings.CollectionSettingWidget;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.settings.options.OptionEditor;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.util.UiTasks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.math.BigInteger;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDocumentStore.Document;
import restudio.rescreen.ui.settings.CompoundToggleSettingWidget;
import restudio.rescreen.ui.settings.SeedSettingWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerSettingsDataControllerTest {
    @BeforeAll
    static void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void missingCreationDocumentKeepsConfiguredServerProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("online-mode", "true");
        ServerSettingsField field = new ServerSettingsField("motd", "motd", ServerSettingsFieldType.TEXT,
                "Software Settings", "Server", "Message", "Server message", "", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES,
                true, true, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("settings", "Settings", "Settings", 0,
                List.of("test"), List.of(document));
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override public Collection<String> softwareTokens() { return List.of("test"); }
            @Override public String property(String key) { return properties.get(key); }
            @Override public void property(String key, String value) { properties.put(key, value); }
            @Override public void removeProperty(String key) { properties.remove(key); }
            @Override public void replaceProperties(Map<String, String> values) { properties.clear(); properties.putAll(values); }
        };
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override public Async<Document> read(String path) { return Async.completed(Document.missing()); }
            @Override public Async<Void> write(String path, String content) { return Async.failed(new AssertionError("Creation Must Not Write Files")); }
        };

        ServerSettingsDocumentDataController controller = new ServerSettingsDocumentDataController(target,
                new ServerSettingsSnapshot(List.of(pack)), store, BrowserSafeYaml::parse);
        controller.load().join();

        assertEquals("true", properties.get("online-mode"));
        assertEquals(List.of("Software Settings"), controller.tabNames());
        controller.close();
    }

    @Test
    void scheduledDocumentLoadPublishesOnlyAfterPreparationAndRejectsClose() {
        List<Runnable> tasks = new ArrayList<>();
        ServerSettingsField field = new ServerSettingsField("name", "name", ServerSettingsFieldType.TEXT, "Server",
                "Configuration", "Name", "Server name", "", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES,
                true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("settings", "Settings", "Settings", 0,
                List.of("test"), List.of(document));
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override public Collection<String> softwareTokens() { return List.of("test"); }
            @Override public String property(String key) { return null; }
            @Override public void property(String key, String value) { }
            @Override public void removeProperty(String key) { }
            @Override public void replaceProperties(Map<String, String> properties) { }
        };
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override public Async<Document> read(String path) { return Async.completed(new Document(true, "name=before\n")); }
            @Override public Async<Void> write(String path, String content) { return Async.completed(null); }
        };

        ServerSettingsDocumentDataController ready = new ServerSettingsDocumentDataController(target,
                new ServerSettingsSnapshot(List.of(pack)), store, BrowserSafeYaml::parse, null, tasks::add);
        assertFalse(ready.load().isDone());
        while (!tasks.isEmpty()) tasks.removeFirst().run();
        assertTrue(ready.load().isDone());
        assertEquals(List.of("Server"), ready.tabNames());
        assertEquals("before", textOption(ready, "Server").get());

        ServerSettingsDocumentDataController closed = new ServerSettingsDocumentDataController(target,
                new ServerSettingsSnapshot(List.of(pack)), store, BrowserSafeYaml::parse, null, tasks::add);
        closed.close();
        while (!tasks.isEmpty()) tasks.removeFirst().run();
        assertTrue(closed.tabNames().isEmpty());
    }

    @Test
    void parsesAndWritesQuotedVelocityTomlValues() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("velocity.toml");
        files.put(documentPath, "[servers]\nlobby = \"127.0.0.1:25566\"\nother = \"keep\"\n");
        Instance instance = velocity(root);

        ServerSettingsDataController controller = controller(instance, files, ServerSettingsFormat.TOML,
                "velocity.toml", "servers.lobby", "127.0.0.1:25566", false);
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Proxy");
        assertEquals("127.0.0.1:25566", option.get());
        option.set("127.0.0.1:25570");
        option.apply();

        controller.save(instance).join();

        String updated = files.read(documentPath).join();
        assertTrue(updated.contains("lobby = \"127.0.0.1:25570\""));
        assertTrue(updated.contains("other = \"keep\""));
    }

    @Test
    void mergesUnrelatedExternalChanges() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("paper.properties");
        files.put(documentPath, "managed=old\nunmanaged=keep\n");
        Instance instance = velocity(root);
        ServerSettingsDataController controller = controller(instance, files, ServerSettingsFormat.PROPERTIES,
                "paper.properties", "managed", "old", false);
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        option.set("new");
        option.apply();
        files.put(documentPath, "managed=old\nunmanaged=external\n");

        controller.save(instance).join();

        String updated = files.read(documentPath).join();
        assertTrue(updated.contains("managed=new"));
        assertTrue(updated.contains("unmanaged=external"));
    }

    @Test
    void rejectsSameKeyExternalChanges() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("paper.properties");
        files.put(documentPath, "managed=old\nunmanaged=keep\n");
        Instance instance = velocity(root);
        ServerSettingsDataController controller = controller(instance, files, ServerSettingsFormat.PROPERTIES,
                "paper.properties", "managed", "old", false);
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        option.set("new");
        option.apply();
        files.put(documentPath, "managed=external\nunmanaged=keep\n");

        CompletableFuture<Void> save = JvmAsyncBridge.toFuture(controller.save(instance));
        CompletionException failure = assertThrows(CompletionException.class, () -> save.join());
        assertInstanceOf(ServerSettingsConflictException.class, failure.getCause());
        assertEquals(0, files.writeCount());
    }

    @Test
    void savesChangedValuesToDifferentNewTargetWithoutConflict() {
        Path sourceRoot = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        Path targetRoot = Path.of("settings-target-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles sourceFiles = new MemoryFiles();
        MemoryFiles targetFiles = new MemoryFiles();
        Path sourceDocument = sourceRoot.resolve("paper.properties");
        Path targetDocument = targetRoot.resolve("paper.properties");
        sourceFiles.put(sourceDocument, "managed=old\n");
        Instance source = velocity(sourceRoot);
        ServerSettingsDataController controller = controller(source, sourceFiles, ServerSettingsFormat.PROPERTIES,
                "paper.properties", "managed", "old", false);
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        option.set("new");
        option.apply();

        BackendFactory.register("SETTINGS_MEMORY", (config, instance) -> new MemoryBackend(targetFiles));
        Instance target = velocity(targetRoot);
        target.setBackendConfig(new BackendConfig("SETTINGS_MEMORY", new HashMap<>()));

        controller.save(target).join();

        assertEquals("managed=new\n", targetFiles.read(targetDocument).join());
    }

    @Test
    void writesYamlTextAsText() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("plugin.yml");
        files.put(documentPath, "managed: old\n");
        Instance instance = velocity(root);
        ServerSettingsDataController controller = controller(instance, files, ServerSettingsFormat.YAML,
                "plugin.yml", "managed", "old", false);
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        option.set("true");
        option.apply();
        controller.save(instance).join();

        assertTrue(files.read(documentPath).join().contains("managed: 'true'"));
    }

    @Test
    void writesYamlListsWithoutQuotingAndKeepsSiblingValues() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("spigot.yml");
        files.put(documentPath, "commands:\n  replace-commands:\n  - setblock\n  keep: true\n");
        Instance instance = velocity(root);
        ServerSettingsField field = new ServerSettingsField("commands.replace", "commands.replace-commands", ServerSettingsFieldType.LIST,
                "Software Settings", "Spigot Commands", "Replaced Commands", "Commands replaced by the server.", List.of("setblock"), null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("spigot.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("spigot", "Spigot", "Spigot", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Software Settings");
        assertEquals("[setblock]", option.get());
        option.set("[setblock, summon]");
        option.apply();
        controller.save(instance).join();

        String updated = files.read(documentPath).join();
        assertTrue(updated.contains("replace-commands: [setblock, summon]"));
        assertTrue(updated.contains("keep: true"));
    }

    @Test
    void readsAndWritesYamlMapsAsFlowValues() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("spigot.yml");
        files.put(documentPath, "stats:\n  forced-stats: {}\n  disable-saving: false\n");
        Instance instance = velocity(root);
        ServerSettingsField field = new ServerSettingsField("stats.forced", "stats.forced-stats", ServerSettingsFieldType.MAP,
                "Software Settings", "Spigot Stats", "Forced Stats", "Forces selected statistics.", Map.of(), null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("spigot.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("spigot", "Spigot", "Spigot", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Software Settings");
        assertEquals("{}", option.get());
        option.set("{minecraft: 1}");
        option.apply();
        controller.save(instance).join();

        String updated = files.read(documentPath).join();
        assertTrue(updated.contains("forced-stats: {minecraft: 1}"));
        assertTrue(updated.contains("disable-saving: false"));
    }

    @Test
    void appliesRootMapBeforeNestedLeafWithoutLosingEitherMutation() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("spigot.yml");
        files.put(documentPath, "world-settings:\n  default:\n    enabled: false\n  overworld:\n    enabled: false\n");
        Instance instance = velocity(root);
        ServerSettingsField rootField = new ServerSettingsField("world-settings.dynamic", "world-settings", ServerSettingsFieldType.MAP,
                "Software Settings", "Dynamic Worlds", "World Settings", "World overrides.", Map.of(), null, null, List.of());
        ServerSettingsField leafField = new ServerSettingsField("world-settings.default.enabled", "world-settings.default.enabled", ServerSettingsFieldType.BOOLEAN,
                "Software Settings", "Default World", "Enabled", "Default world setting.", false, null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("spigot.yml", ServerSettingsFormat.YAML, true, false, List.of(rootField, leafField));
        ServerSettingsPack pack = new ServerSettingsPack("spigot", "Spigot", "Spigot", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> rootOption = textOption(controller, "Software Settings");
        rootOption.set("{default: {enabled: false}, overworld: {enabled: true}}");
        rootOption.apply();
        ConfigOption<Boolean> leafOption = booleanOption(controller, "Software Settings", 1);
        leafOption.set(true);
        leafOption.apply();
        controller.save(instance).join();

        String updated = files.read(documentPath).join();
        assertTrue(updated.contains("default: {enabled: true}"));
        assertTrue(updated.contains("overworld: {enabled: true}"));
    }

    @Test
    void mergesNestedMapMutationsWithEscapedDynamicKeys() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("spigot.yml");
        files.put(documentPath, "world-settings:\n  default:\n    enabled: false\n  world.one:\n    enabled: false\n");
        Instance instance = velocity(root);
        ServerSettingsField rootField = new ServerSettingsField("world-settings.dynamic", "world-settings", ServerSettingsFieldType.MAP,
                "Software Settings", "Dynamic Worlds", "World Settings", "World overrides.", Map.of(), null, null, List.of());
        ServerSettingsField leafField = new ServerSettingsField("world-settings.world-one.enabled", "world-settings.world\\.one.enabled", ServerSettingsFieldType.BOOLEAN,
                "Software Settings", "World One", "Enabled", "World one setting.", false, null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("spigot.yml", ServerSettingsFormat.YAML, true, false, List.of(rootField, leafField));
        ServerSettingsPack pack = new ServerSettingsPack("spigot", "Spigot", "Spigot", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> rootOption = textOption(controller, "Software Settings");
        rootOption.set("{default: {enabled: false}, world.one: {enabled: true}}");
        rootOption.apply();
        ConfigOption<Boolean> leafOption = booleanOption(controller, "Software Settings", 1);
        leafOption.set(true);
        leafOption.apply();
        controller.save(instance).join();

        String updated = files.read(documentPath).join();
        assertTrue(updated.contains("world.one: {enabled: true}"));
        assertTrue(updated.contains("default: {enabled: false}"));
    }

    @Test
    void doesNotRewriteEscapedServerPropertiesWithoutAnEdit() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        files.put(root.resolve("server.properties"), "motd=Hello\\ World\n");
        Instance instance = velocity(root);
        ServerSettingsDataController controller = controller(instance, files, ServerSettingsFormat.PROPERTIES,
                "server.properties", "motd", "", false);
        controller.load().join();

        assertEquals("Hello World", textOption(controller, "Server").get());
        assertTrue(controller.changedFileContents().isEmpty());
    }

    @Test
    void browserDocumentStoreTargetsChangedServerProperty() {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("server.properties", "# header\nmotd=Hello\\ World\nexternal=before\n");
        Map<String, String> properties = new LinkedHashMap<>();
        AtomicInteger writes = new AtomicInteger();
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override public Collection<String> softwareTokens() { return List.of("velocity"); }
            @Override public String property(String key) { return properties.get(key); }
            @Override public void property(String key, String value) { properties.put(key, value); }
            @Override public void removeProperty(String key) { properties.remove(key); }
            @Override public void replaceProperties(Map<String, String> values) { properties.clear(); properties.putAll(values); }
        };
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override public Async<Document> read(String relativePath) {
                String content = documents.get(relativePath);
                return Async.completed(content == null ? Document.missing() : new Document(true, content));
            }
            @Override public Async<Void> write(String relativePath, String content) {
                documents.put(relativePath, content);
                writes.incrementAndGet();
                return Async.completed(null);
            }
        };
        ServerSettingsField field = new ServerSettingsField("motd", "motd", ServerSettingsFieldType.TEXT, "Server",
                "Configuration", "Message", "Server message", "", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("settings", "Settings", "Settings", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target, new ServerSettingsSnapshot(List.of(pack)), store);
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        option.set("Welcome Home");
        option.apply();
        documents.put("server.properties", "# header\nmotd=Hello\\ World\nexternal=after\n");
        controller.save(target).join();

        assertEquals(1, writes.get());
        assertTrue(documents.get("server.properties").contains("# header"));
        assertTrue(documents.get("server.properties").contains("motd=Welcome\\ Home"));
        assertTrue(documents.get("server.properties").contains("external=after"));
    }

    @Test
    void retriesOnlyDocumentsThatDidNotFinishWriting() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path firstPath = root.resolve("first.properties");
        Path secondPath = root.resolve("second.properties");
        files.put(firstPath, "managed=old\n");
        files.put(secondPath, "managed=old\n");
        Instance instance = velocity(root);
        ServerSettingsDocument first = document("first.properties", "First");
        ServerSettingsDocument second = document("second.properties", "Second");
        ServerSettingsPack pack = new ServerSettingsPack("settings", "Settings", "Settings", 0, List.of("velocity"), List.of(first, second));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> firstOption = textOption(controller, "First");
        firstOption.set("new");
        firstOption.apply();
        ConfigOption<String> secondOption = textOption(controller, "Second");
        secondOption.set("new");
        secondOption.apply();
        files.failNextWrite(secondPath);

        assertThrows(CompletionException.class, () -> JvmAsyncBridge.toFuture(controller.save(instance)).join());
        assertTrue(files.read(firstPath).join().contains("managed=new"));
        assertTrue(files.read(secondPath).join().contains("managed=old"));

        controller.save(instance).join();
        assertTrue(files.read(secondPath).join().contains("managed=new"));
    }

    @Test
    void routesWorldFieldToDropdownWhenFewWorlds() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path propertiesPath = root.resolve("server.properties");
        files.put(propertiesPath, "level-name=world\n");
        Instance instance = velocity(root);
        instance.getServerProperties().setProperty("level-name", "world");

        ServerSettingsField field = new ServerSettingsField("level-name", "level-name", ServerSettingsFieldType.TEXT,
                "Server", "World", "Level Name", "World folder name", "world", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("server", "Server", "Server", 0, List.of("velocity"), List.of(document));

        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("velocity");
            }
            @Override
            public String property(String key) {
                return instance.getServerProperties().getProperty(key);
            }
            @Override
            public void property(String key, String value) {
                instance.getServerProperties().setProperty(key, value);
            }
            @Override
            public void removeProperty(String key) {
                instance.getServerProperties().remove(key);
            }
            @Override
            public void replaceProperties(Map<String, String> properties) {
                instance.getServerProperties().clear();
                instance.getServerProperties().putAll(properties);
            }
        };

        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target, new ServerSettingsSnapshot(List.of(pack)),
                worldStore(root, files, List.of("world", "world_nether", "world_the_end")));
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        assertEquals("world", option.get());
        SettingEntryWidget entry = entryWidget(controller, "Server");
        DropDownWidget<?> worlds = assertInstanceOf(DropDownWidget.class, entry.mountedWidgets.getLast());
        assertEquals(List.of("world", "world_nether", "world_the_end"), worlds.getItems());

        option.set("world_nether");
        option.apply();
        controller.save(instance).join();

        String updated = files.read(propertiesPath).join();
        assertTrue(updated.contains("level-name=world_nether"));
    }

    @Test
    void keepsCustomWorldInputAlongsideDiscoveredChoices() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path propertiesPath = root.resolve("server.properties");
        files.put(propertiesPath, "level-name=world1\n");
        Instance instance = velocity(root);

        ServerSettingsField field = new ServerSettingsField("level-name", "level-name", ServerSettingsFieldType.TEXT,
                "Server", "World", "Level Name", "World folder name", "world1", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("server", "Server", "Server", 0, List.of("velocity"), List.of(document));

        List<String> manyWorlds = List.of("world1", "world2", "world3", "world4", "world5", "world6", "world7", "world8", "world9", "world10", "world11", "world12");
        Map<String, String> properties = new LinkedHashMap<>(Map.of("level-name", "world1"));
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("velocity");
            }
            @Override
            public String property(String key) {
                return properties.get(key);
            }
            @Override
            public void property(String key, String value) { properties.put(key, value); }
            @Override
            public void removeProperty(String key) { properties.remove(key); }
            @Override
            public void replaceProperties(Map<String, String> values) { properties.clear(); properties.putAll(values); }
        };

        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target, new ServerSettingsSnapshot(List.of(pack)),
                worldStore(root, files, manyWorlds));
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        SettingEntryWidget entry = entryWidget(controller, "Server");
        DropDownWidget<?> worlds = assertInstanceOf(DropDownWidget.class, entry.mountedWidgets.getLast());
        assertEquals(12, worlds.getItems().size());
        option.set("custom-world");
        option.apply();
        controller.save(instance).join();
        assertTrue(files.read(propertiesPath).join().contains("level-name=custom-world"));
    }

    @Test
    void worldDiscoveryDoesNotBlockSettingsOrReplaceExposedOptions() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path propertiesPath = root.resolve("server.properties");
        files.put(propertiesPath, "level-name=world\n");
        Instance instance = velocity(root);
        instance.getServerProperties().setProperty("level-name", "world");
        ServerSettingsField field = new ServerSettingsField("level-name", "level-name", ServerSettingsFieldType.TEXT,
                "Server", "World", "Level Name", "World folder name", "world", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("server", "Server", "Server", 0, List.of("velocity"), List.of(document));
        Async<List<ServerSettingsDocumentStore.Entry>> stalledDiscovery = Async.pending();
        AtomicInteger listings = new AtomicInteger();
        ServerSettingsDocumentStore filesStore = memoryStore(root, files);
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                return filesStore.read(relativePath);
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return filesStore.write(relativePath, content);
            }

            @Override
            public Async<List<Entry>> list(String relativePath) {
                listings.incrementAndGet();
                return relativePath.equals(".") ? stalledDiscovery : Async.completed(List.of(new Entry("level.dat", false)));
            }
        };
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("velocity");
            }

            @Override
            public String property(String key) {
                return instance.getServerProperties().getProperty(key);
            }

            @Override
            public void property(String key, String value) {
                instance.getServerProperties().setProperty(key, value);
            }

            @Override
            public void removeProperty(String key) {
                instance.getServerProperties().remove(key);
            }

            @Override
            public void replaceProperties(Map<String, String> properties) {
                instance.getServerProperties().clear();
                instance.getServerProperties().putAll(properties);
            }
        };
        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target, new ServerSettingsSnapshot(List.of(pack)), store);

        controller.load().join();
        assertEquals(0, listings.get());
        ConfigOption<String> option = textOption(controller, "Server");
        assertEquals(1, listings.get());
        assertEquals("world", option.get());
        assertNull(option.getOptions());

        SettingEntryWidget entry = entryWidget(controller, "Server");
        DropDownWidget<?> selector = (DropDownWidget<?>) entry.mountedWidgets.getLast();
        option.set("custom-world");
        stalledDiscovery.complete(List.of(new ServerSettingsDocumentStore.Entry("world", true),
                new ServerSettingsDocumentStore.Entry("discovered", true)));
        assertEquals(option, textOption(controller, "Server"));
        assertEquals(entry, entryWidget(controller, "Server"));
        assertEquals("custom-world", option.get());
        assertTrue(selector.getItems().contains("discovered"));
        option.apply();
        controller.save(instance).join();
        assertTrue(files.read(propertiesPath).join().contains("level-name=custom-world"));
    }

    @Test
    void publishesIndependentDocumentTabsAndFencesClosedLoads() {
        ServerSettingsField fastField = new ServerSettingsField("fast", "fast", ServerSettingsFieldType.TEXT,
                "Fast", "Fast", "Fast Value", "Fast value", "ready", null, null, List.of());
        ServerSettingsField slowField = new ServerSettingsField("slow", "slow", ServerSettingsFieldType.TEXT,
                "Slow", "Slow", "Slow Value", "Slow value", "later", null, null, List.of());
        ServerSettingsPack pack = new ServerSettingsPack("progressive", "Progressive", "Progressive", 0, List.of("paper"), List.of(
                new ServerSettingsDocument("fast.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(fastField)),
                new ServerSettingsDocument("slow.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(slowField))));
        Async<Document> fastRead = Async.pending();
        Async<Document> slowRead = Async.pending();
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                return relativePath.equals("fast.properties") ? fastRead : slowRead;
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return Async.completed(null);
            }

            @Override
            public Async<List<Entry>> list(String relativePath) {
                return Async.completed(List.of());
            }
        };
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("paper");
            }

            @Override
            public String property(String key) {
                return "world";
            }

            @Override
            public void property(String key, String value) {
            }

            @Override
            public void removeProperty(String key) {
            }

            @Override
            public void replaceProperties(Map<String, String> properties) {
            }
        };
        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target,
                new ServerSettingsSnapshot(List.of(pack)), store);
        List<String> publications = new ArrayList<>();
        controller.onTabsPublished(publications::addAll);

        assertEquals(List.of("Fast", "Slow"), controller.plannedTabNames());
        assertTrue(controller.tabNames().isEmpty());
        assertTrue(controller.settings("Fast").isEmpty());
        fastRead.complete(new Document(true, "fast=ready\n"));

        assertEquals(List.of("Fast"), publications);
        assertEquals(List.of("Fast"), controller.tabNames());
        assertFalse(controller.settings("Fast").isEmpty());
        assertTrue(controller.settings("Slow").isEmpty());
        assertFalse(controller.ready().isDone());

        controller.close();
        assertTrue(slowRead.isCancelled());
        assertEquals(List.of("Fast"), publications);
    }

    @Test
    void publishesLoadedSettingsOnUiThread() throws InterruptedException {
        ServerSettingsField field = new ServerSettingsField("motd", "motd", ServerSettingsFieldType.TEXT,
                "Server", "General", "MOTD", "Message", "Hello", null, null, List.of());
        ServerSettingsPack pack = new ServerSettingsPack("paper", "Paper", "Paper", 0, List.of("paper"), List.of(
                new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(field))));
        Async<Document> read = Async.pending();
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                return read;
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return Async.completed(null);
            }
        };
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("paper");
            }

            @Override
            public String property(String key) {
                return null;
            }

            @Override
            public void property(String key, String value) {
            }

            @Override
            public void removeProperty(String key) {
            }

            @Override
            public void replaceProperties(Map<String, String> properties) {
            }
        };
        Thread uiThread = Thread.currentThread();
        UiTasks.setUiThreadChecker(() -> Thread.currentThread() == uiThread);
        try {
            ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target,
                    new ServerSettingsSnapshot(List.of(pack)), store);
            Thread worker = new Thread(() -> read.complete(new Document(true, "motd=Hello\n")));
            worker.start();
            worker.join();

            assertTrue(controller.tabNames().isEmpty());
            assertFalse(controller.ready().isDone());
            ScreenManager.getInstance().processTasks();
            assertEquals(List.of("Server"), controller.tabNames());
            assertFalse(controller.settings("Server").isEmpty());
            assertTrue(controller.ready().isDone());
            controller.close();
        } finally {
            UiTasks.setUiThreadChecker(() -> true);
        }
    }

    @Test
    void routesBiomeFieldToSearchableSemanticCatalog() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path configPath = root.resolve("paper-world.yml");
        files.put(configPath, "default-biome: 'minecraft:plains'\n");
        Instance instance = velocity(root);

        ServerSettingsField field = new ServerSettingsField("default-biome", "default-biome", ServerSettingsFieldType.TEXT,
                "World", "Generation", "Default Biome", "Sets default biome", "minecraft:plains", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("paper-world.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("paper", "Paper", "Paper", 0, List.of("velocity"), List.of(document));

        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "World");
        OptionEditor.Scalar<String> editor = assertInstanceOf(OptionEditor.Scalar.class, option.getOptionEditor());
        OptionEditor.Choice<String> cherry = editor.value().catalog().get().choices().stream()
            .filter(choice -> choice.value().equals("minecraft:cherry_grove")).findFirst().orElseThrow();
        assertEquals("Cherry Grove", cherry.label());
        assertEquals("minecraft:plains", option.get());

        option.set("minecraft:cherry_grove");
        option.apply();
        controller.save(instance).join();

        String updated = files.read(configPath).join();
        assertTrue(updated.contains("default-biome: 'minecraft:cherry_grove'"));
    }

    @Test
    void routesIntegerWithDisabledSentinelToCompoundToggle() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path configPath = root.resolve("spigot.yml");
        files.put(configPath, "view-distance: 10\n");
        Instance instance = velocity(root);

        ServerSettingsField field = new ServerSettingsField("view-distance", "view-distance", ServerSettingsFieldType.INTEGER,
                "Settings", "World", "View Distance", "View distance. Set to -1 to disable", 8,
                new BigDecimal("2"), new BigDecimal("32"), List.of(), false, true, "-1");
        ServerSettingsDocument document = new ServerSettingsDocument("spigot.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("spigot", "Spigot", "Spigot", 0, List.of("velocity"), List.of(document));

        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> option = typedOption(controller, "Settings");
        assertTrue(option.hasDisabledValue());
        assertEquals("-1", option.getDisabledValue());
        assertEquals("10", option.get());

        SettingEntryWidget entry = entryWidget(controller, "Settings");
        assertInstanceOf(CompoundToggleSettingWidget.class, entry.mountedWidgets.getLast());

        CompoundToggleSettingWidget<String> compound = (CompoundToggleSettingWidget<String>) entry.mountedWidgets.getLast();
        assertTrue(compound.getToggleWidget().getValue());

        compound.getToggleWidget().setValue(false);
        compound.getToggleWidget().onChange.run();
        assertEquals("-1", option.get());

        compound.getToggleWidget().setValue(true);
        compound.getToggleWidget().onChange.run();
        assertEquals("10", option.get());

        compound.getToggleWidget().setValue(false);
        compound.getToggleWidget().onChange.run();

        option.apply();
        controller.save(instance).join();

        String updated = files.read(configPath).join();
        assertTrue(updated.contains("view-distance: -1"));
    }

    @Test
    void routesIntegerOrDisabledFieldToDisabledSentinelString() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path configPath = root.resolve("paper.yml");
        files.put(configPath, "despawn-range: disabled\n");
        Instance instance = velocity(root);

        ServerSettingsField field = new ServerSettingsField("despawn-range", "despawn-range", ServerSettingsFieldType.INTEGER_OR_DISABLED,
                "Settings", "Entities", "Despawn Range", "Despawn range in blocks", "disabled",
                new BigDecimal("0"), new BigDecimal("128"), List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("paper.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("paper", "Paper", "Paper", 0, List.of("velocity"), List.of(document));

        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> option = typedOption(controller, "Settings");
        assertTrue(option.hasDisabledValue());
        assertEquals("disabled", option.getDisabledValue());
        assertEquals("disabled", option.get());

        option.set("64");
        option.apply();
        controller.save(instance).join();

        String updated = files.read(configPath).join();
        assertTrue(updated.contains("despawn-range: 64"));

        option.set("disabled");
        option.apply();
        controller.save(instance).join();

        String reDisabled = files.read(configPath).join();
        assertTrue(reDisabled.contains("despawn-range: 'disabled'") || reDisabled.contains("despawn-range: disabled"));
    }

    @Test
    void routesListFieldToTypedCollectionEditor() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path documentPath = root.resolve("paper.yml");
        files.put(documentPath, "warn-on-overload: [warn, log]\n");
        Instance instance = velocity(root);
        ServerSettingsField.ValueSpec value = new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.SELECT,
                null, null, List.of("warn", "log", "restart"), null, false, List.of(), List.of(), null, null);
        ServerSettingsField.CollectionSchema collection = new ServerSettingsField.CollectionSchema(
                ServerSettingsField.CollectionMode.SEQUENCE, true, true, List.of(), null, value);
        ServerSettingsField field = new ServerSettingsField("warn-on-overload", "warn-on-overload", ServerSettingsFieldType.LIST,
                "Settings", "Watchdog", "Warn On Overload", "Actions on server overload", List.of("warn", "log"),
                null, null, List.of(), false, true, null, collection);
        ServerSettingsDocument document = new ServerSettingsDocument("paper.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("paper", "Paper", "Paper", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<List<String>> option = typedOption(controller, "Settings");
        assertInstanceOf(OptionEditor.Sequence.class, option.getOptionEditor());
        assertEquals(List.of("warn", "log"), option.get());
        SettingEntryWidget entry = entryWidget(controller, "Settings");
        assertInstanceOf(CollectionSettingWidget.class, entry);

        option.set(List.of("warn", "restart"));
        option.apply();
        controller.save(instance).join();
        assertTrue(files.read(documentPath).join().contains("restart"));
    }

    @Test
    void routesLevelTypeFieldToPresetsDropdown() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path propertiesPath = root.resolve("server.properties");
        files.put(propertiesPath, "level-type=minecraft\\:normal\n");
        Instance instance = velocity(root);
        instance.getServerProperties().setProperty("level-type", "minecraft:normal");

        ServerSettingsField field = new ServerSettingsField("level-type", "level-type", ServerSettingsFieldType.TEXT,
                "Server", "World", "Level Type", "World generation preset", "minecraft:normal", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("server", "Server", "Server", 0, List.of("velocity"), List.of(document));

        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("velocity");
            }
            @Override
            public String property(String key) {
                return instance.getServerProperties().getProperty(key);
            }
            @Override
            public void property(String key, String value) {
                instance.getServerProperties().setProperty(key, value);
            }
            @Override
            public void removeProperty(String key) {
                instance.getServerProperties().remove(key);
            }
            @Override
            public void replaceProperties(Map<String, String> properties) {
                instance.getServerProperties().clear();
                instance.getServerProperties().putAll(properties);
            }
        };

        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target, new ServerSettingsSnapshot(List.of(pack)), memoryStore(root, files));
        controller.load().join();

        ConfigOption<String> option = textOption(controller, "Server");
        assertNotNull(option.getOptions());
        assertTrue(option.getOptions().contains("minecraft:flat"));
        assertTrue(option.getOptions().contains("minecraft:large_biomes"));
        assertEquals("minecraft:normal", option.get());
        assertEquals("Large Biomes", option.getDisplayFunction().apply("minecraft:large_biomes"));

        SettingEntryWidget entry = entryWidget(controller, "Server");
        assertInstanceOf(DropDownWidget.class, entry.mountedWidgets.getLast());

        option.set("minecraft:flat");
        option.apply();
        controller.save(target).join();

        String updated = files.read(propertiesPath).join();
        assertTrue(updated.contains("level-type=minecraft\\:flat"));
    }

    @Test
    void singleBiomePresetUsesBiomeSelectorAndHidesRawGeneratorJson() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path propertiesPath = root.resolve("server.properties");
        files.put(propertiesPath, "generator-settings={\"biome\"\\:\"minecraft\\:desert\"}\n"
                + "level-type=minecraft\\:single_biome_surface\n");
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("generator-settings", "{\"biome\":\"minecraft:desert\"}");
        properties.put("level-type", "minecraft:single_biome_surface");
        ServerSettingsDocumentTarget target = new ServerSettingsDocumentTarget() {
            @Override
            public Collection<String> softwareTokens() {
                return List.of("paper");
            }

            @Override
            public String property(String key) {
                return properties.get(key);
            }

            @Override
            public void property(String key, String value) {
                properties.put(key, value);
            }

            @Override
            public void removeProperty(String key) {
                properties.remove(key);
            }

            @Override
            public void replaceProperties(Map<String, String> replacement) {
                properties.clear();
                properties.putAll(replacement);
            }
        };
        ServerSettingsField generator = new ServerSettingsField("generator-settings", "generator-settings", ServerSettingsFieldType.TEXT,
                "Server", "World Generation", "Generator Settings", "Generator JSON", "{}", null, null, List.of());
        ServerSettingsField levelType = new ServerSettingsField("level-type", "level-type", ServerSettingsFieldType.TEXT,
                "Server", "World Generation", "Level Type", "World preset", "minecraft:normal", null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("server.properties", ServerSettingsFormat.PROPERTIES,
                true, false, List.of(generator, levelType));
        ServerSettingsPack pack = new ServerSettingsPack("server", "Server", "Server", 0, List.of("paper"), List.of(document));
        ServerSettingsDataController controller = new ServerSettingsDocumentDataController(target,
                new ServerSettingsSnapshot(List.of(pack)), memoryStore(root, files));
        controller.load().join();

        ConfigOption<String> biome = namedOption(controller, "Server", "Biome");
        ConfigOption<String> raw = namedOption(controller, "Server", "Flat World Settings");
        ConfigOption<String> preset = namedOption(controller, "Server", "Level Type");
        assertEquals("minecraft:desert", biome.get());
        assertTrue(biome.isVisible());
        assertFalse(raw.isVisible());

        preset.set("minecraft:flat");
        assertFalse(biome.isVisible());
        assertTrue(raw.isVisible());

        preset.set("minecraft:single_biome_surface");
        biome.set("minecraft:jungle");
        biome.apply();
        assertEquals("{\"biome\":\"minecraft:jungle\"}", properties.get("generator-settings"));
    }

    @Test
    void routesStructureSeedFieldToSeedOptionWithoutSlider() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path configPath = root.resolve("spigot.yml");
        files.put(configPath, "world-settings:\n  default:\n    seed-village: 10387312\n");
        Instance instance = velocity(root);

        ServerSettingsField field = new ServerSettingsField("seed-village", "world-settings.default.seed-village", ServerSettingsFieldType.INTEGER,
                "Settings", "World", "Village Seed", "Seed for village generation", 10387312,
                new BigDecimal("-9223372036854775808"), new BigDecimal("9223372036854775807"), List.of());
        ServerSettingsDocument document = new ServerSettingsDocument("spigot.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("spigot", "Spigot", "Spigot", 0, List.of("velocity"), List.of(document));

        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<String> option = typedOption(controller, "Settings");
        assertTrue(option.isSeed());
        assertEquals("-9223372036854775808", option.getMin());
        assertEquals("9223372036854775807", option.getMax());
        assertEquals("10387312", option.get());

        SettingEntryWidget entry = entryWidget(controller, "Settings");
        assertInstanceOf(SeedSettingWidget.class, entry.mountedWidgets.getLast());

        option.set("9223372036854775807");
        option.apply();
        controller.save(instance).join();
        assertTrue(files.read(configPath).join().contains("seed-village: 9223372036854775807"));
    }

    @Test
    void routesMapFieldToTypedFixedCollection() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path configPath = root.resolve("bukkit.yml");
        files.put(configPath, "spawn-limits:\n  monsters: 70\n  animals: 10\n  water-ambient: -1\n");
        Instance instance = velocity(root);

        Map<String, Object> defaultMap = Map.of("monsters", 70, "animals", 10, "water-ambient", -1);
        ServerSettingsField.ValueSpec value = new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.INTEGER,
                BigDecimal.ZERO, null, List.of(), null, false,
                List.of(new ServerSettingsField.Sentinel(-1, "Use Bukkit Settings", ServerSettingsField.SentinelRole.INHERIT)),
                List.of(), null, null);
        ServerSettingsField.CollectionSchema collection = new ServerSettingsField.CollectionSchema(
                ServerSettingsField.CollectionMode.FIXED_MAP, true, true,
                List.of(new ServerSettingsField.FixedKey("monsters", "Monsters"),
                        new ServerSettingsField.FixedKey("animals", "Animals"),
                        new ServerSettingsField.FixedKey("water-ambient", "Water Ambient")), null, value);
        ServerSettingsField field = new ServerSettingsField("spawn-limits", "spawn-limits", ServerSettingsFieldType.MAP,
                "Settings", "Spawning", "Spawn Limits", "Mob category spawn limits", defaultMap, null, null, List.of(),
                false, true, null, collection);
        ServerSettingsDocument document = new ServerSettingsDocument("bukkit.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("bukkit", "Bukkit", "Bukkit", 0, List.of("velocity"), List.of(document));

        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<Map<String, Object>> option = typedOption(controller, "Settings");
        OptionEditor.MapEntries<?, ?> editor = assertInstanceOf(OptionEditor.MapEntries.class, option.getOptionEditor());
        assertEquals(OptionEditor.KeyMode.FIXED, editor.keys().mode());
        assertEquals(new BigInteger("70"), option.get().get("monsters"));
        assertEquals(new BigInteger("-1"), option.get().get("water-ambient"));
        OptionEditor.ScalarValue<?> scalar = assertInstanceOf(OptionEditor.ScalarValue.class, editor.value());
        assertEquals("Use Bukkit Settings", scalar.sentinels().getFirst().label());

        SettingEntryWidget entry = entryWidget(controller, "Settings");
        assertInstanceOf(CollectionSettingWidget.class, entry);

        LinkedHashMap<String, Object> changed = new LinkedHashMap<>(option.get());
        changed.put("monsters", new BigInteger("85"));
        option.set(changed);
        option.apply();
        controller.save(instance).join();

        String updated = files.read(configPath).join();
        assertTrue(updated.contains("monsters: 85") || updated.contains("monsters: '85'"));
    }

    @Test
    void inheritSentinelResolvesTheCurrentReferencedFieldPerMapEntry() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        files.put(root.resolve("bukkit.yml"), "spawn-limits:\n  ambient: 23\n");
        files.put(root.resolve("paper.yml"), "spawn-limits:\n  ambient: -1\n");
        Instance instance = velocity(root);

        ServerSettingsField bukkit = new ServerSettingsField("spawn-limits.ambient", "spawn-limits.ambient",
                ServerSettingsFieldType.INTEGER, "Settings", "Bukkit", "Ambient Bukkit Limit", "Bukkit limit", 15,
                BigDecimal.ZERO, null, List.of());
        ServerSettingsField.ValueReference reference = new ServerSettingsField.ValueReference("bukkit.yml", null,
                Map.of("ambient", "spawn-limits.ambient"));
        ServerSettingsField.ValueSpec value = new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.INTEGER,
                BigDecimal.ZERO, null, List.of(), null, false, List.of(new ServerSettingsField.Sentinel(-1,
                "Use Bukkit Settings", ServerSettingsField.SentinelRole.INHERIT, reference)), List.of(), null, null);
        ServerSettingsField.CollectionSchema collection = new ServerSettingsField.CollectionSchema(
                ServerSettingsField.CollectionMode.FIXED_MAP, true, true,
                List.of(new ServerSettingsField.FixedKey("ambient", "Ambient", "Ambient mob cap.")), null, value);
        ServerSettingsField paper = new ServerSettingsField("paper-spawn-limits", "spawn-limits", ServerSettingsFieldType.MAP,
                "Settings", "Paper", "Paper Spawn Limits", "Paper overrides", Map.of("ambient", -1), null, null,
                List.of(), false, true, null, collection);
        ServerSettingsPack pack = new ServerSettingsPack("settings", "Settings", "Settings", 0, List.of("velocity"), List.of(
                new ServerSettingsDocument("bukkit.yml", ServerSettingsFormat.YAML, true, false, List.of(bukkit)),
                new ServerSettingsDocument("paper.yml", ServerSettingsFormat.YAML, true, false, List.of(paper))));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance,
                new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<Map<String, Object>> paperOption = optionNamed(controller, "Settings", "Paper Spawn Limits");
        OptionEditor.MapEntries<?, ?> editor = assertInstanceOf(OptionEditor.MapEntries.class, paperOption.getOptionEditor());
        OptionEditor.ScalarValue<?> scalar = assertInstanceOf(OptionEditor.ScalarValue.class, editor.value());
        assertEquals(new BigInteger("23"), scalar.sentinels().getFirst().inheritedValueFor("ambient"));

        ConfigOption<String> bukkitOption = optionNamed(controller, "Settings", "Ambient Bukkit Limit");
        bukkitOption.set("31");

        assertEquals(new BigInteger("31"), scalar.sentinels().getFirst().inheritedValueFor("ambient"));
    }

    @Test
    void preservesNestedTypedCollectionsThroughApplyAndSave() {
        Path root = Path.of("settings-source-" + UUID.randomUUID()).toAbsolutePath();
        MemoryFiles files = new MemoryFiles();
        Path configPath = root.resolve("velocity.yml");
        files.put(configPath, "forced-hosts:\n  play.example.net: [lobby, survival]\n");
        Instance instance = velocity(root);

        ServerSettingsField.ValueSpec text = new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.TEXT,
                null, null, List.of(), null, false, List.of(), List.of(), null, null);
        ServerSettingsField.CollectionSchema servers = new ServerSettingsField.CollectionSchema(
                ServerSettingsField.CollectionMode.SEQUENCE, true, true, List.of(), null, text);
        ServerSettingsField.ValueSpec serverList = new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.LIST,
                null, null, List.of(), null, false, List.of(), List.of(), servers, null);
        ServerSettingsField.CollectionSchema hosts = new ServerSettingsField.CollectionSchema(
                ServerSettingsField.CollectionMode.DYNAMIC_MAP, true, true, List.of(), text, serverList);
        ServerSettingsField field = new ServerSettingsField("forced-hosts", "forced-hosts", ServerSettingsFieldType.MAP,
                "Settings", "Routing", "Forced Hosts", "Sets the ordered backend list for each host name.", Map.of(),
                null, null, List.of(), false, true, null, hosts);
        ServerSettingsDocument document = new ServerSettingsDocument("velocity.yml", ServerSettingsFormat.YAML, true, false, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("velocity", "Velocity", "Velocity", 0, List.of("velocity"), List.of(document));
        ServerSettingsDataController controller = new DesktopServerSettingsDataController(instance,
                new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
        controller.load().join();

        ConfigOption<Map<String, Object>> option = typedOption(controller, "Settings");
        assertEquals(List.of("lobby", "survival"), option.get().get("play.example.net"));
        LinkedHashMap<String, Object> changed = new LinkedHashMap<>(option.get());
        changed.put("play.example.net", List.of("lobby", "minigames"));
        option.set(changed);
        option.apply();
        controller.save(instance).join();

        String updated = files.read(configPath).join();
        assertTrue(updated.contains("minigames"));
        assertFalse(updated.contains("survival"));
    }

    private static ServerSettingsDocumentStore memoryStore(Path root, MemoryFiles files) {
        return new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                Path path = root.resolve(relativePath);
                return JvmAsyncBridge.fromFuture(files.exists(path))
                        .thenCompose(exists -> Boolean.TRUE.equals(exists)
                                ? JvmAsyncBridge.fromFuture(files.read(path)).thenApply(content -> new Document(true, content))
                                : Async.completed(Document.missing()));
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return JvmAsyncBridge.fromFuture(files.write(root.resolve(relativePath), content));
            }
        };
    }

    private static ServerSettingsDocumentStore worldStore(Path root, MemoryFiles files, List<String> worlds) {
        ServerSettingsDocumentStore filesStore = memoryStore(root, files);
        return new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                return filesStore.read(relativePath);
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return filesStore.write(relativePath, content);
            }

            @Override
            public Async<List<Entry>> list(String relativePath) {
                String path = relativePath == null || relativePath.isBlank() ? "." : relativePath;
                if (path.equals(".")) return Async.completed(worlds.stream().map(name -> new Entry(name, true)).toList());
                return Async.completed(worlds.contains(path) ? List.of(new Entry("level.dat", false)) : List.of());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigOption<T> typedOption(ServerSettingsDataController controller, String tab) {
        Setting setting = controller.settings(tab).getFirst();
        Widget widget = setting.getRows().getFirst().getWidgets().getFirst();
        return (ConfigOption<T>) ((SettingEntryWidget) widget).getOption();
    }

    private SettingEntryWidget entryWidget(ServerSettingsDataController controller, String tab) {
        Setting setting = controller.settings(tab).getFirst();
        Widget widget = setting.getRows().getFirst().getWidgets().getFirst();
        return (SettingEntryWidget) widget;
    }

    private ServerSettingsDataController controller(Instance instance, MemoryFiles files, ServerSettingsFormat format,
                                                    String path, String key, String defaultValue, boolean createIfMissing) {
        String tab = format == ServerSettingsFormat.TOML ? "Proxy" : "Server";
        ServerSettingsField field = new ServerSettingsField("managed", key, ServerSettingsFieldType.TEXT, tab,
                "Configuration", "Managed", "Managed value", defaultValue, null, null, List.of());
        ServerSettingsDocument document = new ServerSettingsDocument(path, format, true, createIfMissing, List.of(field));
        ServerSettingsPack pack = new ServerSettingsPack("settings", "Settings", "Settings", 0, List.of("velocity"), List.of(document));
        return new DesktopServerSettingsDataController(instance, new ServerSettingsSnapshot(List.of(pack)), new MemoryApi(files));
    }

    private ServerSettingsDocument document(String path, String tab) {
        ServerSettingsField field = new ServerSettingsField("managed", "managed", ServerSettingsFieldType.TEXT, tab,
                "Configuration", "Managed", "Managed value", "old", null, null, List.of());
        return new ServerSettingsDocument(path, ServerSettingsFormat.PROPERTIES, true, false, List.of(field));
    }

    private ConfigOption<String> textOption(ServerSettingsDataController controller, String tab) {
        Setting setting = controller.settings(tab).getFirst();
        Widget widget = setting.getRows().getFirst().getWidgets().getFirst();
        return (ConfigOption<String>) ((SettingEntryWidget) widget).getOption();
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigOption<T> optionNamed(ServerSettingsDataController controller, String tab, String name) {
        for (Setting setting : controller.settings(tab)) {
            for (var row : setting.getRows()) {
                for (Widget widget : row.getWidgets()) {
                    if (widget instanceof SettingEntryWidget entry && entry.getOption().getName().equals(name)) {
                        return (ConfigOption<T>) entry.getOption();
                    }
                }
            }
        }
        throw new IllegalArgumentException("Missing option " + name);
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigOption<T> namedOption(ServerSettingsDataController controller, String tab, String name) {
        return (ConfigOption<T>) controller.settings(tab).stream()
                .flatMap(setting -> setting.getRows().stream())
                .flatMap(row -> row.getWidgets().stream())
                .filter(SettingEntryWidget.class::isInstance)
                .map(SettingEntryWidget.class::cast)
                .map(SettingEntryWidget::getOption)
                .filter(option -> option.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private ConfigOption<Boolean> booleanOption(ServerSettingsDataController controller, String tab, int settingIndex) {
        Setting setting = controller.settings(tab).get(settingIndex);
        Widget widget = setting.getRows().getFirst().getWidgets().getFirst();
        return (ConfigOption<Boolean>) ((SettingEntryWidget) widget).getOption();
    }

    private Instance velocity(Path root) {
        Instance instance = new Instance("Velocity", "1.21.10", root.toString());
        instance.setServer(true);
        instance.setServerSoftwareType("velocity");
        return instance;
    }

    private static final class MemoryApi implements RebaseAPI {
        private final MemoryFiles files;

        private MemoryApi(MemoryFiles files) {
            this.files = files;
        }

        @Override
        public CompletableFuture<List<FileEntry>> listDirectory(Path path) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Void> copy(List<Path> sources, Path destination) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> move(List<Path> sources, Path destination) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> delete(List<Path> paths) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> rename(Path oldPath, Path newPath) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> createFile(Path path) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> createDirectory(Path path) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> upload(List<Path> localPaths, Path remotePath) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> download(List<Path> remotePaths, Path localPath) {
            return unsupported();
        }

        @Override
        public CompletableFuture<String> readFile(Path path) {
            return files.readValue(path);
        }

        @Override
        public CompletableFuture<Void> writeFile(Path path, String content) {
            return files.writeValue(path, content);
        }

        @Override
        public CompletableFuture<Boolean> fileExists(Path path) {
            return files.existsValue(path);
        }

        @Override
        public boolean canUndo() {
            return false;
        }

        @Override
        public CompletableFuture<Void> undo() {
            return unsupported();
        }

        @Override
        public Object createTtyConnector(Instance instance, int columns, int rows, java.util.function.Consumer<String> outputConsumer,
                                         java.util.function.Consumer<restudio.rebase.instance.InstanceState> stateConsumer) {
            return null;
        }

        @Override
        public CompletableFuture<String> launchServer(Instance instance) {
            return CompletableFuture.completedFuture("");
        }

        @Override
        public String getInitialDirectory(Instance instance) {
            return instance.getPath();
        }

        private CompletableFuture<Void> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    private static final class MemoryFiles implements FileSystemProvider {
        private final Map<Path, String> files = new LinkedHashMap<>();
        private int writes;
        private Path failedWrite;

        private synchronized void put(Path path, String content) {
            files.put(normalize(path), content);
        }

        private synchronized int writeCount() {
            return writes;
        }

        private synchronized void failNextWrite(Path path) {
            failedWrite = normalize(path);
        }

        private synchronized CompletableFuture<String> readValue(Path path) {
            String value = files.get(normalize(path));
            return value == null ? CompletableFuture.failedFuture(new IllegalStateException("Missing test file")) : CompletableFuture.completedFuture(value);
        }

        private synchronized CompletableFuture<Void> writeValue(Path path, String content) {
            Path normalized = normalize(path);
            if (normalized.equals(failedWrite)) {
                failedWrite = null;
                return CompletableFuture.failedFuture(new IllegalStateException("Test write failure"));
            }
            files.put(normalized, content);
            writes++;
            return CompletableFuture.completedFuture(null);
        }

        private synchronized CompletableFuture<Boolean> existsValue(Path path) {
            return CompletableFuture.completedFuture(files.containsKey(normalize(path)));
        }

        private Path normalize(Path path) {
            return path.toAbsolutePath().normalize();
        }

        @Override
        public CompletableFuture<List<FileEntry>> ls(Path path) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Void> copy(List<Path> sources, Path destination) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> move(List<Path> sources, Path destination) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> delete(List<Path> paths) {
            return unsupported();
        }

        @Override
        public CompletableFuture<String> read(Path path) {
            return readValue(path);
        }

        @Override
        public CompletableFuture<Void> write(Path path, String content) {
            return writeValue(path, content);
        }

        @Override
        public CompletableFuture<Void> writeAtomic(Path path, String content) {
            return writeValue(path, content);
        }

        @Override
        public CompletableFuture<Void> upload(List<Path> localPaths, Path remotePath) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> download(List<Path> remotePaths, Path localPath) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> rename(Path oldPath, Path newPath) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> createFile(Path path) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> createDirectory(Path path) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> exists(Path path) {
            return existsValue(path);
        }

        private CompletableFuture<Void> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    private static final class MemoryBackend implements ServerBackend {
        private final MemoryFiles files;

        private MemoryBackend(MemoryFiles files) {
            this.files = files;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public FileSystemProvider getFileSystem() {
            return files;
        }

        @Override
        public ExecutionProvider getExecution() {
            return null;
        }

        @Override
        public <T extends BackendFeature> Optional<T> getFeature(Class<T> featureClass) {
            return Optional.empty();
        }

        @Override
        public <T extends BackendFeature> void registerFeature(Class<T> featureClass, T implementation) {
        }
    }

}
