package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerBackupSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerExtraSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerFeatureSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerGameRulesSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerGeneralSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerJvmSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerManagementSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerNetworkSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerPlanSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerScheduleSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerSubuserSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerStartupSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerStartupSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.PlayerActionsSettingsController;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rebase.settings.controllers.ModpackSettingsController;
import restudio.rebase.settings.controllers.VersionSettingsController;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.util.ReStudioJavaRuntimeResolver;
import restudio.rescreen.config.Config;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ServerConfigurationUiComposition {
    private ServerConfigurationUiComposition() {
    }

    public static ServerScreenHost.ConfigurationUi create(Screen owner, ServerScreenHost.ConfigurationState state,
                                                           ServerSettingsDataController data, Map<String, String> remoteVariables,
                                                           List<String> extraFiles,
                                                           Runnable reload, BooleanSupplier allow, String defaultLocation,
                                                           ServerConfigurationUiPlatform platform) {
        Object original = platform == null ? null : platform.originalTarget();
        Object instance = platform == null ? null : platform.target();
        if (instance == null || !(owner instanceof ReScreen screen)) {
            return new ServerScreenHost.ConfigurationUi(Map.of(), () -> {}, "Server Configuration", () -> "", () -> "", () -> null, () -> defaultLocation);
        }
        Map<String, Supplier<List<Setting>>> settings = new LinkedHashMap<>();
        List<Runnable> cleanup = new ArrayList<>();
        TextInputWidget instanceLocationField = null;
        if (!state.editMode() && !state.restudioCreation() && !state.resourcePoolCreation() && state.remoteHost() == null) {
            instanceLocationField = new TextInputWidget.Builder()
                    .text(defaultLocation == null ? "" : defaultLocation)
                    .placeholder("Instances Path")
                    .size(0, 20)
                    .build();
        }
        TextInputWidget locationField = instanceLocationField;
        Supplier<ResourcePoolModels.DraftOptions> options = owner instanceof ServerConfigurationScreen creation && state.resourcePoolCreation()
                ? creation::poolOptions : state::poolOptions;
        List<ProfileChoice> profileChoices = new ArrayList<>(state.resourcePoolCreation() ? choices(options.get()) : List.of());
        ScrollSelectorWidget profile = state.resourcePoolCreation() ? new ScrollSelectorWidget.Builder()
                .options(profileChoices.stream().map(ProfileChoice::label).toList())
                .selectedIndex(profileIndex(profileChoices, remoteVariables.get("VERSION"))).size(200, 20).build() : null;
        if (profile != null) profile.setActive(!profileChoices.isEmpty());
        PoolCreationPreview preview = state.resourcePoolCreation()
                ? state.poolPreview() == null ? new PoolCreationPreview(state.poolView(), state.poolOptions(), state.poolShares()) : state.poolPreview() : null;
        VersionSettingsController version = new VersionSettingsController(platform.versionTarget(), platform.versionCatalog());
        if (state.restudioBackend() || state.restudioCreation()) {
            version.bindToRemoteVariables(remoteVariables);
        }
        version.allowServerSoftwareChangeWhen(ignored -> allow == null || allow.getAsBoolean());
        version.onServerSoftwareChanged(ignored -> reload.run());
        ServerGeneralSettingsController general = new ServerGeneralSettingsController(platform.generalSettingsProvider(),
                state.editMode() || state.resourcePoolCreation());
        ModpackSettingsController modpack = new ModpackSettingsController(platform.modpackTarget(), platform.managedModpackTarget(), platform.modpackProvider());
        boolean linkedModpack = platform.modpackTarget().linkedModpack();
        cleanup.add(modpack::cleanup);
        ServerPlanSettingsController plan = state.restudioCreation() && !state.resourcePoolCreation()
                ? new ServerPlanSettingsController(platform.planSettingsProvider()) : null;
        TextInputWidget subdomain = state.resourcePoolCreation() && owner instanceof ServerConfigurationScreen
                ? new TextInputWidget.Builder().placeholder("Optional Subdomain").size(230, 20).build() : null;
        List<Setting> addressSettings = new ArrayList<>();
        if (subdomain != null) {
            Setting.Builder address = new Setting.Builder("Subdomain");
            address.addRow("Subdomain", subdomain);
            address.addRow("", new MountableButtonWidget.Builder("Server Address")
                    .description("Choose A ReStudio Subdomain Or Leave It Empty To Use The Server Address").build());
            addressSettings.add(address.build());
        }
        if (plan != null) plan.selectPlanByName(state.preselectedPlanName());
        Supplier<List<Setting>> poolSettings = state.resourcePoolCreation()
                ? state.poolPreview() == null ? poolSettings(screen, preview, cleanup) : sharedPoolSettings(preview, cleanup) : () -> List.of();
        if (state.poolPreview() != null) settings.put("Resources", poolSettings);
        List<Setting> storageSettings = new ArrayList<>();
        if (locationField != null) {
            Setting.Builder storage = new Setting.Builder("Storage");
            storage.addRow("Location", locationField);
            storageSettings.add(storage.build());
        }
        List<Setting> poolVersions = new ArrayList<>();
        settings.put("General", () -> {
            List<Setting> result = new ArrayList<>();
            if (plan != null) result.addAll(plan.getSettings());
            result.addAll(general.getSettings());
            if (owner instanceof ServerConfigurationScreen creation && state.resourcePoolCreation()) result.addAll(creation.poolLoadingSettings());
            result.addAll(addressSettings);
            if (state.resourcePoolCreation()) {
                if (!linkedModpack) version.bindToRemoteVariables(remoteVariables);
                if (poolVersions.isEmpty()) {
                    poolVersions.addAll(linkedModpack ? List.of(new Setting.Builder("Game Version").build()) : version.getSettings());
                    Setting versionSetting = poolVersions.getFirst();
                    if (linkedModpack) versionSetting.addRow("", new MountableButtonWidget.Builder("Minecraft Version")
                            .description("Set By The Linked Modpack").build());
                    versionSetting.addRow("", new MountableButtonWidget.Builder("Server Image")
                            .description("Choose The Java Runtime For This Server")
                            .addWidget(profile).build());
                }
                result.addAll(poolVersions);
                if (state.poolPreview() == null) result.addAll(poolSettings.get());
            } else if (!linkedModpack) {
                if (state.restudioBackend() || state.restudioCreation()) version.bindToRemoteVariables(remoteVariables);
                result.addAll(version.getSettings());
            }
            result.addAll(storageSettings);
            if (state.editMode() && state.restudioBackend()) {
                result.addAll(modpack.getSettings());
            }
            return result;
        });
        settings.put("Features", new ServerFeatureSettingsController(platform.featureSettingsProvider(),
                !state.restudioCreation() && !state.resourcePoolCreation())::getSettings);
        if ((state.editMode() && state.restudioBackend()) || state.resourcePoolCreation()) {
            settings.put("Software Settings", new ServerStartupSettingsController(ServerStartupSettingsProvider.map(remoteVariables,
                    linkedModpack ? Set.of("AUTOMATIC_UPDATING") : Set.of()))::getSettings);
        }
        if (Config.configManager instanceof RemotelyConfigStore config) {
            settings.put("Discord", new DiscordRpcSettingsController(
                    platform.discordSettings(), config, platform.discordCapability())::getSettings);
        }
        ServerJvmSettingsController java = new ServerJvmSettingsController(platform.jvmSettingsProvider());
        if (state.restudioBackend() || state.restudioCreation()) java.bindToRemoteVariables(remoteVariables);
        if (!state.resourcePoolCreation()) settings.put("Java", java::getSettings);
        if (state.editMode()) {
            ServerBackupSettingsController backup = new ServerBackupSettingsController(screen, platform.backupProvider());
            settings.put("Backups", backup::getSettings);
            cleanup.add(backup::cleanup);
            ServerScheduleSettingsController schedules = new ServerScheduleSettingsController(screen, platform.scheduleProvider());
            settings.put("Schedules", schedules::getSettings);
            cleanup.add(schedules::cleanup);
        }
        if (state.editMode() && state.restudioBackend()) {
            ServerNetworkSettingsController network = new ServerNetworkSettingsController(screen, platform.portProvider());
            ServerSubuserSettingsController subusers = new ServerSubuserSettingsController(screen, platform.subuserProvider(), screen instanceof ServerConfigurationScreen configuration ? configuration::settingsOwner : () -> screen);
            settings.put("Network", network::getSettings);
            settings.put("Subusers", subusers::getSettings);
            cleanup.add(network::cleanup);
            cleanup.add(subusers::cleanup);
        }
        boolean compatible = platform.managementCompatible();
        if (compatible) settings.put("Management", new ServerManagementSettingsController(platform.managementSettings())::getSettings);
        boolean enabled = platform.managementEnabled();
        if (state.editMode()) {
            PlayerActionsSettingsController actions = new PlayerActionsSettingsController(platform.playerActionsFileProvider());
            settings.put("Player Actions", actions::getSettings);
            cleanup.add(actions::cleanup);
            if (compatible && enabled) {
                var liveProvider = platform.liveSettingsProvider();
                ServerGameRulesSettingsController rules = new ServerGameRulesSettingsController(liveProvider);
                ServerLiveSettingsController live = new ServerLiveSettingsController(liveProvider);
                settings.put("Live Settings", () -> {
                    List<Setting> result = new ArrayList<>(rules.getSettings());
                    result.addAll(live.getSettings());
                    return result;
                });
                cleanup.add(rules::cleanup);
                cleanup.add(live::cleanup);
            }
        }
        if (state.editMode() && !state.restudioCreation()) {
            ServerExtraSettingsController extra = new ServerExtraSettingsController(extraFiles, data.documentPaths(), platform.documentAccess());
            settings.put("Extra Files", () -> {
                List<String> availableFiles = new ArrayList<>(extraFiles == null ? List.of() : extraFiles);
                availableFiles.addAll(data.availableDocumentPaths());
                extra.updateFiles(availableFiles, data.documentPaths());
                return extra.getSettings();
            });
        }
        String title = state.editMode() ? "Edit " + platform.name()
                : state.resourcePoolCreation() ? "Create Server"
                : state.restudioCreation() ? "Order New Server" : "Create New Server";
        Runnable dispose = () -> cleanup.forEach(Runnable::run);
        return new ServerScreenHost.ConfigurationUi(settings, dispose, title,
                () -> plan == null ? "" : plan.getSelectedPlanName(),
                () -> subdomain == null ? plan == null ? "" : plan.getSubdomain() : subdomain.getText().strip().toLowerCase(Locale.ROOT),
                () -> plan == null ? null : plan.getCustomPlanRequest(),
                () -> locationField == null ? defaultLocation : locationField.getText().trim(),
                () -> {
                    if (profileChoices.isEmpty()) return new ServerScreenHost.PoolResources("", "", "", "", "", "", "", "");
                    ProfileChoice selected = profileChoices.get(Math.clamp(profile.getSelectedIndex(), 0, profileChoices.size() - 1));
                    return new ServerScreenHost.PoolResources(selected.gameId(), selected.profileId(),
                            preview.reserved(PoolAllocationEditor.Resource.RAM).toString(),
                            preview.reserved(PoolAllocationEditor.Resource.CPU).toString(),
                            preview.value(PoolAllocationEditor.Resource.RAM).toString(),
                            preview.value(PoolAllocationEditor.Resource.CPU).toString(),
                            preview.value(PoolAllocationEditor.Resource.DISK).toString(),
                            "0");
                }, () -> {
                    version.refreshRemoteVariables(remoteVariables);
                    if (profile == null) return;
                    ResourcePoolModels.DraftOptions available = options.get();
                    List<ProfileChoice> next = choices(available);
                    if (!profileChoices.equals(next)) {
                        ProfileChoice previous = profileChoices.isEmpty() ? null
                                : profileChoices.get(Math.clamp(profile.getSelectedIndex(), 0, profileChoices.size() - 1));
                        profileChoices.clear();
                        profileChoices.addAll(next);
                        profile.setOptions(profileChoices.stream().map(ProfileChoice::label).toList());
                        int selected = previous == null ? -1 : profileChoices.indexOf(previous);
                        profile.setSelectedIndex(selected >= 0 ? selected : profileIndex(profileChoices, remoteVariables.get("VERSION")));
                    }
                    profile.setActive(!profileChoices.isEmpty());
                    preview.acceptOptions(available);
                });
    }

    private static Supplier<List<Setting>> poolSettings(ReScreen screen, PoolCreationPreview preview,
                                                        List<Runnable> cleanup) {
        ResourcePoolScreen resources = screen instanceof ServerConfigurationScreen creation ? creation.poolResources() : null;
        PoolAllocationEditor editor = resources == null ? null : resources.poolEditor(preview.view().pool().id());
        ResourceAllocationBarWidget ramBar = new ResourceAllocationBarWidget(preview, editor, PoolAllocationEditor.Resource.RAM,
                id -> screen instanceof ServerConfigurationScreen creation ? creation.poolServerName(id) : id);
        ResourceAllocationBarWidget cpuBar = new ResourceAllocationBarWidget(preview, editor, PoolAllocationEditor.Resource.CPU,
                id -> screen instanceof ServerConfigurationScreen creation ? creation.poolServerName(id) : id);
        ResourceAllocationBarWidget diskBar = new ResourceAllocationBarWidget(preview, editor, PoolAllocationEditor.Resource.DISK,
                id -> screen instanceof ServerConfigurationScreen creation ? creation.poolServerName(id) : id);
        Runnable[] updateEditor = {() -> {}};
        Runnable refreshBars = () -> {
            if (editor != null) {
                for (PoolAllocationEditor.Resource resource : List.of(PoolAllocationEditor.Resource.RAM,
                        PoolAllocationEditor.Resource.CPU, PoolAllocationEditor.Resource.DISK)) {
                    preview.limit(resource, editor.previewAvailable(resource));
                }
                editor.hold(preview.reserved(PoolAllocationEditor.Resource.RAM),
                        preview.reserved(PoolAllocationEditor.Resource.CPU), preview.reserved(PoolAllocationEditor.Resource.DISK));
            }
            ramBar.refresh();
            cpuBar.refresh();
            diskBar.refresh();
            updateEditor[0].run();
        };
        Runnable refreshAndUpdate = () -> {
            refreshBars.run();
            if (resources != null) {
                resources.refreshBars(preview.view().pool().id());
                resources.updateChangeActions();
            }
        };
        CreationControl ram = creationSlider(preview, PoolAllocationEditor.Resource.RAM, refreshAndUpdate);
        CreationControl cpu = creationSlider(preview, PoolAllocationEditor.Resource.CPU, refreshAndUpdate);
        CreationControl disk = creationSlider(preview, PoolAllocationEditor.Resource.DISK, refreshAndUpdate);
        List<CreationControl> controls = List.of(ram, cpu, disk);
        ramBar.onChange(ram.refresh());
        cpuBar.onChange(cpu.refresh());
        diskBar.onChange(disk.refresh());
        MountableButtonWidget editRow = null;
        if (editor != null) {
            editRow = new MountableButtonWidget.Builder("Server Changes").build();
            MountableButtonWidget row = editRow;
            updateEditor[0] = () -> {
                ResourcePoolModels.Allocation selected = editor.selected();
                boolean restart = selected != null && selected.state() == ResourcePoolModels.AllocationState.PENDING
                        && new BigInteger(selected.desired().ramMiB())
                        .compareTo(new BigInteger(selected.effective().ramMiB())) < 0;
                String detail = resources.applyingChanges() ? "Applying Server Changes"
                        : editor.busy() ? "Applying Resource Change"
                        : restart ? "RAM Becomes Free After A Stop Or Restart • Refresh To Check Capacity"
                        : editor.submitted() ? "Waiting For Updated Pool Capacity • Refresh To Check"
                        : selected != null && editor.waitingForCapacity(selected.serverId())
                        ? "Waiting For Free Capacity • Apply Reductions And Refresh"
                        : editor.changed() ? "RAM " + editor.value(PoolAllocationEditor.Resource.RAM) + " MiB • CPU "
                        + editor.value(PoolAllocationEditor.Resource.CPU) + "% • Disk "
                        + editor.value(PoolAllocationEditor.Resource.DISK) + " MiB"
                        : editor.hasChanges() ? "Use Apply All Or Discard In Pool Capacity"
                        : "Drag An Existing Server Divider To Preview A Change";
                row.setDescription(detail + (editor.changeCount() > 1 ? " • " + editor.changeCount() + " Server Previews" : ""));
            };
            refreshBars.run();
            Runnable unwatch = resources.watchPool(preview.view().pool().id(), view -> {
                if (view == null) {
                    row.setDescription("Resource Pool Is Unavailable");
                    return;
                }
                preview.accept(view);
                editor.accept(view);
                ramBar.setAllocations(view.allocations());
                cpuBar.setAllocations(view.allocations());
                diskBar.setAllocations(view.allocations());
                ram.refresh().run();
                cpu.refresh().run();
                disk.refresh().run();
                refreshBars.run();
            });
            cleanup.add(unwatch);
            cleanup.add(resources.watchChanges(refreshBars));
            cleanup.add(() -> editor.hold(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
        }
        MountableButtonWidget existingEditor = editRow;
        List<Setting> poolSettings = new ArrayList<>();
        return () -> {
            if (!poolSettings.isEmpty()) return List.copyOf(poolSettings);
            Setting capacity = new Setting.Builder("Pool Capacity").build();
            if (resources != null && editor != null) {
                capacity.addTitleAction("Refresh", resources::refreshPool, "Refresh Pool Capacity", PopupWidget.TitleActionRole.SECONDARY);
                capacity.addTitleAction("Discard", () -> {
                    if (resources.hasChanges(preview.view().pool().id())) resources.discardChanges(preview.view().pool().id());
                }, "Discard All Resource Previews", PopupWidget.TitleActionRole.SECONDARY);
                capacity.addTitleAction("Apply All", () -> {
                    if (resources.canApplyChanges(preview.view().pool().id())) resources.applyChanges(preview.view().pool().id());
                }, "Apply All Previewed Server Changes", PopupWidget.TitleActionRole.PRIMARY);
            }
            for (ResourceAllocationBarWidget bar : List.of(ramBar, cpuBar, diskBar)) {
                bar.setHeight(18);
                capacity.addRow(new PopupWidget.PopupRow.Builder("", bar).minHeight(18).build());
            }
            if (existingEditor != null) capacity.addRow(new PopupWidget.PopupRow.Builder("", existingEditor).minHeight(24).build());
            capacity.fitContentHeight();
            Setting.Builder allocation = new Setting.Builder("Server Resources");
            allocation.addRow("", ram.row());
            allocation.addRow("", cpu.row());
            allocation.addRow("", disk.row());
            Setting serverResources = allocation.build();
            serverResources.setChanges(new Setting.Changes() {
                @Override
                public String validate() {
                    return controls.stream().map(control -> control.failure().get()).filter(value -> !value.isBlank()).findFirst().orElse("");
                }
            });
            poolSettings.add(capacity);
            poolSettings.add(serverResources);
            return List.copyOf(poolSettings);
        };
    }

    private static Supplier<List<Setting>> sharedPoolSettings(PoolCreationPreview preview, List<Runnable> cleanup) {
        List<CreationControl> controls = new ArrayList<>();
        Map<PoolAllocationEditor.Resource, BigInteger> initial = new EnumMap<>(PoolAllocationEditor.Resource.class);
        Setting.Builder resources = new Setting.Builder("Server Resources");
        for (PoolAllocationEditor.Resource resource : List.of(PoolAllocationEditor.Resource.RAM, PoolAllocationEditor.Resource.CPU, PoolAllocationEditor.Resource.DISK)) {
            initial.put(resource, preview.value(resource));
            CreationControl control = creationSlider(preview, resource, () -> {});
            controls.add(control);
            resources.addRow("", control.row());
        }
        Setting setting = resources.build();
        setting.setChanges(new Setting.Changes() {
            @Override
            public String validate() {
                String failure = controls.stream().map(control -> control.failure().get()).filter(value -> !value.isBlank()).findFirst().orElse("");
                return !failure.isBlank() ? failure : preview.possible() ? "" : "This Server Needs More Available Capacity During Setup";
            }

            @Override
            public boolean hasPending() {
                return initial.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(preview.value(entry.getKey())))
                        || controls.stream().anyMatch(control -> !control.failure().get().isBlank());
            }
        });
        List<Setting> resident = List.of(setting);
        cleanup.add(preview.watch(() -> controls.forEach(control -> control.refresh().run())));
        return () -> resident;
    }

    private static List<ProfileChoice> choices(ResourcePoolModels.DraftOptions options) {
        List<ProfileChoice> result = new ArrayList<>();
        for (ResourcePoolModels.GameOption game : options.games()) {
            if (!"minecraft:java".equals(game.id())) continue;
            for (ResourcePoolModels.ProfileOption profile : game.profiles()) {
                result.add(new ProfileChoice(game.id(), profile.id(), profile.label()));
            }
        }
        return List.copyOf(result);
    }

    private static int profileIndex(List<ProfileChoice> choices, String minecraftVersion) {
        String image = ReStudioJavaRuntimeResolver.resolveJavaDockerImage(minecraftVersion);
        if (image == null) return 0;
        String profile = switch (image) {
            case ReStudioJavaRuntimeResolver.JAVA_8_IMAGE -> "java8";
            case ReStudioJavaRuntimeResolver.JAVA_16_IMAGE -> "java16";
            case ReStudioJavaRuntimeResolver.JAVA_17_IMAGE -> "java17";
            case ReStudioJavaRuntimeResolver.JAVA_21_IMAGE -> "java21";
            case ReStudioJavaRuntimeResolver.JAVA_25_IMAGE -> "java25";
            default -> "";
        };
        for (int index = 0; index < choices.size(); index++) {
            if (profile.equalsIgnoreCase(choices.get(index).profileId().replace("_", ""))) return index;
        }
        return 0;
    }

    private static CreationControl creationSlider(PoolCreationPreview preview, PoolAllocationEditor.Resource resource,
                                                 Runnable refresh) {
        String title = switch (resource) {
            case RAM -> "RAM";
            case CPU -> "CPU";
            case DISK -> "Disk";
            case BACKUP -> "Backup Storage";
        };
        DoubleSliderWidget slider = new DoubleSliderWidget.Builder().size(230, 20)
                .value(preview.fraction(resource)).label(capacityText(preview.value(resource), resource))
                .fineStep(precision(preview, resource))
                .hint("Drag To Change The Server Allocation • Shift Drag For 1 MiB Or 1% Steps").build();
        TextInputWidget input = new TextInputWidget.Builder().text(preview.value(resource).toString())
                .numericOnly(true).size(72, 20).build();
        MountableButtonWidget row = new MountableButtonWidget.Builder(title).addWidget(slider).addWidget(input).build();
        boolean[] editing = {false};
        Runnable update = () -> {
            refresh.run();
            slider.label = capacityText(preview.value(resource), resource);
            slider.setValue(preview.fraction(resource));
            slider.setFineStep(precision(preview, resource));
            if (!input.isFocused() && !editing[0]) input.setText(preview.value(resource).toString());
            boolean available = preview.available(resource).compareTo(PoolCreationPreview.minimum(resource)) >= 0;
            boolean editable = preview.editLimit(resource).compareTo(PoolCreationPreview.minimum(resource)) >= 0;
            slider.setActive(editable);
            input.setActive(editable);
            String setup = (resource == PoolAllocationEditor.Resource.RAM || resource == PoolAllocationEditor.Resource.CPU)
                    && preview.reserved(resource).compareTo(preview.value(resource)) > 0
                    ? " • " + capacityText(preview.reserved(resource), resource) + " During Setup" : "";
            row.setDescription((available ? capacityText(preview.free(resource), resource) + " Free After Creation" : "No Capacity Available") + setup
                    + (resource == PoolAllocationEditor.Resource.DISK ? " • Server Files And Backups Share This Limit" : ""));
        };
        slider.onChange = () -> {
            preview.propose(resource, slider.getValue());
            input.setFocused(false);
            update.run();
        };
        Supplier<String> failure = () -> {
            try {
                BigInteger value = new BigInteger(input.getText().trim());
                return value.compareTo(PoolCreationPreview.minimum(resource)) < 0 || value.compareTo(preview.available(resource)) > 0
                        ? "Set " + title + " Within Available Capacity" : "";
            } catch (NumberFormatException invalid) {
                return "Enter A Whole " + title + " Value";
            }
        };
        input.onChange = () -> {
            editing[0] = true;
            try {
                if (!preview.set(resource, new BigInteger(input.getText().trim()))) {
                    row.setDescription("Enter A Value Within Available Capacity");
                    return;
                }
                update.run();
            } catch (NumberFormatException invalid) {
                row.setDescription("Enter A Whole MiB Or CPU Percent Value");
            } finally {
                editing[0] = false;
            }
        };
        input.onEnter = () -> {
            String error = failure.get();
            if (!error.isBlank()) {
                row.setDescription(error);
                return;
            }
            input.onChange.run();
            input.setFocused(false);
            update.run();
        };
        slider.setTextCommitHandler(text -> {
            try {
                if (preview.set(resource, parseCapacity(text, resource))) update.run();
                else row.setDescription("Enter A Value Within Available Capacity");
            } catch (NumberFormatException | ArithmeticException error) {
                row.setDescription("Enter A Whole MiB Or CPU Percent Value");
            }
        });
        update.run();
        return new CreationControl(row, update, failure);
    }

    private static BigInteger parseCapacity(String text, PoolAllocationEditor.Resource resource) {
        String value = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (resource == PoolAllocationEditor.Resource.CPU) return new BigInteger(value.replace("%", ""));
        if (value.endsWith("gib")) {
            return new BigDecimal(value.substring(0, value.length() - 3)).multiply(BigDecimal.valueOf(1024))
                    .toBigIntegerExact();
        }
        if (value.endsWith("mib")) value = value.substring(0, value.length() - 3);
        return new BigInteger(value);
    }

    private static double precision(PoolCreationPreview preview, PoolAllocationEditor.Resource resource) {
        BigInteger span = preview.editLimit(resource).subtract(PoolCreationPreview.minimum(resource));
        return span.signum() <= 0 ? 0 : 1.0 / span.doubleValue();
    }

    private static String capacityText(BigInteger value, PoolAllocationEditor.Resource resource) {
        if (resource == PoolAllocationEditor.Resource.CPU) return value + "%";
        if (value.compareTo(BigInteger.valueOf(1024)) < 0) return value + " MiB";
        return new BigDecimal(value).divide(BigDecimal.valueOf(1024), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + " GiB";
    }

    private record CreationControl(MountableButtonWidget row, Runnable refresh, Supplier<String> failure) {
    }

    private record ProfileChoice(String gameId, String profileId, String label) {
    }
}
