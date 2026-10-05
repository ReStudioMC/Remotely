package redxax.oxy.remotely.ui.server;

import restudio.rebase.api.git.data.GitBranch;
import restudio.rebase.api.git.data.GitCommit;
import restudio.rebase.api.git.data.GitFileStatus;
import restudio.rebase.api.git.data.GitStashEntry;
import restudio.rebase.api.git.data.GitStatus;
import restudio.rebase.backend.CapabilityIds;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.GitJobProvider;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import redxax.oxy.remotely.ui.server.ServerDevelopmentProvider.*;
import restudio.rebase.ui.screens.editor.FileEditorScreen;
import restudio.rebase.ui.screens.git.GitChangesTreeWidget;
import restudio.rebase.ui.screens.git.GitChangesTreeWidget.Capability;
import restudio.rebase.ui.screens.git.GitUnifiedDiffDocument;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rebase.ui.widgets.editor.TextAreaWidget;
import restudio.rebase.ui.widgets.editor.plugins.impl.DiffPlugin;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.layout.FreeLayout;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.IconMessage;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import restudio.rescreen.platform.Async;

final class ServerDevelopmentPanel {
    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
    private final ServerDevelopmentHost host;
    private final ServerDevelopmentProvider development;
    private final ServerUiCapabilityProvider capabilities;
    private final SidePanel panel;
    private final Container content;
    private final List<SquareButtonWidget> gitTools = new ArrayList<>();
    private final TextInputWidget search;
    private final SquareButtonWidget publish;
    private final SquareButtonWidget sync;
    private final SquareButtonWidget history;
    private final SquareButtonWidget settings;
    private final SquareButtonWidget gitActions;
    private final Map<String, FileChange> changes = new LinkedHashMap<>();
    private Object source;
    private Object developmentServer;
    private Workspace workspace;
    private Inspection inspection;
    private String query = "";
    private long nextRefreshAt;
    private long requestNonce;
    private boolean loading;
    private boolean inspectionLoading;
    private boolean requestedVisible;
    private boolean disposed;
    private GitJobProvider gitJobs;
    private RemotePath gitRoot;
    private DeveloperCapabilityProvider developer;
    private RemoteFileSystemProvider gitProvider;
    private GitChangesTreeWidget gitChangesTree;
    private boolean gitPreparing;
    private GitStatus gitStatus;
    private String gitStatusKey = "";
    private boolean publishInspectionReady;

    ServerDevelopmentPanel(ServerDevelopmentHost host, ServerUiCapabilityProvider capabilities) {
        this.host = host;
        this.development = host.developmentProvider();
        this.capabilities = capabilities == null ? ServerUiCapabilityProvider.unavailable() : capabilities;
        publish = tool("merge.png", "Publish Changes", this::showPublish, ThemeManager.getAccent("nice"));
        sync = tool("reload.png", "Sync From Server", this::confirmSync, ThemeManager.getAccent("calm"));
        history = tool("history.png", "Version History", this::showHistory, ThemeManager.getDefaultAccent());
        settings = tool("settings.png", "Development Settings", development.available() ? this::showSettings : this::showWorkspaceSelector, ThemeManager.getDefaultAccent());
        search = new TextInputWidget.Builder().placeholder("Search Changes").search(true).forcePlaceholder(false).animateElevation(false).entranceAnimation(false).onChange(value -> {
            query = normalize(value);
            if (gitChangesTree != null) gitChangesTree.setQuery(query);
        }).build();
        panel = host.createSidePanel("server-development").dismissible("Development").right().y(60).minWidth(190).maxWidth(Integer.MAX_VALUE).maxWidthRatio(100).width(310).height(Math.max(80, host.getHeight() - 80));
        panel.onUserVisibilityChanged(visible -> requestedVisible = visible);
        panel.container().layout(new FreeLayout()).backgroundDrawing(true).enableSelecting(false).setAnimateLayout(false);
        addGitToolbarButton(Capability.REFRESH, "reload.png", "Refresh", this::refresh);
        addGitToolbarButton(Capability.STAGE, "add.png", "Stage Selected", () -> {
            if (gitChangesTree != null) gitChangesTree.stageSelected();
        });
        addGitToolbarButton(Capability.UNSTAGE, "unmerge.png", "Unstage Selected", () -> {
            if (gitChangesTree != null) gitChangesTree.unstageSelected();
        });
        addGitToolbarButton(Capability.COMMIT, "checkmark.png", "Commit Selected", () -> showGitCommit(gitChangesTree == null ? List.of() : gitChangesTree.selectedChanges()));
        addGitToolbarButton(Capability.STASH, "save.png", "Stash", () -> runGitOperation(gitJobs == null ? null : gitJobs.stash(), "Stashed", "Stash Failed"));
        gitActions = addGitToolbarButton("manager.png", "Git Actions", this::showGitActions);
        content = new Container("server-development-content", 0, 0, 310, 120);
        content.layout(new ManagedLayout()).columns(1).padding(2).verticalSpacing(1).scrolling(true).backgroundDrawing(false).enableSelecting(true).enableDoubleClick(false).setAnimateLayout(false);
        content.setOnSelectionChanged(this::selectionChanged);
        panel.addWidget(search, settings, history, sync, publish, content);
        gitTools.forEach(panel::addWidget);
        panel.hide();
    }

    void open(Object candidate) {
        if (candidate == null || disposed) return;
        if (!development.available()) {
            openBrowserWorkspace(candidate);
            return;
        }
        Workspace existing = twinForDevelopmentServer(candidate);
        if (existing != null) {
            if (existing.currentState != null && !existing.currentState.isBlank()) {
                new Notification("Preparing Development Server", existing.name, Notification.Type.INFO);
                return;
            }
            Object existingSource = sourceFor(existing);
            if (existingSource != null) openWorkspace(existingSource, existing);
            return;
        }
        source = candidate;
        workspace = development.workspaceForSource(candidate);
        if (workspace == null && development.isPreparing(candidate)) {
            new Notification("Preparing Development Server", host.developmentInstanceName(candidate), Notification.Type.INFO);
            return;
        }
        if (workspace != null && workspace.currentState != null && !workspace.currentState.isBlank()) {
            new Notification("Preparing Development Server", workspace.name, Notification.Type.INFO);
            return;
        }
        if (workspace == null) {
            showSetup();
            return;
        }
        openWorkspace(candidate, workspace);
    }

    void toggle(Object candidate) {
        if (panel.isVisible() && sameInstance(candidate, source)) hidePanel();
        else if (sameInstance(candidate, source) && workspace != null && host.isActiveDevelopment(candidate)) {
            showPanel();
            refresh();
        } else open(candidate);
    }

    void activeServerChanged(Object active) {
        if (disposed || active == null) return;
        if (!development.available()) {
            if (sameInstance(active, source) && panel.isVisible()) refresh();
            else hideTemporarily();
            return;
        }
        Workspace activeWorkspace = twinForDevelopmentServer(active);
        if (activeWorkspace == null) activeWorkspace = development.workspaceForSource(active);
        if (activeWorkspace != null && sameInstance(active, source) && sameInstance(active, sourceFor(activeWorkspace))) {
            source = active;
            workspace = activeWorkspace;
            if (panel.isVisible()) refresh();
            return;
        }
        hideTemporarily();
    }

    void developmentModeChanged(Object remote, Object local, boolean localActive) {
        if (disposed || !development.available() || remote == null || local == null) return;
        Workspace activeWorkspace = development.workspaceForSource(remote);
        if (activeWorkspace == null) return;
        source = remote;
        workspace = activeWorkspace;
        developmentServer = local;
        initializeGitWorkspace();
        if (requestedVisible) {
            if (!panel.isVisible()) panel.toggle();
            layout();
            refresh();
        }
    }

    boolean isRequestedVisible() {
        return requestedVisible;
    }

    void restoreVisibility(boolean visible) {
        if (visible) showPanel();
        else {
            requestedVisible = false;
            hideTemporarily();
        }
    }

    void layout() {
        panel.y(60).height(Math.max(80, host.getHeight() - 80));
        panel.updateContainerBounds();
        Container shell = panel.container();
        int x = shell.getX();
        int y = shell.getY();
        int width = Math.max(0, shell.getWidth());
        int height = Math.max(0, shell.getHeight());
        int toolX = x + width - 4;
        for (SquareButtonWidget tool : List.of(publish, sync, history, settings)) {
            toolX -= 16;
            tool.setPosition(toolX, y + 4);
            tool.setSize(16, 16);
            toolX -= 3;
        }
        search.setPosition(x + 4, y + 4);
        search.setSize(Math.max(50, toolX - x - 4), 16);
        int gitToolX = x + 4;
        for (SquareButtonWidget widget : gitTools) {
            widget.setPosition(gitToolX, y + 24);
            widget.setSize(18, 18);
            gitToolX += 20;
        }
        content.setPosition(x + 2, y + 44);
        content.setSize(Math.max(0, width - 4), Math.max(0, height - 46));
        content.updateWidgetPositions();
    }

    void tick() {
        if (!panel.isVisible() || loading || disposed || !content.getSelectedWidgets().isEmpty() || System.currentTimeMillis() < nextRefreshAt) return;
        refresh();
    }

    void dispose() {
        disposed = true;
        requestNonce++;
    }

    private void openWorkspace(Object target, Workspace targetWorkspace) {
        if (!development.available()) return;
        Object local = development.developmentServer(targetWorkspace);
        if (local == null) {
            if (targetWorkspace.currentState != null && !targetWorkspace.currentState.isBlank()) {
                new Notification("Preparing Development Server", targetWorkspace.name, Notification.Type.INFO);
                return;
            }
            new Notification("Development Server Missing", targetWorkspace.name, Notification.Type.ERROR);
            return;
        }
        source = target;
        workspace = targetWorkspace;
        developmentServer = local;
        initializeGitWorkspace();
        host.openDevelopmentTab(target, local);
        showPanel();
        refresh();
    }

    private void openBrowserWorkspace(Object target) {
        source = target;
        developer = capabilities.developer(target);
        long nonce = ++requestNonce;
        DeveloperCapabilityProvider selected = developer;
        selected.workspace().devices().whenComplete((devices, error) -> ScreenManager.getInstance().execute(() -> {
            if (disposed || nonce != requestNonce || !sameInstance(source, target)) return;
            if (error != null) {
                new Notification("Devices Failed", message(error), Notification.Type.ERROR);
                return;
            }
            var descriptor = selected.workspace().capabilities().get(CapabilityIds.WORKSPACE);
            if (descriptor == null || !descriptor.available()) {
                new Notification("Development Is Unavailable", descriptor == null ? "No Developer Workspace" : descriptor.detail(), Notification.Type.INFO);
                return;
            }
            selected.workspace().current().whenComplete((binding, currentError) -> ScreenManager.getInstance().execute(() -> {
                if (disposed || nonce != requestNonce || !sameInstance(source, target)) return;
                if (currentError != null) {
                    new Notification("Workspace Failed", message(currentError), Notification.Type.ERROR);
                } else if (binding == null) {
                    showWorkspaceSelector();
                } else {
                    applyBrowserBinding(target, binding);
                }
            }));
        }));
    }

    private void applyBrowserBinding(Object target, DeveloperCapabilityProvider.Workspace.Binding binding) {
        Workspace selected = new Workspace();
        selected.id = binding.id();
        selected.name = binding.root().name();
        selected.sourceInstanceId = host.developmentInstanceId(target);
        selected.sourcePath = capabilities.serverDataRoot(target);
        selected.workspacePath = binding.root().path().asString();
        selected.gitEnabled = true;
        source = target;
        workspace = selected;
        developmentServer = target;
        publish.setActive(false);
        sync.setActive(false);
        history.setActive(false);
        publish.setHint("Publishing Requires A Desktop Workspace");
        sync.setHint("Syncing Requires A Desktop Workspace");
        history.setHint("History Requires A Desktop Workspace");
        initializeGitWorkspace();
        showPanel();
        refresh();
    }

    private void showWorkspaceSelector() {
        if (source == null) return;
        if (developer == null) developer = capabilities.developer(source);
        developer.workspace().devices().whenComplete((devices, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Devices Failed", message(error), Notification.Type.ERROR);
                return;
            }
            List<DeveloperCapabilityProvider.Workspace.Device> available = devices == null ? List.of() : devices.stream().filter(DeveloperCapabilityProvider.Workspace.Device::online).toList();
            ItemSelectorWidget.Builder builder = gitSelector("Developer Devices", "No Online Devices", available.size());
            available.forEach(device -> builder.addItem(device.name(), "Online", device.name(), () -> showRootSelector(device)));
            showGitSelector(builder);
        }));
    }

    private void showRootSelector(DeveloperCapabilityProvider.Workspace.Device device) {
        developer.workspace().roots(device.id()).whenComplete((roots, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Folders Failed", message(error), Notification.Type.ERROR);
                return;
            }
            List<DeveloperCapabilityProvider.Workspace.Root> available = roots == null ? List.of() : roots.stream().filter(DeveloperCapabilityProvider.Workspace.Root::read).toList();
            ItemSelectorWidget.Builder builder = gitSelector(device.name(), "No Developer Folders", available.size());
            available.forEach(root -> builder.addItem(root.name(), root.path().asString(), root.name() + " " + root.path().asString(), () -> bindWorkspace(device, root)));
            showGitSelector(builder);
        }));
    }

    private void bindWorkspace(DeveloperCapabilityProvider.Workspace.Device device, DeveloperCapabilityProvider.Workspace.Root root) {
        Object target = source;
        developer.workspace().bind(device.id(), root.id()).whenComplete((binding, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) new Notification("Workspace Failed", message(error), Notification.Type.ERROR);
            else if (target != null && binding != null && sameInstance(source, target)) applyBrowserBinding(target, binding);
        }));
    }

    private void showPanel() {
        requestedVisible = true;
        if (!panel.isVisible()) panel.toggle();
        layout();
    }

    private void hidePanel() {
        requestedVisible = false;
        hideTemporarily();
    }

    private void hideTemporarily() {
        if (!panel.isVisible()) return;
        panel.toggle();
    }

    private void refresh() {
        if (source == null || workspace == null || gitPreparing || disposed) return;
        if (loading) return;
        long nonce = ++requestNonce;
        loading = true;
        nextRefreshAt = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
        showGitLoading();
        Async<GitLoad> gitLoad = gitJobs == null
                ? Async.completed(new GitLoad(null, new IllegalStateException("Git Repository Missing")))
                : gitJobs.refreshIndex().thenCompose(ignored -> gitJobs.getStatus()).handle(GitLoad::new);
        gitLoad.whenComplete((result, error) -> ScreenManager.getInstance().execute(() -> {
            if (disposed || nonce != requestNonce) return;
            loading = false;
            if (error != null) {
                publish.setActive(false);
                publish.setHint("Could Not Check Changes");
                showGitFailure(error);
                return;
            }
            applyGitLoad(result);
        }));
    }

    private void applyInspection() {
        changes.clear();
        if (inspection != null && inspection.visibleChanges() != null) {
            inspection.visibleChanges().stream().filter(change -> isVisibleGitPath(change.relativePath())).forEach(change -> changes.put(normalizePath(change.relativePath()), change));
        }
        boolean serverChanged = inspection != null && inspection.remoteDrift() != null && !inspection.remoteDrift().isEmpty();
        sync.setHint(serverChanged ? "Sync Server Changes" : "Sync From Server");
        updatePublishState();
        layout();
    }

    private void initializeGitWorkspace() {
        if (workspace == null || developmentServer == null || workspace.workspacePath == null || workspace.workspacePath.isBlank()) return;
        RemotePath root = RemotePath.of(workspace.workspacePath);
        if (Objects.equals(gitRoot, root) && gitChangesTree != null) return;
        gitRoot = root;
        developer = capabilities.developer(developmentServer);
        gitJobs = developer.git(root);
        gitProvider = developer.logicalFilesystem();
        gitChangesTree = new GitChangesTreeWidget(host.screen(), content, gitProvider, this::openGitFile, this::openGitDiff, this::refresh, this::showGitCommit,
                this::extendGitContextMenu, this::gitCategory, gitCapabilities());
        updateGitTools();
        gitChangesTree.setQuery(query);
        gitPreparing = true;
        content.clearWidgets();
        content.addWidget(new IconMessage(0, 0, Math.max(120, content.getWidth()), 72, "Preparing Git History", "git.png"));
        content.updateWidgetPositions();
        if (!development.available()) {
            gitPreparing = false;
            refresh();
            return;
        }
        development.prepareGitWorkspace(source, workspace.id).whenComplete((repaired, error) -> ScreenManager.getInstance().execute(() -> {
            gitPreparing = false;
            if (error != null) {
                showGitFailure(error);
                new Notification("Git Setup Failed", message(error), Notification.Type.ERROR);
                return;
            }
            if (Boolean.TRUE.equals(repaired)) new Notification("Git History Ready", workspace.name, Notification.Type.SUCCESS);
            if (panel.isVisible()) refresh();
        }));
    }

    private void showGitLoading() {
        if (gitChangesTree == null || !gitChangesTree.repositories().isEmpty()) return;
        content.clearWidgets();
        content.addWidget(new IconMessage(0, 0, Math.max(120, content.getWidth()), 72, "Loading Git", "git.png"));
        content.updateWidgetPositions();
    }

    private void applyGitLoad(GitLoad load) {
        if (gitChangesTree == null || load == null || load.error() != null || load.status() == null) {
            showGitFailure(load == null ? null : load.error());
            return;
        }
        gitStatus = load.status();
        String statusKey = buildGitStatusKey(gitStatus);
        if (!Objects.equals(gitStatusKey, statusKey)) {
            gitStatusKey = statusKey;
            publishInspectionReady = false;
        }
        List<GitFileStatus> visibleFiles = gitStatus.files().stream().filter(file -> isVisibleGitPath(file.path())).toList();
        GitStatus visibleStatus = new GitStatus(gitStatus.branch(), gitStatus.upstream(), gitStatus.ahead(), gitStatus.behind(), visibleFiles);
        gitChangesTree.setRepositories(List.of(new GitChangesTreeWidget.RepositoryChanges(workspace.name, gitRoot, gitJobs, visibleStatus)));
        gitChangesTree.setQuery(query);
        updatePublishState();
        layout();
    }

    private void showGitFailure(Throwable error) {
        content.clearWidgets();
        content.addWidget(new IconMessage(0, 0, Math.max(120, content.getWidth()), 72, "Git Status Failed", "git.png"));
        content.updateWidgetPositions();
    }

    private void openGitFile(RemotePath path) {
        if (path == null || workspace == null || developmentServer == null || developer == null) return;
        RemotePath root = gitRoot == null ? path.parent() : gitRoot;
        ScreenManager.getInstance().setScreen(new FileEditorScreen(host.screen(), developmentServer, developer, root, path,
                RemotePath.of(host.developmentConfigPath())));
    }

    private void openGitDiff(GitChangesTreeWidget.RepositoryChanges repository, GitFileStatus file) {
        FileChange twinChange = changes.get(normalizePath(file.path()));
        if (twinChange != null) {
            preview(twinChange);
            return;
        }
        RemotePath path = repository.root().resolve(file.path()).normalize();
        RemotePath remotePath = path;
        boolean untracked = file.workTreeStatus() == GitFileStatus.Status.UNTRACKED || file.indexStatus() == GitFileStatus.Status.UNTRACKED;
        boolean added = file.workTreeStatus() == GitFileStatus.Status.ADDED || file.indexStatus() == GitFileStatus.Status.ADDED;
        Async<GitUnifiedDiffDocument> document = untracked
                ? gitProvider.read(remotePath).thenApply(content -> GitUnifiedDiffDocument.addedFile(file.path(), content))
                : repository.api().diff(file.path()).thenCompose(diff -> diff.isBlank() && added
                        ? gitProvider.read(remotePath).thenApply(content -> GitUnifiedDiffDocument.addedFile(file.path(), content))
                        : Async.completed(GitUnifiedDiffDocument.parse(diff)));
        document.whenComplete((diff, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Diff Failed", message(error), Notification.Type.ERROR);
                return;
            }
            showDiffEditor(path.fileName().isBlank() ? file.path() : path.fileName(), path, diff, null);
        }));
    }

    private void showDiffEditor(String title, RemotePath path, GitUnifiedDiffDocument document, Runnable restore) {
        int popupWidth = Math.max(420, host.getWidth() - 24);
        int popupHeight = Math.max(280, host.getHeight() - 40);
        CodeEditorWidget editor = new CodeEditorWidget(0, 0, popupWidth - 16, popupHeight - 64);
        editor.setAnimateLayout(false);
        editor.setReadOnly(true);
        editor.setMonospace(true);
        editor.setWordWrap(false);
        editor.setLanguage(editorLanguage(path));
        editor.setText(document.text().isBlank() ? "No Changes" : document.text());
        if (!document.text().isBlank()) editor.registerPlugin(new DiffPlugin(document.lineTypes()));
        PopupWidget.Builder builder = new PopupWidget.Builder(title).size(popupWidth, popupHeight).setResizable(true);
        builder.addRow(new PopupWidget.PopupRow.Builder("", row(document.additions() + " Added", document.deletions() + " Removed", ThemeManager.getDefaultAccent(), 22)).build());
        builder.addRow(new PopupWidget.PopupRow.Builder("", editor).minHeight(popupHeight - 64).build());
        if (restore != null) builder.addTitleAction("Restore File", restore, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget popup = builder.build();
        host.addDrawableChild(popup);
        popup.show();
    }

    private String editorLanguage(RemotePath path) {
        if (path == null || path.fileName().isBlank()) return "plain";
        String name = path.fileName();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "plain" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void preview(FileChange change) {
        if (workspace == null || change == null) return;
        Object target = source;
        Workspace targetWorkspace = workspace;
        development.buildChangeDetails(targetWorkspace.id, change.relativePath()).whenComplete((details, error) -> ScreenManager.getInstance().execute(() -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            if (error != null) {
                new Notification("Could Not Open Changes", message(error), Notification.Type.ERROR);
                return;
            }
            showPreview(target, targetWorkspace, change, details);
        }));
    }

    private void showPreview(Object target, Workspace targetWorkspace, FileChange change, ChangePreview details) {
            int popupWidth = Math.max(380, host.getWidth() - 24);
            int popupHeight = Math.max(260, host.getHeight() - 40);
            String title = RemotePath.of(change.relativePath()).fileName();
            PopupWidget.Builder builder = new PopupWidget.Builder(title);
            String state = changeLabel(details.type());
            if (details.text()) {
                showDiffEditor(title, RemotePath.of(change.relativePath()), GitUnifiedDiffDocument.parse(details.diff()), () -> confirmRestore(target, targetWorkspace, change));
                return;
            } else {
                builder.width(420).setResizable(false);
                builder.addRow(new PopupWidget.PopupRow.Builder("", row(state, "Binary Or Large File", ThemeManager.getDefaultAccent())).build());
                builder.addRow(new PopupWidget.PopupRow.Builder("Server File", row(formatSize(details.baselineSize()), "Published Version", ThemeManager.getDefaultAccent(), 22)).build());
                builder.addRow(new PopupWidget.PopupRow.Builder("Development File", row(formatSize(details.workspaceSize()), "Local Version", ThemeManager.getDefaultAccent(), 22)).build());
            }
            builder.addTitleAction("Restore File", () -> confirmRestore(target, targetWorkspace, change), PopupWidget.TitleActionRole.DESTRUCTIVE);
            PopupWidget popup = builder.build();
            host.addDrawableChild(popup);
            popup.show();
    }

    private void selectionChanged(List<AnimatedWidget> selected) {
        updatePublishState();
    }

    private void updatePublishState() {
        if (!development.available()) {
            publish.setActive(false);
            publish.setHint("Publishing Requires A Desktop Workspace");
            return;
        }
        boolean remoteChanges = inspection != null && inspection.remoteDrift() != null && !inspection.remoteDrift().isEmpty();
        List<String> selected = selectedGitPaths();
        boolean selectionActive = hasGitSelection();
        int count = selectionActive ? selected.size() : publishInspectionReady ? changes.size() : gitChangePaths().size();
        publish.setActive(count > 0 && !remoteChanges);
        publish.setHint(remoteChanges ? "Sync Before Publishing" : count == 0 ? "No Changes To Publish" : selectionActive ? "Publish " + count + (count == 1 ? " Selected Change" : " Selected Changes") : "Publish All Changes");
        publish.setAccent(remoteChanges ? ThemeManager.getAccent("danger") : ThemeManager.getAccent("nice"));
    }

    private List<String> selectedGitPaths() {
        if (gitChangesTree == null) return List.of();
        Set<String> publishable = new LinkedHashSet<>();
        if (publishInspectionReady) changes.values().forEach(change -> publishable.add(normalizePath(change.relativePath())));
        else gitChangePaths().forEach(path -> publishable.add(normalizePath(path)));
        return gitChangesTree.selectedChanges().stream()
                .flatMap(selection -> selection.files().stream())
                .map(GitFileStatus::path)
                .filter(path -> publishable.contains(normalizePath(path)))
                .distinct()
                .toList();
    }

    private List<String> gitChangePaths() {
        if (gitStatus == null || gitStatus.files() == null) return List.of();
        return gitStatus.files().stream().map(GitFileStatus::path).filter(this::isVisibleGitPath).distinct().toList();
    }

    private boolean isVisibleGitPath(String path) {
        if (development.isManagedIgnorePath(path)) return false;
        return !development.isRuntimeStatePath(path) || development.includesRuntimeStateInGit(workspace);
    }

    private String gitCategory(GitFileStatus file) {
        return development.isRuntimeStatePath(file.path()) ? "Server Data" : "";
    }

    private String buildGitStatusKey(GitStatus status) {
        StringBuilder key = new StringBuilder();
        for (GitFileStatus file : status.files()) key.append(file.path()).append(':').append(file.indexStatus()).append(':').append(file.workTreeStatus()).append('\n');
        return key.toString();
    }

    private boolean hasGitSelection() {
        return gitChangesTree != null && !gitChangesTree.selectedChanges().isEmpty();
    }

    private void extendGitContextMenu(List<GitChangesTreeWidget.Selection> selection, ContextMenuWidget.Builder menu) {
        Set<String> publishable = new LinkedHashSet<>();
        changes.values().forEach(change -> publishable.add(normalizePath(change.relativePath())));
        List<String> paths = selection.stream().flatMap(selected -> selected.files().stream()).map(GitFileStatus::path).filter(path -> publishable.contains(normalizePath(path))).distinct().toList();
        if (paths.isEmpty()) return;
        menu.addIconItem("Restore Selected", "goback.png", () -> confirmRestoreSelected(paths), paths.size() + (paths.size() == 1 ? " File" : " Files"));
        menu.addIconItem("Ignore Selected", "hide.png", () -> ignoreSelected(paths), "Hide From Development Changes");
    }

    private void confirmRestoreSelected(List<String> paths) {
        if (workspace == null || paths == null || paths.isEmpty()) return;
        Object target = source;
        Workspace targetWorkspace = workspace;
        PopupWidget.Builder builder = new PopupWidget.Builder("Restore Changes").width(380).setResizable(false);
        builder.addRow(new PopupWidget.PopupRow.Builder("", row(paths.size() + (paths.size() == 1 ? " File" : " Files"), "Discard The Selected Local Changes", ThemeManager.getAccent("danger"))).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Restore", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            reference[0].hide();
            development.restoreWorkspacePaths(targetWorkspace.id, paths).whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameWorkspace(target, targetWorkspace)) {
                    refresh();
                }
            }));
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private void ignoreSelected(List<String> paths) {
        if (workspace == null || paths == null || paths.isEmpty()) return;
        Object target = source;
        Workspace targetWorkspace = workspace;
        development.addIgnorePaths(targetWorkspace.id, paths).whenComplete((updated, error) -> ScreenManager.getInstance().execute(() -> {
            if (error == null && sameWorkspace(target, targetWorkspace)) {
                workspace = updated;
                refresh();
            }
        }));
    }

    private void confirmRestore(Object target, Workspace targetWorkspace, FileChange change) {
        PopupWidget.Builder builder = new PopupWidget.Builder("Restore File").width(380).setResizable(false);
        builder.addRow(new PopupWidget.PopupRow.Builder("", row(change.relativePath(), "Discard The Local Changes To This File", ThemeManager.getAccent("danger"))).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Restore", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            reference[0].hide();
            development.restoreWorkspacePath(targetWorkspace.id, change.relativePath()).whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameWorkspace(target, targetWorkspace)) {
                    refresh();
                }
            }));
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private void showSetup() {
        if (source == null) return;
        Object target = source;
        TextInputWidget name = new TextInputWidget.Builder().text(host.developmentInstanceName(target) + " Development").build();
        DropDownWidget<String> content = new DropDownWidget.Builder<>(List.of("Recommended", "Everything", "Choose Content")).selectedItem("Recommended").size(190, 18).build();
        ToggleWidget configs = toggle(true);
        ToggleWidget plugins = toggle(true);
        ToggleWidget mods = toggle(true);
        ToggleWidget dataPacks = toggle(true);
        ToggleWidget scripts = toggle(true);
        ToggleWidget worlds = toggle(false);
        ToggleWidget playerProgress = toggle(false);
        TextInputWidget include = new TextInputWidget.Builder().placeholder("plugins/MyPlugin, custom-folder").build();
        TextAreaWidget exclude = pathArea(List.of(), "logs\ncache\ngenerated-files");
        Container contentOptions = contentOptions(configs, plugins, mods, dataPacks, scripts, worlds, playerProgress);
        PopupWidget.Builder builder = new PopupWidget.Builder("Set Up Development").width(430).setResizable(false).setExpandWithDropdowns(true).setAntiOutOfBound(true);
        builder.addRow(new PopupWidget.PopupRow.Builder("Name", name).id("name").description("The Local Development Server Name").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Content", content).id("content").description("Recommended Includes Settings, Plugins, Mods, And Scripts").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("", contentOptions).id("contentOptions").minHeight(70).build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Extra Folders", include).id("include").description("Other Server Folders To Include").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Ignored Content", exclude).id("exclude").description("One Path Per Line").minHeight(58).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Set Up", () -> {
            if (!sameInstance(source, target)) return;
            reference[0].hide();
            ScopeProfile scope = new ScopeProfile();
            String selected = content.getSelectedItem();
            scope.fullInstance = "Everything".equals(selected);
            if ("Choose Content".equals(selected)) {
                scope.configs = configs.getValue();
                scope.plugins = plugins.getValue();
                scope.mods = mods.getValue();
                scope.dataPacks = dataPacks.getValue();
                scope.scripts = scripts.getValue();
                scope.world = worlds.getValue();
                scope.playerData = playerProgress.getValue();
                scope.stats = playerProgress.getValue();
                scope.advancements = playerProgress.getValue();
            }
            scope.includePaths = paths(include.getText());
            scope.excludePaths = paths(exclude.getText());
            CreateRequest request = new CreateRequest();
            request.twinName = name.getText();
            request.scope = scope;
            request.initializeGit = true;
            development.createWorkspace(target, request, loading("Setting Up Development", host.developmentInstanceName(target))).whenComplete((created, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameInstance(source, target)) openWorkspace(target, created);
            }));
        }, PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        Runnable visibility = () -> {
            boolean custom = "Choose Content".equals(content.getSelectedItem());
            popup.setRowVisibility("contentOptions", custom);
        };
        content.setOnSelectionChanged(ignored -> visibility.run());
        visibility.run();
        host.addDrawableChild(popup);
        popup.show();
    }

    private void showGitActions() {
        ContextMenuWidget.Builder menu = new ContextMenuWidget.Builder(host.screen());
        GitChangesTreeWidget.Capabilities gitCapabilities = gitCapabilities();
        if (gitCapabilities.allows(Capability.BRANCHES)) menu.addIconItem("Branches", "graph.png", this::showGitBranches, "Checkout Branch");
        if (gitCapabilities.allows(Capability.HISTORY)) menu.addIconItem("Git Log", "history.png", this::showGitLog, "Commit Log");
        if (gitCapabilities.allows(Capability.STASH)) menu.addIconItem("Stashes", "backup.png", this::showGitStashes, "Browse Stashes");
        if (gitCapabilities.allows(Capability.POP_STASH)) menu.addIconItem("Pop Stash", "unmerge.png", () -> runGitOperation(gitJobs == null ? null : gitJobs.popStash(), "Stash Popped", "Pop Stash Failed"), "Apply Latest Stash");
        if (gitCapabilities.allows(Capability.STASH)) menu.addIconItem("Drop Stash", "delete.png", this::showDropStash, "Delete A Stash");
        GitChangesTreeWidget.RepositoryChanges repository = gitRepository();
        if (repository != null && gitCapabilities.allows(Capability.PULL)) menu.addIconItem("Pull", "download.png", () -> runGitOperation(repository.api().pull(), "Pulled", "Pull Failed"), "Pull Changes");
        if (repository != null && gitCapabilities.allows(Capability.PUSH)) menu.addIconItem("Push", "upload.png", () -> runGitOperation(repository.api().push(), "Pushed", "Push Failed"), "Push Changes");
        host.screen().showContextMenu(gitActions.getX(), gitActions.getY() + gitActions.getHeight() + 2, menu);
    }

    private void showGitCommit(List<GitChangesTreeWidget.Selection> selection) {
        if (selection == null || selection.isEmpty()) {
            new Notification("Commit", "Select Changes", Notification.Type.INFO);
            return;
        }
        String[] message = {""};
        PopupWidget[] reference = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Commit Changes").width(420).setMinSize(320, 0);
        builder.addTextField("Message", "", value -> message[0] = value);
        builder.addTitleAction("Commit", () -> {
            String value = message[0] == null ? "" : message[0].trim();
            if (value.isBlank()) {
                new Notification("Commit", "Message Required", Notification.Type.INFO);
                return;
            }
            reference[0].hide();
            List<Async<Void>> commits = selection.stream().map(selected -> {
                List<String> paths = selected.files().stream().map(GitFileStatus::path).toList();
                return gitJobs != null && !gitJobs.supports(GitJobProvider.Operation.STAGE)
                        ? selected.repository().api().commit(value, paths)
                        : selected.repository().api().add(paths).thenCompose(ignored -> selected.repository().api().commit(value, paths));
            }).toList();
            runGitOperations(commits, "Committed", "Commit Failed");
        }, PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private void runGitOperation(Async<Void> operation, String success, String failure) {
        runGitOperations(operation == null ? List.of() : List.of(operation), success, failure);
    }

    private void runGitOperations(List<Async<Void>> operations, String success, String failure) {
        if (operations == null || operations.isEmpty()) {
            new Notification(failure, "No Repository", Notification.Type.INFO);
            return;
        }
        Async.allOf(operations.toArray(Async[]::new)).whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
            if (error == null) {
                new Notification(success, workspace == null ? "Development" : workspace.name, Notification.Type.SUCCESS);
                refresh();
            } else {
                new Notification(failure, message(error), Notification.Type.ERROR);
            }
        }));
    }

    private GitChangesTreeWidget.RepositoryChanges gitRepository() {
        if (gitChangesTree == null || gitChangesTree.repositories().isEmpty()) return null;
        return gitChangesTree.repositories().getFirst();
    }

    private void showGitBranches() {
        GitChangesTreeWidget.RepositoryChanges repository = gitRepository();
        if (repository == null) return;
        repository.api().getBranches().whenComplete((branches, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) new Notification("Branches Failed", message(error), Notification.Type.ERROR);
            else showBranchSelector(repository, branches);
        }));
    }

    private void showBranchSelector(GitChangesTreeWidget.RepositoryChanges repository, List<GitBranch> branches) {
        ItemSelectorWidget.Builder builder = gitSelector("Branches", "No Branches", branches.size());
        branches.forEach(branch -> {
            String label = branch.name();
            String hint = branch.remote() ? "Remote" : branch.upstream() == null || branch.upstream().isBlank() ? "Local" : branch.upstream();
            builder.addItem(label, branch.current() ? "checkmark.png" : null, hint, branch.name() + " " + hint, () -> {
                if (branch.current()) return;
                runGitOperation(repository.api().checkout(branch.name()), "Branch Changed", "Checkout Failed");
            });
        });
        showGitSelector(builder);
    }

    private void showGitLog() {
        GitChangesTreeWidget.RepositoryChanges repository = gitRepository();
        if (repository == null) return;
        repository.api().getLog(100, null).whenComplete((commits, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Git Log Failed", message(error), Notification.Type.ERROR);
                return;
            }
            ItemSelectorWidget.Builder builder = gitSelector("Git Log", "No Commits", commits.size());
            for (GitCommit commit : commits) {
                String label = commit.shortHash() + " · " + commit.subject();
                String hint = commit.authorName() + " · " + commit.timestamp();
                boolean filesAvailable = gitJobs == null || gitJobs.supports(GitJobProvider.Operation.COMMIT_FILES);
                builder.addItem(label, hint, label + " " + hint, () -> {
                    if (filesAvailable) showCommitFiles(repository, commit);
                });
            }
            showGitSelector(builder);
        }));
    }

    private void showCommitFiles(GitChangesTreeWidget.RepositoryChanges repository, GitCommit commit) {
        repository.api().getCommitFiles(commit.hash()).whenComplete((files, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Commit Files Failed", message(error), Notification.Type.ERROR);
                return;
            }
            ItemSelectorWidget.Builder builder = gitSelector(commit.shortHash() + " · Files", "No Files", files.size());
            for (String file : files) builder.addItem(file, commit.subject(), file + " " + commit.subject(), () -> openGitCommitDiff(repository, commit, file));
            showGitSelector(builder);
        }));
    }

    private void openGitCommitDiff(GitChangesTreeWidget.RepositoryChanges repository, GitCommit commit, String file) {
        repository.api().diff(commit.hash(), file).whenComplete((text, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Commit Diff Failed", message(error), Notification.Type.ERROR);
                return;
            }
            RemotePath path = RemotePath.of(file);
            String fileName = path.fileName().isBlank() ? file : path.fileName();
            showDiffEditor(commit.shortHash() + " · " + fileName, path, GitUnifiedDiffDocument.parse(text), null);
        }));
    }

    private void showGitStashes() {
        GitChangesTreeWidget.RepositoryChanges repository = gitRepository();
        if (repository == null) return;
        repository.api().listStash().whenComplete((stashes, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Stashes Failed", message(error), Notification.Type.ERROR);
                return;
            }
            ItemSelectorWidget.Builder builder = gitSelector("Stashes", "No Stashes", stashes.size());
            for (GitStashEntry stash : stashes) {
                boolean canApply = gitJobs == null || gitJobs.supports(GitJobProvider.Operation.STASH_APPLY);
                String label = (canApply ? "Apply " : "Stash ") + stash.index() + " · " + stash.message();
                String hint = stash.branch() + " · " + (Instant.EPOCH.equals(stash.timestamp()) ? stash.author() : stash.timestamp());
                builder.addItem(label, hint, label + " " + hint, () -> {
                    if (canApply) {
                        Async<Void> operation = stash.id() == null || stash.id().isBlank() ? repository.api().applyStash(stash.index())
                                : gitJobs.applyStash(stash.id());
                        runGitOperation(operation, "Stash Applied", "Apply Stash Failed");
                    }
                });
            }
            showGitSelector(builder);
        }));
    }

    private void showDropStash() {
        GitChangesTreeWidget.RepositoryChanges repository = gitRepository();
        if (repository == null) return;
        repository.api().listStash().whenComplete((stashes, error) -> ScreenManager.getInstance().execute(() -> {
            if (error != null) {
                new Notification("Stashes Failed", message(error), Notification.Type.ERROR);
                return;
            }
            ItemSelectorWidget.Builder builder = gitSelector("Drop Stash", "No Stashes", stashes.size());
            for (GitStashEntry stash : stashes) {
                String label = "Drop " + stash.index() + " · " + stash.message();
                builder.addItem(label, stash.branch(), label + " " + stash.branch(), () -> runGitOperation(repository.api().dropStash(stash.index()), "Stash Dropped", "Drop Stash Failed"));
            }
            showGitSelector(builder);
        }));
    }

    private ItemSelectorWidget.Builder gitSelector(String title, String emptyMessage, int entryCount) {
        int selectorWidth = Math.min(480, Math.max(300, host.getWidth() - 40));
        int selectorLimit = Math.min(360, Math.max(64, host.getHeight() - 100));
        int selectorHeight = Math.min(selectorLimit, Math.max(64, 47 + Math.max(1, entryCount) * 15));
        return new ItemSelectorWidget.Builder(host.screen()).size(selectorWidth, selectorHeight).emptyMessage(emptyMessage).addSectionHeader(title);
    }

    private GitChangesTreeWidget.Capabilities gitCapabilities() {
        if (developer == null) return GitChangesTreeWidget.Capabilities.of();
        var descriptor = developer.capabilities().get(CapabilityIds.GIT);
        if (descriptor == null || !descriptor.available()) return GitChangesTreeWidget.Capabilities.of();
        Set<Capability> supported = new LinkedHashSet<>();
        var files = developer.capabilities().get(CapabilityIds.FILES);
        if (files != null && files.available()) supported.add(Capability.OPEN_FILE);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.DIFF)) supported.add(Capability.SHOW_DIFF);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.STAGE)) {
            supported.add(Capability.STAGE);
            supported.add(Capability.UNSTAGE);
        }
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.COMMIT)) supported.add(Capability.COMMIT);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.BRANCH)) supported.add(Capability.BRANCHES);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.LOG)) supported.add(Capability.HISTORY);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.STASH_PUSH)
                || gitJobs.supports(GitJobProvider.Operation.STASH_LIST)
                || gitJobs.supports(GitJobProvider.Operation.STASH_DROP)) supported.add(Capability.STASH);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.STASH_POP)) supported.add(Capability.POP_STASH);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.PULL)) supported.add(Capability.PULL);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.PUSH)) supported.add(Capability.PUSH);
        if (gitJobs == null || gitJobs.supports(GitJobProvider.Operation.STATUS)) supported.add(Capability.REFRESH);
        return GitChangesTreeWidget.Capabilities.of(supported.toArray(Capability[]::new));
    }

    private void updateGitTools() {
        if (gitTools.size() < 6) return;
        GitChangesTreeWidget.Capabilities supported = gitCapabilities();
        gitTools.get(0).setActive(supported.allows(Capability.REFRESH));
        gitTools.get(1).setActive(supported.allows(Capability.STAGE));
        gitTools.get(2).setActive(supported.allows(Capability.UNSTAGE));
        gitTools.get(3).setActive(supported.allows(Capability.COMMIT));
        gitTools.get(4).setActive(supported.allows(Capability.STASH));
        gitTools.get(5).setActive(supported.allowsAny(Capability.BRANCHES, Capability.HISTORY, Capability.STASH, Capability.POP_STASH,
                Capability.PULL, Capability.PUSH));
    }

    private void showGitSelector(ItemSelectorWidget.Builder builder) {
        ItemSelectorWidget[] reference = new ItemSelectorWidget[1];
        builder.onClose(() -> {
            if (reference[0] != null) host.remove(reference[0]);
        });
        ItemSelectorWidget selector = builder.build();
        reference[0] = selector;
        host.addDrawableChild(selector);
        selector.show(Math.max(8, (host.getWidth() - selector.getWidth()) / 2), Math.max(54, (host.getHeight() - selector.getHeight()) / 2));
    }

    private void showPublish() {
        if (!publishInspectionReady) {
            loadPublishInspection();
            return;
        }
        List<String> selected = selectedGitPaths();
        boolean selectionActive = hasGitSelection();
        if (selectionActive && selected.isEmpty()) {
            new Notification("Nothing To Publish", "The Selection Contains Git-Only Files", Notification.Type.INFO);
            return;
        }
        List<String> publishPaths = selectionActive ? selected : changes.values().stream().map(FileChange::relativePath).toList();
        if (source == null || workspace == null || publishPaths.isEmpty()) return;
        if (inspection != null && inspection.remoteDrift() != null && !inspection.remoteDrift().isEmpty()) {
            new Notification("Sync Required", "The Server Changed Since Your Last Sync", Notification.Type.ERROR);
            return;
        }
        Object target = source;
        Workspace targetWorkspace = workspace;
        TextInputWidget message = new TextInputWidget.Builder().placeholder("What Changed?").build();
        PopupWidget.Builder builder = new PopupWidget.Builder("Publish Changes").width(390).setResizable(false);
        builder.addRow(new PopupWidget.PopupRow.Builder("Summary", message).description("This Appears In Version History").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("", row(publishPaths.size() + (publishPaths.size() == 1 ? " Change" : " Changes"), "A Restorable Version Is Saved Automatically", ThemeManager.getAccent("nice"))).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Publish", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            if (message.getText() == null || message.getText().isBlank()) {
                new Notification("Summary Required", "Describe What Changed", Notification.Type.INFO);
                return;
            }
            reference[0].hide();
            DeployRequest request = new DeployRequest();
            request.workspaceId = targetWorkspace.id;
            request.label = message.getText().trim();
            request.mode = DeploymentMode.DIRECT;
            request.selectedPaths = publishPaths;
            request.createGitCheckpoint = false;
            request.failOnRemoteDrift = true;
            development.deploy(target, request, loading("Publishing", targetWorkspace.name)).whenComplete((result, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameWorkspace(target, targetWorkspace)) {
                    refresh();
                }
            }));
        }, PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private void loadPublishInspection() {
        if (inspectionLoading || source == null || workspace == null) return;
        inspectionLoading = true;
        Object target = source;
        String twinId = workspace.id;
        new Notification("Checking Server Changes", workspace.name, Notification.Type.INFO);
        development.inspectLocal(target, twinId).whenComplete((result, error) -> ScreenManager.getInstance().execute(() -> {
            inspectionLoading = false;
            if (disposed || workspace == null || !Objects.equals(workspace.id, twinId)) return;
            if (error != null) {
                new Notification("Could Not Check Changes", message(error), Notification.Type.ERROR);
                return;
            }
            inspection = result;
            workspace = inspection.workspace();
            publishInspectionReady = true;
            applyInspection();
            showPublish();
        }));
    }

    private void confirmSync() {
        if (source == null || workspace == null) return;
        Object target = source;
        Workspace targetWorkspace = workspace;
        PopupWidget.Builder builder = new PopupWidget.Builder("Sync From Server").width(380).setResizable(false);
        builder.addRow(new PopupWidget.PopupRow.Builder("", row("Bring In Server Changes", "Review Local Changes After Syncing", ThemeManager.getAccent("calm"))).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Sync", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            reference[0].hide();
            development.pull(target, targetWorkspace.id, loading("Syncing From Server", host.developmentInstanceName(target))).whenComplete((updated, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameInstance(source, target)) {
                    workspace = updated;
                    refresh();
                }
            }));
        }, PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private void showHistory() {
        if (workspace == null) return;
        Object target = source;
        Workspace targetWorkspace = workspace;
        List<Deployment> versions = new ArrayList<>(targetWorkspace.deployments == null ? List.of() : targetWorkspace.deployments);
        versions.sort(Comparator.comparingLong(Deployment::createdAt).reversed());
        int selectorWidth = Math.min(400, Math.max(300, host.getWidth() - 40));
        int selectorHeight = Math.min(Math.max(78, 48 + versions.size() * 30), Math.min(300, Math.max(120, host.getHeight() - 90)));
        ItemSelectorWidget[] reference = new ItemSelectorWidget[1];
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(host.screen())
                .size(selectorWidth, selectorHeight)
                .emptyMessage("No Published Versions")
                .addSectionHeader("Version History")
                .onClose(() -> {
                    if (reference[0] != null) host.remove(reference[0]);
                });
        for (Deployment version : versions) {
            String badge = version.deployedToSource() ? "Published" : "Saved";
            int changeCount = version.changes() == null ? 0 : version.changes().size();
            String label = version.label() == null || version.label().isBlank() ? "Untitled Version" : version.label();
            String hint = TIME.format(Instant.ofEpochMilli(version.createdAt())) + " • " + changeCount + (changeCount == 1 ? " Change" : " Changes");
            builder.addBadgedItem(label, badge, hint, label + " " + hint, () -> {
                if (reference[0] != null) reference[0].hide();
                confirmRestoreVersion(target, targetWorkspace, version);
            });
        }
        ItemSelectorWidget selector = builder.build();
        reference[0] = selector;
        host.addDrawableChild(selector);
        int selectorX = Math.clamp(history.getX() + history.getWidth() - selector.getWidth(), 8, Math.max(8, host.getWidth() - selector.getWidth() - 8));
        int below = history.getY() + history.getHeight() + 3;
        int selectorY = below + selector.getHeight() <= host.getHeight() - 20 ? below : Math.max(54, history.getY() - selector.getHeight() - 3);
        selector.show(selectorX, selectorY);
    }

    private void confirmRestoreVersion(Object target, Workspace targetWorkspace, Deployment version) {
        String label = version.label() == null || version.label().isBlank() ? "Untitled Version" : version.label();
        int changeCount = version.changes() == null ? 0 : version.changes().size();
        PopupWidget.Builder builder = new PopupWidget.Builder("Restore " + label).width(400).setResizable(false);
        String description = version.deployedToSource() ? "The Server And Development Server Return To This Version" : "The Development Server Returns To This Version";
        builder.addRow(new PopupWidget.PopupRow.Builder("", row(changeCount + (changeCount == 1 ? " Change" : " Changes"), description, ThemeManager.getAccent("danger"))).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Restore", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            reference[0].hide();
            development.rollback(target, targetWorkspace.id, version.id(), loading("Restoring Version", label)).whenComplete((restored, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameWorkspace(target, targetWorkspace)) {
                    refresh();
                }
            }));
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private void showSettings() {
        if (workspace == null) return;
        Object target = source;
        Workspace targetWorkspace = workspace;
        ScopeProfile scope = targetWorkspace.scope == null ? ScopeProfile.defaults() : targetWorkspace.scope.copy();
        boolean recommended = !scope.fullInstance && scope.configs && scope.plugins && scope.mods && scope.dataPacks && scope.scripts
                && !scope.world && !scope.playerData && !scope.stats && !scope.advancements;
        String currentProfile = scope.fullInstance ? "Everything" : recommended ? "Recommended" : "Choose Content";
        DropDownWidget<String> content = new DropDownWidget.Builder<>(List.of("Recommended", "Everything", "Choose Content")).selectedItem(currentProfile).size(190, 18).build();
        ToggleWidget configs = toggle(scope.configs);
        ToggleWidget plugins = toggle(scope.plugins);
        ToggleWidget mods = toggle(scope.mods);
        ToggleWidget dataPacks = toggle(scope.dataPacks);
        ToggleWidget scripts = toggle(scope.scripts);
        ToggleWidget worlds = toggle(scope.world);
        ToggleWidget playerProgress = toggle(scope.playerData || scope.stats || scope.advancements);
        TextInputWidget additional = new TextInputWidget.Builder().placeholder("Extra Folders Or Files").text(String.join(", ", scope.includePaths == null ? List.of() : scope.includePaths)).animateElevation(false).entranceAnimation(false).build();
        TextAreaWidget ignored = pathArea(targetWorkspace.ignorePaths, "logs\ncache\ngenerated-files");
        Container contentOptions = contentOptions(configs, plugins, mods, dataPacks, scripts, worlds, playerProgress);
        PopupWidget.Builder builder = new PopupWidget.Builder("Development Settings").width(420).setResizable(false).setExpandWithDropdowns(true).setAntiOutOfBound(true);
        builder.addRow(new PopupWidget.PopupRow.Builder("Content", content).description("Recommended Includes Settings, Plugins, Mods, And Scripts").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("", contentOptions).id("contentOptions").minHeight(70).build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Additional Content", additional).description("Comma-Separated Paths To Include").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Ignored Content", ignored).description("One Path Per Line").minHeight(58).build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Save", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            reference[0].hide();
            ScopeProfile updatedScope = new ScopeProfile();
            String selectedProfile = content.getSelectedItem();
            updatedScope.fullInstance = "Everything".equals(selectedProfile);
            if ("Choose Content".equals(selectedProfile)) {
                updatedScope.configs = configs.getValue();
                updatedScope.plugins = plugins.getValue();
                updatedScope.mods = mods.getValue();
                updatedScope.dataPacks = dataPacks.getValue();
                updatedScope.scripts = scripts.getValue();
                updatedScope.world = worlds.getValue();
                updatedScope.playerData = playerProgress.getValue();
                updatedScope.stats = playerProgress.getValue();
                updatedScope.advancements = playerProgress.getValue();
            }
            updatedScope.includePaths = paths(additional.getText());
            development.updateSettings(targetWorkspace.id, updatedScope, paths(ignored.getText())).whenComplete((updated, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameInstance(source, target)) {
                    workspace = updated;
                    development.pull(target, updated.id, loading("Updating Development Files", host.developmentInstanceName(target))).whenComplete((synced, syncError) -> ScreenManager.getInstance().execute(() -> {
                        if (!sameInstance(source, target)) return;
                        if (syncError == null) workspace = synced;
                        refresh();
                    }));
                }
            }));
        }, PopupWidget.TitleActionRole.PRIMARY);
        if (inspection != null && inspection.ignoredChanges() != null && !inspection.ignoredChanges().isEmpty()) {
            builder.addTitleAction("View Ignored", () -> {
                reference[0].hide();
                showIgnored(inspection.ignoredChanges());
            }, PopupWidget.TitleActionRole.SECONDARY);
        }
        builder.addTitleAction("Remove", () -> {
            reference[0].hide();
            confirmRemove(target, targetWorkspace);
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        Runnable updateRows = () -> {
            boolean custom = "Choose Content".equals(content.getSelectedItem());
            popup.setRowVisibility("contentOptions", custom);
        };
        content.setOnSelectionChanged(ignoredSelection -> updateRows.run());
        updateRows.run();
        host.addDrawableChild(popup);
        popup.show();
    }

    private void showIgnored(List<IgnoredChange> ignored) {
        ItemSelectorWidget[] reference = new ItemSelectorWidget[1];
        int selectorWidth = Math.min(480, Math.max(300, host.getWidth() - 40));
        int selectorHeight = Math.min(Math.max(78, 48 + ignored.size() * 30), Math.min(300, Math.max(120, host.getHeight() - 90)));
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(host.screen()).size(selectorWidth, selectorHeight).emptyMessage("No Ignored Files").addSectionHeader("Ignored Files").onClose(() -> {
            if (reference[0] != null) host.remove(reference[0]);
        });
        ignored.forEach(change -> builder.addBadgedItem(change.relativePath(), changeLabel(change.type()), change.reason(), change.relativePath() + " " + change.reason(), () -> {}));
        ItemSelectorWidget selector = builder.build();
        reference[0] = selector;
        host.addDrawableChild(selector);
        int selectorX = Math.clamp(settings.getX() + settings.getWidth() - selector.getWidth(), 8, Math.max(8, host.getWidth() - selector.getWidth() - 8));
        int below = settings.getY() + settings.getHeight() + 3;
        int selectorY = below + selector.getHeight() <= host.getHeight() - 20 ? below : Math.max(54, settings.getY() - selector.getHeight() - 3);
        selector.show(selectorX, selectorY);
    }

    private void confirmRemove(Object target, Workspace targetWorkspace) {
        ToggleWidget deleteFiles = toggle(false);
        PopupWidget.Builder builder = new PopupWidget.Builder("Remove Development Server").width(400).setResizable(false);
        builder.addRow(new PopupWidget.PopupRow.Builder("Delete Local Files", deleteFiles).description("Keep Them Off To Preserve The Local Server Files").build());
        PopupWidget[] reference = new PopupWidget[1];
        builder.addTitleAction("Remove", () -> {
            if (!sameWorkspace(target, targetWorkspace)) return;
            reference[0].hide();
            Object removed = developmentServer;
            development.disable(target, deleteFiles.getValue()).whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
                if (error == null && sameInstance(source, target)) {
                    hidePanel();
                    host.closeInstanceTab(removed);
                    host.addInstanceTab(target);
                    source = null;
                    developmentServer = null;
                    workspace = null;
                    inspection = null;
                    changes.clear();
                }
            }));
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget popup = builder.build();
        reference[0] = popup;
        host.addDrawableChild(popup);
        popup.show();
    }

    private Workspace twinForDevelopmentServer(Object candidate) {
        return development.workspaceForDevelopmentServer(candidate);
    }

    private Object sourceFor(Workspace targetWorkspace) {
        return development.sourceFor(targetWorkspace);
    }

    private boolean sameWorkspace(Object target, Workspace targetWorkspace) {
        return sameInstance(source, target) && workspace != null && targetWorkspace != null && Objects.equals(workspace.id, targetWorkspace.id);
    }

    private boolean sameInstance(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        String leftId = host.developmentInstanceId(left);
        String rightId = host.developmentInstanceId(right);
        if (!leftId.isBlank() && !rightId.isBlank()) return leftId.equals(rightId);
        return left.equals(right);
    }

    private SquareButtonWidget tool(String image, String hint, Runnable action, Accent accent) {
        return new SquareButtonWidget.Builder().imagePath(image).hint(hint).onClick(action).accentType(accent).animateElevation(false).entranceAnimation(false).size(16, 16).build();
    }

    private SquareButtonWidget addGitToolbarButton(String image, String hint, Runnable action) {
        SquareButtonWidget button = new SquareButtonWidget.Builder().imagePath(image).hint(hint).onClick(action).animateElevation(false).entranceAnimation(false).size(18, 18).build();
        gitTools.add(button);
        return button;
    }

    private void addGitToolbarButton(Capability capability, String image, String hint, Runnable action) {
        if (developer == null || gitCapabilities().allows(capability)) addGitToolbarButton(image, hint, action);
    }

    private MountableButtonWidget row(String title, String description, Accent accent) {
        return row(title, description, accent, 30);
    }

    private MountableButtonWidget row(String title, String description, Accent accent, int height) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(title).description(description).build();
        row.setSize(340, height);
        row.setAccent(accent);
        row.entranceAnimationEnabled = false;
        row.setAnimateLayout(false);
        return row;
    }

    private ToggleWidget toggle(boolean value) {
        return new ToggleWidget.Builder().toggled(value).animateElevation(false).entranceAnimation(false).build();
    }

    private TextAreaWidget pathArea(List<String> paths, String placeholder) {
        return new TextAreaWidget.Builder()
                .text(String.join("\n", paths == null ? List.of() : paths))
                .placeholder(placeholder)
                .wordWrap(false)
                .showLineNumbers(false)
                .animateElevation(false)
                .entranceAnimation(false)
                .size(200, 58)
                .build();
    }

    private Container contentOptions(ToggleWidget configs, ToggleWidget plugins, ToggleWidget mods, ToggleWidget dataPacks,
                                     ToggleWidget scripts, ToggleWidget worlds, ToggleWidget playerProgress) {
        Container options = new Container("development-content-options", 0, 0, 390, 70);
        options.layout(new ManagedLayout()).columns(3).padding(2).scrolling(false).backgroundDrawing(false).enableSelecting(false).setAnimateLayout(false);
        options.addWidget(contentOption("Settings", configs));
        options.addWidget(contentOption("Plugins", plugins));
        options.addWidget(contentOption("Mods", mods));
        options.addWidget(contentOption("Data Packs", dataPacks));
        options.addWidget(contentOption("Scripts", scripts));
        options.addWidget(contentOption("Worlds", worlds));
        options.addWidget(contentOption("Player Data", playerProgress));
        options.updateWidgetPositions();
        return options;
    }

    private MountableButtonWidget contentOption(String name, ToggleWidget toggle) {
        MountableButtonWidget option = new MountableButtonWidget.Builder(name).addWidget(toggle).build();
        option.setSize(120, 20);
        option.mountedGap(2).headerPadding(3);
        option.entranceAnimationEnabled = false;
        option.selectable = false;
        option.setAnimateLayout(false);
        return option;
    }

    private Notification loading(String title, String description) {
        return new Notification.Builder().message(title).description(description).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
    }

    private List<String> paths(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return new ArrayList<>();
        for (String part : value.split("[,\\r\\n]+")) {
            String normalized = part.trim().replace('\\', '/');
            if (!normalized.isBlank()) result.add(normalized);
        }
        return new ArrayList<>(result);
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("^/+", "").toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String changeLabel(FileChangeType type) {
        return switch (type == null ? FileChangeType.MODIFIED : type) {
            case ADDED -> "New";
            case MODIFIED -> "Changed";
            case DELETED -> "Removed";
        };
    }

    private String formatSize(long size) {
        if (size <= 0) return "None";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = size;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (unit == 0 ? Long.toString(size) : String.format(Locale.ROOT, "%.1f", value)) + " " + units[unit];
    }

    private String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Request Failed" : message;
    }

    private record GitLoad(GitStatus status, Throwable error) {
    }

}
