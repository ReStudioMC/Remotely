package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.FlowWorkspaceDocument;
import redxax.oxy.remotely.flow.data.TabDefinition;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborationDocuments;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioResourceRenameAware;
import redxax.oxy.remotely.flow.ui.studio.StudioSaveProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
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

public class TabDesignerScreen extends StudioScreen implements DesktopWindowBehaviorProvider, StudioCloseHandledScreen, StudioResourceRenameAware, ReSyncCollaborativeView, StudioSaveProvider {
    private static final int PANEL_PADDING = 8;
    private static final int PREVIEW_BG = 0x7F101010;
    private static final int PREVIEW_TEXT = 0xFFFFFFFF;
    private static final Pattern MINI_HEX_PATTERN = Pattern.compile("<#([0-9a-fA-F]{6})>");

    private TabDefinition tab;
    private final VersionedEditorDraft<TabDefinition> tabDraft;
    private final String serverId;
    private final Object parent;
    private final boolean forceSuperScreen;
    private final boolean animateTopHeader;
    private final ReSyncStudioPanelState panelState = new ReSyncStudioPanelState();

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (tabDraft.defer(() -> resourceRenamed(type, oldId, newId))) {
            return;
        }
        tabDraft.markMutation();
        if (oldId.equals(tab.getId())) {
            ReSyncResourceType.TAB.applyRename(tab, newId);
        }
    }

    private StudioPanel inspectorStudioPanel;
    private SidePanel inspectorPanel;
    private CodeEditorWidget headerInput;
    private TextInputWidget entryFormatInput;
    private CodeEditorWidget footerInput;
    private String previewHeader = "";
    private String previewEntry = "%player%";
    private String previewFooter = "";
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
        return ReSyncCollaborationDocuments.from(tab);
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return tabDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return tabDraft.requestProjection(ReSyncResourceType.TAB.typeId(), ReSyncResourceType.TAB.extractId(tab),
            this::collaborationSource, projection -> completion.accept(collaborationSnapshot(lifecycle, projection)));
    }

    private TabDefinition collaborationSource() {
        TabDefinition source = new TabDefinition();
        source.setId(tab.getId());
        source.setEnabled(tab.isEnabled());
        source.setHeader(tab.getHeader());
        source.setEntryFormat(tab.getEntryFormat());
        source.setFooter(tab.getFooter());
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
        return tabDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (tab == null) {
                throw new IllegalStateException("Tab Is Unavailable");
            }
            if (ReSyncCollaborationDocuments.to(document, TabDefinition.class) == null) {
                throw new IllegalArgumentException("Collaboration Document Is Invalid");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (tabDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        tabDraft.markMutation();
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
        TabDefinition incoming = ReSyncCollaborationDocuments.to(document, TabDefinition.class);
        ReSyncCollaborationDocuments.copy(tab, incoming);
        buildInspectorPanel();
        refreshPreviewText();
    }

    private void captureHistory() {
        if (!applyingCollaboration) {
            tabDraft.markMutation();
            history.capture();
        }
    }

    public TabDesignerScreen(TabDefinition tab) {
        this(tab, null, null);
    }

    public TabDesignerScreen(TabDefinition tab, String serverId, Object parent) {
        this(tab, serverId, parent, !(parent instanceof Screen));
    }

    public TabDesignerScreen(TabDefinition tab, String serverId, Object parent, boolean forceSuperScreen) {
        this(tab, serverId, parent, forceSuperScreen, false);
    }

    public TabDesignerScreen(TabDefinition tab, String serverId, Object parent, boolean forceSuperScreen, boolean animateTopHeader) {
        this.tab = tab;
        this.serverId = serverId;
        this.parent = parent;
        this.forceSuperScreen = forceSuperScreen;
        this.animateTopHeader = animateTopHeader;
        this.autoResizeContainers = false;
        ensureDefaults();
        this.tabDraft = new VersionedEditorDraft<>(this.tab, FlowSerializer::serializeTab, FlowSerializer::deserializeTab,
            this::rebindTab, this::failSnapshot, () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        this.historyRebase = new HistoryRebaseCoordinator<>(history,
            () -> serverId + ":" + (tab != null ? tab.getId() : ""), () -> collaborationLifecycle,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
    }

    public String getDesktopAppId() {
        return "tab-designer";
    }

    public String getDesktopAppTitle() {
        return "Tab Designer";
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
        if (isDesktopWindow()) {
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
        tabDraft.close();
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
        tabDraft.close();
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
        tabDraft.drain();
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
        if (dispatchSidePanelMouseClicked(event)) {
            return true;
        }
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (dispatchSidePanelMouseDragged(event)) {
            return true;
        }
        return super.mouseDragged(event);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (dispatchSidePanelMouseReleased(event)) {
            return true;
        }
        return super.mouseReleased(event);
    }


    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleStudioSaveShortcut(event)) {
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

    private void buildHeader() {
        header().reset();
        if (shouldShowBackButton()) {
            header().addRight("close.png", this::close, "Back");
        }
        header().addRight("save.png", this::saveTab, "Save Tab");
        header().build();
    }

    private boolean shouldShowBackButton() {
        return !desktopMode || shouldForceSuperScreen();
    }

    private void buildInspectorPanel() {
        if (inspectorPanel == null) {
            inspectorStudioPanel = rightStudioPanel("tab_inspector")
                .show();
            inspectorPanel = inspectorStudioPanel.sidePanel();
        }
        Container container = inspectorPanel.container();
        inspectorStudioPanel.padding(panelState.padding());
        int rowWidth = inspectorStudioPanel.rowWidth();
        if (headerInput != null && entryFormatInput != null && footerInput != null) {
            if (!headerInput.isFocused()) {
                headerInput.setText(tab.getHeader());
            }
            if (!entryFormatInput.isFocused()) {
                entryFormatInput.setText(tab.getEntryFormat() != null ? tab.getEntryFormat() : "%player%");
            }
            if (!footerInput.isFocused()) {
                footerInput.setText(tab.getFooter());
            }
            return;
        }

        headerInput = new CodeEditorWidget(0, 0, rowWidth, 100);
        headerInput.setText(tab.getHeader());
        headerInput.onChange = t -> updateHeader(headerInput.getText());
        container.addWidget(panelState.codeRow("Header", headerInput, rowWidth, 118, tabPanelDescription("Header")));

        entryFormatInput = new TextInputWidget.Builder()
            .text(tab.getEntryFormat() != null ? tab.getEntryFormat() : "%player%")
            .placeholder("Entry")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(this::updateEntryFormat)
            .build();
        ReSyncStudioPanelState.disableEntrance(entryFormatInput);
        container.addWidget(panelState.row("Entry", entryFormatInput, rowWidth, tabPanelDescription("Entry")));

        footerInput = new CodeEditorWidget(0, 0, rowWidth, 100);
        footerInput.setText(tab.getFooter());
        footerInput.onChange = t -> updateFooter(footerInput.getText());
        container.addWidget(panelState.codeRow("Footer", footerInput, rowWidth, 118, tabPanelDescription("Footer")));
    }

    private String tabPanelDescription(String label) {
        return switch (label) {
            case "Header" -> "Text above the player list in the tab overlay.\nSupports multiple lines.";
            case "Entry" -> "Format for each player row.\n%player% is replaced in the preview.\nRuntime placeholders depend on the synced tab renderer.";
            case "Footer" -> "Text below the player list in the tab overlay.\nSupports multiple lines.";
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
        List<String> headerLines = splitPreviewLines(previewHeader);
        List<String> footerLines = splitPreviewLines(previewFooter);
        List<String> playerLines = new ArrayList<>();
        String format = previewEntry != null ? previewEntry : "%player%";
        String[] players = {"RedxAx", "Steve", "Alex", "Player"};
        for (String player : players) {
            playerLines.add(format.replace("%player%", player));
        }

        List<String> renderLines = new ArrayList<>(headerLines);
        if (!headerLines.isEmpty()) {
            renderLines.add("");
        }
        renderLines.addAll(playerLines);
        if (!footerLines.isEmpty()) {
            renderLines.add("");
        }
        renderLines.addAll(footerLines);

        int maxTextWidth = 0;
        for (String line : renderLines) {
            maxTextWidth = Math.max(maxTextWidth, textWidth(line));
        }
        int horizontalPadding = 1;
        int verticalPadding = 1;
        int lineHeight = 10;
        int areaX = getPreviewAreaX();
        int areaTop = getPreviewAreaTop();
        int areaWidth = getPreviewAreaWidth();
        int areaHeight = getPreviewAreaHeight();

        int panelWidth = Math.max(2, maxTextWidth + horizontalPadding * 2);
        int panelHeight = Math.max(2, verticalPadding * 2 + renderLines.size() * lineHeight);
        panelWidth = Math.clamp(panelWidth, 2, areaWidth);
        panelHeight = Math.clamp(panelHeight, 2, areaHeight);

        int x = areaX + Math.max(0, (areaWidth - panelWidth) / 2);
        int y = areaTop + Math.max(0, (areaHeight - panelHeight) / 2);
        context.fill(x, y, x + panelWidth, y + panelHeight, PREVIEW_BG);

        int contentMaxWidth = Math.max(0, panelWidth - horizontalPadding * 2);
        List<String> drawLines = new ArrayList<>(renderLines.size());
        int maxDrawWidth = 0;
        for (String line : renderLines) {
            String drawLine = fitLineToWidth(line, contentMaxWidth);
            drawLines.add(drawLine);
            maxDrawWidth = Math.max(maxDrawWidth, textWidth(drawLine));
        }
        int drawX = x + (panelWidth - maxDrawWidth) / 2;
        int cursor = y + verticalPadding;
        for (String drawLine : drawLines) {
            context.drawText(drawLine, drawX, cursor, PREVIEW_TEXT, true);
            cursor += lineHeight;
        }
    }

    private int getPreviewAreaX() {
        return PANEL_PADDING;
    }

    private int getPreviewAreaTop() {
        return header().headerSize + 5;
    }

    private int getPreviewAreaWidth() {
        int rightWidth = inspectorPanel != null ? inspectorPanel.layoutWidth(0) : 0;
        int availableWidth = width - rightWidth - PANEL_PADDING * 2;
        return Math.max(2, availableWidth);
    }

    private int getPreviewAreaHeight() {
        int contentTop = getPreviewAreaTop();
        return Math.max(120, height - contentTop - PANEL_PADDING);
    }

    private void refreshPreviewText() {
        int requestRevision = ++previewRequestRevision;
        previewHeader = formatPreviewText(tab.getHeader());
        previewEntry = formatPreviewText(tab.getEntryFormat() != null ? tab.getEntryFormat() : "%player%");
        previewFooter = formatPreviewText(tab.getFooter());
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager == null || serverId == null) {
            return;
        }
        flowManager.resolvePlaceholderPreview(serverId, tab.getHeader(), rendered -> {
            if (requestRevision == previewRequestRevision && rendered != null) {
                previewHeader = formatPreviewText(rendered);
            }
        });
        flowManager.resolvePlaceholderPreview(serverId, tab.getEntryFormat(), rendered -> {
            if (requestRevision == previewRequestRevision && rendered != null) {
                previewEntry = formatPreviewText(rendered);
            }
        });
        flowManager.resolvePlaceholderPreview(serverId, tab.getFooter(), rendered -> {
            if (requestRevision == previewRequestRevision && rendered != null) {
                previewFooter = formatPreviewText(rendered);
            }
        });
    }

    private void updateEntryFormat(String value) {
        String source = value != null ? value : "";
        if (tabDraft.defer(() -> updateEntryFormat(source))) {
            return;
        }
        String format = !source.isEmpty() ? source : "%player%";
        if (!format.contains("%player%")) {
            format = format + " %player%";
        }
        if (!Objects.equals(tab.getEntryFormat(), format)) {
            captureHistory();
            tab.setEntryFormat(format);
            refreshPreviewText();
        }
        if (value != null && !format.equals(value)) {
            if (entryFormatInput != null && !format.equals(entryFormatInput.getText())) {
                entryFormatInput.setText(format);
            }
        }
    }

    private String formatPreviewText(String text) {
        return miniMessageToSection(text != null ? text : "").replace('&', '§');
    }

    private List<String> splitPreviewLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String normalized = text.replace("\r", "");
        Collections.addAll(lines, normalized.split("\n", -1));
        return lines;
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

    private void saveTab() {
        FlowManager flowManager = FlowManager.getInstance();
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.TAB,
            tab.getId(), tab.getId());
        observeSave(ticket);
        if (flowManager != null && serverId != null) {
            String id = tab.getId();
            if (!tabDraft.capture(ReSyncResourceType.TAB.typeId(), id, ticket,
                snapshot -> flowManager.saveTab(serverId, snapshot.serialize(FlowSerializer::deserializeTab), ticket))) {
                DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            }
        } else {
            DesignerSaveNotifications.failExact(ticket, "ReSync Offline");
        }
    }

    private void updateHeader(String value) {
        String next = value != null ? value : "";
        if (tabDraft.defer(() -> updateHeader(next))) {
            return;
        }
        if (Objects.equals(tab.getHeader(), next)) {
            return;
        }
        captureHistory();
        tab.setHeader(next);
        refreshPreviewText();
    }

    private void updateFooter(String value) {
        String next = value != null ? value : "";
        if (tabDraft.defer(() -> updateFooter(next))) {
            return;
        }
        if (Objects.equals(tab.getFooter(), next)) {
            return;
        }
        captureHistory();
        tab.setFooter(next);
        refreshPreviewText();
    }

    @Override
    public boolean requestStudioSave() {
        saveTab();
        return true;
    }

    private void rebindTab(TabDefinition previous, TabDefinition replacement) {
        TabDefinition currentTab = tab;
        String currentPreviewHeader = previewHeader;
        String currentPreviewEntry = previewEntry;
        String currentPreviewFooter = previewFooter;
        int currentPreviewRequestRevision = previewRequestRevision;
        try {
            tab = replacement;
            buildInspectorPanel();
            refreshPreviewText();
        } catch (RuntimeException | Error exception) {
            tab = currentTab;
            previewHeader = currentPreviewHeader;
            previewEntry = currentPreviewEntry;
            previewFooter = currentPreviewFooter;
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
                tabDraft.discard(ticket);
                return;
            }
            FlowManager manager = FlowManager.getInstance();
            FlowManager.ResourceReadLease lease = manager != null
                ? manager.snapshotResource(serverId, ReSyncResourceType.TAB.typeId(), ticket.id()) : null;
            if (lease == null || !tabDraft.acknowledge(ticket, lease::materialize)) {
                tabDraft.discard(ticket);
            }
        }));
    }

    private void ensureDefaults() {
        if (tab.getId() == null || tab.getId().isBlank()) {
            tab.setId("main");
        }
        if (tab.getEntryFormat() == null || tab.getEntryFormat().isEmpty()) {
            tab.setEntryFormat("%player%");
        }
    }
}
