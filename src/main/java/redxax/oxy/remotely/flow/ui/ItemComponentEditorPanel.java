package redxax.oxy.remotely.flow.ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ItemComponentEditorPanel {
    public static final String SCHEMA_SOURCE = "server:minecraft:item_attribute_schema";
    private static final Gson GSON = new Gson();
    private static final Set<String> PRESENCE_COMPONENTS = Set.of("minecraft:unbreakable", "minecraft:glider",
        "minecraft:intangible_projectile", "minecraft:creative_slot_lock");

    public record Snapshot(JsonObject scope, Map<String, Object> components) {
        public Snapshot {
            scope = scope != null ? scope.deepCopy() : null;
            components = copyMap(components);
        }

        @Override
        public JsonObject scope() {
            return scope != null ? scope.deepCopy() : null;
        }

        @Override
        public Map<String, Object> components() {
            return copyMap(components);
        }
    }

    public record Model(String title, String material, JsonObject scope, Map<String, Object> components,
                        boolean saveAction, Consumer<Snapshot> onChange, Consumer<Snapshot> onSave,
                        Runnable onClose) {
        public Model {
            title = title == null || title.isBlank() ? "Item Components" : title;
            material = material == null ? "" : material;
            scope = scope != null ? scope.deepCopy() : null;
            components = copyMap(components);
            onChange = onChange != null ? onChange : ignored -> {};
            onSave = onSave != null ? onSave : ignored -> {};
            onClose = onClose != null ? onClose : () -> {};
        }

        @Override
        public JsonObject scope() {
            return scope != null ? scope.deepCopy() : null;
        }

        @Override
        public Map<String, Object> components() {
            return copyMap(components);
        }
    }

    private final String serverId;
    private final StudioPanel panel;
    private final SidePanel sidePanel;
    private final Map<String, ComponentView> componentViews = new LinkedHashMap<>();
    private final Map<String, GroupView> groupViews = new LinkedHashMap<>();
    private Model model;
    private JsonObject scope;
    private Map<String, Object> components = new LinkedHashMap<>();
    private String material = "";
    private String query = "";
    private String catalogContextKey = "";
    private long catalogRevision = Long.MIN_VALUE;
    private int editorWidth;
    private MountableButtonWidget header;
    private MountableButtonWidget componentsHeader;
    private MountableButtonWidget catalogStatus;
    private TitledRowWidget categoryRow;
    private TitledRowWidget targetRow;
    private DropDownWidget<String> kindControl;
    private DropDownWidget<String> categoryControl;
    private TextInputWidget targetInput;
    private TextInputWidget searchInput;
    private boolean syncingControls;
    private boolean open;
    private boolean dismissing;
    private boolean closing;

    public ItemComponentEditorPanel(ReScreen screen, String serverId, String id) {
        Objects.requireNonNull(screen, "Component editor screen is required");
        this.serverId = serverId == null ? "" : serverId;
        panel = new StudioPanel(screen, id == null || id.isBlank() ? "itemComponentEditor" : id)
            .right().dismissible("Item Components").padding(3).hide();
        sidePanel = panel.sidePanel();
        sidePanel.minWidth(360).width(420).maxWidthRatio(55);
        sidePanel.onUserVisibilityChanged(visible -> {
            if (!visible && open && !closing) {
                close();
            }
        });
    }

    public SidePanel sidePanel() {
        return sidePanel;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isPresenting() {
        return open || dismissing;
    }

    public void open(Model next) {
        model = Objects.requireNonNull(next, "Component editor model is required");
        scope = model.scope();
        components = new LinkedHashMap<>(model.components());
        material = model.material();
        query = "";
        catalogContextKey = "";
        catalogRevision = Long.MIN_VALUE;
        componentViews.clear();
        groupViews.clear();
        open = true;
        dismissing = false;
        buildWidgetTree();
        preloadCatalogs();
        refreshForCatalogPublication();
        panel.show();
    }

    public void close() {
        if (!open) {
            return;
        }
        closing = true;
        try {
            open = false;
            dismissing = true;
            panel.hide();
            Model closed = model;
            model = null;
            if (closed != null) {
                closed.onClose().run();
            }
        } finally {
            closing = false;
        }
    }

    public void refreshCatalog() {
        catalogRevision = Long.MIN_VALUE;
        if (open) {
            preloadCatalogs();
            refreshForCatalogPublication();
        }
    }

    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        if (!isPresenting()) {
            return;
        }
        if (open) {
            refreshForCatalogPublication();
        }
        panel.layout();
        sidePanel.update();
        sidePanel.container().render(context, mouseX, mouseY, delta);
        sidePanel.renderHeader(context, mouseX, mouseY);
        panel.renderHintOverlay(context);
        if (!open && sidePanel.getAnimatedWidth() <= 1f) {
            dismissing = false;
        }
    }

    private void buildWidgetTree() {
        editorWidth = Math.max(320, panel.rowWidth());
        MountableButtonWidget.Builder headerBuilder = new MountableButtonWidget.Builder(model.title())
            .description(componentSummary()).iconPath("item.png");
        if (model.saveAction()) {
            headerBuilder.addButton(new SquareButtonWidget.Builder().imagePath("save.png").hint("Save Component Builder")
                .entranceAnimation(false).onClick(() -> model.onSave().accept(snapshot())).build());
        }
        header = headerBuilder.addButton(new SquareButtonWidget.Builder().imagePath("close.png").hint("Close")
            .entranceAnimation(false).onClick(this::close).build()).build();
        header.setSize(editorWidth, 30);
        List<AnimatedWidget> roots = new ArrayList<>();
        roots.add(header);
        if (scope != null) {
            addStableScopeRows(roots);
        }
        searchInput = new TextInputWidget.Builder().text("").placeholder("Search Components")
            .forcePlaceholder(false).size(Math.max(180, editorWidth - 12), 18).build();
        searchInput.setSearch(true);
        searchInput.setOnChange(() -> {
            query = searchInput.getText();
            updateVisibility();
        });
        roots.add(row("Search", "Name Or Purpose", editorWidth, searchInput));
        componentsHeader = section("Components", componentCountSummary(), editorWidth);
        roots.add(componentsHeader);
        catalogStatus = section("Loading Components", "Waiting For The Server Catalog", editorWidth);
        roots.add(catalogStatus);
        roots.forEach(ReSyncStudioPanelState::disableEntrance);
        panel.setWidgets(roots);
        components.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(id -> admitComponent(id, null, "Current Components", true));
        syncScopeRows();
        updateVisibility();
    }

    private void addStableScopeRows(List<AnimatedWidget> roots) {
        kindControl = new DropDownWidget.Builder<>(List.of("Dynamic", "Category", "Tag", "Item"))
            .selectedItem(label(text(scope, "kind", "dynamic"))).maxVisibleItems(4).size(230, 18)
            .onSelectionChanged(next -> {
                if (syncingControls) return;
                scope.addProperty("kind", next.toLowerCase(Locale.ROOT));
                scope.addProperty("value", "");
                material = "";
                syncingControls = true;
                targetInput.setText("");
                categoryControl.setSelectedItem("Item");
                syncingControls = false;
                syncScopeRows();
                changed();
                refreshCatalogContext();
            }).build();
        roots.add(row("Applies To", "Dynamic, Category, Tag, Or Item", editorWidth, kindControl));

        List<String> categories = List.of("Item", "Block", "Armor", "Weapon", "Tool", "Projectile", "Food");
        categoryControl = new DropDownWidget.Builder<>(categories).selectedItem("Item").maxVisibleItems(7).size(230, 18)
            .onSelectionChanged(next -> {
                if (syncingControls) return;
                scope.addProperty("value", next.toLowerCase(Locale.ROOT));
                changed();
            }).build();
        categoryRow = row("Category", "Only Matching Items", editorWidth, categoryControl);
        roots.add(categoryRow);

        targetInput = new TextInputWidget.Builder().text(text(scope, "value", "")).placeholder("minecraft:item")
            .forcePlaceholder(false).size(210, 18).build();
        targetInput.setOnChange(() -> {
            if (syncingControls) return;
            String value = targetInput.getText().trim();
            scope.addProperty("value", value);
            if ("item".equals(scopeKind())) {
                material = value;
                refreshCatalogContext();
            }
            changed();
        });
        SquareButtonWidget browse = new SquareButtonWidget.Builder().imagePath("search.png").hint("Search")
            .size(18, 18).entranceAnimation(false).onClick(() -> {
                String source = "tag".equals(scopeKind()) ? "server:minecraft:item_tag" : ItemOptionCatalog.SOURCE;
                showCatalogSelector(source, selected -> {
                    targetInput.setText(selected);
                    targetInput.runOnChange();
                });
            }).build();
        targetRow = row("Target", "Search Available Values", editorWidth,
            new RowWidget.Builder().size(240, 18).padding(3).addWidget(targetInput).addWidget(browse).build());
        roots.add(targetRow);
    }

    private void syncScopeRows() {
        if (scope == null || categoryRow == null || targetRow == null) return;
        String kind = scopeKind();
        String value = text(scope, "value", "");
        syncingControls = true;
        kindControl.setSelectedItem(label(kind));
        if ("category".equals(kind)) {
            String category = value.isBlank() ? "Item" : label(value);
            categoryControl.setSelectedItem(category);
            if (value.isBlank()) scope.addProperty("value", category.toLowerCase(Locale.ROOT));
        }
        if ("tag".equals(kind) || "item".equals(kind)) {
            targetInput.placeholder = "tag".equals(kind) ? "minecraft:item_tag" : "minecraft:item";
            if (!Objects.equals(targetInput.getText(), value)) targetInput.setText(value);
        }
        syncingControls = false;
        categoryRow.setVisible("category".equals(kind));
        targetRow.setVisible("tag".equals(kind) || "item".equals(kind));
        panel.container().updateWidgetPositions();
    }

    private void refreshCatalogContext() {
        catalogRevision = Long.MIN_VALUE;
        preloadCatalogs();
    }

    private void refreshForCatalogPublication() {
        String nextContext = contextKey();
        OptionCatalogCache.LegacyCatalogSnapshot snapshot = schemaSnapshot(nextContext);
        if (snapshot.revision() == catalogRevision && Objects.equals(catalogContextKey, nextContext)) return;
        catalogRevision = snapshot.revision();
        catalogContextKey = nextContext;
        List<OptionCatalogItem> items = catalogItems();
        Set<String> admitted = items.stream().map(OptionCatalogItem::getValue).filter(Objects::nonNull)
            .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        for (ComponentView view : componentViews.values()) {
            if (!view.seeded) view.catalogEligible = admitted.contains(view.id);
        }
        items.stream().filter(item -> item.getValue() != null && !item.getValue().isBlank())
            .sorted(Comparator.comparing(this::catalogGroup, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(OptionCatalogItem::getLabel, String.CASE_INSENSITIVE_ORDER))
            .forEach(item -> admitComponent(item.getValue(), item, catalogGroup(item), false));
        catalogStatus.setName(snapshot.present() ? "No Components Available" : "Loading Components");
        catalogStatus.setDescription(snapshot.present() ? snapshot.diagnostic() : "Waiting For The Server Catalog");
        updateVisibility();
    }

    private void admitComponent(String id, OptionCatalogItem item, String group, boolean seeded) {
        ComponentView existing = componentViews.get(id);
        if (existing != null) {
            existing.seeded |= seeded;
            existing.catalogEligible |= item != null;
            existing.updateCatalog(item);
            return;
        }
        ComponentView view = new ComponentView(id, item, group, seeded);
        componentViews.put(id, view);
        GroupView groupView = groupViews.computeIfAbsent(group, this::mountGroup);
        groupView.components.add(view);
        mountInGroup(groupView, view.row);
    }

    private GroupView mountGroup(String name) {
        GroupView group = new GroupView(section(name, "Item Components", editorWidth));
        panel.mountWidget(group.header, insertionIndex());
        return group;
    }

    private void mountInGroup(GroupView group, AnimatedWidget widget) {
        List<AnimatedWidget> widgets = panel.container().getWidgets();
        int index = widgets.indexOf(group.header) + 1;
        for (ComponentView component : group.components) {
            int current = widgets.indexOf(component.row);
            if (current >= index) index = current + 1;
        }
        panel.mountWidget(widget, index);
    }

    private int insertionIndex() {
        List<AnimatedWidget> widgets = panel.container().getWidgets();
        int status = widgets.indexOf(catalogStatus);
        return status < 0 ? widgets.size() : status;
    }

    private void updateVisibility() {
        int visibleComponents = 0;
        for (ComponentView view : componentViews.values()) {
            boolean visible = (view.seeded || view.catalogEligible || view.enabled()) && matchesQuery(view);
            view.row.setVisible(visible);
            if (visible) visibleComponents++;
            view.refreshHeader();
        }
        for (GroupView group : groupViews.values()) {
            group.header.setVisible(group.components.stream().anyMatch(component -> component.row.isVisible()));
        }
        boolean hasCatalogRows = componentViews.values().stream().anyMatch(view -> view.catalogEligible);
        catalogStatus.setVisible(!hasCatalogRows && visibleComponents == 0);
        componentsHeader.setDescription(componentCountSummary());
        header.setDescription(componentSummary());
        panel.container().updateWidgetPositions();
    }

    private boolean matchesQuery(ComponentView view) {
        if (query == null || query.isBlank()) return true;
        OptionCatalogItem item = view.item;
        String candidate = view.id + " " + view.title() + " " + view.group + " "
            + (item == null ? "" : item.getDescription());
        return candidate.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private List<AnimatedWidget> componentEditors(ComponentView view) {
        List<AnimatedWidget> rows = new ArrayList<>();
        String id = view.id;
        Object value = view.value();
        int width = editorWidth - 8;
        if (PRESENCE_COMPONENTS.contains(id)) {
            rows.add(section(label(id), "Enabled While This Component Is Present", width));
        } else if ("minecraft:custom_name".equals(id) || "minecraft:item_name".equals(id)) {
            rows.add(row("Text", "Displayed Item Name", width,
                textControl(textComponent(value), next -> view.replace(Map.of("text", next)), width)));
        } else if ("minecraft:lore".equals(id)) {
            CodeEditorWidget editor = codeEditor(String.join("\n", stringList(value)), width - 12, text ->
                view.replace(nonBlankLines(text)));
            rows.add(largeRow("Lore", "One Line Per Entry", width, editor));
        } else if ("minecraft:enchantments".equals(id) || "minecraft:stored_enchantments".equals(id)) {
            view.enchantments = new EnchantmentsEditor(view, value, width);
            rows.addAll(view.enchantments.widgets());
        } else if ("minecraft:attribute_modifiers".equals(id)) {
            view.modifiers = new ModifierEditor(view, value, width);
            rows.addAll(view.modifiers.widgets());
        } else {
            addValueEditors(rows, view, "", value, schema(view.item), label(id), width, 0);
        }
        return rows;
    }

    private static List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String value : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = value.trim();
            if (!line.isBlank()) lines.add(line);
        }
        return List.copyOf(lines);
    }

    private void addValueEditors(List<AnimatedWidget> rows, ComponentView view, String path, Object value,
                                 Map<String, Object> schema, String title, int width, int depth) {
        if (depth > 5) {
            rows.add(rawEditor(view, path, value, title, width));
            return;
        }
        String kind = schemaKind(schema, value);
        if ("object".equals(kind)) {
            Map<String, Object> fields = map(schema.get("fields"));
            if (fields.isEmpty()) {
                rows.add(rawEditor(view, path, value, title, width));
                return;
            }
            Map<String, Object> object = map(value);
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                Map<String, Object> fieldSchema = map(field.getValue());
                Object fieldValue = object.containsKey(field.getKey()) ? object.get(field.getKey()) : defaultFor(fieldSchema);
                addValueEditors(rows, view, child(path, field.getKey()), fieldValue, fieldSchema,
                    label(field.getKey()), width, depth + 1);
            }
        } else if ("array".equals(kind)) {
            rows.add(rawEditor(view, path, value, title, width));
        } else if ("boolean".equals(kind)) {
            ToggleWidget toggle = new ToggleWidget.Builder().label(title).toggled(Boolean.TRUE.equals(value))
                .size(180, 18).entranceAnimation(false).onChange(next -> view.replacePath(path, next)).build();
            rows.add(row(title, "True Or False", width, toggle));
        } else {
            TextInputWidget input = textControl(value == null ? "" : String.valueOf(value), next ->
                view.replacePath(path, "number".equals(kind) ? number(next, value) : next), width);
            rows.add(row(title, "number".equals(kind) ? "Number" : "Text", width, input));
        }
    }

    private AnimatedWidget rawEditor(ComponentView view, String path, Object value, String title, int width) {
        CodeEditorWidget editor = codeEditor(GSON.toJson(GSON.toJsonTree(value)), width - 12, text -> {
            try {
                JsonElement parsed = JsonParser.parseString(text);
                view.replacePath(path, GSON.fromJson(parsed, Object.class));
            } catch (RuntimeException ignored) {
            }
        });
        return largeRow(title, "Structured Value", width, editor);
    }

    private void preloadCatalogs() {
        OptionCatalogLoader.preload(serverId, SCHEMA_SOURCE, schemaContext());
        OptionCatalogLoader.preload(serverId, "server:minecraft:attribute", Map.of());
        OptionCatalogLoader.preload(serverId, "server:minecraft:enchantment", Map.of());
        OptionCatalogLoader.preload(serverId, "server:minecraft:item_tag", Map.of());
    }

    private void changed() {
        if (model != null) model.onChange().accept(snapshot());
        updateVisibility();
    }

    private Snapshot snapshot() {
        return new Snapshot(scope, components);
    }

    private void showCatalogSelector(String source, Consumer<String> selected) {
        Map<String, Object> context = Map.of();
        OptionCatalogLoader.preload(serverId, source, context);
        List<OptionCatalogItem> items = "server:minecraft:item".equals(source) || ItemOptionCatalog.SOURCE.equals(source)
            ? List.of() : catalogItems(source, context);
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(ScreenManager.getInstance().getPopupOverlay())
            .size(240, 260).searchPlaceholder("Search").emptyMessage("No Options")
            .dismissOnSelect(true);
        if (items.isEmpty() && ("server:minecraft:item".equals(source) || ItemOptionCatalog.SOURCE.equals(source))) {
            ItemOptionCatalog.mergedValues(serverId).forEach(value -> builder.addItem(ItemOptionCatalog.label(serverId, value),
                "", value, "Items", () -> selected.accept(value)));
        } else {
            for (OptionCatalogItem item : items) {
                if (item.getValue() != null && !item.getValue().isBlank()) {
                    builder.addItem(item.getLabel(), item.getIcon(), item.getDescription(), item.getGroup(),
                        () -> selected.accept(item.getValue()));
                }
            }
        }
        ItemSelectorWidget selector = builder.build();
        ScreenManager.getInstance().getPopupOverlay().addDrawableChild(selector);
        selector.show(ScreenManager.getInstance().getMouseX(), ScreenManager.getInstance().getMouseY());
    }

    private List<OptionCatalogItem> catalogItems() {
        return catalogItems(SCHEMA_SOURCE, schemaContext());
    }

    private List<OptionCatalogItem> catalogItems(String source, Map<String, Object> context) {
        FlowManager manager = FlowManager.getInstance();
        ReSyncFlowClient client = manager != null ? manager.existingFlowClient(serverId) : null;
        String key = client != null ? client.optionCatalogContextKey(context) : "";
        return OptionCatalogCache.getInstance().getItems(serverId, source, key);
    }

    private OptionCatalogCache.LegacyCatalogSnapshot schemaSnapshot(String key) {
        return OptionCatalogCache.getInstance().snapshot(serverId, SCHEMA_SOURCE, key);
    }

    private String contextKey() {
        FlowManager manager = FlowManager.getInstance();
        ReSyncFlowClient client = manager != null ? manager.existingFlowClient(serverId) : null;
        return client != null ? client.optionCatalogContextKey(schemaContext()) : "";
    }

    private Map<String, Object> schemaContext() {
        String exact = exactMaterial();
        return exact.isBlank() ? Map.of() : Map.of("material", exact.toUpperCase(Locale.ROOT));
    }

    private String exactMaterial() {
        if (scope != null && "item".equals(scopeKind())) {
            String value = text(scope, "value", "");
            if (!value.startsWith("content:") && !value.startsWith("provider:")) return value;
        }
        return material;
    }

    private String scopeKind() {
        return text(scope, "kind", "dynamic").toLowerCase(Locale.ROOT);
    }

    private String catalogGroup(OptionCatalogItem item) {
        return item.getGroup() == null || item.getGroup().isBlank() ? "Other" : item.getGroup();
    }

    private Object defaultValue(OptionCatalogItem item) {
        if (item != null) {
            Object value = item.getMetadata().get("defaultValue");
            if (value == null) value = item.getMetadata().get("exampleValue");
            if (value != null) return copy(value);
            Map<String, Object> schema = schema(item);
            if (!schema.isEmpty()) return defaultFor(schema);
        }
        return Map.of();
    }

    private Map<String, Object> schema(OptionCatalogItem item) {
        return item != null ? map(item.getMetadata().get("schema")) : Map.of();
    }

    private Object defaultFor(Map<String, Object> schema) {
        return switch (schemaKind(schema, null)) {
            case "boolean" -> false;
            case "number" -> 0;
            case "array" -> List.of();
            case "object" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                map(schema.get("fields")).forEach((key, value) -> result.put(key, defaultFor(map(value))));
                yield result;
            }
            default -> "";
        };
    }

    private String schemaKind(Map<String, Object> schema, Object value) {
        String kind = String.valueOf(schema.getOrDefault("kind", ""));
        if (!kind.isBlank() && !"raw".equals(kind)) return kind;
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        if (value instanceof Collection<?>) return "array";
        if (value instanceof Map<?, ?>) return "object";
        return "string";
    }

    private TextInputWidget textControl(String value, Consumer<String> changed, int width) {
        TextInputWidget input = new TextInputWidget.Builder().text(value).placeholder("Value")
            .forcePlaceholder(false).size(Math.max(130, width - 90), 18).build();
        input.setOnChange(() -> changed.accept(input.getText()));
        return input;
    }

    private RowWidget searchableText(String value, String source, Consumer<String> changed, int width) {
        TextInputWidget input = textControl(value, changed, width);
        SquareButtonWidget search = new SquareButtonWidget.Builder().imagePath("search.png").hint("Search")
            .size(18, 18).entranceAnimation(false).onClick(() -> showCatalogSelector(source, selected -> {
                input.setText(selected);
                input.runOnChange();
            })).build();
        return new RowWidget.Builder().size(Math.max(180, width - 20), 18).padding(3)
            .addWidget(input).addWidget(search).build();
    }

    private DropDownWidget<String> dropdown(List<String> values, String selected, Consumer<String> changed) {
        List<String> options = new ArrayList<>(values);
        if (!selected.isBlank() && !options.contains(selected)) options.addFirst(selected);
        return new DropDownWidget.Builder<>(options).selectedItem(selected).maxVisibleItems(8)
            .size(220, 18).onSelectionChanged(changed).build();
    }

    private CodeEditorWidget codeEditor(String value, int width, Consumer<String> changed) {
        CodeEditorWidget editor = new CodeEditorWidget(0, 0, Math.max(180, width), 78);
        editor.setShowLineNumbers(false);
        editor.setShowSearchNavigation(false);
        editor.setWordWrap(true);
        editor.setText(value);
        editor.onChange = changed;
        return editor;
    }

    private TitledRowWidget row(String title, String description, int width, Widget... controls) {
        return new TitledRowWidget.Builder().title(title).description(description).size(width, 36)
            .gap(4).addWidget(controls).build();
    }

    private TitledRowWidget largeRow(String title, String description, int width, AnimatedWidget editor) {
        return new TitledRowWidget.Builder().title(title).description(description).size(width, 96)
            .gap(4).addWidget(editor).build();
    }

    private MountableButtonWidget section(String title, String description, int width) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(title).description(description).build();
        row.setActive(false);
        row.setSize(width, 24);
        return row;
    }

    private String componentSummary() {
        String target = scope == null ? exactMaterial() : "dynamic".equals(scopeKind())
            ? "Any Item" : label(text(scope, "value", text(scope, "kind", "Item")));
        if (target.isBlank()) target = "Any Item";
        return target + " | " + componentCountSummary();
    }

    private String componentCountSummary() {
        return components.size() + (components.size() == 1 ? " Component" : " Components");
    }

    private String valueSummary(Object value) {
        if (value instanceof Map<?, ?> map) return map.size() + (map.size() == 1 ? " Field" : " Fields");
        if (value instanceof Collection<?> collection) return collection.size() + (collection.size() == 1 ? " Entry" : " Entries");
        return value == null ? "Set Up" : String.valueOf(value);
    }

    private final class ComponentView {
        private final String id;
        private final String group;
        private final MountableButtonWidget row;
        private final ToggleWidget toggle;
        private OptionCatalogItem item;
        private Object draft;
        private boolean seeded;
        private boolean catalogEligible;
        private boolean bodyMounted;
        private EnchantmentsEditor enchantments;
        private ModifierEditor modifiers;

        private ComponentView(String id, OptionCatalogItem item, String group, boolean seeded) {
            this.id = id;
            this.item = item;
            this.group = group;
            this.seeded = seeded;
            catalogEligible = item != null;
            draft = copy(components.containsKey(id) ? components.get(id) : defaultValue(item));
            toggle = new ToggleWidget.Builder().toggled(enabled()).size(32, 18).entranceAnimation(false)
                .onChange(this::setEnabled).build();
            row = new MountableButtonWidget.Builder(title()).description(description()).onClick(this::toggleExpanded)
                .addWidget(toggle).build();
            row.setSize(editorWidth, 28);
            ReSyncStudioPanelState.disableEntrance(row);
        }

        private void updateCatalog(OptionCatalogItem next) {
            if (next == null) return;
            item = next;
            row.setName(title());
            refreshHeader();
        }

        private String title() {
            return item != null && item.getLabel() != null && !item.getLabel().isBlank() ? item.getLabel() : label(id);
        }

        private String description() {
            if (enabled()) return valueSummary(value());
            return item != null && item.getDescription() != null && !item.getDescription().isBlank()
                ? item.getDescription() : "Can Add";
        }

        private Object value() {
            return enabled() ? components.get(id) : draft;
        }

        private boolean enabled() {
            return components.containsKey(id);
        }

        private void setEnabled(boolean enabled) {
            if (enabled) {
                components.put(id, copy(draft));
                setExpanded(true);
            } else {
                draft = copy(components.remove(id));
                setExpanded(false);
            }
            toggle.setValue(enabled);
            changed();
        }

        private void toggleExpanded() {
            if (enabled()) setExpanded(!row.isEmbeddedBodyVisible());
        }

        private void setExpanded(boolean expanded) {
            if (expanded && !bodyMounted) {
                List<AnimatedWidget> editors = componentEditors(this);
                editors.forEach(ReSyncStudioPanelState::disableEntrance);
                row.setEmbeddedBody(editors, true);
                bodyMounted = true;
            } else if (bodyMounted) {
                row.setEmbeddedBody(null, expanded);
            }
            row.setSelected(expanded);
            panel.container().updateWidgetPositions();
        }

        private void replace(Object next) {
            draft = copy(next);
            if (enabled()) {
                components.put(id, copy(next));
                changed();
            }
            refreshHeader();
        }

        private void replacePath(String path, Object next) {
            replace(updatePath(value(), path, next));
        }

        private void refreshHeader() {
            row.setDescription(description());
            if (toggle.getValue() != enabled()) toggle.setValue(enabled());
        }

        private void insertBefore(AnimatedWidget anchor, AnimatedWidget widget) {
            ReSyncStudioPanelState.disableEntrance(widget);
            row.addEmbeddedBodyWidgetBefore(anchor, widget);
            panel.container().updateWidgetPositions();
        }
    }

    private final class EnchantmentsEditor {
        private final ComponentView component;
        private final boolean wrapped;
        private final Map<String, Object> root;
        private final LinkedHashMap<String, EnchantmentEntry> entries = new LinkedHashMap<>();
        private final TitledRowWidget actionRow;
        private final int width;

        private EnchantmentsEditor(ComponentView component, Object value, int width) {
            this.component = component;
            this.width = width;
            root = map(value);
            wrapped = root.containsKey("levels");
            Map<String, Object> levels = wrapped ? map(root.get("levels")) : root;
            levels.forEach((id, level) -> entries.put(id, new EnchantmentEntry(id, level)));
            AnimatedButton add = new AnimatedButton.Builder().label("Add Enchantment").size(180, 18)
                .entranceAnimation(false).onClick(() -> showCatalogSelector("server:minecraft:enchantment", this::add)).build();
            actionRow = row("Enchantments", levels.isEmpty() ? "None" : levels.size() + " Total", width, add);
        }

        private List<AnimatedWidget> widgets() {
            List<AnimatedWidget> widgets = new ArrayList<>();
            entries.values().forEach(entry -> widgets.add(entry.row));
            widgets.add(actionRow);
            return widgets;
        }

        private void add(String id) {
            EnchantmentEntry existing = entries.get(id);
            if (existing != null) {
                existing.active = true;
                existing.level = 1;
                existing.input.setText("1");
                existing.row.setVisible(true);
            } else {
                EnchantmentEntry created = new EnchantmentEntry(id, 1);
                entries.put(id, created);
                component.insertBefore(actionRow, created.row);
            }
            sync();
        }

        private void sync() {
            Map<String, Object> levels = new LinkedHashMap<>();
            entries.values().stream().filter(entry -> entry.active).forEach(entry -> levels.put(entry.id, entry.level));
            component.replace(wrapped ? with(root, "levels", levels) : levels);
        }

        private final class EnchantmentEntry {
            private final String id;
            private final TitledRowWidget row;
            private final TextInputWidget input;
            private Object level;
            private boolean active = true;

            private EnchantmentEntry(String id, Object level) {
                this.id = id;
                this.level = level;
                input = textControl(String.valueOf(level), next -> {
                    this.level = number(next, this.level);
                    sync();
                }, width);
                AnimatedButton remove = new AnimatedButton.Builder().label("Remove").size(62, 18)
                    .accentType(ThemeManager.getAccent("danger")).entranceAnimation(false).onClick(() -> remove()).build();
                row = row(label(id), "Enchantment Level", width,
                    new RowWidget.Builder().size(240, 18).padding(3).addWidget(input).addWidget(remove).build());
            }

            private void remove() {
                active = false;
                row.setVisible(false);
                sync();
                panel.container().updateWidgetPositions();
            }
        }
    }

    private final class ModifierEditor {
        private final ComponentView component;
        private final boolean wrapped;
        private final Map<String, Object> root;
        private final List<ModifierEntry> entries = new ArrayList<>();
        private final TitledRowWidget actionRow;
        private final int width;
        private int nextOrdinal = 1;

        private ModifierEditor(ComponentView component, Object value, int width) {
            this.component = component;
            this.width = width;
            root = map(value);
            wrapped = root.get("modifiers") instanceof List<?>;
            List<?> values;
            if (value instanceof List<?> list) {
                values = list;
            } else if (root.get("modifiers") instanceof List<?> list) {
                values = list;
            } else {
                values = List.of();
            }
            values.forEach(entry -> entries.add(new ModifierEntry(map(entry), nextOrdinal++)));
            AnimatedButton add = new AnimatedButton.Builder().label("Add Modifier").size(180, 18)
                .entranceAnimation(false).onClick(this::add).build();
            actionRow = row("Modifiers", values.isEmpty() ? "None" : values.size() + " Total", width, add);
        }

        private List<AnimatedWidget> widgets() {
            List<AnimatedWidget> widgets = new ArrayList<>();
            entries.forEach(entry -> widgets.addAll(entry.widgets));
            widgets.add(actionRow);
            return widgets;
        }

        private void add() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", "resync:modifier_" + nextOrdinal);
            value.put("type", "minecraft:generic.attack_damage");
            value.put("amount", 1);
            value.put("operation", "add_value");
            value.put("slot", "any");
            ModifierEntry entry = new ModifierEntry(value, nextOrdinal++);
            entries.add(entry);
            entry.widgets.forEach(widget -> component.insertBefore(actionRow, widget));
            sync();
        }

        private void sync() {
            List<Object> values = entries.stream().filter(entry -> entry.active)
                .map(entry -> (Object) new LinkedHashMap<>(entry.value)).toList();
            component.replace(wrapped ? with(root, "modifiers", values) : values);
        }

        private final class ModifierEntry {
            private final Map<String, Object> value;
            private final List<AnimatedWidget> widgets = new ArrayList<>();
            private boolean active = true;

            private ModifierEntry(Map<String, Object> value, int ordinal) {
                this.value = new LinkedHashMap<>(value);
                widgets.add(section("Modifier " + ordinal, String.valueOf(value.getOrDefault("type", "Attribute")), width));
                widgets.add(row("Attribute", "Minecraft Attribute", width,
                    searchableText(String.valueOf(value.getOrDefault("type", "minecraft:generic.attack_damage")),
                        "server:minecraft:attribute", next -> update("type", next), width)));
                widgets.add(row("Amount", "Modifier Value", width,
                    textControl(String.valueOf(value.getOrDefault("amount", 0)),
                        next -> update("amount", number(next, this.value.get("amount"))), width)));
                widgets.add(row("Operation", "How The Value Is Applied", width,
                    dropdown(List.of("add_value", "add_multiplied_base", "add_multiplied_total"),
                        String.valueOf(value.getOrDefault("operation", "add_value")), next -> update("operation", next))));
                widgets.add(row("Slot", "Equipment Slot", width,
                    dropdown(List.of("any", "mainhand", "offhand", "head", "chest", "legs", "feet", "body"),
                        String.valueOf(value.getOrDefault("slot", "any")), next -> update("slot", next))));
                AnimatedButton remove = new AnimatedButton.Builder().label("Remove Modifier").size(180, 18)
                    .accentType(ThemeManager.getAccent("danger")).entranceAnimation(false).onClick(this::remove).build();
                widgets.add(row("Action", "Delete This Modifier", width, remove));
            }

            private void update(String field, Object next) {
                value.put(field, next);
                sync();
            }

            private void remove() {
                active = false;
                widgets.forEach(widget -> widget.setVisible(false));
                sync();
                panel.container().updateWidgetPositions();
            }
        }
    }

    private final class GroupView {
        private final MountableButtonWidget header;
        private final List<ComponentView> components = new ArrayList<>();

        private GroupView(MountableButtonWidget header) {
            this.header = header;
        }
    }

    private static Object updatePath(Object current, String path, Object value) {
        if (path == null || path.isBlank()) return copy(value);
        int split = path.indexOf('.');
        String head = split < 0 ? path : path.substring(0, split);
        String tail = split < 0 ? "" : path.substring(split + 1);
        Map<String, Object> updated = new LinkedHashMap<>(map(current));
        updated.put(head, updatePath(updated.get(head), tail, value));
        return updated;
    }

    private static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private static String child(String path, String field) {
        return path == null || path.isBlank() ? field : path + "." + field;
    }

    private static Number number(String text, Object previous) {
        try {
            double value = Double.parseDouble(text.trim());
            if (previous instanceof Byte || previous instanceof Short || previous instanceof Integer) return (int) value;
            if (previous instanceof Long) return (long) value;
            return value;
        } catch (RuntimeException exception) {
            return previous instanceof Number number ? number : 0;
        }
    }

    private static String textComponent(Object value) {
        Map<String, Object> object = map(value);
        return object.containsKey("text") ? String.valueOf(object.get("text")) : value == null ? "" : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : collection) result.add(textComponent(item));
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entry) -> {
            if (key != null) result.put(String.valueOf(key), copy(entry));
        });
        return result;
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? Map.of() : map(value);
    }

    private static Object copy(Object value) {
        return value == null ? null : GSON.fromJson(GSON.toJsonTree(value), Object.class);
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String label(String value) {
        String source = value == null ? "" : value;
        int namespace = source.indexOf(':');
        if (namespace >= 0) source = source.substring(namespace + 1);
        StringBuilder result = new StringBuilder();
        for (String part : source.replace('_', ' ').replace('.', ' ').replace('/', ' ').split("\\s+")) {
            if (part.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return result.isEmpty() ? value : result.toString();
    }
}
