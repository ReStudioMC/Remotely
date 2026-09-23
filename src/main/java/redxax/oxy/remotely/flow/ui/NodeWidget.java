package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.CoreGraphUiProjection;
import redxax.oxy.remotely.data.flow.CoreRepeatableUiProjection;
import redxax.oxy.remotely.data.flow.ReSyncGenericDescriptorProjection;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.data.FlowResourceReference;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncTypedCatalogConsumer;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.type.TypeReference;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.resync.flow.contract.FlowTypeMetadata;
import restudio.resync.flow.contract.EditorDiagnostic;
import redxax.oxy.remotely.flow.ui.studio.ReSyncResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.render.Render;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetComposite;
import restudio.rescreen.ui.core.WidgetCleanup;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ColorFieldWidget;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SliderWidget;
import restudio.rebase.ui.widgets.editor.TextAreaWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.TitleBarStyle;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static restudio.rescreen.config.Config.animationsEnabled;
import static restudio.rescreen.config.Config.deltaTime;
import static restudio.rescreen.config.Config.globalMovementSpeed;
import static restudio.rescreen.config.Config.shadow;
import static restudio.rescreen.render.TextRenderer.tr;

public class NodeWidget extends AnimatedWidget implements AutoCloseable, WidgetCleanup, WidgetComposite {
    public record NodeValueMutation(UUID mutationId, NodeInstanceId nodeId, PinId pinId,
                                    RepeatableElementId elementId, Object value, boolean remove,
                                    TypedValue exactValue) {
        public NodeValueMutation {
            Objects.requireNonNull(mutationId, "Mutation identity is required");
            Objects.requireNonNull(nodeId, "Node identity is required");
            Objects.requireNonNull(pinId, "Pin identity is required");
            if (remove) {
                value = null;
                exactValue = null;
            } else {
                value = immutableNodeValue(value);
            }
        }

        public NodeValueMutation(NodeInstanceId nodeId, PinId pinId, Object value, boolean remove) {
            this(UUID.randomUUID(), nodeId, pinId, null, value, remove, null);
        }

        public NodeValueMutation(NodeInstanceId nodeId, PinId pinId, RepeatableElementId elementId,
                                 Object value, boolean remove) {
            this(UUID.randomUUID(), nodeId, pinId, elementId, value, remove, null);
        }

        public NodeValueMutation(NodeInstanceId nodeId, PinId pinId, TypedValue exactValue) {
            this(UUID.randomUUID(), nodeId, pinId, null, presentedValue(exactValue), false,
                Objects.requireNonNull(exactValue, "Exact typed value is required"));
        }

        public NodeValueMutation(NodeInstanceId nodeId, PinId pinId, RepeatableElementId elementId,
                                 TypedValue exactValue) {
            this(UUID.randomUUID(), nodeId, pinId, elementId, presentedValue(exactValue), false,
                Objects.requireNonNull(exactValue, "Exact typed value is required"));
        }
    }

    @FunctionalInterface
    public interface NodeValueMutationHandler {
        boolean apply(NodeValueMutation mutation);
    }

    public enum RepeatableMutationKind {
        ADD,
        REMOVE,
        MOVE_EARLIER,
        MOVE_LATER
    }

    public record RepeatableMutation(UUID mutationId, NodeInstanceId nodeId, RepeatableGroupId groupId,
                                     RepeatableElementId elementId, RepeatableMutationKind kind) {
        public RepeatableMutation {
            Objects.requireNonNull(mutationId, "Mutation identity is required");
            Objects.requireNonNull(nodeId, "Node identity is required");
            Objects.requireNonNull(groupId, "Repeatable group identity is required");
            Objects.requireNonNull(elementId, "Repeatable element identity is required");
            Objects.requireNonNull(kind, "Repeatable mutation kind is required");
        }

        public RepeatableMutation(NodeInstanceId nodeId, RepeatableGroupId groupId,
                                  RepeatableElementId elementId, RepeatableMutationKind kind) {
            this(UUID.randomUUID(), nodeId, groupId, elementId, kind);
        }
    }

    @FunctionalInterface
    public interface RepeatableMutationHandler {
        boolean apply(RepeatableMutation mutation);
    }

    public record InspectorFieldMutation(CoreGraphUiProjection.InspectorValue original,
                                         CoreGraphUiProjection.InspectorValue replacement, boolean remove,
                                         boolean exactOption) {
        public InspectorFieldMutation {
            Objects.requireNonNull(original, "Original inspector value is required");
            if (remove) {
                replacement = null;
                exactOption = false;
            } else {
                Objects.requireNonNull(replacement, "Replacement inspector value is required");
                if (!original.fieldId().equals(replacement.fieldId())) {
                    throw new IllegalArgumentException("Inspector field identity cannot change");
                }
                if (exactOption && !original.type().equals(replacement.type())) {
                    throw new IllegalArgumentException("Inspector option type cannot change");
                }
                if (!exactOption && !original.withTypedValue(replacement.typedValue()).equals(replacement)) {
                    throw new IllegalArgumentException("Inspector replacement is incompatible with its original value");
                }
            }
        }

        public InspectorFieldMutation(CoreGraphUiProjection.InspectorValue original,
                                      CoreGraphUiProjection.InspectorValue replacement, boolean remove) {
            this(original, replacement, remove, false);
        }
    }

    public record InspectorMutation(UUID mutationId, NodeInstanceId nodeId, List<InspectorFieldMutation> fields) {
        public InspectorMutation {
            Objects.requireNonNull(mutationId, "Inspector mutation identity is required");
            Objects.requireNonNull(nodeId, "Node identity is required");
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("Inspector mutation fields are required");
            }
        }

        public InspectorMutation(NodeInstanceId nodeId, List<InspectorFieldMutation> fields) {
            this(UUID.randomUUID(), nodeId, fields);
        }
    }

    @FunctionalInterface
    public interface InspectorMutationHandler {
        boolean apply(InspectorMutation mutation);
    }

    private static Object immutableNodeValue(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            values.forEach((key, entry) -> copy.put(key, immutableNodeValue(entry)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> values) {
            return Collections.unmodifiableList(values.stream().map(NodeWidget::immutableNodeValue).toList());
        }
        if (value instanceof Set<?> values) {
            List<Object> copy = values.stream().map(NodeWidget::immutableNodeValue).toList();
            return Collections.unmodifiableSet(new LinkedHashSet<>(copy));
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }

    private static Object presentedValue(TypedValue value) {
        if (value == null) {
            return null;
        }
        return switch (value.state()) {
            case ABSENT, NULL -> null;
            case LOCATOR -> value.locator();
            case VALUE, OPAQUE -> value.value();
        };
    }

    private final FlowNode node;
    private final FlowGraph graph;
    private final String nodeId;
    private final String serverId;
    private final List<NodeDefinition.PinDefinition> inputs = new ArrayList<>();
    private final List<NodeDefinition.PinDefinition> outputs = new ArrayList<>();
    private final NodeDefinition definition;
    private final Map<String, Widget> inputWidgets = new HashMap<>();
    private final Map<String, String> searchableSelectorValues = new HashMap<>();
    private final Map<String, NodeValueMutation> inputValuePreviews = new LinkedHashMap<>();
    private final Set<String> deferredInputValues = new LinkedHashSet<>();
    private boolean reconcilingInputValues;
    private boolean collaborationChildrenDirty = true;
    private List<Widget> collaborationChildren = List.of();
    private final List<NodeDefinition.PinDefinition> visibleInputs = new ArrayList<>();
    private final List<NodeDefinition.PinDefinition> visibleOutputs = new ArrayList<>();
    private final Map<String, NodeDefinition.PinDefinition> visibleInputsById = new HashMap<>();
    private final Set<String> stringTemplateInputNames = new LinkedHashSet<>();
    private final List<FlowBranch> flowBranches = new ArrayList<>();
    private final List<PinRow> inputRows = new ArrayList<>();
    private final List<PinRow> outputRows = new ArrayList<>();
    private final Map<String, PinRow> inputRowsById = new HashMap<>();
    private final Map<String, PinRow> outputRowsById = new HashMap<>();
    private final List<InputPinLayout> inputPinLayouts = new ArrayList<>();
    private final List<OutputPinLayout> outputPinLayouts = new ArrayList<>();
    private final Map<String, PinBounds> inputPinBounds = new HashMap<>();
    private final Map<String, PinBounds> outputPinBounds = new HashMap<>();
    private final Map<String, AnimatedButton> branchWidgetsByOutput = new HashMap<>();
    private final Map<String, CoreRepeatableUiProjection.PinEndpoint> corePinEndpoints = new LinkedHashMap<>();
    private final Map<String, String> coreViewPins = new LinkedHashMap<>();
    private final List<FlowGraph.FunctionParameter> callParameters = new ArrayList<>();
    private final Runnable onClose;
    private final Runnable onMutation;
    private final NodeValueMutationHandler nodeValueMutationHandler;
    private final boolean definitionReadOnly;
    private final String definitionReadOnlyReason;
    private final boolean definitionLookupBlocked;
    private final AnimatedButton closeButton;
    private final AnimatedButton openFunctionButton;
    private AnimatedButton inspectorButton;
    private AnimatedButton addBranchButton;
    private final Map<String, AnimatedButton> addInputButtons = new LinkedHashMap<>();
    private AnimatedButton paramButton;
    private static final TitleBarStyle TITLE_STYLE = TitleBarStyle.COMPACT;
    private static final int TITLE_HEIGHT = TITLE_STYLE.height();
    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_SPACING = 6;
    private static final int PIN_BUTTON_SIZE = 10;
    private static final int PIN_TEXT_GAP = 4;
    private static final int INPUT_FIELD_GAP = 6;
    private static final int INPUT_WIDGET_WIDTH = 90;
    private static final int INPUT_WIDGET_HEIGHT = 16;
    private static final int OUTPUT_WIDGET_WIDTH = 100;
    private static final int PASSTHROUGH_DASH_SIZE = 7;
    private static final int PASSTHROUGH_DASH_GAP = 5;
    private static final int TOGGLE_WIDGET_WIDTH = 28;
    private static final int TOGGLE_WIDGET_HEIGHT = 12;
    private static final int COLUMN_GAP = 12;
    private static final int SINGLE_COLUMN_MIN_WIDTH = 100;
    private static final int DEFAULT_WIDTH = 170;
    private static final int PIN_HIT_PADDING = 4;
    private static final long DIAGNOSTIC_PULSE_DURATION_MILLIS = 3_000L;
    private static final long DIAGNOSTIC_PULSE_CYCLE_MILLIS = 900L;
    private static final int CLOSE_BUTTON_WIDTH = TITLE_STYLE.controlWidth();
    private static final int CLOSE_BUTTON_HEIGHT = TITLE_STYLE.controlHeight();
    private static final String FLOW_BRANCHES_KEY = "__flow_branches";
    private static final String REPEATABLE_COUNT_PREFIX = "__repeatable_count:";
    private static final String LEGACY_PERMISSION_COUNT_KEY = "__permission_count";
    private static final String REMOVED_OPTIONAL_INPUTS_KEY = "__removed_optional_inputs";
    private static final String VARIABLE_CATALOG = "server:resync:variable_definition";
    private static final String TIMER_CATALOG = "server:resync:timer_definition";
    private static final String SCHEDULE_CATALOG = "server:resync:schedule_definition";
    private static final String CUSTOM_FUNCTION_NODE_PREFIX = "custom_function:";
    private static final String CALL_FUNCTION_ID = "call_function";
    private static final String CALL_FUNCTION_CANONICAL_ID = "call.function";
    private static final String CALL_PARAMETERS_KEY = "__call_parameters";
    private static final String FUNCTION_SIGNATURE_KEY = "__function_signature";
    private static final String FUNCTION_SIGNATURE_ISSUES_KEY = "__function_signature_issues";

    private boolean updatingBranchSelection = false;
    private int lastScreenX;
    private int lastScreenY;
    private boolean hasLastScreenMouse;
    private List<EditorDiagnostic> editorDiagnostics = List.of();
    private long diagnosticPulseUntil;
    private boolean catalogConnectionRequested;
    private boolean pinLayoutDirty = true;
    private int inputsContentHeight;
    private int outputsContentHeight;
    private long pinLayoutOperationCount;
    private long pinLayoutGeneration;
    private long childLayoutGeneration;
    private long childLayoutOperationCount;
    private ChildLayoutKey appliedChildLayout;
    private AnimatedWidget hintOverlayCandidate;
    private ReSyncGenericDescriptorProjection.Inspector inspector = ReSyncGenericDescriptorProjection.Inspector.empty();
    private CoreGraphUiProjection.InspectorProjection inspectorProjection;
    private InspectorMutationHandler inspectorMutationHandler;
    private ServerResourceLocator coreResource;
    private Map<PinId, PinValue> corePinValues = Map.of();
    private CoreRepeatableUiProjection.Projection coreRepeatables = CoreRepeatableUiProjection.Projection.empty();
    private RepeatableMutationHandler repeatableMutationHandler;
    private boolean coreRepeatablesConfigured;
    private PopupWidget inspectorPopup;
    private Screen inspectorPopupOwner;
    private final PinHintState pinHint = new PinHintState();
    private int childLayoutLeftColumnWidth;
    private int childLayoutRightColumnWidth;

    private record ChildLayoutKey(int x, int y, int width, int height, long pinGeneration, long childGeneration) {
    }

    private record InspectorDraft(ReSyncGenericDescriptorProjection.InspectorField field,
                                  CoreGraphUiProjection.InspectorValue original, Widget editor,
                                  BrowserSafeState.BooleanValue dirty, BrowserSafeState.ReferenceValue<TypedValue.State> state,
                                  BrowserSafeState.ReferenceValue<TypedValue> exactValue) {
    }

    private record PinBounds(int x, int y) {
        double[] array() {
            return new double[]{x, y, PIN_BUTTON_SIZE, PIN_BUTTON_SIZE};
        }
    }

    private record InputPinLayout(NodeDefinition.PinDefinition pin, String label, String description, int rowY,
                                  int rowHeight, int pinX, int pinY, int textX, int textY, int labelWidth, int color) {
    }

    private record OutputPinLayout(NodeDefinition.PinDefinition pin, String label, String description, int rowY,
                                   int rowHeight, int pinX, int pinY, int labelX, int textY, int labelWidth, int color,
                                   boolean showLabel) {
    }

    private static final class PinHintState {
        private NodeDefinition.PinDefinition pin;
        private boolean hovered;
        private boolean owned;
        private float progress;
        private int x;
        private int y;
        private int width;
        private int height;
    }

    static String pinId(NodeDefinition.PinDefinition pin) {
        return EditorPassthroughPins.pinId(pin);
    }

    private static String pinDisplayName(NodeDefinition.PinDefinition pin) {
        return pin != null && pin.getDisplayName() != null && !pin.getDisplayName().isBlank()
            ? pin.getDisplayName() : pinId(pin);
    }

    private static String functionParameterPinId(FlowGraph.FunctionParameter parameter,
                                                 NodeDefinition.PinDirection direction) {
        return functionParameterPinId(parameter, direction, false);
    }

    private static String functionParameterPinId(FlowGraph.FunctionParameter parameter,
                                                 NodeDefinition.PinDirection direction,
                                                 boolean allowLegacyName) {
        if (parameter == null) {
            return "";
        }
        String parameterId = parameter.getParameterId();
        if (allowLegacyName && (parameterId == null || parameterId.isBlank())) {
            parameterId = parameter.getName();
        }
        if (parameterId == null || parameterId.isBlank()) {
            return "";
        }
        String inputPrefix = "function-input-";
        String outputPrefix = "function-output-";
        if (parameterId.startsWith(inputPrefix)) {
            parameterId = parameterId.substring(inputPrefix.length());
        } else if (parameterId.startsWith(outputPrefix)) {
            parameterId = parameterId.substring(outputPrefix.length());
        }
        return (direction == NodeDefinition.PinDirection.INPUT ? inputPrefix : outputPrefix) + parameterId;
    }

    public void setEditorDiagnostics(List<EditorDiagnostic> diagnostics) {
        editorDiagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        diagnosticPulseUntil = editorDiagnostics.isEmpty() ? 0L : System.currentTimeMillis() + DIAGNOSTIC_PULSE_DURATION_MILLIS;
    }

    private boolean hasEditorDiagnostic(String field) {
        return editorDiagnostics.stream().anyMatch(diagnostic -> field.equals(diagnostic.field()) && !resolved(diagnostic));
    }

    private boolean resolved(EditorDiagnostic diagnostic) {
        if (!"REQUIRED_INPUT_MISSING".equals(diagnostic.code())) {
            return false;
        }
        if (isInputWired(diagnostic.field())) {
            return true;
        }
        Object value = presentationInputValue(diagnostic.field());
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private int diagnosticPinBorder(String field) {
        long now = System.currentTimeMillis();
        if (!hasEditorDiagnostic(field) || now >= diagnosticPulseUntil) {
            return 0;
        }
        long elapsed = DIAGNOSTIC_PULSE_DURATION_MILLIS - Math.max(0L, diagnosticPulseUntil - now);
        double progress = elapsed % DIAGNOSTIC_PULSE_CYCLE_MILLIS / (double) DIAGNOSTIC_PULSE_CYCLE_MILLIS;
        double pulse = 0.5 - 0.5 * Math.cos(progress * Math.PI * 2.0);
        int alpha = Math.clamp((int) Math.round(48 + pulse * 207), 0, 255);
        return alpha << 24 | ThemeManager.getAccent("danger").getAccentColor() & 0x00FFFFFF;
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId) {
        this(x, y, node, graph, nodeId, null, null, null);
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId) {
        this(x, y, node, graph, nodeId, serverId, null, null);
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose) {
        this(x, y, node, graph, nodeId, serverId, onClose, null);
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose, Runnable onMutation) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, null, false);
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                      Runnable onMutation, NodeDefinition definitionOverride, boolean definitionReadOnly) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, definitionOverride, definitionReadOnly, false);
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                      Runnable onMutation, NodeDefinition definitionOverride, boolean definitionReadOnly,
                      boolean definitionLookupBlocked) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, definitionOverride, definitionReadOnly,
            definitionLookupBlocked, null);
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                      Runnable onMutation, NodeDefinition definitionOverride, boolean definitionReadOnly,
                      boolean definitionLookupBlocked, NodeValueMutationHandler nodeValueMutationHandler) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, definitionOverride, definitionReadOnly,
            definitionLookupBlocked, nodeValueMutationHandler, null, Map.of());
    }

    public NodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                      Runnable onMutation, NodeDefinition definitionOverride, boolean definitionReadOnly,
                      boolean definitionLookupBlocked, NodeValueMutationHandler nodeValueMutationHandler,
                      ServerResourceLocator coreResource, Map<PinId, PinValue> corePinValues) {
        super(x, y, DEFAULT_WIDTH, 100, "");
        setCursorHoverReactive(true);
        this.node = node;
        this.graph = graph;
        this.nodeId = nodeId;
        setCollaborationKey("node:" + nodeId);
        this.serverId = serverId;
        this.coreResource = coreResource;
        this.corePinValues = immutableCorePinValues(corePinValues);
        boolean authorityLookupBlocked = definitionLookupBlocked
            || definitionOverride == null && typedCatalogAuthorityActive(serverId);
        this.definitionLookupBlocked = authorityLookupBlocked;
        loadCallParameters();
        this.definition = definitionOverride != null ? definitionOverride
            : authorityLookupBlocked ? null : resolveDefinition(serverId, node.getType());
        this.definitionReadOnlyReason = readOnlyReason(node, definitionReadOnly, coreResource != null, definition);
        this.definitionReadOnly = !definitionReadOnlyReason.isBlank();
        if (definition == null && !authorityLookupBlocked) {
            requestNodeRegistry();
        }
        this.enableHoverColors = true;
        this.selectable = true;
        this.animateElevation = false;
        this.entranceAnimationEnabled = false;
        this.onClose = onClose;
        this.onMutation = onMutation;
        this.nodeValueMutationHandler = nodeValueMutationHandler;
        this.closeButton = new AnimatedButton.Builder()
            .onClick(() -> {
                if (this.onClose != null) {
                    this.onClose.run();
                }
            })
            .accentType(ThemeManager.getAccent("danger"))
            .animateElevation(false)
            .entranceAnimation(false)
            .size(CLOSE_BUTTON_WIDTH, CLOSE_BUTTON_HEIGHT)
            .hint("Delete Node")
            .build();
        this.closeButton.visible = this.onClose != null;
        this.openFunctionButton = new AnimatedButton.Builder()
            .onClick(() -> {
            })
            .accentType(ThemeManager.getAccent("nice"))
            .animateElevation(false)
            .entranceAnimation(false)
            .size(CLOSE_BUTTON_WIDTH, CLOSE_BUTTON_HEIGHT)
            .hint("Open Function")
            .build();
        this.openFunctionButton.visible = false;

        refreshFunctionParameterButton();
        initializeDefinition();
    }

    public void configureEditAction(String hint, Runnable action) {
        openFunctionButton.setMessage("E");
        openFunctionButton.setHint(hint == null || hint.isBlank() ? "Edit" : hint);
        openFunctionButton.setAction(action);
        openFunctionButton.visible = action != null;
        invalidateChildLayout();
    }

    public String inputResourceId(String pin) {
        return resourceId(presentationInputValue(pin));
    }

    protected final void refreshFunctionParameterButton() {
        if (isFunctionStartNode() || isFunctionEndNode() || isFunctionCallNode() && !isCoreWidget()) {
            if (this.paramButton != null) {
                return;
            }
            this.paramButton = new AnimatedButton.Builder()
                .label("+")
                .onClick(this::showParamContextMenu)
                .accentType(ThemeManager.getAccent("nice"))
                .animateElevation(false)
                .entranceAnimation(false)
                .size(CLOSE_BUTTON_WIDTH, CLOSE_BUTTON_HEIGHT)
                .hint(isFunctionCallNode() ? "Arguments" : "Params")
                .build();
        } else {
            this.paramButton = null;
        }
    }

    private void initializeDefinition() {
        if (definition != null) {
            inputs.addAll(definition.getInputs());
            outputs.addAll(definition.getOutputs());
            applyFunctionParameterPins();
            applyFunctionCallSignaturePins();
            applyAdvancedInputPins();
            applyAdvancedOutputPins();
            applyRemovedOptionalInputs();
            updateAddInputButtons();
            if (!this.definitionReadOnly && nodeValueMutationHandler == null) {
                seedDefaultInputValues();
            }
            updateStringTemplatePins();
            preflightOptionCatalogs();
            createInputWidgets();
            createOutputWidgets();
            updateSize();
        } else {
            createLoadingState();
        }
        setAnimateLayout(true);
    }

    public void configureInspector(ReSyncGenericDescriptorProjection.Inspector inspector,
                                   CoreGraphUiProjection.InspectorProjection projection,
                                   InspectorMutationHandler mutationHandler) {
        closeInspectorPopup();
        this.inspector = inspector == null ? ReSyncGenericDescriptorProjection.Inspector.empty() : inspector;
        this.inspectorProjection = projection;
        this.inspectorMutationHandler = mutationHandler;
        if (!this.inspector.present()) {
            if (inspectorButton != null) {
                inspectorButton.visible = false;
            }
            updateSize();
            return;
        }
        if (inspectorButton == null) {
            inspectorButton = new AnimatedButton.Builder()
                .label("i")
                .onClick(this::showInspectorPopup)
                .accentType(ThemeManager.getAccent("nice"))
                .animateElevation(false)
                .entranceAnimation(false)
                .size(CLOSE_BUTTON_WIDTH, CLOSE_BUTTON_HEIGHT)
                .hint("Inspector")
                .build();
        }
        inspectorButton.visible = true;
        updateSize();
    }

    public boolean configureCoreRepeatables(CoreRepeatableUiProjection.Projection projection,
                                            RepeatableMutationHandler mutationHandler) {
        CoreRepeatableUiProjection.Projection next = projection == null
            ? CoreRepeatableUiProjection.Projection.empty() : projection;
        if (coreRepeatablesConfigured && coreRepeatables.equals(next)
            && (repeatableMutationHandler == null) == (mutationHandler == null)) {
            return false;
        }
        coreRepeatables = next;
        repeatableMutationHandler = mutationHandler;
        coreRepeatablesConfigured = true;
        refreshInputWidgets();
        return true;
    }

    public void configureCoreOptions(ServerResourceLocator resource, Map<PinId, PinValue> values) {
        Map<PinId, PinValue> safeValues = immutableCorePinValues(values);
        if (Objects.equals(coreResource, resource) && corePinValues.equals(safeValues)) {
            return;
        }
        coreResource = resource;
        corePinValues = safeValues;
        if (definition != null) {
            refreshInputWidgets();
        }
    }

    private static Map<PinId, PinValue> immutableCorePinValues(Map<PinId, PinValue> values) {
        return values == null || values.isEmpty() ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public void updateInspectorProjection(CoreGraphUiProjection.InspectorProjection projection) {
        if (Objects.equals(inspectorProjection, projection)) {
            return;
        }
        closeInspectorPopup();
        inspectorProjection = projection;
    }

    public void showInspectorPopup() {
        var screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null || !inspector.present()) {
            return;
        }
        closeInspectorPopup();
        String title = inspector.title().isBlank() ? "Inspector" : inspector.title();
        PopupWidget.Builder builder = new PopupWidget.Builder(title).width(420).setResizable(true)
            .setExpandWithDropdowns(true);
        List<InspectorDraft> drafts = new ArrayList<>();
        for (ReSyncGenericDescriptorProjection.InspectorSection section : inspector.sections()) {
            String sectionTitle = section.title();
            for (ReSyncGenericDescriptorProjection.InspectorRow row : section.rows()) {
                String rowTitle = row.title();
                for (ReSyncGenericDescriptorProjection.InspectorField field : row.fields()) {
                    addInspectorField(builder, drafts, field, inspector.readOnly() || section.readOnly() || row.readOnly(),
                        inspectorLabel(sectionTitle, rowTitle, ""));
                }
            }
        }
        if (drafts.isEmpty()) {
            TextInputWidget empty = new TextInputWidget.Builder().text("No Fields").size(250, 20)
                .entranceAnimation(false).build();
            empty.setActive(false);
            builder.addRow("Inspector", empty);
        } else if (inspectorMutationHandler != null && drafts.stream().anyMatch(draft -> draft.editor().isActive())) {
            builder.addTitleAction("Apply", () -> applyInspectorDrafts(drafts), PopupWidget.TitleActionRole.PRIMARY);
        }
        PopupWidget popup = builder.build();
        popup.setCollaborationKey("node:" + nodeId + "/inspector-popup");
        popup.onClose = () -> closeInspectorPopup(popup, screen);
        inspectorPopup = popup;
        inspectorPopupOwner = screen;
        screen.addDrawableChild(popup);
        popup.show();
    }

    private void addInspectorField(PopupWidget.Builder builder, List<InspectorDraft> drafts,
                                   ReSyncGenericDescriptorProjection.InspectorField field,
                                   boolean inheritedReadOnly, String parentLabel) {
        InspectorFieldId fieldId;
        try {
            fieldId = InspectorFieldId.of(field.id());
        } catch (RuntimeException exception) {
            return;
        }
        CoreGraphUiProjection.InspectorValue original = inspectorProjection != null
            ? inspectorProjection.field(fieldId).orElse(null) : null;
        if (original == null) {
            original = CoreGraphUiProjection.absentInspectorValue(field).orElse(null);
        }
        BrowserSafeState.BooleanValue dirty = new BrowserSafeState.BooleanValue();
        BrowserSafeState.ReferenceValue<TypedValue.State> state = new BrowserSafeState.ReferenceValue<>();
        BrowserSafeState.ReferenceValue<TypedValue> exactValue = new BrowserSafeState.ReferenceValue<>();
        boolean editable = original != null && inspectorMutationHandler != null && !definitionReadOnly
            && !inheritedReadOnly && !field.readOnly() && field.editor().editable()
            && field.editor().editorKind() != ReSyncGenericDescriptorProjection.EditorKind.UNSUPPORTED
            && original.state() != TypedValue.State.OPAQUE
            && (field.optionSource() != null || original.state() != TypedValue.State.LOCATOR);
        Widget editor = inspectorEditor(field, original, editable, dirty, state, exactValue);
        editor.setCollaborationKey("node:" + nodeId + "/inspector:" + field.id());
        String label = inspectorLabel(parentLabel, "", field.title().isBlank() ? field.id() : field.title());
        if (editable) {
            AnimatedButton nullValue = new AnimatedButton.Builder().label("Null")
                .onClick(() -> {
                    state.set(TypedValue.State.NULL);
                    exactValue.set(null);
                    dirty.set(true);
                })
                .size(42, INPUT_WIDGET_HEIGHT).entranceAnimation(false).build();
            AnimatedButton remove = new AnimatedButton.Builder().label("Remove")
                .onClick(() -> {
                    state.set(TypedValue.State.ABSENT);
                    exactValue.set(null);
                    dirty.set(true);
                })
                .size(54, INPUT_WIDGET_HEIGHT).entranceAnimation(false).build();
            builder.addRow(label, editor, nullValue, remove);
        } else {
            builder.addRow(label, editor);
        }
        drafts.add(new InspectorDraft(field, original, editor, dirty, state, exactValue));
        String childParent = inspectorLabel(parentLabel, "", field.title().isBlank() ? field.id() : field.title());
        for (ReSyncGenericDescriptorProjection.InspectorField child : field.children()) {
            addInspectorField(builder, drafts, child, inheritedReadOnly || field.readOnly(), childParent);
        }
    }

    private Widget inspectorEditor(ReSyncGenericDescriptorProjection.InspectorField field,
                                   CoreGraphUiProjection.InspectorValue value, boolean editable,
                                   BrowserSafeState.BooleanValue dirty, BrowserSafeState.ReferenceValue<TypedValue.State> state,
                                   BrowserSafeState.ReferenceValue<TypedValue> exactValue) {
        Runnable changed = () -> {
            state.set(null);
            exactValue.set(null);
            dirty.set(true);
        };
        if (editable && field.optionSource() != null) {
            return buildCoreInspectorSelector(field, value, dirty, state, exactValue);
        }
        ReSyncGenericDescriptorProjection.EditorKind kind = field.editor().editorKind();
        if (editable && kind == ReSyncGenericDescriptorProjection.EditorKind.BOOLEAN) {
            ToggleWidget toggle = new ToggleWidget.Builder()
                .toggled(value != null && Boolean.TRUE.equals(value.value()))
                .onChange(changed)
                .entranceAnimation(false)
                .build();
            toggle.setSize(TOGGLE_WIDGET_WIDTH, TOGGLE_WIDGET_HEIGHT);
            return toggle;
        }
        if (editable && kind == ReSyncGenericDescriptorProjection.EditorKind.JSON) {
            return new TextAreaWidget.Builder()
                .text(inspectorValueText(value, true))
                .placeholder(placeholder(value))
                .size(250, INPUT_WIDGET_HEIGHT * 4)
                .onChange(text -> changed.run())
                .entranceAnimation(false)
                .build();
        }
        TextInputWidget text = new TextInputWidget.Builder()
            .text(inspectorValueText(value, false))
            .placeholder(placeholder(value))
            .forcePlaceholder(false)
            .numericOnly(editable && kind == ReSyncGenericDescriptorProjection.EditorKind.NUMBER)
            .size(250, INPUT_WIDGET_HEIGHT)
            .onChange(changed)
            .entranceAnimation(false)
            .build();
        text.setActive(editable && (kind == ReSyncGenericDescriptorProjection.EditorKind.TEXT
            || kind == ReSyncGenericDescriptorProjection.EditorKind.NUMBER));
        return text;
    }

    private void applyInspectorDrafts(List<InspectorDraft> drafts) {
        NodeInstanceId identity = nodeIdentity();
        if (identity == null || inspectorMutationHandler == null) {
            return;
        }
        List<InspectorFieldMutation> mutations = new ArrayList<>();
        try {
            for (InspectorDraft draft : drafts) {
                if (!draft.dirty().get() || draft.original() == null || !draft.editor().isActive()) {
                    continue;
                }
                InspectorFieldMutation mutation = inspectorMutation(draft);
                if (mutation != null) {
                    mutations.add(mutation);
                }
            }
        } catch (RuntimeException exception) {
            new Notification("Inspector", "Invalid Field Value", Notification.Type.ERROR);
            return;
        }
        if (mutations.isEmpty() || inspectorMutationHandler.apply(new InspectorMutation(identity, mutations))) {
            closeInspectorPopup();
        }
    }

    private InspectorFieldMutation inspectorMutation(InspectorDraft draft) {
        CoreGraphUiProjection.InspectorValue original = draft.original();
        if (draft.state().get() != null || draft.exactValue().get() != null) {
            return inspectorReplacement(original, draft.state().get(), draft.exactValue().get(), null);
        }
        ReSyncGenericDescriptorProjection.EditorKind kind = draft.field().editor().editorKind();
        Object material;
        if (draft.editor() instanceof ToggleWidget toggle) {
            material = toggle.getValue();
        } else if (draft.editor() instanceof TextAreaWidget textArea) {
            String text = textArea.getText();
            if (text == null || text.isBlank()) {
                return original.absent() ? null : new InspectorFieldMutation(original, null, true);
            }
            material = jsonMaterial(JsonParser.parseString(text));
        } else if (draft.editor() instanceof TextInputWidget textInput) {
            String text = textInput.getText();
            material = kind == ReSyncGenericDescriptorProjection.EditorKind.NUMBER
                ? inspectorNumber(original.type(), text) : text;
        } else {
            return null;
        }
        return inspectorReplacement(original, null, null, material);
    }

    static InspectorFieldMutation inspectorReplacement(CoreGraphUiProjection.InspectorValue original,
                                                        TypedValue.State state, TypedValue exactValue,
                                                        Object material) {
        Objects.requireNonNull(original, "Original inspector value is required");
        if (state == TypedValue.State.ABSENT) {
            return original.absent() ? null : new InspectorFieldMutation(original, null, true);
        }
        CoreGraphUiProjection.InspectorValue replacement;
        if (state == TypedValue.State.NULL) {
            replacement = original.asNull();
        } else if (exactValue != null) {
            replacement = new CoreGraphUiProjection.InspectorValue(original.fieldId(), exactValue);
        } else {
            replacement = material == null ? original.asNull() : original.withValue(material);
        }
        return replacement.canonicalJson().equals(original.canonicalJson()) ? null
            : new InspectorFieldMutation(original, replacement, false, exactValue != null);
    }

    private static Object inspectorNumber(TypeExpr type, String text) {
        BigDecimal value = new BigDecimal(text);
        TypeExpr unwrapped = type instanceof TypeExpr.OptionalType optional ? optional.element() : type;
        if (unwrapped instanceof TypeExpr.Named named && "builtin".equals(named.reference().ownerId())
            && "integer".equals(named.reference().localId())) {
            return value.toBigIntegerExact().longValueExact();
        }
        return value;
    }

    private static Object jsonMaterial(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), jsonMaterial(entry.getValue())));
            return result;
        }
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            value.getAsJsonArray().forEach(entry -> result.add(jsonMaterial(entry)));
            return result;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            return new BigDecimal(value.getAsString());
        }
        return value.getAsString();
    }

    private static String inspectorValueText(CoreGraphUiProjection.InspectorValue value, boolean json) {
        if (value == null || value.absent() || value.nullValue()) {
            return "";
        }
        if (value.state() != TypedValue.State.VALUE) {
            return value.canonicalJson();
        }
        Object material = value.value();
        if (!json && material instanceof String text) {
            return text;
        }
        return CanonicalJson.canonicalize(material);
    }

    private static String placeholder(CoreGraphUiProjection.InspectorValue value) {
        if (value == null || value.absent()) {
            return "Not Set";
        }
        if (value.nullValue()) {
            return "Null";
        }
        if (value.locatorValue()) {
            return "Resource Reference";
        }
        if (value.opaque()) {
            return "Unavailable";
        }
        return "";
    }

    private static String inspectorLabel(String section, String row, String field) {
        return Stream.of(section, row, field).filter(value -> value != null && !value.isBlank())
            .distinct().collect(Collectors.joining(" / "));
    }

    private void closeInspectorPopup() {
        closeInspectorPopup(inspectorPopup, inspectorPopupOwner);
    }

    private void closeInspectorPopup(PopupWidget popup, Screen owner) {
        if (popup == null) {
            return;
        }
        if (inspectorPopup == popup) {
            inspectorPopup = null;
            inspectorPopupOwner = null;
        }
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        if (overlay != null) {
            overlay.hidePopup(popup);
        } else {
            popup.hide();
        }
        if (owner != null) {
            owner.remove(popup);
        } else {
            WidgetCleanup.cleanup(popup);
        }
    }

    @Override
    public void close() {
        closeInspectorPopup();
    }

    @Override
    public void cleanup() {
        close();
    }

    private static NodeDefinition resolveDefinition(String serverId, String nodeType) {
        return NodeRegistry.getInstance() != null ? NodeRegistry.getInstance().getDefinition(serverId, nodeType) : null;
    }

    private static boolean typedCatalogAuthorityActive(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        ReSyncFlowClient flowClient = manager != null
            ? manager.existingFlowClient(WorldGenManager.connectionServerId(serverId)) : null;
        if (WorldGenManager.isWorldGenServerId(serverId)) {
            return flowClient == null || flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY;
        }
        return ReSyncTypedCatalogConsumer.authoritative(flowClient);
    }

    private void requestNodeRegistry() {
        ReSyncFlowClient client = catalogFlowClient(true);
        if (client != null) {
            if (WorldGenManager.isWorldGenServerId(serverId)
                && client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY) {
                client.requestCatalogPublication(true);
                return;
            }
            client.requestNodeRegistry();
        }
    }

    public boolean hasLoadedDefinition() {
        return definition != null;
    }

    protected boolean isDefinitionReadOnly() {
        return definitionReadOnly;
    }

    public String getDefinitionReadOnlyReason() {
        return definitionReadOnlyReason;
    }

    public String getDefinitionResolutionCause() {
        if (definitionLookupBlocked) {
            return "lookup_blocked";
        }
        if (definition == null) {
            return "definition_missing";
        }
        if (definitionReadOnly) {
            return "read_only:" + definitionReadOnlyReason;
        }
        return "editable";
    }

    protected boolean isDefinitionLookupBlocked() {
        return definitionLookupBlocked;
    }

    protected boolean isCoreWidget() {
        return coreResource != null;
    }

    private static String readOnlyReason(FlowNode node, boolean descriptorReadOnly, boolean coreWidget,
                                         NodeDefinition definition) {
        if (descriptorReadOnly) {
            return "Required descriptor capability is unavailable.";
        }
        ContractRef<NodeId> nodeIdentity = node != null ? FlowNodeWidget.typedNodeIdentity(node.getType()) : null;
        ContractRef<NodeId> definitionIdentity = definition != null
            ? FlowNodeWidget.typedNodeIdentity(definition.getOwner(), definition.getId()) : null;
        if (node == null || nodeIdentity == null && definitionIdentity == null) {
            return "Typed node capability identity is unavailable.";
        }
        Map<String, Object> values = node != null ? node.getInputValues() : null;
        if (!coreWidget && values != null && (values.containsKey(CALL_PARAMETERS_KEY) || values.containsKey(FUNCTION_SIGNATURE_KEY)
            || values.containsKey(FUNCTION_SIGNATURE_ISSUES_KEY))) {
            return "Function interaction capability is unavailable.";
        }
        return "";
    }

    private void createInputWidgets() {
        if (graph == null || nodeId == null) return;

        for (NodeDefinition.PinDefinition input : inputs) {
            if (!isInputWidgetEligible(input) || inputWidgets.containsKey(pinId(input))) {
                continue;
            }
            Widget widget = buildWidgetForPin(input);
            if (widget != null) {
                inputWidgets.put(pinId(input), widget);
            }
        }
        updatePinVisibility();
    }

    private void preflightOptionCatalogs() {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient client = catalogFlowClient(true);
        if (client == null) {
            return;
        }
        List<OptionCatalogLoader.Request> requests = new ArrayList<>();
        for (NodeDefinition.PinDefinition input : inputs) {
            String source = input != null ? input.getOptionsSource() : null;
            if (source == null || source.isBlank()) {
                continue;
            }
            Map<String, Object> context = optionCatalogContext(input);
            requests.add(OptionCatalogLoader.request(source, context));
        }
        Map<String, OptionCatalogLoader.Request> distinct = new LinkedHashMap<>();
        for (OptionCatalogLoader.Request request : requests) {
            String key = request.source() + "\u0000" + client.optionCatalogContextKey(request.context());
            distinct.putIfAbsent(key, request);
        }
        distinct.values().forEach(request -> client.requestOptionCatalog(request.source(), request.context()));
    }

    private Widget buildWidgetForPin(NodeDefinition.PinDefinition input) {
        Object currentValue = presentationInputValue(pinId(input));
        if (resourceReferenceInput(input) && exactResourceType(input) == null) {
            return buildUnavailableResourceSelector();
        }
        NodeDefinition.WidgetType widgetType = resolveWidgetType(input);
        if (resourceReferenceInput(input)) {
            widgetType = NodeDefinition.WidgetType.SEARCHABLE_LIST;
        }

        switch (widgetType) {
            case DROPDOWN -> {
                List<String> options = resolveOptions(input);
                if (options.isEmpty()) {
                    return buildTextInput(input, currentValue, input.getOptionsSource());
                }
                String selected = resolveSelected(options, currentValue, input.getDefaultValue());
                return buildDropdown(input, options, selected);
            }
            case SEARCHABLE_LIST -> {
                List<String> options = resolveOptions(input);
                if (options.isEmpty() && !resourceReferenceInput(input)) {
                    options = fallbackSearchableOptions(currentValue, input.getDefaultValue());
                }
                String selected = resolveSelected(options, currentValue, input.getDefaultValue());
                return buildSearchableSelector(input, options, selected);
            }
            case TOGGLE -> {
                boolean toggled = currentValue instanceof Boolean ? (boolean) currentValue : Boolean.parseBoolean(String.valueOf(currentValue));
                ToggleWidget widget = new ToggleWidget.Builder()
                    .toggled(toggled)
                    .onChange(() -> {
                        handleInputValueChanged(input);
                    })
                    .entranceAnimation(false)
                    .build();
                widget.setSize(TOGGLE_WIDGET_WIDTH, TOGGLE_WIDGET_HEIGHT);
                return widget;
            }
            case SLIDER -> {
                double value = 0.0;
                if (currentValue instanceof Number n) {
                    value = n.doubleValue();
                } else if (currentValue != null) {
                    try {
                        value = Double.parseDouble(currentValue.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                NodeDefinition.PinConstraints constraints = input.getConstraints();
                double min = constraints != null && constraints.getMin() != null ? constraints.getMin() : 0.0;
                double max = constraints != null && constraints.getMax() != null ? constraints.getMax() : 100.0;
                double step = constraints != null && constraints.getStep() != null ? constraints.getStep() : 1.0;
                return new SliderWidget.Builder()
                    .min(min)
                    .max(max)
                    .step(step)
                    .value(value)
                    .onChange(() -> {
                        handleInputValueChanged(input);
                    })
                    .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
                    .entranceAnimation(false)
                    .build();
            }
            case NUMBER -> {
                String textValue = currentValue != null ? currentValue.toString() : "";
                TextInputWidget widget = new TextInputWidget.Builder()
                    .text(textValue)
                    .placeholder("")
                    .forcePlaceholder(false)
                    .numericOnly(true)
                    .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
                    .onChange(() -> handleInputValueChanged(input))
                    .entranceAnimation(false)
                    .build();
                return widget;
            }
            case MULTILINE -> {
                String textValue = currentValue != null ? currentValue.toString() : "";
                return new TextAreaWidget.Builder()
                    .text(textValue)
                    .placeholder("")
                    .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT * 3)
                    .onChange(text -> handleInputValueChanged(input))
                    .entranceAnimation(false)
                    .build();
            }
            case COLOR -> {
                String textValue = currentValue != null ? currentValue.toString() : "#FFFFFF";
                return new ColorFieldWidget.Builder()
                    .color(textValue)
                    .onChange(() -> {
                        handleInputValueChanged(input);
                    })
                    .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
                    .entranceAnimation(false)
                    .build();
            }
            default -> {
                return buildTextInput(input, currentValue, "");
            }
        }
    }

    private Widget buildTextInput(NodeDefinition.PinDefinition input, Object currentValue, String placeholder) {
        String textValue = currentValue != null ? currentValue.toString() : "";
        return new TextInputWidget.Builder()
            .text(textValue)
            .placeholder(placeholder != null ? placeholder : "")
            .forcePlaceholder(false)
            .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
            .onChange(() -> handleInputValueChanged(input))
            .entranceAnimation(false)
            .build();
    }

    protected boolean shouldShowLiteralInput(NodeDefinition.PinDefinition input) {
        if (isStringTemplateValuePin(input)) {
            return false;
        }
        return true;
    }

    protected boolean shouldShowInputPin(NodeDefinition.PinDefinition input) {
        return true;
    }

    private void handleInputValueChanged(NodeDefinition.PinDefinition input) {
        if (reconcilingInputValues) {
            return;
        }
        if (nodeValueMutationHandler != null) {
            InputValueProposal proposal = inputValueProposal(input);
            applyCoreNodeValueProposal(input, proposal);
            return;
        }
        saveInputValue();
        refreshDependentCatalogs(pinId(input));
        if (managedResourceType(input) != null) {
            refreshInputWidgets();
        } else if (isFunctionCallNode() && "function".equals(pinId(input))) {
            refreshInputWidgets();
        } else {
            updatePinVisibility();
        }
        if (onMutation != null) {
            onMutation.run();
        }
    }

    private void refreshDependentCatalogs(String contextKey) {
        if (typedCatalogAuthorityActive()) {
            return;
        }
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry == null || contextKey == null || contextKey.isBlank()) {
            return;
        }
        Set<String> refreshedSources = new LinkedHashSet<>();
        for (NodeDefinition.PinDefinition candidate : inputs) {
            if (contextKey.equals(pinId(candidate))) {
                continue;
            }
            String sourceId = candidate.getOptionsSource();
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            FlowOptionSourceMetadata metadata = registry.getServerOptionSource(serverId, sourceId);
            if (metadata != null && metadata.getContextKeys().contains(contextKey) && refreshedSources.add(sourceId)) {
                refreshOptionCatalog(sourceId);
            }
        }
    }

    private boolean isInputWidgetEligible(NodeDefinition.PinDefinition input) {
        return input != null
            && input.getType() == NodeDefinition.PinType.DATA
            && shouldShowInputPin(input)
            && shouldShowLiteralInput(input)
            && isLiteralInput(input)
            && !isInputWired(pinId(input));
    }

    private Widget buildSearchableSelector(NodeDefinition.PinDefinition input, List<String> options, String selected) {
        if (input.getOptionSourceRef() != null) {
            return buildCoreSearchableSelector(input);
        }
        List<OptionCatalogItem> initialItems = resolveCatalogItems(input, options);
        if (isRealCatalogOption(selected)) {
            searchableSelectorValues.put(pinId(input), selected);
        }
        AnimatedButton button = new AnimatedButton.Builder()
            .label(selectorButtonLabel(initialItems, selected))
            .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
        button.setAction(() -> {
            var screen = ScreenManager.getInstance().getCurrentScreen();
            if (screen == null) return;
            String catalogFailure = catalogFailure(input);
            if (!catalogFailure.isBlank()) {
                new Notification("Catalog Unavailable", catalogFailure, Notification.Type.WARN);
                return;
            }
            List<String> currentOptions = resolveOptions(input);
            if (currentOptions.isEmpty() && !resourceReferenceInput(input)) {
                currentOptions = fallbackSearchableOptions(presentationInputValue(pinId(input)), input.getDefaultValue());
            }
            List<OptionCatalogItem> richItems = resolveCatalogItems(input, currentOptions);
            String selectedValue = searchableSelectorValues.getOrDefault(pinId(input), resourceId(presentationInputValue(pinId(input))));
            Consumer<String> onSelected = option -> {
                if (!isRealCatalogOption(option)) {
                    return;
                }
                searchableSelectorValues.put(pinId(input), option);
                button.setMessage(selectorButtonLabel(resolveCatalogItems(input, resolveOptions(input)), option));
                handleInputValueChanged(input);
            };
            int selectorX = hasLastScreenMouse ? lastScreenX : button.getX();
            int selectorY = hasLastScreenMouse ? lastScreenY : button.getY() + button.getHeight();
            Runnable refreshAction = OptionCatalogSelector.refreshAction(catalogServerId(), input.getOptionsSource(), optionCatalogContext(input));
            ItemSelectorWidget.AsyncItemSource itemSource = () -> catalogSelectorSnapshot(input, onSelected);
            if (screen instanceof FlowGraphDesignerScreen flowEditorScreen) {
                if (input.getOptionsSource() == null || input.getOptionsSource().isBlank()) {
                    flowEditorScreen.showNodeInputSelectorAtScreen(currentOptions, selectedValue, onSelected, selectorX, selectorY);
                } else {
                    flowEditorScreen.showAsyncNodeInputSelectorAtScreen(refreshAction, itemSource,
                        selectorButtonLabel(richItems, selectedValue), selectorX, selectorY);
                }
                return;
            }
            BrowserSafeState.ReferenceValue<ItemSelectorWidget> selector = new BrowserSafeState.ReferenceValue<>();
            ItemSelectorWidget.Builder selectorBuilder = new ItemSelectorWidget.Builder(screen)
                .size(180, 220)
                .dismissOnSelect(true)
                .onClose(() -> screen.remove(selector.get()));
            boolean catalogBacked = input.getOptionsSource() != null && !input.getOptionsSource().isBlank();
            if (catalogBacked) {
                selectorBuilder.asyncItems(refreshAction, itemSource);
            }
            selector.set(selectorBuilder.build());
            if (!catalogBacked) {
                for (String option : currentOptions) {
                    selector.get().addItem(option, () -> onSelected.accept(option));
                }
            }
            selector.get().setSelectedItem(selectorButtonLabel(richItems, selectedValue));
            screen.addDrawableChild(selector.get());
            selector.get().show(selectorX, selectorY);
        });
        return button;
    }

    private Widget buildCoreSearchableSelector(NodeDefinition.PinDefinition input) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label(typedValueLabel(corePinValue(input)))
            .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
        button.setAction(() -> {
            var screen = ScreenManager.getInstance().getCurrentScreen();
            if (screen == null) {
                return;
            }
            BrowserSafeState.ReferenceValue<ItemSelectorWidget> selector = new BrowserSafeState.ReferenceValue<>();
            selector.set(new ItemSelectorWidget.Builder(screen)
                .size(180, 220)
                .dismissOnSelect(true)
                .asyncItems(() -> refreshCoreOption(input.getOptionSourceRef()),
                    () -> coreOptionSnapshot(input.getOptionSourceRef(), () -> corePinValue(input), value -> {
                        CoreOptionSelection selection = coreOptionSelection(input.getOptionSourceRef()).orElse(null);
                        if (selection == null || !validCoreOption(input, selection.request(), value)) {
                            new Notification("Options", "Typed Option Is Incompatible", Notification.Type.ERROR);
                            return;
                        }
                        button.setMessage(typedValueLabel(value));
                        applyCoreTypedNodeValue(input, value);
                    }))
                .onClose(() -> screen.remove(selector.get()))
                .build());
            selector.get().setSelectedItem(typedValueLabel(corePinValue(input)));
            screen.addDrawableChild(selector.get());
            int selectorX = hasLastScreenMouse ? lastScreenX : button.getX();
            int selectorY = hasLastScreenMouse ? lastScreenY : button.getY() + button.getHeight();
            selector.get().show(selectorX, selectorY);
        });
        return button;
    }

    private Widget buildCoreInspectorSelector(ReSyncGenericDescriptorProjection.InspectorField field,
                                              CoreGraphUiProjection.InspectorValue original,
                                              BrowserSafeState.BooleanValue dirty, BrowserSafeState.ReferenceValue<TypedValue.State> state,
                                              BrowserSafeState.ReferenceValue<TypedValue> exactValue) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label(typedValueLabel(original != null ? original.typedValue() : null))
            .size(250, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
        button.setAction(() -> {
            var screen = ScreenManager.getInstance().getCurrentScreen();
            if (screen == null || original == null) {
                return;
            }
            BrowserSafeState.ReferenceValue<ItemSelectorWidget> selector = new BrowserSafeState.ReferenceValue<>();
            selector.set(new ItemSelectorWidget.Builder(screen)
                .size(220, 240)
                .dismissOnSelect(true)
                .asyncItems(() -> refreshCoreOption(field.optionSource()),
                    () -> coreOptionSnapshot(field.optionSource(),
                        () -> exactValue.get() != null ? exactValue.get() : original.typedValue(), value -> {
                        CoreOptionSelection selection = coreOptionSelection(field.optionSource()).orElse(null);
                        if (selection == null || !validCoreOption(original.type(), selection.request(), value)) {
                            new Notification("Options", "Typed Option Is Incompatible", Notification.Type.ERROR);
                            return;
                        }
                        exactValue.set(value);
                        state.set(null);
                        dirty.set(true);
                        button.setMessage(typedValueLabel(value));
                    }))
                .onClose(() -> screen.remove(selector.get()))
                .build());
            selector.get().setSelectedItem(typedValueLabel(original.typedValue()));
            screen.addDrawableChild(selector.get());
            selector.get().show(button.getX(), button.getY() + button.getHeight());
        });
        return button;
    }

    private Optional<CoreOptionSelection> coreOptionSelection(ContractRef<InspectorFieldId> source) {
        ReSyncFlowClient client = catalogFlowClient(false);
        if (client == null || source == null) {
            return Optional.empty();
        }
        return client.activeOptionSource(source)
            .flatMap(optionSource -> {
                OptionCatalogLoader.CoreQueryValues values = coreOptionQueryValues(optionSource);
                return OptionCatalogLoader.automaticCoreRequest(client, source, coreResource,
                    values.context(), values.dependencies(), "")
                    .map(request -> new CoreOptionSelection(client, request));
            });
    }

    private void refreshCoreOption(ContractRef<InspectorFieldId> source) {
        coreOptionSelection(source).ifPresent(selection ->
            OptionCatalogLoader.refresh(selection.client(), selection.request()));
    }

    private ItemSelectorWidget.AsyncItemSnapshot coreOptionSnapshot(ContractRef<InspectorFieldId> source,
        Supplier<TypedValue> selectedSupplier, Consumer<TypedValue> onSelected) {
        CoreOptionSelection selection = coreOptionSelection(source).orElse(null);
        return selection == null
            ? new ItemSelectorWidget.AsyncItemSnapshot(List.of(), false, "Options Unavailable")
            : OptionCatalogSelector.snapshot(OptionCatalogLoader.snapshot(selection.client(), selection.request()),
                selectedSupplier, onSelected, "No Options");
    }

    private OptionCatalogLoader.CoreQueryValues coreOptionQueryValues(InspectorOptionSource source) {
        Map<String, TypedValue> pinValues = new LinkedHashMap<>();
        for (NodeDefinition.PinDefinition input : inputs) {
            TypedValue value = corePinValue(input);
            if (input != null && input.getId() != null && value != null && value.state() != TypedValue.State.ABSENT) {
                pinValues.put(input.getId().canonicalText(), value);
            }
        }
        return OptionCatalogLoader.automaticValues(source, node != null ? node.getType() : "", pinValues);
    }

    private record CoreOptionSelection(ReSyncFlowClient client, OptionCatalogLoader.CoreRequest request) {
    }

    private boolean validCoreOption(NodeDefinition.PinDefinition input, OptionCatalogLoader.CoreRequest request,
                                    TypedValue value) {
        try {
            TypeExpr expected = input != null && input.getTypedType() != null
                ? input.getTypedType() : input != null ? CoreGraphUiProjection.descriptorType(input.getTypeRef()) : null;
            return value != null && validCoreOption(expected, request, value);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean validCoreOption(TypeExpr expected, OptionCatalogLoader.CoreRequest request, TypedValue value) {
        return expected != null && request != null && value != null
            && value.type().equals(expected) && value.type().equals(request.source().optionType());
    }

    private boolean applyCoreTypedNodeValue(NodeDefinition.PinDefinition input, TypedValue value) {
        NodeInstanceId identity = nodeIdentity();
        CoreRepeatableUiProjection.PinEndpoint endpoint = input != null ? corePinEndpoint(pinId(input)) : null;
        NodeValueMutation mutation = endpoint != null && identity != null && value != null
            ? new NodeValueMutation(identity, endpoint.pinId(), endpoint.elementId(), value) : null;
        if (mutation == null || nodeValueMutationHandler == null) {
            rebuildInputWidgets();
            return false;
        }
        inputValuePreviews.put(coreMutationKey(mutation.pinId(), mutation.elementId()), mutation);
        boolean accepted;
        try {
            accepted = nodeValueMutationHandler.apply(mutation);
        } catch (RuntimeException exception) {
            rejectInputValuePreview(mutation);
            throw exception;
        }
        if (!accepted) {
            rejectInputValuePreview(mutation);
            return false;
        }
        updatePinVisibility();
        if (onMutation != null) {
            onMutation.run();
        }
        return true;
    }

    private TypedValue corePinValue(NodeDefinition.PinDefinition input) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = input != null ? corePinEndpoint(pinId(input)) : null;
        PinValue value = endpoint == null ? null : endpoint.elementId() == null ? corePinValues.get(endpoint.pinId())
            : coreRepeatableGroup(endpoint).flatMap(group -> group.element(endpoint.elementId()))
                .flatMap(element -> element.value(endpoint.pinId())).orElse(null);
        return value != null ? value.value() : null;
    }

    private static String typedValueLabel(TypedValue value) {
        if (value == null) {
            return "Not Set";
        }
        return switch (value.state()) {
            case ABSENT -> "Absent";
            case NULL -> "Null";
            case VALUE -> value.value() instanceof String text ? text : CanonicalJson.canonicalize(value.value());
            case LOCATOR -> value.locator().id();
            case OPAQUE -> "Unavailable";
        };
    }

    private ItemSelectorWidget.AsyncItemSnapshot catalogSelectorSnapshot(NodeDefinition.PinDefinition input, Consumer<String> onSelected) {
        String source = input != null ? input.getOptionsSource() : null;
        if (source == null || source.isBlank()) {
            return new ItemSelectorWidget.AsyncItemSnapshot(List.of(), false, "No Options");
        }
        Map<String, Object> context = optionCatalogContext(input);
        ItemSelectorWidget.AsyncItemSnapshot snapshot = OptionCatalogSelector.snapshot(catalogServerId(), source, context, List::of,
            () -> searchableSelectorValues.getOrDefault(pinId(input), resourceId(presentationInputValue(pinId(input)))),
            onSelected, "No Options");
        String resourceType = managedResourceType(input);
        if (resourceType == null) {
            return snapshot;
        }
        String resourceName = ReSyncResourceCreator.resourceTypeName(resourceType);
        List<ItemSelectorWidget.AsyncItem> items = new ArrayList<>();
        items.add(new ItemSelectorWidget.AsyncItem("Create " + resourceName, "add.png",
            "Create, select, and configure a new " + resourceName + ".", "new create add " + resourceName,
            100, "", "Manage", () -> createManagedResource(input, resourceType, onSelected)));
        String selectedId = searchableSelectorValues.getOrDefault(pinId(input), resourceId(presentationInputValue(pinId(input))));
        if (!selectedId.isBlank()) {
            String selectedLabel = selectorButtonLabel(resolveCatalogItems(input, resolveOptions(input)), selectedId);
            items.add(new ItemSelectorWidget.AsyncItem("Edit " + selectedLabel, "edit.png",
                "Configure the selected " + resourceName + ".", "edit configure manage " + selectedLabel,
                90, "", "Manage", () -> openManagedResource(resourceType, selectedId)));
            items.add(new ItemSelectorWidget.AsyncItem("Delete " + selectedLabel, "delete.png",
                "Permanently delete the selected " + resourceName + ".", "delete remove " + selectedLabel,
                80, "", "Manage", () -> confirmManagedResourceDelete(input, resourceType, selectedId, selectedLabel)));
        }
        items.addAll(snapshot.items());
        return new ItemSelectorWidget.AsyncItemSnapshot(items, snapshot.loading(), "No " + resourceName + "s");
    }

    private String managedResourceType(NodeDefinition.PinDefinition input) {
        String source = input != null ? input.getOptionsSource() : null;
        return switch (source != null ? source : "") {
            case VARIABLE_CATALOG -> ReSyncResourceDragPayload.VARIABLE_DEFINITION;
            case TIMER_CATALOG -> ReSyncResourceDragPayload.TIMER_DEFINITION;
            case SCHEDULE_CATALOG -> ReSyncResourceDragPayload.SCHEDULE_DEFINITION;
            default -> null;
        };
    }

    private void createManagedResource(NodeDefinition.PinDefinition input, String resourceType, Consumer<String> onSelected) {
        var screen = ScreenManager.getInstance().getCurrentScreen();
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resourceType);
        if (screen == null || type == null) {
            return;
        }
        ReSyncResourceCreator.showCreatePopup(screen, catalogServerId(), resourceType, type.defaultFolder(), null, result -> {
            if (onSelected != null) {
                onSelected.accept(result.id());
            }
            requestOptionCatalog(input.getOptionsSource(), optionCatalogContext(input), true);
            openManagedResource(resourceType, result.id());
        });
    }

    private void openManagedResource(String resourceType, String id) {
        var screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen instanceof StudioScreen studioScreen) {
            studioScreen.openWorkspaceResource(resourceType, id);
        } else {
            new Notification("Open Resource", "Open ReSync Studio To Configure " + id, Notification.Type.WARN);
        }
    }

    private String resourceName(String type) {
        return ReSyncResourceCreator.resourceTypeName(type);
    }

    private List<OptionCatalogItem> resolveCatalogItems(NodeDefinition.PinDefinition input, List<String> values) {
        String source = input != null ? input.getOptionsSource() : null;
        if (source == null || source.isBlank()) {
            return List.of();
        }
        Set<String> accepted = new LinkedHashSet<>(values != null ? values : List.of());
        ReSyncFlowClient flowClient = catalogFlowClient(false);
        String catalogServerId = catalogServerId();
        if (catalogServerId == null) {
            return List.of();
        }
        String contextKey = flowClient != null ? flowClient.optionCatalogContextKey(optionCatalogContext(input)) : "";
        return OptionCatalogCache.getInstance().getItems(catalogServerId, source, contextKey).stream()
            .filter(item -> item != null && item.getValue() != null && (accepted.isEmpty() || accepted.contains(item.getValue())))
            .toList();
    }

    private String catalogFailure(NodeDefinition.PinDefinition input) {
        String source = input != null ? input.getOptionsSource() : null;
        if (source == null || source.isBlank()) {
            return "";
        }
        ReSyncFlowClient flowClient = catalogFlowClient(false);
        String catalogServerId = catalogServerId();
        if (catalogServerId == null) {
            return "";
        }
        String contextKey = flowClient != null ? flowClient.optionCatalogContextKey(optionCatalogContext(input)) : "";
        OptionCatalogCache cache = OptionCatalogCache.getInstance();
        String status = cache.getStatus(catalogServerId, source, contextKey);
        if ("available".equals(status) || "stale".equals(status) || "missing".equals(status) && !cache.hasCatalog(catalogServerId, source, contextKey)) {
            return "";
        }
        String diagnostic = cache.getDiagnostic(catalogServerId, source, contextKey);
        return diagnostic.isBlank() ? status : diagnostic;
    }

    private String selectedCatalogLabel(List<OptionCatalogItem> items, String selectedValue) {
        if (selectedValue == null) {
            return "";
        }
        return items.stream()
            .filter(item -> selectedValue.equals(item.getValue()))
            .map(OptionCatalogItem::getLabel)
            .findFirst()
            .orElse(selectedValue);
    }

    private String selectorButtonLabel(List<OptionCatalogItem> items, String selectedValue) {
        String label = selectedCatalogLabel(items, selectedValue);
        return isRealCatalogOption(label) ? label : "Select";
    }

    private boolean isRealCatalogOption(String value) {
        return value != null && !value.isBlank() && !"Loading".equals(value) && !"No Options".equals(value);
    }

    private String catalogSearchTerms(OptionCatalogItem item) {
        Object aliases = item.getMetadata().get("aliases");
        return String.join(" ", item.getValue(), item.getLabel(), item.getDescription(), item.getGroup(), aliases != null ? aliases.toString() : "");
    }

    private DropDownWidget<String> buildDropdown(NodeDefinition.PinDefinition input, List<String> options, String selected) {
        return new DropDownWidget.Builder<>(options)
            .selectedItem(selected)
            .onSelectionChanged(value -> {
                handleInputValueChanged(input);
            })
            .maxVisibleItems(8)
            .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
    }

    private AnimatedButton buildSelectorButton(List<String> options, String selected, int width, Consumer<String> onSelected) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label(selected)
            .size(width, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
        button.setAction(() -> showStringSelector(button, options, button.getMessage(), value -> {
            if (value == null || value.isBlank() || "Loading".equals(value)) {
                return;
            }
            button.setMessage(value);
            onSelected.accept(value);
        }));
        return button;
    }

    private AnimatedButton buildScreenSelectorButton(List<String> options, String selected, int width, Consumer<String> onSelected) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label(selected)
            .size(width, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
        button.setAction(() -> showScreenSelector(button, options, button.getMessage(), value -> {
            if (value == null || value.isBlank() || "Loading".equals(value)) {
                return;
            }
            button.setMessage(value);
            onSelected.accept(value);
        }));
        return button;
    }

    private void showStringSelector(AnimatedButton anchor, List<String> options, String selected, Consumer<String> onSelected) {
        var screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null || anchor == null || options == null || options.isEmpty() || onSelected == null) {
            return;
        }
        if (screen instanceof FlowGraphDesignerScreen flowEditorScreen) {
            flowEditorScreen.showNodeInputSelectorAtScreen(options, selected, onSelected,
                hasLastScreenMouse ? lastScreenX : anchor.getX(), hasLastScreenMouse ? lastScreenY : anchor.getY() + anchor.getHeight());
            return;
        }
        BrowserSafeState.ReferenceValue<ItemSelectorWidget> selector = new BrowserSafeState.ReferenceValue<>();
        selector.set(new ItemSelectorWidget.Builder(screen)
            .size(180, 220)
            .dismissOnSelect(true)
            .onClose(() -> screen.remove(selector.get()))
            .build());
        for (String option : options) {
            selector.get().addItem(option, () -> onSelected.accept(option));
        }
        selector.get().setSelectedItem(selected);
        screen.addDrawableChild(selector.get());
        selector.get().show(hasLastScreenMouse ? lastScreenX : anchor.getX(), hasLastScreenMouse ? lastScreenY : anchor.getY() + anchor.getHeight());
    }

    private void showBranchSelector(AnimatedButton anchor, List<String> options, String selected, Consumer<String> onSelected) {
        var screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null || anchor == null || options == null || options.isEmpty() || onSelected == null) {
            return;
        }
        int currentMouseX = ScreenManager.getInstance().getMouseX();
        int currentMouseY = ScreenManager.getInstance().getMouseY();
        int selectorX = currentMouseX > 0 ? currentMouseX : hasLastScreenMouse ? lastScreenX : anchor.getX();
        int selectorY = currentMouseY > 0 ? currentMouseY : hasLastScreenMouse ? lastScreenY : anchor.getY() + anchor.getHeight();
        if (screen instanceof FlowGraphDesignerScreen flowEditorScreen) {
            flowEditorScreen.showNodeInputSelectorAtScreen(options, selected, onSelected, selectorX, selectorY);
            return;
        }
        BrowserSafeState.ReferenceValue<ItemSelectorWidget> selector = new BrowserSafeState.ReferenceValue<>();
        selector.set(new ItemSelectorWidget.Builder(screen)
            .size(180, 220)
            .dismissOnSelect(true)
            .onClose(() -> screen.remove(selector.get()))
            .build());
        for (String option : options) {
            selector.get().addItem(option, () -> onSelected.accept(option));
        }
        selector.get().setSelectedItem(selected);
        screen.addDrawableChild(selector.get());
        selector.get().show(selectorX, selectorY);
    }

    private void showScreenSelector(AnimatedButton anchor, List<String> options, String selected, Consumer<String> onSelected) {
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        if (overlay == null || anchor == null || options == null || options.isEmpty() || onSelected == null) {
            return;
        }
        BrowserSafeState.ReferenceValue<ItemSelectorWidget> selector = new BrowserSafeState.ReferenceValue<>();
        selector.set(new ItemSelectorWidget.Builder(overlay)
            .size(180, 220)
            .dismissOnSelect(true)
            .onClose(() -> overlay.remove(selector.get()))
            .build());
        selector.get().setLayer(900);
        selector.get().setPriority(30);
        for (String option : options) {
            selector.get().addItem(option, () -> onSelected.accept(option));
        }
        selector.get().setSelectedItem(selected);
        overlay.addDrawableChild(selector.get());
        selector.get().show(anchor.getX(), anchor.getY() + anchor.getHeight());
    }

    private List<String> resolveOptions(NodeDefinition.PinDefinition input) {
        List<String> options = input.getOptions();
        if (options != null && !options.isEmpty()) {
            return options;
        }
        String source = input.getOptionsSource();
        if (source != null && !source.isBlank()) {
            ReSyncFlowClient flowClient = catalogFlowClient(false);
            String catalogServerId = catalogServerId();
            if (catalogServerId == null) {
                return List.of();
            }
            Map<String, Object> context = optionCatalogContext(input);
            String contextKey = flowClient != null ? flowClient.optionCatalogContextKey(context) : "";
            requestOptionCatalog(source, context);
            List<String> values = OptionCatalogCache.getInstance().getValues(catalogServerId, source, contextKey);
            return values;
        }
        return List.of();
    }

    private List<String> fallbackSearchableOptions(Object currentValue, String defaultValue) {
        String value = resourceId(currentValue);
        if (value.isBlank()) {
            value = defaultValue;
        }
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private void requestOptionCatalog(String source, Map<String, Object> context) {
        requestOptionCatalog(source, context, false);
    }

    private void requestOptionCatalog(String source, Map<String, Object> context, boolean forceRefresh) {
        ReSyncFlowClient flowClient = catalogFlowClient(false);
        if (flowClient != null && source != null && !source.isBlank()) {
            flowClient.requestOptionCatalog(source, context, forceRefresh);
        }
    }

    private Widget buildUnavailableResourceSelector() {
        return new AnimatedButton.Builder()
            .label("Resource Type Required")
            .active(false)
            .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
    }

    private boolean applyCoreNodeValueProposal(NodeDefinition.PinDefinition input, InputValueProposal proposal) {
        NodeInstanceId identity = nodeIdentity();
        CoreRepeatableUiProjection.PinEndpoint endpoint = input != null ? corePinEndpoint(pinId(input)) : null;
        NodeValueMutation mutation = proposal != null && endpoint != null && identity != null
            ? new NodeValueMutation(identity, endpoint.pinId(), endpoint.elementId(), proposal.value(), proposal.remove()) : null;
        if (mutation == null || nodeValueMutationHandler == null) {
            rebuildInputWidgets();
            return false;
        }
        inputValuePreviews.put(coreMutationKey(mutation.pinId(), mutation.elementId()), mutation);
        boolean accepted;
        try {
            accepted = nodeValueMutationHandler.apply(mutation);
        } catch (RuntimeException exception) {
            rejectInputValuePreview(mutation);
            throw exception;
        }
        if (!accepted) {
            rejectInputValuePreview(mutation);
            return false;
        }
        updatePinVisibility();
        if (onMutation != null) {
            onMutation.run();
        }
        return true;
    }

    private NodeInstanceId nodeIdentity() {
        try {
            return NodeInstanceId.parseCanonicalText(nodeId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private InputValueProposal inputValueProposal(NodeDefinition.PinDefinition input) {
        if (input == null || input.getId() == null) {
            return null;
        }
        Widget widget = inputWidgets.get(pinId(input));
        if (widget == null || !widget.isVisible()) {
            return null;
        }
        if (widget instanceof TextInputWidget textInput) {
            String value = textInput.getText();
            if (value.isEmpty()) {
                return InputValueProposal.removal();
            }
            Object converted = convertLiteralValue(value, input);
            return converted != null ? InputValueProposal.value(converted) : null;
        }
        if (widget instanceof ToggleWidget toggle) {
            return InputValueProposal.value(toggle.getValue());
        }
        if (widget instanceof AnimatedButton button) {
            String value = searchableSelectorValues.getOrDefault(pinId(input), button.getMessage());
            if (value == null || value.isBlank() || "Loading".equals(value)) {
                return null;
            }
            Object converted = convertLiteralValue(value, input);
            return converted != null ? InputValueProposal.value(converted) : null;
        }
        if (widget instanceof DropDownWidget<?> dropdown) {
            Object value = dropdown.getSelectedItem();
            Object converted = value != null ? convertLiteralValue(value.toString(), input) : null;
            return converted != null ? InputValueProposal.value(converted) : null;
        }
        if (widget instanceof SliderWidget slider) {
            return InputValueProposal.value(slider.getValue());
        }
        if (widget instanceof TextAreaWidget textArea) {
            String value = textArea.getText();
            return value.isEmpty() ? InputValueProposal.removal() : InputValueProposal.value(value);
        }
        if (widget instanceof ColorFieldWidget colorField) {
            String value = colorField.getColor();
            return value.isEmpty() ? InputValueProposal.removal() : InputValueProposal.value(value);
        }
        return null;
    }

    private record InputValueProposal(Object value, boolean remove) {
        private static InputValueProposal value(Object value) {
            return value != null ? new InputValueProposal(value, false) : null;
        }

        private static InputValueProposal removal() {
            return new InputValueProposal(null, true);
        }
    }

    private Object presentationInputValue(String pin) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pin);
        if (endpoint == null) {
            return node.getInputValues() != null ? node.getInputValues().get(pin) : null;
        }
        NodeValueMutation preview = inputValuePreviews.get(coreMutationKey(endpoint.pinId(), endpoint.elementId()));
        if (preview != null) {
            return preview.remove() ? null : preview.value();
        }
        if (endpoint.elementId() != null) {
            return coreRepeatableGroup(endpoint).flatMap(group -> group.element(endpoint.elementId()))
                .flatMap(element -> element.value(endpoint.pinId()))
                .map(value -> presentedValue(value.value())).orElse(null);
        }
        return node.getInputValues() != null ? node.getInputValues().get(pin) : null;
    }

    private Map<String, Object> presentationInputValues() {
        Map<String, Object> values = node.getInputValues() != null
            ? new LinkedHashMap<>(node.getInputValues()) : new LinkedHashMap<>();
        inputValuePreviews.values().stream().filter(preview -> preview.elementId() == null).forEach(preview -> {
            String pin = preview.pinId().canonicalText();
            if (preview.remove()) {
                values.remove(pin);
            } else {
                values.put(pin, preview.value());
            }
        });
        return values;
    }

    void rejectInputValuePreview(NodeValueMutation mutation) {
        if (mutation == null || mutation.pinId() == null) {
            return;
        }
        String pin = coreMutationKey(mutation.pinId(), mutation.elementId());
        NodeValueMutation current = inputValuePreviews.get(pin);
        if (sameMutation(current, mutation)) {
            inputValuePreviews.remove(pin);
            rebuildInputWidgets();
        }
    }

    void commitInputValuePreview(NodeValueMutation mutation) {
        if (mutation == null || mutation.pinId() == null) {
            return;
        }
        String pin = coreMutationKey(mutation.pinId(), mutation.elementId());
        NodeValueMutation current = inputValuePreviews.get(pin);
        if (sameMutation(current, mutation)) {
            inputValuePreviews.remove(pin);
            refreshInputWidgets();
        }
    }

    private boolean sameMutation(NodeValueMutation first, NodeValueMutation second) {
        return first != null && second != null && first.mutationId().equals(second.mutationId());
    }

    boolean hasInputValuePreview(PinId pin) {
        return pin != null && inputValuePreviews.containsKey(coreMutationKey(pin, null));
    }

    private static String coreMutationKey(PinId pinId, RepeatableElementId elementId) {
        return pinId.canonicalText() + '\u0000' + (elementId != null ? elementId.canonicalText() : "");
    }

    private String catalogServerId() {
        return serverId == null || serverId.isBlank() ? null : WorldGenManager.connectionServerId(serverId);
    }

    private ReSyncFlowClient catalogFlowClient(boolean requestConnection) {
        String catalogServerId = catalogServerId();
        FlowManager manager = FlowManager.getInstance();
        if (catalogServerId == null || manager == null) {
            return null;
        }
        ReSyncFlowClient flowClient = manager.existingFlowClient(catalogServerId);
        if (flowClient != null || !requestConnection || catalogConnectionRequested) {
            return flowClient;
        }
        catalogConnectionRequested = true;
        return manager.ensureFlowClient(catalogServerId);
    }

    private boolean typedCatalogAuthorityActive() {
        return typedCatalogAuthorityActive(serverId);
    }

    private boolean legacyFunctionIdentityMode() {
        return !isCoreWidget() && !typedCatalogAuthorityActive();
    }

    private String generatedFunctionParameterId(String scope, int index) {
        String graphIdentity = graph != null && graph.getId() != null && !graph.getId().isBlank()
            ? graph.getId().trim() : "graph";
        String nodeIdentity = nodeId != null && !nodeId.isBlank() ? nodeId.trim() : "node";
        return FunctionParameterId.deterministic(scope + '\u0000' + graphIdentity + '\u0000' + nodeIdentity + '\u0000' + index)
            .canonicalText();
    }

    private String newFunctionParameterId() {
        return FunctionParameterId.interactive().canonicalText();
    }

    private String normalizedCallParameterId(Object rawId, int index) {
        if (rawId != null) {
            String value = rawId.toString().trim();
            if (!value.isBlank()) {
                try {
                    return FunctionParameterId.parseCanonicalText(value).canonicalText();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return generatedFunctionParameterId("call-parameter", index);
    }

    private Map<String, Object> optionCatalogContext(NodeDefinition.PinDefinition input) {
        Map<String, Object> context = new HashMap<>();
        context.putAll(presentationInputValues());
        CoreRepeatableUiProjection.PinEndpoint endpoint = input != null ? corePinEndpoint(pinId(input)) : null;
        if (endpoint != null && endpoint.elementId() != null) {
            CoreRepeatableUiProjection.Group group = coreRepeatableGroup(endpoint).orElse(null);
            if (group != null) {
                Set<PinId> memberIds = group.members().stream().map(NodeDefinition.PinDefinition::getId)
                    .collect(Collectors.toSet());
                memberIds.forEach(pinId -> context.remove(pinId.canonicalText()));
                group.element(endpoint.elementId()).ifPresent(element -> element.values().forEach((pinId, value) ->
                    context.put(pinId.canonicalText(), presentedValue(value.value()))));
                inputValuePreviews.values().stream()
                    .filter(preview -> endpoint.elementId().equals(preview.elementId())
                        && memberIds.contains(preview.pinId()))
                    .forEach(preview -> {
                        String pinId = preview.pinId().canonicalText();
                        if (preview.remove()) {
                            context.remove(pinId);
                        } else {
                            context.put(pinId, preview.value());
                        }
                    });
            }
        }
        if (ScreenManager.getInstance().getCurrentScreen() instanceof FlowGraphDesignerScreen designer) {
            context.putAll(designer.optionCatalogContext());
        }
        context.put("$nodeType", node.getType() != null ? node.getType() : "");
        String inputPin = endpoint != null ? endpoint.pinId().canonicalText() : input != null ? pinId(input) : "";
        context.put("$pin", inputPin);
        if (!inputPin.isBlank()) {
            context.remove(inputPin);
        }
        return context;
    }

    private String resolveSelected(List<String> options, Object currentValue, String defaultValue) {
        String selected = resourceId(currentValue);
        if (selected.isBlank()) {
            selected = defaultValue;
        }
        if (selected == null || selected.isBlank()) {
            selected = options.isEmpty() ? "" : options.getFirst();
        }
        for (String option : options) {
            if (option.equalsIgnoreCase(selected)) {
                return option;
            }
        }
        return selected;
    }

    private NodeDefinition.WidgetType resolveWidgetType(NodeDefinition.PinDefinition input) {
        if (resourceReferenceInput(input)) {
            return NodeDefinition.WidgetType.SEARCHABLE_LIST;
        }
        if (input.getWidgetType() != null && input.getWidgetType() != NodeDefinition.WidgetType.AUTO) {
            return input.getWidgetType();
        }
        if (input.getOptionSourceRef() != null) {
            return NodeDefinition.WidgetType.SEARCHABLE_LIST;
        }
        String optionsSource = input.getOptionsSource();
        if (optionsSource != null && !optionsSource.isBlank()) {
            if (typedCatalogAuthorityActive()) {
                return NodeDefinition.WidgetType.SEARCHABLE_LIST;
            }
            NodeRegistry registry = NodeRegistry.getInstance();
            FlowOptionSourceMetadata meta = registry != null ? registry.getServerOptionSource(serverId, optionsSource) : null;
            if (meta != null) {
                String metadataWidget = meta.getWidgetType();
                if (metadataWidget != null && !metadataWidget.isBlank()) {
                    try {
                        NodeDefinition.WidgetType resolved = NodeDefinition.WidgetType.fromSerializedName(metadataWidget);
                        if (resolved != NodeDefinition.WidgetType.AUTO) {
                            return resolved;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (!meta.isSearchable()) {
                    return NodeDefinition.WidgetType.DROPDOWN;
                }
            }
            return NodeDefinition.WidgetType.SEARCHABLE_LIST;
        }
        if (input.getDataType() == FlowDataType.BOOLEAN) {
            return NodeDefinition.WidgetType.TOGGLE;
        }
        return NodeDefinition.WidgetType.TEXT;
    }

    private void seedDefaultInputValues() {
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        for (NodeDefinition.PinDefinition input : inputs) {
            if (node.getInputValues().containsKey(pinId(input))) {
                continue;
            }
            if (input.getTypedDefault() != null) {
                continue;
            }
            if (input.getDefaultValue() != null) {
                Object value = convertLiteralValue(input.getDefaultValue(), input);
                if (value != null) {
                    node.getInputValues().put(pinId(input), value);
                }
            }
        }
    }

    private void updatePinVisibility() {
        visibleInputs.clear();
        visibleInputsById.clear();
        if (node.getInputValues() == null && inputValuePreviews.isEmpty()) {
            for (NodeDefinition.PinDefinition input : inputs) {
                if (shouldShowInputPin(input)) {
                    visibleInputs.add(input);
                    visibleInputsById.putIfAbsent(pinId(input), input);
                }
            }
            createOutputWidgets();
            return;
        }
        if (!definitionReadOnly && nodeValueMutationHandler == null) {
            seedFlowBranches();
        }
        for (NodeDefinition.PinDefinition input : inputs) {
            boolean shouldShow = shouldShowInputPin(input) && evaluateVisibleWhen(input, input.getVisibleWhen());
            Widget widget = inputWidgets.get(pinId(input));
            if (widget != null) {
                widget.setVisible(shouldShow);
            }
            if (shouldShow) {
                visibleInputs.add(input);
                visibleInputsById.putIfAbsent(pinId(input), input);
            }
        }
        createOutputWidgets();
        updateSize();
        updateInputWidgetPositions();
        updateOutputWidgetPositions();
    }

    private void seedFlowBranches() {
        List<NodeDefinition.PinDefinition> flowOutputs = new ArrayList<>();
        for (NodeDefinition.PinDefinition output : outputs) {
            if (isFlowOutput(output) && evaluateVisibleWhen(output, output.getVisibleWhen())) {
                flowOutputs.add(output);
            }
        }
        if (flowOutputs.size() > 2) {
            node.getInputValues().put(FLOW_BRANCHES_KEY, resolveFlowBranches(flowOutputs));
        }
    }

    private boolean evaluateVisibleWhen(Map<String, String> visibleWhen) {
        return evaluateVisibleWhen(null, visibleWhen);
    }

    private boolean evaluateVisibleWhen(NodeDefinition.PinDefinition pin, Map<String, String> visibleWhen) {
        if (visibleWhen == null || visibleWhen.isEmpty()) {
            return true;
        }
        CoreRepeatableUiProjection.PinEndpoint endpoint = pin != null ? corePinEndpoint(pinId(pin)) : null;
        for (Map.Entry<String, String> condition : visibleWhen.entrySet()) {
            String conditionPin = condition.getKey();
            if (endpoint != null && endpoint.elementId() != null) {
                try {
                    String viewPin = coreViewPin(PinId.of(conditionPin), endpoint.elementId());
                    if (viewPin != null) {
                        conditionPin = viewPin;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            Object actualValue = presentationInputValue(conditionPin);
            boolean matches = false;
            for (String expected : condition.getValue().split(",")) {
                if (matchesVisibleValue(actualValue, expected.trim())) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesVisibleValue(Object actualValue, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (actualValue instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && matchesVisibleValue(value, expected)) {
                    return true;
                }
            }
            return false;
        }
        String actual = actualValue != null ? actualValue.toString().trim() : "";
        if (expected.endsWith("*")) {
            String prefix = expected.substring(0, expected.length() - 1);
            return actual.regionMatches(true, 0, prefix, 0, prefix.length());
        }
        return actual.equalsIgnoreCase(expected);
    }

    public void refreshInputWidgets() {
        resetDefinitionPins();
        applyFunctionParameterPins();
        applyFunctionCallSignaturePins();
        applyAdvancedInputPins();
        applyAdvancedOutputPins();
        applyRemovedOptionalInputs();
        updateAddInputButtons();
        updateStringTemplatePins();
        preflightOptionCatalogs();
        List<String> removed = inputWidgets.keySet().stream()
            .filter(pinName -> !isInputWidgetEligible(findInputDefinition(pinName))).toList();
        for (String pinName : removed) {
            WidgetCleanup.cleanup(inputWidgets.remove(pinName));
            searchableSelectorValues.remove(pinName);
        }
        createInputWidgets();
        createOutputWidgets();
        reconcileInputValues();
        updateSize();
    }

    private void reconcileInputValues() {
        if (nodeValueMutationHandler == null && !isCoreWidget()) {
            return;
        }
        deferredInputValues.retainAll(inputWidgets.keySet());
        for (NodeDefinition.PinDefinition input : inputs) {
            if (!reconcileInputValue(input)) {
                deferredInputValues.add(pinId(input));
            } else {
                deferredInputValues.remove(pinId(input));
            }
        }
    }

    private boolean reconcileInputValue(NodeDefinition.PinDefinition input) {
        if (input == null || resourceReferenceInput(input) && exactResourceType(input) == null) {
            return true;
        }
        String pin = pinId(input);
        Widget widget = inputWidgets.get(pin);
        if (widget == null) {
            return true;
        }
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pin);
        Widget focused = widget.getFocusedDescendant();
        if (endpoint != null && inputValuePreviews.containsKey(coreMutationKey(endpoint.pinId(), endpoint.elementId()))
            || widget.isFocused() || focused != null && focused.isFocused()) {
            return false;
        }
        Object value = presentationInputValue(pin);
        String text = value != null ? value.toString() : "";
        reconcilingInputValues = true;
        try {
            if (widget instanceof TextInputWidget field) {
                if (!field.getText().equals(text)) field.setText(text);
            } else if (widget instanceof TextAreaWidget field) {
                if (!field.getText().equals(text)) field.setText(text);
            } else if (widget instanceof ToggleWidget toggle) {
                toggle.setValue(value instanceof Boolean flag ? flag : Boolean.parseBoolean(text));
            } else if (widget instanceof SliderWidget slider) {
                double number = value instanceof Number numeric ? numeric.doubleValue() : 0D;
                if (!(value instanceof Number) && !text.isBlank()) {
                    try {
                        number = Double.parseDouble(text);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (Double.isFinite(number)) slider.setValue(number);
            } else if (widget instanceof ColorFieldWidget field) {
                String color = value != null ? text : "#FFFFFF";
                if (!field.getColor().equals(color)) field.setColor(color);
            } else if (widget instanceof DropDownWidget<?> dropdown) {
                reconcileDropdown(input, dropdown, value);
            } else if (widget instanceof AnimatedButton button && (resourceReferenceInput(input)
                || resolveWidgetType(input) == NodeDefinition.WidgetType.SEARCHABLE_LIST)) {
                if (input.getOptionSourceRef() != null) {
                    button.setMessage(typedValueLabel(corePinValue(input)));
                } else {
                    String selected = resourceId(value);
                    searchableSelectorValues.put(pin, selected);
                    button.setMessage(selectorButtonLabel(resolveCatalogItems(input, resolveOptions(input)), selected));
                }
            }
        } finally {
            reconcilingInputValues = false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void reconcileDropdown(NodeDefinition.PinDefinition input, DropDownWidget<?> widget, Object value) {
        DropDownWidget<String> dropdown = (DropDownWidget<String>) widget;
        List<String> options = resolveOptions(input);
        String selected = resolveSelected(options, value, input.getDefaultValue());
        if (!Objects.equals(selected, dropdown.getSelectedItem()) || !options.equals(dropdown.getItems())) {
            dropdown.setItems(options, selected);
        }
    }

    private void resetDefinitionPins() {
        inputs.clear();
        outputs.clear();
        if (definition != null) {
            inputs.addAll(definition.getInputs());
            outputs.addAll(definition.getOutputs());
        }
    }

    private void applyFunctionCallSignaturePins() {
        if (!isFunctionSignatureNode()) {
            return;
        }
        String functionId = node.getInputValues() != null ? resourceId(presentationInputValue("function")) : "";
        NodeRegistry registry = NodeRegistry.getInstance();
        NodeDefinition signature = registry != null && !functionId.isBlank()
            ? registry.getDefinition(serverId, CUSTOM_FUNCTION_NODE_PREFIX + functionId)
            : null;
        boolean dynamic = isInputWired("function");
        FunctionCallPinModel.ResolvedPins resolved = FunctionCallPinModel.resolve(inputs, outputs, signature, dynamic,
            callParameterPins());
        inputs.clear();
        inputs.addAll(resolved.inputs());
        outputs.clear();
        outputs.addAll(resolved.outputs());
        syncFunctionSignature(resolved.signatureInputs(), resolved.signatureOutputs());
    }

    private void syncFunctionSignature(Map<String, String> nextInputs, Map<String, String> nextOutputs) {
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        Map<String, String> previousInputs = storedSignatureTypes("inputs");
        Map<String, String> previousOutputs = storedSignatureTypes("outputs");
        boolean genericInputAvailable = inputs.stream().anyMatch(input -> "arguments".equals(pinId(input)));
        migrateFunctionPins(FunctionCallPinModel.pinMigrations(previousInputs, nextInputs, true, genericInputAvailable), true);
        migrateFunctionPins(FunctionCallPinModel.pinMigrations(previousOutputs, nextOutputs, false, false), false);
        removeUnknownFunctionConnections();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("inputs", nextInputs);
        snapshot.put("outputs", nextOutputs);
        node.getInputValues().put(FUNCTION_SIGNATURE_KEY, snapshot);
        node.getInputValues().remove(FUNCTION_SIGNATURE_ISSUES_KEY);
    }

    private void migrateFunctionPins(Map<String, String> migrations, boolean input) {
        if (migrations.isEmpty()) {
            return;
        }
        if (graph != null && graph.getConnections() != null) {
            for (FlowConnection connection : graph.getConnections()) {
                if (input && nodeId.equals(connection.getTargetNodeId())) {
                    String target = migrations.get(connection.getTargetPinId());
                    if (target != null) {
                        connection.setTargetPinId(target);
                    }
                } else if (!input && nodeId.equals(connection.getSourceNodeId())) {
                    String source = migrations.get(connection.getSourcePinId());
                    if (source != null) {
                        connection.setSourcePinId(source);
                    }
                }
                if (input && nodeId.equals(connection.getEditorSourceNodeId())) {
                    for (Map.Entry<String, String> migration : migrations.entrySet()) {
                        if (passthroughOutputPin(migration.getKey()).equals(connection.getEditorSourcePinId())) {
                            connection.setEditorSourcePinId(passthroughOutputPin(migration.getValue()));
                        }
                    }
                }
            }
        }
        if (input && graph != null && graph.getEditorPassthroughs() != null) {
            for (FlowGraph.EditorPassthrough passthrough : graph.getEditorPassthroughs()) {
                if (nodeId.equals(passthrough.getNodeId()) && migrations.containsKey(passthrough.getInputPinId())) {
                    passthrough.setInputPinId(migrations.get(passthrough.getInputPinId()));
                }
            }
        }
        if (input) {
            for (Map.Entry<String, String> migration : migrations.entrySet()) {
                if (node.getInputValues().containsKey(migration.getKey()) && !node.getInputValues().containsKey(migration.getValue())) {
                    node.getInputValues().put(migration.getValue(), node.getInputValues().remove(migration.getKey()));
                }
            }
        }
    }

    private void removeUnknownFunctionConnections() {
        if (graph == null || graph.getConnections() == null) {
            return;
        }
        Set<String> inputNames = inputs.stream().map(NodeWidget::pinId).collect(Collectors.toSet());
        Set<String> outputNames = outputs.stream().map(NodeWidget::pinId).collect(Collectors.toSet());
        graph.getConnections().removeIf(connection ->
            nodeId.equals(connection.getTargetNodeId()) && !inputNames.contains(connection.getTargetPinId())
                || nodeId.equals(connection.getSourceNodeId()) && !outputNames.contains(connection.getSourcePinId()));
        for (FlowConnection connection : graph.getConnections()) {
            if (nodeId.equals(connection.getEditorSourceNodeId()) && isPassthroughOutputPin(connection.getEditorSourcePinId())
                && !inputNames.contains(passthroughInputPin(connection.getEditorSourcePinId()))) {
                connection.setEditorSourceNodeId(null);
                connection.setEditorSourcePinId(null);
            }
        }
        if (graph.getEditorPassthroughs() != null) {
            graph.getEditorPassthroughs().removeIf(passthrough ->
                nodeId.equals(passthrough.getNodeId()) && !inputNames.contains(passthrough.getInputPinId()));
        }
    }

    private Map<String, String> storedSignatureTypes(String direction) {
        if (node.getInputValues() == null || !(node.getInputValues().get(FUNCTION_SIGNATURE_KEY) instanceof Map<?, ?> snapshot)
            || !(snapshot.get(direction) instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, String> signature = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                signature.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return signature;
    }

    private boolean isFunctionCallNode() {
        return CALL_FUNCTION_ID.equals(node.getType()) || CALL_FUNCTION_CANONICAL_ID.equals(node.getType());
    }

    private boolean isFunctionSignatureNode() {
        return isFunctionCallNode();
    }

    private void loadCallParameters() {
        if (!isFunctionCallNode() || node.getInputValues() == null || !(node.getInputValues().get(CALL_PARAMETERS_KEY) instanceof Iterable<?> values)) {
            return;
        }
        int index = 0;
        for (Object value : values) {
            int parameterIndex = index++;
            if (!(value instanceof Map<?, ?> entry) || entry.get("name") == null || entry.get("type") == null) {
                continue;
            }
            String name = entry.get("name").toString().trim();
            if (name.isBlank()) {
                continue;
            }
            try {
                FlowTypeRef typeRef = FlowTypeRef.parse(entry.get("type").toString());
                String parameterId = normalizedCallParameterId(entry.get("parameterId"), parameterIndex);
                FlowGraph.FunctionParameter parameter = new FlowGraph.FunctionParameter(parameterId, name,
                    FlowDataType.fromString(typeRef.getTypeId()));
                parameter.setTypeRef(typeRef);
                callParameters.add(parameter);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveCallParameters() {
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        if (callParameters.isEmpty()) {
            node.getInputValues().remove(CALL_PARAMETERS_KEY);
            return;
        }
        List<Map<String, String>> values = new ArrayList<>();
        int index = 0;
        for (FlowGraph.FunctionParameter parameter : callParameters) {
            if (isValidFunctionParameter(parameter)) {
                String parameterId = normalizedCallParameterId(parameter.getParameterId(), index++);
                if (!parameterId.equals(parameter.getParameterId())) {
                    parameter.setParameterId(parameterId);
                }
                Map<String, String> value = new LinkedHashMap<>();
                value.put("parameterId", parameterId);
                value.put("name", parameter.getName());
                value.put("type", parameter.getTypeRef().toString());
                values.add(value);
            }
        }
        node.getInputValues().put(CALL_PARAMETERS_KEY, values);
    }

    private List<NodeDefinition.PinDefinition> callParameterPins() {
        if (callParameters.isEmpty()) {
            return List.of();
        }
        List<NodeDefinition.PinDefinition> pins = new ArrayList<>();
        boolean legacyIdentity = legacyFunctionIdentityMode();
        for (FlowGraph.FunctionParameter parameter : callParameters) {
            if (!isValidFunctionParameter(parameter)) {
                continue;
            }
            String parameterPinId = functionParameterPinId(parameter, NodeDefinition.PinDirection.INPUT, legacyIdentity);
            if (parameterPinId.isBlank()) {
                continue;
            }
            pins.add(new NodeDefinition.PinBuilder(
                PinId.of(parameterPinId),
                parameter.getDisplayName(),
                NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT,
                parameter.getType() != null ? parameter.getType() : FlowDataType.ANY
            ).typeRef(parameter.getTypeRef()).build());
        }
        return pins;
    }

    private String nodeTitle() {
        return definition != null ? definition.getDisplayName() : "Loading";
    }

    public void refreshOptionCatalog(String sourceId) {
        boolean refreshAll = sourceId == null || sourceId.isBlank();
        for (NodeDefinition.PinDefinition input : inputs) {
            if (input.getOptionsSource() == null || input.getOptionsSource().isBlank()
                || !refreshAll && !sourceId.equals(input.getOptionsSource())) {
                continue;
            }
            Widget widget = inputWidgets.get(pinId(input));
            if (widget instanceof DropDownWidget<?> rawDropdown) {
                @SuppressWarnings("unchecked")
                DropDownWidget<String> dropdown = (DropDownWidget<String>) rawDropdown;
                List<String> options = new ArrayList<>(resolveOptions(input));
                String selected = resourceId(presentationInputValue(pinId(input)));
                if (selected.isBlank()) {
                    selected = input.getDefaultValue();
                }
                if (selected != null && !selected.isBlank() && !options.contains(selected)) {
                    options.addFirst(selected);
                }
                dropdown.setItems(options, selected);
            } else if (widget instanceof AnimatedButton button && resolveWidgetType(input) == NodeDefinition.WidgetType.SEARCHABLE_LIST) {
                String selected = searchableSelectorValues.getOrDefault(pinId(input), resourceId(presentationInputValue(pinId(input))));
                List<String> options = resolveOptions(input);
                button.setMessage(selectorButtonLabel(resolveCatalogItems(input, options), selected));
            } else if (widget instanceof TextInputWidget && resolveWidgetType(input) != NodeDefinition.WidgetType.TEXT && !resolveOptions(input).isEmpty()) {
                inputWidgets.remove(pinId(input));
                Widget replacement = buildWidgetForPin(input);
                if (replacement != null) {
                    inputWidgets.put(pinId(input), replacement);
                }
                invalidatePinLayout();
            }
        }
        updateSize();
        updateInputWidgetPositions();
    }

    private void applyAdvancedInputPins() {
        if (isCoreWidget() && coreRepeatablesConfigured) {
            applyCoreRepeatablePins(true);
            return;
        }
        for (NodeDefinition.PinDefinition base : repeatableInputDefinitions()) {
            inputs.removeIf(input -> repeatableInputIndex(base, pinId(input)) > 0);
            int insertionIndex = repeatableInsertionIndex(base);
            int count = repeatableInputCount(base);
            for (int index = 1; index <= count; index++) {
                inputs.add(insertionIndex++, repeatableInput(base, index));
            }
        }
    }

    private void applyAdvancedOutputPins() {
        if (isCoreWidget() && coreRepeatablesConfigured) {
            applyCoreRepeatablePins(false);
            return;
        }
        for (NodeDefinition.PinDefinition base : repeatableOutputDefinitions()) {
            outputs.removeIf(output -> repeatableInputIndex(base, pinId(output)) > 0);
            int insertionIndex = repeatableOutputInsertionIndex(base);
            int count = repeatableInputCount(base);
            Set<String> removed = removedOptionalInputNames();
            for (int index = 1; index <= count; index++) {
                String outputName = index == 1 ? pinId(base) : pinId(base) + "_" + index;
                if (!removed.contains(outputName)) {
                    outputs.add(insertionIndex++, repeatableOutput(base, index));
                }
            }
        }
    }

    private NodeDefinition.PinDefinition repeatableOutput(NodeDefinition.PinDefinition base, int index) {
        String id = index == 1 ? pinId(base) : pinId(base) + "_" + index;
        return new NodeDefinition.PinDefinition(
            PinId.of(id),
            index == 1 ? pinDisplayName(base) : pinDisplayName(base) + " " + index,
            base.getType(),
            NodeDefinition.PinDirection.OUTPUT,
            base.getDataType(),
            base.getWidgetType(),
            base.getOptions(),
            base.getOptionsSource(),
            base.getDefaultValue(),
            base.getConstraints(),
            base.getVisibleWhen(),
            base.getDescription(),
            index > base.getRepeatable().getMinItems(),
            base.getTypeRef(),
            base.getRepeatable()
        );
    }

    private int repeatableOutputInsertionIndex(NodeDefinition.PinDefinition base) {
        List<NodeDefinition.PinDefinition> definitions = definition != null ? definition.getOutputs() : List.of();
        int definitionIndex = definitions.indexOf(base);
        for (int index = definitionIndex + 1; index < definitions.size(); index++) {
            String nextName = pinId(definitions.get(index));
            for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                if (nextName.equals(pinId(outputs.get(outputIndex)))) {
                    return outputIndex;
                }
            }
        }
        return outputs.size();
    }

    private NodeDefinition.PinDefinition repeatableInput(NodeDefinition.PinDefinition base, int index) {
        String id = index == 1 ? pinId(base) : pinId(base) + "_" + index;
        return new NodeDefinition.PinDefinition(
            PinId.of(id),
            index == 1 ? pinDisplayName(base) : pinDisplayName(base) + " " + index,
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            base.getDataType(),
            base.getWidgetType(),
            base.getOptions(),
            base.getOptionsSource(),
            base.getDefaultValue(),
            base.getConstraints(),
            base.getVisibleWhen(),
            base.getDescription(),
            index > base.getRepeatable().getMinItems(),
            base.getTypeRef(),
            base.getRepeatable()
        );
    }

    private int repeatableInsertionIndex(NodeDefinition.PinDefinition base) {
        List<NodeDefinition.PinDefinition> definitions = definition != null ? definition.getInputs() : List.of();
        int definitionIndex = definitions.indexOf(base);
        for (int index = definitionIndex + 1; index < definitions.size(); index++) {
            String nextName = pinId(definitions.get(index));
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                if (nextName.equals(pinId(inputs.get(inputIndex)))) {
                    return inputIndex;
                }
            }
        }
        return inputs.size();
    }

    private void applyRemovedOptionalInputs() {
        Set<String> removed = removedOptionalInputNames();
        inputs.removeIf(input -> input.isOptional() && removed.contains(pinId(input)));
    }

    private List<NodeDefinition.PinDefinition> repeatableInputDefinitions() {
        if (definition == null || definition.getInputs() == null) {
            return List.of();
        }
        return definition.getInputs().stream().filter(input -> input.getRepeatable() != null).toList();
    }

    private List<NodeDefinition.PinDefinition> repeatableOutputDefinitions() {
        if (definition == null || definition.getOutputs() == null) {
            return List.of();
        }
        return definition.getOutputs().stream().filter(output -> output.getRepeatable() != null).toList();
    }

    private NodeDefinition.PinDefinition repeatableInputDefinition(String pinName) {
        return repeatableInputDefinitions().stream().filter(base -> repeatableInputIndex(base, pinName) > 0).findFirst().orElse(null);
    }

    private int repeatableInputCount(NodeDefinition.PinDefinition base) {
        NodeDefinition.RepeatablePin repeatable = base.getRepeatable();
        int minimum = repeatable != null ? repeatable.getMinItems() : 1;
        int maximum = repeatable != null ? repeatable.getMaxItems() : minimum;
        if (node.getInputValues() == null) {
            return minimum;
        }
        Object stored = node.getInputValues().get(repeatableCountKey(base));
        if (stored == null && "permissions".equals(repeatable.getGroupId())) {
            stored = node.getInputValues().get(LEGACY_PERMISSION_COUNT_KEY);
        }
        if (stored instanceof Number number) {
            return Math.clamp(number.intValue(), minimum, maximum);
        }
        if (stored != null) {
            try {
                return Math.clamp(Integer.parseInt(stored.toString()), minimum, maximum);
            } catch (NumberFormatException ignored) {
            }
        }
        return minimum;
    }

    private int repeatableInputIndex(NodeDefinition.PinDefinition base, String pinName) {
        if (pinId(base).equals(pinName)) {
            return 1;
        }
        String prefix = pinId(base) + "_";
        if (pinName == null || !pinName.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(pinName.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void updateAddInputButtons() {
        invalidateChildLayout();
        if (isCoreWidget() && coreRepeatablesConfigured) {
            updateCoreRepeatableButtons();
            return;
        }
        Set<String> activeGroups = new LinkedHashSet<>();
        for (NodeDefinition.PinDefinition base : repeatableInputDefinitions()) {
            String groupId = base.getRepeatable().getGroupId();
            if (!evaluateVisibleWhen(base.getVisibleWhen())) {
                continue;
            }
            if (activeRepeatableInputCount(base) >= base.getRepeatable().getMaxItems()) {
                continue;
            }
            activeGroups.add(groupId);
            String itemLabel = base.getRepeatable().getItemLabel();
            addInputButtons.computeIfAbsent(groupId, ignored -> new AnimatedButton.Builder()
                .label("Add " + (itemLabel != null && !itemLabel.isBlank() ? itemLabel : "Value"))
                .onClick(() -> addRepeatableInput(base))
                .animateElevation(false)
                .entranceAnimation(false)
                .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
                .build()).visible = true;
        }
        addInputButtons.entrySet().removeIf(entry -> !activeGroups.contains(entry.getKey()));
    }

    private void updateCoreRepeatableButtons() {
        Set<String> activeGroups = new LinkedHashSet<>();
        if (repeatableMutationHandler == null || !coreRepeatables.available()) {
            addInputButtons.clear();
            return;
        }
        for (CoreRepeatableUiProjection.Group group : coreRepeatables.groups().values()) {
            if (group.elements().size() >= group.maximum()) {
                continue;
            }
            String groupId = group.groupId().canonicalText();
            activeGroups.add(groupId);
            String itemLabel = group.itemLabel().isBlank() ? "Value" : group.itemLabel();
            AnimatedButton existing = addInputButtons.get(groupId);
            if (existing == null) {
                existing = new AnimatedButton.Builder()
                    .label("Add " + itemLabel)
                    .onClick(() -> addCoreRepeatable(group.groupId()))
                    .animateElevation(false)
                    .entranceAnimation(false)
                    .size(INPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
                    .build();
                addInputButtons.put(groupId, existing);
            }
            existing.visible = true;
        }
        addInputButtons.entrySet().removeIf(entry -> !activeGroups.contains(entry.getKey()));
    }

    private boolean addCoreRepeatable(RepeatableGroupId groupId) {
        NodeInstanceId identity = nodeIdentity();
        CoreRepeatableUiProjection.Group group = coreRepeatables.group(groupId).orElse(null);
        if (identity == null || group == null || repeatableMutationHandler == null
            || group.elements().size() >= group.maximum()) {
            return false;
        }
        return repeatableMutationHandler.apply(new RepeatableMutation(identity, groupId,
            RepeatableElementId.interactive(), RepeatableMutationKind.ADD));
    }

    private void addRepeatableInput(NodeDefinition.PinDefinition base) {
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        Set<String> removed = removedOptionalInputNames();
        int count = repeatableInputCount(base);
        for (int index = 1; index <= count; index++) {
            String pinName = index == 1 ? pinId(base) : pinId(base) + "_" + index;
            if (removed.remove(pinName)) {
                saveRemovedOptionalInputNames(removed);
                refreshInputWidgets();
                return;
            }
        }
        int nextIndex = Math.min(base.getRepeatable().getMaxItems(), repeatableInputCount(base) + 1);
        node.getInputValues().put(repeatableCountKey(base), nextIndex);
        node.getInputValues().remove(LEGACY_PERMISSION_COUNT_KEY);
        activateRepeatableOutput(base.getRepeatable().getGroupId(), nextIndex);
        refreshInputWidgets();
    }

    private void activateRepeatableOutput(String groupId, int index) {
        NodeDefinition.PinDefinition output = repeatableOutputDefinitions().stream()
            .filter(candidate -> groupId.equals(candidate.getRepeatable().getGroupId()))
            .findFirst()
            .orElse(null);
        if (output == null) {
            return;
        }
        String outputName = index == 1 ? pinId(output) : pinId(output) + "_" + index;
        Object stored = node.getInputValues().get(FLOW_BRANCHES_KEY);
        List<String> branches = new ArrayList<>();
        if (stored instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    branches.add(value.toString());
                }
            }
        }
        if (!branches.contains(outputName)) {
            branches.add(outputName);
            node.getInputValues().put(FLOW_BRANCHES_KEY, branches);
        }
    }

    private int activeRepeatableInputCount(NodeDefinition.PinDefinition base) {
        Set<String> removed = removedOptionalInputNames();
        int active = 0;
        for (int index = 1; index <= repeatableInputCount(base); index++) {
            String pinName = index == 1 ? pinId(base) : pinId(base) + "_" + index;
            if (!removed.contains(pinName)) {
                active++;
            }
        }
        return active;
    }

    public boolean isRepeatableInputPin(String pinName) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        return endpoint != null && endpoint.elementId() != null || repeatableInputDefinition(pinName) != null;
    }

    private String repeatableCountKey(NodeDefinition.PinDefinition base) {
        return REPEATABLE_COUNT_PREFIX + base.getRepeatable().getGroupId();
    }

    private Set<String> removedOptionalInputNames() {
        Set<String> removed = new LinkedHashSet<>();
        if (node.getInputValues() == null) {
            return removed;
        }
        Object stored = node.getInputValues().get(REMOVED_OPTIONAL_INPUTS_KEY);
        if (stored instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    removed.add(value.toString());
                }
            }
        }
        return removed;
    }

    private void saveRemovedOptionalInputNames(Set<String> removed) {
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        if (removed.isEmpty()) {
            node.getInputValues().remove(REMOVED_OPTIONAL_INPUTS_KEY);
        } else {
            node.getInputValues().put(REMOVED_OPTIONAL_INPUTS_KEY, new ArrayList<>(removed));
        }
    }

    public boolean isOptionalInputPin(String pinName) {
        NodeDefinition.PinDefinition input = findInputDefinition(pinName);
        return input != null && input.getDirection() == NodeDefinition.PinDirection.INPUT && input.isOptional();
    }

    public boolean isCoreRepeatablePin(String pinName) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        return endpoint != null && endpoint.elementId() != null;
    }

    public boolean removeCoreRepeatablePin(String pinName) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        if (endpoint == null) {
            return false;
        }
        CoreRepeatableUiProjection.Group group = coreRepeatableGroup(endpoint).orElse(null);
        NodeInstanceId identity = nodeIdentity();
        if (endpoint.elementId() == null || group == null || identity == null || repeatableMutationHandler == null
            || group.elements().size() <= group.minimum()) {
            return false;
        }
        return repeatableMutationHandler.apply(new RepeatableMutation(identity, group.groupId(),
            endpoint.elementId(), RepeatableMutationKind.REMOVE));
    }

    public boolean moveCoreRepeatablePin(String pinName, boolean earlier) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        CoreRepeatableUiProjection.Group group = coreRepeatableGroup(endpoint).orElse(null);
        NodeInstanceId identity = nodeIdentity();
        if (endpoint == null || endpoint.elementId() == null || group == null || !group.ordered()
            || identity == null || repeatableMutationHandler == null) {
            return false;
        }
        int index = -1;
        for (int position = 0; position < group.elements().size(); position++) {
            if (group.elements().get(position).elementId().equals(endpoint.elementId())) {
                index = position;
                break;
            }
        }
        if (index < 0 || earlier && index == 0 || !earlier && index == group.elements().size() - 1) {
            return false;
        }
        return repeatableMutationHandler.apply(new RepeatableMutation(identity, group.groupId(),
            endpoint.elementId(), earlier ? RepeatableMutationKind.MOVE_EARLIER : RepeatableMutationKind.MOVE_LATER));
    }

    public boolean canRemoveCoreRepeatablePin(String pinName) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        CoreRepeatableUiProjection.Group group = coreRepeatableGroup(endpoint).orElse(null);
        return endpoint != null && endpoint.elementId() != null && group != null
            && group.elements().size() > group.minimum();
    }

    public boolean canMoveCoreRepeatablePin(String pinName, boolean earlier) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        CoreRepeatableUiProjection.Group group = coreRepeatableGroup(endpoint).orElse(null);
        if (endpoint == null || endpoint.elementId() == null || group == null || !group.ordered()) {
            return false;
        }
        int index = -1;
        for (int position = 0; position < group.elements().size(); position++) {
            if (group.elements().get(position).elementId().equals(endpoint.elementId())) {
                index = position;
                break;
            }
        }
        return index >= 0 && (earlier ? index > 0 : index < group.elements().size() - 1);
    }

    public boolean removeOptionalInputPin(String pinName) {
        NodeDefinition.PinDefinition input = findInputDefinition(pinName);
        if (input == null || input.getDirection() != NodeDefinition.PinDirection.INPUT || !input.isOptional()) {
            return false;
        }
        if (nodeValueMutationHandler != null) {
            if (isCoreRepeatablePin(pinName)) {
                return removeCoreRepeatablePin(pinName);
            }
            return applyCoreNodeValueProposal(input, InputValueProposal.removal());
        }
        Set<String> removed = removedOptionalInputNames();
        removed.add(pinName);
        saveRemovedOptionalInputNames(removed);
        if (node.getInputValues() != null) {
            node.getInputValues().remove(pinName);
        }
        refreshInputWidgets();
        return true;
    }

    private void applyFunctionParameterPins() {
        boolean functionStart = isFunctionStartNode();
        boolean functionEnd = isFunctionEndNode();
        if (!functionStart && !functionEnd) {
            return;
        }
        inputs.clear();
        outputs.clear();

        boolean legacyIdentity = legacyFunctionIdentityMode();
        if (legacyIdentity) {
            inputs.add(new NodeDefinition.PinDefinition("flow", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION));
            outputs.add(new NodeDefinition.PinDefinition("flow", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION));
        } else {
            NodeDefinition.PinDirection flowDirection = functionStart
                ? NodeDefinition.PinDirection.OUTPUT : NodeDefinition.PinDirection.INPUT;
            NodeDefinition.PinDefinition flowPin = declaredFunctionFlowPin(flowDirection);
            if (flowPin != null) {
                if (flowDirection == NodeDefinition.PinDirection.INPUT) {
                    inputs.add(flowPin);
                } else {
                    outputs.add(flowPin);
                }
            }
        }

        if (graph == null) {
            return;
        }

        if (functionStart && graph.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionInputs()) {
                if (!isValidFunctionParameter(parameter)) {
                    continue;
                }
                String parameterPinId = functionParameterPinId(parameter, NodeDefinition.PinDirection.OUTPUT, legacyIdentity);
                if (parameterPinId.isBlank()) {
                    continue;
                }
                outputs.add(new NodeDefinition.PinDefinition(PinId.of(parameterPinId), parameter.getDisplayName(),
                    NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, parameter.getType(), parameter.getTypeRef()));
            }
        }

        if (functionEnd && graph.getFunctionOutputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionOutputs()) {
                if (!isValidFunctionParameter(parameter)) {
                    continue;
                }
                String parameterPinId = functionParameterPinId(parameter, NodeDefinition.PinDirection.INPUT, legacyIdentity);
                if (parameterPinId.isBlank()) {
                    continue;
                }
                inputs.add(new NodeDefinition.PinDefinition(PinId.of(parameterPinId), parameter.getDisplayName(),
                    NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, parameter.getType(), parameter.getTypeRef()));
            }
        }
    }

    private void applyCoreRepeatablePins(boolean inputDirection) {
        List<NodeDefinition.PinDefinition> pins = inputDirection ? inputs : outputs;
        List<NodeDefinition.PinDefinition> definitions = definition != null
            ? inputDirection ? definition.getInputs() : definition.getOutputs() : List.of();
        pins.removeIf(pin -> pin.getRepeatable() != null);
        corePinEndpoints.entrySet().removeIf(entry -> inputDirection
            == (findDefinitionPin(entry.getValue().pinId(), true) != null));
        coreViewPins.entrySet().removeIf(entry -> !corePinEndpoints.containsKey(entry.getValue()));
        int insertion = 0;
        for (NodeDefinition.PinDefinition definitionPin : definitions) {
            if (definitionPin.getRepeatable() == null) {
                insertion++;
                continue;
            }
            CoreRepeatableUiProjection.Group group = coreRepeatables.group(definitionPin.getRepeatable().getGroupId())
                .orElse(null);
            if (group == null) {
                continue;
            }
            int ordinal = 0;
            for (CoreRepeatableUiProjection.Element element : group.elements()) {
                ordinal++;
                String viewId = coreRepeatableViewId(definitionPin, element.elementId(), inputDirection);
                NodeDefinition.PinDefinition viewPin = coreRepeatablePin(definitionPin, viewId,
                    repeatableLabel(definitionPin, group, ordinal), group.elements().size() > group.minimum());
                pins.add(Math.min(insertion++, pins.size()), viewPin);
                CoreRepeatableUiProjection.PinEndpoint endpoint = new CoreRepeatableUiProjection.PinEndpoint(
                    definitionPin.getId(), element.elementId());
                corePinEndpoints.put(viewId, endpoint);
                coreViewPins.put(coreEndpointKey(endpoint), viewId);
            }
        }
    }

    private NodeDefinition.PinDefinition findDefinitionPin(PinId id, boolean inputDirection) {
        if (id == null || definition == null) {
            return null;
        }
        List<NodeDefinition.PinDefinition> pins = inputDirection ? definition.getInputs() : definition.getOutputs();
        return pins.stream().filter(pin -> id.equals(pin.getId())).findFirst().orElse(null);
    }

    private String coreRepeatableViewId(NodeDefinition.PinDefinition pin, RepeatableElementId elementId,
                                        boolean inputDirection) {
        RepeatableElementId viewIdentity = RepeatableElementId.deterministic("pin-view:"
            + pin.getId().canonicalText() + ":" + elementId.canonicalText() + ":" + inputDirection);
        return "core_repeatable_" + viewIdentity.canonicalText().replace("-", "");
    }

    private NodeDefinition.PinDefinition coreRepeatablePin(NodeDefinition.PinDefinition base, String id,
                                                            String displayName, boolean removable) {
        return new NodeDefinition.PinDefinition(PinId.of(id), displayName, base.getType(), base.getDirection(),
            base.getDataType(), base.getWidgetType(), base.getOptions(), base.getOptionsSource(),
            base.getOptionSourceRef(), base.getDefaultValue(), base.getConstraints(), base.getVisibleWhen(),
            base.getDescription(), removable, base.getTypeRef(), base.getRepeatable(), base.getTypedDefault(),
            base.getTypedType());
    }

    private String repeatableLabel(NodeDefinition.PinDefinition pin, CoreRepeatableUiProjection.Group group,
                                   int ordinal) {
        String item = group.itemLabel().isBlank() ? pinDisplayName(pin) : group.itemLabel();
        return group.members().size() == 1 ? item + " " + ordinal
            : item + " " + ordinal + " " + pinDisplayName(pin);
    }

    private static String coreEndpointKey(CoreRepeatableUiProjection.PinEndpoint endpoint) {
        return endpoint.pinId().canonicalText() + '\u0000'
            + (endpoint.elementId() != null ? endpoint.elementId().canonicalText() : "");
    }

    protected FlowNodeWidget.FunctionBoundaryCatalog functionBoundaryCatalog() {
        return FlowNodeWidget.boundaryCatalogForServer(catalogServerId());
    }

    private NodeDefinition.PinDefinition declaredFunctionFlowPin(NodeDefinition.PinDirection direction) {
        String flowPinId = functionBoundaryCatalog().flowPin(node,
            direction == NodeDefinition.PinDirection.INPUT);
        if (flowPinId == null || flowPinId.isBlank()) {
            flowPinId = definitionFunctionFlowPinId(direction);
        }
        if (flowPinId == null || flowPinId.isBlank()) {
            return null;
        }
        NodeDefinition.PinDefinition declared = findDefinitionFlowPin(direction, flowPinId);
        return declared != null ? declared : new NodeDefinition.PinDefinition(PinId.of(flowPinId),
            flowPinId, NodeDefinition.PinType.FLOW, direction, FlowDataType.EXECUTION);
    }

    private String definitionFunctionFlowPinId(NodeDefinition.PinDirection direction) {
        if (definition == null) {
            return "";
        }
        List<NodeDefinition.PinDefinition> declared = direction == NodeDefinition.PinDirection.INPUT
            ? definition.getInputs() : definition.getOutputs();
        List<String> flowPins = declared.stream()
            .filter(pin -> pin != null && pin.getType() == NodeDefinition.PinType.FLOW
                && pin.getDirection() == direction && pin.getId() != null
                && pin.getDataType() == FlowDataType.EXECUTION)
            .map(NodeWidget::pinId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        return flowPins.size() == 1 ? flowPins.getFirst() : "";
    }

    private NodeDefinition.PinDefinition findDefinitionFlowPin(NodeDefinition.PinDirection direction, String pinId) {
        if (definition == null || pinId == null || pinId.isBlank()) {
            return null;
        }
        List<NodeDefinition.PinDefinition> declared = direction == NodeDefinition.PinDirection.INPUT
            ? definition.getInputs() : definition.getOutputs();
        return declared.stream()
            .filter(pin -> pin != null && pin.getType() == NodeDefinition.PinType.FLOW
                && pin.getDirection() == direction && pinId.equals(pinId(pin)))
            .findFirst()
            .orElse(null);
    }

    private boolean isFunctionStartNode() {
        return functionBoundaryRole() == FlowNodeWidget.FunctionBoundaryRole.INPUTS;
    }

    private boolean isFunctionEndNode() {
        return functionBoundaryRole() == FlowNodeWidget.FunctionBoundaryRole.OUTPUTS;
    }

    private FlowNodeWidget.FunctionBoundaryRole functionBoundaryRole() {
        FlowNodeWidget.FunctionBoundaryIntent intent = functionBoundaryCatalog().intent(node);
        if (intent != null) {
            return intent.role();
        }
        if (FlowNodeWidget.isBuiltinFunctionStartType(node.getType())) {
            return FlowNodeWidget.FunctionBoundaryRole.INPUTS;
        }
        if (FlowNodeWidget.isBuiltinFunctionEndType(node.getType())) {
            return FlowNodeWidget.FunctionBoundaryRole.OUTPUTS;
        }
        if (legacyFunctionIdentityMode()) {
            return switch (node.getType()) {
                case "function_start", "function.start", "function.function_start" ->
                    FlowNodeWidget.FunctionBoundaryRole.INPUTS;
                case "function_end", "function.end", "function.function_end" ->
                    FlowNodeWidget.FunctionBoundaryRole.OUTPUTS;
                default -> null;
            };
        }
        return null;
    }

    private boolean isValidFunctionParameter(FlowGraph.FunctionParameter parameter) {
        return parameter != null && parameter.getName() != null && !parameter.getName().isBlank();
    }

    private List<FlowDataType> getSupportedFunctionTypes() {
        if (typedCatalogAuthorityActive()) {
            return FlowDataType.values().stream().filter(this::isUserFacingFunctionType).toList();
        }
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry != null) {
            List<FlowDataType> serverTypes = registry.getServerDataTypes(serverId);
            if (!serverTypes.isEmpty()) {
                return serverTypes.stream().filter(this::isUserFacingFunctionType).toList();
            }
        }
        return FlowDataType.values().stream().filter(this::isUserFacingFunctionType).toList();
    }

    private boolean isUserFacingFunctionType(FlowDataType type) {
        return type != null && !"resource_reference".equals(type.getId());
    }

    public List<FlowGraph.FunctionParameter> getFunctionParameterList() {
        if (isFunctionCallNode()) {
            return callParameters;
        }
        if (graph == null) {
            return null;
        }
        if (isFunctionStartNode()) {
            return graph.getFunctionInputs();
        }
        if (isFunctionEndNode()) {
            return graph.getFunctionOutputs();
        }
        return null;
    }

    public void setLastScreenMouse(int x, int y) {
        this.lastScreenX = x;
        this.lastScreenY = y;
        this.hasLastScreenMouse = true;
    }

    public void showParamContextMenu() {
        var currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null) return;

        List<FlowGraph.FunctionParameter> params = getFunctionParameterList();
        if (params == null) return;

        String parameterName = isFunctionCallNode() ? "Argument" : "Parameter";
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(currentScreen);
        builder.addHeaderButton("add.png", this::showAddFunctionParameterPopup, "Add " + parameterName, ThemeManager.getAccent("nice"));
        for (FlowGraph.FunctionParameter p : params) {
            if (p != null && p.getName() != null) {
                String name = p.getName();
                String parameterId = p.getParameterId();
                builder.addItem("Remove: " + name, () -> removeFunctionParameter(parameterId), name, ThemeManager.getAccent("danger"));
            }
        }
        ContextMenuWidget menu = builder.build();
        currentScreen.addDrawableChild(menu);
        menu.show(lastScreenX, lastScreenY);
    }

    public void showAddFunctionParameterPopup() {
        boolean callArgument = isFunctionCallNode();
        PopupWidget.Builder builder = new PopupWidget.Builder(callArgument ? "Add Argument" : "Add Parameter").setResizable(false);
        TextInputWidget nameInput = new TextInputWidget.Builder()
            .placeholder(callArgument ? "argument_name" : "parameter_name")
            .size(200, 20)
            .build();
        List<FlowDataType> types = getSupportedFunctionTypes().stream()
            .filter(type -> !callArgument || type != FlowDataType.ANY && type != FlowDataType.EXECUTION)
            .toList();
        FlowDataType defaultType = callArgument ? FlowDataType.STRING : FlowDataType.ANY;
        FlowDataType[] selectedType = new FlowDataType[]{defaultType};
        FlowTypeRef[] selectedTypeRef = new FlowTypeRef[]{FlowTypeRef.simple(defaultType.getId())};
        Stream<String> genericTypes = callArgument
            ? Stream.of("list<string>", "set<string>", "map<string,string>", "optional<string>", "result<string>")
            : Stream.of("list<any>", "list<string>", "set<any>", "set<string>", "map<string,any>", "optional<any>", "result<any>");
        List<String> typeOptions = Stream.concat(types.stream().map(FlowDataType::getId), genericTypes).distinct().toList();
        AnimatedButton typeButton = buildScreenSelectorButton(typeOptions, selectedType[0].getId(), 200, value -> {
            selectedTypeRef[0] = FlowTypeRef.parse(value);
            selectedType[0] = FlowDataType.fromString(selectedTypeRef[0].getTypeId());
        });
        String[] selectedCatalog = new String[]{""};
        String[] selectedWidget = new String[]{""};
        Map<String, FlowOptionSourceMetadata> functionCatalogs = functionInputCatalogs();
        AnimatedButton catalogButton = buildScreenSelectorButton(List.copyOf(functionCatalogs.keySet()), "None", 200, value -> {
            FlowOptionSourceMetadata metadata = functionCatalogs.get(value);
            selectedCatalog[0] = metadata != null ? metadata.getId() : "";
            selectedWidget[0] = metadata == null ? "" : metadata.getWidgetType() != null && !metadata.getWidgetType().isBlank()
                ? metadata.getWidgetType()
                : metadata.isSearchable() ? "SEARCHABLE_LIST" : "DROPDOWN";
            if (metadata != null) {
                selectedTypeRef[0] = FlowTypeRef.parse(metadata.getValueType());
                selectedType[0] = FlowDataType.fromString(selectedTypeRef[0].getTypeId());
                typeButton.setMessage(selectedTypeRef[0].toString());
            }
        });
        TextInputWidget defaultInput = new TextInputWidget.Builder()
            .placeholder("default")
            .size(200, 20)
            .build();

        builder.addRow("Name", nameInput);
        builder.addRow("Type", typeButton);
        if (isFunctionStartNode()) {
            builder.addRow("Catalog", catalogButton);
            builder.addRow("Default", defaultInput);
        }

        PopupWidget[] popupRef = new PopupWidget[1];
        AnimatedButton addButton = new AnimatedButton.Builder()
            .label("Add")
            .onClick(() -> {
                String rawName = nameInput.getText() != null ? nameInput.getText().trim() : "";
                if (rawName.isBlank() || !rawName.matches("^[a-zA-Z0-9_]+$")) {
                    return;
                }
                FlowDataType type = selectedType[0];
                if (type == null) {
                    type = callArgument ? FlowDataType.STRING : FlowDataType.ANY;
                }

                List<FlowGraph.FunctionParameter> targetList;
                targetList = getFunctionParameterList();

                if (targetList == null) {
                    return;
                }
                for (FlowGraph.FunctionParameter existing : targetList) {
                    if (existing != null && rawName.equalsIgnoreCase(existing.getName())) {
                        return;
                    }
                }
                String optionsSource = isFunctionStartNode() ? selectedCatalog[0] : "";
                String widget = optionsSource.isBlank() ? "" : selectedWidget[0];
                String defaultValue = isFunctionStartNode() && defaultInput.getText() != null ? defaultInput.getText().trim() : "";
                FlowGraph.FunctionParameter parameter = new FlowGraph.FunctionParameter(newFunctionParameterId(), rawName,
                    type, widget, optionsSource, defaultValue);
                parameter.setTypeRef(selectedTypeRef[0]);
                targetList.add(parameter);
                if (callArgument) {
                    saveCallParameters();
                } else {
                    graph.setFunctionVersion(graph.getFunctionVersion() + 1);
                }
                refreshInputWidgets();
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .size(80, 18)
            .animateElevation(false)
            .entranceAnimation(false)
            .build();

        builder.addTitleAction("Add", () -> addButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        if (ScreenManager.getInstance().getCurrentScreen() != null) {
            ScreenManager.getInstance().getCurrentScreen().addDrawableChild(popupRef[0]);
            popupRef[0].show();
        }
    }

    private int findParameterIndex(List<FlowGraph.FunctionParameter> parameters, String name) {
        for (int i = 0; i < parameters.size(); i++) {
            FlowGraph.FunctionParameter parameter = parameters.get(i);
            if (parameter != null && parameter.getName() != null && parameter.getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private FlowGraph.FunctionParameter findParameterByName(List<FlowGraph.FunctionParameter> parameters, String name) {
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter != null && parameter.getName() != null && parameter.getName().equals(name)) {
                return parameter;
            }
        }
        return null;
    }

    private void renameParameterConnections(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName) || graph == null || graph.getConnections() == null) {
            return;
        }
        for (FlowConnection connection : graph.getConnections()) {
            if (isFunctionStartNode() && nodeId.equals(connection.getSourceNodeId()) && oldName.equals(connection.getSourcePinId())) {
                connection.setSourcePinId(newName);
            }
            if (isFunctionEndNode() && nodeId.equals(connection.getTargetNodeId()) && oldName.equals(connection.getTargetPinId())) {
                connection.setTargetPinId(newName);
            }
        }
    }

    private void removeParameterConnections(FlowGraph.FunctionParameter parameter) {
        if (parameter == null || graph == null || graph.getConnections() == null) {
            return;
        }
        NodeDefinition.PinDirection direction;
        boolean boundarySource;
        if (isFunctionStartNode()) {
            direction = NodeDefinition.PinDirection.OUTPUT;
            boundarySource = true;
        } else if (isFunctionEndNode()) {
            direction = NodeDefinition.PinDirection.INPUT;
            boundarySource = false;
        } else if (isFunctionCallNode()) {
            direction = NodeDefinition.PinDirection.INPUT;
            boundarySource = false;
        } else {
            return;
        }
        Set<String> parameterPins = new LinkedHashSet<>();
        boolean legacyIdentity = legacyFunctionIdentityMode();
        String directionPin = functionParameterPinId(parameter, direction, legacyIdentity);
        if (!directionPin.isBlank()) {
            parameterPins.add(directionPin);
        }
        String parameterId = parameter.getParameterId();
        if (parameterId != null && !parameterId.isBlank()) {
            parameterPins.add(parameterId);
        }
        if (legacyIdentity) {
            String parameterName = parameter.getName();
            if (parameterName != null && !parameterName.isBlank()) {
                parameterPins.add(parameterName);
            }
        }
        if (parameterPins.isEmpty()) {
            return;
        }
        if (boundarySource) {
            graph.getConnections().removeIf(connection -> nodeId.equals(connection.getSourceNodeId())
                && parameterPins.contains(connection.getSourcePinId()));
        } else {
            graph.getConnections().removeIf(connection -> nodeId.equals(connection.getTargetNodeId())
                && parameterPins.contains(connection.getTargetPinId()));
        }
    }

    public void removeFunctionParameter(String name) {
        List<FlowGraph.FunctionParameter> params = getFunctionParameterList();
        if (params == null || name == null) return;
        for (int i = 0; i < params.size(); i++) {
            FlowGraph.FunctionParameter parameter = params.get(i);
            if (parameter != null && (name.equals(parameter.getParameterId()) || name.equals(parameter.getName()))) {
                params.remove(i);
                removeParameterConnections(parameter);
                if (isFunctionCallNode()) {
                    saveCallParameters();
                } else {
                    graph.setFunctionVersion(graph.getFunctionVersion() + 1);
                }
                refreshInputWidgets();
                return;
            }
        }
    }

    private boolean isLiteralInput(NodeDefinition.PinDefinition input) {
        if (nodeValueMutationHandler != null && (input.getWidgetType() == null
            || input.getWidgetType() == NodeDefinition.WidgetType.AUTO)) {
            return false;
        }
        FlowDataType type = input.getDataType();
        if (type == null) {
            return false;
        }
        if (isObjectPin(type) && (input.getWidgetType() == null || input.getWidgetType() == NodeDefinition.WidgetType.AUTO)) {
            return false;
        }
        if (input.getWidgetType() != null && input.getWidgetType() != NodeDefinition.WidgetType.AUTO) {
            return true;
        }
        if (input.getOptions() != null && !input.getOptions().isEmpty()) {
            return true;
        }
        if (input.getOptionsSource() != null && !input.getOptionsSource().isBlank()) {
            return true;
        }
        if (typedCatalogAuthorityActive()) {
            return type == FlowDataType.STRING || type == FlowDataType.NUMBER || type == FlowDataType.BOOLEAN
                || type == FlowDataType.ANY;
        }
        NodeRegistry registry = NodeRegistry.getInstance();
        FlowTypeMetadata meta = registry != null ? registry.getTypeMetadata(serverId, type.getId()) : null;
        if (meta != null) {
            return meta.isLiteralInput();
        }
        return type == FlowDataType.STRING || type == FlowDataType.NUMBER || type == FlowDataType.BOOLEAN || type == FlowDataType.ANY;
    }

    private boolean isObjectPin(FlowDataType type) {
        if (type == null) {
            return false;
        }
        if (typedCatalogAuthorityActive()) {
            return type == FlowDataType.PLAYER
                || type == FlowDataType.ENTITY
                || type == FlowDataType.LIVING_ENTITY
                || type == FlowDataType.WORLD
                || type == FlowDataType.BLOCK
                || type == FlowDataType.LOCATION
                || type == FlowDataType.INVENTORY
                || type == FlowDataType.ITEM
                || type == FlowDataType.ITEMSTACK;
        }
        NodeRegistry registry = NodeRegistry.getInstance();
        FlowTypeMetadata meta = registry != null ? registry.getTypeMetadata(serverId, type.getId()) : null;
        if (meta != null) {
            return meta.isObjectPin();
        }
        return type == FlowDataType.PLAYER
            || type == FlowDataType.ENTITY
            || type == FlowDataType.LIVING_ENTITY
            || type == FlowDataType.WORLD
            || type == FlowDataType.BLOCK
            || type == FlowDataType.LOCATION
            || type == FlowDataType.INVENTORY
            || type == FlowDataType.ITEM
            || type == FlowDataType.ITEMSTACK;
    }

    private boolean isInputWired(String pinName) {
        if (graph == null || graph.getConnections() == null) {
            return false;
        }
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoint(pinName);
        for (FlowConnection conn : graph.getConnections()) {
            if (!conn.getTargetNodeId().equals(nodeId)) {
                continue;
            }
            if (endpoint != null && endpoint.elementId() != null
                && conn.getTargetPinId().equals(endpoint.pinId().canonicalText())
                && endpoint.elementId().equals(connectionElementId(conn, false))) {
                return true;
            }
            if (endpoint != null && endpoint.elementId() == null && conn.getTargetPinId().equals(pinName)
                && connectionElementId(conn, false) == null) {
                return true;
            }
        }
        return false;
    }

    private RepeatableElementId connectionElementId(FlowConnection connection, boolean source) {
        JsonElement value = connection != null ? connection.getOpaqueProperties()
            .get(source ? "sourceElementId" : "targetElementId") : null;
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return RepeatableElementId.parseCanonicalText(value.getAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void createLoadingState() {
        inputs.clear();
        outputs.clear();
        visibleInputs.clear();
        visibleOutputs.clear();
        visibleInputsById.clear();
        invalidatePinLayout();
        updateSize();
    }

    public void restoreInputWidgets() {
        rebuildInputWidgets();
    }

    private void rebuildInputWidgets() {
        inputWidgets.values().forEach(WidgetCleanup::cleanup);
        inputWidgets.clear();
        deferredInputValues.clear();
        searchableSelectorValues.clear();
        refreshInputWidgets();
    }

    @Override
    public List<Widget> getChildWidgets() {
        if (!collaborationChildrenDirty) {
            return collaborationChildren;
        }
        ensureChildLayout();
        List<Widget> children = new ArrayList<>();
        for (NodeDefinition.PinDefinition input : visibleInputs) {
            addCollaborationChild(children, inputWidgets.get(pinId(input)), "input:" + pinId(input));
        }
        for (Map.Entry<String, AnimatedButton> entry : addInputButtons.entrySet()) {
            addCollaborationChild(children, entry.getValue(), "add:" + entry.getKey());
        }
        for (FlowBranch branch : flowBranches) {
            addCollaborationChild(children, branch.widget, "branch:" + branch.outputName);
        }
        addCollaborationChild(children, addBranchButton, "add-branch");
        addCollaborationChild(children, paramButton, "parameters");
        addCollaborationChild(children, inspectorButton, "inspector");
        addCollaborationChild(children, openFunctionButton, "open");
        addCollaborationChild(children, closeButton, "delete");
        collaborationChildren = List.copyOf(children);
        collaborationChildrenDirty = false;
        return collaborationChildren;
    }

    private void addCollaborationChild(List<Widget> children, Widget child, String identity) {
        if (child != null && child.isVisible()) {
            child.setCollaborationKey("node:" + nodeId + "/" + identity);
            children.add(child);
        }
    }

    @Override
    public boolean exposesChildScreenOverlays() {
        return false;
    }

    @Override
    public Widget getFocusedDescendant() {
        for (Widget child : getChildWidgets()) {
            Widget focused = child.getFocusedDescendant();
            if (focused != null && focused.isFocused()) {
                return focused;
            }
        }
        return this;
    }

    public static int getPinColor(FlowDataType dataType) {
        if (dataType == null) {
            return 0xFFAAAAAA;
        }
        return dataType.getColor();
    }

    public static boolean isPassthroughOutputPin(String pinName) {
        return EditorPassthroughPins.isOutputPin(pinName);
    }

    public static String passthroughOutputPin(String inputPin) {
        return EditorPassthroughPins.outputPin(inputPin);
    }

    public static String passthroughInputPin(String outputPin) {
        return EditorPassthroughPins.inputPin(outputPin);
    }

    @Override
    public void setX(int x) {
        this.x = x;
        targetX = animatedX = x;
        recomputeRelativeScissor();
    }

    @Override
    public void setY(int y) {
        this.y = y;
        targetY = animatedY = y;
        recomputeRelativeScissor();
    }

    @Override
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        targetX = animatedX = x;
        targetY = animatedY = y;
        recomputeRelativeScissor();
    }

    public void morphFromBounds(int startX, int startY, int startWidth, int startHeight) {
        targetX = getX();
        targetY = getY();
        targetWidth = getWidth();
        targetHeight = getHeight();
        animatedX = startX;
        animatedY = startY;
        animatedWidth = Math.max(1, startWidth);
        animatedHeight = Math.max(1, startHeight);
        x = Math.round(animatedX);
        y = Math.round(animatedY);
        width = Math.round(animatedWidth);
        height = Math.round(animatedHeight);
        layoutInitialized = true;
        recomputeRelativeScissor();
    }

    @Override
    protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        hintOverlayCandidate = null;
        int headerBg = ThemeManager.getColor(ThemeColor.inClickableBackground);
        int headerText = ThemeManager.getColor(ThemeColor.text);
        int labelText = ThemeManager.getColor(ThemeColor.textDark);
        int borderColor = ThemeManager.getColor(ThemeColor.innerBorder);

        Render.drawLayeredInnerBorder(ctx, getX(), getY(), getWidth(), TITLE_HEIGHT, headerBg, borderColor);
        ctx.fill(getX(), getY() + TITLE_HEIGHT, getWidth() + getX(), getY() + TITLE_HEIGHT + 1, this.borderColor);
        ctx.drawText(nodeTitle(), getX() + 4, getY() + TITLE_STYLE.textYOffset(), headerText, shadow);

        ensureChildLayout();
        beginPinHintFrame();
        if (paramButton != null) {
            paramButton.render(ctx, mouseX, mouseY, 0);
            trackHintOverlayCandidate(paramButton);
        }
        if (openFunctionButton.visible) {
            openFunctionButton.render(ctx, mouseX, mouseY, 0);
            trackHintOverlayCandidate(openFunctionButton);
        }
        if (inspectorButton != null && inspectorButton.visible) {
            inspectorButton.render(ctx, mouseX, mouseY, 0);
            trackHintOverlayCandidate(inspectorButton);
        }
        if (closeButton.visible) {
            closeButton.render(ctx, mouseX, mouseY, 0);
            trackHintOverlayCandidate(closeButton);
        }

        ctx.pushScissorState();
        ctx.enableScissor(getX(), getY() + TITLE_HEIGHT + 1, getX() + getWidth(), getY() + getHeight());

        if (definition == null) {
            String text = node.getType() == null || node.getType().isBlank() ? "Loading Definition" : "Loading " + node.getType();
            int textX = getX() + PADDING;
            int textY = getY() + TITLE_HEIGHT + PADDING + 2;
            ctx.drawText(text, textX, textY, labelText, shadow);
            completePinHintFrame();
            ctx.disableScissor();
            ctx.popScissorState();
            return;
        }

        drawPassthroughGuides(ctx);

        for (InputPinLayout layout : inputPinLayouts) {
            drawPinButton(ctx, layout.pinX(), layout.pinY(), layout.color(), pinId(layout.pin()));
            boolean pinHovered = observePinHint(layout.pin(), layout.description(), layout.label(), layout.textX(), layout.textY(),
                layout.labelWidth(), mouseX, mouseY);
            ctx.drawText(layout.label(), layout.textX(), layout.textY(), pinLabelColor(pinHovered, labelText, headerText), shadow);
        }

        for (OutputPinLayout layout : outputPinLayouts) {
            if (layout.showLabel()) {
                boolean pinHovered = observePinHint(layout.pin(), layout.description(), layout.label(), layout.labelX(), layout.textY(),
                    layout.labelWidth(), mouseX, mouseY);
                ctx.drawText(layout.label(), layout.labelX(), layout.textY(), pinLabelColor(pinHovered, labelText, headerText), shadow);
            }
            drawPinButton(ctx, layout.pinX(), layout.pinY(), layout.color(), pinId(layout.pin()));
        }
        completePinHintFrame();

        for (int i = visibleInputs.size() - 1; i >= 0; i--) {
            NodeDefinition.PinDefinition input = visibleInputs.get(i);
            Widget inputWidget = inputWidgets.get(pinId(input));
            if (inputWidget != null) {
                inputWidget.render(ctx, mouseX, mouseY, 0);
                trackHintOverlayCandidate(inputWidget);
            }
        }

        for (int i = visibleOutputs.size() - 1; i >= 0; i--) {
            NodeDefinition.PinDefinition output = visibleOutputs.get(i);
            AnimatedButton branchWidget = getBranchWidget(pinId(output));
            if (branchWidget != null) {
                branchWidget.render(ctx, mouseX, mouseY, 0);
                trackHintOverlayCandidate(branchWidget);
            }
        }

        if (addBranchButton != null && addBranchButton.visible) {
            addBranchButton.render(ctx, mouseX, mouseY, 0);
            trackHintOverlayCandidate(addBranchButton);
        }
        for (AnimatedButton button : addInputButtons.values()) {
            if (button.visible) {
                button.render(ctx, mouseX, mouseY - Math.round(currentElevationOffset), 0);
                trackHintOverlayCandidate(button);
            }
        }

        ctx.disableScissor();
        ctx.popScissorState();
        renderExpandedDropdownOverlays(ctx, mouseX, mouseY);
    }

    private void renderExpandedDropdownOverlays(IDrawContext ctx, int mouseX, int mouseY) {
        for (Widget widget : inputWidgets.values()) {
            if (widget instanceof DropDownWidget<?> dropdown && dropdown.isDropdownVisible() && widget.isVisible()) {
                dropdown.renderScreenOverlay(ctx, mouseX, mouseY, 0);
            }
        }
    }

    @Override
    public void renderHintOverlay(IDrawContext context) {
        boolean childOwnsHint = hintOverlayCandidate != null
            && (hintOverlayCandidate.isHovered() || hintOverlayCandidate.isFocused())
            && hintOverlayCandidate.hint != null && !hintOverlayCandidate.hint.isBlank();
        if (!childOwnsHint && (isHovered() || isFocused())) {
            super.renderHintOverlay(context);
        }
        if (hintOverlayCandidate != null) {
            hintOverlayCandidate.renderHintOverlay(context);
        }
    }

    @Override
    protected float hintAnchorX() {
        return pinHint.owned ? pinHint.x : super.hintAnchorX();
    }

    @Override
    protected float hintAnchorY() {
        return pinHint.owned ? pinHint.y : super.hintAnchorY();
    }

    @Override
    protected float hintAnchorWidth() {
        return pinHint.owned ? pinHint.width : super.hintAnchorWidth();
    }

    @Override
    protected float hintAnchorHeight() {
        return pinHint.owned ? pinHint.height : super.hintAnchorHeight();
    }

    private void beginPinHintFrame() {
        pinHint.hovered = false;
    }

    private boolean observePinHint(NodeDefinition.PinDefinition pin, String description, String label, int x, int y,
                                   int width, int mouseX, int mouseY) {
        if (!isHovered() || !describedPinLabelAt(description, label, x, y, width, mouseX, mouseY)) {
            return false;
        }
        if (pinHint.pin != pin) {
            pinHint.pin = pin;
            pinHint.progress = 0f;
        }
        pinHint.hovered = true;
        pinHint.x = x;
        pinHint.y = y;
        pinHint.width = Math.max(1, width);
        pinHint.height = ITextRenderer.fontHeight;
        if (!pinHint.owned || !Objects.equals(hint, description)) {
            setHint(description);
        }
        pinHint.owned = true;
        pinHint.progress = pinHintProgress(1f);
        return true;
    }

    private void completePinHintFrame() {
        if (!pinHint.hovered) {
            if (pinHint.owned) {
                setHint("");
                pinHint.owned = false;
            }
            pinHint.progress = pinHintProgress(0f);
        }
        if (!pinHint.hovered && pinHint.progress <= 0.001f) {
            pinHint.pin = null;
            pinHint.progress = 0f;
        }
    }

    private float pinHintProgress(float target) {
        return animationsEnabled ? pinHint.progress + (target - pinHint.progress) * globalMovementSpeed * deltaTime : target;
    }

    private boolean describedPinLabelAt(String description, String label, int x, int y, int width,
                                        int mouseX, int mouseY) {
        return description != null && !description.isBlank() && label != null && !label.isBlank() && width > 0
            && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + ITextRenderer.fontHeight;
    }

    private int pinLabelColor(boolean hovered, int base, int hover) {
        return hovered ? blendPinLabelColor(base, hover, pinHint.progress) : base;
    }

    private int blendPinLabelColor(int from, int to, float progress) {
        float amount = Math.max(0f, Math.min(1f, progress));
        int alpha = (int) (((from >> 24) & 255) + (((to >> 24) & 255) - ((from >> 24) & 255)) * amount);
        int red = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * amount);
        int green = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * amount);
        int blue = (int) ((from & 255) + ((to & 255) - (from & 255)) * amount);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private void trackHintOverlayCandidate(Widget widget) {
        if (!(widget instanceof AnimatedWidget animated) || !widget.isVisible() || !widget.isHovered() && !widget.isFocused()) {
            return;
        }
        if (hintOverlayCandidate == null
            || widget.isHovered() && !hintOverlayCandidate.isHovered()
            || widget.isHovered() == hintOverlayCandidate.isHovered() && widget.getPriority() > hintOverlayCandidate.getPriority()) {
            hintOverlayCandidate = animated;
        }
    }

    private Map<String, FlowOptionSourceMetadata> functionInputCatalogs() {
        Map<String, FlowOptionSourceMetadata> catalogs = new LinkedHashMap<>();
        catalogs.put("None", null);
        if (typedCatalogAuthorityActive()) {
            return catalogs;
        }
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry == null) {
            return catalogs;
        }
        List<FlowOptionSourceMetadata> sources = new ArrayList<>(registry.getServerOptionSources(serverId));
        sources.sort((left, right) -> {
            int displayOrder = String.CASE_INSENSITIVE_ORDER.compare(left.getDisplayName(), right.getDisplayName());
            return displayOrder != 0 ? displayOrder : String.CASE_INSENSITIVE_ORDER.compare(left.getId(), right.getId());
        });
        for (FlowOptionSourceMetadata source : sources) {
            String label = source.getDisplayName();
            if (catalogs.containsKey(label)) {
                label += " · " + source.getId();
            }
            catalogs.put(label, source);
        }
        return catalogs;
    }

    private void updateSize() {
        int leftColumnWidth = getLeftColumnWidth();
        int rightColumnWidth = getRightColumnWidth();
        int contentWidth = leftColumnWidth + rightColumnWidth + (leftColumnWidth > 0 && rightColumnWidth > 0 ? COLUMN_GAP : 0);
        int contentHeight = Math.max(getInputsContentHeight(), getOutputsContentHeight());
        if (definition == null) {
            contentHeight = Math.max(contentHeight, ITextRenderer.fontHeight + 4);
        }
        int bottomRows = Math.max(addInputButtons.size(), addBranchButton != null && addBranchButton.visible ? 1 : 0);
        if (bottomRows > 0) {
            contentHeight += bottomRows * ROW_HEIGHT + (contentHeight > 0 ? bottomRows : bottomRows - 1) * ROW_SPACING;
        }
        int minWidth = (visibleInputs.isEmpty() || visibleOutputs.isEmpty()) ? SINGLE_COLUMN_MIN_WIDTH : DEFAULT_WIDTH;
        int titleWidth = tr.getWidth(nodeTitle()) + PADDING * 2;
        if (closeButton.visible) {
            titleWidth += CLOSE_BUTTON_WIDTH + PADDING;
        }
        if (paramButton != null) {
            titleWidth += CLOSE_BUTTON_WIDTH + 4;
        }
        if (openFunctionButton.visible) {
            titleWidth += CLOSE_BUTTON_WIDTH + 4;
        }
        if (inspectorButton != null && inspectorButton.visible) {
            titleWidth += CLOSE_BUTTON_WIDTH + 4;
        }

        setWidth(Math.max(minWidth, Math.max(titleWidth, PADDING * 2 + contentWidth)));
        setHeight(TITLE_HEIGHT + PADDING * 2 + contentHeight);
        invalidateChildLayout();
    }

    private void drawPassthroughGuides(IDrawContext ctx) {
        for (NodeDefinition.PinDefinition output : visibleOutputs) {
            if (!isPassthroughOutputPin(pinId(output))) {
                continue;
            }
            String inputPin = passthroughInputPin(pinId(output));
            double[] input = getPinBounds(inputPin, true);
            double[] outputBounds = getPinBounds(pinId(output), false);
            if (input == null || outputBounds == null) {
                continue;
            }
            int x1 = (int) (input[0] + input[2]);
            int y1 = (int) (input[1] + input[3] / 2);
            int x2 = (int) outputBounds[0];
            int y2 = (int) (outputBounds[1] + outputBounds[3] / 2);
            int midX = getX() + getWidth() / 2;
            int fill = getPinColor(output.getDataType());
            int border = ThemeManager.getColor(ThemeColor.innerBorder);
            drawDashedGuide(ctx, x1, y1, midX, y1, fill, border);
            drawDashedGuide(ctx, midX, y1, midX, y2, fill, border);
            drawDashedGuide(ctx, midX, y2, x2, y2, fill, border);
        }
    }

    private void drawDashedGuide(IDrawContext ctx, int x1, int y1, int x2, int y2, int fill, int border) {
        if (x1 == x2) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            for (int y = minY; y <= maxY; y += PASSTHROUGH_DASH_SIZE + PASSTHROUGH_DASH_GAP) {
                drawPassthroughDash(ctx, x1, y, fill, border);
            }
            return;
        }
        if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            for (int x = minX; x <= maxX; x += PASSTHROUGH_DASH_SIZE + PASSTHROUGH_DASH_GAP) {
                drawPassthroughDash(ctx, x, y1, fill, border);
            }
        }
    }

    private void drawPassthroughDash(IDrawContext ctx, int centerX, int centerY, int fill, int border) {
        int half = PASSTHROUGH_DASH_SIZE / 2;
        int x = centerX - half;
        int y = centerY - half;
        Render.drawLayeredInnerBorder(ctx, x, y, PASSTHROUGH_DASH_SIZE, PASSTHROUGH_DASH_SIZE, ThemeManager.getColor(ThemeColor.inClickableBackground), border);
        ctx.fill(x + 2, y + 2, x + PASSTHROUGH_DASH_SIZE - 2, y + PASSTHROUGH_DASH_SIZE - 2, fill);
    }

    public double[] getPinBounds(String pinName, boolean isInput) {
        ensureChildLayout();
        PinBounds bounds = (isInput ? inputPinBounds : outputPinBounds).get(pinName);
        return bounds != null ? bounds.array() : null;
    }

    public boolean isMouseOverPin(int wx, int wy) {
        if (getExpandedInputWidgetAt(wx, wy) != null) {
            return false;
        }
        return getPinAtPosition(wx, wy) != null;
    }

    public Widget getOutputWidgetAt(int wx, int wy) {
        updateOutputWidgetPositions();
        for (FlowBranch branch : flowBranches) {
            if (branch.widget != null && branch.widget.isMouseOver(wx, wy)) {
                return branch.widget;
            }
        }
        if (addBranchButton != null && addBranchButton.visible && addBranchButton.isMouseOver(wx, wy)) {
            return addBranchButton;
        }
        return null;
    }

    public Widget getInputWidgetAt(int wx, int wy) {
        updateInputWidgetPositions();
        Widget expandedWidget = getExpandedInputWidgetAt(wx, wy);
        if (expandedWidget != null) {
            return expandedWidget;
        }
        Widget bestWidget = null;
        for (Widget widget : inputWidgets.values()) {
            if (widget.isVisible() && widget.isMouseOver(wx, wy)) {
                if (bestWidget == null || widget.getPriority() > bestWidget.getPriority()) {
                    bestWidget = widget;
                }
            }
        }
        for (AnimatedButton button : addInputButtons.values()) {
            if (button.visible && isMouseOverRenderedChild(button, wx, wy)) {
                return button;
            }
        }
        return bestWidget;
    }

    boolean ownsInteractionChild(Widget child) {
        if (child == null) {
            return false;
        }
        for (Widget widget : inputWidgets.values()) {
            if (widget == child) {
                return true;
            }
        }
        for (FlowBranch branch : flowBranches) {
            if (branch.widget == child) {
                return true;
            }
        }
        if (addBranchButton == child) {
            return true;
        }
        for (Widget button : addInputButtons.values()) {
            if (button == child) {
                return true;
            }
        }
        return false;
    }

    public boolean handleBottomInputActionClick(int wx, int wy, int button) {
        updateInputWidgetPositions();
        for (AnimatedButton addButton : addInputButtons.values()) {
            if (!addButton.visible || !isMouseOverRenderedChild(addButton, wx, wy)) {
                continue;
            }
            if (button == 0) {
                addButton.onClick(wx, wy, button);
            }
            return true;
        }
        return false;
    }

    private boolean isMouseOverRenderedChild(Widget widget, int wx, int wy) {
        int visualY = widget.getY() + Math.round(currentElevationOffset);
        return wx >= widget.getX() - 1 && wx < widget.getX() + widget.getWidth() + 1 && wy >= visualY - 1 && wy < visualY + widget.getHeight() + 4;
    }

    private Widget getExpandedInputWidgetAt(int wx, int wy) {
        for (Widget widget : inputWidgets.values()) {
            if (widget instanceof DropDownWidget<?> dropdown && dropdown.isExpanded() && widget.isVisible() && widget.isMouseOver(wx, wy)) {
                return widget;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        int wx = (int) event.x();
        int wy = (int) event.y();

        if (mouseClickedCloseButton(event)) {
            return true;
        }

        if (paramButton != null && paramButton.isMouseOver(wx, wy)) {
            Widget.dispatchMouseClicked(paramButton, event);
            return true;
        }

        if (openFunctionButton.visible && openFunctionButton.isMouseOver(wx, wy)) {
            Widget.dispatchMouseClicked(openFunctionButton, event);
            return true;
        }

        if (inspectorButton != null && inspectorButton.visible && inspectorButton.isMouseOver(wx, wy)) {
            Widget.dispatchMouseClicked(inspectorButton, event);
            return true;
        }

        Widget outputWidget = getOutputWidgetAt(wx, wy);
        if (outputWidget != null) {
            setLastScreenMouse(wx, wy);
            Widget.dispatchMouseClicked(outputWidget, event);
            return true;
        }

        if (isMouseOverPin(wx, wy)) {
            return true;
        }

        Widget inputWidget = getInputWidgetAt(wx, wy);
        if (inputWidget != null) {
            NodeDefinition.PinDefinition inputDefinition = inputDefinitionForWidget(inputWidget);
            if (event.button() == ReMouseButton.RIGHT && showResourceReferenceMenu(inputDefinition, wx, wy)) {
                return true;
            }
            Widget.dispatchMouseClicked(inputWidget, event);
            if (inputWidget instanceof TextInputWidget || inputWidget instanceof TextAreaWidget) {
                if (ScreenManager.getInstance().getCurrentScreen() != null) {
                    ScreenManager.getInstance().getCurrentScreen().setFocusedWidget(inputWidget);
                }
            }
            return true;
        }

        return super.mouseClicked(event);
    }

    protected final boolean mouseClickedCloseButton(ReMouseEvent event) {
        if (event == null || !closeButton.visible || !closeButton.isMouseOver(event.x(), event.y())) {
            return false;
        }
        Widget.dispatchMouseClicked(closeButton, event);
        return true;
    }

    protected final boolean mouseClickedInspectorButton(ReMouseEvent event) {
        if (event == null || inspectorButton == null || !inspectorButton.visible
            || !inspectorButton.isMouseOver(event.x(), event.y())) {
            return false;
        }
        Widget.dispatchMouseClicked(inspectorButton, event);
        return true;
    }

    private NodeDefinition.PinDefinition inputDefinitionForWidget(Widget widget) {
        for (Map.Entry<String, Widget> entry : inputWidgets.entrySet()) {
            if (entry.getValue() == widget) {
                return findInputDefinition(entry.getKey());
            }
        }
        return null;
    }

    private boolean showResourceReferenceMenu(NodeDefinition.PinDefinition input, int x, int y) {
        if (input == null || input.getTypeRef() == null || !"resource_reference".equals(input.getTypeRef().getTypeId())) {
            return false;
        }
        Object stored = presentationInputValue(pinId(input));
        String id = resourceId(stored);
        String kind = stored instanceof FlowResourceReference reference ? reference.getKind() : "";
        if (kind.isBlank() && !input.getTypeRef().getArguments().isEmpty()) {
            kind = input.getTypeRef().getArguments().getFirst().getTypeId();
        }
        if (kind.isBlank() || id.isBlank()) {
            return false;
        }
        var screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null) {
            return false;
        }
        String resourceKind = kind;
        ReSyncFlowClient flowClient = catalogFlowClient(false);
        String catalogServerId = catalogServerId();
        if (catalogServerId == null) {
            return false;
        }
        String contextKey = flowClient != null ? flowClient.optionCatalogContextKey(optionCatalogContext(input)) : "";
        OptionCatalogItem catalogItem = OptionCatalogCache.getInstance().getItems(catalogServerId, input.getOptionsSource(), contextKey).stream()
            .filter(item -> item != null && id.equals(item.getValue()))
            .findFirst()
            .orElse(null);
        String label = catalogItem != null ? catalogItem.getLabel() : id;
        String hint = catalogItem != null && !catalogItem.getDescription().isBlank() ? catalogItem.getDescription() : resourceKind + ":" + id;
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(screen)
            .addIconItem("Edit " + label, "edit.png", () -> {
                if (screen instanceof StudioScreen studioScreen) {
                    studioScreen.openWorkspaceResource(resourceKind, id);
                } else {
                    new Notification("Open Resource", "Open This Flow In ReSync Studio", Notification.Type.ERROR);
                }
            }, hint)
            .addIconItem("Clear Selection", "close.png", () -> clearManagedReference(input), "Use another resource or leave this input empty");
        if (ReSyncResourceType.byTypeId(resourceKind) != null) {
            builder.addIconItem("Delete " + label, "delete.png", () -> confirmManagedResourceDelete(input, resourceKind, id, label),
                "Permanently delete this " + resourceName(resourceKind), ThemeManager.getAccent("danger"));
        }
        ContextMenuWidget menu = builder.build();
        screen.addDrawableChild(menu);
        menu.show(x, y);
        return true;
    }

    private boolean clearManagedReference(NodeDefinition.PinDefinition input) {
        if (nodeValueMutationHandler != null) {
            return applyCoreNodeValueProposal(input, InputValueProposal.removal());
        }
        if (node.getInputValues() != null) {
            node.getInputValues().remove(pinId(input));
        }
        searchableSelectorValues.remove(pinId(input));
        refreshInputWidgets();
        if (onMutation != null) {
            onMutation.run();
        }
        return true;
    }

    private void confirmManagedResourceDelete(NodeDefinition.PinDefinition input, String resourceType, String id, String label) {
        var screen = ScreenManager.getInstance().getCurrentScreen();
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resourceType);
        FlowManager manager = FlowManager.getInstance();
        if (screen == null || type == null || manager == null) {
            return;
        }
        PopupWidget.Builder builder = new PopupWidget.Builder("Delete " + resourceName(resourceType) + " | " + label)
            .setResizable(false)
            .width(360);
        AnimatedButton scope = new AnimatedButton.Builder()
            .label("Every Flow Using This Resource Will Need Another Selection")
            .active(false)
            .size(300, 18)
            .build();
        builder.addRow("Effect", scope);
        PopupWidget[] popup = new PopupWidget[1];
        builder.addTitleAction("Delete", () -> {
            if (popup[0] != null) {
                popup[0].hide();
            }
            manager.deleteResourceSettled(catalogServerId(), type, id).whenComplete((result, failure) -> {
                Runnable finish = () -> {
                    if (!settleManagedResourceDelete(input, resourceType, id, result, failure)) {
                        showManagedResourceDeleteFailure(result, failure);
                        return;
                    }
                    requestOptionCatalog(input.getOptionsSource(), optionCatalogContext(input), true);
                };
                ScreenManager.getInstance().execute(finish);
            });
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        popup[0] = builder.build();
        screen.addDrawableChild(popup[0]);
        popup[0].show();
    }

    private boolean settleManagedResourceDelete(NodeDefinition.PinDefinition input, String resourceType, String id,
                                                 FlowManager.ResourceDeleteResult result, Throwable failure) {
        if (failure != null || result == null || !result.deleted() || !resourceType.equals(result.type())
            || !id.equals(result.id())) {
            return false;
        }
        if (!referencesManagedResource(input, resourceType, id)) {
            return true;
        }
        return clearManagedReference(input);
    }

    private boolean referencesManagedResource(NodeDefinition.PinDefinition input, String resourceType, String id) {
        Object stored = presentationInputValue(pinId(input));
        if (!id.equals(resourceId(stored))) {
            return false;
        }
        String kind = stored instanceof FlowResourceReference reference ? reference.getKind() : "";
        if (kind.isBlank() && input != null && input.getTypeRef() != null && !input.getTypeRef().getArguments().isEmpty()) {
            kind = input.getTypeRef().getArguments().getFirst().getTypeId();
        }
        return resourceType.equals(kind);
    }

    private void showManagedResourceDeleteFailure(FlowManager.ResourceDeleteResult result, Throwable failure) {
        String message = result != null && result.message() != null && !result.message().isBlank()
            ? result.message() : failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
            ? failure.getMessage() : "Resource Delete Failed";
        if (message.length() > 240) {
            message = message.substring(0, 237) + "...";
        }
        new Notification("Delete", message, Notification.Type.ERROR);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        for (Widget widget : inputWidgets.values()) {
            Widget.dispatchMouseReleased(widget, event);
        }
        for (FlowBranch branch : flowBranches) {
            if (branch.widget != null) {
                Widget.dispatchMouseReleased(branch.widget, event);
            }
        }
        if (addBranchButton != null && addBranchButton.visible) {
            Widget.dispatchMouseReleased(addBranchButton, event);
        }
        for (AnimatedButton button : addInputButtons.values()) {
            if (button.visible) {
                Widget.dispatchMouseReleased(button, event);
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        TextInputWidget focusedWidget = getFocusedInputWidget();
        if (focusedWidget != null && Widget.dispatchMouseDragged(focusedWidget, event)) {
            return true;
        }
        TextAreaWidget focusedTextArea = getFocusedTextAreaWidget();
        if (focusedTextArea != null && Widget.dispatchMouseDragged(focusedTextArea, event)) {
            return true;
        }
        for (Widget widget : inputWidgets.values()) {
            if (widget != focusedWidget && widget != focusedTextArea && Widget.dispatchMouseDragged(widget, event)) {
                return true;
            }
        }
        for (FlowBranch branch : flowBranches) {
            if (branch.widget != null && Widget.dispatchMouseDragged(branch.widget, event)) {
                return true;
            }
        }
        if (addBranchButton != null && addBranchButton.visible && Widget.dispatchMouseDragged(addBranchButton, event)) {
            return true;
        }
        for (AnimatedButton button : addInputButtons.values()) {
            if (button.visible && Widget.dispatchMouseDragged(button, event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        for (FlowBranch branch : flowBranches) {
            if (branch.widget != null && Widget.dispatchMouseScrolled(branch.widget, event)) {
                return true;
            }
        }
        for (Widget widget : inputWidgets.values()) {
            if (Widget.dispatchMouseScrolled(widget, event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        TextInputWidget focusedWidget = getFocusedInputWidget();
        if (focusedWidget != null && Widget.dispatchTextInput(focusedWidget, event)) {
            saveInputValue();
            return true;
        }
        TextAreaWidget focusedTextArea = getFocusedTextAreaWidget();
        if (focusedTextArea != null && Widget.dispatchTextInput(focusedTextArea, event)) {
            saveInputValue();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        TextInputWidget focusedWidget = getFocusedInputWidget();
        if (focusedWidget != null && Widget.dispatchKeyPressed(focusedWidget, event)) {
            saveInputValue();
            return true;
        }
        TextAreaWidget focusedTextArea = getFocusedTextAreaWidget();
        if (focusedTextArea != null && Widget.dispatchKeyPressed(focusedTextArea, event)) {
            saveInputValue();
            return true;
        }
        return false;
    }

    private TextInputWidget getFocusedInputWidget() {
        for (Widget widget : inputWidgets.values()) {
            if (widget instanceof TextInputWidget textInput && textInput.isFocused()) {
                return textInput;
            }
        }
        return null;
    }

    private TextAreaWidget getFocusedTextAreaWidget() {
        for (Widget widget : inputWidgets.values()) {
            if (widget instanceof TextAreaWidget textArea && textArea.isFocused()) {
                return textArea;
            }
        }
        return null;
    }

    private void updateInputWidgetPositions() {
        ensureChildLayout();
    }

    private void ensureChildLayout() {
        ensurePinLayout();
        ChildLayoutKey key = new ChildLayoutKey(getX(), getY(), getWidth(), getHeight(), pinLayoutGeneration, childLayoutGeneration);
        if (key.equals(appliedChildLayout)) {
            return;
        }
        rebuildAbsolutePinLayout();
        layoutTitleButtons();
        layoutInputWidgets();
        layoutOutputWidgets();
        appliedChildLayout = key;
    }

    private void rebuildAbsolutePinLayout() {
        childLayoutLeftColumnWidth = getLeftColumnWidth();
        childLayoutRightColumnWidth = getRightColumnWidth();
        inputPinLayouts.clear();
        outputPinLayouts.clear();
        inputPinBounds.clear();
        outputPinBounds.clear();

        for (int index = 0; index < visibleInputs.size(); index++) {
            NodeDefinition.PinDefinition input = visibleInputs.get(index);
            String label = inputLabel(input);
            int rowY = getInputRowY(index);
            int rowHeight = getInputRowHeight(index);
            int pinY = rowY + (rowHeight - PIN_BUTTON_SIZE) / 2;
            int pinX = getX() + PADDING;
            int textY = rowY + (rowHeight - ITextRenderer.fontHeight) / 2 + 1;
            inputPinLayouts.add(new InputPinLayout(input, label, input.getDescription(), rowY, rowHeight, pinX, pinY,
                pinX + PIN_BUTTON_SIZE + PIN_TEXT_GAP, textY, tr.getWidth(label), getPinColor(input.getDataType())));
            inputPinBounds.putIfAbsent(pinId(input), new PinBounds(pinX, pinY));
            childLayoutOperationCount++;
        }

        int outputPinX = getX() + getWidth() - PADDING - PIN_BUTTON_SIZE;
        for (int index = 0; index < visibleOutputs.size(); index++) {
            NodeDefinition.PinDefinition output = visibleOutputs.get(index);
            int rowY = getOutputRowY(index);
            int rowHeight = getOutputRowHeight(index);
            int pinY = rowY + (rowHeight - PIN_BUTTON_SIZE) / 2;
            int textY = rowY + (rowHeight - ITextRenderer.fontHeight) / 2 + 1;
            boolean showLabel = getBranchWidget(pinId(output)) == null;
            String label = showLabel ? outputLabel(output) : "";
            int labelWidth = showLabel ? tr.getWidth(label) : 0;
            int labelX = outputPinX - PIN_TEXT_GAP - labelWidth;
            outputPinLayouts.add(new OutputPinLayout(output, label, output.getDescription(), rowY, rowHeight, outputPinX,
                pinY, labelX, textY, labelWidth, getPinColor(output.getDataType()), showLabel));
            outputPinBounds.putIfAbsent(pinId(output), new PinBounds(outputPinX, pinY));
            childLayoutOperationCount++;
        }
    }

    private void layoutTitleButtons() {
        int titleButtonX = getX() + getWidth() - PADDING;
        int titleButtonY = getY() + TITLE_STYLE.controlYOffset();
        if (closeButton.visible) {
            int closeX = titleButtonX - CLOSE_BUTTON_WIDTH;
            closeButton.setPosition(closeX, titleButtonY);
            titleButtonX = closeX - 4;
            childLayoutOperationCount++;
        }
        if (paramButton != null) {
            int paramX = titleButtonX - CLOSE_BUTTON_WIDTH;
            paramButton.setPosition(paramX, titleButtonY);
            titleButtonX = paramX - 4;
            childLayoutOperationCount++;
        }
        if (openFunctionButton.visible) {
            int openX = titleButtonX - CLOSE_BUTTON_WIDTH;
            openFunctionButton.setPosition(openX, titleButtonY);
            titleButtonX = openX - 4;
            childLayoutOperationCount++;
        }
        if (inspectorButton != null && inspectorButton.visible) {
            inspectorButton.setPosition(titleButtonX - CLOSE_BUTTON_WIDTH, titleButtonY);
            childLayoutOperationCount++;
        }
    }

    private void layoutInputWidgets() {
        int leftColumnEnd = getX() + PADDING + childLayoutLeftColumnWidth;

        for (int i = 0; i < visibleInputs.size(); i++) {
            NodeDefinition.PinDefinition input = visibleInputs.get(i);
            Widget inputWidget = inputWidgets.get(pinId(input));
            if (inputWidget != null) {
                InputPinLayout pinLayout = inputPinLayouts.get(i);
                int rowY = pinLayout.rowY();
                int rowHeight = pinLayout.rowHeight();
                int widgetWidth = getInputWidgetWidth(inputWidget);
                int widgetHeight = getInputWidgetHeight(inputWidget);
                int widgetY = rowY + (rowHeight - widgetHeight) / 2;
                int widgetX = leftColumnEnd - widgetWidth;
                inputWidget.setPosition(widgetX, widgetY);
                inputWidget.setWidth(widgetWidth);
                inputWidget.setHeight(widgetHeight);
                inputWidget.setPriority(visibleInputs.size() - i);
                if (inputWidget instanceof AnimatedWidget w) {
                    w.setLayer(visibleInputs.size() - i);
                }
                childLayoutOperationCount++;
            }
        }
        for (NodeDefinition.PinDefinition input : inputs) {
            Widget inputWidget = inputWidgets.get(pinId(input));
            if (inputWidget != null && !inputRowsById.containsKey(pinId(input))) {
                inputWidget.setVisible(false);
                childLayoutOperationCount++;
            }
        }
        int contentHeight = Math.max(getInputsContentHeight(), getOutputsContentHeight());
        int buttonIndex = 0;
        for (AnimatedButton button : addInputButtons.values()) {
            if (!button.visible) {
                continue;
            }
            int rowY = getRowStartY() + contentHeight + (contentHeight > 0 ? ROW_SPACING : 0) + buttonIndex * (ROW_HEIGHT + ROW_SPACING);
            button.setPosition(getX() + PADDING, rowY);
            button.setWidth(INPUT_WIDGET_WIDTH);
            button.setHeight(INPUT_WIDGET_HEIGHT);
            buttonIndex++;
            childLayoutOperationCount++;
        }
    }

    @Override
    public void tick() {
        if (!deferredInputValues.isEmpty()) {
            deferredInputValues.removeIf(pin -> reconcileInputValue(findInputDefinition(pin)));
        }
        super.tick();
        if (selected || hasVisualAccent()) {
            return;
        }
        bgColor = ThemeManager.getColor(ThemeColor.innerBackground);
        borderColor = ThemeManager.getColor(ThemeColor.innerBorder);
        outerBorderColor = ThemeManager.getColor(ThemeColor.globalOuterBorder);
    }

    private int getLeftColumnWidth() {
        int width = 0;
        for (NodeDefinition.PinDefinition input : visibleInputs) {
            int labelWidth = tr.getWidth(inputLabel(input));
            int rowWidth = PIN_BUTTON_SIZE + PIN_TEXT_GAP + labelWidth;
            Widget widget = inputWidgets.get(pinId(input));
            if (widget != null) {
                rowWidth += INPUT_FIELD_GAP + getInputWidgetWidth(widget);
            }
            width = Math.max(width, rowWidth);
        }
        for (AnimatedButton button : addInputButtons.values()) {
            if (button.visible) {
                width = Math.max(width, button.getWidth());
            }
        }
        return width;
    }

    private int getRightColumnWidth() {
        int width = 0;
        for (NodeDefinition.PinDefinition output : visibleOutputs) {
            AnimatedButton branchWidget = getBranchWidget(pinId(output));
            if (branchWidget != null) {
                int rowWidth = getOutputWidgetWidth(branchWidget) + PIN_TEXT_GAP + PIN_BUTTON_SIZE;
                width = Math.max(width, rowWidth);
            } else {
                int labelWidth = tr.getWidth(outputLabel(output));
                int rowWidth = labelWidth + PIN_TEXT_GAP + PIN_BUTTON_SIZE;
                width = Math.max(width, rowWidth);
            }
        }
        if (addBranchButton != null && addBranchButton.visible) {
            width = Math.max(width, addBranchButton.getWidth());
        }
        return width;
    }

    private int getRowStartY() {
        return getY() + TITLE_HEIGHT + PADDING;
    }

    private void drawPinButton(IDrawContext ctx, int x, int y, int color, String diagnosticField) {
        int background = ThemeManager.getColor(ThemeColor.inClickableBackground);
        int border = ThemeManager.getColor(ThemeColor.innerBorder);
        Render.drawLayeredInnerBorder(ctx, x, y, PIN_BUTTON_SIZE, PIN_BUTTON_SIZE, background, border);
        int inset = 2;
        ctx.fill(x + inset, y + inset, x + PIN_BUTTON_SIZE - inset, y + PIN_BUTTON_SIZE - inset, color);
        int diagnosticBorder = diagnosticPinBorder(diagnosticField);
        if (diagnosticBorder != 0) {
            ctx.fill(x - 2, y - 2, x + PIN_BUTTON_SIZE + 2, y - 1, diagnosticBorder);
            ctx.fill(x - 2, y + PIN_BUTTON_SIZE + 1, x + PIN_BUTTON_SIZE + 2, y + PIN_BUTTON_SIZE + 2, diagnosticBorder);
            ctx.fill(x - 2, y - 1, x - 1, y + PIN_BUTTON_SIZE + 1, diagnosticBorder);
            ctx.fill(x + PIN_BUTTON_SIZE + 1, y - 1, x + PIN_BUTTON_SIZE + 2, y + PIN_BUTTON_SIZE + 1, diagnosticBorder);
        }
    }

    private void saveInputValue() {
        if (nodeValueMutationHandler != null) {
            return;
        }
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        for (Map.Entry<String, Widget> entry : inputWidgets.entrySet()) {
            NodeDefinition.PinDefinition def = findInputDefinition(entry.getKey());
            if (def == null) {
                continue;
            }
            Widget widget = entry.getValue();
            if (!widget.isVisible()) {
                continue;
            }
            String pin = entry.getKey();
            Object typedValue = null;
            if (widget instanceof TextInputWidget textInput) {
                String value = textInput.getText();
                if (value.isEmpty()) {
                    node.getInputValues().remove(pin);
                    continue;
                }
                typedValue = convertLiteralValue(value, def);
            } else if (widget instanceof ToggleWidget toggle) {
                typedValue = toggle.getValue();
            } else if (widget instanceof AnimatedButton button) {
                String value = searchableSelectorValues.getOrDefault(entry.getKey(), button.getMessage());
                if (value == null || value.isBlank() || "Loading".equals(value)) {
                    continue;
                }
                typedValue = convertLiteralValue(value, def);
            } else if (widget instanceof DropDownWidget<?> dropdown) {
                Object value = dropdown.getSelectedItem();
                if (value == null) {
                    continue;
                }
                typedValue = convertLiteralValue(value.toString(), def);
            } else if (widget instanceof SliderWidget slider) {
                typedValue = slider.getValue();
            } else if (widget instanceof TextAreaWidget textArea) {
                String value = textArea.getText();
                if (value.isEmpty()) {
                    node.getInputValues().remove(pin);
                    continue;
                }
                typedValue = value;
            } else if (widget instanceof ColorFieldWidget colorField) {
                String value = colorField.getColor();
                if (value.isEmpty()) {
                    node.getInputValues().remove(pin);
                    continue;
                }
                typedValue = value;
            }
            if (typedValue != null) {
                node.getInputValues().put(pin, typedValue);
            }
        }
        if (updateStringTemplatePins()) {
            updatePinVisibility();
        }
    }

    private int getInputWidgetWidth(Widget widget) {
        if (widget instanceof ToggleWidget) {
            return TOGGLE_WIDGET_WIDTH;
        }
        return widget.getWidth();
    }

    private int getInputWidgetHeight(Widget widget) {
        if (widget instanceof ToggleWidget) {
            return TOGGLE_WIDGET_HEIGHT;
        }
        return widget.getHeight();
    }

    private int getInputRowY(int index) {
        ensurePinLayout();
        return getRowStartY() + (index >= 0 && index < inputRows.size() ? inputRows.get(index).offset() : 0);
    }

    private int getOutputRowY(int index) {
        ensurePinLayout();
        return getRowStartY() + (index >= 0 && index < outputRows.size() ? outputRows.get(index).offset() : 0);
    }

    private int getInputRowHeight(int index) {
        ensurePinLayout();
        return index >= 0 && index < inputRows.size() ? inputRows.get(index).height() : ROW_HEIGHT;
    }

    private int getOutputRowHeight(int index) {
        ensurePinLayout();
        return index >= 0 && index < outputRows.size() ? outputRows.get(index).height() : ROW_HEIGHT;
    }

    private int getInputsContentHeight() {
        ensurePinLayout();
        return inputsContentHeight;
    }

    private int getOutputsContentHeight() {
        ensurePinLayout();
        return outputsContentHeight;
    }

    private void invalidatePinLayout() {
        pinLayoutDirty = true;
        pinLayoutGeneration++;
        invalidateChildLayout();
    }

    private void invalidateChildLayout() {
        collaborationChildrenDirty = true;
        childLayoutGeneration++;
        appliedChildLayout = null;
    }

    private void ensurePinLayout() {
        if (!pinLayoutDirty) {
            return;
        }
        inputRows.clear();
        outputRows.clear();
        inputRowsById.clear();
        outputRowsById.clear();
        branchWidgetsByOutput.clear();

        for (FlowBranch branch : flowBranches) {
            branchWidgetsByOutput.putIfAbsent(branch.outputName, branch.widget);
            pinLayoutOperationCount++;
        }

        int offset = 0;
        for (NodeDefinition.PinDefinition input : visibleInputs) {
            Widget widget = inputWidgets.get(pinId(input));
            int height = widget != null ? Math.max(ROW_HEIGHT, getInputWidgetHeight(widget)) : ROW_HEIGHT;
            PinRow row = new PinRow(offset, height);
            inputRows.add(row);
            inputRowsById.putIfAbsent(pinId(input), row);
            offset += height + ROW_SPACING;
            pinLayoutOperationCount++;
        }
        inputsContentHeight = inputRows.isEmpty() ? 0 : offset - ROW_SPACING;

        offset = 0;
        for (NodeDefinition.PinDefinition output : visibleOutputs) {
            AnimatedButton branchWidget = branchWidgetsByOutput.get(pinId(output));
            int height = branchWidget != null ? Math.max(ROW_HEIGHT, getOutputWidgetHeight(branchWidget)) : ROW_HEIGHT;
            PinRow row = new PinRow(offset, height);
            outputRows.add(row);
            outputRowsById.putIfAbsent(pinId(output), row);
            offset += height + ROW_SPACING;
            pinLayoutOperationCount++;
        }
        outputsContentHeight = outputRows.isEmpty() ? 0 : offset - ROW_SPACING;
        pinLayoutDirty = false;
    }

    long getPinLayoutOperationCount() {
        ensurePinLayout();
        return pinLayoutOperationCount;
    }

    long getChildLayoutOperationCount() {
        ensureChildLayout();
        return childLayoutOperationCount;
    }

    void prepareRenderLayout() {
        ensureChildLayout();
    }

    private NodeDefinition.PinDefinition findInputDefinition(String pinName) {
        for (NodeDefinition.PinDefinition input : inputs) {
            if (pinId(input).equals(pinName)) {
                return input;
            }
        }
        return null;
    }

    private Object convertValue(String value, FlowDataType dataType) {
        if (dataType == null || dataType == FlowDataType.ANY) {
            return value;
        }
        String id = dataType.getId();
        try {
            if ("number".equals(id)) {
                return Double.parseDouble(value);
            }
            if ("boolean".equals(id)) {
                return Boolean.parseBoolean(value);
            }
            return value;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private Object convertLiteralValue(String value, NodeDefinition.PinDefinition definition) {
        FlowTypeRef typeRef = definition != null ? definition.getTypeRef() : null;
        if (typeRef != null && "resource_reference".equals(typeRef.getTypeId())) {
            return canonicalResourceLocator(definition, value);
        }
        return convertValue(value, definition != null ? definition.getDataType() : FlowDataType.ANY);
    }

    private boolean resourceReferenceInput(NodeDefinition.PinDefinition input) {
        FlowTypeRef typeRef = input != null ? input.getTypeRef() : null;
        return typeRef != null && "resource_reference".equals(typeRef.getTypeId());
    }

    private TypeReference exactResourceType(NodeDefinition.PinDefinition input) {
        FlowTypeRef typeRef = input != null ? input.getTypeRef() : null;
        if (typeRef == null || !"resource_reference".equals(typeRef.getTypeId()) || typeRef.getArguments().size() != 1) {
            return null;
        }
        FlowTypeRef resource = typeRef.getArguments().getFirst();
        String identity = resource != null ? resource.getTypeId() : "";
        int separator = identity.indexOf(':');
        if (separator <= 0 || separator != identity.lastIndexOf(':') || separator == identity.length() - 1
            || !resource.getArguments().isEmpty()) {
            return null;
        }
        try {
            return TypeReference.of(identity.substring(0, separator), identity.substring(separator + 1));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, Object> canonicalResourceLocator(NodeDefinition.PinDefinition input, String id) {
        TypeReference type = exactResourceType(input);
        String connectedServerId = catalogServerId();
        if (type == null || id == null || id.isBlank() || connectedServerId == null || connectedServerId.isBlank()) {
            return null;
        }
        try {
            ServerResourceLocator locator = new ServerResourceLocator(ServerId.parseCanonicalText(connectedServerId),
                ContractRef.of(OwnerId.of(type.ownerId()), ResourceTypeId.of(type.localId())), id);
            return locator.canonicalValue();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String resourceId(Object value) {
        if (value instanceof FlowResourceReference reference) {
            return reference.getId() != null ? reference.getId() : "";
        }
        if (value instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id != null ? id.toString() : "";
        }
        return value != null ? value.toString() : "";
    }

    public FlowDataType getPinType(String pinName, boolean isInput) {
        if (isInput) {
            for (NodeDefinition.PinDefinition input : inputs) {
                if (pinId(input).equals(pinName)) {
                    return input.getDataType();
                }
            }
        } else {
            for (NodeDefinition.PinDefinition output : outputs) {
                if (pinId(output).equals(pinName)) {
                    return output.getDataType();
                }
            }
            for (NodeDefinition.PinDefinition output : visibleOutputs) {
                if (pinId(output).equals(pinName)) {
                    return output.getDataType();
                }
            }
        }
        return null;
    }

    public FlowTypeRef getPinTypeRef(String pinName, boolean isInput) {
        if (isInput) {
            for (NodeDefinition.PinDefinition input : inputs) {
                if (pinId(input).equals(pinName)) {
                    return input.getTypeRef();
                }
            }
        } else {
            for (NodeDefinition.PinDefinition output : outputs) {
                if (pinId(output).equals(pinName)) {
                    return output.getTypeRef();
                }
            }
            for (NodeDefinition.PinDefinition output : visibleOutputs) {
                if (pinId(output).equals(pinName)) {
                    return output.getTypeRef();
                }
            }
        }
        return null;
    }

    public String getInputOptionsSource(String pinName) {
        NodeDefinition.PinDefinition input = findInputDefinition(pinName);
        return input != null ? input.getOptionsSource() : null;
    }

    public boolean assignLiteralInput(String pinName, String value) {
        NodeDefinition.PinDefinition input = findInputDefinition(pinName);
        if (input == null) {
            return false;
        }
        Object converted = convertLiteralValue(value, input);
        if (converted == null) {
            return false;
        }
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        node.getInputValues().put(pinId(input), converted);
        refreshInputWidgets();
        updatePinVisibility();
        return true;
    }

    private boolean updateStringTemplatePins() {
        if (coreResource != null) {
            boolean changed = !stringTemplateInputNames.isEmpty();
            inputs.removeIf(input -> stringTemplateInputNames.contains(pinId(input)));
            stringTemplateInputNames.clear();
            return changed;
        }
        if (isFunctionStartNode() || isFunctionEndNode()) {
            return false;
        }
        List<String> current = new ArrayList<>(stringTemplateInputNames);
        List<String> next = new ArrayList<>(nodeStringTemplateNames());
        Set<String> reservedInputNames = new LinkedHashSet<>();
        for (NodeDefinition.PinDefinition input : inputs) {
            if (!stringTemplateInputNames.contains(pinId(input))) {
                reservedInputNames.add(pinId(input));
            }
        }
        next.removeIf(reservedInputNames::contains);
        if (current.equals(next)) {
            for (String name : next) {
                if (inputs.stream().noneMatch(input -> name.equals(pinId(input)))) {
                    inputs.add(new NodeDefinition.PinDefinition(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING));
                }
            }
            return false;
        }

        Set<String> nextSet = new LinkedHashSet<>(next);
        Set<String> removed = new LinkedHashSet<>(current);
        removed.removeAll(nextSet);
        removeStringTemplateConnections(removed);

        inputs.removeIf(input -> stringTemplateInputNames.contains(pinId(input)));
        for (String name : next) {
            inputs.add(new NodeDefinition.PinDefinition(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING));
        }
        stringTemplateInputNames.clear();
        stringTemplateInputNames.addAll(next);
        if (node.getInputValues() != null) {
            for (String name : removed) {
                node.getInputValues().remove(name);
            }
        }
        return true;
    }

    private boolean isStringTemplateValuePin(NodeDefinition.PinDefinition input) {
        return input != null && stringTemplateInputNames.contains(pinId(input));
    }

    private void removeStringTemplateConnections(Set<String> removed) {
        if (removed.isEmpty() || graph == null || graph.getConnections() == null) {
            return;
        }
        graph.getConnections().removeIf(connection -> nodeId.equals(connection.getTargetNodeId()) && removed.contains(connection.getTargetPinId()));
        graph.getEditorPassthroughs().removeIf(passthrough -> nodeId.equals(passthrough.getNodeId()) && removed.contains(passthrough.getInputPinId()));
    }

    private Set<String> nodeStringTemplateNames() {
        Set<String> names = new LinkedHashSet<>();
        if (definition == null || definition.getInputs() == null || node.getInputValues() == null) {
            return names;
        }
        for (NodeDefinition.PinDefinition input : definition.getInputs()) {
            if (!isStringTemplateSourceInput(input)) {
                continue;
            }
            Object value = node.getInputValues().get(pinId(input));
            if (value instanceof String text) {
                names.addAll(stringTemplateNames(text));
            }
        }
        return names;
    }

    private boolean isStringTemplateSourceInput(NodeDefinition.PinDefinition input) {
        return input != null
            && input.getType() == NodeDefinition.PinType.DATA
            && input.getDirection() == NodeDefinition.PinDirection.INPUT
            && input.getDataType() == FlowDataType.STRING
            && shouldShowInputPin(input)
            && evaluateVisibleWhen(input.getVisibleWhen());
    }

    private Set<String> stringTemplateNames(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        int index = 0;
        while (index < template.length()) {
            char current = template.charAt(index);
            if (current == '{') {
                if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
                    index += 2;
                    continue;
                }
                int end = template.indexOf('}', index + 1);
                if (end > index + 1) {
                    String name = template.substring(index + 1, end).trim();
                    if (isStringTemplateName(name)) {
                        names.add(name);
                        index = end + 1;
                        continue;
                    }
                }
            }
            index++;
        }
        return names;
    }

    private boolean isStringTemplateName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    public boolean isFunctionStartOrEnd() {
        return isFunctionStartNode() || isFunctionEndNode();
    }

    public String getNodeId() {
        return nodeId;
    }

    public CoreRepeatableUiProjection.PinEndpoint corePinEndpoint(String viewPin) {
        CoreRepeatableUiProjection.PinEndpoint endpoint = corePinEndpoints.get(viewPin);
        if (endpoint != null) {
            return endpoint;
        }
        try {
            return CoreRepeatableUiProjection.PinEndpoint.plain(PinId.of(viewPin));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public String coreViewPin(PinId pinId, RepeatableElementId elementId) {
        if (pinId == null) {
            return null;
        }
        if (elementId == null) {
            return pinId.canonicalText();
        }
        return coreViewPins.get(coreEndpointKey(new CoreRepeatableUiProjection.PinEndpoint(pinId, elementId)));
    }

    private Optional<CoreRepeatableUiProjection.Group> coreRepeatableGroup(
        CoreRepeatableUiProjection.PinEndpoint endpoint) {
        if (endpoint == null || endpoint.elementId() == null) {
            return Optional.empty();
        }
        NodeDefinition.PinDefinition pin = findDefinitionPin(endpoint.pinId(), true);
        if (pin == null) {
            pin = findDefinitionPin(endpoint.pinId(), false);
        }
        return pin != null && pin.getRepeatable() != null
            ? coreRepeatables.group(pin.getRepeatable().getGroupId()) : Optional.empty();
    }

    public NodeDefinition.PinType getPinKind(String pinName, boolean isInput) {
        if (isInput) {
            for (NodeDefinition.PinDefinition input : inputs) {
                if (pinId(input).equals(pinName)) {
                    return input.getType();
                }
            }
        } else {
            for (NodeDefinition.PinDefinition output : outputs) {
                if (pinId(output).equals(pinName)) {
                    return output.getType();
                }
            }
            for (NodeDefinition.PinDefinition output : visibleOutputs) {
                if (pinId(output).equals(pinName)) {
                    return output.getType();
                }
            }
        }
        return null;
    }

    public String getPinAtPosition(int wx, int wy) {
        for (NodeDefinition.PinDefinition input : visibleInputs) {
            double[] bounds = getPinBounds(pinId(input), true);
            if (bounds != null && isInside(wx, wy, bounds)) {
                return pinId(input);
            }
        }
        for (NodeDefinition.PinDefinition output : visibleOutputs) {
            double[] bounds = getPinBounds(pinId(output), false);
            if (bounds != null && isInside(wx, wy, bounds)) {
                return pinId(output);
            }
        }
        return null;
    }

    public List<String> getVisibleInputPins() {
        return visibleInputs.stream().map(NodeWidget::pinId).toList();
    }

    public List<String> getVisibleOutputPins() {
        return visibleOutputs.stream().map(NodeWidget::pinId).toList();
    }

    public String getInputPinAtPosition(int wx, int wy) {
        ensureChildLayout();
        int minX = getX();
        int maxX = getX() + PADDING + childLayoutLeftColumnWidth + PIN_HIT_PADDING;
        for (InputPinLayout layout : inputPinLayouts) {
            String id = pinId(layout.pin());
            double[] bounds = inputPinBounds.get(id).array();
            if (bounds != null && isInside(wx, wy, bounds)) {
                return id;
            }
            if (wx >= minX && wx <= maxX && wy >= layout.rowY() - PIN_HIT_PADDING
                && wy <= layout.rowY() + layout.rowHeight() + PIN_HIT_PADDING) {
                return id;
            }
        }
        return null;
    }

    public String getOutputPinAtPosition(int wx, int wy) {
        ensureChildLayout();
        int minX = getX() + getWidth() - PADDING - childLayoutRightColumnWidth - PIN_HIT_PADDING;
        int maxX = getX() + getWidth();
        for (OutputPinLayout layout : outputPinLayouts) {
            String id = pinId(layout.pin());
            double[] bounds = outputPinBounds.get(id).array();
            if (bounds != null && isInside(wx, wy, bounds)) {
                return id;
            }
            if (wx >= minX && wx <= maxX && wy >= layout.rowY() - PIN_HIT_PADDING
                && wy <= layout.rowY() + layout.rowHeight() + PIN_HIT_PADDING) {
                return id;
            }
        }
        return null;
    }

    private void createOutputWidgets() {
        invalidatePinLayout();
        visibleOutputs.clear();
        flowBranches.clear();

        List<NodeDefinition.PinDefinition> metadataVisibleOutputs = new ArrayList<>();
        for (NodeDefinition.PinDefinition output : outputs) {
            if (evaluateVisibleWhen(output, output.getVisibleWhen())) {
                metadataVisibleOutputs.add(output);
            }
        }

        List<NodeDefinition.PinDefinition> flowOutputs = new ArrayList<>();
        List<NodeDefinition.PinDefinition> otherOutputs = new ArrayList<>();
        for (NodeDefinition.PinDefinition output : metadataVisibleOutputs) {
            if (isFlowOutput(output)) {
                flowOutputs.add(output);
            } else {
                otherOutputs.add(output);
            }
        }

        if (definitionReadOnly || nodeValueMutationHandler != null) {
            visibleOutputs.addAll(flowOutputs);
            visibleOutputs.addAll(otherOutputs);
            visibleOutputs.sort((left, right) -> Boolean.compare(!isFlowOutput(left), !isFlowOutput(right)));
            appendPassthroughOutputs();
            addBranchButton = null;
            return;
        }

        if (flowOutputs.size() <= 2) {
            visibleOutputs.addAll(flowOutputs);
            visibleOutputs.addAll(otherOutputs);
            visibleOutputs.sort((left, right) -> Boolean.compare(!isFlowOutput(left), !isFlowOutput(right)));
            appendPassthroughOutputs();
            addBranchButton = null;
            return;
        }

        List<String> selectedBranches = resolveFlowBranches(flowOutputs);
        for (String branch : selectedBranches) {
            NodeDefinition.PinDefinition pin = findOutputDefinition(branch);
            if (pin != null && metadataVisibleOutputs.contains(pin)) {
                visibleOutputs.add(pin);
                flowBranches.add(new FlowBranch(branch, buildBranchSelector(flowOutputs, branch)));
            }
        }

        visibleOutputs.addAll(otherOutputs);
        visibleOutputs.sort((left, right) -> Boolean.compare(!isFlowOutput(left), !isFlowOutput(right)));
        appendPassthroughOutputs();
        saveFlowBranches();
        updateAddBranchButton(flowOutputs);
    }

    private void appendPassthroughOutputs() {
        if (graph == null || graph.getEditorPassthroughs() == null) {
            return;
        }
        for (FlowGraph.EditorPassthrough passthrough : graph.getEditorPassthroughs()) {
            if (passthrough == null || !nodeId.equals(passthrough.getNodeId())) {
                continue;
            }
            NodeDefinition.PinDefinition input = findVisibleInputDefinition(passthrough.getInputPinId());
            if (input == null || input.getType() != NodeDefinition.PinType.DATA) {
                continue;
            }
            String outputName = passthroughOutputPin(pinId(input));
            boolean exists = false;
            for (NodeDefinition.PinDefinition output : visibleOutputs) {
                if (outputName.equals(pinId(output))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                visibleOutputs.add(EditorPassthroughPins.outputDefinition(input));
            }
        }
    }

    private NodeDefinition.PinDefinition findVisibleInputDefinition(String pinName) {
        return visibleInputsById.get(pinName);
    }

    private boolean isFlowOutput(NodeDefinition.PinDefinition output) {
        return output.getType() == NodeDefinition.PinType.FLOW && output.getDataType() == FlowDataType.EXECUTION;
    }

    private String outputLabel(NodeDefinition.PinDefinition output) {
        String name = passthroughInputPin(pinId(output));
        String displayName = pinDisplayName(output);
        if (isPassthroughOutputPin(pinId(output))) {
            NodeDefinition.PinDefinition input = findVisibleInputDefinition(name);
            displayName = input != null ? pinDisplayName(input) : name;
        }
        return displayName;
    }

    private String inputLabel(NodeDefinition.PinDefinition input) {
        return pinDisplayName(input);
    }

    private NodeDefinition.PinDefinition findOutputDefinition(String name) {
        for (NodeDefinition.PinDefinition output : outputs) {
            if (pinId(output).equals(name)) {
                return output;
            }
        }
        return null;
    }

    private List<String> resolveFlowBranches(List<NodeDefinition.PinDefinition> flowOutputs) {
        List<String> options = new ArrayList<>();
        for (NodeDefinition.PinDefinition output : flowOutputs) {
            options.add(pinId(output));
        }

        List<String> selected = new ArrayList<>();
        boolean storedBranchesPresent = false;
        if (node.getInputValues() != null) {
            Object stored = node.getInputValues().get(FLOW_BRANCHES_KEY);
            if (stored instanceof List<?> list) {
                storedBranchesPresent = true;
                for (Object entry : list) {
                    if (entry instanceof String name && options.contains(name)) {
                        if (!selected.contains(name)) {
                            selected.add(name);
                        }
                    }
                }
            }
        }

        if (graph != null && graph.getConnections() != null) {
            for (FlowConnection conn : graph.getConnections()) {
                if (nodeId.equals(conn.getSourceNodeId()) && options.contains(conn.getSourcePinId())) {
                    if (!selected.contains(conn.getSourcePinId())) {
                        selected.add(conn.getSourcePinId());
                    }
                }
            }
        }

        if (selected.isEmpty() && !storedBranchesPresent && !options.isEmpty()) {
            selected.add(options.getFirst());
        }
        return selected;
    }

    private AnimatedButton buildBranchSelector(List<NodeDefinition.PinDefinition> flowOutputs, String selected) {
        List<String> options = new ArrayList<>();
        for (NodeDefinition.PinDefinition output : flowOutputs) {
            options.add(pinId(output));
        }
        AnimatedButton button = new AnimatedButton.Builder()
            .label(selected)
            .size(OUTPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
            .entranceAnimation(false)
            .build();
        button.setAction(() -> showBranchSelector(button, options, button.getMessage(), value -> {
            String oldName = button.getMessage();
            if (value == null || value.isBlank() || "Loading".equals(value)) {
                return;
            }
            button.setMessage(value);
            updateFlowBranchSelection(oldName, value);
        }));
        return button;
    }

    private void updateFlowBranchSelection(String oldName, String newName) {
        if (updatingBranchSelection || oldName == null || newName == null || oldName.equals(newName)) {
            return;
        }
        FlowBranch targetBranch = null;
        boolean conflict = false;
        for (FlowBranch branch : flowBranches) {
            if (branch.outputName.equals(oldName)) {
                targetBranch = branch;
            } else if (branch.outputName.equals(newName)) {
                conflict = true;
            }
        }

        if (conflict) {
            updatingBranchSelection = true;
            if (targetBranch != null && targetBranch.widget != null) {
                targetBranch.widget.setMessage(oldName);
            }
            updatingBranchSelection = false;
            return;
        }

        if (targetBranch != null) {
            targetBranch.outputName = newName;
        }

        if (graph != null && graph.getConnections() != null) {
            for (FlowConnection conn : graph.getConnections()) {
                if (nodeId.equals(conn.getSourceNodeId()) && oldName.equals(conn.getSourcePinId())) {
                    conn.setSourcePinId(newName);
                }
            }
        }

        saveFlowBranches();
        updatePinVisibility();
    }

    private void updateAddBranchButton(List<NodeDefinition.PinDefinition> flowOutputs) {
        if (flowBranches.size() >= flowOutputs.size()) {
            addBranchButton = null;
            return;
        }
        if (addBranchButton == null) {
            addBranchButton = new AnimatedButton.Builder()
                .label("Add Branch")
                .onClick(this::addFlowBranch)
                .animateElevation(false)
                .entranceAnimation(false)
                .size(OUTPUT_WIDGET_WIDTH, INPUT_WIDGET_HEIGHT)
                .build();
        }
        addBranchButton.visible = true;
    }

    private void addFlowBranch() {
        List<String> options = new ArrayList<>();
        for (NodeDefinition.PinDefinition output : outputs) {
            if (isFlowOutput(output)) {
                options.add(pinId(output));
            }
        }
        for (String option : options) {
            boolean used = false;
            for (FlowBranch branch : flowBranches) {
                if (branch.outputName.equals(option)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                flowBranches.add(new FlowBranch(option, null));
                break;
            }
        }
        saveFlowBranches();
        updatePinVisibility();
    }

    private void saveFlowBranches() {
        if (node.getInputValues() == null) {
            node.setInputValues(new HashMap<>());
        }
        List<String> branches = new ArrayList<>();
        for (FlowBranch branch : flowBranches) {
            branches.add(branch.outputName);
        }
        node.getInputValues().put(FLOW_BRANCHES_KEY, branches);
    }

    private void updateOutputWidgetPositions() {
        ensureChildLayout();
    }

    private void layoutOutputWidgets() {
        int buttonX = getX() + getWidth() - PADDING - childLayoutRightColumnWidth;

        for (int i = 0; i < visibleOutputs.size(); i++) {
            NodeDefinition.PinDefinition output = visibleOutputs.get(i);
            AnimatedButton branchWidget = getBranchWidget(pinId(output));
            if (branchWidget != null) {
                OutputPinLayout pinLayout = outputPinLayouts.get(i);
                int rowY = pinLayout.rowY();
                int rowHeight = pinLayout.rowHeight();
                int widgetWidth = getOutputWidgetWidth(branchWidget);
                int widgetHeight = getOutputWidgetHeight(branchWidget);
                int widgetY = rowY + (rowHeight - widgetHeight) / 2;
                int widgetX = buttonX + childLayoutRightColumnWidth - PIN_BUTTON_SIZE - PIN_TEXT_GAP - widgetWidth;
                branchWidget.setPosition(widgetX, widgetY);
                branchWidget.setWidth(widgetWidth);
                branchWidget.setHeight(widgetHeight);
                branchWidget.setPriority(visibleOutputs.size() - i);
                branchWidget.setLayer(visibleOutputs.size() - i);
                childLayoutOperationCount++;
            }
        }

        if (addBranchButton != null && addBranchButton.visible) {
            int contentHeight = Math.max(getInputsContentHeight(), getOutputsContentHeight());
            int rowY = getRowStartY() + contentHeight + (contentHeight > 0 ? ROW_SPACING : 0);
            addBranchButton.setPosition(buttonX, rowY);
            addBranchButton.setWidth(Math.min(OUTPUT_WIDGET_WIDTH, childLayoutRightColumnWidth));
            addBranchButton.setHeight(INPUT_WIDGET_HEIGHT);
            childLayoutOperationCount++;
        }

        updateFunctionParameterButtonPosition();
    }

    private void updateFunctionParameterButtonPosition() {
    }

    private int getOutputWidgetWidth(Widget widget) {
        return OUTPUT_WIDGET_WIDTH;
    }

    private int getOutputWidgetHeight(Widget widget) {
        return INPUT_WIDGET_HEIGHT;
    }

    private AnimatedButton getBranchWidget(String outputName) {
        ensurePinLayout();
        return branchWidgetsByOutput.get(outputName);
    }

    private record PinRow(int offset, int height) {
    }

    private static class FlowBranch {
        private String outputName;
        private final AnimatedButton widget;

        private FlowBranch(String outputName, AnimatedButton widget) {
            this.outputName = outputName;
            this.widget = widget;
        }
    }

    private boolean isInside(int x, int y, double[] bounds) {
        double minX = bounds[0] - PIN_HIT_PADDING;
        double minY = bounds[1] - PIN_HIT_PADDING;
        double maxX = bounds[0] + bounds[2] + PIN_HIT_PADDING;
        double maxY = bounds[1] + bounds[3] + PIN_HIT_PADDING;
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
