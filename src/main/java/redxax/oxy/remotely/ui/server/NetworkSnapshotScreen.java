package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.network.DesktopNetworkAccess;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.SyncRealm;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;
import restudio.resync.network.NetworkSnapshotMetadata;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;



public class NetworkSnapshotScreen extends ReScreen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final RemotelyClient remotelyClient;
    private final String networkId;
    private final String initialPlayerId;
    private final List<NetworkSnapshotMetadata> initialSnapshots;
    private final boolean initialHasMore;
    private NetworkDefinition network;
    private String playerId = "";
    private List<NetworkSnapshotMetadata> snapshots = List.of();
    private boolean hasMore;
    private boolean loading;

    public NetworkSnapshotScreen(Screen parent, RemotelyClient remotelyClient, String networkId) {
        this(parent, remotelyClient, networkId, "", List.of(), false);
    }

    private NetworkSnapshotScreen(Screen parent, RemotelyClient remotelyClient, String networkId, String initialPlayerId, List<NetworkSnapshotMetadata> initialSnapshots, boolean initialHasMore) {
        this.parent = parent;
        this.remotelyClient = remotelyClient;
        this.networkId = networkId;
        this.initialPlayerId = initialPlayerId;
        this.initialSnapshots = List.copyOf(initialSnapshots);
        this.initialHasMore = initialHasMore;
    }

    public String getDesktopAppId() {
        return "network-snapshots";
    }

    public String getDesktopAppTitle() {
        return "Player Snapshots";
    }

    public String getDesktopAppIconPath() {
        return "history.png";
    }

    @Override
    public void init() {
        super.init();
        network = DesktopNetworkAccess.manager(remotelyClient).getNetwork(networkId).orElse(null);
        if (network == null) {
            new Notification("Network Unavailable", Notification.Type.ERROR);
            client.setScreen(parent);
            return;
        }
        playerId = initialPlayerId;
        snapshots = initialSnapshots;
        hasMore = initialHasMore;
        header().addLeft("close.png", () -> client.setScreen(parent), "Back").addRight("reload.png", this::reload, "Reload").build();
        Container container = createContainer("network_snapshots", 6, 38, width - 12, Math.max(80, height - 44)).columns(1).padding(8).layout(new ManagedLayout()).scrolling(true).backgroundDrawing(false);
        populate(container);
        setActiveContainer(container);
    }

    private void populate(Container container) {
        String[] query = {playerId};
        TextInputWidget input = new TextInputWidget.Builder().size(Math.max(220, width - 44), 22).placeholder("Player UUID").search(true).text(playerId).maxLength(36).onChange(value -> query[0] = value == null ? "" : value.trim()).build();
        container.addWidget(input);
        container.addWidget(new IconButton.Builder().size(Math.max(220, width - 44), 24).label("Load Snapshots").hint("Inspect Pinned And Recent Player State").imagePath("history.png").accentType(ThemeManager.getAccent("nice")).onClick(() -> load(query[0])).build());
        if (playerId.isBlank()) {
            container.addWidget(summary("Enter A Player UUID", "Snapshot History Is Private To This Network", "calm"));
            return;
        }
        container.addWidget(summary(snapshots.size() + " Snapshots • " + snapshots.stream().filter(NetworkSnapshotMetadata::pinned).count() + " Pinned", playerId, snapshots.isEmpty() ? "warning" : "nice"));
        if (snapshots.isEmpty()) {
            container.addWidget(summary("No Snapshots", "The Player May Not Have Joined This Realm Yet", "warning"));
            return;
        }
        for (NetworkSnapshotMetadata snapshot : snapshots) {
            String label = TIME.format(Instant.ofEpochMilli(snapshot.createdAt())) + " • " + titleCase(snapshot.family().substring(snapshot.family().indexOf('/') + 1));
            String hint = snapshot.originNodeId() + " • Fence " + snapshot.fenceEpoch() + " • " + formatBytes(snapshot.payloadBytes()) + " • " + (snapshot.pinned() ? "Pinned" : "Retained");
            container.addWidget(new IconButton.Builder().size(Math.max(220, width - 44), 30).label(label).hint(hint).imagePath(snapshot.pinned() ? "favorite.png" : "history.png").accentType(ThemeManager.getAccent(snapshot.pinned() ? "love" : "calm")).onClick(() -> inspect(snapshot)).build());
        }
        if (hasMore) {
            container.addWidget(new IconButton.Builder().size(Math.max(220, width - 44), 24).label("Load More").hint("Older Snapshot History").imagePath("add.png").accentType(ThemeManager.getAccent("calm")).onClick(() -> loadPage(playerId, snapshots.size(), snapshots)).build());
        }
    }

    private void load(String rawPlayerId) {
        if (loading) {
            return;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(rawPlayerId == null ? "" : rawPlayerId.trim());
        } catch (IllegalArgumentException exception) {
            new Notification("Invalid Player UUID", Notification.Type.ERROR);
            return;
        }
        loadPage(parsed.toString(), 0, List.of());
    }

    private void loadPage(String rawPlayerId, int offset, List<NetworkSnapshotMetadata> existing) {
        if (loading) {
            return;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(rawPlayerId == null ? "" : rawPlayerId.trim());
        } catch (IllegalArgumentException exception) {
            new Notification("Invalid Player UUID", Notification.Type.ERROR);
            return;
        }
        loading = true;
        Notification notification = new Notification.Builder().message("Loading Snapshots").description(parsed.toString()).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        DesktopNetworkAccess.manager(remotelyClient).listRuntimeSnapshots(networkId, parsed, offset, 100).whenComplete((loaded, throwable) -> ScreenManager.getInstance().execute(() -> {
            loading = false;
            if (throwable != null) {
                notification.update().message("Snapshot Load Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            List<NetworkSnapshotMetadata> combined = new ArrayList<>(existing);
            loaded.stream().filter(snapshot -> combined.stream().noneMatch(current -> current.snapshotId().equals(snapshot.snapshotId()))).forEach(combined::add);
            notification.update().message("Snapshots Loaded").description(combined.size() + " Results").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            client.setScreen(new NetworkSnapshotScreen(parent, remotelyClient, networkId, parsed.toString(), combined, loaded.size() == 100));
        }));
    }

    private void reload() {
        if (!playerId.isBlank()) {
            load(playerId);
        }
    }

    private void inspect(NetworkSnapshotMetadata snapshot) {
        List<NetworkMember> targets = restoreTargets(snapshot);
        NetworkMember[] target = {targets.isEmpty() ? null : targets.getFirst()};
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Snapshot Details").size(430, 290).setResizable(true).setExpandWithDropdowns(true).onClose(() -> popup[0].hide());
        builder.addRow(new PopupWidget.PopupRow.Builder("Created", detail(TIME.format(Instant.ofEpochMilli(snapshot.createdAt())))).id("created").contentWidth().build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Family", detail(snapshot.family())).id("family").contentWidth().build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Origin", detail(snapshot.originNodeId() + " • Fence " + snapshot.fenceEpoch())).id("origin").contentWidth().build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Versions", detail("Schema " + snapshot.schemaVersion() + " • Data " + snapshot.dataVersion())).id("versions").contentWidth().build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Integrity", detail(formatBytes(snapshot.payloadBytes()) + " • " + snapshot.payloadHash().substring(0, Math.min(16, snapshot.payloadHash().length())))).id("integrity").contentWidth().build());
        if (!targets.isEmpty()) {
            builder.addDropdown("Restore Target", targets, target[0], NetworkMember::routeName, value -> target[0] = value);
        }
        builder.addTitleAction(snapshot.pinned() ? "Unpin" : "Pin", () -> {
            popup[0].hide();
            pin(snapshot);
        }, PopupWidget.TitleActionRole.SECONDARY);
        builder.addTitleAction("Restore", () -> {
            if (target[0] == null) {
                new Notification("No Restore Target", "Add A Compatible ReSync Realm Server", Notification.Type.ERROR);
                return;
            }
            popup[0].hide();
            restore(snapshot, target[0]);
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        popup[0] = builder.build();
        popup[0].setX((width - popup[0].getWidth()) / 2);
        popup[0].setY((height - popup[0].getHeight()) / 2);
        addDrawableChild(popup[0]);
        popup[0].show();
    }

    private void pin(NetworkSnapshotMetadata snapshot) {
        Notification notification = operation("Updating Snapshot", snapshot.snapshotId());
        DesktopNetworkAccess.manager(remotelyClient).pinRuntimeSnapshot(networkId, snapshot.snapshotId(), !snapshot.pinned()).whenComplete((updated, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                notification.update().message("Snapshot Update Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            List<NetworkSnapshotMetadata> next = new ArrayList<>(snapshots);
            next.replaceAll(value -> value.snapshotId().equals(updated.snapshotId()) ? updated : value);
            notification.update().message(updated.pinned() ? "Snapshot Pinned" : "Snapshot Unpinned").description(TIME.format(Instant.ofEpochMilli(updated.createdAt()))).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            client.setScreen(new NetworkSnapshotScreen(parent, remotelyClient, networkId, playerId, next, hasMore));
        }));
    }

    private void restore(NetworkSnapshotMetadata snapshot, NetworkMember target) {
        Notification notification = operation("Preparing Restore", target.routeName());
        DesktopNetworkAccess.manager(remotelyClient).restoreRuntimeSnapshot(networkId, snapshot.snapshotId(), target.nodeId()).whenComplete((transfer, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                notification.update().message("Restore Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            notification.update().message("Restore Ready").description(target.routeName() + " • Player Can Join For 10 Minutes").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
        }));
    }

    private List<NetworkMember> restoreTargets(NetworkSnapshotMetadata snapshot) {
        String realmId = snapshot.family().contains("/") ? snapshot.family().substring(0, snapshot.family().indexOf('/')) : snapshot.family();
        Set<String> nodes = network.syncRealms().stream().filter(realm -> realm.id().equals(realmId)).map(SyncRealm::nodeIds).findFirst().orElse(Set.of());
        return network.members().stream().filter(member -> nodes.contains(member.nodeId()) && member.isManaged() && member.resyncEnabled() && !member.isProxy()).toList();
    }

    private AnimatedButton detail(String value) {
        return new AnimatedButton.Builder().size(250, 20).label(value).accentType(ThemeManager.getDefaultAccent()).build();
    }

    private AnimatedButton summary(String label, String hint, String accent) {
        return new AnimatedButton.Builder().size(Math.max(220, width - 44), 22).label(label).hint(hint).accentType(ThemeManager.getAccent(accent)).build();
    }

    private Notification operation(String message, String description) {
        return new Notification.Builder().message(message).description(description).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
    }

    private String formatBytes(int bytes) {
        if (bytes >= 1_048_576) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576d);
        }
        if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024d);
        }
        return bytes + " B";
    }

    private String titleCase(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (char character : normalized.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "Network Snapshot Failed" : message;
    }
}
