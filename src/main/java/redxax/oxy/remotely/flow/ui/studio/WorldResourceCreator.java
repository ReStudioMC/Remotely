package redxax.oxy.remotely.flow.ui.studio;

import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.world.WorldGeneratorDescriptor;
import redxax.oxy.remotely.data.flow.world.WorldSnapshot;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.WorldUiSupport;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static restudio.rescreen.config.Config.desktopMode;

public final class WorldResourceCreator {
    private WorldResourceCreator() {
    }

    public static void showCreatePopup(StudioScreen screen, String serverId, String folder, Consumer<String> onCreated) {
        FlowManager manager = FlowManager.getInstance();
        if (screen == null || manager == null || serverId == null) {
            return;
        }
        WorldGenManager.getInstance().requestProjectList(serverId);
        WorldSnapshot snapshot = manager.getWorldSnapshot(serverId);
        List<WorldGeneratorDescriptor> generatorDescriptors = snapshot == null ? List.of() : snapshot.getGeneratorDescriptors();
        TextInputWidget worldInput = new TextInputWidget.Builder().placeholder("World Name").size(220, 18).build();
        TextInputWidget seedInput = new TextInputWidget.Builder().placeholder("Seed").size(220, 18).build();
        DropDownWidget<String> environmentSelect = dropdown(List.of("NORMAL", "NETHER", "THE_END", "CUSTOM"), "NORMAL", value -> {});
        List<GeneratorOption> generatorOptions = createGeneratorOptions(manager, serverId, generatorDescriptors);
        List<String> generatorLabels = generatorOptions.stream().map(GeneratorOption::label).toList();
        GeneratorOption[] selectedGenerator = new GeneratorOption[] {generatorOptionByLabel(generatorOptions, generatorLabels.getFirst())};
        TextInputWidget generatorConfig = new TextInputWidget.Builder().placeholder("Generator Config").size(220, 18).build();
        applyGeneratorOption(selectedGenerator[0], generatorConfig);
        DropDownWidget<String> generatorSelect = dropdown(generatorLabels, selectedGenerator[0].label(), value -> {
            if (value != null && !value.isBlank()) {
                selectedGenerator[0] = generatorOptionByLabel(generatorOptions, value);
                applyGeneratorOption(selectedGenerator[0], generatorConfig);
            }
        });
        PopupWidget.Builder builder = new PopupWidget.Builder("Create World")
            .setResizable(false)
            .setAntiOutOfBound(true)
            .setBoundOffset(desktopMode ? 35 : 0)
            .width(420);
        builder.addRow("World", worldInput);
        builder.addRow("Seed", seedInput);
        builder.addRow("Environment", environmentSelect);
        builder.addRow("Generator", generatorSelect);
        builder.addRow("Config", generatorConfig);
        PopupWidget[] popupRef = new PopupWidget[1];
        Runnable create = () -> {
                String worldName = safeText(worldInput.getText()).trim();
                if (!WorldUiSupport.isValidSimpleId(worldName)) {
                    new Notification("World", "Invalid World Name", Notification.Type.ERROR);
                    return;
                }
                if (worldExists(manager, serverId, worldName)) {
                    new Notification("World", "World Exists", Notification.Type.ERROR);
                    return;
                }
                GeneratorOption generatorOption = selectedGenerator[0];
                manager.createWorld(serverId, worldName, seedInput.getText(), environmentSelect.getSelectedItem(),
                    generatorOption == null ? "" : generatorOption.generator(), generatorConfig.getText());
                String targetFolder = ReSyncProjectMetadata.normalizePath(folder);
                FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(serverId);
                FlowManager.ProjectResource existing = metadata.resource(ReSyncResourceDragPayload.WORLD, worldName);
                metadata.putResource(ReSyncResourceDragPayload.WORLD, worldName, worldName, targetFolder,
                    existing != null ? existing.sortOrder() : metadata.nextResourceSortOrder());
                manager.saveProjectMetadata(metadata, true);
                if (onCreated != null) {
                    onCreated.accept(worldName);
                }
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            };
        builder.addTitleAction("Create", create, PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        screen.addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    public static void showDeletePopup(Screen screen, String serverId, String worldName, Runnable onDeleted) {
        FlowManager manager = FlowManager.getInstance();
        if (screen == null || manager == null || worldName == null || worldName.isBlank()) {
            return;
        }
        List<String> fallbackOptions = fallbackWorldOptions(manager, serverId, worldName);
        PopupWidget.Builder builder = new PopupWidget.Builder("Delete World | " + worldName)
            .setResizable(false)
            .setAntiOutOfBound(true)
            .setBoundOffset(desktopMode ? 35 : 0)
            .width(430);
        ToggleWidget deleteFiles = new ToggleWidget.Builder()
            .label("Delete Files")
            .toggled(false)
            .size(115, 18)
            .entranceAnimation(false)
            .build();
        TextInputWidget fallbackInput = new TextInputWidget.Builder()
            .text(selectDefaultFallbackWorld(manager, serverId, worldName, fallbackOptions))
            .placeholder("Fallback World")
            .size(220, 18)
            .build();
        builder.addRow("Files", deleteFiles);
        builder.addRow("Fallback", fallbackInput);
        PopupWidget[] popupRef = new PopupWidget[1];
        Runnable delete = () -> {
                String fallbackWorld = safeText(fallbackInput.getText()).trim();
                if (!WorldUiSupport.containsIgnoreCase(fallbackOptions, fallbackWorld)) {
                    new Notification("World", "Unknown Fallback World", Notification.Type.ERROR);
                    return;
                }
                manager.deleteWorld(serverId, worldName, deleteFiles.getValue(), fallbackWorld);
                FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(serverId);
                metadata.removeResource(ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.WORLD, worldName));
                manager.saveProjectMetadata(metadata, true);
                if (onDeleted != null) {
                    onDeleted.run();
                }
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            };
        builder.addTitleAction("Delete", delete, PopupWidget.TitleActionRole.DESTRUCTIVE);
        popupRef[0] = builder.build();
        screen.addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    public static boolean worldExists(FlowManager manager, String serverId, String worldName) {
        if (manager == null || serverId == null || worldName == null || worldName.isBlank()) {
            return false;
        }
        if (manager.getProjectResource(serverId, ReSyncResourceDragPayload.WORLD, worldName) != null) {
            return true;
        }
        return manager.getWorldsForServer(serverId).containsKey(worldName);
    }

    private static DropDownWidget<String> dropdown(List<String> options, String selected, Consumer<String> onSelected) {
        List<String> safeOptions = options == null || options.isEmpty() ? List.of("NORMAL") : options;
        return new DropDownWidget.Builder<>(safeOptions)
            .selectedItem(safeOptions.contains(selected) ? selected : safeOptions.getFirst())
            .onSelectionChanged(value -> {
                if (value != null && !value.isBlank() && onSelected != null) {
                    onSelected.accept(value);
                }
            })
            .size(220, 18)
            .maxVisibleItems(8)
            .entranceAnimation(false)
            .build();
    }

    private static List<GeneratorOption> createGeneratorOptions(FlowManager manager, String serverId, List<WorldGeneratorDescriptor> descriptors) {
        List<GeneratorOption> options = new ArrayList<>();
        options.add(new GeneratorOption("Default", "", "", false));
        for (WorldGeneratorDescriptor descriptor : descriptors) {
            if (descriptor == null || safeText(descriptor.getId()).isBlank()) {
                continue;
            }
            String label = safeText(descriptor.getDisplayName()).isBlank() ? descriptor.getId() : descriptor.getDisplayName();
            addGeneratorOption(options, new GeneratorOption(label, descriptor.getId(), descriptor.getDefaultConfig(), descriptor.isConfigurable()));
        }
        Set<String> projectIds = new LinkedHashSet<>(WorldGenManager.getInstance().getProjectIds(serverId));
        for (ReSyncProjectMetadata.ResourceEntry resource : manager.getProjectResources(serverId)) {
            if (resource != null && ReSyncResourceDragPayload.WORLDGEN.equals(resource.getType()) && !safeText(resource.getId()).isBlank()) {
                projectIds.add(resource.getId());
            }
        }
        List<String> sortedProjectIds = new ArrayList<>(projectIds);
        sortedProjectIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String projectId : sortedProjectIds) {
            if (!safeText(projectId).isBlank()) {
                addGeneratorOption(options, new GeneratorOption("WorldGen: " + projectId, "worldgen_project", projectId, true));
            }
        }
        return options;
    }

    private static void addGeneratorOption(List<GeneratorOption> options, GeneratorOption option) {
        for (GeneratorOption existing : options) {
            if (safeText(existing.label()).equalsIgnoreCase(safeText(option.label()))) {
                return;
            }
        }
        options.add(option);
    }

    private static GeneratorOption generatorOptionByLabel(List<GeneratorOption> options, String label) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        for (GeneratorOption option : options) {
            if (safeText(option.label()).equalsIgnoreCase(safeText(label))) {
                return option;
            }
        }
        return options.getFirst();
    }

    private static void applyGeneratorOption(GeneratorOption option, TextInputWidget generatorConfig) {
        if (generatorConfig == null) {
            return;
        }
        boolean configurable = option != null && option.configurable();
        generatorConfig.active = configurable;
        if (!generatorConfig.isFocused() || !configurable) {
            generatorConfig.setText(configurable ? safeText(option.config()) : "");
        }
    }

    private static List<String> fallbackWorldOptions(FlowManager manager, String serverId, String worldName) {
        List<String> options = new ArrayList<>(manager.getWorldsForServer(serverId).keySet());
        options.removeIf(option -> option != null && option.equalsIgnoreCase(worldName));
        options.sort(String.CASE_INSENSITIVE_ORDER);
        return options;
    }

    private static String selectDefaultFallbackWorld(FlowManager manager, String serverId, String worldName, List<String> fallbackOptions) {
        if (fallbackOptions == null || fallbackOptions.isEmpty()) {
            return "";
        }
        var world = manager.getWorld(serverId, worldName);
        var profile = world == null ? null : world.getProfileSettings();
        List<String> preferred = new ArrayList<>();
        if (profile != null) {
            preferred.add(profile.getRespawnWorld());
            preferred.add(profile.getLinkedOverworld());
        }
        preferred.add("world");
        preferred.add("overworld");
        for (String candidate : preferred) {
            for (String option : fallbackOptions) {
                if (option != null && option.equalsIgnoreCase(safeText(candidate))) {
                    return option;
                }
            }
        }
        return fallbackOptions.getFirst();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private record GeneratorOption(String label, String generator, String config, boolean configurable) {
    }
}
