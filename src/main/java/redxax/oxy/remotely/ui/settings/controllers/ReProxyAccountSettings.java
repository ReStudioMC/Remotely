package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.reproxy.ReProxyClient;
import restudio.rebase.reproxy.ReProxyModels.Address;
import restudio.rebase.reproxy.ReProxyModels.AddressCheck;
import restudio.rebase.reproxy.ReProxyModels.AddressSpec;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Catalog;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.reproxy.ReProxyModels.ConnectionPatch;
import restudio.rebase.reproxy.ReProxyModels.ConnectionSpec;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.EndpointSpec;
import restudio.rebase.reproxy.ReProxyModels.Operation;
import restudio.rebase.reproxy.ReProxyModels.PublicPort;
import restudio.rebase.reproxy.ReProxyModels.Suffix;
import restudio.rebase.reproxy.ReProxyModels.Summary;
import restudio.rebase.reproxy.ReProxyModels.Target;
import restudio.rebase.reproxy.ReProxyModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ReProxyAccountSettings {
    private final ReProxySettingsCapability capability;
    private final Runnable refresh;
    private final Setting status = new Setting.Builder("ReProxy").build();
    private final Setting connections = new Setting.Builder("Servers").build();
    private final Setting addresses = new Setting.Builder("Addresses").build();
    private final Map<String, Connection> connectionValues = new LinkedHashMap<>();
    private final Map<String, Address> addressValues = new LinkedHashMap<>();
    private final Map<String, PopupRow> connectionRows = new LinkedHashMap<>();
    private final Map<String, PopupRow> addressRows = new LinkedHashMap<>();
    private final Map<String, PopupRow> endpointRows = new LinkedHashMap<>();
    private final Map<String, Object> rowStamps = new LinkedHashMap<>();
    private final MountableButtonWidget overview;
    private final AnimatedButton actionState;
    private final List<Setting> settings;
    private Map<String, String> targetNames = Map.of();
    private Summary summary;
    private Catalog catalog;
    private String account = "";
    private String stamp = "";
    private String error = "";
    private boolean loading;
    private boolean busy;
    private boolean creatingServer;
    private boolean closed;
    private long generation;
    private long request;
    private long form;
    private TaskScheduler.ScheduledTask availabilityTask;
    private TaskScheduler.ScheduledTask availabilityExpiry;
    private ReProxyClient.Poll poll;
    private Supplier<Async<Operation>> retry;
    private Supplier<Async<Binding>> targetRetry;
    private Consumer<Operation> settled;
    private Operation receipt;

    ReProxyAccountSettings(ReProxySettingsCapability capability, Runnable refresh) {
        this.capability = capability;
        this.refresh = refresh;
        overview = new MountableButtonWidget.Builder("Loading")
                .description("Share Your Server With A Public Address")
                .addButton(button("create.png", "Add Server", this::createConnection))
                .addButton(button("create.png", "Add Address", () -> addressEditor(null)))
                .addButton(button("reload.png", "Refresh", this::load)).build();
        actionState = message("Ready");
        settings = List.of(status, connections, addresses);
        updateStatus();
    }

    List<Setting> settings() {
        adoptAccount();
        if (closed) return settings;
        if (!capability.authenticated()) {
            status.setRows(List.of(row("", message("ReStudio Login Required"))));
            return List.of(status);
        }
        if (!capability.availability().available()) {
            status.setRows(List.of(row("Status", message(capability.availability().reason()))));
            return settings;
        }
        if (summary == null && !loading && error.isBlank()) load();
        return settings;
    }

    void cleanup() {
        closed = true;
        generation++;
        request++;
        form++;
        loading = false;
        busy = false;
        cancelObservation();
    }

    void activate() {
        closed = false;
        generation++;
        request++;
        loading = false;
        busy = false;
        adoptAccount();
        if (capability.authenticated() && capability.availability().available()) load();
    }

    private String currentAccount() {
        return capability.authenticated() ? safe(capability.accountId()) : "";
    }

    private void adoptAccount() {
        String identity = currentAccount();
        if (!identity.equals(account)) {
            invalidate();
            account = identity;
        }
    }

    private void invalidate() {
        generation++;
        request++;
        form++;
        cancelObservation();
        summary = null;
        catalog = null;
        targetNames = Map.of();
        stamp = "";
        error = "";
        loading = false;
        busy = false;
        creatingServer = false;
        retry = null;
        targetRetry = null;
        receipt = null;
        settled = null;
        connectionValues.clear();
        addressValues.clear();
        connectionRows.clear();
        addressRows.clear();
        endpointRows.clear();
        rowStamps.clear();
        connections.setRows(List.of());
        addresses.setRows(List.of());
        updateStatus();
    }

    private void cancelObservation() {
        if (poll != null) poll.cancel();
        poll = null;
        if (availabilityTask != null) availabilityTask.cancel();
        availabilityTask = null;
        if (availabilityExpiry != null) availabilityExpiry.cancel();
        availabilityExpiry = null;
    }

    private void load() {
        load(null);
    }

    private void load(Runnable ready) {
        adoptAccount();
        if (closed || busy || loading || !capability.authenticated() || !capability.availability().available()) return;
        long id = ++request;
        long epoch = generation;
        String identity = account;
        loading = true;
        error = "";
        updateStatus();
        Async<Summary> accountRead;
        Async<Catalog> catalogRead;
        Async<List<ReProxySettingsCapability.ServerTarget>> targetsRead;
        try {
            accountRead = timeout(capability.client().summary());
            catalogRead = timeout(capability.client().catalog());
            targetsRead = timeout(capability.serverTargets());
        } catch (RuntimeException failure) {
            loading = false;
            error = failure(failure);
            notifyFailure(error);
            updateStatus();
            refresh.run();
            return;
        }
        Async.allOf(accountRead.exceptionally(problem -> null), catalogRead.exceptionally(problem -> null), targetsRead.exceptionally(problem -> null))
                .whenComplete((ignored, failure) -> ui(() -> {
                    if (closed || epoch != generation || id != request) return;
                    if (!identity.equals(currentAccount())) {
                        adoptAccount();
                        if (capability.authenticated() && capability.availability().available()) load();
                        refresh.run();
                        return;
                    }
                    loading = false;
                    try {
                        if (targetsRead.failure() == null && targetsRead.value() != null) {
                            Map<String, String> names = new LinkedHashMap<>();
                            for (ReProxySettingsCapability.ServerTarget target : targetsRead.value()) names.put(bindingKey(target.binding()), target.name());
                            Map<String, String> next = Map.copyOf(names);
                            if (!next.equals(targetNames)) stamp = "";
                            targetNames = next;
                        }
                        if (accountRead.failure() == null) admit(accountRead.value());
                        if (catalogRead.failure() == null) catalog = catalogRead.value();
                        if (accountRead.failure() != null) error = failure(accountRead.failure());
                        if (catalogRead.failure() != null) error = (error.isBlank() ? "" : error + " | ") + failure(catalogRead.failure());
                    } catch (RuntimeException problem) {
                        error = failure(problem);
                    }
                    if (!error.isBlank()) notifyFailure(error);
                    updateStatus();
                    refresh.run();
                    if (error.isBlank() && ready != null) ready.run();
                }));
    }

    private void admit(Summary value) {
        if (value == null) return;
        summary = value;
        String identity = account + ":" + value.revision();
        if (identity.equals(stamp)) return;
        stamp = identity;
        connectionValues.clear();
        addressValues.clear();
        List<PopupRow> nextConnections = new ArrayList<>();
        Map<String, PopupRow> nextRows = new LinkedHashMap<>();
        Map<String, Object> nextStamps = new LinkedHashMap<>();
        Map<String, PopupRow> nextEndpointRows = new LinkedHashMap<>();
        for (Connection connection : value.connections()) {
            connectionValues.put(connection.id(), connection);
            String key = "connection:" + connection.id();
            ConnectionRowStamp revision = new ConnectionRowStamp(connection.revision(), connection.desiredState(), connection.state(), connection.observedAt(),
                    bindingText(connection.binding()), connection.pendingOperationId(), connection.name(), connection.address() == null ? "" : connection.address().publicHost());
            PopupRow existing = connectionRows.get(connection.id());
            if (existing == null || !revision.equals(rowStamps.get(key))) existing = connectionRow(connection);
            nextRows.put(connection.id(), existing);
            nextStamps.put(key, revision);
            nextConnections.add(existing);
            for (Endpoint endpoint : connection.endpoints()) {
                String endpointKey = "endpoint:" + connection.id() + ":" + endpoint.id();
                EndpointRowStamp endpointStamp = new EndpointRowStamp(endpoint.name(), endpoint.protocol(), endpoint.target().port(), endpoint.enabled(),
                        endpoint.publicHost(), endpoint.publicPort(), endpoint.readiness(), endpoint.advertisement() == null ? "" : endpoint.advertisement().description());
                PopupRow endpointRow = endpointRows.get(endpointKey);
                if (endpointRow == null || !endpointStamp.equals(rowStamps.get(endpointKey))) endpointRow = row("", endpointWidget(connection.id(), endpoint));
                nextEndpointRows.put(endpointKey, endpointRow);
                nextStamps.put(endpointKey, endpointStamp);
                nextConnections.add(endpointRow);
            }
            if (!safe(connection.lastError()).isBlank()) nextConnections.add(row("Error", message(readable(connection.lastError()))));
        }
        if (nextConnections.isEmpty()) nextConnections.add(row("", message("Add A Server To Share It With A Public Address")));
        connectionRows.clear();
        connectionRows.putAll(nextRows);
        endpointRows.clear();
        endpointRows.putAll(nextEndpointRows);
        List<PopupRow> nextAddresses = new ArrayList<>();
        Map<String, PopupRow> nextAddressRows = new LinkedHashMap<>();
        for (Address address : value.addresses()) {
            addressValues.put(address.id(), address);
            String key = "address:" + address.id();
            AddressRowStamp revision = new AddressRowStamp(address, addressUse(address));
            PopupRow existing = addressRows.get(address.id());
            if (existing == null || !revision.equals(rowStamps.get(key))) existing = addressRow(address);
            nextAddressRows.put(address.id(), existing);
            nextStamps.put(key, revision);
            nextAddresses.add(existing);
        }
        if (nextAddresses.isEmpty()) nextAddresses.add(row("", message("No Addresses Yet")));
        addressRows.clear();
        addressRows.putAll(nextAddressRows);
        rowStamps.clear();
        rowStamps.putAll(nextStamps);
        connections.setRows(nextConnections);
        addresses.setRows(nextAddresses);
    }

    private PopupRow connectionRow(Connection connection) {
        String id = connection.id();
        MountableButtonWidget.Builder builder = new MountableButtonWidget.Builder(safe(connection.name()))
                .description(connectionState(connection) + " | " + safe(connection.address() == null ? "" : connection.address().publicHost()))
                .hiddenText(bindingText(connection.binding()))
                .onClick(() -> connectionEditor(connectionValues.get(id)))
                .addButton(button("edit.png", "Edit Server", () -> connectionEditor(connectionValues.get(id))))
                .addButton(button("create.png", "Add Port", () -> endpointEditor(connectionValues.get(id), null, null)))
                .addButton(button("server.png", "Use For Server", () -> selectConnection(id)))
                .addButton(button("ENABLED".equals(connection.desiredState()) ? "closeReverse.png" : "start.png", "ENABLED".equals(connection.desiredState()) ? "Stop" : "Start", () -> toggleConnection(id)))
                .addButton(button("delete.png", "Remove Server", () -> deleteConnection(id)));
        return row("", builder.build());
    }

    private MountableButtonWidget endpointWidget(String connectionId, Endpoint endpoint) {
        String endpointId = endpoint.id();
        String description = canonical(endpoint) + " | " + endpoint.protocol();
        return new MountableButtonWidget.Builder(safe(endpoint.name()))
                .description(description)
                .hiddenText("Server Port " + endpoint.target().port() + " | " + (endpoint.enabled() ? readableState(endpoint.readiness()) : "Disabled"))
                .onClick(() -> endpointDetails(connectionId, endpointId))
                .addButton(button("clipboard.png", "Copy Address", () -> copyEndpoint(currentEndpoint(connectionId, endpointId))))
                .addButton(button("edit.png", "Edit Port", () -> endpointEditor(connectionValues.get(connectionId), currentEndpoint(connectionId, endpointId), null)))
                .addButton(button("delete.png", "Remove Port", () -> removeEndpoint(connectionId, endpointId))).build();
    }

    private PopupRow addressRow(Address address) {
        String id = address.id();
        if (!"ACTIVE".equalsIgnoreCase(address.status())) {
            return row("", new MountableButtonWidget.Builder(safe(address.publicHost()))
                    .iconPath("delete.png")
                    .description("Deleted Address")
                    .hiddenText("Restore If The Name Is Still Available")
                    .addButton(button("reload.png", "Restore Address", () -> restoreAddress(id)))
                    .addButton(button("close.png", "Dismiss", () -> dismissAddress(id))).build());
        }
        return row("", new MountableButtonWidget.Builder(safe(address.publicHost()))
                .description(addressUse(address))
                .addButton(button("clipboard.png", "Copy Host", () -> copy(safe(addressValues.get(id).publicHost()))))
                .addButton(button("edit.png", "Replace Address", () -> addressEditor(addressValues.get(id))))
                .addButton(button("delete.png", "Delete Address", () -> deleteAddress(id))).build());
    }

    private void updateStatus() {
        overview.setName(loading ? "Loading" : summary == null ? "ReProxy" : "Share Your Server");
        overview.setDescription(summary == null ? "Connect With A Public Address" : plural(summary.connections().size(), "Server") + " | " + plural(summary.addresses().stream().filter(address -> !"DELETED".equals(address.status())).count(), "Address"));
        overview.setHiddenText("");
        overview.mountedWidgets.get(0).setActive(summary != null && !busy && !loading);
        overview.mountedWidgets.get(1).setActive(summary != null && !busy && !loading);
        overview.mountedWidgets.get(2).setActive(!busy && !loading);
        List<PopupRow> rows = new ArrayList<>();
        rows.add(row("", overview));
        if (busy) rows.add(row("", actionState));
        if (!error.isBlank()) {
            rows.add(row("", message(error)));
            rows.add(row("", new AnimatedButton.Builder().label("Try Again")
                    .active(!busy).onClick(() -> {
                        if (retry != null || targetRetry != null || receipt != null) retryAction();
                        else load();
                    }).build()));
        }
        status.setRows(rows);
    }

    private void registerTarget() {
        if (closed || busy) return;
        long epoch = generation;
        String identity = account;
        timeout(capability.serverTargets()).whenComplete((choices, failure) -> ui(() -> {
            if (!valid(epoch, identity)) return;
            if (failure != null) {
                notifyFailure(failure(failure));
                return;
            }
            PopupWidget.Builder popup = popup("Connect Server");
            if (popup == null) return;
            if (choices == null || choices.isEmpty()) {
                popup.addRow("Server", message("Add A Server Or Pair A Device To Continue"));
            } else {
                List<ReProxySettingsCapability.ServerTarget> targets = List.copyOf(choices);
                ScrollSelectorWidget selected = selector(targets.stream().map(ReProxySettingsCapability.ServerTarget::name).toList(), 0);
                popup.addRow("Choose Server", selected);
                popup.addRow("", message("Only The Ports You Choose Will Be Shared"));
                popup.addTitleAction("Connect Server", () -> {
                    if (busy) return;
                    String key = capability.operationKey();
                    Binding binding = targets.get(selected.getSelectedIndex()).binding();
                    retry = null;
                    receipt = null;
                    settled = null;
                    targetRetry = () -> capability.client().registerBinding(binding, key);
                    submitTarget();
                    popup.getWidget().setVisible(false);
                }, PopupWidget.TitleActionRole.PRIMARY);
            }
            show(popup);
        }));
    }

    private void submitTarget() {
        if (closed || busy || targetRetry == null) return;
        long epoch = generation;
        String identity = account;
        busy = true;
        error = "";
        actionState.setMessage("Connecting Server");
        updateStatus();
        timeout(targetRetry.get()).whenComplete((value, problem) -> ui(() -> {
            if (!valid(epoch, identity)) return;
            busy = false;
            error = problem == null ? "" : failure(problem);
            if (problem != null) notifyFailure(error);
            actionState.setMessage(problem == null ? "Server Connected" : "Registration Failed");
            if (problem == null) targetRetry = null;
            updateStatus();
            if (problem == null) {
                boolean next = creatingServer;
                creatingServer = false;
                load(next ? this::createConnection : null);
            }
        }));
    }

    private void selectConnection(String id) {
        Connection connection = connectionValues.get(id);
        if (connection == null || busy) return;
        long epoch = generation;
        String identity = account;
        busy = true;
        error = "";
        actionState.setMessage("Selecting Server Connection");
        updateStatus();
        timeout(capability.selectConnection(connection)).whenComplete((ignored, failure) -> ui(() -> {
            if (!valid(epoch, identity)) return;
            busy = false;
            error = failure == null ? "" : failure(failure);
            if (failure != null) notifyFailure(error);
            actionState.setMessage(failure == null ? "Server Connection Selected" : "Selection Failed");
            updateStatus();
            refresh.run();
        }));
    }

    private void createConnection() {
        if (summary == null || busy || catalog == null) return;
        if (catalog.suffixes().isEmpty()) {
            notifyFailure("No Domains Are Available");
            return;
        }
        if (summary.bindings().isEmpty()) {
            creatingServer = true;
            registerTarget();
            return;
        }
        PopupWidget.Builder popup = popup("Add Server");
        if (popup == null) return;
        TextInputWidget name = input("Server Name", "");
        List<Binding> bindings = List.copyOf(summary.bindings());
        ScrollSelectorWidget target = selector(bindings.stream().map(this::bindingText).toList(), 0);
        String[] selectedAddress = {""};
        AnimatedButton addressChoice = new AnimatedButton.Builder().label("New Address").build();
        popup.addRow("Name", name).addRow("Server", target).addRow("Saved Address", addressChoice);
        AddressForm address = new AddressForm(popup, null, List.of("TCP"), List.of("JAVA_HOSTNAME"));
        long epoch = generation;
        String identity = account;
        long formId = form;
        addressChoice.setAction(() -> {
            if (!valid(epoch, identity) || formId != form || busy) return;
            if (!catalogCurrent()) {
                address.refreshCatalog();
                return;
            }
            Screen screen = ScreenManager.getInstance().getCurrentScreen();
            if (screen == null) return;
            ItemSelectorWidget.Builder choices = new ItemSelectorWidget.Builder(screen).searchPlaceholder("Search Addresses");
            choices.addItem("New Address", () -> {
                if (!valid(epoch, identity) || formId != form || busy) return;
                selectedAddress[0] = "";
                addressChoice.setMessage("New Address");
                address.visible(popup, true);
                address.check();
            });
            for (Address saved : summary.addresses()) {
                if (!addressAvailable(saved, null)) continue;
                choices.addItem(saved.publicHost(), () -> {
                    if (!valid(epoch, identity) || formId != form || busy) return;
                    Address current = addressValues.get(saved.id());
                    if (!catalogCurrent() || !addressAvailable(current, null)) {
                        notifyFailure("This Address Is Unavailable. Refresh And Choose Another");
                        return;
                    }
                    selectedAddress[0] = current.id();
                    addressChoice.setMessage(current.publicHost());
                    address.visible(popup, false);
                });
            }
            ItemSelectorWidget selector = choices.build();
            selector.setSelectedItem(addressChoice.getMessage());
            selector.show(addressChoice.getX(), addressChoice.getY() + addressChoice.getHeight());
        });
        List<EndpointSpec> endpoints = new ArrayList<>();
        AnimatedButton endpointCount = message("Add A Service Before Saving");
        popup.addRow("Ports", endpointCount);
        popup.addRow("", new AnimatedButton.Builder().label("Add Port").onClick(() -> {
            Binding binding = bindings.get(target.getSelectedIndex());
            endpointDraft(binding, spec -> {
                endpoints.add(spec);
                endpointCount.setMessage(plural(endpoints.size(), "Port") + " | " + String.join(", ", endpoints.stream().map(EndpointSpec::name).toList()));
                address.requirements(endpoints.stream().map(EndpointSpec::protocol).distinct().toList(), endpoints.stream().map(EndpointSpec::routingMode).distinct().toList());
                if (selectedAddress[0].isBlank()) address.check();
            });
        }).build());
        popup.addTitleAction("Save", () -> {
            if (!valid(epoch, identity) || formId != form || busy) return;
            if (endpoints.isEmpty()) {
                notifyFailure("Add At Least One Port");
                return;
            }
            Binding binding = bindings.get(target.getSelectedIndex());
            if (endpoints.stream().anyMatch(endpoint -> !binding.id().equals(endpoint.target().bindingId()))) {
                notifyFailure("Select The Same Server Used By The Ports");
                return;
            }
            AddressSpec inline = selectedAddress[0].isBlank() ? address.value() : null;
            if (selectedAddress[0].isBlank() && inline == null) return;
            String existing = selectedAddress[0].isBlank() ? null : selectedAddress[0];
            if (existing != null && (!catalogCurrent() || !addressAvailable(addressValues.get(existing), null))) {
                notifyFailure("This Address Is Unavailable. Refresh And Choose Another");
                return;
            }
            ConnectionSpec spec = new ConnectionSpec(name.getText().trim(), existing, inline, binding.id(), endpoints, "STOPPED");
            String key = capability.operationKey();
            mutate(() -> capability.client().createConnection(spec, key), this::changed);
            popup.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.PRIMARY);
        popup.addTitleAction("Connect Another Server", () -> {
            creatingServer = true;
            popup.getWidget().setVisible(false);
            registerTarget();
        }, PopupWidget.TitleActionRole.SECONDARY);
        show(popup);
    }

    private void connectionEditor(Connection connection) {
        if (connection == null || busy) return;
        if (!safe(connection.pendingOperationId()).isBlank()) {
            notifyFailure("A Change Is Still Being Saved. Try Again Shortly");
            return;
        }
        PopupWidget.Builder popup = popup("Edit Server");
        if (popup == null) return;
        long epoch = generation;
        String identity = account;
        long formId = ++form;
        TextInputWidget name = input("Server Name", connection.name());
        List<Binding> bindings = List.copyOf(summary.bindings());
        if (bindings.isEmpty()) return;
        int selected = 0;
        for (int index = 0; index < bindings.size(); index++) if (connection.binding().id().equals(bindings.get(index).id())) selected = index;
        ScrollSelectorWidget binding = selector(bindings.stream().map(this::bindingText).toList(), selected);
        String[] selectedAddress = {connection.address().id()};
        AnimatedButton address = new AnimatedButton.Builder().label(connection.address().publicHost()).build();
        address.setAction(() -> {
            if (!valid(epoch, identity) || formId != form || busy) return;
            if (!catalogCurrent()) {
                notifyFailure("Refresh Available Addresses Before Choosing One");
                load();
                return;
            }
            Screen screen = ScreenManager.getInstance().getCurrentScreen();
            if (screen == null) return;
            ItemSelectorWidget.Builder choices = new ItemSelectorWidget.Builder(screen).searchPlaceholder("Search Addresses");
            for (Address saved : summary.addresses()) {
                if (!addressAvailable(saved, connection.id())) continue;
                choices.addItem(saved.publicHost(), () -> {
                    if (!valid(epoch, identity) || formId != form || busy) return;
                    Address current = addressValues.get(saved.id());
                    if (!catalogCurrent() || !addressAvailable(current, connection.id())) {
                        notifyFailure("This Address Is Unavailable. Refresh And Choose Another");
                        return;
                    }
                    selectedAddress[0] = current.id();
                    address.setMessage(current.publicHost());
                });
            }
            ItemSelectorWidget selector = choices.build();
            selector.setSelectedItem(address.getMessage());
            selector.show(address.getX(), address.getY() + address.getHeight());
        });
        popup.addRow("Name", name).addRow("Server", binding).addRow("Address", address);
        popup.addRow("", message("Changing The Server Updates All Its Ports"));
        popup.addTitleAction("Save", () -> {
            if (!valid(epoch, identity) || formId != form || busy) return;
            Connection current = connectionValues.get(connection.id());
            if (current == null || !current.revision().equals(connection.revision()) || !safe(current.pendingOperationId()).isBlank()) {
                notifyFailure("These Settings Changed. Refresh And Try Again");
                return;
            }
            String addressId = connection.address().id().equals(selectedAddress[0]) ? null : selectedAddress[0];
            if (addressId != null && (!catalogCurrent() || !addressAvailable(addressValues.get(addressId), connection.id()))) {
                notifyFailure("This Address Is Unavailable. Refresh And Choose Another");
                return;
            }
            String selectedBinding = bindings.get(binding.getSelectedIndex()).id();
            List<EndpointSpec> endpoints = connection.endpoints().stream().map(endpoint -> spec(endpoint, selectedBinding)).toList();
            ConnectionPatch patch = new ConnectionPatch(name.getText().trim(), selectedBinding, endpoints, null, addressId);
            String key = capability.operationKey();
            mutate(() -> capability.client().updateConnection(connection.id(), patch, connection.revision(), key), this::changed);
            popup.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.PRIMARY);
        show(popup);
    }

    private boolean addressAvailable(Address address, String connectionId) {
        if (address == null || !"ACTIVE".equalsIgnoreCase(address.status())) return false;
        if (!safe(address.connectionId()).isBlank() && !address.connectionId().equals(connectionId)) return false;
        return summary.connections().stream().noneMatch(value -> !value.id().equals(connectionId) && value.address() != null && value.address().id().equals(address.id()));
    }

    private boolean catalogCurrent() {
        try {
            return catalog != null && Instant.parse(catalog.expiresAt()).isAfter(Instant.now());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void endpointDraft(Binding binding, Consumer<EndpointSpec> save) {
        endpointEditor(null, null, new Draft(binding, save));
    }

    private void endpointEditor(Connection connection, Endpoint endpoint, Draft draft) {
        if (busy || (connection == null && draft == null)) return;
        Binding binding = draft == null ? connection.binding() : draft.binding();
        PopupWidget.Builder popup = popup(endpoint == null ? "Add Port" : "Edit Port");
        if (popup == null) return;
        TextInputWidget name = input("Service Name", endpoint == null ? "" : endpoint.name());
        TextInputWidget port = input("Server Port", endpoint == null ? "25565" : Integer.toString(endpoint.target().port()));
        int service = endpoint == null ? connection == null || connection.endpoints().stream().noneMatch(value -> "JAVA_HOSTNAME".equals(value.routingMode())) ? 0 : 1
                : "JAVA_HOSTNAME".equals(endpoint.routingMode()) ? 0 : "UDP".equals(endpoint.protocol()) ? 2 : 1;
        ScrollSelectorWidget type = selector(List.of("Minecraft Java", "TCP Port", "UDP Port"), service);
        PublicPort policy = endpoint == null || endpoint.publicPortPolicy() == null ? PublicPort.auto() : endpoint.publicPortPolicy();
        ScrollSelectorWidget allocation = selector(List.of("Automatic", "Preferred Port"), "PREFERRED".equals(policy.mode()) ? 1 : 0);
        TextInputWidget preferred = input("Preferred Public Port", policy.port() == null ? "" : Integer.toString(policy.port()));
        ToggleWidget fallback = new ToggleWidget.Builder().toggled(policy.allowFallback()).build();
        ToggleWidget enabled = new ToggleWidget.Builder().toggled(endpoint == null || endpoint.enabled()).build();
        popup.addRow("Name", name).addRow("Server Port", port).addRow("Service", type).addRow("Enabled", enabled);
        if (endpoint != null) popup.addRow("Address", message(canonical(endpoint)));
        boolean[] expanded = {false};
        Runnable options = () -> {
            boolean dedicated = expanded[0] && type.getSelectedIndex() != 0;
            popup.getWidget().setRowVisibility("public-port", dedicated);
            popup.getWidget().setRowVisibility("preferred-port", dedicated && allocation.getSelectedIndex() == 1);
            popup.getWidget().setRowVisibility("fallback", dedicated && allocation.getSelectedIndex() == 1);
        };
        AnimatedButton.Builder more = new AnimatedButton.Builder().label("More Options");
        more.onClick(() -> {
            expanded[0] = !expanded[0];
            more.label(expanded[0] ? "Fewer Options" : "More Options");
            options.run();
        });
        popup.addRow("", more.build()).addRow("public-port", "Public Port", allocation).addRow("preferred-port", "Preferred Port", preferred)
                .addRow("fallback", "Use Another Port If Taken", fallback);
        type.onChange = options;
        allocation.onChange = options;
        options.run();
        popup.addTitleAction("Save", () -> {
            try {
                String transport = type.getSelectedIndex() == 2 ? "UDP" : "TCP";
                String route = type.getSelectedIndex() == 0 ? "JAVA_HOSTNAME" : "DEDICATED";
                PublicPort publicPort = type.getSelectedIndex() == 0 || allocation.getSelectedIndex() == 0 ? PublicPort.auto() : PublicPort.preferred(port(preferred.getText()), fallback.getValue());
                String serviceName = name.getText().trim().isBlank() ? type.getSelectedOption() : name.getText().trim();
                EndpointSpec value = new EndpointSpec(endpoint == null ? "" : endpoint.id(), serviceName, transport, route,
                        new Target(binding.id(), port(port.getText())), publicPort, enabled.getValue());
                if (draft != null) draft.save().accept(value);
                else {
                    String key = capability.operationKey();
                    mutate(() -> endpoint == null ? capability.client().addEndpoint(connection.id(), value, connection.revision(), key)
                            : capability.client().updateEndpoint(connection.id(), endpoint.id(), value, connection.revision(), key), this::changed);
                }
                popup.getWidget().setVisible(false);
            } catch (IllegalArgumentException failure) {
                notifyFailure(failure.getMessage());
            }
        }, PopupWidget.TitleActionRole.PRIMARY);
        show(popup);
    }

    private EndpointSpec spec(Endpoint endpoint, String bindingId) {
        return new EndpointSpec(endpoint.id(), endpoint.name(), endpoint.protocol(), endpoint.routingMode(), new Target(bindingId, endpoint.target().port()),
                endpoint.publicPortPolicy() == null ? PublicPort.auto() : endpoint.publicPortPolicy(), endpoint.enabled());
    }

    private void endpointDetails(String connectionId, String endpointId) {
        Connection connection = connectionValues.get(connectionId);
        Endpoint endpoint = currentEndpoint(connectionId, endpointId);
        if (connection == null || endpoint == null) return;
        PopupWidget.Builder popup = popup("Port Settings");
        if (popup == null) return;
        popup.addRow("Address", message(canonical(endpoint)))
                .addRow("Status", message(readableState(endpoint.readiness())))
                .addRow("Server Port", message(Integer.toString(endpoint.target().port())))
                .addRow("Traffic", message(formatBytes(endpoint.bytesIn()) + " Received | " + formatBytes(endpoint.bytesOut()) + " Sent"));
        ReProxyModels.Advertisement advertisement = endpoint.advertisement();
        if (advertisement != null) {
            popup.addRow("Plugin Settings", message("Use This Address In Your Plugin Configuration"));
            advertisement.settings().forEach((key, value) -> popup.addRow(key, message(value)));
            if (!safe(advertisement.reload()).isBlank()) popup.addRow("Reload", message("MANUAL".equalsIgnoreCase(advertisement.reload()) ? "Restart The Plugin After Saving Its Settings" : readable(advertisement.reload())));
            else if (advertisement.reloadRequired()) popup.addRow("Reload", message("Reload The Service After Updating Its Public Address"));
        }
        popup.addTitleAction("Copy Address", () -> copyEndpoint(endpoint), PopupWidget.TitleActionRole.SECONDARY);
        ReProxySettingsCapability.Availability supported = capability.pluginAvailability(connection, endpoint);
        if (supported.available()) popup.addTitleAction("Apply Plugin Settings", () -> {
            if (busy) return;
            long epoch = generation;
            String identity = account;
            busy = true;
            actionState.setMessage("Applying Plugin Settings");
            updateStatus();
            timeout(capability.applyPluginSettings(connection, endpoint, capability.operationKey())).whenComplete((ignored, failure) -> ui(() -> {
                if (!valid(epoch, identity)) return;
                busy = false;
                error = failure == null ? "" : failure(failure);
                if (failure != null) notifyFailure(error);
                actionState.setMessage(failure == null ? "Plugin Settings Applied" : "Plugin Settings Failed");
                updateStatus();
                if (failure == null) load();
            }));
        }, PopupWidget.TitleActionRole.PRIMARY);
        else popup.addRow("Manual Setup", message(supported.reason()));
        show(popup);
    }

    private void addressEditor(Address address) {
        if (address != null && !"ACTIVE".equalsIgnoreCase(address.status())) return;
        if (busy || catalog == null || catalog.suffixes().isEmpty()) {
            if (catalog != null && catalog.suffixes().isEmpty()) notifyFailure("No Domains Are Available");
            return;
        }
        PopupWidget.Builder popup = popup(address == null ? "Add Address" : "Replace Address");
        if (popup == null) return;
        List<Connection> affected = address == null ? List.of() : summary.connections().stream()
                .filter(connection -> connection.address() != null && address.id().equals(connection.address().id())).toList();
        List<String> transports = affected.stream().flatMap(connection -> connection.endpoints().stream()).map(Endpoint::protocol).distinct().toList();
        List<String> routing = affected.stream().flatMap(connection -> connection.endpoints().stream()).map(Endpoint::routingMode).distinct().toList();
        AddressForm form = new AddressForm(popup, address, transports.isEmpty() ? List.of("TCP") : transports, routing);
        if (address != null) {
            popup.addRow("Current Address", message(address.publicHost()));
            popup.addRow("", message("The Old Address Will Stop Working"));
            if (!affected.isEmpty()) popup.addRow("", message("Traffic May Reconnect. Update The Address And Ports In Your Plugins"));
        }
        popup.addTitleAction(address == null ? "Add" : "Replace", () -> {
            AddressSpec value = form.value();
            if (value == null) return;
            String key = capability.operationKey();
            mutate(() -> address == null ? capability.client().claimAddress(value.label(), value.suffixId(), key)
                    : capability.client().replaceAddress(address.id(), value.label(), value.suffixId(), null, address.revision(), key), this::changed);
            popup.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.PRIMARY);
        show(popup);
    }

    private void toggleConnection(String id) {
        Connection connection = connectionValues.get(id);
        if (connection == null || busy) return;
        boolean stop = "ENABLED".equals(connection.desiredState());
        if (!stop && !capability.connectionAvailability(connection).available()) {
            notifyFailure(capability.connectionAvailability(connection).reason());
            return;
        }
        String key = capability.operationKey();
        mutate(() -> stop ? capability.client().stop(id, connection.revision(), key) : capability.client().start(id, connection.revision(), key), this::changed);
    }

    private void deleteConnection(String id) {
        Connection connection = connectionValues.get(id);
        if (connection == null || busy) return;
        PopupWidget.Builder popup = popup("Remove Server");
        if (popup == null) return;
        popup.addRow("Connection", message(connection.name()));
        popup.addRow("", message("Stops Sharing This Server And Removes Its Port Settings"));
        for (Endpoint endpoint : connection.endpoints()) popup.addRow(endpoint.name(), message(canonical(endpoint)));
        ToggleWidget release = new ToggleWidget.Builder().toggled(true).build();
        popup.addRow("Delete Address Too", release);
        if (connection.address() != null) popup.addRow("Address", message(connection.address().publicHost()));
        popup.addTitleAction("Delete", () -> {
            String key = capability.operationKey();
            boolean releaseAddress = release.getValue();
            mutate(() -> capability.client().deleteConnection(id, releaseAddress, connection.revision(), key), operation -> capability.connectionDeleted(id));
            popup.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        show(popup);
    }

    private void deleteAddress(String id) {
        Address address = addressValues.get(id);
        if (address == null || busy) return;
        PopupWidget.Builder popup = popup("Delete Address");
        if (popup == null) return;
        popup.addRow("Address", message(address.publicHost()));
        popup.addRow("", message("This Address Will Stop Working For All Servers Using It"));
        List<Connection> affected = summary.connections().stream().filter(connection -> connection.address() != null && id.equals(connection.address().id())).toList();
        for (Connection connection : affected) for (Endpoint endpoint : connection.endpoints()) popup.addRow(connection.name(), message(canonical(endpoint)));
        
        popup.addTitleAction("Delete", () -> {
            String key = capability.operationKey();
            mutate(() -> capability.client().deleteAddress(id, address.revision(), key), operation -> affected.forEach(connection -> capability.connectionDeleted(connection.id())));
            popup.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        show(popup);
    }

    private void restoreAddress(String id) {
        Address address = addressValues.get(id);
        if (address == null || busy || "ACTIVE".equalsIgnoreCase(address.status())) return;
        String key = capability.operationKey();
        mutate(() -> capability.client().restoreAddress(id, address.revision(), key), this::changed);
    }

    private void dismissAddress(String id) {
        Address address = addressValues.get(id);
        if (address == null || busy || "ACTIVE".equalsIgnoreCase(address.status())) return;
        String key = capability.operationKey();
        mutate(() -> capability.client().dismissAddress(id, address.revision(), key), this::changed);
    }

    private void removeEndpoint(String connectionId, String endpointId) {
        Connection connection = connectionValues.get(connectionId);
        Endpoint endpoint = currentEndpoint(connectionId, endpointId);
        if (connection == null || endpoint == null || busy) return;
        PopupWidget.Builder popup = popup("Remove Port");
        if (popup == null) return;
        popup.addRow("Port", message(canonical(endpoint)));
        popup.addRow("", message("Stops Sharing This Port. Your Other Ports Stay The Same"));
        popup.addTitleAction("Remove", () -> {
            String key = capability.operationKey();
            mutate(() -> capability.client().deleteEndpoint(connectionId, endpointId, connection.revision(), key), this::changed);
            popup.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        show(popup);
    }

    private void mutate(Supplier<Async<Operation>> command, Consumer<Operation> onSettled) {
        if (closed || busy) return;
        targetRetry = null;
        retry = command;
        settled = onSettled;
        receipt = null;
        submit();
    }

    private void submit() {
        if (closed || busy || retry == null) return;
        long epoch = generation;
        String identity = account;
        busy = true;
        error = "";
        actionState.setMessage("Saving Changes");
        updateStatus();
        Async<Operation> action;
        try {
            action = retry.get();
        } catch (RuntimeException failure) {
            action = Async.failed(failure);
        }
        timeout(action).whenComplete((operation, failure) -> ui(() -> {
            if (!valid(epoch, identity)) return;
            if (failure != null || operation == null) finish(null, failure, epoch, identity);
            else {
                receipt = operation;
                observe(operation, epoch, identity);
            }
        }));
    }

    private void observe(Operation operation, long epoch, String identity) {
        actionState.setMessage("Saving Changes");
        updateStatus();
        if (operation.connection() != null) {
            capability.connectionChanged(operation.connection());
            if ("ENABLED".equals(operation.connection().desiredState()) && capability.connectionAvailability(operation.connection()).available()) {
                AsyncTools.withTimeout(capability.attachConnection(operation.connection()), TaskSchedulers.current(), Duration.ofSeconds(90)).whenComplete((ignored, failure) -> ui(() -> {
                    if (!valid(epoch, identity)) return;
                    if (failure != null) {
                        finish(null, failure, epoch, identity);
                        return;
                    }
                    settle(operation, epoch, identity);
                }));
                return;
            }
        }
        settle(operation, epoch, identity);
    }

    private void settle(Operation operation, long epoch, String identity) {
        poll = capability.client().settle(operation, Duration.ofSeconds(90), Clock.system(), TaskSchedulers.current());
        poll.result().whenComplete((value, failure) -> ui(() -> finish(value, failure, epoch, identity)));
    }

    private void finish(Operation operation, Throwable failure, long epoch, String identity) {
        if (!valid(epoch, identity)) return;
        poll = null;
        busy = false;
        if (failure != null || operation == null || !operation.succeeded()) {
            error = failure != null ? failure(failure) : operation == null ? "Operation Response Is Missing"
                    : operation.error() == null ? "Operation Failed" : structured(operation.error());
            actionState.setMessage("Try Again");
            notifyFailure(error);
        } else {
            receipt = null;
            retry = null;
            error = "";
            actionState.setMessage("Saved");
            if (settled != null) settled.accept(operation);
            settled = null;
            load();
        }
        updateStatus();
        refresh.run();
    }

    private void retryAction() {
        if (busy || closed) return;
        if (targetRetry != null) submitTarget();
        else if (receipt == null) submit();
        else {
            busy = true;
            error = "";
            observe(receipt, generation, account);
        }
    }

    private void changed(Operation operation) {
        if (operation.connection() != null) capability.connectionChanged(operation.connection());
    }

    private boolean valid(long epoch, String identity) {
        return !closed && epoch == generation && identity.equals(account) && identity.equals(currentAccount()) && capability.authenticated();
    }

    private Endpoint currentEndpoint(String connectionId, String endpointId) {
        Connection connection = connectionValues.get(connectionId);
        if (connection == null) return null;
        return connection.endpoints().stream().filter(endpoint -> endpointId.equals(endpoint.id())).findFirst().orElse(null);
    }

    private void copyEndpoint(Endpoint endpoint) {
        if (endpoint == null) return;
        if (!"READY".equalsIgnoreCase(endpoint.readiness()) && !"ACTIVE".equalsIgnoreCase(endpoint.readiness()) && !"STOPPED".equalsIgnoreCase(endpoint.readiness())) {
            notifyFailure("This Port Is Still Being Set Up. Try Again Shortly");
            return;
        }
        copy(canonical(endpoint));
    }

    private String canonical(Endpoint endpoint) {
        if (endpoint == null || safe(endpoint.publicHost()).isBlank() || endpoint.publicPort() < 1) return "Address Not Assigned Yet";
        return endpoint.publicHost() + ":" + endpoint.publicPort();
    }

    private void copy(String value) {
        if (value.isBlank()) return;
        capability.copyAddress(value);
        new Notification("Address Copied", value, Notification.Type.SUCCESS);
    }

    private String connectionState(Connection connection) {
        if (!safe(connection.pendingOperationId()).isBlank()) return "Saving Changes";
        return "ENABLED".equals(connection.desiredState()) ? readableState(connection.state()) : "Stopped";
    }

    private String bindingText(Binding binding) {
        if (binding == null) return "Server Unavailable";
        String name = targetNames.get(bindingKey(binding));
        if (name != null && !name.isBlank()) return name;
        String kind = "LOCAL".equals(binding.kind()) ? "Local Server" : "HOSTED".equals(binding.kind()) ? "Hosted Server" : "Paired Server";
        if (summary == null) return kind;
        List<Binding> peers = summary.bindings().stream().filter(value -> safe(value.kind()).equals(safe(binding.kind()))).toList();
        for (int index = 0; index < peers.size(); index++) {
            if (peers.get(index).id().equals(binding.id())) return peers.size() == 1 ? kind : kind + " " + (index + 1);
        }
        return kind;
    }

    private String bindingKey(Binding binding) {
        if (binding == null) return "";
        return binding.kind() + ":" + safe(binding.instanceId()) + ":" + safe(binding.serverId()) + ":" + safe(binding.agentId());
    }

    private String addressUse(Address address) {
        if (!"ACTIVE".equalsIgnoreCase(address.status())) return readableState(address.status());
        List<String> names = summary.connections().stream().filter(connection -> connection.address() != null && address.id().equals(connection.address().id()))
                .map(Connection::name).toList();
        if (names.isEmpty() && !safe(address.connectionId()).isBlank()) return "Connecting To A Server";
        return names.isEmpty() ? "Ready To Use" : String.join(", ", names);
    }

    private String readableState(String value) {
        return switch (safe(value).toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "READY", "AVAILABLE" -> "Ready";
            case "ONLINE" -> "Running";
            case "OFFLINE", "DISCONNECTED" -> "Disconnected";
            case "STOPPED", "DISABLED" -> "Stopped";
            case "PENDING", "PREPARING", "STARTING", "ENABLED" -> "Starting";
            case "REVOKING", "RELEASING", "QUARANTINED", "RETIRED", "RETIRING" -> "Releasing";
            case "FAILED", "ERROR" -> "Needs Attention";
            case "UNAVAILABLE", "DEGRADED" -> "Unavailable";
            default -> readable(value);
        };
    }

    private String readable(String value) {
        String text = safe(value).replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "Server");
        text = text.replace('_', ' ').replace("Ports", "Ports").replace("Port", "Port").replace("Binding", "Server");
        if (text.equals(text.toUpperCase(Locale.ROOT))) {
            List<String> words = new ArrayList<>();
            for (String word : text.toLowerCase(Locale.ROOT).split(" +")) if (!word.isBlank()) words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            return String.join(" ", words);
        }
        return text;
    }

    private String relativeTime(String value) {
        try {
            long seconds = (Instant.parse(value).toEpochMilli() - System.currentTimeMillis()) / 1000;
            long magnitude = Math.abs(seconds);
            if (magnitude < 5) return "Now";
            String amount = magnitude < 60 ? plural(magnitude, "Second") : magnitude < 3600 ? plural((magnitude + 59) / 60, "Minute")
                    : magnitude < 86400 ? plural((magnitude + 3599) / 3600, "Hour") : plural((magnitude + 86399) / 86400, "Day");
            return seconds > 0 ? "In " + amount : amount + " Ago";
        } catch (RuntimeException failure) {
            return "Soon";
        }
    }

    private String plural(long count, String unit) {
        return count + " " + (count == 1 ? unit : "Address".equals(unit) ? "Addresses" : unit + "s");
    }

    private String formatBytes(String value) {
        long bytes;
        try {
            bytes = Long.parseLong(value);
        } catch (RuntimeException failure) {
            return "Unavailable";
        }
        if (bytes < 1024) return bytes + " B";
        double size = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            size /= 1024;
            unit++;
        } while (size >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    private String failure(Throwable failure) {
        Throwable value = failure;
        while (value != null && value.getCause() != null && !(value instanceof ReProxyClient.Failure)) value = value.getCause();
        if (value instanceof ReProxyClient.Failure problem) return structured(problem.error());
        return value == null || safe(value.getMessage()).isBlank() ? "Could Not Complete This Action. Try Again" : readable(value.getMessage());
    }

    private String structured(ReProxyModels.Error value) {
        if (value.status() == 429) {
            String retry = !safe(value.retryAt()).isBlank() ? relativeTime(value.retryAt()) : "";
            if (retry.isBlank() && safe(value.retryAfter()).matches("[0-9]+")) {
                try {
                    retry = relativeTime(Instant.now().plusSeconds(Long.parseLong(value.retryAfter())).toString());
                } catch (RuntimeException ignored) {
                    retry = "Soon";
                }
            }
            return "Too Many Changes. Try Again " + (retry.isBlank() ? "Shortly" : retry);
        }
        if (value.status() == 412 || value.status() == 428) return "These Settings Changed. Refresh And Try Again";
        return switch (safe(value.code()).toUpperCase(Locale.ROOT)) {
            case "QUOTA_EXCEEDED" -> "Your Saved Items Are Full. Remove An Unused Address, Server Or Port And Try Again";
            case "BINDING_REQUIRED", "TARGET_DENIED", "TARGET_UNAVAILABLE" -> "Reconnect This Server Before Sharing Its Ports";
            case "POOL_UNAVAILABLE", "SERVICE_UNAVAILABLE", "SUFFIX_UNAVAILABLE" -> "This Domain Is Temporarily Unavailable. Choose Another Or Try Again Later";
            case "NAME_TAKEN" -> "This Address Is Already Taken. Choose Another Name";
            case "OPERATION_PENDING" -> "A Change Is Still Being Saved. Try Again Shortly";
            default -> readable(safe(value.message()).isBlank() ? "Could Not Complete This Action. Try Again" : value.message());
        };
    }

    private void notifyFailure(String value) {
        new Notification(value.startsWith("Too Many Changes") ? "Please Wait" : "ReProxy", value, Notification.Type.ERROR);
    }

    private <T> Async<T> timeout(Async<T> value) {
        return AsyncTools.withTimeout(value, TaskSchedulers.current(), Duration.ofSeconds(20));
    }

    private PopupWidget.Builder popup(String title) {
        Screen screen = ScreenManager.getInstance().getCurrentScreen();
        return screen == null ? null : new PopupWidget.Builder(title).pos(50, screen.height / 5).width(440).setResizable(false);
    }

    private void show(PopupWidget.Builder builder) {
        Screen screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null) return;
        PopupWidget popup = builder.build();
        screen.addDrawableChild(popup);
        popup.show();
    }

    private TextInputWidget input(String placeholder, String text) {
        return new TextInputWidget.Builder().placeholder(placeholder).text(safe(text)).size(250, 20).build();
    }

    private ScrollSelectorWidget selector(List<String> options, int selected) {
        return new ScrollSelectorWidget.Builder().options(options).selectedIndex(selected).size(280, 20).build();
    }

    private AnimatedButton message(String text) {
        return new AnimatedButton.Builder().label(safe(text)).active(false).build();
    }

    private SquareButtonWidget button(String image, String hint, Runnable action) {
        return new SquareButtonWidget.Builder().imagePath(image).hint(hint).onClick(action).size(18, 18).build();
    }

    private PopupRow row(String label, Widget... widgets) {
        return new PopupRow.Builder(label, widgets).build();
    }

    private int port(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Port Must Be Between 1 And 65535");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void ui(Runnable task) {
        ScreenManager.getInstance().execute(task);
    }

    private record ConnectionRowStamp(String revision, String desiredState, String state, String observedAt, String binding,
                                      String pendingOperationId, String name, String publicHost) {
    }

    private record EndpointRowStamp(String name, String protocol, int targetPort, boolean enabled, String publicHost, int publicPort,
                                    String readiness, String advertisement) {
    }

    private record AddressRowStamp(Address address, String use) {
    }

    private record Draft(Binding binding, Consumer<EndpointSpec> save) {
    }

    private final class AddressForm {
        private final long epoch = generation;
        private final String identity = account;
        private final long formId = ++form;
        private final TextInputWidget label;
        private final AnimatedButton suffix;
        private String suffixId = "";
        private List<Suffix> suffixes;
        private final AnimatedButton preview;
        private final AnimatedButton availability;
        private List<String> transports;
        private List<String> routing;
        private String checkedLabel = "";
        private String checkedSuffix = "";
        private boolean available;
        private long checkId;
        private long catalogDeadline;
        private long availabilityDeadline;

        private AddressForm(PopupWidget.Builder popup, Address address, List<String> transports, List<String> routing) {
            this.transports = transports;
            this.routing = routing;
            suffixes = availableSuffixes(catalog);
            catalogDeadline = deadline(catalog.expiresAt());
            int selected = 0;
            for (int index = 0; index < suffixes.size(); index++) {
                Suffix value = suffixes.get(index);
                if (address == null && "reproxy.link".equalsIgnoreCase(value.suffix()) || address != null && value.id().equals(address.suffixId())) selected = index;
            }
            label = input("my-server", address == null ? "" : address.label());
            if (!suffixes.isEmpty()) suffixId = suffixes.get(selected).id();
            suffix = new AnimatedButton.Builder().label(suffixes.isEmpty() ? "No Domains Available" : suffixes.get(selected).suffix())
                    .onClick(this::chooseSuffix).build();
            preview = message("Enter A Name");
            availability = message("");
            popup.addRow("address-name", "Name", label).addRow("address-domain", "Domain", suffix)
                    .addRow("address-preview", "Address", preview).addRow("address-availability", "", availability);
            popup.addRow("address-refresh", "", new AnimatedButton.Builder().label("Check Again").onClick(this::refreshCatalog).build());
            label.setOnChange(this::check);
            check();
        }

        private List<Suffix> availableSuffixes(Catalog value) {
            return value.suffixes().stream().filter(candidate -> "ACTIVE".equalsIgnoreCase(candidate.state()) && "READY".equalsIgnoreCase(candidate.readiness()))
                    .filter(candidate -> candidate.transports().containsAll(transports) && candidate.routingModes().containsAll(routing)).toList();
        }

        private void requirements(List<String> transports, List<String> routing) {
            if (this.transports.equals(transports) && this.routing.equals(routing)) return;
            this.transports = List.copyOf(transports);
            this.routing = List.copyOf(routing);
            suffixes = availableSuffixes(catalog);
            Suffix selected = suffixes.stream().filter(candidate -> suffixId.equals(candidate.id())).findFirst().orElse(suffixes.isEmpty() ? null : suffixes.getFirst());
            suffixId = selected == null ? "" : selected.id();
            suffix.setMessage(selected == null ? "No Domains Available" : selected.suffix());
            available = false;
        }

        private void chooseSuffix() {
            if (!valid(epoch, identity) || formId != form) return;
            if (System.currentTimeMillis() >= catalogDeadline) {
                refreshCatalog();
                return;
            }
            Screen screen = ScreenManager.getInstance().getCurrentScreen();
            if (screen == null || suffixes.isEmpty()) return;
            ItemSelectorWidget.Builder choices = new ItemSelectorWidget.Builder(screen).searchPlaceholder("Search Domains");
            for (Suffix candidate : suffixes) choices.addItem(candidate.suffix(), () -> {
                if (!valid(epoch, identity) || formId != form) return;
                if (System.currentTimeMillis() >= catalogDeadline || suffixes.stream().noneMatch(value -> value.id().equals(candidate.id()))) {
                    refreshCatalog();
                    return;
                }
                suffixId = candidate.id();
                suffix.setMessage(candidate.suffix());
                check();
            });
            ItemSelectorWidget selector = choices.build();
            selector.setSelectedItem(suffix.getMessage());
            selector.show(suffix.getX(), suffix.getY() + suffix.getHeight());
        }

        private void visible(PopupWidget.Builder popup, boolean visible) {
            for (String id : List.of("address-name", "address-domain", "address-preview", "address-availability", "address-refresh")) {
                popup.getWidget().setRowVisibility(id, visible);
            }
        }

        private void check() {
            checkId++;
            available = false;
            if (availabilityTask != null) availabilityTask.cancel();
            if (availabilityExpiry != null) availabilityExpiry.cancel();
            availabilityDeadline = 0;
            if (System.currentTimeMillis() >= catalogDeadline) {
                refreshCatalog();
                return;
            }
            scheduleExpiry(catalogDeadline);
            String name = label.getText().trim().toLowerCase(Locale.ROOT);
            if (suffixes.isEmpty()) {
                preview.setMessage("No Domains Available");
                return;
            }
            Suffix selected = suffixes.stream().filter(candidate -> suffixId.equals(candidate.id())).findFirst().orElse(null);
            if (selected == null) return;
            preview.setMessage(name.isBlank() ? "Enter A Name" : name + "." + selected.suffix());
            if (!name.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                availability.setMessage(name.isBlank() ? "" : "Use Letters, Numbers And Hyphens, Up To 63 Characters");
                return;
            }
            availability.setMessage("Checking Availability");
            long id = checkId;
            List<String> protocols = List.copyOf(transports);
            List<String> routes = List.copyOf(routing);
            availabilityTask = TaskSchedulers.current().schedule(() -> timeout(capability.client().availability(new AddressCheck(name, List.of(selected.id()), protocols, routes)))
                    .whenComplete((value, failure) -> ui(() -> {
                        if (!valid(epoch, identity) || formId != form || id != checkId) return;
                        if (failure != null) {
                            availability.setMessage("Could Not Check This Address");
                            notifyFailure(ReProxyAccountSettings.this.failure(failure));
                            return;
                        }
                        if (value == null || value.results().isEmpty()) {
                            availability.setMessage("Availability Is Unknown");
                            return;
                        }
                        ReProxyModels.NameAvailability result = value.results().getFirst();
                        checkedLabel = name;
                        checkedSuffix = selected.id();
                        availabilityDeadline = safe(value.expiresAt()).isBlank() ? deadline(value.observedAt()) + 30000 : deadline(value.expiresAt());
                        if (System.currentTimeMillis() >= Math.min(catalogDeadline, availabilityDeadline)) {
                            stale();
                            return;
                        }
                        scheduleExpiry(Math.min(catalogDeadline, availabilityDeadline));
                        boolean free = "AVAILABLE".equalsIgnoreCase(result.nameState());
                        available = free && "READY".equalsIgnoreCase(result.readiness());
                        availability.setMessage(free ? available ? "Available" : "Domain Temporarily Unavailable"
                                : safe(result.releaseAt()).isBlank() ? "Already Taken. Try Another Name" : "Available " + relativeTime(result.releaseAt()));
                    })), Duration.ofMillis(300));
        }

        private void refreshCatalog() {
            if (!valid(epoch, identity) || formId != form) return;
            checkId++;
            long id = checkId;
            available = false;
            availability.setMessage("Refreshing Availability");
            timeout(capability.client().catalog()).whenComplete((value, failure) -> ui(() -> {
                if (!valid(epoch, identity) || formId != form || id != checkId) return;
                if (failure != null || value == null) {
                    availability.setMessage("Could Not Refresh Availability");
                    notifyFailure(ReProxyAccountSettings.this.failure(failure));
                    return;
                }
                List<Suffix> next = availableSuffixes(value);
                if (next.isEmpty()) {
                    catalog = value;
                    catalogDeadline = deadline(value.expiresAt());
                    suffixes = List.of();
                    suffixId = "";
                    suffix.setMessage("No Domains Available");
                    preview.setMessage("No Domains Available");
                    availability.setMessage("No Domains Are Available");
                    return;
                }
                Suffix selected = next.stream().filter(candidate -> suffixId.equals(candidate.id())).findFirst().orElse(next.getFirst());
                catalog = value;
                catalogDeadline = deadline(value.expiresAt());
                suffixes = next;
                suffixId = selected.id();
                suffix.setMessage(selected.suffix());
                check();
            }));
        }

        private long deadline(String value) {
            try {
                return Instant.parse(value).toEpochMilli();
            } catch (RuntimeException failure) {
                return 0;
            }
        }

        private void scheduleExpiry(long deadline) {
            if (availabilityExpiry != null) availabilityExpiry.cancel();
            long id = checkId;
            availabilityExpiry = TaskSchedulers.current().schedule(() -> ui(() -> {
                if (valid(epoch, identity) && formId == form && id == checkId && System.currentTimeMillis() >= deadline) stale();
            }), Duration.ofMillis(Math.max(1, deadline - System.currentTimeMillis())));
        }

        private void stale() {
            available = false;
            availability.setMessage("Check This Address Again Before Saving");
        }

        private AddressSpec value() {
            if (suffixes.isEmpty()) return null;
            if (formId != form || System.currentTimeMillis() >= catalogDeadline || System.currentTimeMillis() >= availabilityDeadline) {
                stale();
                notifyFailure("Check This Address Again Before Saving");
                refreshCatalog();
                return null;
            }
            String name = label.getText().trim().toLowerCase(Locale.ROOT);
            String selected = suffixId;
            if (!available || !name.equals(checkedLabel) || !selected.equals(checkedSuffix)) {
                notifyFailure("Choose An Available Name Before Saving");
                return null;
            }
            return new AddressSpec(name, selected);
        }
    }
}
