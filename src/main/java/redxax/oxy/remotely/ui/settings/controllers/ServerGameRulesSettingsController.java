package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsProvider.GameRuleValue;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class ServerGameRulesSettingsController {
    private final ServerLiveSettingsProvider provider;
    private AnimatedButton statusBadge;
    private Setting gameRulesSetting;
    private final Map<String, PopupWidget.PopupRow> ruleRows = new LinkedHashMap<>();
    private final Map<String, String> shownValues = new LinkedHashMap<>();
    private PopupWidget.PopupRow statusRow;
    private boolean loading;
    private ServerLiveSettingsProvider.Subscription stateSubscription = ServerLiveSettingsProvider.Subscription.NONE;
    private ServerLiveSettingsProvider.Subscription statusSubscription = ServerLiveSettingsProvider.Subscription.NONE;
    private boolean subscribed;
    private volatile boolean cleaned;

    public ServerGameRulesSettingsController(Object instance) {
        this(instance instanceof ServerLiveSettingsProvider settingsProvider
                ? settingsProvider
                : new UnavailableServerLiveSettingsProvider("Live Game Rules Are Unavailable"));
    }

    public ServerGameRulesSettingsController(ServerLiveSettingsProvider provider) {
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
        if (("MSMP: Connected".equals(text) || provider.connected()) && gameRulesSetting != null && ruleRows.isEmpty()) {
            loadRules();
        }
    }

    private void updateStatus() {
        if (cleaned) return;
        if (provider.connected()) {
            setStatus("MSMP: Connected");
            if (ruleRows.isEmpty()) {
                loadRules();
            }
        } else {
            setStatus(provider.status());
            provider.connect();
        }
    }

    public List<Setting> getSettings() {
        if (gameRulesSetting != null) return List.of(gameRulesSetting);
        Setting.Builder builder = new Setting.Builder("Game Rules (Live)");
        statusBadge = new AnimatedButton.Builder().label("...").active(false).build();
        builder.addRow("", statusBadge);
        this.gameRulesSetting = builder.build();
        statusRow = gameRulesSetting.getRows().getFirst();

        subscribe();
        updateStatus();

        return List.of(gameRulesSetting);
    }

    private void loadRules() {
        if (!provider.connected() || loading) {
            setStatus(provider.status());
            return;
        }
        loading = true;
        provider.gameRules()
                .thenAccept(rules -> ScreenManager.getInstance().execute(() -> {
                    if (cleaned) return;
                    loading = false;
                    buildRulesUI(rules);
                })
        ).exceptionally(e -> {
            ScreenManager.getInstance().execute(() -> {
                if (cleaned) return;
                loading = false;
                setStatus("Failed to load rules: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            });
            return null;
        });
    }

    private void buildRulesUI(List<GameRuleValue> rules) {
        if (rules == null || rules.isEmpty()) {
            ruleRows.clear();
            shownValues.clear();
            gameRulesSetting.setRows(List.of(statusRow));
            setStatus("No game rules available");
            return;
        }
        setStatus(rules.size() + " rules loaded");

        rules = new ArrayList<>(rules);
        rules.sort(Comparator.comparing(GameRuleValue::name));

        Map<String, PopupWidget.PopupRow> next = new LinkedHashMap<>();
        Map<String, String> nextShown = new LinkedHashMap<>();
        for (GameRuleValue rule : rules) {
            nextShown.put(rule.name(), rule.value());
            PopupWidget.PopupRow existing = ruleRows.get(rule.name());
            if (existing != null) {
                MountableButtonWidget old = (MountableButtonWidget) existing.getWidgets().getFirst();
                boolean oldBoolean = !old.mountedWidgets.isEmpty() && old.mountedWidgets.getFirst() instanceof ToggleWidget;
                if (oldBoolean != "boolean".equalsIgnoreCase(rule.type())) existing = null;
            }
            if (existing != null) {
                MountableButtonWidget widget = (MountableButtonWidget) existing.getWidgets().getFirst();
                if (!widget.mountedWidgets.isEmpty()) {
                    if (widget.mountedWidgets.getFirst() instanceof ToggleWidget toggle) {
                        if (toggle.getValue() == Boolean.parseBoolean(shownValues.getOrDefault(rule.name(), rule.value()))) {
                            toggle.setValue(Boolean.parseBoolean(rule.value()));
                        }
                    } else if (widget.mountedWidgets.getFirst() instanceof TextInputWidget text && !text.isFocused()
                            && text.getText().equals(shownValues.getOrDefault(rule.name(), rule.value()))) {
                        text.setText(rule.value());
                    }
                }
                next.put(rule.name(), existing);
                continue;
            }
            MountableButtonWidget.Builder rowBuilder = new MountableButtonWidget.Builder(rule.name());

            if ("boolean".equalsIgnoreCase(rule.type())) {
                ToggleWidget toggle = new ToggleWidget.Builder().toggled(Boolean.parseBoolean(rule.value())).build();
                toggle.onChange = () -> setRule(rule.name(), String.valueOf(toggle.getValue()));
                rowBuilder.addWidget(toggle);
            } else {
                TextInputWidget text = new TextInputWidget.Builder().text(rule.value()).build();
                text.onEnter = () -> setRule(rule.name(), text.getText());
                rowBuilder.addWidget(text);
            }
            next.put(rule.name(), new PopupWidget.PopupRow.Builder("", rowBuilder.build()).build());
        }
        List<PopupWidget.PopupRow> rows = new ArrayList<>();
        rows.add(statusRow);
        rows.addAll(next.values());
        if (!gameRulesSetting.getRows().equals(rows)) gameRulesSetting.setRows(rows);
        ruleRows.clear();
        ruleRows.putAll(next);
        shownValues.clear();
        shownValues.putAll(nextShown);
    }

    private void setRule(String key, String value) {
        if (!provider.connected()) return;
        provider.setGameRule(key, value).exceptionally(e -> null);
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
