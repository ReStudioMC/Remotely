package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.RemotelyCapabilityException;
import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;

import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.Duration;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;


import static restudio.rescreen.util.SoundUtils.playSound;

public class ServerNetworkSettingsController {
    private static final String NETWORK_TAB = "Network";
    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final ReScreen parentScreen;
    private final PortManagementSettingsProvider portFeature;
    private final List<ServerModels.Allocation> allocationCache = new ArrayList<>();
    private volatile boolean allocationsLoaded;
    private volatile boolean loadingAllocations;
    private volatile boolean loadingAction;
    private volatile String loadError;
    private volatile long loadStartedAt;
    private volatile long loadRequestId;
    private volatile boolean closed;
    private Setting setting;
    private AnimatedButton createPortButton;
    private PopupRow createRow;
    private PopupRow stateRow;
    private PopupRow retryRow;
    private PopupRow unavailableRow;
    private final Map<Integer, PopupRow> allocationRows = new LinkedHashMap<>();

    public ServerNetworkSettingsController(ReScreen parentScreen, PortManagementSettingsProvider portFeature) {
        this.parentScreen = parentScreen;
        this.portFeature = portFeature == null ? PortManagementSettingsProvider.unavailable("Network Feature Is Unavailable") : portFeature;
    }

    public void cleanup() {
        closed = true;
        loadRequestId++;
        loadingAllocations = false;
        loadingAction = false;
    }

    public List<Setting> getSettings() {
        if (closed) return setting == null ? List.of() : List.of(setting);
        if (setting != null) {
            if (portFeature.available()) ensureAllocationsLoaded();
            refreshRows();
            return List.of(setting);
        }
        if (portFeature.available()) ensureAllocationsLoaded();

        Setting.Builder builder = new Setting.Builder("Server Network");
        createPortButton = new AnimatedButton.Builder()
                .label("Add Port")
                .accentType(ThemeManager.getAccent("nice"))
                .onClick(this::createAllocation)
                .active(allocationsLoaded && !loadingAction)
                .build();
        builder.addRow("", createPortButton);
        setting = builder.build();
        createRow = setting.getRows().getFirst();
        stateRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Loading Ports").active(false).build()).build();
        retryRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Retry Load")
                .accentType(ThemeManager.getDefaultAccent()).onClick(this::loadAllocations).build()).build();
        unavailableRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Network Feature Unavailable")
                .active(false).hint(portFeature.unavailableReason()).build()).build();
        refreshRows();
        return List.of(setting);
    }

    private void refreshRows() {
        if (setting == null) return;
        if (!portFeature.available()) {
            ((AnimatedButton) unavailableRow.getWidgets().getFirst()).setHint(portFeature.unavailableReason());
            if (!setting.getRows().equals(List.of(unavailableRow))) setting.setRows(List.of(unavailableRow));
            return;
        }
        createPortButton.setActive(allocationsLoaded && !loadingAction);
        List<PopupRow> rows = new ArrayList<>();
        rows.add(createRow);
        if (!allocationsLoaded) {
            ((AnimatedButton) stateRow.getWidgets().getFirst()).setMessage(loadingAllocations ? "Loading Ports" : safe(loadError).isBlank() ? "Ports Unavailable" : "Load Failed");
            rows.add(stateRow);
            if (!safe(loadError).isBlank()) rows.add(retryRow);
        } else if (allocationCache.isEmpty()) {
            allocationRows.clear();
            ((AnimatedButton) stateRow.getWidgets().getFirst()).setMessage("No Ports Found");
            rows.add(stateRow);
        } else {
            List<ServerModels.Allocation> allocations = new ArrayList<>(allocationCache);
            allocations.sort(Comparator.comparing((ServerModels.Allocation item) -> !item.isDefault)
                    .thenComparing(item -> item.port == null ? Integer.MAX_VALUE : item.port));
            Map<Integer, PopupRow> next = new LinkedHashMap<>();
            for (ServerModels.Allocation allocation : allocations) {
                if (allocation.id == null) continue;
                PopupRow row = allocationRows.get(allocation.id);
                if (row == null) {
                    row = new PopupRow.Builder("", createAllocationWidget(allocation)).build();
                } else {
                    MountableButtonWidget widget = (MountableButtonWidget) row.getWidgets().getFirst();
                    widget.setName(formatAllocation(allocation));
                    widget.setDescription(allocation.isDefault ? "Primary Port" : "Additional Port");
                    widget.setHiddenText(safe(allocation.notes).isBlank() ? "No Notes" : allocation.notes);
                }
                ((SquareButtonWidget) ((MountableButtonWidget) row.getWidgets().getFirst()).mountedWidgets.get(1)).setVisible(!allocation.isDefault);
                next.put(allocation.id, row);
                rows.add(row);
            }
            allocationRows.clear();
            allocationRows.putAll(next);
        }
        if (!setting.getRows().equals(rows)) setting.setRows(rows);
    }

    private void ensureAllocationsLoaded() {
        if (loadingAllocations && hasLoadTimedOut()) {
            loadingAllocations = false;
            loadError = "Load timed out";
            updateLoadingState();
            ScreenManager.getInstance().execute(() -> {
                if (!closed) new Notification("Load Failed", loadError, Notification.Type.ERROR);
            });
            refreshNetwork();
        }
        if (!allocationsLoaded && !loadingAllocations && safe(loadError).isBlank()) {
            loadAllocations();
        }
    }

    private void loadAllocations() {
        if (closed || loadingAllocations) {
            return;
        }
        long requestId = ++loadRequestId;
        loadError = null;
        loadingAllocations = true;
        loadStartedAt = System.currentTimeMillis();
        updateLoadingState();
        AsyncTools.withTimeout(portFeature.getAllocations(), TaskSchedulers.current(), Duration.ofSeconds(20))
                .whenComplete((allocations, error) -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (requestId != loadRequestId) {
                        return;
                    }
                    if (error == null) {
                        allocationCache.clear();
                        if (allocations != null) {
                            allocationCache.addAll(allocations);
                        }
                        allocationsLoaded = true;
                        loadError = null;
                    } else {
                        loadError = sanitizeError(error);
                        new Notification("Load Failed", loadError, Notification.Type.ERROR);
                    }
                    loadingAllocations = false;
                    loadStartedAt = 0L;
                    updateLoadingState();
                    refreshNetwork();
                }));
    }

    private MountableButtonWidget createAllocationWidget(ServerModels.Allocation allocation) {
        SquareButtonWidget editButton = new SquareButtonWidget.Builder()
                .imagePath("edit.png")
                .onClick(() -> showEditPopup(currentAllocation(allocation.id)))
                .hint("Edit Notes")
                .size(18, 18)
                .build();

        SquareButtonWidget deleteButton = new SquareButtonWidget.Builder()
                .imagePath("delete.png")
                .onClick(() -> deleteAllocation(currentAllocation(allocation.id)))
                .hint("Delete Port")
                .accentType(ThemeManager.getAccent("danger"))
                .size(18, 18)
                .build();

        String host = displayHost(allocation);
        String title = host + ":" + (allocation.port == null ? "Unknown" : allocation.port);
        String description = allocation.isDefault ? "Primary Port" : "Additional Port";
        String notes = safe(allocation.notes);
        String hiddenText = notes.isBlank() ? "No Notes" : notes;

        MountableButtonWidget.Builder builder = new MountableButtonWidget.Builder(title)
                .description(description)
                .hiddenText(hiddenText)
                .addButton(editButton);

        builder.addButton(deleteButton);
        MountableButtonWidget widget = builder.build();
        deleteButton.setVisible(!allocation.isDefault);
        return widget;
    }

    private ServerModels.Allocation currentAllocation(Integer id) {
        for (ServerModels.Allocation allocation : allocationCache) {
            if (id != null && id.equals(allocation.id)) return allocation;
        }
        return new ServerModels.Allocation();
    }

    private void showEditPopup(ServerModels.Allocation allocation) {
        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null) {
            return;
        }

        PopupWidget.Builder builder = new PopupWidget.Builder("Edit Port")
                .pos(50, currentScreen.height / 5)
                .width(300)
                .setResizable(false);

        TextInputWidget notesField = new TextInputWidget.Builder()
                .placeholder("Notes")
                .size(170, 20)
                .build();
        notesField.setText(safe(allocation.notes));
        builder.addRow("Notes", notesField);

        builder.addTitleAction("Save", () -> {
            playSound(Sound.CREATE);
            updateAllocation(allocation, notesField.getText(), false);
            builder.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.PRIMARY);

        PopupWidget popup = builder.build();
        currentScreen.addDrawableChild(popup);
        popup.show();
    }

    private void createAllocation() {
        if (closed || loadingAction) {
            return;
        }
        loadingAction = true;
        updateLoadingState();
        new Notification("Adding Port", "Requesting New Port", Notification.Type.INFO);
        portFeature.createAllocation()
                .thenAccept(allocation -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (allocation != null) {
                        upsertAllocation(allocation);
                        new Notification("Port Added", formatAllocation(allocation), Notification.Type.SUCCESS);
                    } else {
                        new Notification("Add Failed", "Server returned an error", Notification.Type.ERROR);
                    }
                    loadingAction = false;
                    updateLoadingState();
                    refreshNetwork();
                    loadAllocations();
                }))
                .exceptionally(e -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        new Notification("Add Failed", sanitizeAllocationError(e), Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                        refreshNetwork();
                    });
                    return null;
                });
    }

    private void updateAllocation(ServerModels.Allocation allocation, String notes, boolean primary) {
        if (closed || allocation.id == null || loadingAction) {
            return;
        }
        loadingAction = true;
        updateLoadingState();
        portFeature.updateAllocation(allocation.id, notes, primary)
                .thenAccept(updated -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    if (updated != null) {
                        if (primary) {
                            allocationCache.forEach(item -> item.isDefault = false);
                        }
                        upsertAllocation(updated);
                        new Notification("Port Updated", formatAllocation(updated), Notification.Type.SUCCESS);
                    } else {
                        new Notification("Update Failed", "Server returned an error", Notification.Type.ERROR);
                    }
                    loadingAction = false;
                    updateLoadingState();
                    refreshNetwork();
                    loadAllocations();
                }))
                .exceptionally(e -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        new Notification("Update Failed", sanitizeError(e), Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                        refreshNetwork();
                    });
                    return null;
                });
    }

    private void deleteAllocation(ServerModels.Allocation allocation) {
        if (closed || allocation.id == null || allocation.isDefault || loadingAction) {
            return;
        }
        playSound(Sound.DELETE);
        loadingAction = true;
        updateLoadingState();
        portFeature.deleteAllocation(allocation.id)
                .thenRun(() -> ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                    allocationCache.removeIf(item -> allocation.id.equals(item.id));
                    new Notification("Port Deleted", formatAllocation(allocation), Notification.Type.SUCCESS);
                    loadingAction = false;
                    updateLoadingState();
                    refreshNetwork();
                    loadAllocations();
                }))
                .exceptionally(e -> {
                    ScreenManager.getInstance().execute(() -> {
                    if (closed) return;
                        new Notification("Delete Failed", sanitizeError(e), Notification.Type.ERROR);
                        loadingAction = false;
                        updateLoadingState();
                        refreshNetwork();
                    });
                    return null;
                });
    }

    private void upsertAllocation(ServerModels.Allocation allocation) {
        if (allocation == null || allocation.id == null) {
            return;
        }
        allocationCache.removeIf(existing -> allocation.id.equals(existing.id));
        allocationCache.add(allocation);
    }

    private void refreshNetwork() {
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
            settingsScreen.refreshTab(NETWORK_TAB);
        }
    }

    private void updateLoadingState() {
        if (parentScreen != null) {
            parentScreen.setLoading(loadingAllocations || loadingAction);
        }
    }

    private boolean hasLoadTimedOut() {
        return loadStartedAt > 0L && System.currentTimeMillis() - loadStartedAt > LOAD_TIMEOUT_MS;
    }

    private String displayHost(ServerModels.Allocation allocation) {
        if (!safe(allocation.ipAlias).isBlank()) {
            return allocation.ipAlias;
        }
        if (!safe(allocation.ip).isBlank()) {
            return allocation.ip;
        }
        return "Unknown";
    }

    private String formatAllocation(ServerModels.Allocation allocation) {
        return displayHost(allocation) + ":" + (allocation.port == null ? "Unknown" : allocation.port);
    }

    private String sanitizeError(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 180 ? message.substring(0, 180) + "..." : message;
    }

    private String sanitizeAllocationError(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RemotelyCapabilityException failure
                && "remotely_web_allocation_limit_reached".equals(failure.code())) {
            return failure.getMessage();
        }
        String message = sanitizeError(throwable);
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.equals("browser capability failed with status 400")
                || normalized.contains("allocation limit")
                || normalized.contains("maximum network port")) {
            return "Maximum Network Ports Reached";
        }
        return message;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
