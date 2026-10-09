package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.servers.ReProxyTarget;
import redxax.oxy.remotely.servers.reproxy.ReProxyIntegrations;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rebase.reproxy.ReProxyModels.Address;
import restudio.rebase.reproxy.ReProxyModels.AddressSpec;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.reproxy.ReProxyModels.ConnectionPatch;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.EndpointSpec;
import restudio.rebase.reproxy.ReProxyModels.PublicPort;
import restudio.rebase.reproxy.ReProxyModels.Suffix;
import restudio.rebase.reproxy.ReProxyModels.Summary;
import restudio.rebase.reproxy.ReProxyModels.Target;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rebase.ui.screens.resources.ResourceForwarding;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ReProxyServerSettingsController {
    private static final Identifier LOADING = Identifier.animatedIcon("loadingBlue");
    private static final Identifier CONNECT = Identifier.icon("reverse.png");
    private static final Identifier DISCONNECT = Identifier.icon("closeReverse.png");
    private final ServerScreenHost host;
    private final Object server;
    private final String serverKey;
    private final boolean network;
    private final ServerScreenHost.AccountIdentity account;
    private final String accountNamespace;
    private final String authenticationSession;
    private final ResourceForwarding forwarding;
    private final Setting connectionSetting = new Setting.Builder("Server Address").build();
    private final Setting portsSetting = new Setting.Builder("Ports").build();
    private final Setting pluginsSetting = new Setting.Builder("Plugins").build();
    private final List<Setting> settings = List.of(connectionSetting, portsSetting, pluginsSetting);
    private final List<Setting> loginSettings = List.of(messageSetting("ReStudio Login Required"));
    private final List<Setting> changedAccountSettings = List.of(messageSetting("Reopen Server Settings"));
    private final Map<String, PopupRow> portRows = new LinkedHashMap<>();
    private final Map<String, PopupRow> pluginRows = new LinkedHashMap<>();
    private final MountableButtonWidget address;
    private final IconButton connect;
    private Summary summary;
    private Connection connection;
    private List<ResourceContainerItem> resources = List.of();
    private Connection stamp;
    private Async<List<ResourceContainerItem>> inventory;
    private AutoCloseable watch;
    private List<ResourceContainerItem> knownPlugins = List.of();
    private boolean loaded;
    private boolean loading;
    private boolean busy;
    private boolean closed;
    private boolean creationChecked;
    private long generation;
    private long form;
    private PopupWidget popup;
    private ItemSelectorWidget selector;
    private Supplier<Screen> owner = () -> ScreenManager.getInstance().getCurrentScreen();
    private Runnable changed = () -> {};
    private final Runnable membershipListener = this::queueMembership;

    private static Setting messageSetting(String value) {
        Setting.Builder builder = new Setting.Builder("ReProxy");
        builder.addRow("", message(value));
        return builder.build();
    }

    public ReProxyServerSettingsController(ServerScreenHost host, Object server, ReProxyTarget target) {
        this(host, server, target, false);
    }

    public ReProxyServerSettingsController(ServerScreenHost host, Object server, ReProxyTarget target, boolean network) {
        this.host = host;
        this.server = server;
        this.network = network;
        serverKey = target.key();
        account = host.accountIdentity();
        accountNamespace = host.hostedNetworkAccount();
        authenticationSession = host.authenticationSession();
        forwarding = host.serverResourceForwarding(server);
        connect = action("reverse.png", "Start ReProxy", this::toggleConnection);
        address = new MountableButtonWidget.Builder("Loading Address").description("Use This Address To Join Your Server")
                .addWidget(action("edit.png", "Change Address", this::chooseAddress)).addWidget(connect)
                .addWidget(action("reload.png", "Refresh", this::refreshAll)).build();
        connectionSetting.setRows(List.of(row("", address)));
        portsSetting.setRows(List.of(row("", message("Loading Ports"))));
        pluginsSetting.setRows(List.of(row("", message("Loading Plugins"))));
        host.addNetworkChangeListener(membershipListener);
    }

    public void owner(Supplier<Screen> owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void onChanged(Runnable changed) {
        this.changed = changed == null ? () -> {} : changed;
    }

    private boolean addressAllowed() {
        ServerScreenHost.NetworkRole role = host.networkRole(server);
        return network ? role == ServerScreenHost.NetworkRole.PROXY : role == ServerScreenHost.NetworkRole.NONE;
    }

    private void queueMembership() {
        host.application().execute(this::membershipChanged);
    }

    private void membershipChanged() {
        if (closed) return;
        if (!addressAllowed()) {
            form++;
            hideForm();
        }
        connectionSetting.setVisible(addressAllowed());
        refresh();
    }

    public List<Setting> getSettings() {
        ServerScreenHost.AccountIdentity currentAccount = host.accountIdentity();
        if (!account.authenticated() || !currentAccount.authenticated() || !account.subjectId().equals(currentAccount.subjectId())
                || !Objects.equals(accountNamespace, host.hostedNetworkAccount())
                || !Objects.equals(authenticationSession, host.authenticationSession())) {
            return currentAccount.authenticated() ? changedAccountSettings : loginSettings;
        }
        if (!closed && watch == null) {
            long epoch = generation;
            watch = ReProxyManager.watchServer(target(), value -> host.application().execute(() -> {
                if (!current(epoch) || !loaded || loading || Objects.equals(connection, value)) return;
                connection = value;
                Summary current = ReProxyManager.accountSummary();
                if (current != null) summary = current;
                updateRows();
            }));
        }
        if (!closed && !creationChecked && addressAllowed()) {
            creationChecked = true;
            if (host.pendingReProxyCreation(server)) resumeCreation();
        }
        if (!closed && !loaded && !loading) reload();
        connectionSetting.setVisible(addressAllowed());
        return addressAllowed() ? settings : List.of(portsSetting, pluginsSetting);
    }

    public void activate() {
        cleanup();
        closed = false;
        loaded = false;
        loading = false;
        busy = false;
        creationChecked = false;
        inventory = null;
        pluginRows.clear();
        pluginsSetting.setRows(List.of(row("", message("Loading Plugins"))));
        host.addNetworkChangeListener(membershipListener);
    }

    public void cleanup() {
        closed = true;
        generation++;
        form++;
        if (watch != null) {
            try { watch.close(); } catch (Exception ignored) { }
            watch = null;
        }
        hideForm();
        host.removeNetworkChangeListener(membershipListener);
    }

    private ReProxyTarget target() {
        ReProxyTarget target = host.reProxyTarget(server);
        ServerScreenHost.AccountIdentity current = host.accountIdentity();
        if (closed || target == null || !host.supportsReProxy(server) || !serverKey.equals(target.key())
                || !account.authenticated() || !current.authenticated() || !account.subjectId().equals(current.subjectId())
                || !Objects.equals(accountNamespace, host.hostedNetworkAccount())
                || !Objects.equals(authenticationSession, host.authenticationSession())) {
            throw new Async.Cancellation();
        }
        return target;
    }

    private boolean current(long epoch) {
        if (generation != epoch) return false;
        try { target(); return true; } catch (Async.Cancellation ignored) { return false; }
    }

    private void refreshAll() {
        if (busy || loading || closed) return;
        if (addressAllowed() && host.pendingReProxyCreation(server)) {
            creationChecked = true;
            resumeCreation();
            return;
        }
        inventory = null;
        loaded = false;
        long epoch = generation;
        ReProxyManager.refreshSummary().whenComplete((ignored, failure) -> host.application().execute(() -> {
            if (!current(epoch)) return;
            if (failure != null) failure(failure);
            reload();
        }));
    }

    private void resumeCreation() {
        if (closed || busy || loading || !addressAllowed()) return;
        long epoch = generation;
        loading = true;
        busy = true;
        updateAddress();
        host.resumeReProxyCreation(server).whenComplete((ignored, error) -> host.application().execute(() -> {
            if (!current(epoch)) return;
            loading = false;
            busy = false;
            loaded = false;
            if (error != null) failure(error);
            reload();
        }));
    }

    private void reload() {
        if (loading || closed) return;
        long epoch = generation;
        try { target(); } catch (Throwable failure) { return; }
        loading = true;
        if (inventory == null) inventory = host.serverResources(server);
        updateAddress();
        ReProxyManager.serverSummary().thenCompose(value -> {
            if (!current(epoch)) return Async.failed(new Async.Cancellation());
            Summary snapshot = value;
            return inventory.thenApply(items -> {
                host.application().execute(() -> {
                    if (!current(epoch)) return;
                    try {
                        summary = snapshot;
                        connection = ReProxyManager.serverConnection(target());
                        resources = items == null ? List.of() : List.copyOf(items);
                        List<ResourceContainerItem> recognized = new ArrayList<>();
                        collect(resources, recognized);
                        knownPlugins = List.copyOf(recognized);
                        forwarding.admit(resources);
                        loaded = true;
                        loading = false;
                        updateRows();
                    } catch (Throwable failure) {
                        loading = false;
                        loaded = true;
                        failure(failure);
                        updateAddress();
                    }
                });
                return null;
            });
        }).whenComplete((ignored, failure) -> host.application().execute(() -> {
            if (!current(epoch)) return;
            loading = false;
            if (failure != null) {
                loaded = true;
                updateAddress();
                address.setName("Could Not Load Address");
                address.setDescription(ReProxyManager.failureMessage(failure));
                portsSetting.setRows(List.of(row("", message("Refresh To Try Again"))));
                pluginsSetting.setRows(List.of(row("", message("Refresh To Try Again"))));
                refresh();
            }
        }));
    }

    private void updateAddress() {
        boolean online = host.isReProxyForwarded(server);
        address.setName(connection != null && connection.address() != null ? connection.address().publicHost() : loading ? "Loading Address" : "Choose An Address");
        address.setDescription(busy ? "Updating Connection" : loading ? "Loading" : online ? "Connected" : connection == null ? "Choose An Address To Share This Server" : "Ready To Connect");
        connect.setIcon(busy || loading ? LOADING : online ? DISCONNECT : CONNECT);
        connect.setHint(busy || loading ? "Updating Connection" : online ? "Stop ReProxy" : "Start ReProxy");
    }

    private void updateRows() {
        updateAddress();
        if (!Objects.equals(connection, stamp)) {
            stamp = connection;
            portRows.clear();
            if (connection != null) for (Endpoint endpoint : connection.endpoints()) {
                MountableButtonWidget widget = new PortWidget(endpoint);
                portRows.put(endpoint.id(), row("", widget));
            }
        }
        List<PopupRow> ports = new ArrayList<>();
        ports.add(row("", new AnimatedButton.Builder().label("Add Port").onClick(() -> editPort(null)).active(connection != null && !busy).build()));
        ports.addAll(portRows.values());
        if (connection == null) ports.add(row("", message("Choose An Address First")));
        portsSetting.setRows(ports);
        Map<String, PopupRow> nextPlugins = new LinkedHashMap<>();
        for (ResourceContainerItem resource : knownPlugins) {
            String key = ResourceContainerItem.key(resource);
            PopupRow existing = pluginRows.get(key);
            if (existing == null) {
                ReProxyIntegrations.Integration integration = ReProxyIntegrations.find(resource);
                IconButton button = new PluginButton(resource);
                MountableButtonWidget widget = new MountableButtonWidget.Builder(integration.name())
                        .description("Connect Using This Server Address").addWidget(button).build();
                existing = row("", widget);
            }
            nextPlugins.put(key, existing);
        }
        pluginRows.clear();
        pluginRows.putAll(nextPlugins);
        pluginsSetting.setRows(pluginRows.isEmpty() ? List.of(row("", message("No Supported Plugins Installed"))) : List.copyOf(pluginRows.values()));
        refresh();
    }

    private void collect(List<ResourceContainerItem> items, List<ResourceContainerItem> found) {
        for (ResourceContainerItem resource : items) {
            if (ReProxyIntegrations.find(resource) != null) found.add(resource);
            collect(resource.getChildren(), found);
        }
    }

    private boolean pluginsBusy() {
        return knownPlugins.stream().anyMatch(resource -> forwarding.state(resource).busy());
    }

    private boolean pluginPort(Endpoint endpoint) {
        return forwarding.ownsPort(endpoint.id());
    }

    private void toggleConnection() {
        if (busy || loading) return;
        if (connection == null) { chooseAddress(); return; }
        if (host.isReProxyForwarded(server)) {
            run(() -> ReProxyManager.stopServer(target()), "Disconnected");
        } else {
            run(() -> ReProxyManager.startServer(target()), "Connected");
        }
    }

    private void chooseAddress() {
        if (busy || loading || summary == null || !addressAllowed()) return;
        Screen screen = screen();
        if (screen == null) return;
        long epoch = generation;
        long formId = ++form;
        hideForm();
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(screen).size(330, 240).entryHeight(22)
                .searchPlaceholder("Search Addresses").emptyMessage("No Available Addresses");
        summary.addresses().stream().filter(this::available).sorted(Comparator.comparing(Address::publicHost, String.CASE_INSENSITIVE_ORDER)).forEach(value ->
                builder.addItem(value.publicHost(), "reverse.png", connection != null && connection.address().id().equals(value.id()) ? "Assigned To This Server" : "Use For This Server", value.publicHost(), () -> {
                    if (current(epoch) && formId == form) attach(value.id(), null);
                }));
        builder.addItem("Create Address", "create.png", "Choose A New Address", "new create address", () -> {
            if (current(epoch) && formId == form) createAddress();
        });
        selector = builder.build();
        screen.addDrawableChild(selector);
        selector.show(Math.max(4, (screen.width - selector.getWidth()) / 2), Math.max(4, (screen.height - selector.getHeight()) / 2));
    }

    private boolean available(Address value) {
        if (!"ACTIVE".equalsIgnoreCase(value.status()) && !"AVAILABLE".equalsIgnoreCase(value.status())) return false;
        if (value.connectionId() != null && !value.connectionId().isBlank() && (connection == null || !value.connectionId().equals(connection.id()))) return false;
        return summary.connections().stream().noneMatch(owner -> owner.address() != null && owner.address().id().equals(value.id()) && !target().matches(owner.binding()));
    }

    private void createAddress() {
        if (!addressAllowed()) return;
        long epoch = generation;
        long formId = ++form;
        hideForm();
        ReProxyManager.serverCatalog().whenComplete((catalog, failure) -> host.application().execute(() -> {
            if (!current(epoch) || formId != form) return;
            if (failure != null) { failure(failure); return; }
            List<Suffix> available = catalog.suffixes().stream().filter(suffix -> "READY".equalsIgnoreCase(suffix.readiness()) && "ACTIVE".equalsIgnoreCase(suffix.state()))
                    .filter(suffix -> suffix.transports().contains("TCP") && suffix.routingModes().contains("JAVA_HOSTNAME")).toList();
            if (available.isEmpty()) { failure(new IllegalStateException("No Domains Available. Try Again Shortly")); return; }
            Suffix[] selected = {available.stream().filter(suffix -> "reproxy.link".equalsIgnoreCase(suffix.suffix())).findFirst().orElse(available.getFirst())};
            TextInputWidget name = new TextInputWidget.Builder().placeholder("Your Server Name").maxLength(32).build();
            PopupWidget.Builder builder = popup("Create Address");
            if (builder == null) return;
            builder.addRow("Name", name);
            AnimatedButton domain = new AnimatedButton.Builder().label(selected[0].suffix()).active(available.size() > 1).build();
            domain.setAction(() -> {
                if (!current(epoch) || formId != form) return;
                Screen screen = screen();
                if (screen == null) return;
                ItemSelectorWidget.Builder choices = new ItemSelectorWidget.Builder(screen).searchPlaceholder("Search Domains");
                for (Suffix suffix : available) choices.addItem(suffix.suffix(), () -> {
                    if (!current(epoch) || formId != form) return;
                    selected[0] = suffix;
                    domain.setMessage(suffix.suffix());
                });
                selector = choices.build();
                screen.addDrawableChild(selector);
                selector.show(domain.getX(), domain.getY() + domain.getHeight());
            });
            builder.addRow("Domain", domain).addTitleAction("Use Address", () -> {
                if (!current(epoch) || formId != form) return;
                String label = name.getText().trim().toLowerCase(Locale.ROOT);
                if (!label.matches("[a-z0-9](?:[a-z0-9-]{1,30}[a-z0-9])")) {
                    failure(new IllegalArgumentException("Use 3 To 32 Letters, Numbers Or Hyphens"));
                    return;
                }
                attach("", new AddressSpec(label, selected[0].id()));
            }, PopupWidget.TitleActionRole.PRIMARY);
            show(builder);
        }));
    }

    private void attach(String addressId, AddressSpec address) {
        if (busy || !addressAllowed()) return;
        boolean changing = connection != null && (address != null || !connection.address().id().equals(addressId));
        if (changing && host.state(server) != ServerScreenHost.ServerState.STOPPED && host.state(server) != ServerScreenHost.ServerState.CRASHED) {
            failure(new IllegalStateException("Stop The Server Before Changing Its Address"));
            return;
        }
        hideForm();
        run(() -> host.loadReProxyTarget(server).thenCompose(value -> {
                    if (!addressAllowed()) return Async.failed(new IllegalStateException("Manage This Address From The Network"));
                    return ReProxyManager.prepareServer(value, addressId, address);
                })
                .thenCompose(value -> host.saveReProxyConnection(server, value))
                .thenCompose(ignored -> changing ? forwarding.reconcile() : Async.completed(null)), "Address Saved");
    }

    private void editPort(Endpoint endpoint) {
        if (connection == null || busy || endpoint != null && (pluginsBusy() || pluginPort(endpoint))) return;
        long epoch = generation;
        long formId = ++form;
        hideForm();
        PopupWidget.Builder builder = popup(endpoint == null ? "Add Port" : "Edit Port");
        if (builder == null) return;
        TextInputWidget name = new TextInputWidget.Builder().placeholder("Port Name").text(endpoint == null ? "" : endpoint.name()).build();
        TextInputWidget port = new TextInputWidget.Builder().placeholder("Local Port").text(endpoint == null ? "" : Integer.toString(endpoint.target().port())).maxLength(5).build();
        ToggleWidget udp = new ToggleWidget.Builder().toggled(endpoint != null && "UDP".equals(endpoint.protocol())).label("UDP").hint("Off Uses TCP. On Uses UDP").build();
        builder.addRow("Name", name).addRow("Local Port", port).addRow("Use UDP", udp).addTitleAction("Save", () -> {
            if (!current(epoch) || formId != form || busy) return;
            int number;
            try { number = Integer.parseInt(port.getText().trim()); } catch (NumberFormatException invalid) { number = 0; }
            if (number < 1 || number > 65535 || name.getText().trim().isBlank()) {
                failure(new IllegalArgumentException("Choose A Name And A Port From 1 To 65535"));
                return;
            }
            int targetPort = number;
            String portName = name.getText().trim();
            String protocol = udp.getValue() ? "UDP" : "TCP";
            String id = endpoint == null ? UUID.randomUUID().toString() : endpoint.id();
            String connectionId = connection.id();
            String operation = UUID.randomUUID().toString();
            hideForm();
            run(() -> ReProxyManager.serverChange(target(), connectionId, (api, fresh) -> {
                List<EndpointSpec> endpoints = new ArrayList<>();
                for (Endpoint value : fresh.endpoints()) if (!id.equals(value.id())) endpoints.add(spec(value));
                if (endpoint != null && fresh.endpoints().stream().noneMatch(value -> id.equals(value.id()))) return Async.failed(new IllegalStateException("This Port Was Removed. Refresh And Try Again"));
                Endpoint previous = fresh.endpoints().stream().filter(value -> id.equals(value.id())).findFirst().orElse(null);
                if (previous != null && (pluginsBusy() || "JAVA_HOSTNAME".equals(previous.routingMode()) || pluginPort(previous))) return Async.failed(new IllegalStateException("Manage This Port Through Its Plugin"));
                endpoints.add(new EndpointSpec(id, portName, protocol, "DEDICATED",
                        new Target(fresh.binding().id(), targetPort), previous == null ? PublicPort.auto() : previous.publicPortPolicy(), previous == null || previous.enabled()));
                return api.updateConnection(fresh.id(), new ConnectionPatch(null, null, endpoints, null), fresh.revision(), operation);
            }).thenApply(ignored -> null), "Port Saved");
        }, PopupWidget.TitleActionRole.PRIMARY);
        show(builder);
    }

    private void removePort(Endpoint endpoint) {
        if (connection == null || busy || pluginsBusy() || pluginPort(endpoint)) return;
        String id = connection.id();
        String operation = UUID.randomUUID().toString();
        run(() -> ReProxyManager.serverChange(target(), id, (api, fresh) -> {
            Endpoint existing = fresh.endpoints().stream().filter(value -> endpoint.id().equals(value.id())).findFirst().orElse(null);
            if (existing == null) return Async.failed(new IllegalStateException("This Port Was Already Removed"));
            if (pluginsBusy() || "JAVA_HOSTNAME".equals(existing.routingMode()) || pluginPort(existing)) return Async.failed(new IllegalStateException("Disconnect This Plugin From Plugins"));
            List<EndpointSpec> endpoints = fresh.endpoints().stream().filter(value -> !endpoint.id().equals(value.id())).map(this::spec).toList();
            return api.updateConnection(fresh.id(), new ConnectionPatch(null, null, endpoints, null), fresh.revision(), operation);
        }).thenApply(ignored -> null), "Port Removed");
    }

    private EndpointSpec spec(Endpoint value) {
        return new EndpointSpec(value.id(), value.name(), value.protocol(), value.routingMode(), value.target(), value.publicPortPolicy(), value.enabled());
    }

    private void run(Supplier<Async<Void>> operation, String success) {
        if (busy) return;
        long epoch = generation;
        try { target(); } catch (Throwable failure) { return; }
        busy = true;
        updateAddress();
        Async<Void> request;
        try { request = operation.get(); } catch (Throwable failure) { request = Async.failed(failure); }
        request.whenComplete((ignored, failure) -> host.application().execute(() -> {
            if (!current(epoch)) return;
            busy = false;
            if (failure != null) failure(failure);
            else host.application().notify(success, "", ReSyncNotificationLevel.SUCCESS);
            loaded = false;
            reload();
        }));
    }

    private final class PortWidget extends MountableButtonWidget {
        private final Endpoint endpoint;
        private final IconButton edit;
        private final IconButton remove;

        private PortWidget(Endpoint endpoint) {
            super(endpoint.name(), endpoint.protocol() + " | Local Port " + endpoint.target().port(), portAddress(endpoint), new ArrayList<>(), null);
            this.endpoint = endpoint;
            edit = action("edit.png", "Edit Port", () -> editPort(endpoint));
            remove = action("delete.png", "Remove Port", () -> removePort(endpoint));
            if (!"JAVA_HOSTNAME".equals(endpoint.routingMode())) {
                addMountedWidget(edit);
                addMountedWidget(remove);
            }
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
            boolean editable = !pluginsBusy() && !pluginPort(endpoint);
            edit.setVisible(editable);
            remove.setVisible(editable);
            super.drawContent(context, mouseX, mouseY);
        }
    }

    private final class PluginButton extends IconButton {
        private final ResourceContainerItem resource;
        private ResourceForwarding.State rendered;
        private boolean pending;
        private String waitingHint = "";

        private PluginButton(ResourceContainerItem resource) {
            super(0, 0, 18, 18, "", CONNECT);
            this.resource = resource;
            setOnClick(() -> {
                if (closed || pending) return;
                long epoch = generation;
                pending = true;
                rendered = null;
                waitingHint = "";
                try {
                    forwarding.toggle(resource).whenComplete((ignored, failure) -> host.application().execute(() -> {
                        if (closed || generation != epoch) return;
                        pending = false;
                        rendered = null;
                        if (!current(epoch)) return;
                        if (failure != null) failure(failure);
                        loaded = false;
                        reload();
                    }));
                    notifyWait(forwarding.state(resource));
                } catch (RuntimeException failure) {
                    pending = false;
                    rendered = null;
                    if (current(epoch)) failure(failure);
                }
            });
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
            ResourceForwarding.State state = forwarding.state(resource);
            if (!state.equals(rendered)) {
                rendered = state;
                setIcon(state.busy() || pending ? LOADING : state.connected() ? DISCONNECT : CONNECT);
                setHint(state.address().isBlank() ? state.hint() : state.address() + " | " + state.hint());
            }
            notifyWait(state);
            super.drawContent(context, mouseX, mouseY);
        }

        private void notifyWait(ResourceForwarding.State state) {
            String hint = state.hint();
            if (closed || !pending || !state.busy() || !(hint.startsWith("Stop ") || hint.startsWith("Start ") || hint.startsWith("Choose ") || hint.startsWith("Enable "))
                    || hint.equals(waitingHint)) return;
            waitingHint = hint;
            host.application().notify("Plugin Connection Queued", hint, ReSyncNotificationLevel.INFO);
        }
    }

    private void refresh() {
        changed.run();
        ScreenManager manager = ScreenManager.getInstance();
        if (manager.getCurrentScreen() instanceof SettingsScreen screen) screen.refreshTab("ReProxy");
        if (manager.getDesktopWindowsOverlay() != null) for (var window : manager.getDesktopWindowsOverlay().getWindows()) {
            if (window.getScreen() instanceof SettingsScreen screen) screen.refreshTab("ReProxy");
        }
    }

    private Screen screen() {
        return owner.get();
    }

    private PopupWidget.Builder popup(String title) {
        Screen screen = screen();
        return screen == null ? null : new PopupWidget.Builder(title).width(330).pos(Math.max(4, (screen.width - 330) / 2), Math.max(4, screen.height / 5)).setResizable(false).setAntiOutOfBound(true);
    }

    private void show(PopupWidget.Builder builder) {
        Screen screen = screen();
        if (screen == null) return;
        popup = builder.build();
        screen.addDrawableChild(popup);
        popup.show();
    }

    private void hideForm() {
        if (selector != null) { selector.hide(); selector = null; }
        if (popup != null) { popup.hide(); popup = null; }
    }

    private void failure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof Async.Cancellation) return;
        host.application().notify("ReProxy Update Failed", ReProxyManager.failureMessage(failure), ReSyncNotificationLevel.ERROR);
    }

    private static String portAddress(Endpoint endpoint) {
        return endpoint.displayAddress() == null || endpoint.displayAddress().isBlank() ? "Waiting For Public Port" : endpoint.displayAddress();
    }

    private static PopupRow row(String label, Widget widget) {
        return new PopupRow.Builder(label, widget).build();
    }

    private static AnimatedButton message(String value) {
        return new AnimatedButton.Builder().label(value).active(false).build();
    }

    private static IconButton action(String image, String hint, Runnable action) {
        return new IconButton.Builder().imagePath(image).hint(hint).onClick(action).size(18, 18).build();
    }
}
