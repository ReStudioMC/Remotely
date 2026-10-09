package redxax.oxy.remotely.ui.settings.data;

import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.metadata.catalog.ResolvedCatalogRepository;
import redxax.oxy.remotely.metadata.catalog.ServerSettingsCatalogService;
import redxax.oxy.remotely.network.ConfigurationFormat;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapter;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;
import redxax.oxy.remotely.network.config.StructuredDocumentParser;
import redxax.oxy.remotely.settings.server.BrowserSafeYaml;
import redxax.oxy.remotely.settings.server.ServerSettingsDocument;
import redxax.oxy.remotely.settings.server.ServerSettingsField;
import redxax.oxy.remotely.settings.server.ServerSettingsFieldType;
import redxax.oxy.remotely.settings.server.ServerSettingsFormat;
import redxax.oxy.remotely.settings.server.ServerSettingsPack;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import restudio.rebase.minecraft.MinecraftBiomeCatalog;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingEntryWidget;
import restudio.rescreen.ui.settings.SettingWidgetFactory;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.settings.options.OptionEditor;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.util.UiTasks;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ServerSettingsDocumentDataController implements ServerSettingsDataController {
    public static final String SERVER_PROPERTIES_PATH = "server.properties";
    private static final String REMOVE_VALUE = "\u0000";

    private final ServerSettingsDocumentTarget source;
    private final ServerSettingsSnapshot snapshot;
    private final ServerSettingsDocumentStore store;
    private final boolean writeServerProperties;
    private final NetworkConfigurationAdapters adapters;
    private final StructuredDocumentParser structuredParser;
    private final ServerSettingsCatalogService.View hostedCatalogs;
    private final Consumer<Runnable> loadScheduler;
    private final Object stateLock = new Object();
    private final Async<Void> loadFuture;
    private final LinkedHashMap<String, DocumentState> documents = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<FieldBinding>> fieldsByTab = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<Setting>> settingsByTab = new LinkedHashMap<>();
    private final LinkedHashMap<FieldBinding, ConfigOption<?>> collectionOptions = new LinkedHashMap<>();
    private final LinkedHashMap<FieldBinding, ConfigOption<?>> fieldOptions = new LinkedHashMap<>();
    private final Map<FieldBinding, DropDownWidget<String>> worldSelectors = new LinkedHashMap<>();
    private final LinkedHashSet<String> documentPaths = new LinkedHashSet<>();
    private final LinkedHashSet<String> availableDocumentPaths = new LinkedHashSet<>();
    private final LinkedHashSet<String> unavailableDocumentPaths = new LinkedHashSet<>();
    private final LinkedHashSet<String> declaredTabs = new LinkedHashSet<>();
    private final LinkedHashSet<String> publishedTabs = new LinkedHashSet<>();
    private final LinkedHashSet<String> completedDocuments = new LinkedHashSet<>();
    private final IdentityHashMap<ResolvedCatalogRepository.Snapshot, OptionEditor.Catalog<Object>> catalogAdapters = new IdentityHashMap<>();
    private final OptionEditor.Catalog<String> bundledBiomes = bundledBiomeCatalog();
    private final List<Consumer<List<String>>> publicationListeners = new ArrayList<>();
    private List<DocumentDefinition> documentDefinitions = List.of();
    private List<Async<Void>> documentLoads = List.of();
    private List<String> worldCatalog = List.of();
    private Async<List<String>> worldDiscovery;
    private long loadGeneration = 1;
    private boolean closed;
    private boolean worldDiscoveryStarted;

    public ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                 ServerSettingsDocumentStore store) {
        this(source, snapshot, store, BrowserSafeYaml::parse);
    }

    public ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                 ServerSettingsDocumentStore store, StructuredDocumentParser structuredParser) {
        this(source, snapshot, store, true, structuredParser);
    }

    protected ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                    ServerSettingsDocumentStore store, boolean writeServerProperties,
                                                    StructuredDocumentParser structuredParser) {
        this(source, snapshot, store, writeServerProperties, structuredParser, null);
    }

    public ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                ServerSettingsDocumentStore store, StructuredDocumentParser structuredParser,
                                                ServerSettingsCatalogService.View hostedCatalogs) {
        this(source, snapshot, store, true, structuredParser, hostedCatalogs);
    }

    protected ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                    ServerSettingsDocumentStore store, boolean writeServerProperties,
                                                    StructuredDocumentParser structuredParser,
                                                    ServerSettingsCatalogService.View hostedCatalogs) {
        this(source, snapshot, store, writeServerProperties, structuredParser, hostedCatalogs, null);
    }

    public ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                ServerSettingsDocumentStore store, StructuredDocumentParser structuredParser,
                                                ServerSettingsCatalogService.View hostedCatalogs, Consumer<Runnable> loadScheduler) {
        this(source, snapshot, store, true, structuredParser, hostedCatalogs, loadScheduler);
    }

    protected ServerSettingsDocumentDataController(ServerSettingsDocumentTarget source, ServerSettingsSnapshot snapshot,
                                                 ServerSettingsDocumentStore store, boolean writeServerProperties,
                                                 StructuredDocumentParser structuredParser,
                                                 ServerSettingsCatalogService.View hostedCatalogs,
                                                 Consumer<Runnable> loadScheduler) {
        this.source = Objects.requireNonNull(source, "source");
        this.snapshot = snapshot == null ? new ServerSettingsSnapshot(List.of()) : snapshot;
        this.store = Objects.requireNonNull(store, "store");
        this.writeServerProperties = writeServerProperties;
        this.structuredParser = Objects.requireNonNull(structuredParser, "structuredParser");
        this.hostedCatalogs = hostedCatalogs;
        this.loadScheduler = loadScheduler;
        adapters = new NetworkConfigurationAdapters(structuredParser);
        loadFuture = loadDocuments();
    }

    public Async<Void> load() {
        return loadFuture;
    }

    public Async<Void> ready() {
        return loadFuture;
    }

    public List<String> tabNames() {
        synchronized (stateLock) {
            return List.copyOf(publishedTabs);
        }
    }

    @Override
    public List<String> plannedTabNames() {
        synchronized (stateLock) {
            return List.copyOf(declaredTabs);
        }
    }

    @Override
    public Runnable onTabsPublished(Consumer<List<String>> listener) {
        Objects.requireNonNull(listener, "listener");
        List<String> current;
        synchronized (stateLock) {
            if (closed) return () -> {};
            publicationListeners.add(listener);
            current = List.copyOf(publishedTabs);
        }
        if (!current.isEmpty()) {
            try {
                listener.accept(current);
            } catch (RuntimeException ignored) {
            }
        }
        return () -> {
            synchronized (stateLock) {
                publicationListeners.remove(listener);
            }
        };
    }

    public List<String> getTabNames() {
        return tabNames();
    }

    public List<Setting> settings(String tab) {
        boolean discover;
        long generation;
        synchronized (stateLock) {
            List<FieldBinding> fields = fieldsByTab.get(tab);
            discover = !worldDiscoveryStarted && fields != null && fields.stream().anyMatch(binding -> isWorldField(binding.field));
            if (discover) worldDiscoveryStarted = true;
            generation = loadGeneration;
        }
        if (discover) discoverWorlds(generation);
        synchronized (stateLock) {
            List<FieldBinding> fields = fieldsByTab.get(tab);
            return closed || fields == null ? List.of() : settingsByTab.computeIfAbsent(tab, ignored -> buildSettings(fields));
        }
    }

    public List<Setting> getSettings(String tab) {
        return settings(tab);
    }

    public List<Setting> settingsForTab(String tab) {
        return settings(tab);
    }

    public Map<String, List<Setting>> settingsByTab() {
        synchronized (stateLock) {
            fieldsByTab.forEach((tab, fields) -> settingsByTab.computeIfAbsent(tab, ignored -> buildSettings(fields)));
            return Collections.unmodifiableMap(new LinkedHashMap<>(settingsByTab));
        }
    }

    public List<String> documentPaths() {
        synchronized (stateLock) {
            return List.copyOf(documentPaths);
        }
    }

    public List<String> availableDocumentPaths() {
        synchronized (stateLock) {
            return List.copyOf(availableDocumentPaths);
        }
    }

    public List<String> unavailableDocumentPaths() {
        synchronized (stateLock) {
            return List.copyOf(unavailableDocumentPaths);
        }
    }

    public List<String> getAvailableDocumentPaths() {
        return availableDocumentPaths();
    }

    public boolean isDocumentAvailable(String relativePath) {
        synchronized (stateLock) {
            return availableDocumentPaths.contains(relativePath);
        }
    }

    public Map<String, String> changedFileContents() {
        synchronized (stateLock) {
            LinkedHashMap<String, String> changed = new LinkedHashMap<>();
            for (DocumentState document : documents.values()) {
                synchronizeChangedValues(document);
                if (!document.changedValues.isEmpty()) {
                    changed.put(document.definition.relativePath(), applyMutations(document, document.baselineContent, document.changedValues));
                }
            }
            return Collections.unmodifiableMap(changed);
        }
    }

    public Map<String, String> getChangedFileContents() {
        return changedFileContents();
    }

    public Async<Void> save(Object target) {
        Objects.requireNonNull(target, "target");
        return loadFuture.thenCompose(ignored -> saveTo(store, true));
    }

    @Override
    public void close() {
        Async<List<String>> discovery;
        synchronized (stateLock) {
            closed = true;
            loadGeneration++;
            discovery = worldDiscovery;
            worldDiscovery = null;
            documents.clear();
            fieldsByTab.clear();
            settingsByTab.clear();
            worldSelectors.clear();
            fieldOptions.clear();
            collectionOptions.clear();
            declaredTabs.clear();
            publishedTabs.clear();
            completedDocuments.clear();
            publicationListeners.clear();
            documentPaths.clear();
            availableDocumentPaths.clear();
            unavailableDocumentPaths.clear();
            catalogAdapters.clear();
            documentLoads.forEach(Async::cancel);
            documentLoads = List.of();
        }
        if (discovery != null) discovery.cancel();
        if (hostedCatalogs != null) hostedCatalogs.close();
    }

    private Async<Void> loadDocuments() {
        long generation;
        synchronized (stateLock) {
            generation = loadGeneration;
        }
        List<DocumentDefinition> definitions = definitions();
        synchronized (stateLock) {
            documentDefinitions = definitions;
            worldCatalog = initialWorlds();
            definitions.forEach(definition -> documentPaths.add(definition.relativePath()));
            definitions.stream().flatMap(definition -> definition.fields().stream()).map(ServerSettingsField::tab).forEach(declaredTabs::add);
        }
        List<Async<Void>> loads = definitions.stream()
                .map(definition -> loadDocument(definition).thenCompose(loaded -> prepareLoadedDocument(generation, loaded)))
                .toList();
        synchronized (stateLock) {
            if (closed || generation != loadGeneration) {
                loads.forEach(Async::cancel);
                return Async.completed(null);
            }
            documentLoads = loads;
        }
        if (loads.isEmpty()) {
            return Async.completed(null);
        }
        return Async.allOf(loads.toArray(Async[]::new));
    }

    private Async<Void> onUi(Runnable action) {
        Async<Void> result = Async.pending();
        Runnable task = () -> {
            try {
                action.run();
                result.complete(null);
            } catch (Throwable error) {
                result.fail(error);
            }
        };
        try {
            if (UiTasks.isUiThread()) task.run();
            else ScreenManager.getInstance().execute(task);
        } catch (Throwable error) {
            result.fail(error);
        }
        return result;
    }

    private Async<Void> prepareLoadedDocument(long generation, LoadedDocument loaded) {
        if (loadScheduler == null) return onUi(() -> acceptLoadedDocument(generation, loaded, null));
        Async<Void> result = Async.pending();
        scheduleDocumentStep(generation, loaded, null, 0, result);
        return result;
    }

    private void scheduleDocumentStep(long generation, LoadedDocument loaded, DocumentState document, int start, Async<Void> result) {
        try {
            loadScheduler.accept(() -> {
                if (result.isDone()) return;
                synchronized (stateLock) {
                    if (closed || generation != loadGeneration) {
                        result.cancel();
                        return;
                    }
                }
                try {
                    DocumentState prepared = document;
                    if (loaded.available()) {
                        if (prepared == null) prepared = new DocumentState(loaded.definition(), loaded.content(), loaded.exists(),
                                adapters.get(adapterFormat(loaded.definition().format())));
                        List<ServerSettingsField> fields = prepared.definition.fields();
                        int end = Math.min(start + 4, fields.size());
                        for (int index = start; index < end; index++) initializeField(prepared, fields.get(index));
                        if (end < fields.size()) {
                            scheduleDocumentStep(generation, loaded, prepared, end, result);
                            return;
                        }
                    }
                    acceptLoadedDocument(generation, loaded, prepared);
                    result.complete(null);
                } catch (Throwable error) {
                    result.fail(error);
                }
            });
        } catch (Throwable error) {
            result.fail(error);
        }
    }

    private void acceptLoadedDocument(long generation, LoadedDocument loaded, DocumentState prepared) {
        List<String> published;
        List<Consumer<List<String>>> listeners;
        synchronized (stateLock) {
            if (closed || generation != loadGeneration) return;
            DocumentDefinition definition = loaded.definition();
            if (loaded.available()) {
                DocumentState state = prepared == null
                        ? new DocumentState(definition, loaded.content(), loaded.exists(), adapters.get(adapterFormat(definition.format())))
                        : prepared;
                if (prepared == null) initializeDocument(state);
                else if (state.exists && isServerProperties(definition.relativePath())) updateServerProperties(state.baselineContent);
                documents.put(definition.relativePath(), state);
                availableDocumentPaths.add(definition.relativePath());
            } else if (definition.required() && !definition.createIfMissing()) {
                unavailableDocumentPaths.add(definition.relativePath());
            }
            completedDocuments.add(definition.relativePath());
            published = publishReadyTabs();
            listeners = published.isEmpty() ? List.of() : List.copyOf(publicationListeners);
        }
        if (!published.isEmpty()) {
            for (Consumer<List<String>> listener : listeners) {
                try {
                    listener.accept(published);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private List<String> publishReadyTabs() {
        List<String> ready = new ArrayList<>();
        for (String tab : declaredTabs) {
            if (publishedTabs.contains(tab) || !tabReady(tab)) continue;
            List<FieldBinding> fields = new ArrayList<>();
            for (DocumentDefinition definition : documentDefinitions) {
                DocumentState document = documents.get(definition.relativePath());
                if (document == null) continue;
                document.bindings.stream().filter(binding -> binding.field.tab().equals(tab)).forEach(fields::add);
            }
            fieldsByTab.put(tab, List.copyOf(fields));
            publishedTabs.add(tab);
            ready.add(tab);
        }
        return List.copyOf(ready);
    }

    private boolean tabReady(String tab) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (DocumentDefinition definition : documentDefinitions) {
            for (ServerSettingsField field : definition.fields()) {
                if (!field.tab().equals(tab)) continue;
                required.add(definition.relativePath());
                collectRequiredDocuments(field.collection(), required);
            }
        }
        return completedDocuments.containsAll(required);
    }

    private void collectRequiredDocuments(ServerSettingsField.CollectionSchema schema, LinkedHashSet<String> required) {
        if (schema == null) return;
        collectRequiredDocuments(schema.key(), required);
        collectRequiredDocuments(schema.value(), required);
    }

    private void collectRequiredDocuments(ServerSettingsField.ValueSpec spec, LinkedHashSet<String> required) {
        if (spec == null) return;
        if (spec.catalog() != null && spec.catalog().field() != null) addFieldOwner(spec.catalog().field(), required);
        if (spec.fieldsFrom() != null) addFieldOwner(spec.fieldsFrom(), required);
        spec.fields().forEach(field -> collectRequiredDocuments(field.value(), required));
        collectRequiredDocuments(spec.collection(), required);
    }

    private void addFieldOwner(String reference, LinkedHashSet<String> required) {
        for (DocumentDefinition definition : documentDefinitions) {
            if (definition.fields().stream().anyMatch(field -> field.id().equals(reference) || field.key().equals(reference))) {
                required.add(definition.relativePath());
            }
        }
    }

    private void publishWorlds(long generation, List<String> worlds) {
        synchronized (stateLock) {
            if (closed || generation != loadGeneration) return;
            List<String> updated = worlds == null ? List.of() : List.copyOf(worlds);
            if (worldCatalog.equals(updated)) return;
            worldCatalog = updated;
            refreshWorldSettings();
        }
    }

    private List<String> initialWorlds() {
        String current = source.property("level-name");
        return List.of(current == null || current.isBlank() ? "world" : current.trim());
    }

    private void discoverWorlds(long generation) {
        Async<List<String>> discovery = discoverWorlds();
        synchronized (stateLock) {
            if (closed || generation != loadGeneration) {
                discovery.cancel();
                return;
            }
            worldDiscovery = discovery;
        }
        discovery.thenCompose(worlds -> onUi(() -> publishWorlds(generation, worlds))).exceptionally(error -> null);
        discovery.whenComplete((worlds, error) -> {
            synchronized (stateLock) {
                if (worldDiscovery == discovery) worldDiscovery = null;
            }
        });
    }

    private Async<List<String>> discoverWorlds() {
        String container;
        synchronized (stateLock) {
            container = worldContainer();
        }
        LinkedHashSet<String> initial = new LinkedHashSet<>(initialWorlds());
        return safeList(container).thenCompose(entries -> {
            Async<LinkedHashSet<String>> discovery = Async.completed(initial);
            List<ServerSettingsDocumentStore.Entry> directories = entries.stream()
                    .filter(entry -> entry.directory() && !entry.name().isBlank()).toList();
            for (int offset = 0; offset < directories.size(); offset += 4) {
                List<ServerSettingsDocumentStore.Entry> batch = directories.subList(offset, Math.min(offset + 4, directories.size()));
                discovery = discovery.thenCompose(worlds -> {
                    List<Async<String>> checks = batch.stream().map(entry -> safeList(joinPath(container, entry.name())).thenApply(children ->
                            children.stream().anyMatch(child -> !child.directory() && child.name().equalsIgnoreCase("level.dat")) ? entry.name() : "")).toList();
                    return Async.allOf(checks.toArray(Async[]::new)).thenApply(ignored -> {
                        checks.stream().map(Async::join).filter(name -> !name.isBlank()).forEach(worlds::add);
                        return worlds;
                    });
                });
            }
            return discovery.thenApply(worlds -> worlds.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        });
    }

    private Async<List<ServerSettingsDocumentStore.Entry>> safeList(String path) {
        try {
            Async<List<ServerSettingsDocumentStore.Entry>> listed = store.list(path);
            if (listed == null) return Async.completed(List.of());
            return listed.handle((entries, error) -> error == null && entries != null ? entries : List.of());
        } catch (RuntimeException ignored) {
            return Async.completed(List.of());
        }
    }

    private String worldContainer() {
        DocumentState bukkit = documents.get("bukkit.yml");
        if (bukkit == null || bukkit.baselineContent.isBlank()) return ".";
        try {
            Object root = structuredParser.parse(bukkit.baselineContent);
            if (root instanceof Map<?, ?> map && map.get("settings") instanceof Map<?, ?> settings) {
                Object value = settings.get("world-container");
                return safeRelativePath(value == null ? "." : value.toString());
            }
        } catch (RuntimeException ignored) {
        }
        return ".";
    }

    private String safeRelativePath(String value) {
        String normalized = value == null ? "." : value.trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.equals(".")) return ".";
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) return ".";
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) return ".";
        }
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized.isBlank() ? "." : normalized;
    }

    private String joinPath(String parent, String child) {
        return parent == null || parent.isBlank() || parent.equals(".") ? child : parent + "/" + child;
    }

    private Async<LoadedDocument> loadDocument(DocumentDefinition definition) {
        Async<ServerSettingsDocumentStore.Document> readFuture;
        try {
            readFuture = store.read(definition.relativePath());
        } catch (RuntimeException exception) {
            return Async.completed(new LoadedDocument(definition, false, false, ""));
        }
        if (readFuture == null) {
            return Async.completed(new LoadedDocument(definition, false, false, ""));
        }
        return readFuture.handle((document, error) -> {
            if (error != null || document == null) {
                return new LoadedDocument(definition, false, false, "");
            }
            if (!document.exists()) {
                return new LoadedDocument(definition, definition.createIfMissing(), false, "");
            }
            return new LoadedDocument(definition, true, true, document.content());
        });
    }

    private List<DocumentDefinition> definitions() {
        LinkedHashMap<String, DocumentDefinition> definitions = new LinkedHashMap<>();
        for (ServerSettingsPack pack : snapshot.packs()) {
            if (pack == null || !pack.appliesTo(source.softwareTokens())) {
                continue;
            }
            for (ServerSettingsDocument document : pack.documents()) {
                if (document == null) {
                    continue;
                }
                DocumentDefinition definition = new DocumentDefinition(document.relativePath(), document.format(), document.required(),
                        document.createIfMissing(), document.fields());
                DocumentDefinition existing = definitions.get(definition.relativePath());
                if (existing == null) {
                    definitions.put(definition.relativePath(), definition);
                } else {
                    definitions.put(definition.relativePath(), existing.merge(definition));
                }
            }
        }
        return List.copyOf(definitions.values());
    }

    private void initializeDocument(DocumentState document) {
        if (document.exists && isServerProperties(document.definition.relativePath())) {
            updateServerProperties(document.baselineContent);
        }
        for (ServerSettingsField field : document.definition.fields()) initializeField(document, field);
    }

    private void initializeField(DocumentState document, ServerSettingsField field) {
        boolean present = document.baselineReader.contains(field.key());
        String rawValue = document.baselineReader.read(field.key());
        Object initial = parseValue(document, field, present ? rawValue : null);
        FieldBinding binding = new FieldBinding(document, field, initial, defaultValue(field), present, rawValue);
        document.bindings.add(binding);
        document.baselines.putIfAbsent(field.key(), new BaselineValue(present, rawValue));
    }

    private void refreshWorldSettings() {
        worldSelectors.forEach((binding, selector) -> {
            ConfigOption<?> option = fieldOptions.get(binding);
            String value = String.valueOf(option.get());
            List<String> choices = new ArrayList<>(worldCatalog);
            if (!choices.contains(value)) choices.addFirst(value);
            selector.setItems(choices, value);
        });
    }

    private List<Setting> buildSettings(List<FieldBinding> bindings) {
        LinkedHashMap<String, Setting.Builder> grouped = new LinkedHashMap<>();
        for (FieldBinding binding : bindings) addSetting(grouped, binding);
        return grouped.values().stream().map(Setting.Builder::build).toList();
    }

    private void addSetting(Map<String, Setting.Builder> grouped, FieldBinding binding) {
        Setting.Builder builder = grouped.computeIfAbsent(binding.field.group(), Setting.Builder::new);
        if (isGeneratorSettingsField(binding.field)) {
            builder.addOption(singleBiomeGeneratorOption(binding));
            builder.addOption(rawGeneratorOption(binding));
        } else {
            ConfigOption<?> option = option(binding);
            fieldOptions.put(binding, option);
            if (isWorldField(binding.field)) {
                addWorldSetting(builder, binding, option);
            } else {
                builder.addOption(option);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addWorldSetting(Setting.Builder builder, FieldBinding binding, ConfigOption<?> option) {
        ConfigOption<String> world = (ConfigOption<String>) option;
        SettingEntryWidget entry = SettingWidgetFactory.createWidget(world);
        List<String> choices = new ArrayList<>(worldCatalog);
        if (!choices.contains(world.get())) choices.addFirst(world.get());
        DropDownWidget<String> selector = new DropDownWidget.Builder<>(choices)
                .selectedItem(world.get()).onSelectionChanged(world::set).size(140, 20).build();
        world.addChangeListener(value -> {
            List<String> available = new ArrayList<>(worldCatalog);
            if (!available.contains(value)) available.addFirst(value);
            selector.setItems(available, value);
        });
        worldSelectors.put(binding, selector);
        entry.addMountedWidget(selector);
        entry.setHeight(30);
        builder.addRow("", entry);
    }

    private boolean isGeneratorSettingsField(ServerSettingsField field) {
        String key = field.key().toLowerCase(Locale.ROOT);
        String id = field.id().toLowerCase(Locale.ROOT);
        return key.equals("generator-settings") || id.equals("generator-settings") || key.endsWith(".generator-settings");
    }

    private ConfigOption<String> singleBiomeGeneratorOption(FieldBinding binding) {
        String current = generatorBiome(readValue(binding));
        return ConfigOption.<String>builder("Biome")
                .description("Chooses the biome used by the Single Biome Surface world preset.")
                .bind(() -> generatorBiome(readValue(binding)), value -> writeValue(binding, "{\"biome\":\"" + value + "\"}"))
                .defaultValue(generatorBiome(binding.defaultValue))
                .editor(biomeEditor(current, "current:" + binding.document.definition.relativePath() + ":generator-biome"))
                .visibleWhen(() -> levelTypeValue().equals("minecraft:single_biome_surface"))
                .build();
    }

    private ConfigOption<String> rawGeneratorOption(FieldBinding binding) {
        return ConfigOption.<String>builder("Flat World Settings")
                .description("Sets the generator JSON used by flat or custom world presets.")
                .bind(() -> String.valueOf(readValue(binding)), value -> writeValue(binding, value))
                .defaultValue(String.valueOf(binding.defaultValue))
                .visibleWhen(this::rawGeneratorVisible)
                .build();
    }

    private String generatorBiome(Object raw) {
        if (raw != null) {
            try {
                Object parsed = structuredParser.parse(raw.toString());
                if (parsed instanceof Map<?, ?> map && map.get("biome") != null) {
                    String biome = String.valueOf(map.get("biome")).trim();
                    if (!biome.isBlank()) return biome;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return "minecraft:plains";
    }

    private boolean rawGeneratorVisible() {
        String levelType = levelTypeValue();
        return levelType.equals("minecraft:flat") || !levelType.equals("minecraft:normal")
                && !levelType.equals("minecraft:large_biomes") && !levelType.equals("minecraft:amplified")
                && !levelType.equals("minecraft:single_biome_surface");
    }

    private String levelTypeValue() {
        for (DocumentState document : documents.values()) {
            for (FieldBinding binding : document.bindings) {
                if (!isLevelTypeField(binding.field)) continue;
                ConfigOption<?> option = fieldOptions.get(binding);
                Object value = option == null ? readValue(binding) : option.get();
                return value == null ? "minecraft:normal" : value.toString().toLowerCase(Locale.ROOT);
            }
        }
        return "minecraft:normal";
    }

    private ConfigOption<?> option(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        if (isWorldField(field)) {
            return worldOption(binding);
        }
        if (isBiomeField(field)) {
            return biomeOption(binding);
        }
        if (isLevelTypeField(field)) {
            return levelTypeOption(binding);
        }
        return switch (field.type()) {
            case BOOLEAN -> booleanOption(binding);
            case INTEGER, INTEGER_OR_DISABLED, INTEGER_OR_DEFAULT -> numberOption(binding, ConfigOption.Editor.INTEGER);
            case DECIMAL, DECIMAL_OR_DISABLED, DECIMAL_OR_DEFAULT -> numberOption(binding, ConfigOption.Editor.DECIMAL);
            case LIST -> listOption(binding);
            case MAP -> mapOption(binding);
            case BOOLEAN_OR_DEFAULT, BOOLEAN_OR_DISABLED -> booleanUnionOption(binding);
            case TEXT, SELECT, DURATION, DURATION_OR_DISABLED -> textOption(binding);
        };
    }

    private boolean isWorldField(ServerSettingsField field) {
        String key = field.key().toLowerCase(Locale.ROOT);
        String id = field.id().toLowerCase(Locale.ROOT);
        return key.equals("level-name") || key.endsWith(".level-name") || id.equals("level-name") || id.endsWith(".level-name")
                || key.equals("world-name") || id.equals("world-name");
    }

    private ConfigOption<String> worldOption(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        return ConfigOption.<String>builder(field.name())
                .description(field.description())
                .bind(() -> (String) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((String) binding.defaultValue)
                .build();
    }

    private boolean isBiomeField(ServerSettingsField field) {
        if (field.type() != ServerSettingsFieldType.TEXT && field.type() != ServerSettingsFieldType.SELECT) {
            return false;
        }
        String key = field.key().toLowerCase(Locale.ROOT);
        String id = field.id().toLowerCase(Locale.ROOT);
        return (key.endsWith("biome") || id.endsWith("biome") || key.contains("biome-") || id.contains("biome-"))
                && !key.contains("minimum") && !key.contains("maximum") && !key.contains("height") && !key.contains("provider");
    }

    private ConfigOption<String> biomeOption(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        String current = binding.value == null ? null : binding.value.toString();
        return ConfigOption.<String>builder(field.name())
                .description(field.description())
                .bind(() -> (String) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((String) binding.defaultValue)
                .editor(biomeEditor(current, "current:" + binding.document.definition.relativePath() + ":" + field.id()))
                .build();
    }

    private OptionEditor.Scalar<String> biomeEditor(String current, String identity) {
        CatalogCurrent configured = CatalogCurrent.of(identity,
            current == null || current.isBlank() ? List.of() : List.of(current));
        OptionEditor.ScalarValue<String> value = new OptionEditor.ScalarValue<>(OptionEditor.ScalarKind.SELECT,
            current == null || current.isBlank() ? "minecraft:plains" : current, text -> text, text -> text,
            () -> biomeCatalog(configured), List.of());
        return new OptionEditor.Scalar<>(value);
    }

    @SuppressWarnings("unchecked")
    private OptionEditor.Catalog<String> biomeCatalog(CatalogCurrent current) {
        OptionEditor.Catalog<Object> hosted = sourceCatalog("server:minecraft:biome", current);
        boolean populated = hosted.choices().stream().anyMatch(OptionEditor.Choice::selectable);
        if (populated) return (OptionEditor.Catalog<String>) (OptionEditor.Catalog<?>) hosted;
        return bundledBiomes;
    }

    private static OptionEditor.Catalog<String> bundledBiomeCatalog() {
        List<OptionEditor.Choice<String>> choices = MinecraftBiomeCatalog.biomeIds().stream()
            .map(id -> new OptionEditor.Choice<>(id, MinecraftBiomeCatalog.displayName(id),
                "Vanilla biome bundled with Remotely."))
            .toList();
        return new OptionEditor.Catalog<>("bundled-biomes", choices);
    }

    private ConfigOption<?> listOption(FieldBinding binding) {
        return collectionOption(binding, false);
    }

    private boolean isLevelTypeField(ServerSettingsField field) {
        String key = field.key().toLowerCase(Locale.ROOT);
        String id = field.id().toLowerCase(Locale.ROOT);
        return key.equals("level-type") || id.equals("level-type") || key.endsWith(".level-type");
    }

    private ConfigOption<String> levelTypeOption(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        List<String> presets = new ArrayList<>(List.of(
                "minecraft:normal",
                "minecraft:flat",
                "minecraft:large_biomes",
                "minecraft:amplified",
                "minecraft:single_biome_surface"
        ));
        String current = binding.value == null ? null : binding.value.toString();
        if (current != null && !current.isBlank() && !presets.contains(current)) {
            presets.add(current);
        }
        return ConfigOption.<String>builder(field.name())
                .description(field.description())
                .bind(() -> (String) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((String) binding.defaultValue)
                .options(presets)
                .display(SettingWidgetFactory::formatFriendlyDisplayName)
                .build();
    }

    private boolean isSeedField(ServerSettingsField field) {
        String key = field.key().toLowerCase(Locale.ROOT);
        String id = field.id().toLowerCase(Locale.ROOT);
        String name = field.name().toLowerCase(Locale.ROOT);
        return key.contains("seed") || id.contains("seed") || name.contains("seed");
    }

    private ConfigOption<?> mapOption(FieldBinding binding) {
        return collectionOption(binding, true);
    }

    @SuppressWarnings("unchecked")
    private ConfigOption<?> collectionOption(FieldBinding binding, boolean map) {
        ServerSettingsField field = binding.field;
        ServerSettingsField.CollectionSchema schema = field.collection();
        if (schema == null) {
            return textOption(binding);
        }
        CatalogCurrent keys = catalogCurrent(binding, schema, true);
        CatalogCurrent values = catalogCurrent(binding, schema, false);
        OptionEditor<Object> editor = (OptionEditor<Object>) collectionEditor(schema, field.name(), keys, values);
        Object fallback = normalizeCollection(schema, structuredValue(binding.defaultValue, map), map);
        ConfigOption<Object> option = ConfigOption.builder(field.name())
                .description(field.description())
                .bind(() -> normalizeCollection(schema, structuredValue(readValue(binding), map), map),
                        value -> writeValue(binding, formatFlow(value)))
                .defaultValue(fallback)
                .editor(editor)
                .build();
        collectionOptions.put(binding, option);
        return option;
    }

    private OptionEditor<?> collectionEditor(ServerSettingsField.CollectionSchema schema) {
        return collectionEditor(schema, null, CatalogCurrent.empty(), CatalogCurrent.empty());
    }

    private OptionEditor<?> collectionEditor(ServerSettingsField.CollectionSchema schema, String settingName) {
        return collectionEditor(schema, settingName, CatalogCurrent.empty(), CatalogCurrent.empty());
    }

    private OptionEditor<?> collectionEditor(ServerSettingsField.CollectionSchema schema, String settingName,
                                              CatalogCurrent keysCurrent, CatalogCurrent valuesCurrent) {
        OptionEditor.Value<Object> value = valueEditor(schema.value(), valuesCurrent);
        OptionEditor.CollectionPresentation presentation = collectionPresentation(schema.presentation(), settingName);
        if (schema.mode() == ServerSettingsField.CollectionMode.SEQUENCE) {
            boolean custom = schema.value().catalog() == null || schema.value().allowCustom();
            return new OptionEditor.Sequence<>(value, schema.ordered(), schema.unique(), custom, presentation);
        }
        OptionEditor.KeyPolicy<Object> keys;
        if (schema.mode() == ServerSettingsField.CollectionMode.FIXED_MAP) {
            List<OptionEditor.Choice<Object>> choices = schema.keys().stream()
                    .map(key -> new OptionEditor.Choice<>((Object) key.value(), key.label(), key.description()))
                    .toList();
            keys = OptionEditor.KeyPolicy.fixed(choices);
        } else {
            OptionEditor.ScalarValue<Object> key = scalarEditor(schema.key(), keysCurrent);
            if (schema.key().catalog() == null) {
                keys = OptionEditor.KeyPolicy.custom(key);
            } else {
                keys = OptionEditor.KeyPolicy.catalog(key.catalog(), schema.key().allowCustom() ? key : null);
            }
        }
        return new OptionEditor.MapEntries<>(keys, value, presentation);
    }

    private OptionEditor.CollectionPresentation collectionPresentation(ServerSettingsField.CollectionPresentation source,
                                                                       String settingName) {
        String items = source != null && source.itemsName() != null ? source.itemsName()
                : settingName == null || settingName.isBlank() ? "Entries" : settingName;
        String item = source != null && source.itemName() != null ? source.itemName() : singular(items);
        return new OptionEditor.CollectionPresentation(
                item,
                items,
                source == null ? null : source.addLabel(),
                source == null ? null : source.emptyLabel(),
                source == null ? null : source.emptyDescription(),
                source == null ? null : source.identityField(),
                source == null ? null : source.detailsLabel());
    }

    private String singular(String value) {
        if (value.endsWith("ies") && value.length() > 3) return value.substring(0, value.length() - 3) + "y";
        if (value.endsWith("s") && !value.endsWith("ss") && value.length() > 1) return value.substring(0, value.length() - 1);
        return value;
    }

    @SuppressWarnings("unchecked")
    private OptionEditor.Value<Object> valueEditor(ServerSettingsField.ValueSpec spec) {
        return valueEditor(spec, CatalogCurrent.empty());
    }

    private OptionEditor.Value<Object> valueEditor(ServerSettingsField.ValueSpec spec, CatalogCurrent current) {
        if (spec.type() == ServerSettingsField.ValueType.OBJECT) {
            List<OptionEditor.Member<?>> members = new ArrayList<>();
            for (ServerSettingsField.ObjectField field : objectFields(spec)) {
                members.add(new OptionEditor.Member<>(field.key(), field.name(), field.description(), valueEditor(field.value())));
            }
            return (OptionEditor.Value<Object>) (OptionEditor.Value<?>) new OptionEditor.ObjectValue(members);
        }
        if (spec.type() == ServerSettingsField.ValueType.LIST || spec.type() == ServerSettingsField.ValueType.MAP) {
            OptionEditor<Object> nested = (OptionEditor<Object>) collectionEditor(spec.collection());
            Object fallback = spec.type() == ServerSettingsField.ValueType.LIST ? List.of() : Map.of();
            return new OptionEditor.CollectionValue<>(nested, fallback);
        }
        return scalarEditor(spec, current);
    }

    private List<ServerSettingsField.ObjectField> objectFields(ServerSettingsField.ValueSpec spec) {
        if (!spec.fields().isEmpty() || spec.fieldsFrom() == null) {
            return spec.fields();
        }
        return dependentCatalog(spec.fieldsFrom()).stream()
                .map(choice -> new ServerSettingsField.ObjectField(String.valueOf(choice.value()), choice.label(), choice.description(),
                        new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.TEXT, null, null, List.of(), null,
                                false, List.of(), List.of(), null, null)))
                .toList();
    }

    private OptionEditor.ScalarValue<Object> scalarEditor(ServerSettingsField.ValueSpec spec) {
        return scalarEditor(spec, CatalogCurrent.empty());
    }

    private OptionEditor.ScalarValue<Object> scalarEditor(ServerSettingsField.ValueSpec spec, CatalogCurrent current) {
        Object fallback = scalarDefault(spec);
        return new OptionEditor.ScalarValue<>(scalarKind(spec.type()), fallback,
                value -> parseScalar(spec, value), value -> scalarText(spec, value), () -> catalog(spec, current), sentinels(spec));
    }

    private CatalogCurrent catalogCurrent(FieldBinding binding, ServerSettingsField.CollectionSchema schema, boolean keys) {
        Object value = normalizeCollection(schema, structuredValue(binding.value,
            binding.field.type() == ServerSettingsFieldType.MAP), binding.field.type() == ServerSettingsFieldType.MAP);
        List<String> values;
        if (value instanceof Map<?, ?> map) {
            Collection<?> selected = keys ? map.keySet() : map.values();
            values = selected.stream().filter(Objects::nonNull).map(String::valueOf).filter(text -> !text.isBlank()).distinct().toList();
        } else if (!keys && value instanceof List<?> list) {
            values = list.stream().filter(Objects::nonNull).map(String::valueOf).filter(text -> !text.isBlank()).distinct().toList();
        } else {
            values = List.of();
        }
        String kind = keys ? "keys" : "values";
        return CatalogCurrent.of("current:" + binding.document.definition.relativePath() + ":" + binding.field.id() + ":" + kind,
            values);
    }

    private OptionEditor.ScalarKind scalarKind(ServerSettingsField.ValueType type) {
        return switch (type) {
            case BOOLEAN -> OptionEditor.ScalarKind.BOOLEAN;
            case INTEGER -> OptionEditor.ScalarKind.INTEGER;
            case DECIMAL -> OptionEditor.ScalarKind.DECIMAL;
            case SELECT -> OptionEditor.ScalarKind.SELECT;
            case TEXT, DURATION, RAW, LIST, MAP, OBJECT -> OptionEditor.ScalarKind.TEXT;
        };
    }

    private Object scalarDefault(ServerSettingsField.ValueSpec spec) {
        if (!spec.sentinels().isEmpty()) {
            return normalizeSentinel(spec, spec.sentinels().getFirst().value());
        }
        return switch (spec.type()) {
            case BOOLEAN -> false;
            case INTEGER -> spec.min() != null ? spec.min().toBigIntegerExact() : BigInteger.ZERO;
            case DECIMAL -> spec.min() != null ? spec.min() : BigDecimal.ZERO;
            case SELECT -> spec.options().getFirst();
            case TEXT, DURATION, RAW -> "";
            case LIST -> List.of();
            case MAP, OBJECT -> Map.of();
        };
    }

    private Object parseScalar(ServerSettingsField.ValueSpec spec, String value) {
        String text = value == null ? "" : value.trim();
        for (ServerSettingsField.Sentinel sentinel : spec.sentinels()) {
            if (String.valueOf(sentinel.value()).equalsIgnoreCase(text)) {
                return normalizeSentinel(spec, sentinel.value());
            }
        }
        return switch (spec.type()) {
            case BOOLEAN -> {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("Expected true or false");
                }
                yield Boolean.parseBoolean(text);
            }
            case INTEGER -> checkedNumber(spec, new BigDecimal(new BigInteger(text))).toBigIntegerExact();
            case DECIMAL -> checkedNumber(spec, new BigDecimal(text));
            case DURATION -> {
                if (!validDuration(text)) throw new IllegalArgumentException("Invalid duration");
                yield text;
            }
            case RAW -> {
                try {
                    yield structuredParser.parse(text);
                } catch (RuntimeException exception) {
                    yield text;
                }
            }
            case TEXT, SELECT -> text;
            case LIST, MAP, OBJECT -> throw new IllegalArgumentException("Expected a scalar value");
        };
    }

    private BigDecimal checkedNumber(ServerSettingsField.ValueSpec spec, BigDecimal value) {
        if (spec.min() != null && value.compareTo(spec.min()) < 0 || spec.max() != null && value.compareTo(spec.max()) > 0) {
            throw new IllegalArgumentException("Value is outside the allowed range");
        }
        return value;
    }

    private Object normalizeCollection(ServerSettingsField.CollectionSchema schema, Object value, boolean map) {
        if (schema.mode() == ServerSettingsField.CollectionMode.SEQUENCE) {
            if (!(value instanceof List<?> values)) return List.of();
            List<Object> result = new ArrayList<>();
            for (Object item : values) {
                Object normalized = normalizeValue(schema.value(), item);
                if (!schema.unique() || !result.contains(normalized)) result.add(normalized);
            }
            return List.copyOf(result);
        }
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        values.forEach((key, nested) -> result.put(normalizeScalar(schema.mode() == ServerSettingsField.CollectionMode.FIXED_MAP
                ? textSpec() : schema.key(), key), normalizeValue(schema.value(), nested)));
        return Collections.unmodifiableMap(result);
    }

    private Object normalizeValue(ServerSettingsField.ValueSpec spec, Object value) {
        if (spec.type() == ServerSettingsField.ValueType.OBJECT) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> map) map.forEach((key, nested) -> result.put(String.valueOf(key), nested));
            for (ServerSettingsField.ObjectField field : objectFields(spec)) {
                Object current = result.getOrDefault(field.key(), scalarDefault(field.value()));
                result.put(field.key(), normalizeValue(field.value(), current));
            }
            return Collections.unmodifiableMap(result);
        }
        if (spec.type() == ServerSettingsField.ValueType.LIST || spec.type() == ServerSettingsField.ValueType.MAP) {
            return normalizeCollection(spec.collection(), value, spec.type() == ServerSettingsField.ValueType.MAP);
        }
        return normalizeScalar(spec, value);
    }

    private Object normalizeScalar(ServerSettingsField.ValueSpec spec, Object value) {
        if (value == null) return scalarDefault(spec);
        for (ServerSettingsField.Sentinel sentinel : spec.sentinels()) {
            if (String.valueOf(sentinel.value()).equalsIgnoreCase(String.valueOf(value))) {
                return normalizeSentinel(spec, sentinel.value());
            }
        }
        try {
            return switch (spec.type()) {
                case BOOLEAN -> value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
                case INTEGER -> value instanceof BigInteger integer ? integer : new BigInteger(String.valueOf(value));
                case DECIMAL -> value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
                case TEXT, DURATION, SELECT -> String.valueOf(value);
                case RAW -> immutableStructuredValue(value);
                case LIST, MAP, OBJECT -> value;
            };
        } catch (RuntimeException exception) {
            return scalarDefault(spec);
        }
    }

    private Object normalizeSentinel(ServerSettingsField.ValueSpec spec, Object value) {
        try {
            return switch (spec.type()) {
                case BOOLEAN -> value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
                case INTEGER -> value instanceof BigInteger integer ? integer : new BigInteger(String.valueOf(value));
                case DECIMAL -> value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
                case TEXT, DURATION, SELECT, RAW -> String.valueOf(value);
                case LIST, MAP, OBJECT -> value;
            };
        } catch (RuntimeException exception) {
            return String.valueOf(value);
        }
    }

    private Object immutableStructuredValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, immutableStructuredValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(this::immutableStructuredValue).toList();
        return value;
    }

    private Object structuredValue(Object raw, boolean map) {
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) return raw;
        if (raw == null) return map ? Map.of() : List.of();
        try {
            Object parsed = structuredParser.parse(raw.toString());
            if (map && parsed instanceof Map<?, ?> || !map && parsed instanceof List<?>) return parsed;
        } catch (RuntimeException ignored) {
        }
        return map ? Map.of() : List.of();
    }

    private String scalarText(ServerSettingsField.ValueSpec spec, Object value) {
        if (value == null) return "";
        return spec.type() == ServerSettingsField.ValueType.RAW ? formatFlow(value) : String.valueOf(value);
    }

    private List<OptionEditor.Sentinel<Object>> sentinels(ServerSettingsField.ValueSpec spec) {
        return spec.sentinels().stream().map(sentinel -> new OptionEditor.Sentinel<>(
                normalizeSentinel(spec, sentinel.value()), sentinel.label(), sentinelDescription(sentinel),
                entryKey -> referencedValue(spec, sentinel.reference(), entryKey))).toList();
    }

    private String sentinelDescription(ServerSettingsField.Sentinel sentinel) {
        if (!sentinel.description().isBlank()) return sentinel.description();
        if (sentinel.reference() != null) {
            return "Uses the current referenced setting.";
        }
        return switch (sentinel.role()) {
            case INHERIT -> "Uses the software default.";
            case DISABLED -> "Keeps this setting disabled.";
            case ALL -> "Applies to every value.";
        };
    }

    private Object referencedValue(ServerSettingsField.ValueSpec spec, ServerSettingsField.ValueReference reference,
                                   Object entryKey) {
        if (reference == null) return null;
        String fieldReference = reference.fieldFor(entryKey);
        if (fieldReference == null || fieldReference.isBlank()) return null;
        for (DocumentState document : documents.values()) {
            if (reference.document() != null && !reference.document().equals(document.definition.relativePath())) continue;
            for (FieldBinding binding : document.bindings) {
                if (!binding.field.id().equals(fieldReference) && !binding.field.key().equals(fieldReference)) continue;
                ConfigOption<?> resident = fieldOptions.get(binding);
                Object value = resident == null ? readValue(binding) : resident.get();
                if (value == null) return null;
                try {
                    return parseScalar(spec, String.valueOf(value));
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private OptionEditor.Catalog<Object> catalog(ServerSettingsField.ValueSpec spec, CatalogCurrent current) {
        if (spec.options().isEmpty() && spec.catalog() != null && spec.catalog().field() == null) {
            return sourceCatalog(spec.catalog().source(), current);
        }
        LinkedHashMap<Object, OptionEditor.Choice<Object>> choices = new LinkedHashMap<>();
        for (String option : spec.options()) {
            choices.put(option, new OptionEditor.Choice<>(option, SettingWidgetFactory.formatFriendlyDisplayName(option)));
        }
        Object stamp = List.copyOf(spec.options());
        if (spec.catalog() != null) {
            List<OptionEditor.Choice<Object>> catalog = spec.catalog().field() != null
                    ? dependentCatalog(spec.catalog().field()) : sourceCatalog(spec.catalog().source(), current).choices();
            catalog.forEach(choice -> choices.putIfAbsent(choice.value(), choice));
            stamp = List.of(stamp, catalog);
        }
        return new OptionEditor.Catalog<>(stamp, List.copyOf(choices.values()));
    }

    private List<OptionEditor.Choice<Object>> dependentCatalog(String fieldReference) {
        for (DocumentState document : documents.values()) {
            for (FieldBinding binding : document.bindings) {
                if (!binding.field.id().equals(fieldReference) && !binding.field.key().equals(fieldReference)) continue;
                ConfigOption<?> resident = collectionOptions.get(binding);
                Object value = resident != null ? resident.get()
                        : structuredValue(readValue(binding), binding.field.type() == ServerSettingsFieldType.MAP);
                if (value instanceof Map<?, ?> map) {
                    return map.keySet().stream().map(key -> choice(String.valueOf(key), "")).toList();
                }
                if (value instanceof List<?> list) {
                    return list.stream().map(item -> choice(String.valueOf(item), "")).toList();
                }
            }
        }
        return List.of();
    }

    private OptionEditor.Catalog<Object> sourceCatalog(String sourceId, CatalogCurrent current) {
        if (hostedCatalogs != null) {
            ResolvedCatalogRepository.Snapshot resolved = hostedCatalogs.catalog(sourceId, current.identity(), current.values());
            if (resolved != null) {
                return catalogAdapters.computeIfAbsent(resolved, this::adaptCatalog);
            }
        }
        LinkedHashMap<String, OptionEditor.Choice<Object>> choices = new LinkedHashMap<>();
        if ("server:minecraft:world".equals(sourceId)) {
            worldCatalog.forEach(value -> choices.put(value, choice(value, "")));
        }
        String serverId = source.catalogServerId();
        OptionCatalogLoader.Snapshot snapshot = OptionCatalogLoader.snapshot(serverId, sourceId);
        for (OptionCatalogItem item : snapshot.items()) {
            if (item.getValue() != null && item.isAvailable()) {
                choices.put(item.getValue(), new OptionEditor.Choice<>(item.getValue(), item.getLabel(), item.getDescription()));
            }
        }
        for (String value : snapshot.values()) choices.putIfAbsent(value, choice(value, ""));
        OptionEditor.CatalogState state = !choices.isEmpty() ? OptionEditor.CatalogState.READY
                : snapshot.loading() ? OptionEditor.CatalogState.LOADING : OptionEditor.CatalogState.UNAVAILABLE;
        String message = state == OptionEditor.CatalogState.LOADING ? "Catalog Is Loading"
                : snapshot.diagnostic();
        return new OptionEditor.Catalog<>(List.of(snapshot.contextKey(), snapshot.revision(), snapshot.status(), snapshot.loading()),
            List.copyOf(choices.values()), state, message);
    }

    private OptionEditor.Catalog<Object> adaptCatalog(ResolvedCatalogRepository.Snapshot snapshot) {
        List<OptionEditor.Choice<Object>> choices = snapshot.items().stream().map(item -> {
            ResolvedCatalogRepository.Item presentation = item.item();
            String description = presentation.description();
            String provenance = catalogProvenance(item);
            if (!provenance.isBlank()) description = description.isBlank() ? provenance : description + " " + provenance;
            if (!item.diagnostic().isBlank()) description = description.isBlank() ? item.diagnostic() : description + " " + item.diagnostic();
            OptionEditor.ChoiceState state = switch (item.state()) {
                case AVAILABLE -> OptionEditor.ChoiceState.AVAILABLE;
                case STALE -> OptionEditor.ChoiceState.STALE;
                case CURRENT_ONLY -> OptionEditor.ChoiceState.CURRENT_ONLY;
                case UNAVAILABLE -> OptionEditor.ChoiceState.UNAVAILABLE;
            };
            return new OptionEditor.Choice<>((Object) presentation.value(), presentation.label(), description, state, "");
        }).toList();
        OptionEditor.CatalogState state = switch (snapshot.state()) {
            case READY -> OptionEditor.CatalogState.READY;
            case STALE -> OptionEditor.CatalogState.STALE;
            case LOADING -> OptionEditor.CatalogState.LOADING;
            case UNAVAILABLE -> OptionEditor.CatalogState.UNAVAILABLE;
            case FAILED -> OptionEditor.CatalogState.FAILED;
        };
        String message = snapshot.diagnostics().stream().map(ResolvedCatalogRepository.SourceDiagnostic::message)
                .filter(value -> !value.isBlank()).distinct().collect(Collectors.joining(" "));
        return new OptionEditor.Catalog<>(snapshot.stamp(), choices, state, message);
    }

    private String catalogProvenance(ResolvedCatalogRepository.ResolvedItem item) {
        boolean hosted = item.provenance().contains(ResolvedCatalogRepository.Provenance.BASELINE);
        boolean live = item.provenance().contains(ResolvedCatalogRepository.Provenance.LIVE);
        if (hosted && live) return "Verified by the hosted release catalog and the connected server.";
        if (live) return "Reported by the connected server.";
        if (hosted) return "Provided by the hosted release catalog.";
        return "";
    }

    private OptionEditor.Choice<Object> choice(String value, String description) {
        return new OptionEditor.Choice<>(value, SettingWidgetFactory.formatFriendlyDisplayName(value), description);
    }

    private ServerSettingsField.ValueSpec textSpec() {
        return new ServerSettingsField.ValueSpec(ServerSettingsField.ValueType.TEXT, null, null, List.of(), null,
                false, List.of(), List.of(), null, null);
    }


    private ConfigOption<String> numberOption(FieldBinding binding, ConfigOption.Editor editor) {
        ServerSettingsField field = binding.field;
        ConfigOption.Builder<String> builder = ConfigOption.<String>builder(field.name())
                .description(field.description())
                .bind(() -> (String) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((String) binding.defaultValue)
                .editor(isSeedField(field) ? ConfigOption.Editor.SEED : editor);

        if (field.min() != null && field.max() != null) {
            builder.range(field.min().toPlainString(), field.max().toPlainString());
        }
        String sentinel = switch (field.type()) {
            case INTEGER_OR_DEFAULT, DECIMAL_OR_DEFAULT -> "default";
            case INTEGER_OR_DISABLED, DECIMAL_OR_DISABLED -> field.disabledValue() != null ? field.disabledValue() : "disabled";
            default -> field.disabledValue();
        };
        if (sentinel != null) {
            builder.disabledValue(sentinel);
        }
        return builder.build();
    }

    private ConfigOption<String> booleanUnionOption(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        String sentinel = field.type() == ServerSettingsFieldType.BOOLEAN_OR_DEFAULT ? "default"
                : field.disabledValue() != null ? field.disabledValue() : "disabled";
        List<String> options = new ArrayList<>(List.of(sentinel, "true", "false"));
        String current = String.valueOf(readValue(binding));
        if (!options.contains(current)) options.add(current);
        return ConfigOption.<String>builder(field.name())
                .description(field.description())
                .bind(() -> (String) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((String) binding.defaultValue)
                .options(options)
                .display(SettingWidgetFactory::formatFriendlyDisplayName)
                .build();
    }

    private ConfigOption<Boolean> booleanOption(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        return ConfigOption.<Boolean>builder(field.name())
                .description(field.description())
                .bind(() -> (Boolean) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((Boolean) binding.defaultValue)
                .build();
    }

    private ConfigOption<String> textOption(FieldBinding binding) {
        ServerSettingsField field = binding.field;
        ConfigOption.Builder<String> builder = ConfigOption.<String>builder(field.name())
                .description(field.description())
                .bind(() -> (String) readValue(binding), value -> writeValue(binding, value))
                .defaultValue((String) defaultValue(field));
        if (isSeedField(field)) {
            builder.seed(true);
        }
        if (field.disabledValue() != null) {
            builder.disabledValue(field.disabledValue());
        }
        if (field.type() == ServerSettingsFieldType.SELECT) {
            List<String> options = new ArrayList<>(field.options());
            String current = binding.value == null ? null : binding.value.toString();
            if (current != null && !options.contains(current)) {
                options.add(current);
            }
            builder.options(options);
        }
        return builder.build();
    }

    private Object readValue(FieldBinding binding) {
        synchronized (stateLock) {
            if (isServerProperties(binding.document.definition.relativePath())) {
                String raw = source.property(binding.field.key());
                return raw == null ? binding.value : parseValue(binding.field, raw);
            }
            return binding.value;
        }
    }

    private void writeValue(FieldBinding binding, Object value) {
        synchronized (stateLock) {
            Object parsed = parseValue(binding.field, serializeValue(binding.field.type(), value));
            boolean sameInitial = Objects.equals(parsed, binding.value);
            boolean hadChange = binding.document.changedValues.containsKey(binding.field.key());
            binding.value = parsed;
            if (sameInitial && !hadChange) {
                return;
            }
            String serialized = serializeDocumentValue(binding.document, binding.field, parsed);
            BaselineValue baseline = binding.document.baselines.getOrDefault(binding.field.key(), new BaselineValue(false, ""));
            if (sameInitial) {
                binding.document.changedValues.remove(binding.field.key());
            } else if (baseline.same(!isRemoval(serialized), comparableValue(binding.document, binding.field.key(), serialized))) {
                binding.document.changedValues.remove(binding.field.key());
            } else {
                binding.document.changedValues.put(binding.field.key(), serialized);
            }
            if (isServerProperties(binding.document.definition.relativePath())) {
                String propertiesValue = serializeValue(binding.field.type(), parsed);
                if (binding.field.nullable() && isNullToken(propertiesValue)) {
                    source.removeProperty(binding.field.key());
                } else {
                    source.property(binding.field.key(), propertiesValue);
                }
            }
        }
    }

    protected Async<Void> saveTo(ServerSettingsDocumentStore targetStore, boolean validateConflicts) {
        List<SaveRequest> changed = new ArrayList<>();
        synchronized (stateLock) {
            for (DocumentState document : documents.values()) {
                if (!writeServerProperties && isServerProperties(document.definition.relativePath())) {
                    continue;
                }
                synchronizeChangedValues(document);
                if (!document.changedValues.isEmpty()) {
                    changed.add(new SaveRequest(document, document.changedValues));
                }
            }
        }
        if (changed.isEmpty()) {
            return Async.completed(null);
        }
        List<Async<SavePlan>> plans = changed.stream().map(request -> readSavePlan(targetStore, request)).toList();
        return Async.allOf(plans.toArray(Async[]::new)).thenCompose(ignored -> {
            List<SavePlan> resolved = plans.stream().map(Async::join).toList();
            if (validateConflicts) {
                try {
                    resolved.forEach(this::validateConflict);
                } catch (RuntimeException exception) {
                    return Async.failed(exception);
                }
            }
            Async<Void> writes = Async.completed(null);
            for (SavePlan plan : resolved) {
                writes = writes.thenCompose(ignoredWrite -> targetStore.write(plan.document().definition.relativePath(), plan.updatedContent())
                        .thenRun(() -> markSaved(plan)));
            }
            return writes;
        });
    }

    private Async<SavePlan> readSavePlan(ServerSettingsDocumentStore targetStore, SaveRequest request) {
        DocumentState document = request.document();
        Async<ServerSettingsDocumentStore.Document> latest;
        try {
            latest = targetStore.read(document.definition.relativePath());
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        if (latest == null) {
            return Async.failed(new IllegalStateException("Could not read configuration document: " + document.definition.relativePath()));
        }
        return latest.thenApply(current -> {
            if (current == null || !current.exists()) {
                String updated = applyMutations(document, "", request.mutations());
                return new SavePlan(document, request.mutations(), false, "", updated);
            }
            String resolved = current.content();
            return new SavePlan(document, request.mutations(), true, resolved, applyMutations(document, resolved, request.mutations()));
        });
    }

    private void validateConflict(SavePlan plan) {
        DocumentState document = plan.document();
        for (String key : plan.mutations().keySet()) {
            BaselineValue baseline = document.baselines.getOrDefault(key, new BaselineValue(false, ""));
            boolean latestPresent = plan.exists() && document.adapter.contains(plan.latestContent(), key);
            String latestValue = plan.exists() ? document.adapter.read(plan.latestContent(), key) : "";
            if (!baseline.same(latestPresent, latestValue)) {
                throw new ServerSettingsConflictException(document.definition.relativePath(), key, baseline.value(), latestValue);
            }
        }
    }

    private void markSaved(SavePlan plan) {
        synchronized (stateLock) {
            DocumentState document = plan.document();
            document.setBaseline(plan.updatedContent());
            document.exists = true;
            plan.mutations().forEach((key, value) -> document.changedValues.remove(key, value));
            Map<String, String> remaining = new LinkedHashMap<>(document.changedValues);
            refreshBaselines(document);
            for (FieldBinding binding : document.bindings) {
                String value = remaining.get(binding.field.key());
                if (value != null) {
                    binding.value = parseValue(document, binding.field, value);
                }
            }
        }
    }

    private String applyMutations(DocumentState document, String content, Map<String, String> mutations) {
        String updated = content == null ? "" : content;
        Map<String, String> effective = mergeOverlappingMapMutations(document, mutations);
        List<Map.Entry<String, String>> ordered = effective.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> keyDepth(entry.getKey())))
                .toList();
        for (Map.Entry<String, String> mutation : ordered) {
            updated = isRemoval(mutation.getValue())
                    ? document.adapter.remove(updated, mutation.getKey())
                    : document.adapter.apply(updated, mutation.getKey(), mutation.getValue());
        }
        return updated;
    }

    private Map<String, String> mergeOverlappingMapMutations(DocumentState document, Map<String, String> mutations) {
        LinkedHashMap<String, String> effective = new LinkedHashMap<>(mutations);
        for (Map.Entry<String, String> root : mutations.entrySet()) {
            if (isRemoval(root.getValue())) {
                continue;
            }
            Object parsed = yamlValue(root.getValue());
            if (!(parsed instanceof Map<?, ?>)) {
                continue;
            }
            LinkedHashMap<Object, Object> merged = mutableMap(parsed);
            boolean changed = false;
            List<String> rootSegments = pathSegments(root.getKey());
            for (Map.Entry<String, String> nested : mutations.entrySet()) {
                List<String> nestedSegments = pathSegments(nested.getKey());
                if (nestedSegments.size() > rootSegments.size() && nestedSegments.subList(0, rootSegments.size()).equals(rootSegments)) {
                    applyNestedMutation(merged, nestedSegments.subList(rootSegments.size(), nestedSegments.size()), nested.getValue());
                    effective.remove(nested.getKey());
                    changed = true;
                }
            }
            if (changed) {
                effective.put(root.getKey(), structuredDefault(merged, true));
            }
        }
        return effective;
    }

    private void applyNestedMutation(Map<Object, Object> root, List<String> segments, String value) {
        Map<Object, Object> current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            Object next = current.get(segment);
            if (!(next instanceof Map<?, ?>)) {
                LinkedHashMap<Object, Object> replacement = new LinkedHashMap<>();
                current.put(segment, replacement);
                current = replacement;
            } else {
                LinkedHashMap<Object, Object> replacement = mutableMap(next);
                current.put(segment, replacement);
                current = replacement;
            }
        }
        String leaf = segments.getLast();
        if (isRemoval(value)) {
            current.remove(leaf);
        } else {
            current.put(leaf, yamlValue(value));
        }
    }

    private LinkedHashMap<Object, Object> mutableMap(Object value) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> result.put(key, nested instanceof Map<?, ?> ? mutableMap(nested) : nested));
        }
        return result;
    }

    private Object yamlValue(String value) {
        if (value == null || isRemoval(value)) {
            return null;
        }
        try {
            return structuredParser.parse(value);
        } catch (RuntimeException exception) {
            return value;
        }
    }

    private int keyDepth(String key) {
        return Math.max(0, pathSegments(key).size() - 1);
    }

    private List<String> pathSegments(String key) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        char[] characters = key == null ? new char[0] : key.toCharArray();
        for (char character : characters) {
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '.') {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        segments.add(current.toString());
        return segments;
    }

    private String comparableValue(DocumentState document, String key, String serialized) {
        String content = isRemoval(serialized) ? document.adapter.remove("", key) : document.adapter.apply("", key, serialized);
        return document.adapter.read(content, key);
    }

    private void synchronizeChangedValues(DocumentState document) {
        for (FieldBinding binding : document.bindings) {
            if (isServerProperties(document.definition.relativePath())) {
                String raw = source.property(binding.field.key());
                if (raw != null) {
                    Object value = parseValue(binding.field, raw);
                    if (!Objects.equals(value, binding.value)) {
                        binding.value = value;
                        document.changedValues.put(binding.field.key(), serializeValue(binding.field.type(), value));
                    }
                }
            }
        }
    }

    private void refreshBaselines(DocumentState document) {
        document.baselines.clear();
        for (FieldBinding binding : document.bindings) {
            boolean present = document.baselineReader.contains(binding.field.key());
            String raw = document.baselineReader.read(binding.field.key());
            document.baselines.putIfAbsent(binding.field.key(), new BaselineValue(present, raw));
            binding.presentAtLoad = present;
            binding.rawAtLoad = raw;
            binding.value = parseValue(document, binding.field, present ? raw : null);
        }
    }

    private void updateServerProperties(String content) {
        if (content == null) {
            return;
        }
        Properties properties = new Properties();
        try (StringReader reader = new StringReader(content)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return;
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        source.replaceProperties(values);
    }

    private Object parseValue(ServerSettingsField field, String raw) {
        String value = raw == null ? "" : raw.trim();
        Object fallback = defaultValue(field);
        if (raw == null || value.isEmpty()) {
            return fallback;
        }
        if (field.nullable() && isNullToken(value)) {
            return "null";
        }
        try {
            return switch (field.type()) {
                case BOOLEAN -> value.equalsIgnoreCase("true") ? true : value.equalsIgnoreCase("false") ? false : fallback;
                case BOOLEAN_OR_DEFAULT -> booleanUnionValue(value, "default", fallback);
                case BOOLEAN_OR_DISABLED -> booleanUnionValue(value, field.disabledValue() != null ? field.disabledValue() : "disabled", fallback);
                case INTEGER -> validateInteger(value, field) ? value : fallback;
                case DECIMAL -> validateDecimal(value, field) ? value : fallback;
                case INTEGER_OR_DEFAULT -> unionIntegerValue(value, "default", fallback, field);
                case INTEGER_OR_DISABLED -> unionIntegerValue(value, "disabled", fallback, field);
                case DECIMAL_OR_DEFAULT -> unionDecimalValue(value, "default", fallback, field);
                case DECIMAL_OR_DISABLED -> unionDecimalValue(value, "disabled", fallback, field);
                case TEXT -> raw;
                case DURATION -> validDuration(value) ? raw : fallback;
                case DURATION_OR_DISABLED -> value.equalsIgnoreCase(field.disabledValue() != null ? field.disabledValue() : "disabled") || validDuration(value) ? raw : fallback;
                case SELECT -> raw;
                case LIST -> structuredValue(field, raw, '[', ']') ? raw : fallback;
                case MAP -> structuredValue(field, raw, '{', '}') ? raw : fallback;
            };
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private Object parseValue(DocumentState document, ServerSettingsField field, String raw) {
        return parseValue(field, decodeDocumentValue(document, field.type(), raw));
    }

    private String decodeDocumentValue(DocumentState document, ServerSettingsFieldType type, String value) {
        if (value == null) {
            return value;
        }
        if (document.definition.format() == ServerSettingsFormat.PROPERTIES) {
            Properties properties = new Properties();
            try (StringReader reader = new StringReader("value=" + value)) {
                properties.load(reader);
                return properties.getProperty("value", value);
            } catch (IOException ignored) {
                return value;
            }
        }
        if (document.definition.format() != ServerSettingsFormat.TOML || type != ServerSettingsFieldType.TEXT && type != ServerSettingsFieldType.SELECT && type != ServerSettingsFieldType.DURATION && type != ServerSettingsFieldType.DURATION_OR_DISABLED) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return trimmed;
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String serializeDocumentValue(DocumentState document, ServerSettingsField field, Object value) {
        ServerSettingsFieldType type = field.type();
        String serialized = serializeValue(type, value);
        if (field.nullable() && isNullToken(serialized)) {
            if (document.definition.format() == ServerSettingsFormat.TOML || document.definition.format() == ServerSettingsFormat.PROPERTIES) {
                return REMOVE_VALUE;
            }
            return "null";
        }
        if (document.definition.format() == ServerSettingsFormat.PROPERTIES) {
            return escapePropertyValue(serialized);
        }
        if (document.definition.format() == ServerSettingsFormat.YAML && (type == ServerSettingsFieldType.TEXT || type == ServerSettingsFieldType.SELECT || type == ServerSettingsFieldType.DURATION || type == ServerSettingsFieldType.DURATION_OR_DISABLED)) {
            if (type == ServerSettingsFieldType.SELECT && serialized.matches("-?\\d+(?:\\.\\d+)?")) {
                return serialized;
            }
            return "'" + serialized.replace("'", "''") + "'";
        }
        if (document.definition.format() != ServerSettingsFormat.TOML || type != ServerSettingsFieldType.TEXT && type != ServerSettingsFieldType.SELECT && type != ServerSettingsFieldType.DURATION && type != ServerSettingsFieldType.DURATION_OR_DISABLED) {
            return serialized;
        }
        return "\"" + serialized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String escapePropertyValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case ' ' -> escaped.append("\\ ");
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\f' -> escaped.append("\\f");
                case '=', ':', '#', '!' -> escaped.append('\\').append(character);
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private Object defaultValue(ServerSettingsField field) {
        if (field.defaultSpecified()) {
            if (field.defaultValue() == null) {
                return field.nullable() ? "null" : "";
            }
            return switch (field.type()) {
                case BOOLEAN -> (Boolean) field.defaultValue();
                case INTEGER, DECIMAL -> field.defaultValue().toString();
                case TEXT, SELECT, DURATION, DURATION_OR_DISABLED, BOOLEAN_OR_DEFAULT, BOOLEAN_OR_DISABLED, INTEGER_OR_DEFAULT, INTEGER_OR_DISABLED, DECIMAL_OR_DEFAULT, DECIMAL_OR_DISABLED -> field.defaultValue().toString();
                case LIST -> structuredDefault(field.defaultValue(), false);
                case MAP -> structuredDefault(field.defaultValue(), true);
            };
        }
        return switch (field.type()) {
            case BOOLEAN -> false;
            case BOOLEAN_OR_DEFAULT -> "default";
            case BOOLEAN_OR_DISABLED -> field.disabledValue() != null ? field.disabledValue() : "disabled";
            case INTEGER -> "0";
            case DECIMAL -> "0.0";
            case INTEGER_OR_DEFAULT -> "default";
            case INTEGER_OR_DISABLED -> field.disabledValue() != null ? field.disabledValue() : "disabled";
            case DECIMAL_OR_DEFAULT -> "default";
            case DECIMAL_OR_DISABLED -> field.disabledValue() != null ? field.disabledValue() : "disabled";
            case TEXT -> "";
            case SELECT -> field.options().getFirst();
            case DURATION -> "";
            case DURATION_OR_DISABLED -> field.disabledValue() != null ? field.disabledValue() : "disabled";
            case LIST -> "[]";
            case MAP -> "{}";
        };
    }

    private String serializeValue(ServerSettingsFieldType type, Object value) {
        if (value == null) {
            return "";
        }
        return switch (type) {
            case BOOLEAN -> Boolean.toString((Boolean) value);
            case INTEGER, DECIMAL, TEXT, SELECT, DURATION, DURATION_OR_DISABLED, LIST, MAP, BOOLEAN_OR_DEFAULT, BOOLEAN_OR_DISABLED, INTEGER_OR_DEFAULT, INTEGER_OR_DISABLED, DECIMAL_OR_DEFAULT, DECIMAL_OR_DISABLED -> value.toString();
        };
    }

    private String booleanUnionValue(String value, String sentinel, Object fallback) {
        return value.equalsIgnoreCase(sentinel) || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") ? value : (String) fallback;
    }

    private String unionIntegerValue(String value, String sentinel, Object fallback, ServerSettingsField field) {
        if (value.equalsIgnoreCase(sentinel) || (field.disabledValue() != null && value.equalsIgnoreCase(field.disabledValue()))) {
            return value;
        }
        try {
            BigInteger parsed = new BigInteger(value);
            BigDecimal decimal = new BigDecimal(parsed);
            if (field.min() != null && decimal.compareTo(field.min()) < 0 || field.max() != null && decimal.compareTo(field.max()) > 0) {
                return (String) fallback;
            }
            return value;
        } catch (NumberFormatException ignored) {
            return (String) fallback;
        }
    }

    private String unionDecimalValue(String value, String sentinel, Object fallback, ServerSettingsField field) {
        if (value.equalsIgnoreCase(sentinel) || (field.disabledValue() != null && value.equalsIgnoreCase(field.disabledValue()))) {
            return value;
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (field.min() != null && parsed.compareTo(field.min()) < 0 || field.max() != null && parsed.compareTo(field.max()) > 0) {
                return (String) fallback;
            }
            return value;
        } catch (NumberFormatException ignored) {
            return (String) fallback;
        }
    }

    private boolean validateInteger(String value, ServerSettingsField field) {
        if (field.disabledValue() != null && field.disabledValue().equalsIgnoreCase(value)) {
            return true;
        }
        try {
            BigInteger parsed = new BigInteger(value);
            BigDecimal decimal = new BigDecimal(parsed);
            return (field.min() == null || decimal.compareTo(field.min()) >= 0)
                    && (field.max() == null || decimal.compareTo(field.max()) <= 0);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean validateDecimal(String value, ServerSettingsField field) {
        if (field.disabledValue() != null && field.disabledValue().equalsIgnoreCase(value)) {
            return true;
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            return (field.min() == null || parsed.compareTo(field.min()) >= 0)
                    && (field.max() == null || parsed.compareTo(field.max()) <= 0);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isNullToken(String value) {
        return value.equalsIgnoreCase("null") || value.equals("~");
    }

    private boolean isRemoval(String value) {
        return REMOVE_VALUE.equals(value);
    }

    private boolean validDuration(String value) {
        return value != null && value.matches("(?i)(?:-?\\d+(?:\\.\\d+)?)(?:ms|s|m|h|d|t)?");
    }

    private boolean structuredValue(ServerSettingsField field, String raw, char opening, char closing) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return false;
        }
        try {
            Object parsed = structuredParser.parse(value);
            return field.type() == ServerSettingsFieldType.LIST ? parsed instanceof List<?> : parsed instanceof Map<?, ?>;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String structuredDefault(Object value, boolean map) {
        if (value == null) {
            return map ? "{}" : "[]";
        }
        if (value instanceof Map<?, ?> values) {
            return values.entrySet().stream().map(entry -> formatFlow(entry.getKey()) + ": " + formatFlow(entry.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(formatFlow(item)));
            return items.isEmpty() ? "[]" : "[" + String.join(", ", items) + "]";
        }
        return value.toString();
    }

    private String formatFlow(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) return structuredDefault(value, true);
        if (value instanceof Iterable<?>) return structuredDefault(value, false);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        String text = value.toString();
        if (text.matches("[A-Za-z0-9_./:-]+")) return text;
        return "'" + text.replace("'", "''") + "'";
    }

    private boolean isServerProperties(String path) {
        return SERVER_PROPERTIES_PATH.equals(path);
    }

    private ConfigurationFormat adapterFormat(ServerSettingsFormat format) {
        return switch (format) {
            case PROPERTIES -> ConfigurationFormat.PROPERTIES;
            case YAML -> ConfigurationFormat.YAML;
            case TOML -> ConfigurationFormat.TOML;
        };
    }

    private record DocumentDefinition(String relativePath, ServerSettingsFormat format, boolean required, boolean createIfMissing,
                                      List<ServerSettingsField> fields) {
        private DocumentDefinition {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        private DocumentDefinition merge(DocumentDefinition other) {
            if (format != other.format) {
                throw new IllegalArgumentException("Multiple formats were registered for configuration document: " + relativePath);
            }
            List<ServerSettingsField> merged = new ArrayList<>(fields);
            for (ServerSettingsField field : other.fields) {
                if (merged.stream().anyMatch(existing -> existing.id().equalsIgnoreCase(field.id()) || existing.key().equals(field.key()))) {
                    throw new IllegalArgumentException("Duplicate configuration field in " + relativePath + ": " + field.id());
                }
                merged.add(field);
            }
            return new DocumentDefinition(relativePath, format, required || other.required, createIfMissing || other.createIfMissing, merged);
        }
    }

    private record LoadedDocument(DocumentDefinition definition, boolean available, boolean exists, String content) {
    }

    private record SaveRequest(DocumentState document, Map<String, String> mutations) {
        private SaveRequest {
            mutations = Collections.unmodifiableMap(new LinkedHashMap<>(mutations));
        }
    }

    private record SavePlan(DocumentState document, Map<String, String> mutations, boolean exists,
                            String latestContent, String updatedContent) {
    }

    private static final class DocumentState {
        private final DocumentDefinition definition;
        private final NetworkConfigurationAdapter adapter;
        private final List<FieldBinding> bindings = new ArrayList<>();
        private final LinkedHashMap<String, BaselineValue> baselines = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> changedValues = new LinkedHashMap<>();
        private String baselineContent;
        private NetworkConfigurationAdapter.Reader baselineReader;
        private boolean exists;

        private DocumentState(DocumentDefinition definition, String baselineContent, boolean exists, NetworkConfigurationAdapter adapter) {
            this.definition = definition;
            this.exists = exists;
            this.adapter = adapter;
            setBaseline(baselineContent);
        }

        private void setBaseline(String content) {
            baselineContent = content == null ? "" : content;
            baselineReader = adapter.prepare(baselineContent);
        }
    }

    private static final class FieldBinding {
        private final DocumentState document;
        private final ServerSettingsField field;
        private final Object defaultValue;
        private Object value;
        private boolean presentAtLoad;
        private String rawAtLoad;

        private FieldBinding(DocumentState document, ServerSettingsField field, Object value, Object defaultValue,
                             boolean presentAtLoad, String rawAtLoad) {
            this.document = document;
            this.field = field;
            this.defaultValue = defaultValue;
            this.value = value;
            this.presentAtLoad = presentAtLoad;
            this.rawAtLoad = rawAtLoad;
        }
    }

    private record CatalogCurrent(String identity, List<String> values) {
        private CatalogCurrent {
            identity = identity == null || identity.isBlank() ? "current:none" : identity;
            values = values == null ? List.of() : List.copyOf(values);
        }

        private static CatalogCurrent empty() {
            return new CatalogCurrent("current:none", List.of());
        }

        private static CatalogCurrent of(String identity, List<String> values) {
            return new CatalogCurrent(identity == null ? "current:configured" : identity,
                values == null ? List.of() : List.copyOf(values));
        }
    }

    private record BaselineValue(boolean present, String value) {
        private boolean same(boolean latestPresent, String latestValue) {
            return present == latestPresent && Objects.equals(value, latestValue);
        }

        private boolean matches(String desired) {
            return present && Objects.equals(value, desired);
        }
    }
}
