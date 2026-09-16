package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.FlowDataType;
import restudio.resync.resource.RecipeSchema;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.RecipeSlotTarget;
import redxax.oxy.remotely.flow.ui.studio.RecipeStationLayout;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.game.MinecraftAssetReference;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.render.Render;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.CompactBindingWidget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class RecipeDesignerScreen extends FocusedJsonResourceDesignerScreen implements CollaborativeSlotView {
    protected int recipePreviewX;
    protected int recipePreviewY;
    protected int recipePreviewScale = 1;
    protected RecipeStationLayout recipePreviewLayout;
    protected String selectedRecipeField;
    protected String pressedRecipeField;
    protected String dragRecipeTargetField;
    protected final Set<String> dragRecipeTargetFields = new HashSet<>();
    protected SlotInteractionGrid.Stroke recipeStroke;
    protected int recipeHighlightOriginX;
    protected int recipeHighlightOriginY;
    protected final int recipeHighlightAnimationScope = SlotInteractionGrid.animationScope();
    protected int recipeHighlightPreviewNonce;
    protected int recipeHighlightSelectionNonce;
    protected RecipeStrokeMode recipeStrokeMode = RecipeStrokeMode.NONE;
    protected String recipeStrokeValue = "";
    protected String recipeBrushValue = "";
    protected List<String> pendingRecipeSelectionFields = new ArrayList<>();
    protected boolean draggingRecipeField;
    protected final SlotCollaborationAuthority slotCollaboration = new SlotCollaborationAuthority();
    protected final Map<String, String> recipeBindingDraftModes = new HashMap<>();

    protected enum RecipeStrokeMode {
        NONE,
        PAINT,
        ERASE,
        SELECT
    }

    public RecipeDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.RECIPE_DEFINITION, resourceId, resource, serverId, parent);
        ensureRecipeItemCatalogLoaded();
    }

    @Override
    protected boolean hasResourceHistory() {
        return true;
    }

    @Override
    protected void onResourceSnapshotRestored() {
        selectedRecipeField = null;
        pressedRecipeField = null;
        dragRecipeTargetField = null;
        dragRecipeTargetFields.clear();
        recipeStroke = null;
        recipeStrokeMode = RecipeStrokeMode.NONE;
        recipeStrokeValue = "";
        pendingRecipeSelectionFields = new ArrayList<>();
        draggingRecipeField = false;
        recipeBindingDraftModes.clear();
    }

    @Override
    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderRecipeRealPreview(context, previewX, previewY, previewWidth, previewHeight, text, muted);
    }

    @Override
    protected List<String> editorFields() {
        return recipeFields();
    }

    protected boolean recipeBindingField(String field) {
        return switch (field) {
            case "craftedBinding", "cookedBinding", "conditionBinding", "deniedBinding" -> true;
            default -> false;
        };
    }

    @Override
    protected AnimatedWidget customFieldRow(String field, String label, int rowWidth) {
        return recipeBindingField(field) ? recipeBindingRow(field, label, rowWidth) : null;
    }

    @Override
    protected boolean remountPanelOnFieldReload() {
        return true;
    }

    @Override
    protected boolean supportsRecipeItemPathFields() {
        return true;
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        return switch (field) {
            case "type" -> recipeTypeOptions();
            case "output.material", "template.material", "base.material", "addition.material" -> recipeItemOptions();
            default -> null;
        };
    }

    @Override
    protected boolean customDropdownField(String field) {
        return "type".equals(field);
    }

    @Override
    protected boolean customRebuildOnSelection(String field) {
        return "type".equals(field);
    }

    @Override
    protected boolean customRecipeItemSelectorField(String field) {
        return List.of("output.material", "template.material", "base.material", "addition.material").contains(field)
            || recipeSlotIndex(field) >= 0 || recipeIngredientIndex(field) >= 0;
    }

    @Override
    protected void onStudioSelectorClosed() {
        pendingRecipeSelectionFields = new ArrayList<>();
    }

    @Override
    protected void applyRecipeItemSelection(String field, String value) {
        if (!isRealOption(value)) {
            return;
        }
        String selection = value == null || "none".equalsIgnoreCase(value) ? "" : value;
        recipeBrushValue = selection;
        if (!pendingRecipeSelectionFields.isEmpty()) {
            captureResourceSnapshot();
            resourceEditHistoryBatch = true;
            try {
                for (String pendingField : pendingRecipeSelectionFields) {
                    putJsonText(pendingField, selection);
                }
            } finally {
                resourceEditHistoryBatch = false;
                pendingRecipeSelectionFields = new ArrayList<>();
            }
        } else {
            putJsonText(field, selection);
        }
        reloadFields();
    }

    @Override
    protected boolean handleSpecialJsonTextWrite(String field, String value) {
        if (!"type".equals(field)) {
            return false;
        }
        String nextType = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        resource.addProperty("type", nextType);
        normalizeRecipeSchema(nextType);
        return true;
    }

    @Override
    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        int button = recipeButton(event);
        return button != -1 && handleRecipePreviewClick((int) event.x(), (int) event.y(), button);
    }

    @Override
    protected boolean handleResourceMouseReleased(ReMouseEvent event) {
        return recipeButton(event) != -1 && handleRecipePreviewRelease((int) event.x(), (int) event.y());
    }

    @Override
    protected boolean handleResourceMouseDragged(ReMouseEvent event) {
        return recipeButton(event) != -1 && handleRecipePreviewDrag((int) event.x(), (int) event.y());
    }

    @Override
    protected boolean handleResourceMouseScrolled(ReScrollEvent event) {
        return changeRecipeItemAmount((int) event.x(), (int) event.y(), event.verticalAmount());
    }

    @Override
    protected boolean handleResourceKeyPressed(ReKeyEvent event) {
        return handleStudioHistoryShortcut(event);
    }

    private int recipeButton(ReMouseEvent event) {
        if (event.button() == ReMouseButton.LEFT) {
            return ReMouseButton.LEFT.code();
        }
        if (event.button() == ReMouseButton.RIGHT) {
            return ReMouseButton.RIGHT.code();
        }
        return -1;
    }

    @Override
    protected boolean flowBindingField(String field) {
        return false;
    }

    @Override
    protected String resourceSummary() {
        return firstFilled(jsonText(jsonObject("output"), "material"), "Crafting");
    }

    @Override
    protected String resourceDisplayName() {
        return "Recipe";
    }

    protected AnimatedWidget recipeBindingRow(String field, String label, int rowWidth) {
        String flowField = recipeBindingFlowField(field);
        String functionBase = recipeBindingFunctionBase(field);
        String commandField = recipeBindingCommandField(field);
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            hostScreen(),
            recipeBindingModes(flowField, commandField),
            () -> recipeBindingMode(field, flowField, functionBase, commandField),
            mode -> {
                if ("None".equals(mode)) {
                    recipeBindingDraftModes.remove(field);
                } else {
                    recipeBindingDraftModes.put(field, mode);
                }
                if ("None".equals(mode)) {
                    if (!flowField.isBlank()) {
                        putJsonText(flowField, "");
                    }
                    if (!commandField.isBlank()) {
                        putJsonArrayLines(commandField, "");
                    }
                    putJsonText(functionBase + ".functionId", "");
                } else if ("Run Flow".equals(mode)) {
                    putJsonText(functionBase + ".functionId", "");
                    if (!commandField.isBlank()) {
                        putJsonArrayLines(commandField, "");
                    }
                    if (!flowField.isBlank() && !jsonPathHas(flowField)) {
                        ensureJsonPathText(flowField, "");
                    }
                } else if ("Run Function".equals(mode) || "Function".equals(mode)) {
                    if (!flowField.isBlank()) {
                        putJsonText(flowField, "");
                    }
                    if (!commandField.isBlank()) {
                        putJsonArrayLines(commandField, "");
                    }
                    if (!jsonPathHas(functionBase + ".functionId")) {
                        ensureFunctionCall(functionBase);
                    }
                } else if ("Run Command".equals(mode)) {
                    if (!flowField.isBlank()) {
                        putJsonText(flowField, "");
                    }
                    putJsonText(functionBase + ".functionId", "");
                    if (!commandField.isBlank() && !resource.has(commandField)) {
                        resource.add(commandField, new JsonArray());
                    }
                }
                refreshResourcePanelFields();
            },
            () -> "Run Function".equals(recipeBindingMode(field, flowField, functionBase, commandField)) || "Function".equals(recipeBindingMode(field, flowField, functionBase, commandField)) ? functionOptions() : "Run Flow".equals(recipeBindingMode(field, flowField, functionBase, commandField)) ? flowOptions() : List.of("none"),
            () -> {
                String mode = recipeBindingMode(field, flowField, functionBase, commandField);
                return "Run Function".equals(mode) || "Function".equals(mode) ? jsonPathTextRaw(functionBase + ".functionId") : "Run Flow".equals(mode) ? jsonPathTextRaw(flowField) : "Run Command".equals(mode) ? "Command" : "";
            },
            value -> {
                String mode = recipeBindingMode(field, flowField, functionBase, commandField);
                if ("Run Function".equals(mode) || "Function".equals(mode)) {
                    putJsonText(functionBase + ".functionId", value);
                } else if ("Run Flow".equals(mode) && !flowField.isBlank()) {
                    putJsonText(flowField, value);
                }
                refreshResourcePanelFields();
            },
            () -> compactRecipeBindingInputs(field, flowField, functionBase, commandField),
            () -> openRecipeBinding(field, functionBase, flowField, commandField)
        )
            .createAction("Create New", () -> "Run Function".equals(recipeBindingMode(field, flowField, functionBase, commandField)) || "Function".equals(recipeBindingMode(field, flowField, functionBase, commandField)) || "Run Flow".equals(recipeBindingMode(field, flowField, functionBase, commandField)), () -> createRecipeBindingTarget(field, flowField, functionBase, commandField))
            .animationKey("json." + type + "." + field)
            .size(rowWidth, 18)
            .entranceAnimation(false)
            .build();
        registerResourceBindingWidget(widget);
        ReSyncStudioPanelState.disableEntrance(widget);
        return studioPanelState.row(label, widget, rowWidth, jsonResourceDescription(field, label));
    }

    protected String recipeBindingFlowField(String field) {
        return switch (field) {
            case "craftedBinding" -> "craftedFlow";
            case "cookedBinding" -> "cookedFlow";
            case "deniedBinding" -> "deniedFlow";
            default -> "";
        };
    }

    protected String recipeBindingFunctionBase(String field) {
        return switch (field) {
            case "craftedBinding" -> "craftedAction";
            case "cookedBinding" -> "cookedAction";
            case "conditionBinding" -> "conditions.predicate";
            case "deniedBinding" -> "deniedAction";
            default -> "";
        };
    }

    protected String recipeBindingCommandField(String field) {
        return switch (field) {
            case "craftedBinding" -> "craftedCommands";
            case "cookedBinding" -> "cookedCommands";
            case "deniedBinding" -> "deniedCommands";
            default -> "";
        };
    }

    protected List<String> recipeBindingModes(String flowField, String commandField) {
        if (flowField.isBlank() && commandField.isBlank()) {
            return CompactBindingSupport.PREDICATE_MODES;
        }
        return CompactBindingSupport.ACTION_MODES;
    }

    protected String recipeBindingMode(String field, String flowField, String functionBase, String commandField) {
        if (hasConfiguredJsonText(functionBase + ".functionId")) {
            return flowField.isBlank() && commandField.isBlank() ? "Function" : "Run Function";
        }
        if (!flowField.isBlank() && hasConfiguredJsonText(flowField)) {
            return "Run Flow";
        }
        if (!commandField.isBlank() && hasConfiguredJsonArray(commandField)) {
            return "Run Command";
        }
        String draftMode = recipeBindingDraftModes.get(field);
        return draftMode != null && recipeBindingModes(flowField, commandField).contains(draftMode) ? draftMode : "None";
    }

    protected List<CompactBindingWidget.BindingInput> compactRecipeBindingInputs(String bindingField, String flowField, String functionBase, String commandField) {
        if (!commandField.isBlank() && "Run Command".equals(recipeBindingMode(bindingField, flowField, functionBase, commandField))) {
            return List.of(new CompactBindingWidget.BindingInput(
                "commands",
                "Commands",
                jsonArrayLines(commandField),
                FlowDataType.STRING.getColor(),
                null,
                value -> putJsonArrayLines(commandField, value),
                CompactBindingWidget.InputKind.COMMAND
            ));
        }
        FlowGraph function = selectedFunction(functionBase + ".functionId", recipeFunctionShape(functionBase));
        if (function == null || function.getFunctionInputs() == null || function.getFunctionInputs().isEmpty()) {
            return List.of();
        }
        List<CompactBindingWidget.BindingInput> inputs = new ArrayList<>();
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input == null || input.getName() == null || input.getName().isBlank()) {
                continue;
            }
            String field = functionBase + ".inputs." + input.getName();
            inputs.add(new CompactBindingWidget.BindingInput(
                input.getName(),
                functionInputLabel(input.getName()),
                jsonPathText(field),
                input.getType() != null ? input.getType().getColor() : FlowDataType.ANY.getColor(),
                () -> functionInputOptions(field, input),
                value -> putJsonText(field, value),
                input.getType() != null && FlowDataType.BOOLEAN.isAssignableFrom(input.getType()) ? CompactBindingWidget.InputKind.BOOLEAN : CompactBindingWidget.InputKind.TEXT,
                () -> CompactBindingSupport.functionInputChoices(serverId, input, functionInputOptions(field, input))
            ));
        }
        return inputs;
    }

    protected void openRecipeBinding(String field, String functionBase, String flowField, String commandField) {
        String mode = recipeBindingMode(field, flowField, functionBase, commandField);
        String id = "Run Function".equals(mode) || "Function".equals(mode) ? jsonPathTextRaw(functionBase + ".functionId") : "Run Flow".equals(mode) ? jsonPathTextRaw(flowField) : "";
        if (!id.isBlank()) {
            if (host != null) {
                host.openWorkspaceResource("Run Function".equals(mode) || "Function".equals(mode)
                    ? ReSyncResourceDragPayload.FUNCTION : ReSyncResourceDragPayload.FLOW, id);
            }
        }
    }

    protected void createRecipeBindingTarget(String field, String flowField, String functionBase, String commandField) {
        String mode = recipeBindingMode(field, flowField, functionBase, commandField);
        if ("Run Function".equals(mode) || "Function".equals(mode)) {
            createBindingResource(ReSyncResourceDragPayload.FUNCTION, id -> {
                normalizeBindingFunction(id, recipeFunctionShape(functionBase));
                putJsonText(functionBase + ".functionId", id);
                refreshResourcePanelFields();
            });
        } else if ("Run Flow".equals(mode) && !flowField.isBlank()) {
            createBindingResource(ReSyncResourceDragPayload.FLOW, id -> {
                putJsonText(flowField, id);
                refreshResourcePanelFields();
            });
        }
    }

    protected CompactBindingSupport.FunctionShape recipeFunctionShape(String functionBase) {
        return functionBase != null && functionBase.contains("predicate")
            ? CompactBindingSupport.recipePredicateShape()
            : CompactBindingSupport.recipeActionShape();
    }

    protected void renderRecipeRealPreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int text, int muted) {
        String recipeType = normalizedRecipeType();
        RecipeStationLayout layout = recipeStationLayout(recipeType);
        MinecraftGameAssets gameAssets = getGameAssets();
        MinecraftAssetReference reference = layout.hasTexture() ? gameAssets.containerTexture(layout.texture()) : null;
        boolean hasTexture = reference != null && gameAssets.exists(reference);
        int textureWidth = layout.fallbackWidth();
        int textureHeight = layout.fallbackHeight();
        int scale = 1;
        int viewWidth = textureWidth * scale;
        int viewHeight = textureHeight * scale;
        int viewX = previewX + Math.max(0, (previewWidth - viewWidth) / 2);
        int viewY = previewY + Math.max(0, (previewHeight - viewHeight) / 2);
        recipePreviewX = viewX;
        recipePreviewY = viewY;
        recipePreviewScale = scale;
        recipePreviewLayout = layout;
        if (hasTexture) {
            drawMinecraftTexture(context, gameAssets, reference, gameAssets.getImageId(reference), viewX, viewY, viewWidth, viewHeight, 0, 0, textureWidth, textureHeight, 256, 256);
        } else {
            drawRecipeFallbackPanel(context, layout, viewX, viewY, viewWidth, viewHeight, muted, scale);
        }
        drawRecipeSlotHighlights(context, recipeType, layout, viewX, viewY, scale);
        drawRecipeStationItems(context, recipeType, layout, viewX, viewY, scale);
    }

    protected void drawRecipeFallbackPanel(IDrawContext context, RecipeStationLayout layout, int viewX, int viewY, int viewWidth, int viewHeight, int muted, int scale) {
        int border = ThemeManager.getColor(ThemeColor.innerBorder);
        for (RecipeSlotTarget target : recipeSlotTargets(normalizedRecipeType(), layout)) {
            int[] point = target.point();
            if (point == null || point.length < 2) {
                continue;
            }
            int left = viewX + point[0] * scale;
            int top = viewY + point[1] * scale;
            int size = 16 * scale;
            Render.drawLayeredInnerBorder(context, left, top, size, size, ThemeManager.getColor(ThemeColor.elementBackground), border);
        }
        if (layout.ingredients().length > 0 && layout.output() != null && layout.output().length >= 2) {
            int[] input = layout.ingredients()[0];
            int[] output = layout.output();
            int centerY = viewY + (input[1] * scale) + 8 * scale;
            int lineHeight = Math.max(1, scale);
            int startX = viewX + (input[0] + 24) * scale;
            int endX = viewX + (output[0] - 8) * scale;
            context.fill(startX, centerY, endX, centerY + lineHeight, muted);
        }
    }

    protected boolean handleRecipePreviewClick(int mouseX, int mouseY, int button) {
        if (recipePreviewLayout == null || recipePreviewScale <= 0) {
            return false;
        }
        String field = recipeFieldAt(mouseX, mouseY);
        if (field.isBlank()) {
            return false;
        }
        slotCollaboration.markLocalInteraction();
        recipeHighlightOriginX = mouseX;
        recipeHighlightOriginY = mouseY;
        recipeHighlightPreviewNonce++;
        recipeHighlightSelectionNonce++;
        selectedRecipeField = field;
        recipeStroke = SlotInteractionGrid.beginStroke(recipeSlotRects(), mouseX, mouseY);
        updateRecipeStrokeTargets();
        if (button == ReMouseButton.RIGHT.code()) {
            recipeStrokeMode = RecipeStrokeMode.ERASE;
            pressedRecipeField = field;
            dragRecipeTargetField = field;
            draggingRecipeField = false;
            return true;
        }
        if (button == ReMouseButton.LEFT.code()) {
            pressedRecipeField = field;
            dragRecipeTargetField = field;
            draggingRecipeField = false;
            String fieldValue = jsonPathText(field);
            if (!fieldValue.isBlank()) {
                recipeBrushValue = fieldValue;
            }
            recipeStrokeValue = !fieldValue.isBlank() ? fieldValue : recipeBrushValue;
            recipeStrokeMode = recipeStrokeValue.isBlank() ? RecipeStrokeMode.SELECT : RecipeStrokeMode.PAINT;
            return true;
        }
        return false;
    }

    protected boolean handleRecipePreviewDrag(int mouseX, int mouseY) {
        if (pressedRecipeField == null || pressedRecipeField.isBlank()) {
            return false;
        }
        if (recipeStroke != null) {
            recipeStroke.moveTo(mouseX, mouseY);
            updateRecipeStrokeTargets();
        }
        String field = recipeFieldAt(mouseX, mouseY);
        if (field.isBlank()) {
            dragRecipeTargetField = null;
            return true;
        }
        dragRecipeTargetField = field;
        if (!Objects.equals(field, pressedRecipeField) || (recipeStroke != null && recipeStroke.slots().size() > 1)) {
            draggingRecipeField = true;
        }
        return true;
    }

    protected boolean handleRecipePreviewRelease(int mouseX, int mouseY) {
        if (pressedRecipeField == null || pressedRecipeField.isBlank()) {
            return false;
        }
        String source = pressedRecipeField;
        String target = recipeFieldAt(mouseX, mouseY);
        boolean wasDragging = draggingRecipeField;
        SlotInteractionGrid.Stroke finishedStroke = recipeStroke;
        RecipeStrokeMode finishedMode = recipeStrokeMode;
        String finishedValue = recipeStrokeValue;
        pressedRecipeField = null;
        dragRecipeTargetField = null;
        dragRecipeTargetFields.clear();
        recipeStroke = null;
        recipeStrokeMode = RecipeStrokeMode.NONE;
        recipeStrokeValue = "";
        draggingRecipeField = false;
        if (finishedMode == RecipeStrokeMode.ERASE) {
            commitRecipeStroke(finishedStroke, "", true);
            selectedRecipeField = null;
            recipeHighlightSelectionNonce++;
            reloadFields();
            return true;
        }
        if (finishedMode == RecipeStrokeMode.SELECT) {
            pendingRecipeSelectionFields = recipeStrokeFields(finishedStroke);
            showRecipeMaterialSelector(source, mouseX, mouseY);
            return true;
        }
        if (wasDragging && target.isBlank()) {
            return true;
        }
        if (wasDragging && finishedMode == RecipeStrokeMode.PAINT && !finishedValue.isBlank()) {
            commitRecipeStroke(finishedStroke, finishedValue, false);
            recipeBrushValue = finishedValue;
            selectedRecipeField = target.isBlank() ? source : target;
            recipeHighlightSelectionNonce++;
            reloadFields();
            return true;
        }
        showRecipeMaterialSelector(source, mouseX, mouseY);
        return true;
    }

    protected void updateRecipeStrokeTargets() {
        dragRecipeTargetFields.clear();
        if (recipeStroke == null || recipePreviewLayout == null) {
            return;
        }
        List<RecipeSlotTarget> targets = recipeSlotTargets(normalizedRecipeType(), recipePreviewLayout);
        for (int slot : recipeStroke.slots()) {
            if (slot >= 0 && slot < targets.size()) {
                dragRecipeTargetFields.add(targets.get(slot).field());
            }
        }
    }

    protected void commitRecipeStroke(SlotInteractionGrid.Stroke stroke, String value, boolean erase) {
        List<String> fields = recipeStrokeFields(stroke);
        if (fields.isEmpty()) {
            return;
        }
        captureResourceSnapshot();
        resourceEditHistoryBatch = true;
        try {
            for (String field : fields) {
                if (erase) {
                    deleteRecipeField(field);
                } else if (value != null && !value.isBlank()) {
                    putJsonText(field, value);
                }
            }
        } finally {
            resourceEditHistoryBatch = false;
        }
    }

    protected List<String> recipeStrokeFields(SlotInteractionGrid.Stroke stroke) {
        if (stroke == null || stroke.isEmpty() || recipePreviewLayout == null) {
            return List.of();
        }
        List<RecipeSlotTarget> targets = recipeSlotTargets(normalizedRecipeType(), recipePreviewLayout);
        List<String> fields = new ArrayList<>();
        for (int slot : stroke.slots()) {
            if (slot >= 0 && slot < targets.size()) {
                String field = targets.get(slot).field();
                if (!fields.contains(field)) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    protected String recipeFieldAt(int mouseX, int mouseY) {
        String recipeType = normalizedRecipeType();
        List<RecipeSlotTarget> targets = recipeSlotTargets(recipeType, recipePreviewLayout);
        List<SlotInteractionGrid.SlotRect> slots = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            int[] point = targets.get(i).point();
            if (point == null || point.length < 2) {
                continue;
            }
            int size = Math.max(16, 16 * recipePreviewScale);
            slots.add(new SlotInteractionGrid.SlotRect(i, recipePreviewX + point[0] * recipePreviewScale, recipePreviewY + point[1] * recipePreviewScale, size));
        }
        int slot = SlotInteractionGrid.hitSlot(slots, mouseX, mouseY);
        return slot >= 0 && slot < targets.size() ? targets.get(slot).field() : "";
    }

    protected List<SlotInteractionGrid.SlotRect> recipeSlotRects() {
        String recipeType = normalizedRecipeType();
        List<RecipeSlotTarget> targets = recipeSlotTargets(recipeType, recipePreviewLayout);
        List<SlotInteractionGrid.SlotRect> slots = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            int[] point = targets.get(i).point();
            if (point == null || point.length < 2) {
                continue;
            }
            int size = Math.max(16, 16 * recipePreviewScale);
            slots.add(new SlotInteractionGrid.SlotRect(i, recipePreviewX + point[0] * recipePreviewScale, recipePreviewY + point[1] * recipePreviewScale, size));
        }
        return slots;
    }

    protected List<RecipeSlotTarget> recipeSlotTargets(String recipeType, RecipeStationLayout layout) {
        if (layout == null) {
            return List.of();
        }
        List<RecipeSlotTarget> targets = new ArrayList<>();
        if (isSmithingRecipe(recipeType)) {
            String[] fields = new String[]{"template.material", "base.material", "addition.material"};
            for (int i = 0; i < layout.templates().length && i < fields.length; i++) {
                targets.add(new RecipeSlotTarget(fields[i], layout.templates()[i]));
            }
            targets.add(new RecipeSlotTarget("output.material", layout.output()));
            return targets;
        }
        int[][] points = layout.ingredients();
        for (int i = 0; i < points.length; i++) {
            String field;
            if (isCookingRecipe(recipeType) || "stonecutting".equals(recipeType) || "shapeless".equals(recipeType)) {
                field = "ingredient" + (i + 1);
            } else {
                field = "slot" + (i + 1);
            }
            targets.add(new RecipeSlotTarget(field, points[i]));
        }
        targets.add(new RecipeSlotTarget("output.material", layout.output()));
        return targets;
    }

    protected void drawRecipeSlotHighlights(IDrawContext context, String recipeType, RecipeStationLayout layout, int viewX, int viewY, int scale) {
        int selectedColor = ThemeManager.getDefaultAccent().getAccentColor();
        List<SlotInteractionGrid.SlotRect> rects = new ArrayList<>();
        Set<Integer> selected = new HashSet<>();
        Set<Integer> drag = new HashSet<>();
        for (RecipeSlotTarget target : recipeSlotTargets(recipeType, layout)) {
            boolean locallySelected = Objects.equals(target.field(), selectedRecipeField);
            boolean dragTarget = Objects.equals(target.field(), dragRecipeTargetField) || dragRecipeTargetFields.contains(target.field());
            if (target.point() == null || target.point().length < 2) {
                continue;
            }
            int size = Math.max(16, 16 * scale);
            SlotInteractionGrid.SlotRect rect = new SlotInteractionGrid.SlotRect(target.field().hashCode(), viewX + target.point()[0] * scale, viewY + target.point()[1] * scale, size);
            rects.add(rect);
            if (locallySelected && !slotCollaboration.isFollowing()) {
                selected.add(rect.slot());
            }
            if (dragTarget) {
                drag.add(rect.slot());
            }
        }
        SlotInteractionGrid.drawCollaborativeHighlights(context, rects, drag, selected, selectedColor, selectedColor, slotCollaboration,
            recipeHighlightAnimationScope, "recipe_slot", recipeHighlightOriginX, recipeHighlightOriginY);
    }

    @Override
    public JsonArray collaborationSlots() {
        JsonArray slots = new JsonArray();
        if (!slotCollaboration.isFollowing() && selectedRecipeField != null && !selectedRecipeField.isBlank()) {
            slots.add(selectedRecipeField.hashCode());
        }
        return slots;
    }

    @Override
    public void applyCollaborationSlots(List<RemoteSlotSelection> selections) {
        slotCollaboration.apply(selections);
        Integer followed = slotCollaboration.followedSlot();
        if (followed == null || recipePreviewLayout == null) {
            return;
        }
        recipeSlotTargets(normalizedRecipeType(), recipePreviewLayout).stream().filter(target -> target.field().hashCode() == followed).findFirst()
            .ifPresent(target -> selectedRecipeField = target.field());
    }

    protected void deleteRecipeField(String field) {
        if (resourceEditHistoryBatch) {
            deleteRecipeFieldRaw(field);
            return;
        }
        captureResourceSnapshot();
        resourceEditHistoryBatch = true;
        try {
            deleteRecipeFieldRaw(field);
        } finally {
            resourceEditHistoryBatch = false;
        }
    }

    protected void deleteRecipeFieldRaw(String field) {
        if ("output.material".equals(field)) {
            JsonObject output = jsonObject("output");
            output.remove("material");
            output.remove("amount");
        } else if ("template.material".equals(field) || "base.material".equals(field) || "addition.material".equals(field)) {
            putJsonPathText(field, "");
        } else if (recipeSlotIndex(field) >= 0) {
            putRecipeSlotText(recipeSlotIndex(field), "");
        } else if (recipeIngredientIndex(field) >= 0) {
            putRecipeIngredientText(recipeIngredientIndex(field), "");
        }
    }

    protected void moveRecipeField(String source, String target) {
        String material = jsonPathText(source);
        if (material.isBlank()) {
            return;
        }
        captureResourceSnapshot();
        resourceEditHistoryBatch = true;
        try {
            putJsonText(target, material);
            putJsonText(source, "");
            if ("output.material".equals(source)) {
                jsonObject("output").remove("amount");
            }
            if ("output.material".equals(target) && jsonText(jsonObject("output"), "amount").isBlank()) {
                JsonObject output = jsonObject("output");
                output.addProperty("amount", 1);
                resource.add("output", output);
            }
        } finally {
            resourceEditHistoryBatch = false;
        }
    }

    protected boolean changeRecipeItemAmount(int mouseX, int mouseY, double verticalAmount) {
        if (recipePreviewLayout == null || recipePreviewScale <= 0) {
            return false;
        }
        String field = recipeFieldAt(mouseX, mouseY);
        if (field.isBlank() || jsonPathText(field).isBlank()) {
            return false;
        }
        int amount = recipeFieldAmount(field);
        int nextAmount = Math.clamp(amount + (verticalAmount > 0 ? 1 : -1), 1, 64);
        if (amount == nextAmount) {
            return false;
        }
        captureResourceSnapshot();
        putRecipeFieldAmount(field, nextAmount);
        return true;
    }

    protected List<String> recipeFields() {
        String recipeType = normalizedRecipeType();
        List<String> fields = new ArrayList<>(List.of("type"));
        if (isCookingRecipe(recipeType)) {
            fields.add("experience");
            fields.add("cookingTime");
            fields.add("cookedBinding");
        } else if ("stonecutting".equals(recipeType)) {
            fields.add("craftedBinding");
        } else if ("shapeless".equals(recipeType)) {
            fields.add("craftedBinding");
        } else if (!isSmithingRecipe(recipeType)) {
            fields.add("craftedBinding");
        }
        fields.add("conditions.permission");
        fields.add("conditions.world");
        fields.add("conditionBinding");
        fields.add("deniedBinding");
        return fields;
    }

    protected String recipeSlotLabel(int row, int column) {
        JsonArray shape = resource.has("shape") && resource.get("shape").isJsonArray() ? resource.getAsJsonArray("shape") : new JsonArray();
        JsonObject keys = jsonObject("keys");
        String recipeType = normalizedRecipeType();
        if (!shape.isEmpty() && row < shape.size()) {
            String line = shape.get(row).getAsString();
            if (column < line.length()) {
                JsonElement ingredient = keys.get(String.valueOf(line.charAt(column)));
                String shaped = ingredientLabel(ingredient);
                if (!shaped.isBlank()) {
                    return shaped;
                }
            }
        }
        if (!"shapeless".equals(recipeType)) {
            return "";
        }
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
        int index = row * 3 + column;
        return index < ingredients.size() ? ingredientLabel(ingredients.get(index)) : "";
    }

    protected String normalizedRecipeType() {
        String value = jsonText("type").trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        value = switch (value) {
            case "smelting" -> "furnace";
            case "blast" -> "blasting";
            case "smoker" -> "smoking";
            case "campfire_cooking" -> "campfire";
            case "stonecutter" -> "stonecutting";
            default -> value;
        };
        return value.isBlank() ? "shaped" : value;
    }

    protected boolean isCookingRecipe(String recipeType) {
        return "furnace".equals(recipeType) || "blasting".equals(recipeType) || "smoking".equals(recipeType) || "campfire".equals(recipeType);
    }

    protected boolean isSmithingRecipe(String recipeType) {
        return "smithing".equals(recipeType) || "smithing_transform".equals(recipeType) || "smithing_trim".equals(recipeType) || "trim".equals(recipeType);
    }

    protected String ingredientLabel(JsonElement ingredient) {
        if (ingredient == null || ingredient.isJsonNull()) {
            return "";
        }
        if (ingredient.isJsonArray() && !ingredient.getAsJsonArray().isEmpty()) {
            return ingredientLabel(ingredient.getAsJsonArray().get(0));
        }
        if (!ingredient.isJsonObject()) {
            return ingredient.getAsString();
        }
        return encodeRecipeItemValue(ingredient.getAsJsonObject());
    }

    protected int ingredientAmount(JsonElement ingredient) {
        if (ingredient == null || ingredient.isJsonNull()) {
            return 1;
        }
        if (ingredient.isJsonArray() && !ingredient.getAsJsonArray().isEmpty()) {
            return ingredientAmount(ingredient.getAsJsonArray().get(0));
        }
        if (!ingredient.isJsonObject()) {
            return 1;
        }
        return parseInt(jsonText(ingredient.getAsJsonObject(), "amount"), 1, 1, 64);
    }

    protected JsonElement firstIngredient() {
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
        return ingredients.isEmpty() ? null : ingredients.get(0);
    }

    protected String cookingIngredientLabel() {
        JsonElement ingredient = resource.has("ingredient") ? resource.get("ingredient") : firstIngredient();
        return ingredientLabel(ingredient);
    }

    protected int cookingIngredientAmount() {
        JsonElement ingredient = resource.has("ingredient") ? resource.get("ingredient") : firstIngredient();
        return ingredientAmount(ingredient);
    }

    protected JsonElement recipeSlotIngredient(int row, int column) {
        String recipeType = normalizedRecipeType();
        if (!"shaped".equals(recipeType)) {
            JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
            int index = row * 3 + column;
            return index < ingredients.size() ? ingredients.get(index) : null;
        }
        JsonArray shape = resource.has("shape") && resource.get("shape").isJsonArray() ? resource.getAsJsonArray("shape") : new JsonArray();
        JsonObject keys = jsonObject("keys");
        if (!shape.isEmpty() && row < shape.size()) {
            String line = shape.get(row).getAsString();
            if (column < line.length()) {
                JsonElement ingredient = keys.get(String.valueOf(line.charAt(column)));
                if (ingredient != null) {
                    return ingredient;
                }
            }
        }
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
        int index = row * 3 + column;
        return index < ingredients.size() ? ingredients.get(index) : null;
    }

    protected void normalizeRecipeSchema(String recipeType) {
        if (deferResourceMutation(() -> normalizeRecipeSchema(recipeType))) {
            return;
        }
        switch (RecipeSchema.kind(recipeType)) {
            case SHAPED -> normalizeShapedRecipe();
            case LIST -> {
                JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray()
                    ? compactRecipeIngredients(resource.getAsJsonArray("ingredients"))
                    : recipeIngredientsFromShape();
                if (ingredients.isEmpty()) {
                    ingredients = recipeIngredientsFromShape();
                }
                resource.add("ingredients", ingredients);
                resource.remove("shape");
                resource.remove("keys");
            }
            case SMITHING -> {
                resource.remove("shape");
                resource.remove("keys");
                resource.remove("ingredients");
            }
            case UNKNOWN -> {
            }
        }
    }

    protected void normalizeShapedRecipe() {
        boolean validShape = resource.has("shape") && resource.get("shape").isJsonArray() && !resource.getAsJsonArray("shape").isEmpty();
        boolean validKeys = resource.has("keys") && resource.get("keys").isJsonObject() && !resource.getAsJsonObject("keys").isEmpty();
        if (!validShape || !validKeys) {
            JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray()
                ? compactRecipeIngredients(resource.getAsJsonArray("ingredients"))
                : new JsonArray();
            JsonArray shape = new JsonArray();
            JsonObject keys = new JsonObject();
            for (int row = 0; row < 3 && row * 3 < ingredients.size(); row++) {
                StringBuilder line = new StringBuilder();
                for (int column = 0; column < 3; column++) {
                    int index = row * 3 + column;
                    if (index < ingredients.size()) {
                        String symbol = String.valueOf((char) ('A' + index));
                        line.append(symbol);
                        keys.add(symbol, ingredients.get(index).deepCopy());
                    } else {
                        line.append(' ');
                    }
                }
                shape.add(line.toString());
            }
            resource.add("shape", shape);
            resource.add("keys", keys);
        }
        resource.remove("ingredients");
    }

    protected JsonArray recipeIngredientsFromShape() {
        JsonArray ingredients = new JsonArray();
        if (!resource.has("shape") || !resource.get("shape").isJsonArray() || !resource.has("keys") || !resource.get("keys").isJsonObject()) {
            return ingredients;
        }
        JsonObject keys = resource.getAsJsonObject("keys");
        for (JsonElement rowElement : resource.getAsJsonArray("shape")) {
            String row = rowElement != null && !rowElement.isJsonNull() ? rowElement.getAsString() : "";
            for (int index = 0; index < row.length(); index++) {
                JsonElement ingredient = keys.get(String.valueOf(row.charAt(index)));
                if (!emptyRecipeIngredient(ingredient)) {
                    ingredients.add(ingredient.deepCopy());
                }
            }
        }
        return ingredients;
    }

    protected JsonArray compactRecipeIngredients(JsonArray source) {
        JsonArray ingredients = new JsonArray();
        if (source != null) {
            for (JsonElement ingredient : source) {
                if (!emptyRecipeIngredient(ingredient)) {
                    ingredients.add(ingredient.deepCopy());
                }
            }
        }
        return ingredients;
    }

    protected boolean emptyRecipeIngredient(JsonElement ingredient) {
        if (ingredient == null || ingredient.isJsonNull()) {
            return true;
        }
        if (ingredient.isJsonPrimitive() && ingredient.getAsJsonPrimitive().isString()) {
            return ingredient.getAsString().isBlank();
        }
        return ingredient.isJsonObject() && ingredient.getAsJsonObject().isEmpty();
    }

    protected int recipeSlotAmount(int row, int column) {
        return ingredientAmount(recipeSlotIngredient(row, column));
    }

    protected int recipeFieldAmount(String field) {
        if ("output.material".equals(field)) {
            return parseInt(jsonText(jsonObject("output"), "amount"), 1, 1, 64);
        }
        if ("template.material".equals(field)) {
            return ingredientAmount(jsonObject("template"));
        }
        if ("base.material".equals(field)) {
            return ingredientAmount(jsonObject("base"));
        }
        if ("addition.material".equals(field)) {
            return ingredientAmount(jsonObject("addition"));
        }
        int slotIndex = recipeSlotIndex(field);
        if (slotIndex >= 0) {
            return recipeSlotAmount(slotIndex / 3, slotIndex % 3);
        }
        int ingredientIndex = recipeIngredientIndex(field);
        if (ingredientIndex >= 0) {
            JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
            if (ingredientIndex < ingredients.size()) {
                return ingredientAmount(ingredients.get(ingredientIndex));
            }
            return ingredientIndex == 0 && resource.has("ingredient") ? ingredientAmount(resource.get("ingredient")) : 1;
        }
        return 1;
    }

    protected void putRecipeFieldAmount(String field, int amount) {
        if (deferResourceMutation(() -> putRecipeFieldAmount(field, amount))) {
            return;
        }
        if ("output.material".equals(field) || "template.material".equals(field) || "base.material".equals(field) || "addition.material".equals(field)) {
            String[] parts = field.split("\\.", 2);
            JsonObject object = jsonObject(parts[0]);
            object.addProperty("amount", amount);
            resource.add(parts[0], object);
            return;
        }
        int slotIndex = recipeSlotIndex(field);
        if (slotIndex >= 0) {
            putRecipeSlotAmount(slotIndex, amount);
            return;
        }
        int ingredientIndex = recipeIngredientIndex(field);
        if (ingredientIndex >= 0) {
            putRecipeIngredientAmount(ingredientIndex, amount);
        }
    }

    protected void putRecipeSlotAmount(int index, int amount) {
        JsonObject keys = jsonObject("keys");
        String symbol = recipeSlotSymbol(index);
        JsonElement ingredient = keys.get(symbol);
        if (ingredient != null) {
            JsonObject object = recipeIngredientObject(ingredient);
            object.addProperty("amount", amount);
            keys.add(symbol, object);
            resource.add("keys", keys);
            return;
        }
        putRecipeIngredientAmount(index, amount);
    }

    protected void putRecipeIngredientAmount(int index, int amount) {
        if (deferResourceMutation(() -> putRecipeIngredientAmount(index, amount))) {
            return;
        }
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
        resource.add("ingredients", ingredients);
        while (ingredients.size() <= index) {
            ingredients.add("");
        }
        JsonObject object = recipeIngredientObject(ingredients.get(index));
        object.addProperty("amount", amount);
        ingredients.set(index, object);
        if (index == 0 && (isCookingRecipe(normalizedRecipeType()) || "stonecutting".equals(normalizedRecipeType()))) {
            resource.add("ingredient", object);
        }
    }

    protected JsonObject recipeIngredientObject(JsonElement ingredient) {
        if (ingredient != null && ingredient.isJsonArray() && !ingredient.getAsJsonArray().isEmpty()) {
            return recipeIngredientObject(ingredient.getAsJsonArray().get(0));
        }
        if (ingredient != null && ingredient.isJsonObject()) {
            return ingredient.getAsJsonObject();
        }
        JsonObject object = new JsonObject();
        if (ingredient != null && ingredient.isJsonPrimitive() && !ingredient.getAsString().isBlank()) {
            object.addProperty("material", ingredient.getAsString());
        }
        return object;
    }

    protected void drawRecipeStationItems(IDrawContext context, String recipeType, RecipeStationLayout layout, int viewX, int viewY, int scale) {
        if (isSmithingRecipe(recipeType)) {
            drawRecipeLayoutItem(context, ingredientLabel(jsonObject("template")), ingredientAmount(jsonObject("template")), layout.templates()[0], viewX, viewY, scale);
            drawRecipeLayoutItem(context, ingredientLabel(jsonObject("base")), ingredientAmount(jsonObject("base")), layout.templates()[1], viewX, viewY, scale);
            drawRecipeLayoutItem(context, ingredientLabel(jsonObject("addition")), ingredientAmount(jsonObject("addition")), layout.templates()[2], viewX, viewY, scale);
        } else if (isCookingRecipe(recipeType)) {
            drawRecipeLayoutItem(context, cookingIngredientLabel(), cookingIngredientAmount(), layout.ingredients()[0], viewX, viewY, scale);
        } else if ("stonecutting".equals(recipeType)) {
            drawRecipeLayoutItem(context, ingredientLabel(firstIngredient()), ingredientAmount(firstIngredient()), layout.ingredients()[0], viewX, viewY, scale);
        } else {
            int[][] points = layout.ingredients();
            for (int i = 0; i < points.length; i++) {
                drawRecipeLayoutItem(context, recipeSlotLabel(i / 3, i % 3), recipeSlotAmount(i / 3, i % 3), points[i], viewX, viewY, scale);
            }
        }
        drawRecipeLayoutItem(context, ingredientLabel(jsonObject("output")), parseInt(jsonText(jsonObject("output"), "amount"), 1, 1, 64), layout.output(), viewX, viewY, scale);
    }

    protected void drawRecipeLayoutItem(IDrawContext context, String material, int amount, int[] point, int viewX, int viewY, int scale) {
        if (point == null || point.length < 2) {
            return;
        }
        drawRecipeItem(context, material, amount, viewX + point[0] * scale, viewY + point[1] * scale, scale);
    }

    protected RecipeStationLayout recipeStationLayout(String recipeType) {
        int[][] craftingSlots = new int[][]{
            {30, 17}, {48, 17}, {66, 17},
            {30, 35}, {48, 35}, {66, 35},
            {30, 53}, {48, 53}, {66, 53}
        };
        return switch (recipeType) {
            case "furnace" -> new RecipeStationLayout("furnace.png", 176, 166, new int[][]{{56, 17}}, new int[0][0], new int[]{116, 35});
            case "blasting" -> new RecipeStationLayout("blast_furnace.png", 176, 166, new int[][]{{56, 17}}, new int[0][0], new int[]{116, 35});
            case "smoking" -> new RecipeStationLayout("smoker.png", 176, 166, new int[][]{{56, 17}}, new int[0][0], new int[]{116, 35});
            case "campfire" -> new RecipeStationLayout("", 88, 16, new int[][]{{8, 0}}, new int[0][0], new int[]{64, 0});
            case "stonecutting" -> new RecipeStationLayout("stonecutter.png", 176, 166, new int[][]{{20, 33}}, new int[0][0], new int[]{143, 33});
            case "smithing", "smithing_transform", "smithing_trim", "trim" -> new RecipeStationLayout("smithing.png", 176, 166, new int[0][0], new int[][]{{8, 48}, {26, 48}, {44, 48}}, new int[]{98, 48});
            default -> new RecipeStationLayout("crafting_table.png", 176, 166, craftingSlots, new int[0][0], new int[]{124, 35});
        };
    }
}
