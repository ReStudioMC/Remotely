package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LootTableDesignerScreen extends FocusedJsonResourceDesignerScreen implements CollaborativeSlotView {
    private static final String VAULT_OPEN_EVENT = "vault_open";
    private static final String NORMAL_VAULT_KEY = "minecraft:trial_key";
    private static final String OMINOUS_VAULT_KEY = "minecraft:ominous_trial_key";
    private static final List<String> VAULT_TARGET_OPTIONS = List.of("normal", "ominous", "any");
    private static final String ENTITY_TYPE_OPTIONS_SOURCE = "server:minecraft:entity_type";
    private static final String DAMAGE_TYPE_OPTIONS_SOURCE = "server:minecraft:damage_type";
    private static final List<String> FALLBACK_ENTITY_TYPE_OPTIONS = List.of(
        "zombie", "skeleton", "creeper", "spider", "enderman", "witch", "slime", "villager", "iron_golem", "cow", "pig", "sheep", "chicken", "player"
    );
    private static final List<String> FALLBACK_DAMAGE_TYPE_OPTIONS = List.of(
        "minecraft:lava", "minecraft:in_fire", "minecraft:on_fire", "minecraft:fall", "minecraft:drown", "minecraft:explosion", "minecraft:mob_attack",
        "minecraft:player_attack", "minecraft:arrow", "minecraft:trident", "minecraft:magic", "minecraft:wither", "minecraft:generic"
    );
    private static final int LOOT_SLOT_SIZE = 20;
    private static final int LOOT_SLOT_GAP = 2;
    private static final int LOOT_GRID_PADDING = 8;

    protected int selectedLootEntryIndex;
    protected int lootPreviewX;
    protected int lootPreviewY;
    protected int lootPreviewColumns = 9;
    protected int lootPreviewRows = 3;
    protected int lootPreviewEntryOffset;
    protected int lootHighlightNonce;
    protected final int lootHighlightAnimationScope = SlotInteractionGrid.animationScope();
    protected final SlotCollaborationAuthority slotCollaboration = new SlotCollaborationAuthority();
    protected final List<AnimatedButton> lootSlotButtons = new ArrayList<>();
    protected final AnimatedButton lootGridContainer = new AnimatedButton.Builder()
        .active(false)
        .enableHoverColors(false)
        .animateElevation(false)
        .entranceAnimation(false)
        .build();

    protected record LootSlot(int slotIndex, int entryIndex, SlotInteractionGrid.SlotRect rect) {
    }

    public LootTableDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.LOOT_TABLE, resourceId, resource, serverId, parent);
        ensureTriggerObject();
        if (VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent())) {
            ensureVaultTriggerDefaults();
        }
        ensureRecipeItemCatalogLoaded();
    }

    @Override
    protected boolean hasResourceHistory() {
        return true;
    }

    @Override
    protected void onResourceSnapshotRestored() {
        selectedLootEntryIndex = 0;
    }

    @Override
    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderLootGrid(context, previewX, previewY, previewWidth, previewHeight, mouseX, mouseY);
    }

    @Override
    protected List<String> editorFields() {
        return lootTableFields();
    }

    @Override
    protected List<ResourcePanelSection> editorSections(List<String> fields) {
        return appendRemainingSections(List.of(
            new ResourcePanelSection("", fields.stream().filter(field -> field.startsWith("trigger.")).toList()),
            new ResourcePanelSection("", fields.stream().filter(field -> field.startsWith("pools.")).toList()),
            new ResourcePanelSection("", fields.stream().filter(field -> field.startsWith("hooks.")).toList())
        ), fields);
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        if ("trigger.event".equals(field)) {
            return List.of("none", "block_break", "block_place", "entity_death", "item_use", "item_hit_entity", VAULT_OPEN_EVENT);
        }
        if ("trigger.entity".equals(field)) {
            return entityTargetOptions();
        }
        if ("trigger.target".equals(field) && VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent())) {
            return VAULT_TARGET_OPTIONS;
        }
        if ("trigger.target".equals(field) && "entity_death".equalsIgnoreCase(triggerEvent())) {
            return entityTargetOptions();
        }
        if ("trigger.target".equals(field) || ("trigger.tool".equals(field) && !entityTriggerEvent())) {
            return recipeItemOptions();
        }
        return null;
    }

    @Override
    protected String customSelectorCatalogSource(String field) {
        if ("trigger.entity".equals(field) || "trigger.target".equals(field) && "entity_death".equalsIgnoreCase(triggerEvent())) {
            return ENTITY_TYPE_OPTIONS_SOURCE;
        }
        if ("trigger.tool".equals(field) && entityTriggerEvent()) {
            return DAMAGE_TYPE_OPTIONS_SOURCE;
        }
        return null;
    }

    @Override
    protected boolean customDropdownField(String field) {
        return "trigger.event".equals(field)
            || "trigger.target".equals(field) && VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent());
    }

    @Override
    protected boolean toggleField(String field) {
        return super.toggleField(field) || "trigger.overrideDrops".equals(field);
    }

    @Override
    protected boolean defaultToggleValue(String field) {
        return "trigger.overrideDrops".equals(field) || super.defaultToggleValue(field);
    }

    @Override
    protected boolean customRecipeItemSelectorField(String field) {
        return field.matches("pools\\.\\d+\\.entries\\.\\d+\\.item")
            || ("trigger.tool".equals(field) && !entityTriggerEvent())
            || ("trigger.target".equals(field) && !triggerEventMatches(triggerEvent(), "entity_death", VAULT_OPEN_EVENT));
    }

    @Override
    protected void onDropdownSelectionChanged(String field, String value) {
        super.onDropdownSelectionChanged(field, value);
        if ("trigger.event".equals(field) && VAULT_OPEN_EVENT.equalsIgnoreCase(value)) {
            ensureVaultTriggerDefaults();
            return;
        }
        if ("trigger.target".equals(field) && VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent())) {
            String key = jsonPathText("trigger.tool");
            String nextKey = null;
            if ("ominous".equalsIgnoreCase(value)
                && (key.isBlank() || "none".equalsIgnoreCase(key) || NORMAL_VAULT_KEY.equalsIgnoreCase(key))) {
                nextKey = OMINOUS_VAULT_KEY;
            } else if ("normal".equalsIgnoreCase(value)
                && (key.isBlank() || "none".equalsIgnoreCase(key) || OMINOUS_VAULT_KEY.equalsIgnoreCase(key))) {
                nextKey = NORMAL_VAULT_KEY;
            } else if ("any".equalsIgnoreCase(value)
                && (NORMAL_VAULT_KEY.equalsIgnoreCase(key) || OMINOUS_VAULT_KEY.equalsIgnoreCase(key))) {
                nextKey = "";
            }
            if (nextKey != null) {
                putJsonPathText("trigger.tool", nextKey);
                refreshResourcePanelFields();
            }
        }
    }

    @Override
    protected AnimatedWidget customFieldRow(String field, String label, int rowWidth) {
        if (!"trigger.tool".equals(field) || !entityTriggerEvent()) {
            return null;
        }
        String selected = jsonPathText(field);
        AnimatedButton button = new AnimatedButton.Builder()
            .label(triggerToolSelectorLabel(selected))
            .size(174, 18)
            .entranceAnimation(false)
            .build();
        registerResourceSelectorButton(field, button);
        button.setAction(() -> showTriggerToolSelector(field, button.getX(), button.getY() + button.getHeight()));
        return studioPanelState.row(label, button, rowWidth, jsonResourceDescription(field, label));
    }

    @Override
    protected boolean remountPanelOnFieldReload() {
        return true;
    }

    @Override
    protected boolean customRebuildOnSelection(String field) {
        return "trigger.event".equals(field);
    }

    @Override
    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (event.button() == ReMouseButton.LEFT) {
            return handleLootGridClick(mouseX, mouseY);
        }
        return event.button() == ReMouseButton.RIGHT && handleLootGridRightClick(mouseX, mouseY);
    }

    @Override
    protected boolean handleResourceMouseScrolled(ReScrollEvent event) {
        return changeLootGridAmount((int) event.x(), (int) event.y(), event.verticalAmount());
    }


    @Override
    protected boolean handleResourceKeyPressed(ReKeyEvent event) {
        if ((event.key() == ReKey.DELETE || event.key() == ReKey.BACKSPACE) && !isStudioKeyboardInputFocused() && lootEntryCount() > 0) {
            deleteLootEntry(selectedLootEntryIndex());
            return true;
        }
        return super.handleResourceKeyPressed(event);
    }

    @Override
    protected String fieldLabel(String field) {
        return switch (field) {
            case "trigger.event" -> "Event";
            case "trigger.target" -> "Target";
            case "trigger.entity" -> "Entity";
            case "trigger.tool" -> VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent()) ? "Key" : "Tool";
            case "trigger.overrideDrops" -> "Override";
            default -> {
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.item")) {
                    yield "Item";
                }
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.minAmount")) {
                    yield "Min Amount";
                }
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.maxAmount")) {
                    yield "Max Amount";
                }
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.weight")) {
                    yield "Weight";
                }
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.chance")) {
                    yield "Chance";
                }
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.conditions")) {
                    yield "Conditions";
                }
                if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.components")) {
                    yield "Components";
                }
                yield super.fieldLabel(field);
            }
        };
    }

    @Override
    protected String jsonResourceDescription(String field, String label) {
        if (field == null) {
            return super.jsonResourceDescription(null, label);
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.item")) {
            return "Item produced when this entry is selected.\nSupports Minecraft items, ReSync items, and provider items.";
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.minAmount")) {
            return "Minimum stack size produced by this entry.\nMust be at least 1.";
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.maxAmount")) {
            return "Maximum stack size produced by this entry.\nMust be at least the minimum amount.";
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.weight")) {
            return "Relative selection chance within this pool.\nHigher values make this entry more likely when entries are selected by weight.\n0 excludes it from weighted selection.";
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.chance")) {
            return "Chance this entry is eligible on each roll.\nChecked before weighted selection.";
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.conditions")) {
            return "Conditions that must pass for this entry to be considered.\nSupports permission, world, player, entity type, chance, and nested all, any, or not checks.";
        }
        if (field.matches("pools\\.\\d+\\.entries\\.\\d+\\.components")) {
            return "Item metadata applied to the generated stack.\nSupports display name, lore, custom model data, and enchantments.";
        }
        return switch (field) {
            case "displayName" -> "Loot table name shown in project views and previews.";
            case "enabled" -> "Export state.\nOn: synchronized and active.\nOff: saved but not used live.";
            case "trigger.event" -> "Event that starts this loot table.\nChoose Vault Open to roll it when a matching vault begins opening.\nChoose None to disable event-based rolls.";
            case "trigger.target" -> {
                if (VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent())) {
                    yield "Vault category to match.\nNormal matches standard vaults.\nOminous matches ominous vaults.\nAny matches either category.";
                }
                if ("entity_death".equalsIgnoreCase(triggerEvent())) {
                    yield "Entity type that must die for this loot table to run.\nChoose None to match every entity type.";
                }
                if ("block_break".equalsIgnoreCase(triggerEvent())) {
                    yield "Block that must be broken to start this loot table.\nChoose None to match every block.";
                }
                if ("block_place".equalsIgnoreCase(triggerEvent())) {
                    yield "Block or item that must be placed to start this loot table.\nChoose None to match every placement.";
                }
                if ("item_use".equalsIgnoreCase(triggerEvent())) {
                    yield "Item that must be used to start this loot table.\nChoose None to accept any item.";
                }
                if ("item_hit_entity".equalsIgnoreCase(triggerEvent())) {
                    yield "Item that must hit the target entity.\nChoose None to accept any item.";
                }
                yield "Block or item that starts this loot table.\nChoose None to match every target.";
            }
            case "trigger.tool" -> {
                if (VAULT_OPEN_EVENT.equalsIgnoreCase(triggerEvent())) {
                    yield "Key item required by this vault trigger.\nChoose a Minecraft key, ReSync item, or provider item.\nLeave empty to use each vault category's standard key.";
                }
                if (entityTriggerEvent()) {
                    yield "Item or damage type required for this entity event.\nLeave empty to accept any item or damage type.";
                }
                yield "Item required for this loot event.\nLeave empty to accept any item.";
            }
            case "trigger.entity" -> "Entity type that must be hit for this loot table to run.\nChoose None to match every entity type.";
            case "trigger.overrideDrops" -> "Remove the event's original drops before applying this table's drops.\nApplies to block breaks and entity deaths.";
            case "pools.0.rolls" -> "Number of times this pool selects an entry.\nEach roll returns at most one entry.\nSet to 0 to disable the pool.";
            case "hooks.beforeRollFlow" -> "Flow run before this loot table is rolled.\nReceives the loot table id and available roll context.";
            case "hooks.afterRollFlow" -> "Flow run after this loot table is rolled.\nReceives the generated items and available roll context.";
            case "hooks.deniedRollFlow" -> "Flow run when a roll is requested for this disabled table.\nReceives the request context and no generated items.";
            default -> super.jsonResourceDescription(field, label);
        };
    }

    @Override
    protected String resourceSummary() {
        return firstFilled(jsonText("displayName"), "Loot Table");
    }

    @Override
    protected String resourceDisplayName() {
        return "Loot Table";
    }

    protected void renderLootGrid(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY) {
        int slotStride = LOOT_SLOT_SIZE + LOOT_SLOT_GAP;
        int columns = Math.clamp((previewWidth - LOOT_GRID_PADDING * 2 + LOOT_SLOT_GAP) / slotStride, 6, 18);
        int rows = Math.clamp((previewHeight - LOOT_GRID_PADDING * 2 + LOOT_SLOT_GAP) / slotStride, 3, 8);
        lootPreviewColumns = columns;
        lootPreviewRows = rows;
        int gridWidth = lootPreviewColumns * LOOT_SLOT_SIZE + Math.max(0, lootPreviewColumns - 1) * LOOT_SLOT_GAP;
        int gridHeight = lootPreviewRows * LOOT_SLOT_SIZE + Math.max(0, lootPreviewRows - 1) * LOOT_SLOT_GAP;
        lootPreviewX = previewX + Math.max(0, (previewWidth - gridWidth) / 2);
        lootPreviewY = previewY + Math.max(0, (previewHeight - gridHeight) / 2);
        renderLootGridContainer(context, gridWidth, gridHeight, mouseX, mouseY);
        int visibleSlots = lootPreviewColumns * lootPreviewRows;
        List<JsonObject> entries = firstLootEntries();
        int entryCount = entries.size();
        lootPreviewEntryOffset = Math.clamp(lootPreviewEntryOffset, 0, Math.max(0, entryCount - visibleSlots));
        selectedLootEntryIndex = entryCount <= 0 ? 0 : Math.clamp(selectedLootEntryIndex, 0, entryCount - 1);
        int selectedEntryIndex = entryCount > 0 ? selectedLootEntryIndex : -1;
        for (int slot = 0; slot < visibleSlots; slot++) {
            int x = lootPreviewX + (slot % lootPreviewColumns) * slotStride;
            int y = lootPreviewY + (slot / lootPreviewColumns) * slotStride;
            int entryIndex = lootPreviewEntryOffset + slot;
            JsonObject entry = entryIndex < entryCount ? entries.get(entryIndex) : null;
            AnimatedButton button = lootSlotButton(slot);
            button.setPosition(x, y);
            button.setSize(LOOT_SLOT_SIZE, LOOT_SLOT_SIZE);
            button.setSelected(entryIndex == selectedEntryIndex);
            button.setHovered(inside(mouseX, mouseY, x, y, LOOT_SLOT_SIZE, LOOT_SLOT_SIZE));
            button.renderWidget(context, mouseX, mouseY, 0);
            if (entry != null) {
                drawRecipeItem(context, jsonText(entry, "item"), parseInt(jsonText(entry, "maxAmount"), 1, 1, 64), x + 2, y + 2, 1);
            }
        }
        drawSelectedLootSlot(context);
    }

    protected void renderLootGridContainer(IDrawContext context, int gridWidth, int gridHeight, int mouseX, int mouseY) {
        lootGridContainer.setPosition(lootPreviewX - LOOT_GRID_PADDING, lootPreviewY - LOOT_GRID_PADDING);
        lootGridContainer.setSize(gridWidth + LOOT_GRID_PADDING * 2, gridHeight + LOOT_GRID_PADDING * 2);
        lootGridContainer.renderWidget(context, mouseX, mouseY, 0);
    }

    protected AnimatedButton lootSlotButton(int slot) {
        while (lootSlotButtons.size() <= slot) {
            lootSlotButtons.add(new AnimatedButton.Builder()
                .size(LOOT_SLOT_SIZE, LOOT_SLOT_SIZE)
                .flat(true)
                .animateElevation(false)
                .elevateOnFocused(false)
                .entranceAnimationStrength(0.25f)
                .setSelectable(true)
                .build());
        }
        return lootSlotButtons.get(slot);
    }

    protected void drawSelectedLootSlot(IDrawContext context) {
        if (lootEntryCount() <= 0) {
            return;
        }
        List<SlotInteractionGrid.SlotRect> rects = lootVisibleSlots().stream()
            .map(slot -> new SlotInteractionGrid.SlotRect(slot.entryIndex(), slot.rect().x(), slot.rect().y(), slot.rect().size()))
            .toList();
        Set<Integer> selected = slotCollaboration.isFollowing() ? Set.of() : Set.of(selectedLootEntryIndex());
        SlotInteractionGrid.drawCollaborativeHighlights(context, rects, Set.of(), selected, ThemeManager.getDefaultAccent().getAccentColor(),
            ThemeManager.getDefaultAccent().getAccentColor(), slotCollaboration, lootHighlightAnimationScope, "loot_slot", lootPreviewX, lootPreviewY);
    }

    @Override
    public JsonArray collaborationSlots() {
        JsonArray slots = new JsonArray();
        if (!slotCollaboration.isFollowing() && lootEntryCount() > 0) {
            slots.add(selectedLootEntryIndex());
        }
        return slots;
    }

    @Override
    public void applyCollaborationSlots(List<RemoteSlotSelection> selections) {
        slotCollaboration.apply(selections);
        Integer followed = slotCollaboration.followedSlot();
        if (followed != null && followed >= 0 && followed < lootEntryCount()) {
            selectedLootEntryIndex = followed;
        }
    }

    protected boolean handleLootGridClick(int mouseX, int mouseY) {
        LootSlot slot = lootSlotAt(mouseX, mouseY);
        if (slot == null) {
            return false;
        }
        slotCollaboration.markLocalInteraction();
        selectedLootEntryIndex = slot.entryIndex();
        ensureLootEntry(slot.entryIndex());
        lootHighlightNonce++;
        showRecipeMaterialSelector(lootEntryField(slot.entryIndex(), "item"), mouseX, mouseY);
        reloadFields();
        return true;
    }

    protected boolean handleLootGridRightClick(int mouseX, int mouseY) {
        LootSlot slot = lootSlotAt(mouseX, mouseY);
        if (slot == null || slot.entryIndex() >= lootEntryCount()) {
            return false;
        }
        deleteLootEntry(slot.entryIndex());
        return true;
    }

    protected boolean changeLootGridAmount(int mouseX, int mouseY, double verticalAmount) {
        LootSlot slot = lootSlotAt(mouseX, mouseY);
        if (slot == null) {
            return false;
        }
        if (slot.entryIndex() >= lootEntryCount()) {
            return scrollLootGrid(verticalAmount);
        }
        String amountField = lootEntryField(slot.entryIndex(), "maxAmount");
        int amount = parseInt(jsonPathText(amountField), 1, 1, 64);
        int next = Math.clamp(amount + (verticalAmount > 0 ? 1 : -1), 1, 64);
        if (next == amount) {
            return false;
        }
        captureResourceSnapshot();
        putJsonText(amountField, String.valueOf(next));
        selectedLootEntryIndex = slot.entryIndex();
        return true;
    }

    protected boolean scrollLootGrid(double verticalAmount) {
        int maxOffset = Math.max(0, lootEntryCount() - lootPreviewColumns * lootPreviewRows);
        if (maxOffset <= 0) {
            return false;
        }
        int next = Math.clamp(lootPreviewEntryOffset + (verticalAmount > 0 ? -lootPreviewColumns : lootPreviewColumns), 0, maxOffset);
        if (next == lootPreviewEntryOffset) {
            return false;
        }
        lootPreviewEntryOffset = next;
        return true;
    }

    protected LootSlot lootSlotAt(int mouseX, int mouseY) {
        for (LootSlot slot : lootVisibleSlots()) {
            if (slot.rect().contains(mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    protected List<LootSlot> lootVisibleSlots() {
        List<LootSlot> slots = new ArrayList<>();
        int slotStride = LOOT_SLOT_SIZE + LOOT_SLOT_GAP;
        int visibleSlots = lootPreviewColumns * lootPreviewRows;
        for (int slot = 0; slot < visibleSlots; slot++) {
            int x = lootPreviewX + (slot % lootPreviewColumns) * slotStride;
            int y = lootPreviewY + (slot / lootPreviewColumns) * slotStride;
            slots.add(new LootSlot(slot, lootPreviewEntryOffset + slot, new SlotInteractionGrid.SlotRect(slot, x, y, LOOT_SLOT_SIZE)));
        }
        return slots;
    }

    protected int selectedLootEntryIndex() {
        int count = lootEntryCount();
        if (count <= 0) {
            selectedLootEntryIndex = 0;
            return 0;
        }
        selectedLootEntryIndex = Math.clamp(selectedLootEntryIndex, 0, count - 1);
        return selectedLootEntryIndex;
    }

    protected int lootEntryCount() {
        return firstLootEntries().size();
    }

    protected void ensureLootEntry(int index) {
        if (index < 0) {
            return;
        }
        captureResourceSnapshot();
        JsonArray entries = firstLootEntryArray(true);
        while (entries.size() <= index) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", "minecraft:stone");
            entry.addProperty("minAmount", 1);
            entry.addProperty("maxAmount", 1);
            entry.addProperty("weight", 1);
            entry.addProperty("chance", 100);
            entries.add(entry);
        }
    }

    protected void deleteLootEntry(int index) {
        JsonArray entries = firstLootEntryArray(false);
        if (entries == null || index < 0 || index >= entries.size()) {
            return;
        }
        captureResourceSnapshot();
        entries.remove(index);
        selectedLootEntryIndex = Math.clamp(index, 0, Math.max(0, entries.size() - 1));
        lootPreviewEntryOffset = Math.clamp(lootPreviewEntryOffset, 0, Math.max(0, entries.size() - lootPreviewColumns * lootPreviewRows));
        reloadFields();
    }

    protected List<String> entityTargetOptions() {
        List<String> values = catalogOptions(ENTITY_TYPE_OPTIONS_SOURCE);
        List<String> options = new ArrayList<>();
        options.add("none");
        if (values.stream().anyMatch(this::isRealOption)) {
            options.addAll(values.stream().filter(this::isRealOption).distinct().toList());
        } else {
            options.addAll(FALLBACK_ENTITY_TYPE_OPTIONS);
        }
        return options;
    }

    protected List<String> damageTypeOptions() {
        List<String> values = catalogOptions(DAMAGE_TYPE_OPTIONS_SOURCE);
        List<String> options = new ArrayList<>();
        options.add("none");
        if (values.stream().anyMatch(this::isRealOption)) {
            options.addAll(values.stream().filter(this::isRealOption).distinct().toList());
        } else {
            options.addAll(FALLBACK_DAMAGE_TYPE_OPTIONS);
        }
        return options;
    }

    protected boolean entityTriggerEvent() {
        String event = triggerEvent();
        return "entity_death".equalsIgnoreCase(event) || "item_hit_entity".equalsIgnoreCase(event);
    }

    protected void showTriggerToolSelector(String field, int mouseX, int mouseY) {
        ensureRecipeItemCatalogLoaded();
        openTriggerToolSelector(field, mouseX, mouseY);
    }

    protected void openTriggerToolSelector(String field, int mouseX, int mouseY) {
        String selected = jsonPathText(field);
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Tools")
            .asyncItems(() -> ItemOptionCatalog.refresh(serverId), () -> triggerToolSnapshot(field))
            .build();
        showStudioSelector(selector, triggerToolSelectorLabel(selected), mouseX, mouseY);
    }

    private ItemSelectorWidget.AsyncItemSnapshot triggerToolSnapshot(String field) {
        String selected = jsonPathText(field);
        List<ItemSelectorWidget.AsyncItem> items = new ArrayList<>();
        items.add(new ItemSelectorWidget.AsyncItem("none", "", "none empty clear", () -> applyRecipeItemSelection(field, "none")));
        Set<String> damageTypes = new LinkedHashSet<>(damageTypeOptions());
        damageTypes.removeIf(value -> !isRealOption(value));
        for (String value : damageTypes) {
            String stored = damageTypeToolValue(value);
            items.add(new ItemSelectorWidget.AsyncItem(damageTypeSelectorLabel(value), "", "",
                value + " damage type " + formatOptionLabel(value), "Damage Types", () -> applyRecipeItemSelection(field, stored)));
        }
        ItemSelectorWidget.AsyncItemSnapshot itemSnapshot = ItemOptionCatalog.selectorSnapshot(serverId, () -> "", value -> applyRecipeItemSelection(field, value), false);
        items.addAll(itemSnapshot.items());
        List<String> itemValues = mergedRecipeItemValues();
        if (!selected.isBlank() && !isDamageTypeToolValue(selected) && !itemValues.contains(selected)) {
            items.add(new ItemSelectorWidget.AsyncItem(recipeItemSelectorLabel(selected), "", selected, () -> applyRecipeItemSelection(field, selected)));
        }
        return new ItemSelectorWidget.AsyncItemSnapshot(items, itemSnapshot.loading(), "No Tools");
    }

    protected String damageTypeToolValue(String value) {
        return "damage_type:" + value;
    }

    protected boolean isDamageTypeToolValue(String value) {
        return value != null && (value.startsWith("damage_type:") || value.startsWith("damage:"));
    }

    protected String damageTypeSelectorLabel(String value) {
        return formatOptionLabel(value) + " Damage";
    }

    protected String triggerToolSelectorLabel(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        if (value.startsWith("damage_type:")) {
            return damageTypeSelectorLabel(value.substring("damage_type:".length()));
        }
        if (value.startsWith("damage:")) {
            return damageTypeSelectorLabel(value.substring("damage:".length()));
        }
        return recipeItemSelectorLabel(value);
    }

    protected String triggerEvent() {
        return jsonPathText("trigger.event");
    }

    protected void ensureVaultTriggerDefaults() {
        String target = jsonPathText("trigger.target");
        if (VAULT_TARGET_OPTIONS.stream().noneMatch(option -> option.equalsIgnoreCase(target))) {
            putJsonPathText("trigger.target", VAULT_TARGET_OPTIONS.getFirst());
        }
        String key = jsonPathText("trigger.tool");
        if (key.isBlank() || "none".equalsIgnoreCase(key) || isDamageTypeToolValue(key)) {
            if ("normal".equalsIgnoreCase(jsonPathText("trigger.target"))) {
                putJsonPathText("trigger.tool", NORMAL_VAULT_KEY);
            } else if ("ominous".equalsIgnoreCase(jsonPathText("trigger.target"))) {
                putJsonPathText("trigger.tool", OMINOUS_VAULT_KEY);
            } else {
                putJsonPathText("trigger.tool", "");
            }
        }
    }

    protected void ensureTriggerObject() {
        if (resource.has("trigger") && resource.get("trigger").isJsonObject()) {
            resource.remove("links");
            return;
        }
        JsonObject trigger = new JsonObject();
        if (resource.has("links") && resource.get("links").isJsonArray() && !resource.getAsJsonArray("links").isEmpty() && resource.getAsJsonArray("links").get(0).isJsonObject()) {
            trigger = resource.getAsJsonArray("links").get(0).getAsJsonObject().deepCopy();
        }
        if (!trigger.has("event")) {
            trigger.addProperty("event", "none");
        }
        if (!trigger.has("target")) {
            trigger.addProperty("target", "");
        }
        if (!trigger.has("entity")) {
            trigger.addProperty("entity", "");
        }
        if (!trigger.has("tool")) {
            trigger.addProperty("tool", "");
        }
        if (!trigger.has("overrideDrops")) {
            trigger.addProperty("overrideDrops", true);
        }
        resource.add("trigger", trigger);
        resource.remove("links");
    }

    protected List<JsonObject> firstLootEntries() {
        JsonArray entries = firstLootEntryArray(false);
        if (entries == null) {
            return List.of();
        }
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (entry != null && entry.isJsonObject()) {
                result.add(entry.getAsJsonObject());
            }
        }
        return result;
    }

    protected JsonArray firstLootEntryArray(boolean create) {
        JsonArray pools = resource.has("pools") && resource.get("pools").isJsonArray() ? resource.getAsJsonArray("pools") : null;
        if (pools == null) {
            if (!create) {
                return null;
            }
            pools = new JsonArray();
            resource.add("pools", pools);
        }
        while (create && pools.isEmpty()) {
            JsonObject pool = new JsonObject();
            pool.addProperty("rolls", 1);
            pool.add("entries", new JsonArray());
            pools.add(pool);
        }
        if (pools.isEmpty() || !pools.get(0).isJsonObject()) {
            return null;
        }
        JsonObject pool = pools.get(0).getAsJsonObject();
        if (!pool.has("entries") || !pool.get("entries").isJsonArray()) {
            if (!create) {
                return null;
            }
            pool.add("entries", new JsonArray());
        }
        return pool.getAsJsonArray("entries");
    }

    protected String lootEntryField(int index, String field) {
        return "pools.0.entries." + index + "." + field;
    }

    protected List<String> lootTableFields() {
        String event = triggerEvent();
        List<String> fields = new ArrayList<>(List.of("displayName", "enabled", "trigger.event", "trigger.target"));
        if (triggerEventMatches(event, "block_break", "block_place", "entity_death", "item_hit_entity", VAULT_OPEN_EVENT)) {
            fields.add("trigger.tool");
        }
        if ("item_hit_entity".equalsIgnoreCase(event)) {
            fields.add("trigger.entity");
        }
        if (triggerEventMatches(event, "block_break", "entity_death")) {
            fields.add("trigger.overrideDrops");
        }
        fields.add("pools.0.rolls");
        if (lootEntryCount() > 0) {
            int index = selectedLootEntryIndex();
            fields.addAll(List.of(
                lootEntryField(index, "item"),
                lootEntryField(index, "minAmount"),
                lootEntryField(index, "maxAmount"),
                lootEntryField(index, "weight"),
                lootEntryField(index, "chance"),
                lootEntryField(index, "conditions"),
                lootEntryField(index, "components")
            ));
        }
        fields.addAll(List.of("hooks.beforeRollFlow", "hooks.afterRollFlow", "hooks.deniedRollFlow"));
        return fields;
    }

    protected boolean triggerEventMatches(String event, String... values) {
        if (event == null) {
            return false;
        }
        for (String value : values) {
            if (event.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

}
