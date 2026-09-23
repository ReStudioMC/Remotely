package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.game.MinecraftAssetReference;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.game.MinecraftGameEntities;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.game.MinecraftRenderItem;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.UiTasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class NpcDesignerScreen extends FocusedJsonResourceDesignerScreen {
    private static final String ENTITY_TYPE_OPTIONS_SOURCE = "server:minecraft:entity_type";
    private static final long SKIN_RETRY_DELAY_MS = 60000L;
    private static final int SKIN_PREVIEW_CACHE_MAX_ENTRIES = 128;
    private static final Map<String, Identifier> SKIN_PREVIEW_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(SKIN_PREVIEW_CACHE_MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                if (size() <= SKIN_PREVIEW_CACHE_MAX_ENTRIES) {
                    return false;
                }
                ScreenManager.getInstance().imageAssets().releaseImage(eldest.getValue());
                return true;
            }
        }
    );
    private static final Map<String, Long> SKIN_PREVIEW_FAILURES = BrowserSafeState.map();
    private static final Set<String> SKIN_PREVIEW_FETCHING = BrowserSafeState.set();
    private static final Map<String, Identifier> EQUIPMENT_SLOT_TEXTURES = new LinkedHashMap<>();
    private static final List<String> FALLBACK_ENTITY_TYPE_OPTIONS = List.of(
        "allay", "armadillo", "armor_stand", "axolotl", "bat", "bee",
        "blaze", "bogged", "breeze", "camel", "cat", "cave_spider",
        "chicken", "cod", "copper_golem", "cow", "creaking", "creeper",
        "dolphin", "donkey", "drowned", "elder_guardian", "ender_dragon", "enderman",
        "endermite", "evoker", "fox", "frog", "ghast", "giant",
        "glow_squid", "goat", "guardian", "happy_ghast", "hoglin", "horse",
        "husk", "illusioner", "iron_golem", "llama", "magma_cube", "mooshroom",
        "mule", "ocelot", "panda", "parrot", "phantom", "pig",
        "piglin", "piglin_brute", "pillager", "player", "polar_bear", "pufferfish",
        "rabbit", "ravager", "salmon", "sheep", "shulker", "silverfish",
        "skeleton", "skeleton_horse", "slime", "sniffer", "snow_golem", "spider",
        "squid", "stray", "strider", "tadpole", "trader_llama", "tropical_fish",
        "turtle", "vex", "villager", "vindicator", "wandering_trader", "warden",
        "witch", "wither", "wither_skeleton", "wolf", "zoglin", "zombie",
        "zombie_horse", "zombie_villager", "zombified_piglin"
    );

    protected NpcEntityPreviewWidget npcEntityPreview;
    protected final Map<String, IconButton> npcEquipmentButtons = new LinkedHashMap<>();

    protected record NpcEquipmentControl(String field, String label, String slotTexture, String fallbackIcon) {
    }

    public NpcDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.NPC_DEFINITION, resourceId, resource, serverId, parent);
        ensureEntityTypeCatalogLoaded();
    }

    @Override
    protected int previewLeftReserve() {
        return host != null ? host.studioContentBrowserWidth() : 0;
    }

    @Override
    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderNpcRealPreview(context, previewX, previewY, previewWidth, previewHeight, mouseX, mouseY, text, muted);
    }

    @Override
    protected List<String> editorFields() {
        return npcFields();
    }

    @Override
    protected boolean resourcePanelSaveButtonVisible() {
        return false;
    }

    @Override
    protected void sanitizeLegacyResourceFields() {
        if (npcPlayerEntityType()) {
            resource.addProperty("ai", false);
        }
    }

    @Override
    protected AnimatedWidget customFieldRow(String field, String label, int rowWidth) {
        if ("followRange".equals(field)) {
            return npcFollowRangeSliderRow(label, rowWidth);
        }
        if ("entityType".equals(field)) {
            return entityTypeFieldRow(field, label, rowWidth);
        }
        return null;
    }

    @Override
    protected boolean remountPanelOnFieldReload() {
        return true;
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        return switch (field) {
            case "entityType" -> entityTypeOptions();
            default -> null;
        };
    }

    @Override
    protected String customSelectorCatalogSource(String field) {
        return switch (field) {
            case "entityType" -> ENTITY_TYPE_OPTIONS_SOURCE;
            default -> null;
        };
    }

    @Override
    protected boolean customRebuildOnSelection(String field) {
        return "entityType".equals(field);
    }

    @Override
    protected void onSelectorValueChanged(String field, String value) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) {
            return;
        }
        if ("dialog".equals(field) || "links.dialog".equals(field)) {
            removeJsonPath("tradeProfile");
            removeJsonPath("links.tradeProfile");
        } else if ("tradeProfile".equals(field) || "links.tradeProfile".equals(field)) {
            removeJsonPath("dialog");
            removeJsonPath("links.dialog");
        }
    }

    @Override
    protected boolean handleSpecialJsonTextWrite(String field, String value) {
        if (!"skin.username".equals(field) || value == null || value.isBlank()) {
            return false;
        }
        clearNpcSkinSources("skin.uuid", "skin.texture", "skin.signature", "skinUuid", "skinTexture", "skinSignature");
        putJsonPathText(field, value);
        return true;
    }

    private void clearNpcSkinSources(String... fields) {
        for (String field : fields) {
            removeJsonPath(field);
        }
    }

    @Override
    protected String fieldLabel(String field) {
        return "skin.username".equals(field) ? "Username" : super.fieldLabel(field);
    }

    @Override
    protected String jsonResourceDescription(String field, String label) {
        if (field == null) {
            return super.jsonResourceDescription(null, label);
        }
        return switch (field) {
            case "displayName" -> "Name shown above the NPC.\nAlso used in the designer preview.\nLeave empty to hide the name.";
            case "entityType" -> "Minecraft entity used for this NPC.\nPlayer NPCs use Username for their skin.";
            case "ai" -> "Normal mob behavior.\nOn: the NPC can move and act on its own.\nOff: mob AI stays disabled.";
            case "gravity" -> "Controls whether gravity moves the NPC.\nTurn off to keep it from falling when unsupported.";
            case "invulnerable" -> "Prevents damage from reducing the NPC's health.\nDamage actions still receive attempted hits.";
            case "followPlayer" -> "Turns the NPC toward the nearest player.\nThe NPC looks at them without walking toward them.";
            case "followRange" -> "Maximum distance for Look At Player.\nThe nearest player within this many blocks becomes the target.";
            case "skin.username" -> "Minecraft username used for the player skin.\nOnly applies when Entity is Player.\nLeave empty to use the default skin.";
            case "dialog" -> "Dialog opened when a player interacts with the NPC.\nChoosing a dialog clears the trade profile.";
            case "tradeProfile" -> "Trades opened when a player interacts with the NPC.\nVillagers receive the trades directly; other NPCs open them virtually.";
            case "lootTable" -> "Items dropped when the NPC dies.\nLinked loot replaces the entity's normal drops.";
            case "equipment.mainHand" -> "Item held in the NPC's main hand.";
            case "equipment.offHand" -> "Item held in the NPC's off hand.";
            case "equipment.helmet" -> "Item worn in the NPC's helmet slot.";
            case "equipment.chestplate" -> "Item worn in the NPC's chestplate slot.";
            case "equipment.leggings" -> "Item worn in the NPC's leggings slot.";
            case "equipment.boots" -> "Item worn in the NPC's boots slot.";
            case "hooks.spawnAction" -> "Action run after this NPC is summoned.\nReceives the NPC and summon location.";
            case "hooks.interactAction" -> "Action run for every player interaction.\nRuns for both left and right clicks.";
            case "hooks.rightClickAction" -> "Action run when a player right-clicks the NPC.";
            case "hooks.leftClickAction" -> "Action run when a player left-clicks the NPC.";
            case "hooks.damageAction" -> "Action run whenever the NPC receives a damage attempt.\nIncludes the damager, damage amount, cause, and cancelled state.";
            case "hooks.deathAction" -> "Action run when the NPC dies.\nIncludes the killer and generated drops.";
            case "hooks.despawnAction" -> "Action run when the NPC is removed with the despawn command.";
            default -> super.jsonResourceDescription(field, label);
        };
    }

    @Override
    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        ensureNpcEquipmentButtons();
        if (event.button() == ReMouseButton.RIGHT) {
            for (Map.Entry<String, IconButton> entry : npcEquipmentButtons.entrySet()) {
                if (!entry.getValue().isMouseOver(event.x(), event.y())) {
                    continue;
                }
                captureResourceSnapshot();
                putJsonText(entry.getKey(), "");
                refreshNpcEquipmentButtons();
                return true;
            }
        }
        for (IconButton button : npcEquipmentButtons.values()) {
            if (button.mouseClicked(event.retarget(button, event.x(), event.y()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void renderStudioOverlay(IDrawContext context, int mouseX, int mouseY, float delta) {
        for (IconButton button : npcEquipmentButtons.values()) {
            button.renderHintOverlay(context);
        }
        super.renderStudioOverlay(context, mouseX, mouseY, delta);
    }

    @Override
    protected String defaultFunctionInputContext() {
        return "npc";
    }

    @Override
    protected String resourceSummary() {
        return firstFilled(jsonPathText("entityType"), "NPC");
    }

    @Override
    protected String resourceDisplayName() {
        return "NPC";
    }

    protected AnimatedWidget entityTypeFieldRow(String field, String label, int rowWidth) {
        ensureEntityTypeCatalogLoaded();
        String selected = jsonPathText(field);
        AnimatedButton button = new AnimatedButton.Builder()
            .label(selectorLabel(field, selected))
            .size(174, 18)
            .entranceAnimation(false)
            .build();
        registerResourceSelectorButton(field, button);
        button.setAction(() -> openEntityTypeSelector(field, button.getX(), button.getY() + button.getHeight()));
        return studioPanelState.row(label, button, rowWidth, jsonResourceDescription(field, label));
    }

    protected AnimatedWidget npcFollowRangeSliderRow(String label, int rowWidth) {
        int range = parseInt(jsonPathText("followRange"), 12, 1, 64);
        DoubleSliderWidget[] ref = new DoubleSliderWidget[1];
        DoubleSliderWidget slider = new DoubleSliderWidget.Builder()
            .label("Range " + range)
            .value((range - 1) / 63.0)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(() -> {
                DoubleSliderWidget widget = ref[0];
                if (widget == null) {
                    return;
                }
                int next = Math.clamp(1 + (int) Math.round(widget.getValue() * 63.0), 1, 64);
                widget.label = "Range " + next;
                putJsonText("followRange", String.valueOf(next));
            })
            .build();
        ref[0] = slider;
        ReSyncStudioPanelState.disableEntrance(slider);
        return studioPanelState.row(label, slider, rowWidth, jsonResourceDescription("followRange", label));
    }

    protected List<String> entityTypeOptions() {
        ensureEntityTypeCatalogLoaded();
        List<String> values = OptionCatalogCache.getInstance().getValues(serverId, ENTITY_TYPE_OPTIONS_SOURCE);
        if (!values.isEmpty()) {
            LinkedHashSet<String> options = new LinkedHashSet<>(values);
            options.add("player");
            return new ArrayList<>(options);
        }
        return FALLBACK_ENTITY_TYPE_OPTIONS;
    }

    protected void ensureEntityTypeCatalogLoaded() {
        OptionCatalogLoader.preload(serverId, ENTITY_TYPE_OPTIONS_SOURCE);
    }

    protected void openEntityTypeSelector(String field, int mouseX, int mouseY) {
        String selected = jsonPathText(field);
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Entities")
            .asyncItems(OptionCatalogSelector.refreshAction(serverId, ENTITY_TYPE_OPTIONS_SOURCE),
                () -> OptionCatalogSelector.snapshot(serverId, ENTITY_TYPE_OPTIONS_SOURCE, Map.of(), this::entityTypeOptions,
                    () -> jsonPathText(field), value -> applyEntityTypeSelection(field, value), "No Entities"))
            .build();
        showStudioSelector(selector, OptionCatalogSelector.label(serverId, ENTITY_TYPE_OPTIONS_SOURCE, selected), mouseX, mouseY);
    }

    protected void applyEntityTypeSelection(String field, String value) {
        if (!isRealOption(value)) {
            return;
        }
        putJsonText(field, value);
        if ("minecraft:player".equals(normalizedNpcEntityType(value))) {
            putJsonPathText("ai", "false");
        }
        reloadFields();
    }

    protected void renderNpcRealPreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        if (npcEntityPreview == null) {
            npcEntityPreview = new NpcEntityPreviewWidget();
        }
        npcEntityPreview.setPosition(previewX, previewY);
        npcEntityPreview.setSize(previewWidth, previewHeight);
        npcEntityPreview.render(context, mouseX, mouseY, 0f);
        ensureNpcEquipmentButtons();
        layoutNpcEquipmentButtons(previewX, previewY, previewWidth, previewHeight);
        refreshNpcEquipmentButtons();
        for (IconButton button : npcEquipmentButtons.values()) {
            button.render(context, mouseX, mouseY, 0f);
        }
    }

    protected void ensureNpcEquipmentButtons() {
        if (!npcEquipmentButtons.isEmpty()) {
            return;
        }
        for (NpcEquipmentControl control : npcEquipmentControls()) {
            IconButton button = new IconButton.Builder()
                .identifier(npcEquipmentSlotTexture(control))
                .label("")
                .size(26, 26)
                .iconSize(16)
                .iconPadding(5)
                .active(false)
                .inClickableWhenInactive(true)
                .roundedCorners(false)
                .entranceAnimation(false)
                .hint(npcEquipmentHint(control, ""))
                .onClick(() -> showRecipeMaterialSelector(control.field(), buttonX(control.field()), buttonY(control.field())))
                .build();
            npcEquipmentButtons.put(control.field(), button);
        }
    }

    protected int buttonX(String field) {
        IconButton button = npcEquipmentButtons.get(field);
        return button != null ? button.getX() : 0;
    }

    protected int buttonY(String field) {
        IconButton button = npcEquipmentButtons.get(field);
        return button != null ? button.getY() + button.getHeight() : 0;
    }

    protected void layoutNpcEquipmentButtons(int previewX, int previewY, int previewWidth, int previewHeight) {
        int slotSize = Math.clamp(Math.min(previewWidth / 20, previewHeight / 18), 24, 26);
        int gap = 5;
        int centerX = previewX + previewWidth / 2;
        int centerY = previewY + previewHeight / 2;
        int armorX = Math.max(previewX + 14, centerX - 106);
        int armorTop = Math.max(previewY + 18, centerY - (slotSize * 4 + gap * 3) / 2);
        List<NpcEquipmentControl> controls = npcEquipmentControls();
        for (int index = 2; index < controls.size(); index++) {
            positionNpcEquipmentButton(controls.get(index).field(), armorX, armorTop + (index - 2) * (slotSize + gap), slotSize, slotSize);
        }
        int handY = Math.min(previewY + previewHeight - slotSize - 14, centerY + 58);
        positionNpcEquipmentButton(controls.get(0).field(), Math.max(previewX + 14, centerX - 70), handY, slotSize, slotSize);
        positionNpcEquipmentButton(controls.get(1).field(), Math.min(previewX + previewWidth - slotSize - 14, centerX + 44), handY, slotSize, slotSize);
    }

    protected void positionNpcEquipmentButton(String field, int x, int y, int width, int height) {
        IconButton button = npcEquipmentButtons.get(field);
        if (button == null) {
            return;
        }
        button.setPosition(x, y);
        button.setSize(width, height);
    }

    protected void refreshNpcEquipmentButtons() {
        for (NpcEquipmentControl control : npcEquipmentControls()) {
            IconButton button = npcEquipmentButtons.get(control.field());
            if (button == null) {
                continue;
            }
            String item = jsonPathText(control.field()).trim();
            MinecraftRenderItem preview = item.isBlank() ? null : ItemIconPreview.resolve(serverId, item).toRenderItem(recipeItemSelectorLabel(item));
            button.setIcon(npcEquipmentSlotTexture(control));
            button.setItemIcon(preview);
            button.setMessage("");
            button.setHint(npcEquipmentHint(control, item));
        }
    }

    protected String npcEquipmentHint(NpcEquipmentControl control, String item) {
        String state = item == null || item.isBlank() ? "Empty" : recipeItemSelectorLabel(item);
        return control.label() + " · " + state + "\n" + jsonResourceDescription(control.field(), control.label()) + "\nClick to choose. Right click to clear.";
    }

    protected List<NpcEquipmentControl> npcEquipmentControls() {
        return List.of(
            new NpcEquipmentControl("equipment.mainHand", "Main Hand", "sword", "item.png"),
            new NpcEquipmentControl("equipment.offHand", "Off Hand", "shield", "item.png"),
            new NpcEquipmentControl("equipment.helmet", "Helmet", "helmet", "armor.png"),
            new NpcEquipmentControl("equipment.chestplate", "Chestplate", "chestplate", "armor.png"),
            new NpcEquipmentControl("equipment.leggings", "Leggings", "leggings", "armor.png"),
            new NpcEquipmentControl("equipment.boots", "Boots", "boots", "armor.png")
        );
    }

    protected Identifier npcEquipmentSlotTexture(NpcEquipmentControl control) {
        MinecraftGameAssets assets = getGameAssets();
        synchronized (EQUIPMENT_SLOT_TEXTURES) {
            return EQUIPMENT_SLOT_TEXTURES.computeIfAbsent(control.slotTexture(), slot -> loadNpcEquipmentSlotTexture(assets, slot, control.fallbackIcon()));
        }
    }

    protected Identifier loadNpcEquipmentSlotTexture(MinecraftGameAssets assets, String slot, String fallbackIcon) {
        if (assets == null) {
            return Identifier.icon(fallbackIcon);
        }
        MinecraftAssetReference texture = assets.asset("minecraft", "textures/gui/sprites/container/slot/" + slot + ".png");
        Identifier id = assets.exists(texture) ? assets.getImageId(texture) : null;
        return id != null ? id : Identifier.icon(fallbackIcon);
    }

    protected void drawNpcEntityPreview(IDrawContext context, int x, int y, int size, int mouseX, int mouseY) {
        String entityType = normalizedNpcEntityType();
        String displayName = firstFilled(jsonPathText("displayName"), id);
        boolean baby = npcBaby();
        Map<String, Object> tag = npcEntityPreviewTag();
        float relativeMouseX = mouseX - (x + size / 2.0f);
        float relativeMouseY = mouseY - (y + size / 2.0f);
        if ("minecraft:player".equals(entityType)) {
            context.drawPlayerRelativeMousePreview(MinecraftGameEntities.of("minecraft:player", displayName, null, npcSkinPreview(), false, baby, tag), x, y, 20, size, relativeMouseX, relativeMouseY, false);
        } else {
            context.drawEntityRelativeMousePreview(MinecraftGameEntities.of(entityType, displayName, null, null, false, baby, tag), x, y, 20, size, relativeMouseX, relativeMouseY, false);
        }
    }

    protected boolean npcBaby() {
        String value = firstFilled(jsonPathText("baby"), jsonPathText("isBaby"), jsonPathText("IsBaby"));
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
    }

    private Identifier npcSkinPreview() {
        String username = jsonPathText("skin.username").trim();
        if (username.isBlank()) {
            return null;
        }
        String key = username.toLowerCase(Locale.ROOT);
        Identifier cached = SKIN_PREVIEW_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        long lastFailure = SKIN_PREVIEW_FAILURES.getOrDefault(key, 0L);
        if (lastFailure > 0L && System.currentTimeMillis() - lastFailure < SKIN_RETRY_DELAY_MS) {
            return null;
        }
        if (SKIN_PREVIEW_FETCHING.add(key)) {
            String fetchUsername = username;
            UiTasks.runBackground(() -> {
                Identifier skinId = ScreenManager.getInstance().imageAssets().registerRemoteImage("https://mc-heads.net/skin/" + sanitizeSkinName(fetchUsername));
                ScreenManager.getInstance().execute(() -> {
                    if (skinId != null) {
                        Identifier previous = SKIN_PREVIEW_CACHE.put(key, skinId);
                        if (previous != null && !skinId.equals(previous)) {
                            ScreenManager.getInstance().imageAssets().releaseImage(previous);
                        }
                        SKIN_PREVIEW_FAILURES.remove(key);
                    } else {
                        SKIN_PREVIEW_FAILURES.put(key, System.currentTimeMillis());
                    }
                    SKIN_PREVIEW_FETCHING.remove(key);
                });
            });
        }
        return null;
    }

    private String sanitizeSkinName(String value) {
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    protected Map<String, Object> npcEntityPreviewTag() {
        LinkedHashMap<String, Object> tag = new LinkedHashMap<>();
        LinkedHashMap<String, Object> equipment = new LinkedHashMap<>();
        putNpcEquipment(equipment, "mainHand", "equipment.mainHand");
        putNpcEquipment(equipment, "offHand", "equipment.offHand");
        putNpcEquipment(equipment, "helmet", "equipment.helmet");
        putNpcEquipment(equipment, "chestplate", "equipment.chestplate");
        putNpcEquipment(equipment, "leggings", "equipment.leggings");
        putNpcEquipment(equipment, "boots", "equipment.boots");
        if (!equipment.isEmpty()) {
            tag.put("equipment", Map.copyOf(equipment));
        }
        String skinUsername = jsonPathText("skin.username").trim();
        if (!skinUsername.isBlank()) {
            tag.put("skin", Map.of("username", skinUsername));
            tag.put("skinUsername", skinUsername);
        }
        return tag.isEmpty() ? Map.of() : Map.copyOf(tag);
    }

    protected void putNpcEquipment(Map<String, Object> equipment, String key, String field) {
        String item = jsonPathText(field).trim();
        if (!item.isBlank()) {
            MinecraftRenderItem preview = ItemIconPreview.resolve(serverId, item).toRenderItem(recipeItemSelectorLabel(item));
            equipment.put(key, preview != null ? preview : item);
        }
    }

    protected String normalizedNpcEntityType() {
        return normalizedNpcEntityType(jsonPathText("entityType"));
    }

    private String normalizedNpcEntityType(String value) {
        String entityType = value.trim();
        if (entityType.isBlank()) {
            entityType = "villager";
        }
        entityType = entityType.toLowerCase(Locale.ROOT).replace(' ', '_');
        return entityType.contains(":") ? entityType : "minecraft:" + entityType;
    }

    protected boolean npcPlayerEntityType() {
        return "minecraft:player".equals(normalizedNpcEntityType());
    }

    protected final class NpcEntityPreviewWidget extends AnimatedWidget {
        private NpcEntityPreviewWidget() {
            super(0, 0, 320, 240, "");
            animateElevation = false;
            entranceAnimationEnabled = false;
            enableHoverColors = false;
            roundedCorners = false;
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
            int size = Math.clamp(Math.min(getWidth(), getHeight()) - 48, 64, 220);
            int entityX = getX() + (getWidth() - size) / 2;
            int entityY = getY() + (getHeight() - size) / 2;
            drawNpcEntityPreview(context, entityX, entityY, size, mouseX, mouseY);
        }
    }

    protected List<String> npcFields() {
        List<String> fields = new ArrayList<>(List.of(
            "displayName", "entityType", "ai", "gravity", "invulnerable", "followPlayer", "followRange", "dialog", "tradeProfile", "lootTable", "hooks.spawnAction", "hooks.interactAction",
            "hooks.rightClickAction", "hooks.leftClickAction", "hooks.damageAction", "hooks.deathAction", "hooks.despawnAction"
        ));
        if (npcPlayerEntityType()) {
            fields.remove("ai");
            fields.add(2, "skin.username");
        }
        return fields;
    }
}
