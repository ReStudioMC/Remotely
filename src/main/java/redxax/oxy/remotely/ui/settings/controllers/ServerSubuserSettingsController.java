package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;

import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.restudio.collaboration.CollaborationModels.Person;
import restudio.rebase.restudio.community.ReStudioCommunityProvider;
import restudio.rebase.ui.screens.collaboration.AccessDashboard;
import restudio.rebase.ui.screens.collaboration.ConfirmButton;
import restudio.rebase.ui.screens.collaboration.PermissionEditor;
import restudio.rebase.ui.screens.collaboration.PersonPicker;
import restudio.rebase.restudio.community.ReStudioCommunityProviders;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.WidgetCleanup;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static restudio.rescreen.util.SoundUtils.playSound;

public class ServerSubuserSettingsController {
    private static final String SUBUSERS_TAB = "Subusers";
    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final Supplier<Screen> popupOwner;
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
    private AccessDashboard dashboard;
    private PopupWidget popup;
    private long popupStamp;
    private PersonPicker people;
    private IconButton addSubuserButton;
    private IconButton reloadButton;
    private PopupRow addRow;
    private PopupRow stateRow;
    private PopupRow retryRow;
    private PopupRow unavailableRow;
    private final Map<String, PopupRow> subuserRows = new LinkedHashMap<>();

    public ServerSubuserSettingsController(ReScreen parentScreen, SubuserSettingsProvider subuserFeature) {
        this(parentScreen, subuserFeature, () -> parentScreen);
    }

    public ServerSubuserSettingsController(ReScreen parentScreen, SubuserSettingsProvider subuserFeature, Supplier<Screen> popupOwner) {
        this.popupOwner = popupOwner;
        this.subuserFeature = subuserFeature == null ? SubuserSettingsProvider.unavailable("Subuser Feature Is Unavailable") : subuserFeature;
    }

    public void cleanup() {
        closed = true;
        if (dashboard != null) dashboard.cleanup();
        closePopup();
        subuserRows.values().forEach(row -> row.getWidgets().forEach(WidgetCleanup::cleanup));
        subuserRows.clear();
        loadRequestId++;
        loadingData = false;
        loadingAction = false;
    }

    public List<Setting> getSettings() {
        if (!subuserFeature.collaborationResourceId().isBlank()) {
            if (dashboard == null && !closed) dashboard = new AccessDashboard(popupOwner, "SERVER", subuserFeature.collaborationResourceId(), ReStudioCommunityProviders.current(),
                    () -> subuserFeature.getSystemPermissions().thenApply(this::normalizeCategories));
            return dashboard == null ? List.of() : List.of(dashboard.setting());
        }
        if (closed) return setting == null ? List.of() : List.of(setting);
        if (setting != null) {
            if (subuserFeature.available()) ensureDataLoaded();
            return List.of(setting);
        }
        if (subuserFeature.available()) ensureDataLoaded();

        Setting.Builder builder = new Setting.Builder("Server Subusers");
        addSubuserButton = new IconButton.Builder().imagePath("add.png").hint("Add Subuser").size(18, 18).accentType(null)
                .onClick(() -> showCreatePopup(permissionCategoryCache)).active(dataLoaded).build();
        reloadButton = new IconButton.Builder().imagePath("reload.png").hint("Refresh Subusers").size(18, 18).accentType(null).onClick(this::loadData).build();
        builder.addRow("overview", "", new MountableButtonWidget.Builder("Subusers").iconPath("twoPersons.png")
                .description("Share Server Features And Edit Their Permissions").addWidget(addSubuserButton).addWidget(reloadButton).build());
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
        addSubuserButton.setActive(dataLoaded && !loadingAction && !loadingData);
        reloadButton.setActive(!loadingAction && !loadingData);
        List<PopupRow> rows = new ArrayList<>();
        rows.add(addRow);
        if (!dataLoaded) {
            ((AnimatedButton) stateRow.getWidgets().getFirst()).setMessage(loadingData ? "Loading Subusers" : safe(loadError).isBlank() ? "Subusers Unavailable" : "Load Failed");
            rows.add(stateRow);
            if (!safe(loadError).isBlank()) rows.add(retryRow);
        } else if (subuserCache.isEmpty()) {
            subuserRows.values().forEach(row -> row.getWidgets().forEach(WidgetCleanup::cleanup));
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
            subuserRows.forEach((id, row) -> { if (!next.containsKey(id)) row.getWidgets().forEach(WidgetCleanup::cleanup); });
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

            IconButton editButton = new IconButton.Builder()
                    .imagePath("edit.png")
                    .hint("Edit Permissions")
                    .onClick(() -> showEditPopup(currentSubuser(subuser.uuid), permissionCategoryCache))
                    .accentType(null)
                    .size(18, 18)
                    .build();

            ConfirmButton deleteButton = new ConfirmButton("delete.png", "Delete Subuser", () -> !closed && !loadingAction,
                    () -> deleteSubuser(currentSubuser(subuser.uuid)));

            MountableButtonWidget row = new MountableButtonWidget.Builder(name)
                    .description(description)
                    .hiddenText("Created " + createdAt)
                    .addWidget(editButton)
                    .addWidget(deleteButton)
                    .build();
            return row;
    }

    private ServerModels.Subuser currentSubuser(String uuid) {
        for (ServerModels.Subuser subuser : subuserCache) {
            if (uuid != null && uuid.equals(subuser.uuid)) return subuser;
        }
        return new ServerModels.Subuser();
    }

    private PopupWidget.Builder form(String title) {
        if (popup == null) { popup = new PopupWidget.Builder(title).build(); popup.setVisible(false); }
        popupStamp++;
        if (people != null) { people.cleanup(); people = null; }
        popup.getRows().forEach(row -> row.getWidgets().forEach(WidgetCleanup::cleanup));
        popup.clearFocus(); popup.clearRows(); popup.clearTitleActions(); popup.setTitle(title);
        return new PopupWidget.Builder(popup).width(Math.min(460, Math.max(280, popupOwner.get().width - 30))).setMinSize(280, 0)
                .setExpandWithDropdowns(true).setAntiOutOfBound(true).setResizable(false).virtualizeRows(true).padding(5).rowGap(4).onClose(this::closePopup);
    }

    private void show(PopupWidget.Builder builder) {
        boolean opening = !popup.isVisible();
        popup = builder.build(); popup.setOwnerScreen(popupOwner.get()); popup.setAnimateLayout(false);
        popup.fitContentHeight(Math.max(120, ScreenManager.getInstance().getDesktopWorkArea().height() - 20));
        popup.centerOnOwner();
        if (opening) popup.show();
        else { popup.setVisible(true); popup.active = true; }
    }

    private void closePopup() {
        popupStamp++;
        if (people != null) { people.cleanup(); people = null; }
        if (popup == null) return;
        popup.getRows().forEach(row -> row.getWidgets().forEach(WidgetCleanup::cleanup));
        popup.hide(); popup = null;
    }

    private void closePopup(PopupWidget owner, long stamp) {
        if (popup == owner && popupStamp == stamp) closePopup();
    }

    private void showCreatePopup(Map<String, ServerModels.PermissionCategory> categories) {
        if (closed || loadingAction || !dataLoaded || popupOwner.get() == null) return;
        Screen screen = popupOwner.get();
        ReStudioCommunityProvider provider = ReStudioCommunityProviders.current();
        String account = provider.userId();
        boolean authenticated = provider.isAuthenticated();
        PopupWidget.Builder builder = form("Add Subuser");
        PopupWidget owner = popup;
        long stamp = popupStamp;
        BooleanSupplier current = () -> !closed && popup == owner && popupStamp == stamp && owner.isVisible() && popupOwner.get() == screen
                && ReStudioCommunityProviders.current() == provider && authenticated == provider.isAuthenticated() && account.equals(provider.userId());
        TextInputWidget user = new TextInputWidget(0, 0, 260, 20) {
            @Override
            public void tick() {
                super.tick();
                if (people != null) people.tick();
            }
        };
        user.placeholder = "Username";
        user.setMaxLength(80);
        user.setSearch(true);
        IconButton search = new IconButton.Builder().imagePath("search.png").hint("Search Users").size(18, 18)
                .onClick(() -> { if (people != null && !loadingAction) people.submit(); }).build();
        builder.addRow("user", "User", user, search);
        PermissionEditor editor = new PermissionEditor(builder, categories, null, Set.of());
        builder.addTitleAction("Add", () -> {
            if (!current.getAsBoolean() || loadingAction) return;
            String username = safe(user.getText()).trim();
            if (username.isBlank()) { new Notification("Enter A Username", "Choose Someone To Share With", Notification.Type.WARN); return; }
            createSubuser(username, editor.selected());
        }, PopupWidget.TitleActionRole.PRIMARY);
        show(builder); editor.attach(popup);
        people = new PersonPicker(owner, user, provider, current, this::personUnavailable, person -> {
            if (!current.getAsBoolean() || loadingAction) return;
            user.setText(person.username());
            people.hideResults();
        }, () -> owner.fitContentHeight(Math.max(120, ScreenManager.getInstance().getDesktopWorkArea().height() - 20)),
                query -> subuserFeature.searchUsers(query).thenApply(results -> results == null ? List.of() : results.stream()
                        .filter(person -> person != null)
                        .map(person -> new Person(safe(person.id), safe(person.username), safe(person.displayName), safe(person.avatarUrl))).toList()));
        people.start();
    }

    private String personUnavailable(Person person) {
        if (person.username().isBlank()) return "Username Is Unavailable";
        for (ServerModels.Subuser subuser : subuserCache) {
            if (person.username().equalsIgnoreCase(safe(subuser.username)) || subuser.restudioUser != null
                    && (person.id().equals(safe(subuser.restudioUser.id)) || person.username().equalsIgnoreCase(safe(subuser.restudioUser.username)))) return "Already Added";
        }
        return "";
    }

    private void showEditPopup(ServerModels.Subuser subuser, Map<String, ServerModels.PermissionCategory> categories) {
        if (closed || loadingAction || safe(subuser.uuid).isBlank() || popupOwner.get() == null) return;
        PopupWidget.Builder builder = form("Edit Subuser");
        builder.addRow("identity", "", new MountableButtonWidget.Builder(resolveSubuserDisplayName(subuser)).iconPath("person.png").description("Choose The Server Features They Can Use").build());
        Set<String> selected = subuser.permissions == null ? Set.of() : new LinkedHashSet<>(subuser.permissions);
        PermissionEditor editor = new PermissionEditor(builder, categories, null, selected);
        builder.addTitleAction("Update", () -> { if (!closed && !loadingAction) updateSubuser(subuser, editor.selected()); }, PopupWidget.TitleActionRole.PRIMARY);
        show(builder); editor.attach(popup);
    }

    private void createSubuser(String userIdentifier, List<String> permissions) {
        if (closed || loadingAction) return;
        PopupWidget owner = popup; long stamp = popupStamp;
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
                    closePopup(owner, stamp);
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
        if (closed || loadingAction) return;
        PopupWidget owner = popup; long stamp = popupStamp;
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
                    closePopup(owner, stamp);
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
        if (closed || loadingAction || safe(subuser.uuid).isBlank()) return;
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
        if (popupOwner.get() instanceof SettingsScreen settings) settings.refreshTab(SUBUSERS_TAB);
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
        if (popupOwner.get() instanceof ReScreen screen) screen.setLoading(loading);
    }

    private void updateLoadingState() {
        setLoading(loadingData || loadingAction);
        if (addSubuserButton != null) addSubuserButton.setActive(dataLoaded && !loadingAction && !loadingData);
        if (reloadButton != null) reloadButton.setActive(!loadingAction && !loadingData);
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record LoadedData(List<ServerModels.Subuser> subusers, Map<String, ServerModels.PermissionCategory> categories) { }
}
