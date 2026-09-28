package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.packcontent.GlyphPreviewMode;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PackContentSettingsController {
    private final RemotelyConfigStore configManager;
    private final PackContentSettingsProvider provider;
    private final Map<String, PopupWidget.PopupRow> statusRows = new LinkedHashMap<>();
    private final Map<String, PopupWidget.PopupRow> diagnosticRows = new LinkedHashMap<>();
    private Setting setting;
    private List<PopupWidget.PopupRow> fixedRows = List.of();
    private AnimatedButton refreshButton;

    public PackContentSettingsController(RemotelyConfigStore configManager, PackContentSettingsProvider provider) {
        this.configManager = configManager;
        this.provider = provider == null ? PackContentSettingsProvider.unavailable("Pack Content Refresh Is Unavailable") : provider;
    }

    public List<Setting> getSettings() {
        if (setting != null) {
            refreshRows();
            return List.of(setting);
        }
        Setting.Builder builder = new Setting.Builder("Pack Content");
        builder.addOption(ConfigOption.<GlyphPreviewMode>builder("Glyph Previews")
                .description("Preview custom glyph tags in editors and terminals.")
                .options(Arrays.asList(GlyphPreviewMode.values()))
                .display(GlyphPreviewMode::displayName)
                .bind(configManager::getGlyphPreviewMode, configManager::setGlyphPreviewMode)
                .defaultValue(GlyphPreviewMode.INLINE_HOVER)
                .build());
        PackContentSettingsProvider.Availability refresh = provider.refreshAvailability();
        refreshButton = new AnimatedButton.Builder()
                .label("Refresh")
                .hint(refresh.reason())
                .active(refresh.available())
                .onClick(this::refreshPackContent)
                .build();
        builder.addRow(new PopupWidget.PopupRow.Builder("Actions", refreshButton).contentWidth().build());
        setting = builder.build();
        fixedRows = setting.getRows();
        refreshRows();
        return List.of(setting);
    }

    private void refreshPackContent() {
        PackContentSettingsProvider.Availability refresh = provider.refreshAvailability();
        if (!refresh.available()) {
            new Notification("Refresh Unavailable", refresh.reason(), Notification.Type.ERROR);
            return;
        }
        new Notification("Refreshing Pack Content", "Scanning Server Pack Providers", Notification.Type.INFO);
        provider.refresh().thenAccept(count ->
                ScreenManager.getInstance().execute(() -> {
                    refreshRows();
                    new Notification("Pack Content Refreshed", count + " Workspaces", Notification.Type.SUCCESS);
                })
        ).exceptionally(e -> {
            ScreenManager.getInstance().execute(() -> new Notification("Refresh Failed", e.getMessage(), Notification.Type.ERROR));
            return null;
        });
    }

    private void refreshRows() {
        if (setting == null) return;
        PackContentSettingsProvider.Availability availability = provider.refreshAvailability();
        refreshButton.setActive(availability.available());
        refreshButton.setHint(availability.reason());
        List<PopupWidget.PopupRow> rows = new ArrayList<>(fixedRows);
        List<PackContentSettingsProvider.ProviderStatus> statuses = provider.statuses();
        Map<String, PopupWidget.PopupRow> nextStatuses = new LinkedHashMap<>();
        if (statuses.isEmpty()) {
            String key = "empty";
            PopupWidget.PopupRow row = statusRows.get(key);
            if (row == null) {
                row = new PopupWidget.PopupRow.Builder("", new MountableButtonWidget.Builder("No Pack Providers").build()).id(key).build();
            }
            MountableButtonWidget widget = (MountableButtonWidget) row.getWidgets().getFirst();
            widget.setDescription(availability.available() ? "Open Server Workspace Then Refresh" : availability.reason());
            nextStatuses.put(key, row);
            rows.add(row);
        } else {
            for (PackContentSettingsProvider.ProviderStatus status : statuses) {
                String key = "provider:" + status.workspaceName() + ":" + status.providerName() + ":" + status.rootName();
                PopupWidget.PopupRow row = statusRows.get(key);
                if (row == null) row = new PopupWidget.PopupRow.Builder("", providerWidget(status)).id(key).build();
                MountableButtonWidget widget = (MountableButtonWidget) row.getWidgets().getFirst();
                widget.setName(status.workspaceName());
                widget.setHiddenText(status.providerName());
                widget.setDescription(providerDescription(status));
                nextStatuses.put(key, row);
                rows.add(row);
            }
        }
        statusRows.clear();
        statusRows.putAll(nextStatuses);
        Map<String, PopupWidget.PopupRow> nextDiagnostics = new LinkedHashMap<>();
        for (PackContentSettingsProvider.Diagnostic diagnostic : provider.diagnostics()) {
            String key = "diagnostic:" + diagnostic.providerId() + ":" + diagnostic.sourceFile() + ":" + diagnostic.message();
            PopupWidget.PopupRow row = diagnosticRows.get(key);
            if (row == null) row = new PopupWidget.PopupRow.Builder("", diagnosticWidget(diagnostic)).id(key).build();
            nextDiagnostics.put(key, row);
            rows.add(row);
        }
        diagnosticRows.clear();
        diagnosticRows.putAll(nextDiagnostics);
        setting.setRows(rows);
    }

    private String providerDescription(PackContentSettingsProvider.ProviderStatus status) {
        String description = status.rootName() + " - " + status.glyphCount() + " Glyphs";
        if (status.frameCount() > 0) description += " - " + status.frameCount() + " Frames";
        if (status.diagnosticCount() > 0) description += " - " + status.diagnosticCount() + " Issues";
        return description;
    }

    private MountableButtonWidget providerWidget(PackContentSettingsProvider.ProviderStatus status) {
        String description = providerDescription(status);
        return new MountableButtonWidget.Builder(status.workspaceName())
                .hiddenText(status.providerName())
                .description(description)
                .build();
    }

    private MountableButtonWidget diagnosticWidget(PackContentSettingsProvider.Diagnostic diagnostic) {
        return new MountableButtonWidget.Builder("Issue")
                .hiddenText(diagnostic.providerId())
                .description(fileName(diagnostic.sourceFile()) + " - " + diagnostic.message())
                .build();
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "Unknown";
        }
        String normalized = path.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 && separator + 1 < normalized.length() ? normalized.substring(separator + 1) : normalized;
    }
}
