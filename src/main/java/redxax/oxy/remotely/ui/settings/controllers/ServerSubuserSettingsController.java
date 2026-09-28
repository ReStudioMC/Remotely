package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;

import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import restudio.rescreen.platform.Async;


import static restudio.rescreen.util.SoundUtils.playSound;

public class ServerSubuserSettingsController {
    private static final String SUBUSERS_TAB = "Subusers";
    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final ReScreen parentScreen;
    private final SubuserSettingsProvider subuserFeature;
    private final List<ServerModels.Subuser> subuserCache = new ArrayList<>();
    private final Map<String, ServerModels.PermissionCategory> permissionCategoryCache = new LinkedHashMap<>();
    private volatile boolean dataLoaded;
    private volatile boolean loadingData;
    private volatile boolean loadingAction;
    private volatile String loadError;
    private volatile long loadStartedAt;
    private volatile long loadRequestId;
    private volatile boolean closed;
    private Setting setting;
    private AnimatedButton addSubuserButton;
    private PopupRow addRow;
    private PopupRow stateRow;
    private PopupRow retryRow;
    private PopupRow unavailableRow;
    private final Map<String, PopupRow> subuserRows = new LinkedHashMap<>();

    public ServerSubuserSettingsController(ReScreen parentScreen, SubuserSettingsProvider subuserFeature) {
        this.parentScreen = parentScreen;
        this.subuserFeature = subuserFeature == null ? SubuserSettingsProvider.unavailable("Subuser Feature Is Unavailable") : subuserFeature;
    }

    public void cleanup() {
        closed = true;
        loadRequestId++;
        loadingData = false;
        loadingAction = false;
    }

    public List<Setting> getSettings() {
        if (closed) return setting == null ? List.of() : List.of(setting);
        if (setting != null) {
            if (subuserFeature.available()) ensureDataLoaded();
            refreshRows();
            return List.of(setting);
        }
        if (subuserFeature.available()) ensureDataLoaded();

        Setting.Builder builder = new Setting.Builder("Server Subusers");
        addSubuserButton = new AnimatedButton.Builder()
                .label("Add Subuser")
                .accentType(ThemeManager.getAccent("nice"))
                .onClick(() -> showCreatePopup(permissionCategoryCache))
                .active(dataLoaded)
                .build();
        builder.addRow("", addSubuserButton);
        setting = builder.build();
        addRow = setting.getRows().getFirst();
        stateRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Loading Subusers").active(false).build()).build();
        retryRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Retry Load")
                .accentType(ThemeManager.getDefaultAccent()).onClick(this::loadData).build()).build();
        unavailableRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Subuser Feature Unavailable")
                .active(false).hint(subuserFeature.unavailableReason()).build()).build();
        refreshRows();
        return List.of(setting);
    }

    private void refreshRows() {
        if (setting == null) return;
        if (!subuserFeature.available()) {
            ((AnimatedButton) unavailableRow.getWidgets().getFirst()).setHint(subuserFeature.unavailableReason());
            if (!setting.getRows().equals(List.of(unavailableRow))) setting.setRows(List.of(unavailableRow));
            return;
        }
        addSubuserButton.setActive(dataLoaded && !loadingAction);
        List<PopupRow> rows = new ArrayList<>();
        rows.add(addRow);
        if (!dataLoaded) {
            ((AnimatedButton) stateRow.getWidgets().getFirst()).setMessage(loadingData ? "Loading Subusers" : safe(loadError).isBlank() ? "Subusers Unavailable" : "Load Failed");
            rows.add(stateRow);
            if (!safe(loadError).isBlank()) rows.add(retryRow);
        } else if (subuserCache.isEmpty()) {
            subuserRows.clear();
            ((AnimatedButton) stateRow.getWidgets().getFirst()).setMessage("No Subusers Found");
            rows.add(stateRow);
        } else {
            List<ServerModels.Subuser> subusers = new ArrayList<>(subuserCache);
            subusers.sort(Comparator.comparing(this::resolveSubuserSortKey));
            Map<String, PopupRow> next = new LinkedHashMap<>();
            for (ServerModels.Subuser subuser : subusers) {
                if (safe(subuser.uuid).isBlank()) continue;
                PopupRow row = subuserRows.get(subuser.uuid);
                if (row == null) row = new PopupRow.Builder("", createSubuserWidget(subuser)).build();
                MountableButtonWidget widget = (MountableButtonWidget) row.getWidgets().getFirst();
                widget.setName(resolveSubuserDisplayName(subuser));
                int permissionCount = subuser.permissions == null ? 0 : subuser.permissions.size();
                List<String> descriptionParts = new ArrayList<>();
                if (subuser.restudioUser != null && !safe(subuser.restudioUser.username).isBlank()) descriptionParts.add("@" + subuser.restudioUser.username);
                descriptionParts.add(permissionCount + " Permissions");
                widget.setDescription(String.join(" \u00b7 ", descriptionParts));
                widget.setHiddenText("Created " + (safe(subuser.createdAt).isBlank() ? "Unknown" : formatDateTime(subuser.createdAt)));
                next.put(subuser.uuid, row);
                rows.add(row);
            }
            subuserRows.clear();
            subuserRows.putAll(next);
        }
        if (!setting.getRows().equals(rows)) setting.setRows(rows);
    }

    private void ensureDataLoaded() {
        if (loadingData && hasLoadTimedOut()) {
            loadingData = false;
            loadError = "Load timed out";
            updateLoadingState();
            ScreenManager.getInstance().execute(() -> {
                if (!closed) new Notification("Load Failed", loadError, Notification.Type.ERROR);
            });
        }
        if (!dataLoaded && !loadingData && safe(loadError).isBlank()) {
            loadData();
        }
    }

    private void loadData() {
        if (closed || loadingData) {
            return;
        }
        long requestId = ++loadRequestId;
        loadError = null;
        loadingData = true;
        loadStartedAt = System.currentTimeMillis();
        updateLoadingState();
        AsyncTools.schedule(TaskSchedulers.current(), Duration.ofMillis(LOAD_TIMEOUT_MS), () ->
                ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (!loadingData || requestId != loadRequestId) {
                        return;
                    }
                    loadingData = false;
                    loadError = "Load timed out";
                    loadStartedAt = 0L;
                    updateLoadingState();
                    new Notification("Load Failed", loadError, Notification.Type.ERROR);
                    refreshSubusers();
                }));
        AsyncTools.withTimeout(AsyncTools.combine(subuserFeature.getSubusers(), subuserFeature.getSystemPermissions(), (subusers, permissions) -> {
            List<ServerModels.Subuser> safeSubusers = subusers == null ? new ArrayList<>() : new ArrayList<>(subusers);
            Map<String, ServerModels.PermissionCategory> safeCategories = normalizeCategories(permissions);
            return new LoadedData(safeSubusers, safeCategories);
        }), TaskSchedulers.current(), Duration.ofSeconds(20)).whenComplete((loadedData, error) ->
                ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (requestId != loadRequestId) {
                        return;
                    }
                    if (error == null && loadedData != null) {
                        subuserCache.clear();
                        subuserCache.addAll(loadedData.subusers());
                        permissionCategoryCache.clear();
                        permissionCategoryCache.putAll(loadedData.categories());
                        dataLoaded = true;
                        loadError = null;
                    } else {
                        loadError = sanitizeError(error);
                        new Notification("Load Failed", loadError, Notification.Type.ERROR);
                    }
                    loadingData = false;
                    loadStartedAt = 0L;
                    updateLoadingState();
                    refreshSubusers();
                }));
    }

    private String resolveSubuserDisplayName(ServerModels.Subuser subuser) {
        if (subuser.restudioUser != null && !safe(subuser.restudioUser.displayName).isBlank()) {
            return subuser.restudioUser.displayName;
        }
        if (!safe(subuser.username).isBlank()) {
            return subuser.username;
        }
        return "Pending Invite";
    }

    private String resolveSubuserSortKey(ServerModels.Subuser subuser) {
        if (subuser.restudioUser != null && !safe(subuser.restudioUser.displayName).isBlank()) {
            return subuser.restudioUser.displayName.toLowerCase(Locale.ROOT);
        }
        if (!safe(subuser.username).isBlank()) {
            return subuser.username.toLowerCase(Locale.ROOT);
        }
        return safe(subuser.uuid).toLowerCase(Locale.ROOT);
    }

    private MountableButtonWidget createSubuserWidget(ServerModels.Subuser subuser) {
            String name = resolveSubuserDisplayName(subuser);

            int permissionCount = subuser.permissions == null ? 0 : subuser.permissions.size();
            List<String> descriptionParts = new ArrayList<>();
            if (subuser.restudioUser != null && !safe(subuser.restudioUser.username).isBlank()) {
                descriptionParts.add("@" + subuser.restudioUser.username);
            }
            descriptionParts.add(permissionCount + " Permissions");
            String description = String.join(" \u00b7 ", descriptionParts);
            String createdAt = safe(subuser.createdAt).isBlank() ? "Unknown" : formatDateTime(subuser.createdAt);

            SquareButtonWidget editButton = new SquareButtonWidget.Builder()
                    .imagePath("edit.png")
                    .hint("Edit Permissions")
                    .onClick(() -> showEditPopup(currentSubuser(subuser.uuid), permissionCategoryCache))
                    .accentType(ThemeManager.getAccent("nice"))
                    .size(18, 18)
                    .build();

            SquareButtonWidget deleteButton = new SquareButtonWidget.Builder()
                    .imagePath("delete.png")
                    .hint("Delete Subuser")
                    .onClick(() -> showDeletePopup(currentSubuser(subuser.uuid)))
                    .accentType(ThemeManager.getAccent("danger"))
                    .size(18, 18)
                    .build();

            MountableButtonWidget row = new MountableButtonWidget.Builder(name)
                    .description(description)
                    .hiddenText("Created " + createdAt)
                    .addButton(editButton)
                    .addButton(deleteButton)
                    .build();
            return row;
    }

    private ServerModels.Subuser currentSubuser(String uuid) {
        for (ServerModels.Subuser subuser : subuserCache) {
            if (uuid != null && uuid.equals(subuser.uuid)) return subuser;
        }
        return new ServerModels.Subuser();
    }

    private void showCreatePopup(Map<String, ServerModels.PermissionCategory> categories) {
        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null) {
            return;
        }

        PopupWidget.Builder popupBuilder = new PopupWidget.Builder("Add Subuser")
                .pos(50, currentScreen.height / 6)
                .size(420, 360)
                .setResizable(true)
                .setMinSize(360, 260);

        TextInputWidget emailInput = new TextInputWidget.Builder()
                .placeholder("Username")
                .search(true)
                .size(200, 20)
                .build();

        AnimatedButton searchButton = new AnimatedButton.Builder()
                .label("Search")
                .size(70, 20)
                .onClick(() -> performUserSearch(emailInput))
                .build();

        popupBuilder.addRow("email", "User", emailInput, searchButton);

        Map<String, ToggleWidget> permissionToggles = new LinkedHashMap<>();
        ScrollSelectorWidget categorySelector = buildPermissionRows(popupBuilder, categories, permissionToggles, Set.of());

        popupBuilder.addTitleAction("Add", () -> {
            String email = safe(emailInput.getText()).trim();
            if (email.isBlank()) {
                new Notification("Invalid Input", "Username is required", Notification.Type.WARN);
                return;
            }

            List<String> permissions = collectPermissions(permissionToggles);
            popupBuilder.getWidget().setVisible(false);
            createSubuser(email, permissions);
        }, PopupWidget.TitleActionRole.PRIMARY);

        PopupWidget popup = popupBuilder.build();
        if (categorySelector != null) {
            categorySelector.setOnChange(() -> applyCategoryVisibility(popup, categories, categorySelector.getSelectedIndex()));
        }

        currentScreen.addDrawableChild(popup);
        popup.show();
        applyCategoryVisibility(popup, categories, 0);
    }

    private void performUserSearch(TextInputWidget emailInput) {
        if (closed) return;
        String query = safe(emailInput.getText()).trim();
        if (query.length() < 2) {
            new Notification("Search", "Enter at least 2 characters", Notification.Type.WARN);
            return;
        }

        loadingAction = true;
        updateLoadingState();
        subuserFeature.searchUsers(query)
                .thenAccept(results -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    loadingAction = false;
                    updateLoadingState();
                    showSearchResults(results, emailInput);
                }))
                .exceptionally(error -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        loadingAction = false;
                        updateLoadingState();
                        new Notification("Search Failed", sanitizeError(error), Notification.Type.ERROR);
                    });
                    return null;
                });
    }

    private void showSearchResults(List<ServerModels.ReStudioUserInfo> results, TextInputWidget emailInput) {
        if (results.isEmpty()) {
            new Notification("No Users Found", "Try a different search term", Notification.Type.INFO);
            return;
        }

        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null) {
            return;
        }

        int popupHeight = Math.min(60 + results.size() * 28, 300);
        PopupWidget.Builder searchPopupBuilder = new PopupWidget.Builder("Select User")
                .size(340, popupHeight)
                .setResizable(true)
                .setMinSize(280, 120);

        for (ServerModels.ReStudioUserInfo user : results) {
            String displayName = safe(user.displayName);
            String username = safe(user.username);
            String label = displayName.isBlank() ? "User" : displayName;
            if (!username.isBlank()) {
                label += " @" + username;
            }

            MountableButtonWidget resultRow = new MountableButtonWidget.Builder(label)
                    .onClick(() -> {
                        if (!username.isBlank()) {
                            emailInput.setText(username);
                        }
                        searchPopupBuilder.getWidget().setVisible(false);
                    })
                    .build();
            searchPopupBuilder.addRow("", resultRow);
        }

        PopupWidget searchPopup = searchPopupBuilder.build();
        currentScreen.addDrawableChild(searchPopup);
        searchPopup.show();
    }

    private void showEditPopup(ServerModels.Subuser subuser, Map<String, ServerModels.PermissionCategory> categories) {
        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null) {
            return;
        }

        PopupWidget.Builder popupBuilder = new PopupWidget.Builder("Edit Subuser")
                .pos(50, currentScreen.height / 6)
                .size(420, 360)
                .setResizable(true)
                .setMinSize(360, 260);

        String identity = resolveSubuserDisplayName(subuser);
        popupBuilder.addRow("identity", "Subuser", new AnimatedButton.Builder().label(identity).active(false).build());

        Set<String> selectedPermissions = new LinkedHashSet<>();
        if (subuser.permissions != null) {
            selectedPermissions.addAll(subuser.permissions);
        }

        Map<String, ToggleWidget> permissionToggles = new LinkedHashMap<>();
        ScrollSelectorWidget categorySelector = buildPermissionRows(popupBuilder, categories, permissionToggles, selectedPermissions);

        popupBuilder.addTitleAction("Update", () -> {
            List<String> permissions = collectPermissions(permissionToggles);
            popupBuilder.getWidget().setVisible(false);
            updateSubuser(subuser, permissions);
        }, PopupWidget.TitleActionRole.PRIMARY);

        PopupWidget popup = popupBuilder.build();
        if (categorySelector != null) {
            categorySelector.setOnChange(() -> applyCategoryVisibility(popup, categories, categorySelector.getSelectedIndex()));
        }

        currentScreen.addDrawableChild(popup);
        popup.show();
        applyCategoryVisibility(popup, categories, 0);
    }

    private ScrollSelectorWidget buildPermissionRows(PopupWidget.Builder popupBuilder,
                                                     Map<String, ServerModels.PermissionCategory> categories,
                                                     Map<String, ToggleWidget> permissionToggles,
                                                     Set<String> selectedPermissions) {
        if (categories.isEmpty()) {
            popupBuilder.addRow("permissions-empty", "Permissions", new AnimatedButton.Builder().label("No permissions available").active(false).build());
            return null;
        }

        List<String> categoryKeys = new ArrayList<>(categories.keySet());
        List<String> categoryLabels = categoryKeys.stream()
                .map(key -> {
                    ServerModels.PermissionCategory category = categories.get(key);
                    int permissionCount = category == null || category.keys == null ? 0 : category.keys.size();
                    return toTitle(key) + " (" + permissionCount + ")";
                })
                .toList();

        ScrollSelectorWidget categorySelector = new ScrollSelectorWidget.Builder()
                .options(categoryLabels)
                .selectedIndex(0)
                .size(300, 18)
                .build();
        popupBuilder.addRow("permissions-categories", "Category", categorySelector);

        for (String categoryKey : categoryKeys) {
            ServerModels.PermissionCategory category = categories.get(categoryKey);
            if (category == null || category.keys == null || category.keys.isEmpty()) {
                popupBuilder.addRow(new PopupWidget.PopupRow.Builder("No permissions", new AnimatedButton.Builder().label("Empty").active(false).size(80, 18).build())
                        .id(permissionGroupRowId(categoryKey, "empty")).alignRight().build());
                continue;
            }

            List<Map.Entry<String, String>> sortedPermissions = new ArrayList<>(category.keys.entrySet());
            sortedPermissions.sort((a, b) -> formatPermissionLabel(categoryKey, a.getKey()).compareToIgnoreCase(formatPermissionLabel(categoryKey, b.getKey())));

            for (Map.Entry<String, String> entry : sortedPermissions) {
                String permissionKey = normalizePermissionKey(categoryKey, entry.getKey());
                if (permissionKey.isBlank()) {
                    continue;
                }
                String permissionLabel = formatPermissionLabel(categoryKey, entry.getKey());
                String permissionDescription = safe(entry.getValue());

                ToggleWidget toggle = new ToggleWidget.Builder()
                        .toggled(selectedPermissions.contains(permissionKey))
                        .size(44, 18)
                        .build();
                permissionToggles.put(permissionKey, toggle);

                MountableButtonWidget.Builder permissionRowBuilder = new MountableButtonWidget.Builder(permissionLabel)
                        .hiddenText(permissionKey)
                        .addWidget(toggle);
                if (!permissionDescription.isBlank()) {
                    permissionRowBuilder.description(permissionDescription);
                }
                MountableButtonWidget permissionRow = permissionRowBuilder.build();
                popupBuilder.addRow(new PopupWidget.PopupRow.Builder("", permissionRow).id(permissionGroupRowId(categoryKey, permissionKey)).build());
            }
        }

        return categorySelector;
    }

    private void applyCategoryVisibility(PopupWidget popup, Map<String, ServerModels.PermissionCategory> categories, int selectedIndex) {
        if (popup == null || categories.isEmpty()) {
            return;
        }

        List<String> categoryKeys = new ArrayList<>(categories.keySet());
        int safeIndex = Math.max(0, Math.min(selectedIndex, categoryKeys.size() - 1));
        String selectedCategory = categoryKeys.get(safeIndex);

        for (String categoryKey : categoryKeys) {
            ServerModels.PermissionCategory category = categories.get(categoryKey);
            if (category == null || category.keys == null || category.keys.isEmpty()) {
                popup.setRowVisibility(permissionGroupRowId(categoryKey, "empty"), categoryKey.equals(selectedCategory));
                continue;
            }

            boolean visible = categoryKey.equals(selectedCategory);
            for (String permissionKey : category.keys.keySet()) {
                popup.setRowVisibility(permissionGroupRowId(categoryKey, normalizePermissionKey(categoryKey, permissionKey)), visible);
            }
        }
    }

    private void showDeletePopup(ServerModels.Subuser subuser) {
        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null) {
            return;
        }

        PopupWidget.Builder popupBuilder = new PopupWidget.Builder("Delete Subuser")
                .width(320)
                .setResizable(false);

        String identity = resolveSubuserDisplayName(subuser);
        if (identity.equals("Pending Invite")) {
            identity = safe(subuser.uuid).isBlank() ? "Subuser" : subuser.uuid;
        }

        AnimatedButton deleteButton = new AnimatedButton.Builder()
                .label("Delete")
                .accentType(ThemeManager.getAccent("danger"))
                .onClick(() -> {
                    popupBuilder.getWidget().setVisible(false);
                    deleteSubuser(subuser);
                })
                .build();

        popupBuilder.addRow(new PopupWidget.PopupRow.Builder("Delete " + identity + "?").id("confirm").build());
        popupBuilder.addTitleAction("Delete", () -> deleteButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.DESTRUCTIVE);

        PopupWidget popup = popupBuilder.build();
        currentScreen.addDrawableChild(popup);
        popup.show();
    }

    private void createSubuser(String userIdentifier, List<String> permissions) {
        if (closed) return;
        loadingAction = true;
        updateLoadingState();
        subuserFeature.createSubuser(userIdentifier, permissions)
                .thenAccept(subuser -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (subuser == null) {
                        new Notification("Add Failed", "Server returned an error", Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                        return;
                    }
                    playSound(Sound.SUCCESS);
                    String name = subuser.restudioUser != null ? safe(subuser.restudioUser.displayName) : userIdentifier;
                    new Notification("Subuser Added", name.isBlank() ? userIdentifier : name, Notification.Type.SUCCESS);
                    upsertSubuser(subuser);
                    refreshSubusers();
                    loadData();
                    loadingAction = false;
                    updateLoadingState();
                }))
                .exceptionally(error -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        new Notification("Add Failed", sanitizeError(error), Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                    });
                    return null;
                });
    }

    private void updateSubuser(ServerModels.Subuser subuser, List<String> permissions) {
        if (closed) return;
        loadingAction = true;
        updateLoadingState();
        subuserFeature.updateSubuser(subuser.uuid, permissions)
                .thenAccept(updated -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (updated == null) {
                        new Notification("Update Failed", "Server returned an error", Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                        return;
                    }
                    playSound(Sound.SUCCESS);
                    String name = resolveSubuserDisplayName(updated);
                    new Notification("Permissions Updated", name, Notification.Type.SUCCESS);
                    upsertSubuser(updated);
                    refreshSubusers();
                    loadData();
                    loadingAction = false;
                    updateLoadingState();
                }))
                .exceptionally(error -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        new Notification("Update Failed", sanitizeError(error), Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                    });
                    return null;
                });
    }

    private void deleteSubuser(ServerModels.Subuser subuser) {
        if (closed) return;
        loadingAction = true;
        updateLoadingState();
        subuserFeature.deleteSubuser(subuser.uuid)
                .thenRun(() -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    playSound(Sound.SUCCESS);
                    String name = resolveSubuserDisplayName(subuser);
                    new Notification("Subuser Deleted", name, Notification.Type.INFO);
                    removeSubuser(subuser.uuid);
                    refreshSubusers();
                    loadData();
                    loadingAction = false;
                    updateLoadingState();
                }))
                .exceptionally(error -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        new Notification("Delete Failed", sanitizeError(error), Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                    });
                    return null;
                });
    }

    private void refreshSubusers() {
        if (closed) return;
        refreshRows();
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
            settingsScreen.refreshTab(SUBUSERS_TAB);
        }
    }

    private List<String> collectPermissions(Map<String, ToggleWidget> toggles) {
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, ToggleWidget> entry : toggles.entrySet()) {
            if (entry.getValue().getValue()) {
                selected.add(entry.getKey());
            }
        }
        selected.sort(String::compareToIgnoreCase);
        return selected;
    }

    private Map<String, ServerModels.PermissionCategory> normalizeCategories(ServerModels.SystemPermissions permissions) {
        if (permissions == null || permissions.permissions == null || permissions.permissions.isEmpty()) {
            return Map.of();
        }

        List<String> sortedKeys = new ArrayList<>(permissions.permissions.keySet());
        sortedKeys.sort(String::compareToIgnoreCase);

        Map<String, ServerModels.PermissionCategory> normalized = new LinkedHashMap<>();
        for (String key : sortedKeys) {
            normalized.put(key, permissions.permissions.get(key));
        }
        return normalized;
    }

    private void setLoading(boolean loading) {
        if (parentScreen != null) {
            parentScreen.setLoading(loading);
        }
    }

    private void updateLoadingState() {
        setLoading(loadingData || loadingAction);
    }

    private void upsertSubuser(ServerModels.Subuser subuser) {
        if (subuser == null || safe(subuser.uuid).isBlank()) {
            return;
        }
        for (int i = 0; i < subuserCache.size(); i++) {
            ServerModels.Subuser existing = subuserCache.get(i);
            if (subuser.uuid.equals(existing.uuid)) {
                subuserCache.set(i, subuser);
                return;
            }
        }
        subuserCache.add(subuser);
    }

    private void removeSubuser(String uuid) {
        if (safe(uuid).isBlank()) {
            return;
        }
        subuserCache.removeIf(subuser -> uuid.equals(subuser.uuid));
    }

    private String permissionGroupRowId(String category, String key) {
        return "perm-" + sanitizeId(category) + "-" + sanitizeId(key);
    }

    private String normalizePermissionKey(String categoryKey, String rawPermissionKey) {
        String key = safe(rawPermissionKey).trim();
        if (key.isBlank()) {
            return "";
        }
        if ("*".equals(key) || key.contains(".")) {
            return key;
        }
        return categoryKey + "." + key;
    }

    private String formatPermissionLabel(String categoryKey, String rawPermissionKey) {
        String key = normalizePermissionKey(categoryKey, rawPermissionKey);
        if (key.isBlank()) {
            return "Unknown";
        }
        int dotIndex = key.indexOf('.');
        String token = dotIndex >= 0 && dotIndex < key.length() - 1 ? key.substring(dotIndex + 1) : key;
        return toTitle(token);
    }

    private String sanitizeId(String value) {
        String safe = safe(value);
        if (safe.isBlank()) {
            return "none";
        }
        return safe.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private String formatDateTime(String isoDateTime) {
        try {
            ZonedDateTime dateTime = ZonedDateTime.parse(isoDateTime);
            return DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm").format(dateTime);
        } catch (DateTimeParseException ignored) {
            return isoDateTime;
        }
    }

    private String sanitizeError(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return safe(root.getMessage()).isBlank() ? "Unknown error" : root.getMessage();
    }

    private boolean hasLoadTimedOut() {
        return loadStartedAt > 0L && System.currentTimeMillis() - loadStartedAt > LOAD_TIMEOUT_MS;
    }

    private String toTitle(String value) {
        String source = safe(value).replace('_', ' ').replace('-', ' ').trim();
        if (source.isBlank()) {
            return "Unknown";
        }
        String[] parts = source.split("\\s+");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            normalized.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return String.join(" ", normalized);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record LoadedData(List<ServerModels.Subuser> subusers, Map<String, ServerModels.PermissionCategory> categories) { }
}
