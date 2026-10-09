package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.RemotelyServerApi.ServerSubdomain;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class ServerSubdomainSettingsController {
    private final RemotelyClient client;
    private final String serverId;
    private final boolean network;
    private final ServerScreenHost listenerHost;
    private final BrowserSafeState.LongValue authGeneration = new BrowserSafeState.LongValue();
    private final Runnable authListener = () -> {
        authGeneration.incrementAndGet();
        ScreenManager.getInstance().execute(this::checkOwner);
    };
    private final Runnable networkListener = () -> ScreenManager.getInstance().execute(this::membershipChanged);
    private final MountableButtonWidget status = new MountableButtonWidget.Builder("Server Address").build();
    private final IconButton edit = new IconButton.Builder().label("Set Subdomain").imagePath("edit.png").size(170, 20).onClick(this::edit).build();
    private final IconButton retry = new IconButton.Builder().label("Refresh Address").imagePath("reload.png").size(170, 20).onClick(this::refresh).build();
    private final Setting setting;
    private Supplier<Screen> screen = () -> ScreenManager.getInstance().getCurrentScreen();
    private Runnable changed = () -> {};
    private Stamp owner;
    private String networkId = "";
    private ServerSubdomain value;
    private PopupWidget editor;
    private long generation;
    private boolean loaded;
    private boolean busy;
    private boolean member;
    private boolean closed;
    private boolean refreshRequired;
    private String error = "";

    public ServerSubdomainSettingsController(RemotelyClient client, String serverId, boolean network) {
        this.client = Objects.requireNonNull(client, "client");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.network = network;
        Setting.Builder builder = new Setting.Builder("Subdomain");
        builder.addRow("", status);
        builder.addRow("", edit);
        builder.addRow("", retry);
        setting = builder.build();
        listenerHost = host();
        listenerHost.addAuthStateListener(authListener);
        listenerHost.addNetworkChangeListener(networkListener);
    }

    public void network(String networkId) {
        Objects.requireNonNull(networkId, "networkId");
        if (!network || networkId.isBlank()) throw new IllegalArgumentException("Network Identity Is Required");
        if (this.networkId.equals(networkId)) return;
        this.networkId = networkId;
        generation++;
        if (editor != null) editor.hide();
        editor = null;
        value = null;
        loaded = false;
        busy = false;
        member = false;
        refreshRequired = false;
        error = "";
        rows();
        changed.run();
    }

    public void owner(Supplier<Screen> screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    public void onChanged(Runnable changed) {
        this.changed = changed == null ? () -> {} : changed;
    }

    public List<Setting> settings() {
        if (closed) return List.of();
        checkOwner();
        if (!loaded && !busy && error.isBlank()) load();
        rows();
        return member ? List.of() : List.of(setting);
    }

    private ServerScreenHost host() {
        return client.getHost().serverScreenHost(client);
    }

    private Stamp stamp() {
        ServerScreenHost host = host();
        String account = host.accountIdentity().authenticated() ? host.hostedNetworkAccount() : "";
        return new Stamp(host, client.getApiClient(), account, host.authenticationSession(), authGeneration.get());
    }

    private void checkOwner() {
        if (closed) return;
        Stamp current = stamp();
        if (Objects.equals(owner, current)) return;
        owner = current;
        generation++;
        if (editor != null) editor.hide();
        editor = null;
        value = null;
        loaded = false;
        busy = false;
        member = false;
        refreshRequired = false;
        error = "";
        rows();
        changed.run();
    }

    public void refresh() {
        if (closed || busy) return;
        checkOwner();
        loaded = false;
        error = "";
        load();
    }

    private void membershipChanged() {
        if (closed) return;
        if (busy) refreshRequired = true;
        else refresh();
    }

    private Snapshot snapshot(ServerSubdomain result) {
        result = valid(result);
        if (network && (networkId.isBlank() || !networkId.equals(result.networkId()) || !serverId.equals(result.serverId()))) {
            throw new IllegalStateException("The Network Proxy Changed. Refresh Your Networks");
        }
        return new Snapshot(result, !network && !result.networkId().isBlank());
    }

    private void load() {
        Stamp captured = owner;
        if (captured == null || captured.api() == null || captured.account().isBlank() || serverId.isBlank()) {
            error = "Sign In To Manage This Subdomain";
            rows();
            return;
        }
        busy = true;
        long request = ++generation;
        rows();
        Async<Snapshot> read;
        try {
            read = captured.api().serverSubdomain(serverId).thenApply(this::snapshot);
        } catch (RuntimeException failure) {
            read = Async.failed(failure);
        }
        AsyncTools.withTimeout(read, client.getComposition().scheduler(), Duration.ofSeconds(30)).whenComplete((result, failure) ->
                ScreenManager.getInstance().execute(() -> {
                    if (!current(captured, request)) return;
                    busy = false;
                    if (failure == null) {
                        value = result.value();
                        member = result.member();
                        loaded = true;
                    } else error = message(failure);
                    rows();
                    changed.run();
                    refreshIfRequired();
                }));
    }

    private void edit() {
        checkOwner();
        if (closed || busy || !loaded || member || value == null || !value.editable() || screen.get() == null) return;
        Stamp captured = owner;
        ServerSubdomain admitted = value;
        long request = generation;
        TextInputWidget input = new TextInputWidget.Builder().placeholder("Subdomain").size(230, 20).build();
        input.setText(value.subdomain());
        PopupWidget.Builder popup = new PopupWidget.Builder("Set Subdomain").width(320).setResizable(false).addRow("Subdomain", input);
        popup.addTitleAction("Save", () -> {
            if (!current(captured, request) || busy) return;
            String label = input.getText().strip().toLowerCase(Locale.ROOT);
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])") || label.contains("--")) {
                new Notification("Subdomain Invalid", "Use 3 To 63 Letters, Numbers, Or Hyphens", Notification.Type.WARN);
                return;
            }
            popup.getWidget().hide();
            editor = null;
            update(captured, admitted, label);
        }, PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget widget = popup.build();
        if (editor != null) editor.hide();
        editor = widget;
        screen.get().addDrawableChild(widget);
        widget.show();
    }

    private void update(Stamp captured, ServerSubdomain admitted, String label) {
        busy = true;
        long request = ++generation;
        long deadline = client.getComposition().clock().millis() + 30000;
        error = "";
        rows();
        Async<Snapshot> mutation;
        try {
            mutation = captured.api().serverSubdomain(serverId).thenApply(this::snapshot).thenCompose(target -> {
                if (!current(captured, request) || !busy || client.getComposition().clock().millis() >= deadline) {
                    return Async.failed(new IllegalStateException("Subdomain Request Expired"));
                }
                if (target.member()) return Async.completed(target);
                if (refreshRequired) return Async.failed(new IllegalStateException("Network Membership Changed. Refresh Before Updating The Address"));
                if (!sameAuthority(admitted, target.value())) return Async.failed(new IllegalStateException("Network Changed. Refresh Before Updating The Address"));
                if (!target.value().editable()) return Async.failed(new IllegalStateException(target.value().reason().isBlank()
                        ? "Subdomain Changes Are Unavailable" : target.value().reason()));
                return captured.api().updateServerSubdomain(admitted.serverId(), label, admitted.networkId(), admitted.networkRevision())
                        .thenApply(value -> new Snapshot(committed(value, admitted, label), false));
            });
        } catch (RuntimeException failure) {
            mutation = Async.failed(failure);
        }
        AsyncTools.withTimeout(mutation, client.getComposition().scheduler(), Duration.ofSeconds(30)).whenComplete((result, failure) ->
                ScreenManager.getInstance().execute(() -> {
                    if (!current(captured, request)) return;
                    busy = false;
                    if (failure == null) {
                        value = result.value();
                        member = result.member();
                        loaded = true;
                        if (member) new Notification("Network Address", "Manage This Address From The Network", Notification.Type.WARN);
                        else new Notification("Subdomain Updated", value.fullDomain(), Notification.Type.SUCCESS);
                    } else {
                        error = message(failure);
                        loaded = false;
                        new Notification("Subdomain Update Failed", error, Notification.Type.ERROR);
                    }
                    rows();
                    changed.run();
                    refreshIfRequired();
                }));
    }

    private void refreshIfRequired() {
        if (!refreshRequired) return;
        refreshRequired = false;
        refresh();
    }

    private ServerSubdomain valid(ServerSubdomain value) {
        if (value == null || value.serverId().isBlank() || !value.networkRevision().matches("0|[1-9][0-9]*")) {
            throw new IllegalStateException("Server Subdomain Response Is Invalid");
        }
        return value;
    }

    private static boolean sameAuthority(ServerSubdomain first, ServerSubdomain second) {
        return first.serverId().equals(second.serverId()) && first.networkId().equals(second.networkId())
                && first.networkRevision().equals(second.networkRevision());
    }

    private ServerSubdomain committed(ServerSubdomain value, ServerSubdomain admitted, String label) {
        ServerSubdomain result = valid(value);
        if (!sameAuthority(admitted, result) || !label.equals(result.subdomain().strip().toLowerCase(Locale.ROOT)) || result.fullDomain().isBlank()) {
            throw new IllegalStateException("Subdomain Update Was Not Confirmed");
        }
        return result;
    }

    private boolean current(Stamp captured, long request) {
        return !closed && request == generation && captured.equals(owner) && captured.equals(stamp());
    }

    private void rows() {
        setting.setVisible(!member);
        status.setDescription(!error.isBlank() ? error : busy ? "Loading Address" : value == null ? "Address Unavailable"
                : !value.editable() ? value.reason().isBlank() ? "Subdomain Changes Are Unavailable" : value.reason()
                : value.fullDomain().isBlank() ? "No Subdomain Is Set" : value.fullDomain());
        edit.setActive(!busy && loaded && !member && value != null && value.editable() && owner != null && !owner.account().isBlank());
        retry.setActive(!busy);
    }

    public void close() {
        if (closed) return;
        closed = true;
        generation++;
        if (editor != null) editor.hide();
        editor = null;
        listenerHost.removeAuthStateListener(authListener);
        listenerHost.removeNetworkChangeListener(networkListener);
        changed = () -> {};
    }

    private static String message(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure.getMessage() == null || failure.getMessage().isBlank() ? "Subdomain Request Failed" : failure.getMessage();
    }

    private record Stamp(ServerScreenHost host, RemotelyServerApi api, String account, String session, long authGeneration) {}
    private record Snapshot(ServerSubdomain value, boolean member) {}
}
