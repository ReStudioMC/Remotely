package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.network.HostedNetworkPendingStore;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DeletionPopup;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.util.Notification;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class NetworkMemberScreen extends ReScreen implements DesktopWindowBehaviorProvider {
    private enum Source { EXISTING, NEW }
    private record Loaded(NetworkCreationContext context, List<NetworkOverviewProvider.ServerView> servers) { }
    private final Screen parent;
    private final RemotelyClient remotelyClient;
    private final ServerScreenHost host;
    private final NetworkOverviewProvider provider;
    private final String networkId;
    private final BooleanSupplier admission;
    private final String account;
    private final String authenticationSession;
    private NetworkCreationContext context;
    private List<NetworkOverviewProvider.ServerView> servers = List.of();
    private ServerConfigurationDraft draft;
    private NetworkPoolBudget budget;
    private ResourcePoolController.Creation creation;
    private HostedNetworkPendingStore.PendingAttach pending;
    private Object createdServer;
    private boolean savedConfiguration;
    private boolean serversLoaded;
    private boolean loading;
    private boolean adding;
    private boolean closed;
    private boolean ownerChanged;
    private boolean selecting;
    private String failure = "";
    private long generation;
    private Container content;
    private Setting sourceSetting;
    private Setting resourcesSetting;
    private Setting statusSetting;
    private ConfigOption<Source> source;
    private ConfigOption<String> server;
    private ConfigOption<Boolean> reSync;
    private MountableButtonWidget status;
    private IconButton addButton;
    private IconButton cancelButton;
    private IconButton retryButton;
    private IconButton discardButton;
    private Notification additionNotice;

    public NetworkMemberScreen(Screen parent, RemotelyClient client, NetworkOverviewProvider provider, String networkId) {
        this.parent = parent;
        enableNavigation(parent);
        this.remotelyClient = Objects.requireNonNull(client);
        this.host = client.getHost().serverScreenHost(client);
        this.provider = Objects.requireNonNull(provider);
        this.networkId = networkId;
        this.admission = provider.mutationAdmission();
        this.account = host.hostedNetworkAccount();
        this.authenticationSession = host.authenticationSession();
    }

    @Override
    public String getDesktopAppTitle() {
        return "Add Server";
    }

    @Override
    public String getDesktopAppIconPath() {
        return "server.png";
    }

    @Override
    public boolean closeThroughDesktopOverlay() {
        return !adding;
    }

    @Override
    public void init() {
        super.init();
        if (source == null) buildOptions();
        header().addLeft(cancelButton).addRight(addButton).build();
        content = createContainer(6, 36, width - 12, Math.max(80, height - 42));
        content.layout(new ManagedLayout()).columns(1).padding(4).verticalSpacing(6).scrolling(true);
        setActiveContainer(content);
        renderSettings();
        if (!serversLoaded && pending == null && !loading) load();
    }

    private void buildOptions() {
        source = ConfigOption.<Source>builder("Source").description("Use A Server On This Network's Host Or Create A New One")
                .bind(() -> Source.EXISTING, value -> {}).defaultValue(Source.EXISTING).options(List.of(Source.EXISTING, Source.NEW))
                .displayFunction(value -> value == Source.NEW ? "New Server" : "Existing Server")
                .dependsOn(this::editable).resettable(false).build();
        server = ConfigOption.<String>builder("Server").description("Choose An Available Server From The Same Host")
                .bind(() -> "", value -> {}).defaultValue("").options(List.of(""))
                .displayFunction(value -> servers.stream().filter(item -> item.id().equals(value)).map(NetworkOverviewProvider.ServerView::name).findFirst().orElse("Choose A Server"))
                .visibleWhen(() -> source.get() == Source.EXISTING)
                .dependsOn(this::editable).resettable(false).build();
        reSync = ConfigOption.<Boolean>builder("Install ReSync").description("Enable Live Status And Shared Network Features")
                .bind(() -> true, value -> {}).defaultValue(true).dependsOn(this::editable).resettable(false).build();
        source.addChangeListener(value -> {
            if (selecting || !current(generation)) return;
            failure = "";
            if (budget != null) budget.setActive("new", value == Source.NEW);
            if (value == Source.NEW && draft == null && !loading && context != null) loadDraft();
            renderSettings();
        });
        server.addChangeListener(value -> {
            if (!selecting && current(generation)) renderSettings();
        });
        sourceSetting = new Setting.Builder("Server Source").addOption(source).addOption(server).addOption(reSync).build();
        status = new MountableButtonWidget.Builder("Loading Servers").description("Loading This Network's Host").build();
        retryButton = new IconButton.Builder().label("Retry").imagePath("reload.png").onClick(this::retry).size(90, 20).build();
        discardButton = new IconButton.Builder().label("Discard Request").imagePath("delete.png").onClick(this::discard).size(150, 20).build();
        Setting.Builder request = new Setting.Builder("Server Request");
        request.addRow("", status).addRow("retry", "", retryButton).addRow("discard", "", discardButton);
        statusSetting = request.build();
        addButton = new IconButton.Builder().size(18, 18).label("Add Server").imagePath("checkmark.png").autoWidthOnTextChange(true).onClick(this::add).build();
        cancelButton = new IconButton.Builder().size(18, 18).label("Cancel").imagePath("goback.png").autoWidthOnTextChange(true).onClick(this::close).build();
    }

    private boolean current(long request) {
        return !closed && !ownerChanged && request == generation && admission.getAsBoolean()
                && authenticationSession.equals(host.authenticationSession())
                && remotelyClient.getHost().serverScreenHost(remotelyClient) == host && Objects.equals(account, host.hostedNetworkAccount());
    }

    private boolean editable() {
        return !inputBlocked() && !adding && pending == null && createdServer == null;
    }

    private boolean inputBlocked() {
        return loading || !current(generation) || pending == null && createdServer == null && draft != null
                && source.get() == Source.NEW && !draft.ready() && draft.failure().isBlank();
    }

    private void requireCurrent(long request) {
        if (!current(request)) throw new IllegalStateException("Server Setup Changed. Reopen The Network");
    }

    private <T> Async<T> onUi(long request, Supplier<T> action) {
        Async<T> result = Async.pending();
        ScreenManager.getInstance().execute(() -> {
            try {
                requireCurrent(request);
                result.complete(action.get());
            } catch (Throwable error) {
                result.fail(error);
            }
        });
        return result;
    }

    private void retry() {
        if (!editable()) return;
        failure = "";
        if (!serversLoaded) load();
        else if (source.get() == Source.NEW && draft == null) loadDraft();
        else if (source.get() == Source.NEW && !draft.failure().isBlank()) draft.reload(host);
        renderSettings();
    }

    private void load() {
        if (loading || adding || !current(generation)) return;
        loading = true;
        failure = "";
        long request = ++generation;
        status.setMessage("Loading Servers");
        status.setDescription("Loading This Network's Host");
        renderSettings();
        Async<Loaded> setup;
        try {
            if (provider.reactorNetwork()) {
                context = new NetworkCreationContext(true, null);
                pending = host.hostedNetworkPendingStore().attachment(account, networkId);
                if (pending != null) {
                    loading = false;
                    renderSettings();
                    return;
                }
            }
            setup = provider.serverCreationContext(networkId).thenCompose(value -> {
                requireCurrent(request);
                return provider.availableServers(networkId).thenApply(available -> new Loaded(value, available));
            });
        } catch (Throwable error) {
            setup = Async.failed(error);
        }
        setup.whenComplete((loaded, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(request)) return;
            loading = false;
            if (error != null) {
                failure = message(error);
                status.setMessage("Servers Unavailable");
                status.setDescription(failure);
            } else {
                context = loaded.context();
                servers = List.copyOf(loaded.servers());
                serversLoaded = true;
                selecting = true;
                try {
                    server.setOptions(servers.stream().map(NetworkOverviewProvider.ServerView::id).toList());
                    if (servers.stream().noneMatch(value -> value.id().equals(server.get()))) {
                        server.set(servers.isEmpty() ? "" : servers.getFirst().id());
                    }
                    if (servers.isEmpty()) source.set(Source.NEW);
                } finally {
                    selecting = false;
                }
                status.setMessage(context.reactor() ? "Reactor" : context.host() == null ? "Local" : context.host().name());
                status.setDescription(servers.isEmpty() ? "Create A Server Using This Network's Host" : "Only Servers Available For This Network Are Listed");
                if (source.get() == Source.NEW && draft == null && pending == null) loadDraft();
            }
            renderSettings();
        }));
    }

    private void loadDraft() {
        if (loading || draft != null || context == null || pending != null || !current(generation)) return;
        loading = true;
        failure = "";
        long request = ++generation;
        status.setMessage("Preparing Server");
        status.setDescription("Loading Server Configuration");
        Async<ServerConfigurationDraft> setup;
        try {
            if (context.reactor()) {
                Async<ResourcePoolModels.DraftOptions> draftOptions = host.resourcePools().getDraftOptions();
                Async<List<ResourcePoolModels.Pool>> availablePools = pools(request, 0, new ArrayList<>());
                setup = draftOptions.thenCompose(options -> availablePools.thenCompose(pools -> {
                    requireCurrent(request);
                    ResourcePoolModels.Pool pool = pools.stream().filter(value -> slots(value, options).signum() > 0)
                            .max((first, second) -> slots(first, options).compareTo(slots(second, options)))
                            .orElseThrow(() -> new IllegalStateException("No Reactor Resources Are Available"));
                    ResourcePoolController.PoolView view = new ResourcePoolController.PoolView(pool, List.of(), List.of(), Map.of());
                    return onUi(request, () -> {
                        budget = new NetworkPoolBudget(view, options);
                        PoolCreationPreview preview = budget.create("new", 1);
                        creation = new ResourcePoolController.Creation(pool.id(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                        return preview;
                    }).thenCompose(preview -> {
                        requireCurrent(request);
                        return ServerConfigurationDraft.create(this, host, "PAPER", "Backend", null, view, options, 1, preview);
                    });
                }));
            } else setup = ServerConfigurationDraft.create(this, host, "PAPER", "Backend", context.host());
        } catch (Throwable error) {
            setup = Async.failed(error);
        }
        setup.whenComplete((value, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(request)) {
                if (value != null) value.close();
                return;
            }
            loading = false;
            if (error != null) {
                failure = message(error);
                status.setMessage("Configuration Unavailable");
                status.setDescription(failure);
            } else {
                draft = value;
                draft.onChanged(() -> {
                    if (draft == value && current(generation)) renderSettings();
                });
                if (budget != null) {
                    Setting.Builder resources = new Setting.Builder("Network Resources");
                    for (PoolAllocationEditor.Resource resource : List.of(PoolAllocationEditor.Resource.RAM, PoolAllocationEditor.Resource.CPU, PoolAllocationEditor.Resource.DISK)) {
                        ResourceAllocationBarWidget bar = new ResourceAllocationBarWidget(resource, ignored -> draft.target().name());
                        bar.setNetwork(budget);
                        bar.editable(() -> editable() && source.get() == Source.NEW && draft.ready());
                        bar.setHeight(22);
                        resources.addRow("", bar);
                    }
                    resourcesSetting = resources.build();
                    budget.setActive("new", source.get() == Source.NEW);
                    budget.onChanged(() -> {
                        if (current(generation)) renderSettings();
                    });
                }
                status.setMessage(context.reactor() ? "Reactor" : context.host() == null ? "Local" : context.host().name());
                status.setDescription("The Server Will Be Added To This Network");
            }
            renderSettings();
        }));
        renderSettings();
    }

    private Async<List<ResourcePoolModels.Pool>> pools(long request, int page, List<ResourcePoolModels.Pool> values) {
        requireCurrent(request);
        return host.resourcePools().listPools(page, 50).thenCompose(result -> {
            requireCurrent(request);
            values.addAll(result.items());
            if (!result.hasMore()) return Async.completed(List.copyOf(values));
            if (result.items().isEmpty() || page >= 99) return Async.failed(new IllegalStateException("Reactor Resources Could Not Be Loaded"));
            return pools(request, page + 1, values);
        });
    }

    private static BigInteger slots(ResourcePoolModels.Pool pool, ResourcePoolModels.DraftOptions options) {
        ResourcePoolModels.Resources available = pool.balance().available();
        return new BigInteger(available.ramMiB()).divide(BigInteger.valueOf(Math.max(1, options.minimumInstallerRamMiB())))
                .min(new BigInteger(available.cpuQuotaPercent()).divide(BigInteger.valueOf(Math.max(1, options.minimumInstallerCpuPercent()))))
                .min(new BigInteger(available.diskMiB()));
    }

    private void renderSettings() {
        if (content == null || closed) return;
        boolean editable = editable();
        sourceSetting.setVisible(pending == null && createdServer == null);
        sourceSetting.refreshState();
        sourceSetting.setActive(editable);
        if (ownerChanged) {
            status.setMessage("Account Changed");
            status.setDescription("Reopen The Network Using The Current Account");
        } else if (pending != null) {
            status.setMessage(pending.terminal() == null ? "Saved Server Request" : "Server Request Needs Review");
            status.setDescription(pending.terminal() == null ? "Resume To Check And Finish The Original Request"
                    : "Review The Network's Activity. Created Servers Are Kept Until You Choose What To Do Next");
        } else if (createdServer != null) {
            status.setMessage("Server Created");
            status.setDescription(failure.isBlank() ? "Retry Attachment To Add This Server To The Network"
                    : "The Created Server Is Kept. " + failure);
        } else if (source.get() == Source.NEW && draft != null && failure.isBlank()) {
            status.setMessage(draft.ready() ? "New Server" : draft.failure().isBlank() ? "Preparing Server" : "Configuration Unavailable");
            status.setDescription(draft.ready() ? "The Server Will Be Added To This Network"
                    : draft.failure().isBlank() ? "Loading Server Configuration" : draft.failure());
        }
        boolean retry = !serversLoaded || source.get() == Source.NEW && (draft == null || !draft.failure().isBlank());
        retryButton.setActive(editable && retry);
        statusSetting.setRowVisibility("retry", !loading && pending == null && createdServer == null && retry);
        discardButton.setActive(!adding && !loading && pending != null && current(generation));
        statusSetting.setRowVisibility("discard", pending != null);
        List<AnimatedWidget> widgets = new ArrayList<>(List.of(sourceSetting, statusSetting));
        if (draft != null) {
            boolean visible = source.get() == Source.NEW && pending == null && createdServer == null;
            List<Setting> settings = new ArrayList<>(draft.sections());
            settings.addAll(draft.resourceSettings());
            if (resourcesSetting != null) settings.addFirst(resourcesSetting);
            for (Setting setting : settings) {
                setting.setVisible(visible);
                setting.setActive(editable && draft.ready());
                setting.fitContentHeight();
                widgets.add(setting);
            }
        }
        sourceSetting.fitContentHeight();
        statusSetting.fitContentHeight();
        if (!content.getWidgets().equals(widgets)) content.replaceWidgets(widgets, true);
        addButton.setMessage(pending != null ? pending.terminal() == null ? "Resume Request" : "Review Outcome"
                : createdServer != null ? "Retry Attachment" : "Add Server");
        addButton.setActive(!adding && !loading && current(generation) && context != null && (pending != null || createdServer != null
                || serversLoaded && (source.get() == Source.EXISTING && !server.get().isBlank()
                    || source.get() == Source.NEW && draft != null && draft.ready() && (budget == null || budget.possible()))));
        cancelButton.setActive(!adding);
        content.updateWidgetPositions();
    }

    private void add() {
        if (adding || loading || context == null || !current(generation)) return;
        if (pending != null && pending.terminal() != null) {
            close();
            return;
        }
        adding = true;
        failure = "";
        long request = ++generation;
        Notification notice = new Notification.Builder().message(pending == null ? "Adding Server" : "Resuming Server Request")
                .description("Checking And Updating The Network").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        additionNotice = notice;
        Consumer<NetworkOperationStatus> progress = value -> ScreenManager.getInstance().execute(() -> {
            if (current(request) && adding) notice.update().description(value.message()).commit();
        });
        Async<Void> operation;
        try {
            if (pending != null) operation = provider.addServer(networkId, null, progress);
            else if (source.get() == Source.EXISTING) {
                NetworkOverviewProvider.ServerView selected = servers.stream().filter(value -> value.id().equals(server.get())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Choose An Available Server"));
                operation = provider.addServer(networkId, new NetworkOverviewProvider.AttachRequest(selected.serverId(), "", reSync.get(), null, selected.name()), progress);
            } else if (context.reactor()) {
                draft.apply();
                NetworkMemberSource.Draft selected = draft.hostedSource(creation);
                requireDraft(selected);
                operation = provider.addServer(networkId, new NetworkOverviewProvider.AttachRequest("", "", reSync.get(), selected, draft.target().name()),
                        progress);
            } else {
                if (createdServer == null) {
                    draft.apply();
                    requireDraft(null);
                }
                NetworkCreationPlan.Server plan = new NetworkCreationPlan.Server("", draft.target().raw(), context.host(), draft.location(), draft.settings(),
                        false, draft.target().name(), NetworkMemberRole.GAMEPLAY, 0, reSync.get());
                operation = provider.validateServerAddition(networkId).thenCompose(ignored -> {
                    requireCurrent(request);
                    if (createdServer != null) return Async.completed(createdServer);
                    return host.createNetworkServer(plan).thenCompose(value -> onUi(request, () -> {
                        createdServer = Objects.requireNonNull(value, "Created Server Is Unavailable");
                        return createdServer;
                    }));
                }).thenCompose(value -> {
                    requireCurrent(request);
                    if (savedConfiguration) return Async.completed(null);
                    return host.saveInstanceConfiguration(value, draft.settings()).thenCompose(ignored -> onUi(request, () -> {
                        savedConfiguration = true;
                        return null;
                    }));
                }).thenCompose(ignored -> {
                    requireCurrent(request);
                    return provider.addServer(networkId,
                            new NetworkOverviewProvider.AttachRequest(host.configurationTarget(createdServer).id(), "", reSync.get()), progress);
                });
            }
        } catch (Throwable error) {
            operation = Async.failed(error);
        }
        renderSettings();
        operation.whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(request)) return;
            adding = false;
            if (error == null) {
                notice.update().message("Server Added").description("The Network Has Been Updated").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
                host.reloadInstances();
                ScreenManager.getInstance().goBack(this, parent);
            } else {
                failure = message(error);
                notice.update().message("Server Addition Failed").description(failure).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                status.setMessage("Server Addition Failed");
                status.setDescription(failure);
                if (context.reactor()) {
                    try {
                        pending = host.hostedNetworkPendingStore().attachment(account, networkId);
                    } catch (RuntimeException savedError) {
                        loading = true;
                        status.setDescription("Reopen The Network To Check The Saved Server Request");
                    }
                }
                renderSettings();
            }
        }));
    }

    private void requireDraft(NetworkMemberSource.Draft source) {
        ServerScreenHost.ActionAvailability availability = provider.serverDraftAvailability(networkId, draft.target(), source);
        if (!availability.available()) throw new IllegalArgumentException(availability.reason());
    }

    private void discard() {
        if (adding || loading || pending == null || !current(generation)) return;
        HostedNetworkPendingStore.PendingAttach selected = pending;
        long request = generation;
        DeletionPopup.show(this, List.of(
                DeletionPopup.entry("Discard Saved Server Request", "network.png"),
                DeletionPopup.entry("Any Created Servers Are Kept", "server.png"),
                DeletionPopup.entry("Creation Already Started On Reactor Continues", "info.png")),
                DeletionPopup.Action.action("Discard Request", "delete.png", true, popup -> {
                    if (adding || loading || pending != selected || !current(request)) return;
                    try {
                        host.hostedNetworkPendingStore().discardAttachment(account, selected.command().requestId(), networkId);
                    } catch (RuntimeException error) {
                        new Notification("Discard Failed", message(error), Notification.Type.ERROR);
                        return;
                    }
                    popup.hide();
                    ScreenManager.getInstance().replaceScreen(this, new NetworkMemberScreen(parent, remotelyClient, provider, networkId));
                }));
    }

    private static String message(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure.getMessage() == null ? "Server Request Failed" : failure.getMessage();
    }

    @Override
    public void close() {
        if (!adding) ScreenManager.getInstance().goBack(this, parent);
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (adding) return true;
        if (event.key() == ReKey.ESCAPE) {
            close();
            return true;
        }
        return inputBlocked() || super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (adding || inputBlocked() && content != null && content.isMouseOver(event.x(), event.y())) return true;
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        return adding || inputBlocked() || super.mouseDragged(event);
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        return adding || inputBlocked() || super.mouseScrolled(event);
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        return adding || inputBlocked() || super.textInput(event);
    }

    @Override
    public void tick() {
        super.tick();
        if (closed || ownerChanged || current(generation)) return;
        ownerChanged = true;
        generation++;
        loading = false;
        adding = false;
        if (additionNotice != null) {
            additionNotice.update().message("Account Changed").description("Reopen The Network To Check The Server Request")
                    .type(Notification.Type.WARN).loading(false).autoSlideOut(true).commit();
        }
        renderSettings();
    }

    @Override
    public void onDesktopWindowClosing() {
        release();
    }

    private void release() {
        if (closed) return;
        closed = true;
        generation++;
        if (budget != null) budget.onChanged(null);
        if (draft != null) draft.close();
    }

    @Override
    public void removed() {
        release();
        super.removed();
    }
}
