package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;
import restudio.rescreen.util.TimeUtils;

import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;


import static restudio.rescreen.util.SoundUtils.playSound;

public class ReProxySettingsController {
    private static final String REPROXY_TAB = "ReProxy";
    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final ReProxySettingsCapability capability;
    private final ReProxyAccountSettings accountSettings;
    private final List<ServerModels.ReProxyDomain> domainCache = new ArrayList<>();
    private ServerModels.ReProxySummary summary;
    private volatile boolean dataLoaded;
    private volatile boolean loadingData;
    private volatile boolean loadingAction;
    private volatile String loadError;
    private volatile long loadStartedAt;
    private volatile long loadRequestId;
    private volatile long generation;
    private volatile boolean closed;
    private Setting statusSetting;
    private Setting domainsSetting;
    private PopupRow overviewRow;
    private PopupRow addressRow;
    private PopupRow limitsRow;
    private PopupRow stateRow;
    private PopupRow retryRow;
    private PopupRow emptyDomainsRow;
    private List<PopupRow> unavailableStatusRows;
    private PopupRow unavailableDomainRow;
    private PopupRow loginRow;
    private final Map<String, PopupRow> domainRows = new LinkedHashMap<>();

    public ReProxySettingsController(ReProxySettingsCapability capability) {
        this.capability = capability == null ? ReProxySettingsCapability.unavailable(false, "ReProxy Is Unavailable") : capability;
        accountSettings = this.capability.client() == null ? null : new ReProxyAccountSettings(this.capability, this::refreshReProxyTab);
    }

    public void cleanup() {
        if (accountSettings != null) accountSettings.cleanup();
        closed = true;
        generation++;
        loadRequestId++;
        loadingData = false;
        loadingAction = false;
    }

    public void activate() {
        if (accountSettings != null) {
            closed = false;
            accountSettings.activate();
            return;
        }
        generation++;
        loadRequestId++;
        loadingData = false;
        loadingAction = false;
        closed = false;
        if (statusSetting != null && capability.authenticated() && capability.availability().available()) loadData();
    }

    public List<Setting> getSettings() {
        if (accountSettings != null) return accountSettings.settings();
        if (closed) return statusSetting == null ? List.of() : domainsSetting == null ? List.of(statusSetting) : List.of(statusSetting, domainsSetting);
        if (!capability.authenticated()) {
            if (statusSetting == null) statusSetting = new Setting.Builder("ReProxy").build();
            if (loginRow == null) loginRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("ReStudio Login Required").active(false).build()).build();
            if (!statusSetting.getRows().equals(List.of(loginRow))) statusSetting.setRows(List.of(loginRow));
            return List.of(statusSetting);
        }

        ReProxySettingsCapability.Availability availability = capability.availability();
        if (!availability.available()) {
            return unavailableSettings(availability.reason());
        }

        ensureDataLoaded();
        if (overviewRow == null) {
            overviewRow = new PopupRow.Builder("Status", createOverviewWidget()).build();
            if (statusSetting == null) statusSetting = new Setting.Builder("ReProxy").build();
            statusSetting.setRows(List.of(overviewRow));
            stateRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Loading Domains").active(false).build()).build();
            retryRow = new PopupRow.Builder("", new AnimatedButton.Builder().label("Retry Load")
                    .accentType(ThemeManager.getDefaultAccent()).onClick(this::loadData).build()).build();
            emptyDomainsRow = new PopupRow.Builder("", new MountableButtonWidget.Builder("No Domains")
                    .description("Create Domain To Start").hiddenText(domainLimitText()).build()).build();
            if (domainsSetting == null) domainsSetting = new Setting.Builder("Domains").build();
        }
        refreshRows();
        return dataLoaded ? List.of(statusSetting, domainsSetting) : List.of(statusSetting);
    }

    private void refreshRows() {
        if (overviewRow == null) return;
        MountableButtonWidget overview = (MountableButtonWidget) overviewRow.getWidgets().getFirst();
        String title = loadingData ? "Loading" : dataLoaded ? "Ready" : "Unavailable";
        if (!safe(loadError).isBlank()) title = "Load Failed";
        overview.setName(title);
        overview.setDescription(summary != null && summary.activeTunnel != null && summary.activeTunnel.domain != null
                ? "Online | " + safeDomain(summary.activeTunnel.domain) : domainCountText());
        overview.setHiddenText(loadingAction ? "Working" : "");
        ((SquareButtonWidget) overview.mountedWidgets.get(0)).setActive(dataLoaded && canCreateDomain() && !loadingAction);
        ((SquareButtonWidget) overview.mountedWidgets.get(1)).setActive(!loadingData);

        List<PopupRow> statusRows = new ArrayList<>();
        statusRows.add(overviewRow);
        if (!dataLoaded) {
            ((AnimatedButton) stateRow.getWidgets().getFirst()).setMessage(loadingData ? "Loading Domains" : safe(loadError).isBlank() ? "Domains Unavailable" : "Load Failed");
            statusRows.add(stateRow);
            if (!safe(loadError).isBlank()) statusRows.add(retryRow);
        } else {
            ServerModels.ReProxyTunnel tunnel = summary == null ? null : summary.activeTunnel;
            if (addressRow == null || (tunnel != null) != (((MountableButtonWidget) addressRow.getWidgets().getFirst()).mountedWidgets.size() > 0)) {
                MountableButtonWidget address = tunnel == null
                        ? new MountableButtonWidget.Builder("No Active Tunnel").description("Start ReProxy From A Server").hiddenText(tunnelLimitText()).build()
                        : createActiveTunnelWidget(tunnel);
                addressRow = new PopupRow.Builder("Address", address).build();
            } else {
                MountableButtonWidget address = (MountableButtonWidget) addressRow.getWidgets().getFirst();
                if (tunnel == null) {
                    address.setHiddenText(tunnelLimitText());
                } else {
                    address.setName(tunnel.domain == null ? "Unknown Domain" : safeDomain(tunnel.domain));
                    address.setDescription("Port " + tunnel.localPort + " | " + safeStatus(tunnel.status));
                    address.setHiddenText(tunnel.connectionCount + " Connections | " + formatBytes(tunnel.bytesIn) + "/" + formatBytes(tunnel.bytesOut));
                }
            }
            statusRows.add(addressRow);
            if (limitsRow == null) limitsRow = new PopupRow.Builder("Limits", new AnimatedButton.Builder().label(limitText()).active(false).build()).build();
            ((AnimatedButton) limitsRow.getWidgets().getFirst()).setMessage(limitText());
            statusRows.add(limitsRow);
            refreshDomains();
        }
        if (!statusSetting.getRows().equals(statusRows)) statusSetting.setRows(statusRows);
    }

    private void refreshDomains() {
        if (domainsSetting == null) return;
        List<PopupRow> rows = new ArrayList<>();
        if (domainCache.isEmpty()) {
            domainRows.clear();
            MountableButtonWidget empty = (MountableButtonWidget) emptyDomainsRow.getWidgets().getFirst();
            empty.setHiddenText(domainLimitText());
            rows.add(emptyDomainsRow);
        } else {
            List<ServerModels.ReProxyDomain> domains = domainCache;
            Map<String, PopupRow> next = new LinkedHashMap<>();
            for (ServerModels.ReProxyDomain domain : domains) {
                if (safe(domain.id).isBlank()) continue;
                PopupRow row = domainRows.get(domain.id);
                ServerModels.ReProxyTunnel tunnel = activeTunnelForDomain(domain);
                boolean active = "ACTIVE".equalsIgnoreCase(safe(domain.status));
                if (row == null) {
                    row = new PopupRow.Builder("", createDomainWidget(domain)).build();
                } else {
                    MountableButtonWidget widget = (MountableButtonWidget) row.getWidgets().getFirst();
                    widget.setName(safeDomain(domain));
                    widget.setDescription(tunnel != null ? "Online | Port " + tunnel.localPort : safeStatus(domain.status) + " | " + safe(domain.subdomain));
                    widget.setHiddenText(tunnel != null
                            ? tunnel.connectionCount + " Connections | " + formatBytes(tunnel.bytesIn) + "/" + formatBytes(tunnel.bytesOut)
                            : domainTimeline(domain));
                    widget.setActive(active);
                }
                MountableButtonWidget widget = (MountableButtonWidget) row.getWidgets().getFirst();
                widget.mountedWidgets.get(1).setVisible(tunnel != null);
                widget.mountedWidgets.get(2).setVisible(tunnel == null && active);
                next.put(domain.id, row);
                rows.add(row);
            }
            domainRows.clear();
            domainRows.putAll(next);
        }
        if (!domainsSetting.getRows().equals(rows)) domainsSetting.setRows(rows);
    }

    private List<Setting> unavailableSettings(String reason) {
        String message = safe(reason).isBlank() ? "ReProxy Is Unavailable" : reason;
        if (unavailableStatusRows == null) {
            unavailableStatusRows = List.of(
                    new PopupRow.Builder("Status", createUnavailableOverviewWidget(message)).build(),
                    new PopupRow.Builder("Address", new MountableButtonWidget.Builder("No Active Tunnel")
                            .description(message).hiddenText("Tunnel Limit Unknown").build()).build(),
                    new PopupRow.Builder("Limits", new AnimatedButton.Builder()
                            .label("Domain Limit Unknown | Tunnel Limit Unknown").active(false).hint(message).build()).build());
            unavailableDomainRow = new PopupRow.Builder("", new MountableButtonWidget.Builder("No Domains")
                    .description(message).hiddenText("Domain Limit Unknown").build()).build();
        }
        ((MountableButtonWidget) unavailableStatusRows.get(0).getWidgets().getFirst()).setDescription(message);
        ((MountableButtonWidget) unavailableStatusRows.get(1).getWidgets().getFirst()).setDescription(message);
        ((MountableButtonWidget) unavailableDomainRow.getWidgets().getFirst()).setDescription(message);
        if (statusSetting == null) statusSetting = new Setting.Builder("ReProxy").build();
        if (domainsSetting == null) domainsSetting = new Setting.Builder("Domains").build();
        if (!statusSetting.getRows().equals(unavailableStatusRows)) statusSetting.setRows(unavailableStatusRows);
        if (!domainsSetting.getRows().equals(List.of(unavailableDomainRow))) domainsSetting.setRows(List.of(unavailableDomainRow));
        return List.of(statusSetting, domainsSetting);
    }

    private MountableButtonWidget createUnavailableOverviewWidget(String reason) {
        return new MountableButtonWidget.Builder("Unavailable")
                .description(reason)
                .addButton(new SquareButtonWidget.Builder().imagePath("create.png").hint(reason).accentType(ThemeManager.getAccent("nice")).active(false).size(18, 18).build())
                .addButton(new SquareButtonWidget.Builder().imagePath("reload.png").hint(reason).active(false).size(18, 18).build())
                .build();
    }

    private void ensureDataLoaded() {
        if (loadingData && hasLoadTimedOut()) {
            loadingData = false;
            loadError = "Load timed out";
            loadStartedAt = 0L;
            ScreenManager.getInstance().execute(() -> {
                if (!closed) new Notification("Load Failed", loadError, Notification.Type.ERROR);
            });
            refreshReProxyTab();
        }
        if (!dataLoaded && !loadingData && safe(loadError).isBlank()) {
            loadData();
        }
    }

    private void loadData() {
        if (closed || loadingData) {
            return;
        }
        long requestId = ++loadRequestId;
        loadingData = true;
        loadError = null;
        loadStartedAt = System.currentTimeMillis();
        AsyncTools.withTimeout(capability.summary(), TaskSchedulers.current(), Duration.ofSeconds(20))
                .whenComplete((value, error) -> ScreenManager.getInstance().execute(() -> {
                    if (closed || requestId != loadRequestId) {
                        return;
                    }
                    if (error == null && value != null) {
                        summary = value;
                        domainCache.clear();
                        if (value.domains != null) {
                            domainCache.addAll(value.domains);
                            sortDomains();
                        }
                        dataLoaded = true;
                        loadError = null;
                    } else {
                        loadError = sanitizeError(error);
                        new Notification("Load Failed", loadError, Notification.Type.ERROR);
                    }
                    loadingData = false;
                    loadStartedAt = 0L;
                    refreshReProxyTab();
                }));
    }

    private MountableButtonWidget createOverviewWidget() {
        String title = loadingData ? "Loading" : dataLoaded ? "Ready" : "Unavailable";
        if (!safe(loadError).isBlank()) {
            title = "Load Failed";
        }

        String description = domainCountText();
        if (summary != null && summary.activeTunnel != null && summary.activeTunnel.domain != null) {
            description = "Online | " + safeDomain(summary.activeTunnel.domain);
        }

        MountableButtonWidget.Builder builder = new MountableButtonWidget.Builder(title)
                .description(description)
                .hiddenText(loadingAction ? "Working" : "");

        builder.addButton(new SquareButtonWidget.Builder()
                .imagePath("create.png")
                .hint("Create Domain")
                .accentType(ThemeManager.getAccent("nice"))
                .active(dataLoaded && canCreateDomain() && !loadingAction)
                .onClick(this::showCreatePopup)
                .size(18, 18)
                .build());
        builder.addButton(new SquareButtonWidget.Builder()
                .imagePath("reload.png")
                .hint("Refresh")
                .active(!loadingData)
                .onClick(this::loadData)
                .size(18, 18)
                .build());

        return builder.build();
    }

    private MountableButtonWidget createActiveTunnelWidget(ServerModels.ReProxyTunnel tunnel) {
        String domain = tunnel.domain == null ? "Unknown Domain" : safeDomain(tunnel.domain);
        String description = "Port " + tunnel.localPort + " | " + safeStatus(tunnel.status);
        String hidden = tunnel.connectionCount + " Connections | " + formatBytes(tunnel.bytesIn) + "/" + formatBytes(tunnel.bytesOut);

        return new MountableButtonWidget.Builder(domain)
                .description(description)
                .hiddenText(hidden)
                .addButton(new SquareButtonWidget.Builder()
                        .imagePath("clipboard.png")
                        .hint("Copy Address")
                        .onClick(() -> {
                            if (summary != null && summary.activeTunnel != null && summary.activeTunnel.domain != null) {
                                copyAddress(safeDomain(summary.activeTunnel.domain));
                            }
                        })
                        .size(18, 18)
                        .build())
                .addButton(new SquareButtonWidget.Builder()
                        .imagePath("closeReverse.png")
                        .hint("Stop ReProxy")
                        .onClick(() -> {
                            if (summary != null && summary.activeTunnel != null) stopTunnel(summary.activeTunnel);
                        })
                        .accentType(ThemeManager.getAccent("danger"))
                        .size(18, 18)
                        .build())
                .build();
    }

    private MountableButtonWidget createDomainWidget(ServerModels.ReProxyDomain domain) {
        ServerModels.ReProxyTunnel activeTunnel = activeTunnelForDomain(domain);
        boolean online = activeTunnel != null;
        boolean active = "ACTIVE".equalsIgnoreCase(safe(domain.status));
        String description = online ? "Online | Port " + activeTunnel.localPort : safeStatus(domain.status) + " | " + safe(domain.subdomain);
        String hiddenText = online
                ? activeTunnel.connectionCount + " Connections | " + formatBytes(activeTunnel.bytesIn) + "/" + formatBytes(activeTunnel.bytesOut)
                : domainTimeline(domain);

        MountableButtonWidget.Builder builder = new MountableButtonWidget.Builder(safeDomain(domain))
                .description(description)
                .hiddenText(hiddenText)
                .onClick(() -> copyAddress(safeDomain(currentDomain(domain.id))))
                .addButton(new SquareButtonWidget.Builder()
                        .imagePath("clipboard.png")
                        .hint("Copy Address")
                        .onClick(() -> copyAddress(safeDomain(currentDomain(domain.id))))
                        .size(18, 18)
                        .build());

        SquareButtonWidget stopButton = new SquareButtonWidget.Builder()
                    .imagePath("closeReverse.png")
                    .hint("Stop ReProxy")
                    .onClick(() -> {
                        ServerModels.ReProxyTunnel current = activeTunnelForDomain(currentDomain(domain.id));
                        if (current != null) stopTunnel(current);
                    })
                    .accentType(ThemeManager.getAccent("danger"))
                    .size(18, 18)
                    .build();
        SquareButtonWidget deleteButton = new SquareButtonWidget.Builder()
                    .imagePath("delete.png")
                    .hint("Delete Domain")
                    .onClick(() -> showDeletePopup(currentDomain(domain.id)))
                    .accentType(ThemeManager.getAccent("danger"))
                    .size(18, 18)
                    .build();
        builder.addButton(stopButton).addButton(deleteButton);

        MountableButtonWidget widget = builder.build();
        stopButton.setVisible(online);
        deleteButton.setVisible(!online && active);
        if (!active) {
            widget.setActive(false);
        }
        return widget;
    }

    private ServerModels.ReProxyTunnel activeTunnelForDomain(ServerModels.ReProxyDomain domain) {
        if (summary == null || summary.activeTunnel == null || domain == null || summary.activeTunnel.domain == null) {
            return null;
        }
        ServerModels.ReProxyDomain tunnelDomain = summary.activeTunnel.domain;
        if (!safe(domain.id).isBlank() && domain.id.equals(tunnelDomain.id)) {
            return summary.activeTunnel;
        }
        if (!safe(domain.fullDomain).isBlank() && domain.fullDomain.equalsIgnoreCase(safe(tunnelDomain.fullDomain))) {
            return summary.activeTunnel;
        }
        return null;
    }

    private ServerModels.ReProxyDomain currentDomain(String id) {
        for (ServerModels.ReProxyDomain domain : domainCache) {
            if (id != null && id.equals(domain.id)) return domain;
        }
        return new ServerModels.ReProxyDomain();
    }

    private void showCreatePopup() {
        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null || loadingAction) {
            return;
        }

        PopupWidget.Builder builder = new PopupWidget.Builder("Create Domain")
                .pos(50, currentScreen.height / 5)
                .width(280)
                .setResizable(false);

        TextInputWidget subdomainInput = new TextInputWidget.Builder()
                .placeholder("Subdomain")
                .size(190, 20)
                .build();
        subdomainInput.setText(suggestSubdomain());
        builder.addRow("Subdomain", subdomainInput);
        builder.addRow("Limit", new AnimatedButton.Builder().label(domainLimitText()).active(false).build());
        builder.addTitleAction("Create", () -> {
            String subdomain = normalizeSubdomain(subdomainInput.getText());
            if (subdomain.isBlank()) {
                new Notification("Create Failed", "Subdomain Required", Notification.Type.ERROR);
                return;
            }
            playSound(Sound.CREATE);
            createDomain(subdomain);
            builder.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.PRIMARY);

        PopupWidget popup = builder.build();
        currentScreen.addDrawableChild(popup);
        popup.show();
    }

    private void showDeletePopup(ServerModels.ReProxyDomain domain) {
        Screen currentScreen = ScreenManager.getInstance().getCurrentScreen();
        if (currentScreen == null || domain == null || loadingAction) {
            return;
        }

        PopupWidget.Builder builder = new PopupWidget.Builder("Delete Domain")
                .pos(50, currentScreen.height / 5)
                .width(300)
                .setResizable(false);

        builder.addRow("Domain", new AnimatedButton.Builder().label(safeDomain(domain)).active(false).build());
        builder.addRow("Status", new AnimatedButton.Builder().label(safeStatus(domain.status)).active(false).build());
        builder.addTitleAction("Delete", () -> {
            playSound(Sound.DELETE);
            deleteDomain(domain);
            builder.getWidget().setVisible(false);
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);

        PopupWidget popup = builder.build();
        currentScreen.addDrawableChild(popup);
        popup.show();
    }

    private void createDomain(String subdomain) {
        if (closed || loadingAction) {
            return;
        }
        long actionGeneration = generation;
        loadingAction = true;
        refreshReProxyTab();
        capability.createDomain(subdomain)
                .whenComplete((domain, error) -> ScreenManager.getInstance().execute(() -> {
                    if (closed || actionGeneration != generation) return;
                    loadingAction = false;
                    if (error == null && domain != null) {
                        upsertDomain(domain);
                        new Notification("Domain Created", safeDomain(domain), Notification.Type.SUCCESS);
                        loadData();
                    } else {
                        String message = sanitizeError(error);
                        new Notification(isLimitError(message) ? "Limit Reached" : "Domain Taken", message, Notification.Type.ERROR);
                    }
                    refreshReProxyTab();
                }));
    }

    private void deleteDomain(ServerModels.ReProxyDomain domain) {
        if (closed || domain == null || safe(domain.id).isBlank() || loadingAction) {
            return;
        }
        long actionGeneration = generation;
        loadingAction = true;
        refreshReProxyTab();
        capability.deleteDomain(domain.id)
                .whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
                    if (closed || actionGeneration != generation) return;
                    loadingAction = false;
                    if (error == null) {
                        domain.status = "DISABLED";
                        new Notification("Domain Deleted", safeDomain(domain), Notification.Type.SUCCESS);
                        loadData();
                    } else {
                        new Notification("Delete Failed", sanitizeError(error), Notification.Type.ERROR);
                    }
                    refreshReProxyTab();
                }));
    }

    private void stopTunnel(ServerModels.ReProxyTunnel tunnel) {
        if (closed || tunnel == null || safe(tunnel.id).isBlank() || loadingAction) {
            return;
        }
        long actionGeneration = generation;
        loadingAction = true;
        refreshReProxyTab();
        capability.stopTunnel(tunnel.id)
                .whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
                    if (closed || actionGeneration != generation) return;
                    loadingAction = false;
                    if (error == null) {
                        new Notification("ReProxy Stopped", safeDomain(tunnel.domain), Notification.Type.SUCCESS);
                        if (summary != null) {
                            summary.activeTunnel = null;
                        }
                        loadData();
                    } else {
                        new Notification("Stop Failed", sanitizeError(error), Notification.Type.ERROR);
                    }
                    refreshReProxyTab();
                }));
    }

    private void copyAddress(String address) {
        if (safe(address).isBlank()) {
            return;
        }
        capability.copyAddress(address);
        new Notification("Address Copied", address, Notification.Type.SUCCESS);
    }

    private void upsertDomain(ServerModels.ReProxyDomain domain) {
        if (domain == null || safe(domain.id).isBlank()) {
            return;
        }
        domainCache.removeIf(existing -> domain.id.equals(existing.id));
        domainCache.add(domain);
        sortDomains();
    }

    private void sortDomains() {
        domainCache.sort(Comparator.comparing((ServerModels.ReProxyDomain domain) -> !"ACTIVE".equalsIgnoreCase(safe(domain.status)))
                .thenComparing(domain -> safe(domain.subdomain).toLowerCase(Locale.ROOT)));
    }

    private boolean canCreateDomain() {
        if (summary == null || summary.limits == null) {
            return true;
        }
        long activeDomains = domainCache.stream().filter(domain -> "ACTIVE".equalsIgnoreCase(safe(domain.status))).count();
        return activeDomains < summary.limits.maxDomainsPerUser;
    }

    private String domainCountText() {
        long active = domainCache.stream().filter(domain -> "ACTIVE".equalsIgnoreCase(safe(domain.status))).count();
        int total = domainCache.size();
        if (summary == null || summary.limits == null) {
            return active + "/" + total + " Domains";
        }
        return active + "/" + summary.limits.maxDomainsPerUser + " Domains";
    }

    private String limitText() {
        return domainLimitText() + " | " + tunnelLimitText();
    }

    private String domainLimitText() {
        if (summary == null || summary.limits == null || summary.usageWindow == null) {
            return "Domain Limit Unknown";
        }
        return summary.usageWindow.domainsCreatedToday + "/" + summary.limits.maxDomainCreatesPerDay + " Created Today";
    }

    private String tunnelLimitText() {
        if (summary == null || summary.limits == null || summary.usageWindow == null) {
            return "Tunnel Limit Unknown";
        }
        return summary.usageWindow.tunnelsStartedThisHour + "/" + summary.limits.maxTunnelStartsPerHour + " Started This Hour";
    }

    private String domainTimeline(ServerModels.ReProxyDomain domain) {
        List<String> parts = new ArrayList<>();
        String created = timeAgo(domain.createdAt);
        if (!created.isBlank()) {
            parts.add("Created " + created);
        }
        String lastUsed = timeAgo(domain.lastUsedAt);
        if (!lastUsed.isBlank()) {
            parts.add("Used " + lastUsed);
        }
        return parts.isEmpty() ? "Never Used" : String.join(" | ", parts);
    }

    private String timeAgo(String value) {
        if (safe(value).isBlank()) {
            return "";
        }
        try {
            return TimeUtils.timeSense(Instant.parse(value).toEpochMilli());
        } catch (DateTimeParseException ignored) {
            return "Unknown";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024D && unit < units.length - 1) {
            value /= 1024D;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private String suggestSubdomain() {
        long suffix = System.currentTimeMillis() % 100000L;
        return "server-" + Long.toString(suffix, 36);
    }

    private String normalizeSubdomain(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    private String safeDomain(ServerModels.ReProxyDomain domain) {
        if (domain == null) {
            return "Unknown Domain";
        }
        return safeDomain(domain.fullDomain);
    }

    private String safeDomain(String value) {
        return safe(value).isBlank() ? "Unknown Domain" : value;
    }

    private String safeStatus(String value) {
        return safe(value).isBlank() ? "Unknown" : value;
    }

    private boolean isLimitError(String message) {
        return safe(message).toLowerCase(Locale.ROOT).contains("limit");
    }

    private boolean hasLoadTimedOut() {
        return loadStartedAt > 0L && System.currentTimeMillis() - loadStartedAt > LOAD_TIMEOUT_MS;
    }

    private String sanitizeError(Throwable throwable) {
        if (throwable == null) {
            return "Request Failed";
        }
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return "Request Failed";
        }
        return message.length() > 180 ? message.substring(0, 180) + "..." : message;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void refreshReProxyTab() {
        if (closed) return;
        refreshRows();
        ScreenManager screenManager = ScreenManager.getInstance();
        Screen current = screenManager.getCurrentScreen();
        List<SettingsScreen> targets = new ArrayList<>();
        if (current instanceof SettingsScreen settingsScreen) {
            targets.add(settingsScreen);
        }
        if (screenManager.getDesktopWindowsOverlay() != null) {
            for (ScreenWindowWidget window : screenManager.getDesktopWindowsOverlay().getWindows()) {
                if (window.getScreen() instanceof SettingsScreen settingsScreen && !targets.contains(settingsScreen)) {
                    targets.add(settingsScreen);
                }
            }
        }
        for (SettingsScreen settingsScreen : targets) {
            settingsScreen.refreshTab(REPROXY_TAB);
        }
    }
}
