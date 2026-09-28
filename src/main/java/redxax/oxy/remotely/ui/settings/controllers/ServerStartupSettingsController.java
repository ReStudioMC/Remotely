package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ServerStartupSettingsController {
    private final ServerStartupSettingsProvider provider;
    private final Map<String, AnimatedWidget> controls = new LinkedHashMap<>();
    private Setting setting;

    public ServerStartupSettingsController(ServerStartupSettingsProvider provider) {
        this.provider = provider;
    }

    public List<Setting> getSettings() {
        if (setting == null) setting = new Setting.Builder("Startup Options").build();
        reconcileSelect("ADDITIONAL_FLAGS", "Performance Flags", "Adds a supported set of Java performance flags",
                List.of("None", "Aikar's Flags", "Velocity Flags"));
        reconcileSelect("MINEHUT_SUPPORT", "Minehut Support", "Configures the forwarding flags required for Minehut",
                List.of("None", "Velocity", "Waterfall", "Bukkit"));
        reconcileToggle("AUTOMATIC_UPDATING", "Automatic Updating", "Updates supported server software when the server starts");
        reconcileToggle("SIMD_OPERATIONS", "SIMD Operations", "Enables vector operations for software that supports them");
        reconcileToggle("REMOVE_UPDATE_WARNING", "Remove Update Warning", "Removes the supported server software update warning");
        reconcileToggle("MALWARE_SCAN", "Malware Scan", "Scans server files for known malware when the server starts");
        return controls.isEmpty() ? List.of() : List.of(setting);
    }

    private void reconcileSelect(String key, String name, String description, List<String> options) {
        if (!provider.available(key)) {
            if (controls.containsKey(key)) setting.setRowVisibility(key, false);
            return;
        }
        String current = provider.value(key);
        String selected = options.stream().filter(value -> value.equalsIgnoreCase(current)).findFirst().orElse(options.getFirst());
        AnimatedWidget existing = controls.get(key);
        if (existing instanceof DropDownWidget<?> dropdown) {
            @SuppressWarnings("unchecked")
            DropDownWidget<String> input = (DropDownWidget<String>) dropdown;
            if (!Objects.equals(input.getSelectedItem(), selected)) input.setItems(options, selected);
            setting.setRowVisibility(key, true);
            return;
        }
        DropDownWidget<String> input = new DropDownWidget.Builder<>(options)
                .selectedItem(selected)
                .onSelectionChanged(value -> provider.value(key, value))
                .size(220, 20)
                .build();
        admit(key, name, description, input);
    }

    private void reconcileToggle(String key, String name, String description) {
        if (!provider.available(key)) {
            if (controls.containsKey(key)) setting.setRowVisibility(key, false);
            return;
        }
        boolean value = enabled(provider.value(key));
        AnimatedWidget existing = controls.get(key);
        if (existing instanceof ToggleWidget input) {
            if (input.getValue() != value) input.setValue(value);
            setting.setRowVisibility(key, true);
            return;
        }
        ToggleWidget input = new ToggleWidget.Builder().toggled(value).build();
        input.onChange = () -> provider.value(key, input.getValue() ? "1" : "0");
        admit(key, name, description, input);
    }

    private void admit(String key, String name, String description, AnimatedWidget input) {
        MountableButtonWidget widget = new MountableButtonWidget.Builder(name).description(description).addWidget(input).build();
        setting.addRow(new PopupWidget.PopupRow.Builder("", widget).id(key).build());
        controls.put(key, input);
    }

    private boolean enabled(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
