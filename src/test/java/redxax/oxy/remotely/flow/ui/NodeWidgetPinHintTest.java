package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncProductionDescriptorFixture;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.IMatrixStack;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.util.Identifier;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeWidgetPinHintTest {
    @BeforeAll
    static void initializeRendering() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void typedCatalogDescriptionReachesTheRenderedPinDefinition() {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        NodeDefinition definition = ReSyncProductionDescriptorFixture.widget(interaction, "event.command").definition();
        NodeDefinition.PinDefinition arguments = definition.getOutputs().stream()
            .filter(pin -> "event.args".equals(pin.getName()))
            .findFirst()
            .orElseThrow();

        assertEquals("Provides the command arguments as joined text.", arguments.getDescription());
    }

    @Test
    void describedPinLabelsGlowAndRenderHintsWithoutAffectingBlankLabels() {
        boolean animations = Config.animationsEnabled;
        Config.animationsEnabled = false;
        try {
            NodeDefinition definition = new NodeDefinition.Builder("pin-hints", "Pin Hints", NodeDefinition.NodeCategory.FLOW)
                .owner("builtin")
                .input(new NodeDefinition.PinBuilder("input", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT,
                    FlowDataType.ENTITY).displayName("Input Value").description("Describes the input value.").build())
                .output(new NodeDefinition.PinBuilder("output", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT,
                    FlowDataType.ENTITY).displayName("Output Value").description("Describes the output value.").build())
                .output(new NodeDefinition.PinBuilder("blank", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT,
                    FlowDataType.ENTITY).displayName("Blank Value").description("").build())
                .build();
            NodeWidget widget = new NodeWidget(40, 70,
                new FlowNode("builtin:pin-hints", 40, 70, new LinkedHashMap<>()), new FlowGraph(), "node", null,
                null, null, definition, false);
            widget.hintDelay = 0f;
            RecordingDrawContext context = new RecordingDrawContext();

            widget.render(context, -100, -100, 0f);
            assertEquals(ThemeManager.getColor(ThemeColor.textDark), context.color("Input Value"));
            assertEquals(ThemeManager.getColor(ThemeColor.textDark), context.color("Output Value"));
            assertEquals(ThemeManager.getColor(ThemeColor.textDark), context.color("Blank Value"));

            double[] inputBounds = widget.getPinBounds("input", true);
            assertNotNull(inputBounds);
            context.clearText();
            widget.render(context, inputLabelX(inputBounds), labelY(inputBounds), 0f);
            assertEquals("Describes the input value.", widget.hint);
            assertEquals(ThemeManager.getColor(ThemeColor.text), context.color("Input Value"));
            widget.render(context, inputLabelX(inputBounds), labelY(inputBounds), 0f);
            context.clearText();
            widget.renderHintOverlay(context);
            assertTrue(context.hasText("Describes the input value."));

            double[] blankBounds = widget.getPinBounds("blank", false);
            assertNotNull(blankBounds);
            context.clearText();
            widget.render(context, outputLabelX(blankBounds), labelY(blankBounds), 0f);
            assertEquals("", widget.hint);
            assertEquals(ThemeManager.getColor(ThemeColor.textDark), context.color("Blank Value"));
            context.clearText();
            widget.renderHintOverlay(context);
            assertFalse(context.hasText("Describes the input value."));

            double[] outputBounds = widget.getPinBounds("output", false);
            assertNotNull(outputBounds);
            context.clearText();
            widget.render(context, outputLabelX(outputBounds), labelY(outputBounds), 0f);
            assertEquals("Describes the output value.", widget.hint);
            assertEquals(ThemeManager.getColor(ThemeColor.text), context.color("Output Value"));
            assertEquals(ThemeManager.getColor(ThemeColor.textDark), context.color("Input Value"));

            context.clearText();
            widget.render(context, -100, -100, 0f);
            assertEquals("", widget.hint);
            assertEquals(ThemeManager.getColor(ThemeColor.textDark), context.color("Output Value"));
        } finally {
            Config.animationsEnabled = animations;
        }
    }

    @Test
    void pinHintResolutionSharesTheExistingPinDrawTraversal() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/NodeWidget.java"));
        String drawContent = source.substring(source.indexOf("protected void drawContent"),
            source.indexOf("private void renderExpandedDropdownOverlays"));
        String observePinHint = source.substring(source.indexOf("private boolean observePinHint"),
            source.indexOf("private void completePinHintFrame"));

        assertEquals(1, occurrences(drawContent, "for (InputPinLayout layout : inputPinLayouts)"));
        assertEquals(1, occurrences(drawContent, "for (OutputPinLayout layout : outputPinLayouts)"));
        assertEquals(2, occurrences(drawContent, "observePinHint(layout.pin(), layout.description()"));
        assertFalse(drawContent.contains(".getDescription()"));
        assertFalse(observePinHint.contains("for ("));
        assertFalse(observePinHint.contains(".stream()"));
        assertFalse(source.contains("private void updatePinHint"));
    }

    @Test
    void focusedChildHintSuppressesTheHoveredPinHint() throws Exception {
        boolean animations = Config.animationsEnabled;
        Config.animationsEnabled = false;
        try {
            NodeDefinition definition = new NodeDefinition.Builder("pin-hints", "Pin Hints", NodeDefinition.NodeCategory.FLOW)
                .owner("builtin")
                .input(new NodeDefinition.PinBuilder("input", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT,
                    FlowDataType.ENTITY).displayName("Input Value").description("Describes the input value.").build())
                .build();
            NodeWidget widget = new NodeWidget(40, 70,
                new FlowNode("builtin:pin-hints", 40, 70, new LinkedHashMap<>()), new FlowGraph(), "node", null,
                null, null, definition, false);
            widget.hintDelay = 0f;
            RecordingDrawContext context = new RecordingDrawContext();
            FocusedHintWidget focusedChild = new FocusedHintWidget();
            inputWidgets(widget).put("input", focusedChild);

            double[] inputBounds = widget.getPinBounds("input", true);
            assertNotNull(inputBounds);
            int mouseX = inputLabelX(inputBounds);
            int mouseY = labelY(inputBounds);
            widget.render(context, mouseX, mouseY, 0f);
            widget.render(context, mouseX, mouseY, 0f);
            assertTrue(focusedChild.isFocused());
            assertFalse(focusedChild.isHovered());
            assertEquals("Focused Child Hint", focusedChild.hint);
            assertTrue(hintVisible(widget));
            context.clearText();
            widget.renderHintOverlay(context);

            assertTrue(context.hasText("Focused Child Hint"));
            assertFalse(context.hasText("Describes the input value."));
        } finally {
            Config.animationsEnabled = animations;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Widget> inputWidgets(NodeWidget widget) throws ReflectiveOperationException {
        Field field = NodeWidget.class.getDeclaredField("inputWidgets");
        field.setAccessible(true);
        return (Map<String, Widget>) field.get(widget);
    }

    private static boolean hintVisible(AnimatedWidget widget) throws ReflectiveOperationException {
        Field field = AnimatedWidget.class.getDeclaredField("hintVisible");
        field.setAccessible(true);
        return field.getBoolean(widget);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static int inputLabelX(double[] bounds) {
        return (int) (bounds[0] + bounds[2]) + 5;
    }

    private static int outputLabelX(double[] bounds) {
        return (int) bounds[0] - 5;
    }

    private static int labelY(double[] bounds) {
        return (int) (bounds[1] + bounds[3] / 2);
    }

    private static final class FocusedHintWidget extends AnimatedWidget {
        private FocusedHintWidget() {
            super(0, 0, 20, 12, "");
            setHint("Focused Child Hint");
            setFocused(true);
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
        }

        @Override
        public void renderHintOverlay(IDrawContext context) {
            context.drawText(hint, getX(), getY(), ThemeManager.getColor(ThemeColor.text), false);
        }
    }

    private static final class RecordingDrawContext implements IDrawContext {
        private final Map<String, Integer> textColors = new LinkedHashMap<>();
        private final IMatrixStack matrices = new IMatrixStack() {
            @Override
            public void push() {
            }

            @Override
            public void pop() {
            }

            @Override
            public void translate(float x, float y, float z) {
            }

            @Override
            public void scale(float x, float y, float z) {
            }

            @Override
            public void rotate(float angle, float x, float y, float z) {
            }

            @Override
            public void multiply(float angle) {
            }
        };

        private int color(String text) {
            Integer color = textColors.get(text);
            assertNotNull(color, text);
            return color;
        }

        private boolean hasText(String text) {
            return textColors.containsKey(text);
        }

        private void clearText() {
            textColors.clear();
        }

        @Override
        public IMatrixStack getMatrices() {
            return matrices;
        }

        @Override
        public void pushScissorState() {
        }

        @Override
        public void popScissorState() {
        }

        @Override
        public void clearScissor() {
        }

        @Override
        public void enableScissor(float x1, float y1, float x2, float y2) {
        }

        @Override
        public boolean scissorsContains(int x, int y) {
            return true;
        }

        @Override
        public void disableScissor() {
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int argb) {
        }

        @Override
        public void fillGradient(int x1, int y1, int x2, int y2, int color1, int color2) {
        }

        @Override
        public void fillGradient(int x1, int y1, int x2, int y2, int color1, int color2, boolean horizontal) {
        }

        @Override
        public void fillRoundedRectWithBorders(int x, int y, int width, int height, float roundness, int bgColor,
                                               int borderColor, int outerBorderColor) {
        }

        @Override
        public void drawAnimatedCornerGradient(float x, float y, float width, float height, int color) {
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            textColors.put(text, color);
        }

        @Override
        public void drawItem(Object item, int x, int y, int z) {
        }

        @Override
        public void drawBufferedImage(Identifier id, float x, float y, float width, float height) {
        }

        @Override
        public void drawPixelArt(Identifier id, float x, float y, float width, float height) {
        }

        @Override
        public void drawInvertedRect(float x1, float y1, float x2, float y2) {
        }
    }
}
