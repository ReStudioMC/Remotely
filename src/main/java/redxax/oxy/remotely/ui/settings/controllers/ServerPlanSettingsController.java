package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ServerPlanSettingsController {

    private final BrowserSafeState.ReferenceValue<ServerModels.Plan> selectedPlan = new BrowserSafeState.ReferenceValue<>();
    private final List<ServerModels.Plan> availablePlans = new ArrayList<>();
    private DropDownWidget<ServerModels.Plan> planDropdown;
    private Setting setting;
    private MountableButtonWidget planWidget;
    private TextInputWidget subdomainInput;
    private TextInputWidget customRamInput;
    private String selectedPlanName;
    private final ServerPlanSettingsProvider provider;

    public ServerPlanSettingsController(ServerPlanSettingsProvider provider) {
        this.provider = provider;
    }

    public void selectPlanByName(String planName) {
        selectedPlanName = planName;
        if (planDropdown == null || availablePlans.isEmpty() || planName == null || planName.isBlank()) {
            return;
        }
        ServerModels.Plan preferred = null;
        for (ServerModels.Plan candidate : availablePlans) {
            if (candidate != null && candidate.name != null && candidate.name.equalsIgnoreCase(planName)) {
                preferred = candidate;
                break;
            }
        }
        planDropdown.setItems(availablePlans, preferred);
        selectedPlan.set(planDropdown.getSelectedItem());
        updateCustomRamVisibility();
    }

    public List<Setting> getSettings() {
        if (setting != null) return List.of(setting);
        Setting.Builder builder = new Setting.Builder("Server Plan");

        planDropdown = new DropDownWidget.Builder<ServerModels.Plan>(new ArrayList<>())
                .displayFunction(this::formatPlanOption)
                .onSelectionChanged(plan -> {
                    selectedPlan.set(plan);
                    if (plan != null && plan.name != null && !plan.name.isBlank()) {
                        selectedPlanName = plan.name;
                    }
                    updateCustomRamVisibility();
                })
                .size(150, 20)
                .build();

        planWidget = new MountableButtonWidget.Builder("Reactor Plans")
                .description("Choose the plan for this Reactor server.")
                .addWidget(planDropdown)
                .build();

        builder.addRow("", planWidget);

        customRamInput = new TextInputWidget.Builder()
                .placeholder("12")
                .numericOnly(true)
                .onChange(this::refreshCustomPlanLabel)
                .size(150, 20)
                .build();

        MountableButtonWidget customRamWidget = new MountableButtonWidget.Builder("Custom RAM")
                .description("RAM in GB for the Custom plan.")
                .addWidget(customRamInput)
                .build();

        builder.addRow("customRam", "", customRamWidget);

        subdomainInput = new TextInputWidget.Builder()
                .placeholder("Optional (e.g. myserver)")
                .size(150, 20)
                .build();

        MountableButtonWidget subdomainWidget = new MountableButtonWidget.Builder("Custom Subdomain")
                .description("Set a custom subdomain (e.g. myserver.restudiomc.net).")
                .addWidget(subdomainInput)
                .build();

        builder.addRow("", subdomainWidget);

        setting = builder.build();
        loadPlans();
        updateCustomRamVisibility();
        return List.of(setting);
    }

    private void loadPlans() {
        provider.plans().thenAccept(plans -> ScreenManager.getInstance().execute(() -> {
            availablePlans.clear();
            if (plans != null) {
                availablePlans.addAll(plans);
            }

            boolean hasCustomPlan = availablePlans.stream().anyMatch(plan -> plan != null && "custom".equalsIgnoreCase(plan.name));
            if (!hasCustomPlan) {
                availablePlans.add(createCustomPlanOption());
            }
            availablePlans.sort(Comparator.comparingInt((ServerModels.Plan plan) -> plan != null && "custom".equalsIgnoreCase(plan.name) ? 1 : 0).thenComparingLong(p -> p.priceCents));

            if (planDropdown == null) {
                return;
            }

            ServerModels.Plan preferred = selectedPlan.get();
            if (preferred == null && selectedPlanName != null && !selectedPlanName.isBlank()) {
                for (ServerModels.Plan candidate : availablePlans) {
                    if (candidate != null && candidate.name != null && candidate.name.equalsIgnoreCase(selectedPlanName)) {
                        preferred = candidate;
                        break;
                    }
                }
            }

            if (availablePlans.isEmpty()) {
                planDropdown.setItems(List.of(), null);
                selectedPlan.set(null);
                updateCustomRamVisibility();
                return;
            }

            planDropdown.setItems(availablePlans, preferred);
            selectedPlan.set(planDropdown.getSelectedItem());
            updateCustomRamVisibility();
        })).exceptionally(e -> {
            ScreenManager.getInstance().execute(() -> {
                if (planDropdown != null) {
                    planDropdown.setItems(List.of(), null);
                }
                selectedPlan.set(null);
                updateCustomRamVisibility();
            });
            return null;
        });
    }

    public String getSelectedPlanName() {
        ServerModels.Plan plan = selectedPlan.get();
        return plan != null ? plan.name : null;
    }

    public String getSubdomain() {
        return subdomainInput != null ? subdomainInput.getText() : null;
    }

    public ServerModels.CustomPlanRequest getCustomPlanRequest() {
        if (!"custom".equalsIgnoreCase(getSelectedPlanName())) {
            return null;
        }
        ServerModels.CustomPlanRequest request = new ServerModels.CustomPlanRequest();
        int minimumRamGb = minimumCustomRamGb(customPlanTemplate());
        request.memoryMb = parseGb(customRamInput, minimumRamGb, minimumRamGb, 64) * 1024;
        return request;
    }

    private String formatPlanOption(ServerModels.Plan plan) {
        if (plan == null) {
            return "Select Plan";
        }
        String name = plan.name == null || plan.name.isBlank() ? "Plan" : plan.name;
        if ("Custom".equalsIgnoreCase(name)) {
            int minimumRamGb = minimumCustomRamGb(plan);
            int ramGb = parseGb(customRamInput, minimumRamGb, minimumRamGb, 64);
            return String.format(Locale.US, "Custom - $%.2f/mo - %s", calculateCustomPriceCents(plan, ramGb) / 100.0, formatCustomSpecs(plan, ramGb));
        }
        long cents = Math.max(0L, plan.priceCents);
        return String.format(Locale.US, "%s - $%.2f/mo", name, cents / 100.0);
    }

    private ServerModels.Plan createCustomPlanOption() {
        ServerModels.Plan plan = new ServerModels.Plan();
        plan.name = "Custom";
        plan.memoryMb = 12288;
        plan.diskMb = 12800;
        plan.cpuPercent = 50;
        plan.databases = 4;
        plan.backups = 4;
        plan.allocations = 4;
        plan.priceCents = 175;
        return plan;
    }

    private int parseGb(TextInputWidget input, int fallback, int min, int max) {
        return parseInt(input, fallback, min, max);
    }

    private long calculateCustomPriceCents(ServerModels.Plan template, int ramGb) {
        int minimumRamGb = minimumCustomRamGb(template);
        long centsPerRamGb = Math.max(1L, template != null ? template.priceCents : 175L);
        return Math.max(Math.round(minimumRamGb * (double) centsPerRamGb), Math.round(ramGb * (double) centsPerRamGb));
    }

    private String formatCustomSpecs(ServerModels.Plan template, int ramGb) {
        int storageMbPerRamGb = Math.max(0, template != null ? template.diskMb : 12800);
        int cpuPercentPerRamGb = Math.max(0, template != null ? template.cpuPercent : 50);
        int allocationRamDivisor = Math.max(1, template != null ? template.allocations : 4);
        int storageMb = Math.clamp(Math.max(102400, ramGb * storageMbPerRamGb), 102400, 512000);
        int cpuPercent = Math.clamp(Math.max(600, ramGb * cpuPercentPerRamGb), 600, 2400);
        int storageGb = Math.round(storageMb / 1024.0f);
        String threads = formatThreads(cpuPercent);
        int featureLimit = Math.clamp(Math.max(5, Math.ceilDiv(ramGb, allocationRamDivisor)), 5, 25);
        return ramGb + "GB RAM / " + threads + " Threads / " + storageGb + "GB Storage / " + featureLimit + " Ports";
    }

    private String formatThreads(int cpuPercent) {
        if (cpuPercent % 100 == 0) {
            return String.valueOf(cpuPercent / 100);
        }
        return String.format(Locale.US, "%.1f", cpuPercent / 100.0);
    }

    private void refreshCustomPlanLabel() {
        if (planDropdown == null || availablePlans.isEmpty()) {
            return;
        }
        ServerModels.Plan selected = selectedPlan.get();
        planDropdown.setItems(availablePlans, selected);
        selectedPlan.set(planDropdown.getSelectedItem());
        updateCustomRamVisibility();
    }

    private void updateCustomRamVisibility() {
        boolean custom = "custom".equalsIgnoreCase(getSelectedPlanName());
        if (setting != null) {
            setting.setRowVisibility("customRam", custom);
        }
        if (customRamInput != null) {
            customRamInput.setVisible(custom);
            customRamInput.setActive(custom);
        }
        if (planWidget != null) {
            ServerModels.Plan template = customPlanTemplate();
            int minimumRamGb = minimumCustomRamGb(template);
            planWidget.setDescription(custom ? formatCustomPlanDescription(template, parseGb(customRamInput, minimumRamGb, minimumRamGb, 64)) : "Choose the plan for this Reactor server.");
        }
    }

    private String formatCustomPlanDescription(ServerModels.Plan template, int ramGb) {
        return String.format(Locale.US, "%s - $%.2f/mo", formatCustomSpecs(template, ramGb), calculateCustomPriceCents(template, ramGb) / 100.0);
    }

    private ServerModels.Plan customPlanTemplate() {
        ServerModels.Plan plan = selectedPlan.get();
        if (plan != null && "custom".equalsIgnoreCase(plan.name)) {
            return plan;
        }
        for (ServerModels.Plan candidate : availablePlans) {
            if (candidate != null && "custom".equalsIgnoreCase(candidate.name)) {
                return candidate;
            }
        }
        return createCustomPlanOption();
    }

    private int minimumCustomRamGb(ServerModels.Plan template) {
        return Math.max(1, Math.ceilDiv(template != null ? template.memoryMb : 12288, 1024));
    }

    private int parseInt(TextInputWidget input, int fallback, int min, int max) {
        if (input == null || input.getText() == null || input.getText().isBlank()) {
            return fallback;
        }
        try {
            return Math.clamp(Integer.parseInt(input.getText().trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
