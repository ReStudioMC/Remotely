package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.network.DesktopNetworkAccess;
import redxax.oxy.remotely.network.NetworkAdoptionReport;
import redxax.oxy.remotely.network.NetworkAdoptionRoute;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkMemberManagement;
import redxax.oxy.remotely.network.NetworkValidationIssue;
import restudio.rebase.Rebase;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;

import java.util.List;

import static redxax.oxy.remotely.ui.server.NetworkRouteMappingFlow.rootMessage;
import static redxax.oxy.remotely.ui.server.NetworkRouteMappingFlow.titleCase;

public class NetworkAdoptionScreen extends ReScreen implements DesktopWindowBehaviorProvider {
    private final Screen parent;
    private final RemotelyClient remotelyClient;
    private final Instance proxy;
    private final NetworkAdoptionReport report;
    private String name;
    private TextInputWidget nameInput;
    private IconButton importButton;
    private IconButton scanButton;
    private NetworkRouteMappingFlow routeMappingFlow;
    private boolean busy;
    private boolean closed;

    public NetworkAdoptionScreen(Screen parent, RemotelyClient remotelyClient, Instance proxy, NetworkAdoptionReport report) {
        this(parent, remotelyClient, proxy, report, proxy.getName() + " Network");
    }

    private NetworkAdoptionScreen(Screen parent, RemotelyClient remotelyClient, Instance proxy, NetworkAdoptionReport report, String name) {
        this.parent = parent;
        enableNavigation(parent);
        this.remotelyClient = remotelyClient;
        this.proxy = proxy;
        this.report = report;
        this.name = name;
    }

    @Override
    public String getDesktopAppId() { return "network-adoption"; }

    @Override
    public String getDesktopAppTitle() { return "Import Network"; }

    @Override
    public String getDesktopAppIconPath() { return "merge.png"; }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() { return DesktopWindowBehavior.SINGLETON; }

    @Override
    public boolean closeThroughDesktopOverlay() { return !busy; }

    @Override
    public void init() {
        if (nameInput != null) name = nameInput.getText();
        super.init();
        closed = false;
        routeMappingFlow = new NetworkRouteMappingFlow(this, remotelyClient, screen -> ScreenManager.getInstance().replaceScreen(this, screen));
        importButton = new IconButton.Builder().label("Import Network").imagePath("checkmark.png").size(110, 20)
            .autoWidthOnTextChange(true).onClick(this::adopt).build();
        scanButton = new IconButton.Builder().label("Scan Again").imagePath("reload.png").size(90, 20)
            .autoWidthOnTextChange(true).onClick(this::scan).build();
        header().addLeft(scanButton).addRight(importButton).build();
        tabs().setVisible(false);
        Container content = createContainer("network_import", 6, 36, width - 12, Math.max(1, height - 42))
            .columns(1).padding(4).verticalSpacing(2).layout(new ManagedLayout()).scrolling(true).backgroundDrawing(true);
        nameInput = new TextInputWidget.Builder().text(name).placeholder("Network Name").size(180, 20).build();
        nameInput.setOnChange(this::refreshActions);
        Setting.Builder overview = new Setting.Builder("Network");
        overview.addRow("", new MountableButtonWidget.Builder("Network Name").description("Choose A Name For This Network")
            .addWidget(nameInput).build());
        overview.addRow("", row(proxy.getName(), "Velocity Proxy • Port " + report.entryPort(), "network.png"));
        overview.addRow("", row("Connection", (report.proxyOnlineMode() ? "Online Mode" : "Offline Mode")
            + " • Forwarding: " + titleCase(report.forwardingMode().name()), "link.png"));
        addSection(content, overview);
        populateFindings(content);
        populateRoutes(content);
        if (!report.fallbackRoutes().isEmpty() || !report.forcedHosts().isEmpty()) {
            Setting.Builder routing = new Setting.Builder("Player Routing");
            if (!report.fallbackRoutes().isEmpty()) routing.addRow("", row("Join Order", String.join(" → ", report.fallbackRoutes()), "server.png"));
            report.forcedHosts().forEach((host, routes) -> routing.addRow("", row(host, String.join(" → ", routes), "link.png")));
            addSection(content, routing);
        }
        setActiveContainer(content);
        content.updateWidgetPositions();
        refreshActions();
    }

    private void populateFindings(Container content) {
        if (report.issues().isEmpty()) return;
        Setting.Builder findings = new Setting.Builder(report.canAdopt() ? "Review Notes" : "Before You Import");
        for (NetworkValidationIssue issue : report.issues()) {
            findings.addRow("", row(issue.subject().isBlank() || issue.subject().equals(proxy.getInstanceId())
                ? titleCase(issue.severity().name()) : issue.subject(), issue.message(), issue.blocksPersistence() ? "report.png" : "info.png"));
        }
        addSection(content, findings);
    }

    private void populateRoutes(Container content) {
        Setting.Builder routes = new Setting.Builder("Backend Servers");
        if (report.routes().isEmpty()) {
            routes.addRow("", row("No Backend Routes Found", "This Proxy Has No Existing Backend Routes To Import", "server.png"));
            routes.addRow("", new MountableButtonWidget.Builder("Set Up A New Network")
                .description("Use This Proxy And Add Backend Servers")
                .iconPath("add.png").addWidget(new IconButton.Builder().label("Set Up Network").size(110, 20)
                    .autoWidthOnTextChange(true).onClick(this::createNetwork).build()).build());
        } else {
            for (NetworkAdoptionRoute route : report.routes()) {
                boolean external = route.management() == NetworkMemberManagement.EXTERNAL;
                String status = external ? "External Server" : route.matched() ? "Matched" : "Needs Mapping";
                routes.addRow("", new MountableButtonWidget.Builder(route.routeName()).hiddenText(status)
                    .description(route.address() + ":" + route.port() + " • " + route.finding())
                    .iconPath(route.matched() ? "server.png" : "report.png")
                    .addWidget(new IconButton.Builder().label(route.matched() ? "Change" : "Resolve").size(70, 20)
                        .onClick(() -> openRouteMapping(route)).build()).build());
            }
            routes.addRow("", row(report.canAdopt() ? "Ready To Import" : "Resolve Routes Before Importing",
                "Import Keeps Existing Configuration. ReSync Can Be Installed Later In Network Settings", "info.png"));
        }
        addSection(content, routes);
    }

    private MountableButtonWidget row(String title, String description, String icon) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(title).description(description).iconPath(icon).build();
        row.setHeight(32);
        return row;
    }

    private void addSection(Container content, Setting.Builder builder) {
        Setting section = builder.build();
        section.fitContentHeight();
        content.addWidget(section);
    }

    private void refreshActions() {
        if (importButton == null) return;
        importButton.setActive(!busy && report.canAdopt() && !report.routes().isEmpty() && nameInput != null && !nameInput.getText().isBlank());
        scanButton.setActive(!busy);
        if (nameInput != null) nameInput.setActive(!busy);
    }

    private void openRouteMapping(NetworkAdoptionRoute route) {
        if (busy) return;
        List<Instance> instances = Rebase.get().getInstanceManager().getAllInstances();
        routeMappingFlow.openMapping(new NetworkRouteMappingFlow.RouteMapping(report, route, instances, proxy,
            () -> nameInput.getText(), (updated, requestedName) -> new NetworkAdoptionScreen(parent, remotelyClient, proxy, updated, requestedName),
            instance -> instance.getName() + " • " + instance.getInstanceId(), 100, true));
    }

    private void createNetwork() {
        if (busy) return;
        ServerScreenHost host = remotelyClient.getHost().serverScreenHost(remotelyClient);
        ScreenManager.getInstance().navigate(this, new NetworkCreationScreen(this, host, List.of(host.serverView(proxy))));
    }

    private void scan() {
        if (busy) return;
        busy = true;
        refreshActions();
        Notification notification = new Notification.Builder().message("Scanning Network").description(proxy.getName())
            .type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        Async<NetworkAdoptionReport> request;
        try {
            request = DesktopNetworkAccess.capability(remotelyClient).scanForAdoption(proxy, Rebase.get().getInstanceManager().getAllInstances());
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((updated, error) -> ScreenManager.getInstance().execute(() -> {
                notification.update().message(error == null ? "Network Scanned" : "Network Scan Failed")
                    .description(error == null ? proxy.getName() : rootMessage(error))
                    .type(error == null ? Notification.Type.SUCCESS : Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                if (closed) return;
                busy = false;
                if (error == null) ScreenManager.getInstance().replaceScreen(this, new NetworkAdoptionScreen(parent, remotelyClient, proxy, updated, nameInput.getText()));
                else refreshActions();
            }));
    }

    private void adopt() {
        if (busy || !report.canAdopt() || report.routes().isEmpty() || nameInput.getText().isBlank()) return;
        busy = true;
        refreshActions();
        Notification notification = new Notification.Builder().message("Importing Network").description(proxy.getName())
            .type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        Async<NetworkDefinition> request;
        try {
            request = DesktopNetworkAccess.capability(remotelyClient).adoptNetwork(nameInput.getText().trim(), report,
                Rebase.get().getInstanceManager().getAllInstances());
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((network, error) -> ScreenManager.getInstance().execute(() -> {
            notification.update().message(error == null ? "Network Imported" : "Import Failed")
                .description(error == null ? network.name() : rootMessage(error))
                .type(error == null ? Notification.Type.SUCCESS : Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            if (closed) return;
            busy = false;
            if (error != null) {
                refreshActions();
                return;
            }
            ScreenManager.getInstance().replaceScreen(this,
                new NetworkOverviewScreen(parent, NetworkOverviewProvider.forClient(remotelyClient), network.networkId()));
        }));
    }

    @Override
    public void close() {
        if (!busy) ScreenManager.getInstance().goBack(this, parent);
    }

    @Override
    public void removed() {
        closed = true;
        super.removed();
    }
}
