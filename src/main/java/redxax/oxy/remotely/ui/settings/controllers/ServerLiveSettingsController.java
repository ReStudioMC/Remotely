package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsProvider.LiveSettingValue;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class ServerLiveSettingsController {
    private final ServerLiveSettingsProvider provider;
    private AnimatedButton statusBadge;
    private Setting liveSettingsContainer;
    private final Map<String, PopupWidget.PopupRow> settingRows = new LinkedHashMap<>();
    private final Map<String, LiveSettingValue> settingValues = new LinkedHashMap<>();
    private final Map<String, String> shownValues = new LinkedHashMap<>();
    private PopupWidget.PopupRow statusRow;
    private boolean loading;
    private ServerLiveSettingsProvider.Subscription stateSubscription = ServerLiveSettingsProvider.Subscription.NONE;
    private ServerLiveSettingsProvider.Subscription statusSubscription = ServerLiveSettingsProvider.Subscription.NONE;
    private boolean subscribed;
    private volatile boolean cleaned;

    public ServerLiveSettingsController(Object instance) {
        this(instance instanceof ServerLiveSettingsProvider settingsProvider
                ? settingsProvider
                : new UnavailableServerLiveSettingsProvider("Live Server Settings Are Unavailable"));
    }

    public ServerLiveSettingsController(ServerLiveSettingsProvider provider) {
        this.provider = provider;
    }

    public synchronized void cleanup() {
        if (cleaned) return;
        cleaned = true;
        stateSubscription.close();
        statusSubscription.close();
    }

    private void onMsmpStatusChange(String text) {
        if (cleaned) return;
        setStatus(text);
        if (("MSMP: Connected".equals(text) || provider.connected()) && liveSettingsContainer != null && settingRows.isEmpty()) {
            loadSettings();
        }
    }

    private void updateStatus() {
        if (cleaned) return;
        if (provider.connected()) {
            setStatus("MSMP: Connected");
            if (settingRows.isEmpty()) {
                loadSettings();
            }
        }
    }

    public List<Setting> getSettings() {
        if (liveSettingsContainer != null) return List.of(liveSettingsContainer);
        Setting.Builder builder = new Setting.Builder("Live Server Settings");
        statusBadge = new AnimatedButton.Builder().label("...").active(false).build();
        builder.addRow("", statusBadge);
        this.liveSettingsContainer = builder.build();
        statusRow = liveSettingsContainer.getRows().getFirst();

        subscribe();

        if (!provider.connected()) {
            setStatus(provider.status());
            provider.connect();
        } else {
            updateStatus();
        }

        return List.of(liveSettingsContainer);
    }

    private void loadSettings() {
        if (!provider.connected() || loading) {
            setStatus(provider.status());
            return;
        }
        loading = true;
        provider.liveSettings()
                .thenAccept(settings -> ScreenManager.getInstance().execute(() -> {
                    if (cleaned) return;
                    loading = false;
                    buildSettingsUI(settings);
                })
        ).exceptionally(e -> {
            ScreenManager.getInstance().execute(() -> {
                if (cleaned) return;
                loading = false;
                setStatus("Failed to load settings: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            });
            return null;
        });
    }

    private void buildSettingsUI(List<LiveSettingValue> settings) {
        if (settings == null || settings.isEmpty()) {
            settingRows.clear();
            settingValues.clear();
            shownValues.clear();
            liveSettingsContainer.setRows(List.of(statusRow));
            setStatus("No live settings available");
            return;
        }
        setStatus(settings.size() + " settings loaded");

        settings = new ArrayList<>(settings);
        settings.sort(Comparator.comparing(LiveSettingValue::name));

        Map<String, PopupWidget.PopupRow> next = new LinkedHashMap<>();
        Map<String, LiveSettingValue> nextValues = new LinkedHashMap<>();
        Map<String, String> nextShown = new LinkedHashMap<>();
        for (LiveSettingValue setting : settings) {
            String key = setting.name();
            nextValues.put(key, setting);
            nextShown.put(key, setting.value());
            PopupWidget.PopupRow existing = settingRows.get(key);
            if (existing != null) {
                MountableButtonWidget old = (MountableButtonWidget) existing.getWidgets().getFirst();
                boolean oldBoolean = !old.mountedWidgets.isEmpty() && old.mountedWidgets.getFirst() instanceof ToggleWidget;
                if (oldBoolean != "boolean".equals(setting.type())) existing = null;
            }
            if (existing != null) {
                MountableButtonWidget widget = (MountableButtonWidget) existing.getWidgets().getFirst();
                widget.setDescription(setting.description());
                if (!widget.mountedWidgets.isEmpty()) {
                    if (widget.mountedWidgets.getFirst() instanceof ToggleWidget toggle) {
                        if (toggle.getValue() == Boolean.parseBoolean(shownValues.getOrDefault(key, setting.value()))) {
                            toggle.setValue(Boolean.parseBoolean(setting.value()));
                        }
                    } else if (widget.mountedWidgets.getFirst() instanceof TextInputWidget text && !text.isFocused()
                            && text.getText().equals(shownValues.getOrDefault(key, setting.value()))) {
                        text.setText(setting.value());
                    }
                }
                next.put(key, existing);
                continue;
            }
            MountableButtonWidget.Builder rowBuilder = new MountableButtonWidget.Builder(setting.name())
                    .description(setting.description());

            switch (setting.type()) {
                case "boolean":
                    ToggleWidget toggle = new ToggleWidget.Builder().toggled(Boolean.parseBoolean(setting.value())).build();
                    toggle.onChange = () -> setSetting(settingValues.get(key), String.valueOf(toggle.getValue()));
                    rowBuilder.addWidget(toggle);
                    break;
                case "integer":
                case "string":
                default:
                    TextInputWidget text = new TextInputWidget.Builder().text(setting.value()).build();
                    text.onEnter = () -> setSetting(settingValues.get(key), text.getText());
                    rowBuilder.addWidget(text);
                    break;
            }
            next.put(key, new PopupWidget.PopupRow.Builder("", rowBuilder.build()).build());
        }
        List<PopupWidget.PopupRow> rows = new ArrayList<>();
        rows.add(statusRow);
        rows.addAll(next.values());
        if (!liveSettingsContainer.getRows().equals(rows)) liveSettingsContainer.setRows(rows);
        settingRows.clear();
        settingRows.putAll(next);
        settingValues.clear();
        settingValues.putAll(nextValues);
        shownValues.clear();
        shownValues.putAll(nextShown);
    }

    private void setSetting(LiveSettingValue setting, String value) {
        if (!provider.connected() || setting == null) return;
        provider.setLiveSetting(setting, value)
                .exceptionally(e -> {
                    ScreenManager.getInstance().execute(() -> new Notification("Error", "Failed to set " + setting.name() + ": " + e.getMessage(), Notification.Type.ERROR));
                    return null;
                });
    }

    private void setStatus(String text) {
        if (statusBadge != null) {
            statusBadge.setMessage(text);
        }
    }

    private synchronized void subscribe() {
        if (subscribed || cleaned) return;
        subscribed = true;
        stateSubscription = provider.listenState(() -> {
            if (statusBadge != null) ScreenManager.getInstance().execute(this::updateStatus);
        });
        statusSubscription = provider.listenStatus(this::onMsmpStatusChange);
    }
}
