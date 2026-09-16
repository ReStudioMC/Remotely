package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowWorkspaceDocument;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborationDocuments;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioResourceRenameAware;
import redxax.oxy.remotely.flow.ui.studio.StudioSaveProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import static restudio.rescreen.config.Config.desktopMode;

public class ScoreboardDesignerScreen extends StudioScreen implements DesktopWindowBehaviorProvider, StudioCloseHandledScreen, StudioResourceRenameAware, ReSyncCollaborativeView, StudioSaveProvider {
    private static final int PANEL_PADDING = 8;
    private static final int PREVIEW_ROW_BG = 0x7F101010;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SCORE_COLOR = 0xFFFF5555;
    private static final Pattern MINI_HEX_PATTERN = Pattern.compile("<#([0-9a-fA-F]{6})>");

    private ScoreboardDefinition scoreboard;
    private final VersionedEditorDraft<ScoreboardDefinition> scoreboardDraft;
    private final String serverId;
    private final Object parent;
    private final boolean forceSuperScreen;
    private final boolean animateTopHeader;
    private final ReSyncStudioPanelState panelState = new ReSyncStudioPanelState();

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (scoreboardDraft.defer(() -> resourceRenamed(type, oldId, newId))) {
            return;
        }
        scoreboardDraft.markMutation();
        if (oldId.equals(scoreboard.getId())) {
            ReSyncResourceType.SCOREBOARD.applyRename(scoreboard, newId);
        }
    }

    private StudioPanel inspectorStudioPanel;
    private SidePanel inspectorPanel;
    private TextInputWidget titleInput;
    private TextInputWidget objectiveInput;
    private CodeEditorWidget linesInput;
    private String previewTitle = "";
    private List<String> previewLines = new ArrayList<>();
    private int previewRequestRevision;
    private Runnable studioCloseHandler;
    private boolean closingRequested;
    private boolean closeCompleted;
    private boolean studioCloseNotified;
    private boolean applyingCollaboration;
    private final History<JsonObject> history = history(() -> collaborationDocument().deepCopy(), this::restoreCollaborationDocument);
    private final HistoryRebaseCoordinator<JsonObject> historyRebase;
    private volatile long collaborationLifecycle = 1L;

    @Override
    public JsonObject collaborationDocument() {
        return ReSyncCollaborationDocuments.from(scoreboard);
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return scoreboardDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return scoreboardDraft.requestProjection(ReSyncResourceType.SCOREBOARD.typeId(),
            ReSyncResourceType.SCOREBOARD.extractId(scoreboard), this::collaborationSource,
            projection -> completion.accept(collaborationSnapshot(lifecycle, projection)));
    }

    private ScoreboardDefinition collaborationSource() {
        ScoreboardDefinition source = new ScoreboardDefinition();
        source.setId(scoreboard.getId());
        source.setEnabled(scoreboard.isEnabled());
        source.setTitle(scoreboard.getTitle());
        source.setObjectiveId(scoreboard.getObjectiveId());
        source.setDisplaySlot(scoreboard.getDisplaySlot());
        source.setLines(scoreboard.getLines() != null ? new ArrayList<>(scoreboard.getLines()) : new ArrayList<>());
        return source;
    }

    private CollaborationDocumentSnapshot collaborationSnapshot(long lifecycle,
                                                                 VersionedEditorDraft.ProjectionSnapshot projection) {
        RuntimeException failure = projection.failure();
        JsonObject document = null;
        if (failure == null) {
            try {
                document = gson.fromJson(projection.payload(), JsonObject.class);
                if (document == null) {
                    failure = new IllegalStateException("Collaboration Snapshot Is Empty");
                }
            } catch (RuntimeException | Error exception) {
                failure = exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
            }
        }
        if (failure == null && collaborationLifecycle != lifecycle) {
            document = null;
            failure = new IllegalStateException("Collaboration Snapshot Expired");
        }
        return new CollaborationDocumentSnapshot(this, lifecycle, projection.editVersion(), document, failure);
    }

    @Override
    public boolean applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                              Consumer<CollaborationDocumentApplyResult> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return scoreboardDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (scoreboard == null) {
                throw new IllegalStateException("Scoreboard Is Unavailable");
            }
            if (ReSyncCollaborationDocuments.to(document, ScoreboardDefinition.class) == null) {
                throw new IllegalArgumentException("Collaboration Document Is Invalid");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (scoreboardDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        scoreboardDraft.markMutation();
        applyingCollaboration = true;
        try {
            restoreCollaborationDocument(document);
        } finally {
            applyingCollaboration = false;
        }
    }

    @Override
    public void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        historyRebase.request(patches, copiedPatches -> snapshot -> {
            JsonObject rebased = gson.fromJson(gson.toJson(snapshot), JsonObject.class);
            if (rebased == null) {
                rebased = new JsonObject();
            }
            FlowWorkspaceDocument.apply(rebased, copiedPatches);
            return gson.fromJson(gson.toJson(rebased), JsonObject.class);
        });
    }

    private void restoreCollaborationDocument(JsonObject document) {
        ScoreboardDefinition incoming = ReSyncCollaborationDocuments.to(document, ScoreboardDefinition.class);
        ReSyncCollaborationDocuments.copy(scoreboard, incoming);
        buildInspectorPanel();
        refreshPreviewText();
    }

    private void captureHistory() {
        if (!applyingCollaboration) {
            scoreboardDraft.markMutation();
            history.capture();
        }
    }

    public ScoreboardDesignerScreen(ScoreboardDefinition scoreboard) {
        this(scoreboard, null, null);
    }

    public ScoreboardDesignerScreen(ScoreboardDefinition scoreboard, String serverId, Object parent) {
        this(scoreboard, serverId, parent, !(parent instanceof Screen));
    }

    public ScoreboardDesignerScreen(ScoreboardDefinition scoreboard, String serverId, Object parent, boolean forceSuperScreen) {
        this(scoreboard, serverId, parent, forceSuperScreen, false);
    }

    public ScoreboardDesignerScreen(ScoreboardDefinition scoreboard, String serverId, Object parent, boolean forceSuperScreen, boolean animateTopHeader) {
        this.scoreboard = scoreboard;
        this.serverId = serverId;
        this.parent = parent;
        this.forceSuperScreen = forceSuperScreen;
        this.animateTopHeader = animateTopHeader;
        this.autoResizeContainers = false;
        ensureDefaults();
        this.scoreboardDraft = new VersionedEditorDraft<>(this.scoreboard, FlowSerializer::serializeScoreboard, FlowSerializer::deserializeScoreboard,
            this::rebindScoreboard, this::failSnapshot, () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        this.historyRebase = new HistoryRebaseCoordinator<>(history,
            () -> serverId + ":" + (scoreboard != null ? scoreboard.getId() : ""), () -> collaborationLifecycle,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
    }

    public String getDesktopAppId() {
        return "scoreboard-designer";
    }

    public String getDesktopAppTitle() {
        return "Scoreboard Designer";
    }

    public String getDesktopAppIconPath() {
        return "change.png";
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.SINGLETON;
    }

    @Override
    public boolean shouldForceSuperScreen() {
        return desktopMode && forceSuperScreen;
    }

    @Override
    public void init() {
        super.init();
        closingRequested = false;
        closeCompleted = false;
        studioCloseNotified = false;
        buildHeader();
        if (animateTopHeader) {
            startTopHeaderOpeningAnimation();
        }
        buildInspectorPanel();
        refreshPreviewText();
    }

    @Override
    public void close() {
        requestClose();
    }

    private void requestClose() {
        if (closeCompleted) {
            return;
        }
        if (desktopMode && isDesktopWindow()) {
            var overlay = ScreenManager.getInstance().getDesktopWindowsOverlay();
            if (overlay != null) {
                overlay.requestCloseWindowForScreen(this);
                return;
            }
        }
        if (!closingRequested) {
            closingRequested = true;
            if (animateTopHeader) {
                startTopHeaderClosingAnimation();
            }
            notifyStudioCloseStarted();
            if (inspectorPanel != null) {
                inspectorPanel.hide();
            }
        }
        updateCloseAnimation();
    }

    private void updateCloseAnimation() {
        if (!closingRequested || closeCompleted) {
            return;
        }
        if ((inspectorPanel == null || inspectorPanel.getAnimatedWidth() <= 1f) && (!animateTopHeader || isTopHeaderAnimationFinished())) {
            finishClose();
        }
    }

    private void notifyStudioCloseStarted() {
        if (studioCloseHandler != null && !studioCloseNotified) {
            studioCloseNotified = true;
            studioCloseHandler.run();
        }
    }

    private void finishClose() {
        if (closeCompleted) {
            return;
        }
        closeCompleted = true;
        collaborationLifecycle++;
        historyRebase.close();
        scoreboardDraft.close();
        if (studioCloseHandler != null) {
            if (!studioCloseNotified) {
                studioCloseNotified = true;
                studioCloseHandler.run();
            }
            return;
        }
        super.close();
        if (parent != null) {
            if (RemotelyClient.INSTANCE != null && RemotelyClient.INSTANCE.getHost() != null) {
                RemotelyClient.INSTANCE.getHost().openParentScreen(this, parent);
            } else if (parent instanceof Screen screen) {
                ScreenManager.getInstance().setScreen(screen);
            }
        }
    }

    @Override
    public void removed() {
        collaborationLifecycle++;
        historyRebase.close();
        scoreboardDraft.close();
        super.removed();
    }

    @Override
    public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
        super.renderHandler(context, mouseX, mouseY, delta);
        if (inspectorStudioPanel != null && inspectorPanel != null && (inspectorPanel.isVisible() || inspectorPanel.getAnimatedWidth() > 1f)) {
            renderStudioPanel(inspectorStudioPanel, context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void setStudioCloseHandler(Runnable closeHandler) {
        this.studioCloseHandler = closeHandler;
    }

    @Override
    public boolean isStudioCloseAnimationFinished() {
        return closeCompleted;
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        historyRebase.drain();
        scoreboardDraft.drain();
        updateTopHeaderAnimation();
        updateLayout();
        updateCloseAnimation();
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(IDrawContext context, int mouseX, int mouseY, float delta) {
        if (!forceSuperScreen) {
            super.renderBackground(context, mouseX, mouseY, delta);
        }
        renderPreview(context);
    }


    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (inspectorPanel != null && inspectorPanel.mouseClicked(event.retarget(inspectorPanel, event.x(), event.y()))) {
            return true;
        }
        return super.mouseClicked(event);
    }


    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (inspectorPanel != null && inspectorPanel.mouseReleased(event.retarget(inspectorPanel, event.x(), event.y()))) {
            return true;
        }
        return super.mouseReleased(event);
    }


    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (inspectorPanel != null && inspectorPanel.mouseDragged(event.retarget(inspectorPanel, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
            return true;
        }
        return super.mouseDragged(event);
    }


    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (inspectorPanel != null && inspectorPanel.mouseScrolled(event.retarget(inspectorPanel, event.x(), event.y()))) {
            return true;
        }
        return super.mouseScrolled(event);
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleStudioSaveShortcut(event)) {
            return true;
        }
        if (inspectorPanel != null && inspectorPanel.keyPressed(event.retarget(inspectorPanel))) {
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        if (event.key() == ReKey.ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (inspectorPanel != null && inspectorPanel.textInput(event.retarget(inspectorPanel))) {
            return true;
        }
        return super.textInput(event);
    }


    private void buildHeader() {
        header().reset();
        if (shouldShowBackButton()) {
            header().addRight("close.png", this::close, "Back");
        }
        header().addRight("save.png", this::saveScoreboard, "Save Scoreboard");
        header().build();
    }

    private boolean shouldShowBackButton() {
        return !desktopMode || shouldForceSuperScreen();
    }

    private void buildInspectorPanel() {
        if (inspectorPanel == null) {
            inspectorStudioPanel = rightStudioPanel("scoreboard_inspector")
                .show();
            inspectorPanel = inspectorStudioPanel.sidePanel();
        }
        Container container = inspectorPanel.container();
        inspectorStudioPanel.padding(panelState.padding());
        int rowWidth = inspectorStudioPanel.rowWidth();
        if (titleInput != null && objectiveInput != null && linesInput != null) {
            if (!titleInput.isFocused()) {
                titleInput.setText(scoreboard.getTitle() != null ? scoreboard.getTitle() : "");
            }
            if (!objectiveInput.isFocused()) {
                objectiveInput.setText(scoreboard.getObjectiveId() != null ? scoreboard.getObjectiveId() : "");
            }
            if (!linesInput.isFocused()) {
                linesInput.setText(String.join("\n", scoreboard.getLines()));
            }
            return;
        }

        titleInput = new TextInputWidget.Builder()
            .text(scoreboard.getTitle() != null ? scoreboard.getTitle() : "")
            .placeholder("Title")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(this::updateTitle)
            .build();
        ReSyncStudioPanelState.disableEntrance(titleInput);
        container.addWidget(panelState.row("Title", titleInput, rowWidth, scoreboardPanelDescription("Title")));

        objectiveInput = new TextInputWidget.Builder()
            .text(scoreboard.getObjectiveId() != null ? scoreboard.getObjectiveId() : "")
            .placeholder("Objective ID")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(this::updateObjective)
            .build();
        ReSyncStudioPanelState.disableEntrance(objectiveInput);
        container.addWidget(panelState.row("Objective", objectiveInput, rowWidth, scoreboardPanelDescription("Objective")));

        linesInput = new CodeEditorWidget(0, 0, rowWidth, 220);
        linesInput.setText(String.join("\n", scoreboard.getLines()));
        linesInput.onChange = t -> updateLines(linesInput.getText());
        container.addWidget(panelState.codeRow("Lines", linesInput, rowWidth, 238, scoreboardPanelDescription("Lines")));
    }

    private String scoreboardPanelDescription(String label) {
        return switch (label) {
            case "Title" -> "Sidebar display title.\nMinecraft renders this as the scoreboard objective display name.";
            case "Objective" -> "Internal scoreboard objective id.\nKeep it stable because live updates target this id.";
            case "Lines" -> "Sidebar lines under the title.\nMinecraft shows up to 15 visible rows.\nFirst line appears at the top.";
            default -> "";
        };
    }

    private void updateLayout() {
        if (inspectorPanel != null) {
            if (inspectorStudioPanel != null) {
                inspectorStudioPanel.layout();
            }
        }
    }

    private void renderPreview(IDrawContext context) {
        List<String> lines = previewLines;
        int maxLines = Math.min(15, lines.size());
        String titleText = previewTitle.isEmpty() ? formatPreviewText(scoreboard.getId()) : previewTitle;

        int titleWidth = textWidth(titleText);
        int maxRowWidth = 0;
        for (int i = 0; i < maxLines; i++) {
            String lineText = lines.get(i) != null ? lines.get(i) : "";
            int scoreWidth = textWidth(String.valueOf(maxLines - i));
            maxRowWidth = Math.max(maxRowWidth, textWidth(lineText) + scoreWidth + 8);
        }

        int panelWidth = Math.max(90, Math.max(titleWidth, maxRowWidth) + 10);
        int areaX = getPreviewAreaX();
        int areaTop = getPreviewAreaTop();
        int areaWidth = getPreviewAreaWidth();
        int areaHeight = getPreviewAreaHeight();
        panelWidth = Math.clamp(panelWidth, 90, areaWidth);
        int rowHeight = 11;
        int panelHeight = (maxLines + 1) * rowHeight + 6;
        panelHeight = Math.clamp(panelHeight, 10, areaHeight);
        int x = areaX + Math.max(0, (areaWidth - panelWidth) / 2);
        int y = areaTop + Math.max(0, (areaHeight - panelHeight) / 2);

        context.fill(x, y, x + panelWidth, y + rowHeight + 2, PREVIEW_ROW_BG);
        int titleTextMaxWidth = Math.max(0, panelWidth - 6);
        String titleDraw = fitLineToWidth(titleText, titleTextMaxWidth);
        int titleX = x + (panelWidth - textWidth(titleDraw)) / 2;
        context.drawText(titleDraw, titleX, y + 2, TITLE_COLOR, true);

        for (int i = 0; i < maxLines; i++) {
            int rowY = y + rowHeight + 2 + i * rowHeight;
            context.fill(x, rowY, x + panelWidth, rowY + rowHeight, PREVIEW_ROW_BG);
            String line = lines.get(i) != null ? lines.get(i) : "";
            String score = String.valueOf(maxLines - i);
            int scoreX = x + panelWidth - textWidth(score) - 3;
            int lineX = x + 3;
            int lineMaxWidth = Math.max(0, scoreX - lineX - 2);
            context.drawText(fitLineToWidth(line, lineMaxWidth), lineX, rowY + 1, TEXT_COLOR, true);
            context.drawText(score, scoreX, rowY + 1, SCORE_COLOR, true);
        }
    }

    private String fitLineToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        String value = text != null ? text : "";
        if (textWidth(value) <= maxWidth) {
            return value;
        }
        int end = value.length();
        while (end > 0 && textWidth(value.substring(0, end)) > maxWidth) {
            end--;
        }
        return value.substring(0, end);
    }

    private int getPreviewAreaX() {
        return PANEL_PADDING;
    }

    private int getPreviewAreaTop() {
        return header().headerSize + 5;
    }

    private int getPreviewAreaWidth() {
        int rightWidth = inspectorPanel != null ? (int) inspectorPanel.getAnimatedWidth() : 0;
        int availableWidth = width - rightWidth - PANEL_PADDING * 2;
        return Math.max(120, availableWidth);
    }

    private int getPreviewAreaHeight() {
        int contentTop = getPreviewAreaTop();
        return Math.max(120, height - contentTop - PANEL_PADDING);
    }

    private String formatPreviewText(String text) {
        return miniMessageToSection(text != null ? text : "").replace('&', '§');
    }

    private int textWidth(String text) {
        String clean = stripSectionCodes(text);
        if (RemotelyClient.tr != null) {
            return RemotelyClient.tr.getWidth(clean);
        }
        return clean.length() * 6;
    }

    private String stripSectionCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private String miniMessageToSection(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String withHex = replaceMiniHex(input);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < withHex.length(); i++) {
            char c = withHex.charAt(i);
            if (c != '<') {
                out.append(c);
                continue;
            }
            int end = withHex.indexOf('>', i);
            if (end <= i) {
                out.append(c);
                continue;
            }
            String tag = withHex.substring(i + 1, end).trim().toLowerCase();
            String mapped = mapMiniTag(tag);
            if (mapped != null) {
                out.append(mapped);
            }
            i = end;
        }
        return out.toString();
    }

    private String replaceMiniHex(String input) {
        Matcher matcher = MINI_HEX_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1).toUpperCase();
            String replacement = "§x§" + hex.charAt(0) + "§" + hex.charAt(1) + "§" + hex.charAt(2)
                + "§" + hex.charAt(3) + "§" + hex.charAt(4) + "§" + hex.charAt(5);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String mapMiniTag(String tag) {
        return switch (tag) {
            case "/black", "/dark_blue", "/dark_green", "/dark_aqua", "/dark_red", "/dark_purple", "/gold", "/gray", "/dark_gray",
                 "/blue", "/green", "/aqua", "/red", "/light_purple", "/yellow", "/white", "/bold", "/italic", "/underlined",
                 "/strikethrough", "/obfuscated", "/reset" -> "§r";
            case "black" -> "§0";
            case "dark_blue" -> "§1";
            case "dark_green" -> "§2";
            case "dark_aqua" -> "§3";
            case "dark_red" -> "§4";
            case "dark_purple" -> "§5";
            case "gold" -> "§6";
            case "gray" -> "§7";
            case "dark_gray" -> "§8";
            case "blue" -> "§9";
            case "green" -> "§a";
            case "aqua" -> "§b";
            case "red" -> "§c";
            case "light_purple" -> "§d";
            case "yellow" -> "§e";
            case "white" -> "§f";
            case "bold" -> "§l";
            case "italic" -> "§o";
            case "underlined" -> "§n";
            case "strikethrough" -> "§m";
            case "obfuscated" -> "§k";
            case "reset" -> "§r";
            default -> null;
        };
    }

    private void updateObjective(String value) {
        String source = value != null ? value : "";
        if (scoreboardDraft.defer(() -> updateObjective(source))) {
            return;
        }
        String normalized = value != null ? value.trim() : "";
        if (normalized.length() > 16) {
            normalized = normalized.substring(0, 16);
        }
        if (!Objects.equals(scoreboard.getObjectiveId(), normalized)) {
            captureHistory();
            scoreboard.setObjectiveId(normalized);
        }
        if (value != null && normalized.length() < value.trim().length()) {
            if (objectiveInput != null && !normalized.equals(objectiveInput.getText())) {
                objectiveInput.setText(normalized);
            }
        }
    }

    private void updateLines(String value) {
        String source = value != null ? value : "";
        if (scoreboardDraft.defer(() -> updateLines(source))) {
            return;
        }
        List<String> lines = new ArrayList<>();
        if (!source.isEmpty()) {
            String normalized = source.replace("\r", "");
            if (!normalized.isEmpty()) {
                Collections.addAll(lines, normalized.split("\n", -1));
            }
        }
        if (Objects.equals(scoreboard.getLines(), lines)) {
            return;
        }
        captureHistory();
        scoreboard.setLines(lines);
        refreshPreviewText();
    }

    private void updateTitle(String value) {
        String next = value != null ? value : "";
        if (scoreboardDraft.defer(() -> updateTitle(next))) {
            return;
        }
        if (Objects.equals(scoreboard.getTitle(), next)) {
            return;
        }
        captureHistory();
        scoreboard.setTitle(next);
        refreshPreviewText();
    }

    private void refreshPreviewText() {
        String rawTitle = scoreboard.getTitle() != null && !scoreboard.getTitle().isEmpty() ? scoreboard.getTitle() : scoreboard.getId();
        List<String> sourceLines = scoreboard.getLines() != null ? scoreboard.getLines() : List.of();
        String localTitle = formatPreviewText(rawTitle);
        List<String> localLines = new ArrayList<>();
        for (String line : sourceLines) {
            localLines.add(formatPreviewText(line));
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager == null || serverId == null) {
            previewTitle = localTitle;
            previewLines = localLines;
            return;
        }
        if (previewTitle.isEmpty()) {
            previewTitle = localTitle;
        }
        if (previewLines.isEmpty()) {
            previewLines = localLines;
        }
        int requestRevision = ++previewRequestRevision;
        flowManager.resolvePlaceholderPreview(serverId, rawTitle, rendered -> {
            if (requestRevision == previewRequestRevision && rendered != null) {
                previewTitle = formatPreviewText(rendered);
            }
        });
        String joined = String.join("\u0001", sourceLines);
        int expectedLines = sourceLines.size();
        flowManager.resolvePlaceholderPreview(serverId, joined, rendered -> applyResolvedPreviewLines(rendered, requestRevision, expectedLines));
    }

    private void applyResolvedPreviewLines(String joined, int requestRevision, int expectedLines) {
        if (requestRevision != previewRequestRevision || joined == null) {
            return;
        }
        if (expectedLines <= 0) {
            previewLines = new ArrayList<>();
            return;
        }
        List<String> resolved = new ArrayList<>();
        for (String part : joined.split("\u0001", -1)) {
            resolved.add(formatPreviewText(part));
        }
        previewLines = resolved;
    }

    private void saveScoreboard() {
        FlowManager flowManager = FlowManager.getInstance();
        String id = scoreboard.getId();
        String title = scoreboard.getTitle() != null ? scoreboard.getTitle() : id;
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.SCOREBOARD, id, title);
        observeSave(ticket);
        if (flowManager != null && serverId != null) {
            if (!scoreboardDraft.capture(ReSyncResourceType.SCOREBOARD.typeId(), id, ticket,
                snapshot -> flowManager.saveScoreboard(serverId, snapshot.serialize(FlowSerializer::deserializeScoreboard), ticket))) {
                DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            }
        } else {
            DesignerSaveNotifications.failExact(ticket, "ReSync Offline");
        }
    }

    @Override
    public boolean requestStudioSave() {
        saveScoreboard();
        return true;
    }

    private void rebindScoreboard(ScoreboardDefinition previous, ScoreboardDefinition replacement) {
        ScoreboardDefinition currentScoreboard = scoreboard;
        String currentPreviewTitle = previewTitle;
        List<String> currentPreviewLines = previewLines;
        int currentPreviewRequestRevision = previewRequestRevision;
        try {
            scoreboard = replacement;
            buildInspectorPanel();
            refreshPreviewText();
        } catch (RuntimeException | Error exception) {
            scoreboard = currentScoreboard;
            previewTitle = currentPreviewTitle;
            previewLines = currentPreviewLines;
            previewRequestRevision = currentPreviewRequestRevision >= Integer.MAX_VALUE - 2
                ? 0 : currentPreviewRequestRevision + 2;
            throw exception;
        }
    }

    private void failSnapshot(VersionedEditorDraft.Failure failure) {
        if (failure.stage() == VersionedEditorDraft.Stage.REBASE) {
            new Notification("Save Refresh Failed", "Save Paused", Notification.Type.ERROR);
            return;
        }
        if (failure.request() instanceof DesignerSaveNotifications.SaveTicket ticket) {
            DesignerSaveNotifications.failExact(ticket, "Save Snapshot Failed");
        }
    }

    private void observeSave(DesignerSaveNotifications.SaveTicket ticket) {
        if (ticket == null) {
            return;
        }
        ticket.whenFinished((saved, current) -> ScreenManager.getInstance().execute(() -> {
            if (!saved || !current) {
                scoreboardDraft.discard(ticket);
                return;
            }
            FlowManager manager = FlowManager.getInstance();
            FlowManager.ResourceReadLease lease = manager != null
                ? manager.snapshotResource(serverId, ReSyncResourceType.SCOREBOARD.typeId(), ticket.id()) : null;
            if (lease == null || !scoreboardDraft.acknowledge(ticket, lease::materialize)) {
                scoreboardDraft.discard(ticket);
            }
        }));
    }

    private void ensureDefaults() {
        if (scoreboard.getTitle() == null || scoreboard.getTitle().isBlank()) {
            scoreboard.setTitle(scoreboard.getId() != null ? scoreboard.getId() : "Scoreboard");
        }
        if (scoreboard.getObjectiveId() == null || scoreboard.getObjectiveId().isBlank()) {
            scoreboard.setObjectiveId(scoreboard.getId() != null ? scoreboard.getId() : "scoreboard");
        }
        if (scoreboard.getDisplaySlot() == null || scoreboard.getDisplaySlot().isBlank()) {
            scoreboard.setDisplaySlot(ScoreboardDefinition.SLOT_SIDEBAR);
        }
        if (scoreboard.getLines() == null) {
            scoreboard.setLines(new ArrayList<>());
        }
    }
}
