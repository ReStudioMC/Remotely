package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.game.MinecraftAssetReference;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static redxax.oxy.remotely.flow.ui.GuiEditOverlayState.snapshot;

public class TradeDesignerScreen extends FocusedJsonResourceDesignerScreen implements CollaborativeSlotView {
    public TradeDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.TRADE_PROFILE, resourceId, resource, serverId, parent);
    }

    @Override
    protected void onResourceSnapshotRestored() {
        selectedTradeOfferIndex = 0;
    }

    @Override
    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderTradeRealPreview(context, previewX, previewY, previewWidth, previewHeight, text, muted);
    }

    @Override
    protected List<String> editorFields() {
        return tradeFields();
    }

    @Override
    protected List<ResourcePanelSection> editorSections(List<String> fields) {
        return appendRemainingSections(List.of(
            new ResourcePanelSection("", fields.stream().filter(field -> List.of("displayName", "profession", "villagerType", "level").contains(field)).toList()),
            new ResourcePanelSection("", fields.stream().filter(field -> field.startsWith("offers.") || List.of("maxUses", "restockTicks", "lootTable").contains(field)).toList()),
            new ResourcePanelSection("", fields.stream().filter(field -> field.startsWith("hooks.")).toList())
        ), fields);
    }

    @Override
    protected AnimatedWidget customFieldRow(String field, String label, int rowWidth) {
        return "level".equals(field) ? tradeLevelSliderRow(label, rowWidth) : null;
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        return switch (field) {
            case "profession" -> villagerProfessionOptions();
            case "villagerType" -> villagerTypeOptions();
            default -> null;
        };
    }

    @Override
    protected boolean customDropdownField(String field) {
        return "villagerType".equals(field);
    }

    @Override
    protected boolean customRecipeItemSelectorField(String field) {
        return field.matches("offers\\.\\d+\\.(cost|cost2|result)");
    }

    @Override
    protected String jsonResourceDescription(String field, String label) {
        if (field == null) {
            return super.jsonResourceDescription(null, label);
        }
        if (field.matches("offers\\.\\d+\\.weight")) {
            return "Relative selection chance for this offer.\nHigher values make it more likely when offers are selected by weight.\n0 excludes it from weighted selection.";
        }
        return switch (field) {
            case "displayName" -> "Merchant name shown at the top of the trade window.\nAlso used by virtual merchants that are not attached to a villager.";
            case "profession" -> "Villager profession and appearance.\nSets the merchant's role and the title shown in the preview.\nNone leaves the villager unemployed.";
            case "villagerType" -> "Villager biome appearance.\nChanges the merchant's clothing without changing its offers.";
            case "level" -> "Villager career level.\n1 is Novice and 5 is Master.\nChanges the badge shown beside the profession.";
            case "maxUses" -> "Default number of times each offer can be completed before it runs out.\nMust be at least 1.";
            case "restockTicks" -> "Time between trade restocks in ticks.\n20 ticks = 1 second.\n24000 ticks = one Minecraft day.";
            case "lootTable" -> "Loot table linked to this merchant.\nControls the items it can drop.";
            case "hooks.openAction" -> "Action run after a player opens this merchant's trade window.\nReceives the player, merchant, and trade profile.";
            case "hooks.completeAction" -> "Action run after a player completes a trade.\nReceives the player and the traded result item.";
            case "hooks.deniedAction" -> "Action run when the trade window cannot open or a trade cannot complete.\nUse it to explain the failure or provide another outcome.";
            default -> super.jsonResourceDescription(field, label);
        };
    }

    protected List<String> villagerProfessionOptions() {
        return List.of("none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith");
    }

    protected List<String> villagerTypeOptions() {
        return List.of("plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga");
    }

    @Override
    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (event.button() == ReMouseButton.LEFT && handleTradePreviewClick(mouseX, mouseY)) {
            return true;
        }
        return event.button() == ReMouseButton.RIGHT && handleTradePreviewRightClick(mouseX, mouseY);
    }

    @Override
    protected boolean handleResourceMouseScrolled(ReScrollEvent event) {
        return changeTradePreviewItemAmount((int) event.x(), (int) event.y(), event.verticalAmount());
    }


    @Override
    protected boolean handleResourceKeyPressed(ReKeyEvent event) {
        if ((event.key() == ReKey.DELETE || event.key() == ReKey.BACKSPACE) && !isStudioKeyboardInputFocused() && tradeOfferCount() > 0) {
            deleteSelectedTradeOffer();
            return true;
        }
        return super.handleResourceKeyPressed(event);
    }

    @Override
    protected CompactBindingSupport.FunctionShape runtimeFunctionShape(String functionBase) {
        return CompactBindingSupport.tradeActionShape();
    }

    @Override
    protected String defaultFunctionInputContext() {
        return "trade";
    }

    @Override
    protected String resourceSummary() {
        return firstFilled(jsonPathText("profession"), "Trade");
    }

    @Override
    protected String resourceDisplayName() {
        return "Trade";
    }

    protected int selectedTradeOfferIndex;
    protected int tradePreviewX;
    protected int tradePreviewY;
    protected int tradePreviewScale = 1;
    protected int tradePreviewOfferOffset;
    protected int tradePreviewVisibleOffers;
    protected int tradePreviewOfferScroll;
    protected int tradePreviewAddX;
    protected int tradePreviewAddY;
    protected int tradePreviewAddWidth;
    protected int tradePreviewAddHeight;
    protected final int tradeItemHighlightAnimationScope = SlotInteractionGrid.animationScope();
    protected final SlotCollaborationAuthority slotCollaboration = new SlotCollaborationAuthority();

    protected record TradeItemSlot(String field, SlotInteractionGrid.SlotRect rect) {
    }

    protected AnimatedWidget tradeLevelSliderRow(String label, int rowWidth) {
        int level = parseInt(jsonPathText("level"), 1, 1, 5);
        DoubleSliderWidget[] ref = new DoubleSliderWidget[1];
        DoubleSliderWidget slider = new DoubleSliderWidget.Builder()
            .label("Level " + level)
            .value((level - 1) / 4.0)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(() -> {
                DoubleSliderWidget widget = ref[0];
                if (widget == null) {
                    return;
                }
                int next = Math.clamp(1 + (int) Math.round(widget.getValue() * 4.0), 1, 5);
                widget.label = "Level " + next;
                putJsonText("level", String.valueOf(next));
            })
            .build();
        ref[0] = slider;
        ReSyncStudioPanelState.disableEntrance(slider);
        return studioPanelState.row(label, slider, rowWidth, jsonResourceDescription("level", label));
    }

    protected void renderTradeRealPreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int text, int muted) {
        MinecraftGameAssets gameAssets = getGameAssets();
        MinecraftAssetReference reference = gameAssets.asset("minecraft", "textures/gui/container/villager.png");
        boolean hasTexture = gameAssets.exists(reference);
        int atlasWidth = 512;
        int atlasHeight = 256;
        int viewTextureWidth = 276;
        int viewTextureHeight = 166;
        int scale = 1;
        int viewWidth = viewTextureWidth * scale;
        int viewHeight = viewTextureHeight * scale;
        int viewX = previewX + Math.max(0, (previewWidth - viewWidth) / 2);
        int viewY = previewY + Math.max(0, (previewHeight - viewHeight) / 2);
        tradePreviewX = viewX;
        tradePreviewY = viewY;
        tradePreviewScale = scale;
        if (hasTexture) {
            drawMinecraftTexture(context, gameAssets, reference, gameAssets.getImageId(reference), viewX, viewY, viewWidth, viewHeight, 0, 0, viewTextureWidth, viewTextureHeight, atlasWidth, atlasHeight);
        } else {
            context.fill(viewX, viewY, viewX + viewWidth, viewY + viewHeight, 0xFFE8CFA6);
            context.fill(viewX + 136 * scale, viewY, viewX + viewWidth, viewY + viewHeight, 0xFFE3D8C3);
        }
        drawMerchantPreviewTrades(context, gameAssets, viewX, viewY, scale, text, muted);
        drawMerchantPreviewSummary(context, viewX, viewY, scale, text, muted);
    }

    protected void drawMerchantPreviewTrades(IDrawContext context, MinecraftGameAssets gameAssets, int viewX, int viewY, int scale, int text, int muted) {
        List<JsonObject> offers = tradeOffers();
        int selected = selectedTradeOfferIndex();
        int offerSlots = 6;
        int maxOffset = Math.max(0, offers.size() - offerSlots);
        int offset = Math.clamp(tradePreviewOfferScroll, 0, maxOffset);
        int visibleOffers = Math.min(offerSlots, offers.size() - offset);
        tradePreviewOfferScroll = offset;
        tradePreviewOfferOffset = offset;
        tradePreviewVisibleOffers = Math.max(0, visibleOffers);
        for (int i = 0; i < visibleOffers; i++) {
            int offerIndex = offset + i;
            int buttonY = viewY + (18 + i * 20) * scale;
            int rowX = viewX + 5 * scale;
            MinecraftUiPreviewRenderer.drawButton(context, gameAssets, rowX, buttonY, 88 * scale, 20 * scale, "", offerIndex == selected);
        }
        drawTradeItemSlotHighlights(context);
        for (int i = 0; i < visibleOffers; i++) {
            int offerIndex = offset + i;
            JsonObject offer = offers.get(offerIndex);
            int buttonY = viewY + (18 + i * 20) * scale;
            int rowY = buttonY + scale;
            boolean disabled = !jsonText(offer, "enabled").isBlank() && !Boolean.parseBoolean(jsonText(offer, "enabled"));
            drawRecipeItem(context, firstFilled(jsonText(offer, "cost"), "minecraft:emerald"), parseInt(jsonText(offer, "costAmount"), 1, 1, 64), viewX + 10 * scale, rowY + 2 * scale, scale);
            String cost2 = jsonText(offer, "cost2");
            if (!cost2.isBlank()) {
                drawRecipeItem(context, cost2, parseInt(jsonText(offer, "cost2Amount"), 1, 1, 64), viewX + 40 * scale, rowY + 2 * scale, scale);
            }
            if (!drawMinecraftSprite(context, gameAssets, disabled ? "container/villager/trade_arrow_out_of_stock" : "container/villager/trade_arrow", viewX + 60 * scale, rowY + 3 * scale, 10 * scale, 9 * scale)) {
                context.drawText(">", viewX + 61 * scale, rowY + 5 * scale, disabled ? muted : text, false);
            }
            drawRecipeItem(context, firstFilled(jsonText(offer, "result"), "minecraft:book"), parseInt(jsonText(offer, "resultAmount"), 1, 1, 64), viewX + 73 * scale, rowY + 2 * scale, scale);
            if (disabled) {
                if (!drawMinecraftSprite(context, gameAssets, "container/villager/out_of_stock", viewX + 187 * scale, viewY + 35 * scale, 28 * scale, 21 * scale)) {
                    context.drawText("X", viewX + 196 * scale, viewY + 41 * scale, 0xFFFF5555, false);
                }
            }
        }
        int addSlot = visibleOffers;
        tradePreviewAddX = viewX + 5 * scale;
        tradePreviewAddY = viewY + (18 + addSlot * 20) * scale;
        tradePreviewAddWidth = 88 * scale;
        tradePreviewAddHeight = 20 * scale;
        MinecraftUiPreviewRenderer.drawButton(context, gameAssets, tradePreviewAddX, tradePreviewAddY, tradePreviewAddWidth, tradePreviewAddHeight, "Add Trade", false);
        if (offers.size() > offerSlots) {
            int scrollerY = viewY + (18 + (maxOffset > 0 ? Math.round((float) offset / maxOffset * 92) : 0)) * scale;
            drawMinecraftSprite(context, gameAssets, "container/villager/scroller", viewX + 94 * scale, scrollerY, 6 * scale, 27 * scale);
        } else {
            drawMinecraftSprite(context, gameAssets, "container/villager/scroller_disabled", viewX + 94 * scale, viewY + 18 * scale, 6 * scale, 27 * scale);
        }
    }

    protected void drawTradeItemSlotHighlights(IDrawContext context) {
        List<SlotInteractionGrid.SlotRect> rects = tradeVisibleItemSlots().stream().map(slot -> new SlotInteractionGrid.SlotRect(tradeOfferIndex(slot.field()),
            slot.rect().x(), slot.rect().y(), slot.rect().size())).toList();
        Set<Integer> selected = slotCollaboration.isFollowing() || tradeOfferCount() <= 0 ? Set.of() : Set.of(selectedTradeOfferIndex());
        SlotInteractionGrid.drawCollaborativeHighlights(context, rects, Set.of(), selected, 0xFF000000,
            ThemeManager.getDefaultAccent().getAccentColor(), slotCollaboration, tradeItemHighlightAnimationScope, "trade_item_slot",
            tradePreviewX + 49 * tradePreviewScale, tradePreviewY + 78 * tradePreviewScale);
    }

    @Override
    public JsonArray collaborationSlots() {
        JsonArray slots = new JsonArray();
        if (!slotCollaboration.isFollowing() && tradeOfferCount() > 0) {
            slots.add(selectedTradeOfferIndex());
        }
        return slots;
    }

    @Override
    public void applyCollaborationSlots(List<RemoteSlotSelection> selections) {
        slotCollaboration.apply(selections);
        Integer followed = slotCollaboration.followedSlot();
        if (followed != null && followed >= 0 && followed < tradeOfferCount()) {
            selectedTradeOfferIndex = followed;
        }
    }

    protected void drawMerchantPreviewSummary(IDrawContext context, int viewX, int viewY, int scale, int text, int muted) {
        String profession = formatOptionLabel(firstFilled(jsonPathText("profession"), "none"));
        String title = profession.equals("none") ? resourceDisplayName() : profession + " - " + tradeLevelName(parseInt(jsonPathText("level"), 1, 1, 5));
        int titleX = viewX + (49 + 138) * scale - textWidth(title) / 2;
        int tradesX = viewX + (5 + 48) * scale - textWidth("Trades") / 2;
        context.drawText(title, titleX, viewY + 6 * scale, 0xFF404040, false);
        context.drawText("Trades", tradesX, viewY + 6 * scale, 0xFF404040, false);
    }

    protected String tradeLevelName(int level) {
        return switch (Math.clamp(level, 1, 5)) {
            case 2 -> "Apprentice";
            case 3 -> "Journeyman";
            case 4 -> "Expert";
            case 5 -> "Master";
            default -> "Novice";
        };
    }

    protected List<JsonObject> tradeOffers() {
        JsonArray offers = resource.has("offers") && resource.get("offers").isJsonArray() ? resource.getAsJsonArray("offers") : new JsonArray();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement element : offers) {
            if (element != null && element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    protected List<JsonObject> editableTradeOffers() {
        JsonArray offers = tradeOfferArray(false);
        List<JsonObject> result = new ArrayList<>();
        if (offers == null) {
            return result;
        }
        for (JsonElement element : offers) {
            if (element != null && element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    protected int tradeOfferCount() {
        return editableTradeOffers().size();
    }

    protected int selectedTradeOfferIndex() {
        int count = tradeOfferCount();
        if (count <= 0) {
            selectedTradeOfferIndex = 0;
            return 0;
        }
        selectedTradeOfferIndex = Math.clamp(selectedTradeOfferIndex, 0, count - 1);
        return selectedTradeOfferIndex;
    }

    protected JsonArray tradeOfferArray(boolean create) {
        if (resource.has("offers") && resource.get("offers").isJsonArray()) {
            return resource.getAsJsonArray("offers");
        }
        if (!create) {
            return null;
        }
        JsonArray offers = new JsonArray();
        resource.add("offers", offers);
        return offers;
    }

    protected void addTradeOffer() {
        snapshot();
        JsonArray offers = tradeOfferArray(true);
        JsonObject offer = new JsonObject();
        offer.addProperty("cost", "minecraft:emerald");
        offer.addProperty("costAmount", 1);
        offer.addProperty("result", "minecraft:book");
        offer.addProperty("resultAmount", 1);
        offer.addProperty("weight", 1);
        offers.add(offer);
        selectedTradeOfferIndex = offers.size() - 1;
        tradePreviewOfferScroll = Math.max(0, offers.size() - 6);
        mountResourcePanel();
    }

    protected void deleteSelectedTradeOffer() {
        JsonArray offers = tradeOfferArray(false);
        if (offers == null || offers.size() == 0) {
            return;
        }
        snapshot();
        int index = selectedTradeOfferIndex();
        offers.remove(index);
        selectedTradeOfferIndex = Math.clamp(index, 0, Math.max(0, offers.size() - 1));
        tradePreviewOfferScroll = Math.clamp(tradePreviewOfferScroll, 0, Math.max(0, offers.size() - 6));
        mountResourcePanel();
    }

    protected String tradeOfferSummary(JsonObject offer, int index) {
        String cost = recipeItemSelectorLabel(firstFilled(jsonText(offer, "cost"), "minecraft:emerald"));
        String result = recipeItemSelectorLabel(firstFilled(jsonText(offer, "result"), "minecraft:book"));
        return "Trade " + (index + 1) + "  " + cost + " > " + result;
    }

    protected boolean handleTradePreviewClick(int mouseX, int mouseY) {
        slotCollaboration.markLocalInteraction();
        String itemField = tradePreviewItemFieldAt(mouseX, mouseY);
        if (!itemField.isBlank()) {
            selectedTradeOfferIndex = tradeOfferIndex(itemField);
            showRecipeMaterialSelector(itemField, mouseX, mouseY);
            return true;
        }
        if (inside(mouseX, mouseY, tradePreviewAddX, tradePreviewAddY, tradePreviewAddWidth, tradePreviewAddHeight)) {
            addTradeOffer();
            return true;
        }
        for (int i = 0; i < tradePreviewVisibleOffers; i++) {
            int rowX = tradePreviewX + 5 * tradePreviewScale;
            int rowY = tradePreviewY + (18 + i * 20) * tradePreviewScale;
            if (inside(mouseX, mouseY, rowX, rowY, 88 * tradePreviewScale, 20 * tradePreviewScale)) {
                selectedTradeOfferIndex = tradePreviewOfferOffset + i;
                mountResourcePanel();
                return true;
            }
        }
        return false;
    }

    protected boolean handleTradePreviewRightClick(int mouseX, int mouseY) {
        String itemField = tradePreviewItemFieldAt(mouseX, mouseY);
        if (itemField.isBlank()) {
            return false;
        }
        slotCollaboration.markLocalInteraction();
        captureResourceSnapshot();
        putJsonText(itemField, "");
        String amountField = tradePreviewAmountField(itemField);
        if (!amountField.isBlank()) {
            removeJsonPath(amountField);
        }
        selectedTradeOfferIndex = tradeOfferIndex(itemField);
        reloadFields();
        return true;
    }

    protected boolean changeTradePreviewItemAmount(int mouseX, int mouseY, double verticalAmount) {
        String itemField = tradePreviewItemFieldAt(mouseX, mouseY);
        if (itemField.isBlank()) {
            return changeTradePreviewTradeScroll(mouseX, mouseY, verticalAmount);
        }
        slotCollaboration.markLocalInteraction();
        if (jsonPathText(itemField).isBlank()) {
            return false;
        }
        String amountField = tradePreviewAmountField(itemField);
        if (amountField.isBlank()) {
            return false;
        }
        int amount = parseInt(jsonPathText(amountField), 1, 1, 64);
        int next = Math.clamp(amount + (verticalAmount > 0 ? 1 : -1), 1, 64);
        if (next == amount) {
            return false;
        }
        captureResourceSnapshot();
        putJsonText(amountField, String.valueOf(next));
        selectedTradeOfferIndex = tradeOfferIndex(itemField);
        return true;
    }

    protected boolean changeTradePreviewTradeScroll(int mouseX, int mouseY, double verticalAmount) {
        int maxOffset = Math.max(0, tradeOfferCount() - 6);
        if (maxOffset <= 0 || !inside(mouseX, mouseY, tradePreviewX + 5 * tradePreviewScale, tradePreviewY + 18 * tradePreviewScale, 96 * tradePreviewScale, 122 * tradePreviewScale)) {
            return false;
        }
        int next = Math.clamp(tradePreviewOfferScroll + (verticalAmount > 0 ? -1 : 1), 0, maxOffset);
        if (next == tradePreviewOfferScroll) {
            return false;
        }
        tradePreviewOfferScroll = next;
        return true;
    }

    protected String tradePreviewItemFieldAt(int mouseX, int mouseY) {
        for (TradeItemSlot slot : tradeVisibleItemSlots()) {
            if (slot.rect().contains(mouseX, mouseY)) {
                return slot.field();
            }
        }
        return "";
    }

    protected List<TradeItemSlot> tradeVisibleItemSlots() {
        List<TradeItemSlot> slots = new ArrayList<>();
        int inset = Math.max(1, tradePreviewScale);
        int size = 16 * tradePreviewScale + inset * 2;
        for (int i = 0; i < tradePreviewVisibleOffers; i++) {
            int offerIndex = tradePreviewOfferOffset + i;
            int rowY = tradePreviewY + (18 + i * 20) * tradePreviewScale + tradePreviewScale;
            addTradeItemSlot(slots, offerIndex, "cost", tradePreviewX + 10 * tradePreviewScale - inset, rowY + 2 * tradePreviewScale - inset, size);
            addTradeItemSlot(slots, offerIndex, "cost2", tradePreviewX + 40 * tradePreviewScale - inset, rowY + 2 * tradePreviewScale - inset, size);
            addTradeItemSlot(slots, offerIndex, "result", tradePreviewX + 73 * tradePreviewScale - inset, rowY + 2 * tradePreviewScale - inset, size);
        }
        return slots;
    }

    protected void addTradeItemSlot(List<TradeItemSlot> slots, int offerIndex, String key, int x, int y, int size) {
        String field = "offers." + offerIndex + "." + key;
        slots.add(new TradeItemSlot(field, new SlotInteractionGrid.SlotRect(field.hashCode(), x, y, size)));
    }

    protected int tradeOfferIndex(String field) {
        if (field == null || !field.startsWith("offers.")) {
            return selectedTradeOfferIndex();
        }
        String[] parts = field.split("\\.");
        return parts.length > 1 && isIndex(parts[1]) ? Integer.parseInt(parts[1]) : selectedTradeOfferIndex();
    }

    protected String tradePreviewAmountField(String field) {
        if (field == null) {
            return "";
        }
        if (field.endsWith(".cost")) {
            return field + "Amount";
        }
        if (field.endsWith(".cost2")) {
            return field + "Amount";
        }
        if (field.endsWith(".result")) {
            return field + "Amount";
        }
        return "";
    }

    protected List<String> tradeFields() {
        List<String> fields = new ArrayList<>(List.of("displayName", "profession", "villagerType", "level", "maxUses", "restockTicks", "lootTable"));
        if (tradeOfferCount() > 0) {
            int index = selectedTradeOfferIndex();
            fields.add("offers." + index + ".weight");
        }
        fields.addAll(List.of("hooks.openAction", "hooks.completeAction", "hooks.deniedAction"));
        return fields;
    }
}
