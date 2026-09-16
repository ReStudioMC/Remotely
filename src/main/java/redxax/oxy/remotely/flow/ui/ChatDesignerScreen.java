package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncContentBrowserWidget;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.layout.FreeLayout;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.TabSwitchWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatDesignerScreen extends FocusedJsonResourceDesignerScreen {
    private static final List<String> SECTIONS = List.of("Channel", "Rules", "Private", "Mentions");
    private static final List<String> PREVIEW_MODES = List.of("Public", "Private", "Mention");
    private static final int GAP = 6;
    private static final int TOOLBAR_HEIGHT = 20;
    private static final int MIN_SPLIT_WIDTH = 620;

    private Container editorPane;
    private Container previewPane;
    private TabSwitchWidget sectionTabs;
    private TabSwitchWidget previewTabs;
    private AnimatedButton samplesButton;
    private AnimatedButton guideButton;
    private AnimatedButton previewToggleButton;
    private ChatPreviewWidget previewWidget;
    private boolean initialized;
    private boolean compactPreview;
    private boolean pairedLayout;
    private int activeSection;
    private int previewMode;
    private int workspaceX;
    private int workspaceY;
    private int workspaceWidth;
    private int workspaceHeight;
    private int viewportWidth;
    private int viewportHeight;
    private String sampleSender = "Steve";
    private String sampleReceiver = "Alex";
    private String sampleMessage = "Hello @Alex";

    public ChatDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.CHAT, resourceId, resource, serverId, parent);
    }

    @Override
    public boolean hasPanel() {
        return false;
    }

    @Override
    public void init() {
        ensureWorkspace();
    }

    @Override
    public void resize(int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        updateWorkspaceBounds();
        ensureWorkspace();
        updateWorkspaceLayout();
    }

    private void updateWorkspaceBounds() {
        int browserWidth = host != null ? host.studioContentBrowserPanelWidth() : 0;
        int edge = browserWidth > 0 ? ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_GAP : 18;
        workspaceX = browserWidth > 0 ? browserWidth + edge : 18;
        workspaceY = ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_TOP;
        workspaceWidth = Math.max(120, viewportWidth - workspaceX - edge);
        workspaceHeight = Math.max(80, viewportHeight - workspaceY - ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_BOTTOM);
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        ensureWorkspace();
        updateWorkspaceBounds();
        updateWorkspaceLayout();
        if (editorPane.isVisible()) {
            editorPane.render(context, mouseX, mouseY, delta);
        }
        if (previewPane.isVisible()) {
            previewPane.render(context, mouseX, mouseY, delta);
        }
        sectionTabs.render(context, mouseX, mouseY, delta);
        if (previewToggleButton.isVisible()) {
            previewToggleButton.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void renderStudioOverlay(IDrawContext context, int mouseX, int mouseY, float delta) {
        super.renderStudioOverlay(context, mouseX, mouseY, delta);
        if (!initialized) {
            return;
        }
        if (editorPane.isVisible()) {
            editorPane.renderHintOverlay(context);
        }
        if (previewPane.isVisible()) {
            previewPane.renderHintOverlay(context);
        }
        sectionTabs.renderHintOverlay(context);
        if (previewToggleButton.isVisible()) {
            previewToggleButton.renderHintOverlay(context);
        }
    }

    private void ensureWorkspace() {
        if (initialized) {
            return;
        }
        initialized = true;
        editorPane = new Container("chat-editor", 0, 0, 300, 160);
        editorPane.layout(new ManagedLayout()).columns(1).padding(GAP).scrolling(true).backgroundDrawing(true);

        previewPane = new Container("chat-preview", 0, 0, 260, 160);
        previewPane.layout(new FreeLayout()).padding(GAP).scrolling(false).backgroundDrawing(true);

        sectionTabs = new TabSwitchWidget.Builder()
            .options(SECTIONS)
            .currentIndex(activeSection)
            .entranceAnimation(false)
            .onChange(this::selectSection)
            .build();
        previewTabs = new TabSwitchWidget.Builder()
            .options(PREVIEW_MODES)
            .currentIndex(previewMode)
            .entranceAnimation(false)
            .onChange(index -> previewMode = index)
            .build();
        samplesButton = new AnimatedButton.Builder()
            .label("Preview Data")
            .size(88, TOOLBAR_HEIGHT)
            .entranceAnimation(false)
            .onClick(this::showPreviewDataPopup)
            .build();
        guideButton = new AnimatedButton.Builder()
            .label("Placeholders")
            .size(82, TOOLBAR_HEIGHT)
            .entranceAnimation(false)
            .onClick(this::showPlaceholderPopup)
            .build();
        previewToggleButton = new AnimatedButton.Builder()
            .label("Preview")
            .size(74, TOOLBAR_HEIGHT)
            .entranceAnimation(false)
            .onClick(() -> {
                compactPreview = !compactPreview;
                updateWorkspaceLayout();
            })
            .build();
        previewWidget = new ChatPreviewWidget(0, 0, 240, 120);

        previewPane.addWidget(previewTabs);
        previewPane.addWidget(previewWidget);
        previewPane.addWidget(guideButton);
        previewPane.addWidget(samplesButton);
        rebuildEditorFields();
    }

    private void selectSection(int index) {
        activeSection = Math.clamp(index, 0, SECTIONS.size() - 1);
        compactPreview = false;
        previewMode = switch (activeSection) {
            case 2 -> 1;
            case 3 -> 2;
            default -> 0;
        };
        previewTabs.setCurrentIndex(previewMode);
        rebuildEditorFields();
    }

    private void rebuildEditorFields() {
        if (!initialized) {
            return;
        }
        clearStudioResourcePanelWidgets();
        editorPane.clearWidgets();
        int rowWidth = Math.max(120, editorPane.getWidth() - GAP * 2);
        switch (activeSection) {
            case 0 -> {
                addField("format.template", rowWidth);
                addPair("displayName", "channel.prefix", rowWidth);
                addPair("channel.range", "channel.allowMiniMessage", rowWidth);
                addPair("channel.speakPermission", "channel.readPermission", rowWidth);
                if (Boolean.parseBoolean(jsonPathText("channel.allowMiniMessage"))) {
                    addField("channel.miniMessagePermission", rowWidth);
                }
            }
            case 1 -> {
                addPair("rule.contains", "rule.action", rowWidth);
                ruleFields().stream().filter(field -> !List.of("rule.contains", "rule.action").contains(field)).forEach(field -> addField(field, rowWidth));
            }
            case 2 -> {
                addPair("privateMessages.sender", "privateMessages.receiver", rowWidth);
                addField("privateMessages.spy", rowWidth);
                addField("privateMessages.privateMessageFlow", rowWidth);
            }
            default -> {
                addField("mention.template", rowWidth);
                addField("mention.mentionFlow", rowWidth);
                addField("ignorePlayersText", rowWidth);
            }
        }
        editorPane.resetScroll();
        updateWorkspaceLayout();
    }

    private void addField(String field, int width) {
        editorPane.addWidget(fieldRow(field, width));
    }

    private void addPair(String firstField, String secondField, int width) {
        if (!pairedLayout) {
            addField(firstField, width);
            addField(secondField, width);
            return;
        }
        AnimatedWidget first = fieldRow(firstField, width / 2);
        AnimatedWidget second = fieldRow(secondField, width / 2);
        int height = Math.max(first.getHeight(), second.getHeight());
        editorPane.addWidget(new RowWidget.Builder()
            .size(width, height)
            .padding(GAP)
            .entranceAnimation(false)
            .addWidget(first, second)
            .build());
    }

    private List<String> ruleFields() {
        List<String> fields = new ArrayList<>(List.of("rule.contains", "rule.action"));
        switch (jsonPathText("rule.action").trim().toLowerCase(Locale.ROOT)) {
            case "replace" -> fields.add("rule.replacement");
            case "channel" -> fields.add("rule.channel");
            case "flow" -> fields.add("rule.flowId");
            default -> {
            }
        }
        return fields;
    }

    private void updateWorkspaceLayout() {
        if (!initialized) {
            return;
        }
        int innerX = workspaceX + GAP;
        int toolbarY = workspaceY + GAP;
        int innerWidth = Math.max(80, workspaceWidth - GAP * 2);
        int contentY = toolbarY + TOOLBAR_HEIGHT + GAP;
        int contentHeight = Math.max(40, workspaceY + workspaceHeight - GAP - contentY);
        boolean compact = innerWidth < MIN_SPLIT_WIDTH || contentHeight < 220;
        boolean compactToggleAvailable = compact && innerWidth >= 260;

        previewToggleButton.setVisible(compactToggleAvailable);
        previewToggleButton.setMessage(compactPreview ? "Editor" : "Preview");
        sectionTabs.setPosition(innerX, toolbarY);
        sectionTabs.setSize(Math.max(80, innerWidth - (compactToggleAvailable ? previewToggleButton.getWidth() + GAP : 0)), TOOLBAR_HEIGHT);
        if (compactToggleAvailable) {
            previewToggleButton.setPosition(innerX + innerWidth - previewToggleButton.getWidth(), toolbarY);
        }

        boolean nextPairedLayout;
        if (!compact) {
            int editorWidth = Math.clamp((int) (innerWidth * 0.62), 360, innerWidth - 250 - GAP);
            editorPane.setPosition(innerX, contentY);
            editorPane.size(editorWidth, contentHeight);
            previewPane.setPosition(innerX + editorWidth + GAP, contentY);
            previewPane.size(innerWidth - editorWidth - GAP, contentHeight);
            editorPane.setVisible(true);
            previewPane.setVisible(true);
            nextPairedLayout = editorWidth >= 520;
        } else {
            editorPane.setPosition(innerX, contentY);
            editorPane.size(innerWidth, contentHeight);
            previewPane.setPosition(innerX, contentY);
            previewPane.size(innerWidth, contentHeight);
            editorPane.setVisible(!compactPreview || !compactToggleAvailable);
            previewPane.setVisible(compactPreview && compactToggleAvailable);
            nextPairedLayout = false;
        }
        if (nextPairedLayout != pairedLayout) {
            pairedLayout = nextPairedLayout;
            rebuildEditorFields();
            return;
        }

        int previewInnerWidth = Math.max(60, previewPane.getWidth() - GAP * 2);
        previewTabs.setPosition(previewPane.getX() + GAP, previewPane.getY() + GAP);
        previewTabs.setSize(previewInnerWidth, 18);
        boolean showPreviewActions = previewPane.getHeight() >= 100;
        guideButton.setVisible(showPreviewActions);
        samplesButton.setVisible(showPreviewActions);
        previewWidget.setPosition(previewPane.getX() + GAP, previewPane.getY() + 30);
        previewWidget.setSize(previewInnerWidth, Math.max(20, previewPane.getHeight() - (showPreviewActions ? 62 : 36)));
        if (showPreviewActions) {
            int actionY = previewPane.getY() + previewPane.getHeight() - 26;
            int actionWidth = Math.max(48, (previewInnerWidth - GAP) / 2);
            guideButton.setPosition(previewPane.getX() + GAP, actionY);
            guideButton.setSize(actionWidth, TOOLBAR_HEIGHT);
            samplesButton.setPosition(guideButton.getX() + actionWidth + GAP, actionY);
            samplesButton.setSize(previewInnerWidth - actionWidth - GAP, TOOLBAR_HEIGHT);
        }
    }

    private void showPreviewDataPopup() {
        PopupWidget.Builder builder = new PopupWidget.Builder("Preview Data")
            .width(390)
            .setResizable(false)
            .setAntiOutOfBound(true);
        builder.addTextField("Sender", sampleSender, value -> sampleSender = previewValue(value, "Steve"));
        builder.addTextField("Receiver", sampleReceiver, value -> sampleReceiver = previewValue(value, "Alex"));
        builder.addTextField("Message", sampleMessage, value -> sampleMessage = previewValue(value, "Hello @Alex"));
        PopupWidget popup = builder.build();
        hostScreen().addDrawableChild(popup);
        popup.show();
    }

    private void showPlaceholderPopup() {
        String markdown = """
            ### Public Chat
            `{prefix}` Channel prefix
            `{sender}` Sender name
            `{message}` Message text

            ### Private Messages
            `{sender}` Sender name
            `{receiver}` Receiver name
            `{message}` Message text

            ### Mentions
            `{player}` Mentioned player
            """;
        PopupWidget popup = new PopupWidget.Builder("Placeholders")
            .width(420)
            .setResizable(false)
            .setAntiOutOfBound(true)
            .addMarkdown("", markdown)
            .build();
        hostScreen().addDrawableChild(popup);
        popup.show();
    }

    private String previewValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    protected void reloadFields() {
        rebuildEditorFields();
    }

    @Override
    public void onStudioCatalogRefreshed() {
        rebuildEditorFields();
    }

    @Override
    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        return initialized && (
            previewToggleButton.isVisible() && Widget.dispatchMouseClicked(previewToggleButton, event)
                || Widget.dispatchMouseClicked(sectionTabs, event)
                || previewPane.isVisible() && Widget.dispatchMouseClicked(previewPane, event)
                || editorPane.isVisible() && Widget.dispatchMouseClicked(editorPane, event)
        );
    }

    @Override
    protected boolean handleResourceMouseReleased(ReMouseEvent event) {
        return initialized && (
            previewToggleButton.isVisible() && Widget.dispatchMouseReleased(previewToggleButton, event)
                || Widget.dispatchMouseReleased(sectionTabs, event)
                || previewPane.isVisible() && Widget.dispatchMouseReleased(previewPane, event)
                || editorPane.isVisible() && Widget.dispatchMouseReleased(editorPane, event)
        );
    }

    @Override
    protected boolean handleResourceMouseDragged(ReMouseEvent event) {
        return initialized && (
            previewPane.isVisible() && Widget.dispatchMouseDragged(previewPane, event)
                || editorPane.isVisible() && Widget.dispatchMouseDragged(editorPane, event)
        );
    }

    @Override
    protected boolean handleResourceMouseScrolled(ReScrollEvent event) {
        return initialized && (
            previewPane.isVisible() && Widget.dispatchMouseScrolled(previewPane, event)
                || editorPane.isVisible() && Widget.dispatchMouseScrolled(editorPane, event)
        );
    }

    @Override
    protected boolean handleResourceKeyPressed(ReKeyEvent event) {
        return initialized && (
            previewPane.isVisible() && Widget.dispatchKeyPressed(previewPane, event)
                || editorPane.isVisible() && Widget.dispatchKeyPressed(editorPane, event)
        );
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (super.textInput(event)) {
            return true;
        }
        return initialized && (
            previewPane.isVisible() && Widget.dispatchTextInput(previewPane, event)
                || editorPane.isVisible() && Widget.dispatchTextInput(editorPane, event)
        );
    }

    @Override
    protected boolean centeredTextPreview() {
        return false;
    }

    @Override
    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderChatRealPreview(context, previewX, previewY, previewWidth, previewHeight, text, muted);
    }

    @Override
    protected List<String> editorFields() {
        return chatFields();
    }

    @Override
    protected List<ResourcePanelSection> editorSections(List<String> fields) {
        return appendRemainingSections(List.of(
            new ResourcePanelSection("Channel", fields.stream().filter(field -> field.equals("displayName") || field.startsWith("channel.")).toList()),
            new ResourcePanelSection("Format", fields.stream().filter(field -> field.startsWith("format.")).toList()),
            new ResourcePanelSection("Rule", fields.stream().filter(field -> field.startsWith("rule.")).toList()),
            new ResourcePanelSection("Private Messages", fields.stream().filter(field -> field.startsWith("privateMessages.")).toList()),
            new ResourcePanelSection("Mentions", fields.stream().filter(field -> field.startsWith("mention.")).toList()),
            new ResourcePanelSection("Ignore", fields.stream().filter(field -> field.equals("ignorePlayersText")).toList())
        ), fields);
    }

    @Override
    protected List<String> actionOptions(String field) {
        return List.of("block", "replace", "flow", "channel");
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        return "rule.action".equals(field) ? actionOptions(field) : null;
    }

    @Override
    protected boolean customDropdownField(String field) {
        return "rule.action".equals(field);
    }

    @Override
    protected boolean customRebuildOnSelection(String field) {
        return "rule.action".equals(field);
    }

    @Override
    protected AnimatedWidget customFieldRow(String field, String label, int rowWidth) {
        if (!"channel.allowMiniMessage".equals(field)) {
            return null;
        }
        ToggleWidget toggle = new ToggleWidget.Builder()
            .label("Enabled")
            .toggled(Boolean.parseBoolean(jsonPathText(field)))
            .size(96, 18)
            .entranceAnimation(false)
            .build();
        toggle.onChange = () -> {
            putJsonText(field, Boolean.toString(toggle.getValue()));
            rebuildEditorFields();
        };
        return new TitledRowWidget.Builder()
            .title(label)
            .description(jsonResourceDescription(field, label))
            .size(rowWidth, 34)
            .gap(4)
            .addWidget(toggle)
            .build();
    }

    @Override
    protected boolean customCodeField(String field) {
        return List.of(
            "format.template",
            "rule.replacement",
            "privateMessages.sender",
            "privateMessages.receiver",
            "privateMessages.spy",
            "mention.template",
            "ignorePlayersText"
        ).contains(field);
    }

    @Override
    protected int customCodeFieldHeight(String field) {
        return switch (field) {
            case "format.template" -> 78;
            case "ignorePlayersText" -> 68;
            default -> customCodeField(field) ? 58 : -1;
        };
    }

    @Override
    protected String resourceSummary() {
        return firstFilled(jsonText("displayName"), jsonPathText("channel.prefix"), "Chat");
    }

    @Override
    protected String resourceDisplayName() {
        return "Chat";
    }

    @Override
    protected String mentionPreviewTemplate() {
        return jsonPathText("mention.template");
    }

    protected void renderChatRealPreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int text, int muted) {
        List<PreviewLine> lines = previewLines();
        int lineY = previewY + Math.max(8, previewHeight - lines.size() * 19 - 8);
        for (PreviewLine line : lines) {
            drawFormattedLine(context, line.text(), previewX + 8, lineY, line.muted() ? muted : text, true);
            lineY += 19;
        }
    }

    private List<PreviewLine> previewLines() {
        return switch (previewMode) {
            case 1 -> privatePreviewLines();
            case 2 -> mentionPreviewLines();
            default -> publicPreviewLines();
        };
    }

    private List<PreviewLine> publicPreviewLines() {
        String template = firstFilled(jsonPathText("format.template"), "{prefix}{sender}: {message}");
        String prefix = jsonPathText("channel.prefix");
        return List.of(
            new PreviewLine(expand(template, prefix, sampleSender, sampleReceiver, sampleMessage), false),
            new PreviewLine(expand(template, prefix, sampleReceiver, sampleSender, "Looks good"), true),
            new PreviewLine(expand(template, prefix, sampleSender, sampleReceiver, "This updates live"), false)
        );
    }

    private List<PreviewLine> privatePreviewLines() {
        String sender = firstFilled(jsonPathText("privateMessages.sender"), "<gray>You -> {receiver}: {message}");
        String receiver = firstFilled(jsonPathText("privateMessages.receiver"), "<gray>{sender} -> You: {message}");
        String spy = firstFilled(jsonPathText("privateMessages.spy"), "<dark_gray>[Spy] {sender} -> {receiver}: {message}");
        return List.of(
            new PreviewLine(expand(sender, "", sampleSender, sampleReceiver, sampleMessage), false),
            new PreviewLine(expand(receiver, "", sampleSender, sampleReceiver, sampleMessage), false),
            new PreviewLine(expand(spy, "", sampleSender, sampleReceiver, sampleMessage), true)
        );
    }

    private List<PreviewLine> mentionPreviewLines() {
        String template = firstFilled(jsonPathText("format.template"), "{prefix}{sender}: {message}");
        String prefix = jsonPathText("channel.prefix");
        String line = expand(template, prefix, sampleSender, sampleReceiver, sampleMessage);
        return List.of(
            new PreviewLine(applySampleMention(line, sampleReceiver), false),
            new PreviewLine(expand(template, prefix, sampleReceiver, sampleSender, "I saw that"), true),
            new PreviewLine(applySampleMention(expand(template, prefix, sampleReceiver, sampleSender, "Thanks @" + sampleSender), sampleSender), false)
        );
    }

    private String applySampleMention(String line, String player) {
        String template = firstFilled(jsonPathText("mention.template"), "<yellow>@{player}</yellow>");
        return line.replace("@" + player, template.replace("{player}", player));
    }

    private String expand(String template, String prefix, String sender, String receiver, String message) {
        return template
            .replace("{prefix}", prefix)
            .replace("{sender}", sender)
            .replace("{receiver}", receiver)
            .replace("{player}", receiver)
            .replace("{message}", message);
    }

    protected List<String> chatFields() {
        return List.of(
            "displayName",
            "channel.prefix",
            "format.template",
            "channel.range",
            "channel.speakPermission",
            "channel.readPermission",
            "channel.allowMiniMessage",
            "channel.miniMessagePermission",
            "rule.contains",
            "rule.action",
            "rule.replacement",
            "rule.channel",
            "rule.flowId",
            "privateMessages.sender",
            "privateMessages.receiver",
            "privateMessages.spy",
            "privateMessages.privateMessageFlow",
            "mention.template",
            "mention.mentionFlow",
            "ignorePlayersText"
        );
    }

    private record PreviewLine(String text, boolean muted) {
    }

    private final class ChatPreviewWidget extends AnimatedWidget {
        private ChatPreviewWidget(int x, int y, int width, int height) {
            super(x, y, width, height, "");
            entranceAnimationEnabled = false;
            animateElevation = false;
            enableHoverColors = false;
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
            int text = ThemeManager.getColor(ThemeColor.text);
            int muted = ThemeManager.getColor(ThemeColor.textDark);
            renderChatRealPreview(context, getX(), getY(), getWidth(), getHeight(), text, muted);
        }

        @Override
        protected void drawBackground(IDrawContext context) {
        }

        @Override
        protected void drawBorder(IDrawContext context) {
        }
    }
}
