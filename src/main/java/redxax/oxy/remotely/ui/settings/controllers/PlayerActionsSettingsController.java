package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;
import redxax.oxy.remotely.data.managed.PlayerAction;
import redxax.oxy.remotely.data.managed.PlayerActionJson;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.*;
import restudio.rescreen.util.Notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import restudio.rescreen.platform.Async;


public class PlayerActionsSettingsController {
    private static final String PLAYER_ACTIONS_TAB = "Player Actions";
    private static final long LOAD_TIMEOUT_SECONDS = 20L;

    private final PlayerActionsFileProvider files;
    private List<PlayerAction> loadedActions = new ArrayList<>();
    private boolean actionsLoaded;
    private boolean actionsLoading;
    private volatile boolean closed;
    private Async<Void> saveChain = Async.completed(null);
    private Setting setting;
    private IconButton createButton;
    private PopupWidget.PopupRow createRow;
    private PopupWidget.PopupRow loadingRow;
    private final Map<String, PopupWidget.PopupRow> actionRows = new LinkedHashMap<>();

    public PlayerActionsSettingsController(Object target) {
        this(target instanceof PlayerActionsFileProvider provider
                ? provider
                : new UnavailablePlayerActionsFileProvider("Player Actions File Access Is Unavailable"));
    }

    public PlayerActionsSettingsController(PlayerActionsFileProvider files) {
        this.files = files;
    }

    public void cleanup() {
        closed = true;
        actionsLoading = false;
    }

    private void loadActions() {
        if (closed || actionsLoaded || actionsLoading) {
            return;
        }
        if (!files.available()) return;
        actionsLoading = true;
        AsyncTools.withTimeout(files.read(), TaskSchedulers.current(), Duration.ofSeconds(LOAD_TIMEOUT_SECONDS)).whenComplete((content, error) -> {
            if (error != null) {
                completeLoad(new ArrayList<>(), true);
                return;
            }
            List<PlayerAction> loaded = new ArrayList<>();
            try {
                if (content != null && !content.isEmpty()) {
                    List<PlayerAction> parsed = PlayerActionJson.read(content);
                    if (parsed != null) {
                        loaded = new ArrayList<>(parsed);
                    }
                }
            } catch (Exception e) {
                loaded = new ArrayList<>();
            }
            completeLoad(loaded, true);
        });
    }

    private void completeLoad(List<PlayerAction> actions, boolean refresh) {
        ScreenManager.getInstance().execute(() -> {
            if (closed) return;
            loadedActions = sanitizeActions(actions);
            actionsLoaded = true;
            actionsLoading = false;
            if (refresh) {
                refreshActions();
            }
        });
    }

    private List<PlayerAction> sanitizeActions(List<PlayerAction> actions) {
        List<PlayerAction> sanitized = new ArrayList<>();
        if (actions == null) {
            return sanitized;
        }
        for (PlayerAction action : actions) {
            if (action == null || action.name == null || action.icon == null || action.command == null) {
                continue;
            }
            sanitized.add(copyAction(action));
        }
        return sanitized;
    }

    private PlayerAction copyAction(PlayerAction action) {
        PlayerAction copy = new PlayerAction(action.name, action.icon, action.command);
        copy.uuid = action.uuid == null || action.uuid.isBlank() ? UUID.randomUUID().toString() : action.uuid;
        return copy;
    }

    private void saveActions(List<PlayerAction> actions, String successMessage) {
        if (closed) return;
        List<PlayerAction> snapshot = sanitizeActions(actions);
        String json = PlayerActionJson.write(snapshot);
        loadedActions = new ArrayList<>(snapshot);
        actionsLoaded = true;
        refreshActions();

        synchronized (this) {
            saveChain = saveChain.handle((ignored, previousError) -> null)
                    .thenCompose(ignored -> files.write(json));
            saveChain.thenRun(() -> {
                files.refresh();
                ScreenManager.getInstance().execute(() -> {
                    if (!closed) new Notification("Success", successMessage, Notification.Type.SUCCESS);
                });
            }).exceptionally(e -> {
                ScreenManager.getInstance().execute(() -> {
                    if (!closed) new Notification("Error", "Failed To Save Actions: " + e.getMessage(), Notification.Type.ERROR);
                });
                return null;
            });
        }
    }

    public List<Setting> getSettings() {
        if (closed) return setting == null ? List.of() : List.of(setting);
        loadActions();
        if (setting != null) {
            refreshRows();
            return List.of(setting);
        }
        Setting.Builder builder = new Setting.Builder(PLAYER_ACTIONS_TAB);

        createButton = new IconButton.Builder()
                .label("Create New")
                .imagePath("create.png")
                .onClick(() -> showPlayerActionPopup(null))
                .accentType(ThemeManager.getAccent("nice")).build();
        createButton.setActive(actionsLoaded && files.available());
        builder.addRow("", createButton);
        setting = builder.build();
        createRow = setting.getRows().getFirst();
        MountableButtonWidget loading = new MountableButtonWidget.Builder("Loading Actions")
                    .description(files.available() ? "Reading Player Actions" : files.reason())
                    .iconPath("reload.png")
                    .build();
        loading.setActive(false);
        loadingRow = new PopupWidget.PopupRow.Builder("", loading).build();
        refreshRows();
        return List.of(setting);
    }

    private void refreshRows() {
        if (setting == null) return;
        createButton.setActive(actionsLoaded && files.available());
        List<PopupWidget.PopupRow> rows = new ArrayList<>();
        rows.add(createRow);
        if (!actionsLoaded) {
            rows.add(loadingRow);
            if (!setting.getRows().equals(rows)) setting.setRows(rows);
            return;
        }
        Map<String, PopupWidget.PopupRow> next = new LinkedHashMap<>();
        for (PlayerAction action : loadedActions) {
            String actionUuid = action.uuid;
            PopupWidget.PopupRow existing = actionRows.get(actionUuid);
            if (existing != null) {
                MountableButtonWidget widget = (MountableButtonWidget) existing.getWidgets().getFirst();
                widget.setName(action.name);
                widget.setDescription(action.command);
                widget.setHiddenText(action.icon);
                next.put(actionUuid, existing);
                rows.add(existing);
                continue;
            }

            SquareButtonWidget editButton = new SquareButtonWidget.Builder()
                    .imagePath("edit.png")
                    .onClick(() -> showPlayerActionPopup(actionUuid))
                    .build();

            SquareButtonWidget deleteButton = new SquareButtonWidget.Builder()
                    .imagePath("delete.png")
                    .onClick(() -> {
                        PlayerAction currentAction = findAction(actionUuid);
                        if (currentAction == null) return;
                        List<PlayerAction> currentActions = new ArrayList<>(loadedActions);
                        currentActions.removeIf(current -> actionUuid.equals(current.uuid));
                        saveActions(currentActions, "Action '" + currentAction.name + "' Deleted.");
                    })
                    .accentType(ThemeManager.getAccent("danger"))
                    .build();

            MountableButtonWidget widget = new MountableButtonWidget.Builder(action.name)
                    .description(action.command)
                    .hiddenText(action.icon)
                    .addButton(editButton)
                    .addButton(deleteButton)
                    .build();
            PopupWidget.PopupRow row = new PopupWidget.PopupRow.Builder("", widget).build();
            next.put(actionUuid, row);
            rows.add(row);
        }
        if (!setting.getRows().equals(rows)) setting.setRows(rows);
        actionRows.clear();
        actionRows.putAll(next);
    }

    private void refreshActions() {
        if (closed) return;
        ScreenManager screenManager = ScreenManager.getInstance();
        Screen current = screenManager.getCurrentScreen();
        List<SettingsScreen> targets = new ArrayList<>();
        if (current instanceof SettingsScreen settingsScreen) {
            targets.add(settingsScreen);
        }
        if (screenManager.getDesktopWindowsOverlay() != null) {
            for (ScreenWindowWidget window : screenManager.getDesktopWindowsOverlay().getWindows()) {
                if (window.getScreen() instanceof SettingsScreen settingsScreen && !targets.contains(settingsScreen)) {
                    targets.add(settingsScreen);
                }
            }
        }
        for (SettingsScreen settingsScreen : targets) {
            settingsScreen.refreshTab(PLAYER_ACTIONS_TAB);
        }
    }

    private void showPlayerActionPopup(String actionUuid) {
        PlayerAction actionToEdit = findAction(actionUuid);
        boolean isEditing = actionToEdit != null;
        
        String title = isEditing ? "Edit Player Action" : "Create Player Action";

        PopupWidget.Builder builder = new PopupWidget.Builder(title)
                .width(300)
                .setResizable(false);

        TextInputWidget nameField = new TextInputWidget.Builder()
                .text(isEditing ? actionToEdit.name : "")
                .placeholder("Action Name")
                .build();

        TextInputWidget iconField = new TextInputWidget.Builder()
                .text(isEditing ? actionToEdit.icon : "minecraft.png")
                .placeholder("Icon Path (e.g., start.png)")
                .build();

        TextInputWidget commandField = new TextInputWidget.Builder()
                .text(isEditing ? actionToEdit.command : "")
                .placeholder("Command (you can reference $name and $uuid)")
                .build();

        builder.addTitleAction(isEditing ? "Save" : "Create", () -> {
            String name = nameField.getText().trim();
            String icon = iconField.getText().trim();
            String command = commandField.getText().trim();

            if (name.isEmpty() || icon.isEmpty() || command.isEmpty()) {
                new Notification("Error", "All Fields Are Required.", Notification.Type.ERROR);
                return;
            }

            List<PlayerAction> currentActions = new ArrayList<>(loadedActions);
            if (isEditing) {
                if (!replaceAction(currentActions, actionUuid, name, icon, command)) {
                    new Notification("Error", "Action No Longer Exists.", Notification.Type.ERROR);
                    builder.getWidget().setVisible(false);
                    refreshActions();
                    return;
                }
            } else {
                currentActions.add(new PlayerAction(name, icon, command));
            }
            saveActions(currentActions, "Action '" + name + "' " + (isEditing ? "Updated." : "Created."));

            builder.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.PRIMARY);

        builder.addRow("Name", nameField);
        builder.addRow("Icon", iconField);
        builder.addRow("Command", commandField);

        PopupWidget popup = builder.build();
        ScreenManager.getInstance().getCurrentScreen().addDrawableChild(popup);
        popup.show();
    }

    private boolean replaceAction(List<PlayerAction> actions, String actionUuid, String name, String icon, String command) {
        if (actionUuid == null || actions == null) {
            return false;
        }
        for (int i = 0; i < actions.size(); i++) {
            PlayerAction action = actions.get(i);
            if (action != null && actionUuid.equals(action.uuid)) {
                PlayerAction replacement = new PlayerAction(name, icon, command);
                replacement.uuid = actionUuid;
                actions.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    private PlayerAction findAction(String actionUuid) {
        return findAction(loadedActions, actionUuid);
    }

    private PlayerAction findAction(List<PlayerAction> actions, String actionUuid) {
        if (actionUuid == null || actions == null) {
            return null;
        }
        for (PlayerAction action : actions) {
            if (action != null && actionUuid.equals(action.uuid)) {
                return action;
            }
        }
        return null;
    }
}
