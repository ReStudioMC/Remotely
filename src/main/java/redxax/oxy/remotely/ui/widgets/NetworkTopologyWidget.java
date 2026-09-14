package redxax.oxy.remotely.ui.widgets;

import restudio.rebase.ui.widgets.LifecycleButtonWidget;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkMemberObservation;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkRuntimeNodePresence;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.network.NetworkTopologyHeat;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class NetworkTopologyWidget extends AnimatedWidget {
    private static final int NODE_HEIGHT = 30;
    private static final int NODE_GAP = 8;
    private static final int ROW_GAP = 26;
    private static final int SELECTOR_HEIGHT = 18;
    private static final int PADDING = 6;
    private static final int TREE_MARGIN = 24;
    private static final int MIN_NODE_WIDTH = 140;
    private NetworkDefinition network;
    private final Map<String, NetworkMemberObservation> observations = new LinkedHashMap<>();
    private final List<NodeEntry> nodes = new ArrayList<>();
    private final Supplier<NetworkRuntimeSnapshot> runtimeSnapshot;
    private final Supplier<Map<String, Integer>> transferFailureHeat;
    private final Function<NetworkMember, String> memberName;
    private final Function<NetworkMember, ?> memberState;
    private final Function<NetworkMember, Identifier> memberIcon;
    private final Consumer<NetworkMember> onPower;
    private final BiConsumer<NetworkMember, NetworkLifecycleOperation> onLifecycle;
    private final Consumer<NetworkMember> onMemberSelected;
    private final ReScreen screen;
    private final ScrollSelectorWidget viewSelector;
    private NetworkTopologyHeat selectedHeat;
    private boolean layoutDirty = true;
    private boolean childrenActive = true;

    public NetworkTopologyWidget(int x, int y, int width, int height, NetworkDefinition network, List<NetworkMemberObservation> observations, Supplier<NetworkRuntimeSnapshot> runtimeSnapshot, Consumer<NetworkMember> onMemberSelected) {
        this(null, x, y, width, height, network, observations, runtimeSnapshot, () -> NetworkTopologyHeat.STATUS,
                () -> Map.of(), NetworkMember::routeName, null, null, null, null, onMemberSelected);
    }

    public NetworkTopologyWidget(int x, int y, int width, int height, NetworkDefinition network, List<NetworkMemberObservation> observations, Supplier<NetworkRuntimeSnapshot> runtimeSnapshot, Supplier<NetworkTopologyHeat> heatMode, Supplier<Map<String, Integer>> transferFailureHeat, Consumer<NetworkMember> onMemberSelected) {
        this(null, x, y, width, height, network, observations, runtimeSnapshot, heatMode, transferFailureHeat,
                NetworkMember::routeName, null, null, null, null, onMemberSelected);
    }

    public NetworkTopologyWidget(int x, int y, int width, int height, NetworkDefinition network, List<NetworkMemberObservation> observations, Supplier<NetworkRuntimeSnapshot> runtimeSnapshot, Supplier<NetworkTopologyHeat> heatMode, Supplier<Map<String, Integer>> transferFailureHeat, Function<NetworkMember, String> memberName, Consumer<NetworkMember> onMemberSelected) {
        this(null, x, y, width, height, network, observations, runtimeSnapshot, heatMode, transferFailureHeat,
                memberName, null, null, null, null, onMemberSelected);
    }

    public NetworkTopologyWidget(ReScreen screen, int x, int y, int width, int height, NetworkDefinition network,
                                 List<NetworkMemberObservation> observations, Supplier<NetworkRuntimeSnapshot> runtimeSnapshot,
                                 Supplier<NetworkTopologyHeat> heatMode, Supplier<Map<String, Integer>> transferFailureHeat,
                                 Function<NetworkMember, String> memberName, Function<NetworkMember, ?> memberState,
                                 Function<NetworkMember, Identifier> memberIcon, Consumer<NetworkMember> onPower,
                                 BiConsumer<NetworkMember, NetworkLifecycleOperation> onLifecycle,
                                 Consumer<NetworkMember> onMemberSelected) {
        super(x, y, width, height, "");
        this.screen = screen;
        this.network = network;
        if (observations != null) {
            observations.forEach(observation -> this.observations.put(observation.nodeId(), observation));
        }
        this.runtimeSnapshot = runtimeSnapshot;
        this.transferFailureHeat = transferFailureHeat;
        this.memberName = memberName == null ? NetworkMember::routeName : memberName;
        this.memberState = memberState;
        this.memberIcon = memberIcon;
        this.onPower = onPower;
        this.onLifecycle = onLifecycle;
        this.onMemberSelected = onMemberSelected;
        NetworkTopologyHeat initialHeat = heatMode == null ? null : heatMode.get();
        selectedHeat = initialHeat == null ? NetworkTopologyHeat.STATUS : initialHeat;
        viewSelector = new ScrollSelectorWidget.Builder()
            .options(List.of("Health", "Players", "Tick Speed", "Memory", "Failed Transfers"))
            .selectedIndex(selectedHeat.ordinal())
            .animationKey("network-topology-view")
            .hint("Choose what each server row shows. Scroll over this control or click either side to change the view.")
            .onChange(index -> selectedHeat = NetworkTopologyHeat.values()[index])
            .size(88, SELECTOR_HEIGHT)
            .build();
        syncNodes();
        entranceAnimationEnabled = false;
        animateLayout = false;
        active = false;
        setHeight(preferredHeight(width, network.members().size()));
    }

    private NodeEntry node(NetworkMember member) {
        NodeEntry[] entry = new NodeEntry[1];
        LifecycleButtonWidget power = memberState == null || memberState.apply(member) == null || onPower == null ? null
                : new LifecycleButtonWidget(screen, () -> onPower.accept(entry[0].member), "Server")
                .lifecycleAction(LifecycleButtonWidget.Operation.START,
                        () -> onLifecycle.accept(entry[0].member, NetworkLifecycleOperation.START))
                .lifecycleAction(LifecycleButtonWidget.Operation.STOP,
                        () -> onLifecycle.accept(entry[0].member, NetworkLifecycleOperation.STOP));
        MountableButtonWidget.Builder builder = new MountableButtonWidget.Builder(memberName.apply(member))
            .icon(memberIcon == null ? null : memberIcon.apply(member))
            .onClick(() -> {
                if (onMemberSelected != null) {
                    onMemberSelected.accept(entry[0].member);
                }
            });
        MountableButtonWidget button = builder.build();
        if (power != null) {
            power.setSize(18, 18);
            power.setAnimateLayoutPosition(false);
            button.addMountedWidget(power);
        }
        button.setSize(100, NODE_HEIGHT);
        button.entranceAnimationEnabled = false;
        button.setAnimateLayout(false);
        entry[0] = new NodeEntry(member, button, power);
        return entry[0];
    }

    public void applyNetwork(NetworkDefinition network, List<NetworkMemberObservation> observations) {
        this.network = network;
        this.observations.clear();
        if (observations != null) {
            observations.forEach(observation -> this.observations.put(observation.nodeId(), observation));
        }
        syncNodes();
        setHeight(preferredHeight(getWidth(), network.members().size()));
        layoutDirty = true;
    }

    public void setMemberIcon(String nodeId, Identifier icon) {
        nodes.stream().filter(node -> node.member.nodeId().equals(nodeId)).findFirst().ifPresent(node -> node.button.setIcon(icon));
    }

    private void syncNodes() {
        Map<String, NodeEntry> existing = nodes.stream().collect(Collectors.toMap(node -> node.member.nodeId(), Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<NetworkMember> members = new ArrayList<>();
        NetworkMember proxy = network.proxyMember();
        if (proxy != null) {
            members.add(proxy);
        }
        network.members().stream().filter(member -> !member.isProxy()).forEach(members::add);
        nodes.clear();
        for (NetworkMember member : members) {
            NodeEntry entry = existing.get(member.nodeId());
            if (entry == null) {
                entry = node(member);
            } else {
                entry.member = member;
                entry.button.setName(memberName.apply(member));
                if (memberIcon != null) {
                    entry.button.setIcon(memberIcon.apply(member));
                }
            }
            entry.button.setActive(childrenActive);
            nodes.add(entry);
        }
    }

    public static int preferredHeight(int width, int memberCount) {
        int servers = Math.max(0, memberCount - 1);
        int rows = servers == 0 ? 0 : (int) Math.ceil(servers / (double) columns(width));
        return PADDING * 2 + SELECTOR_HEIGHT + NODE_GAP + NODE_HEIGHT + (rows == 0 ? 0 : 28 + rows * NODE_HEIGHT + Math.max(0, rows - 1) * ROW_GAP);
    }

    @Override
    public void setWidth(int width) {
        boolean changed = width != getWidth();
        super.setWidth(width);
        setHeight(preferredHeight(width, network.members().size()));
        layoutDirty |= changed;
    }

    @Override
    public void setPosition(int x, int y) {
        boolean changed = x != getX() || y != getY();
        super.setPosition(x, y);
        layoutDirty |= changed;
    }

    private void layoutNodes() {
        if (!layoutDirty) {
            return;
        }
        layoutDirty = false;
        viewSelector.setPosition(getX() + PADDING, getY() + PADDING);
        viewSelector.setWidth(Math.clamp(getWidth() / 6, 65, 95));
        if (nodes.isEmpty()) {
            return;
        }
        int proxyY = getY() + PADDING + SELECTOR_HEIGHT + NODE_GAP;
        MountableButtonWidget proxy = nodes.getFirst().button;
        int proxyWidth = Math.max(MIN_NODE_WIDTH, getWidth() - TREE_MARGIN * 2);
        proxy.setPosition(getX() + (getWidth() - proxyWidth) / 2, proxyY);
        proxy.setSize(proxyWidth, NODE_HEIGHT);
        int backendCount = nodes.size() - 1;
        if (backendCount == 0) {
            return;
        }
        int columns = columns(getWidth());
        int contentWidth = Math.max(MIN_NODE_WIDTH, getWidth() - TREE_MARGIN * 2);
        int gridY = proxyY + NODE_HEIGHT + 28;
        int rows = (int) Math.ceil(backendCount / (double) columns);
        for (int row = 0; row < rows; row++) {
            int firstIndex = 1 + row * columns;
            int count = Math.min(columns, nodes.size() - firstIndex);
            int nodeWidth = Math.max(1, (contentWidth - Math.max(0, count - 1) * NODE_GAP) / count);
            int rowWidth = count * nodeWidth + Math.max(0, count - 1) * NODE_GAP;
            int rowX = getX() + (getWidth() - rowWidth) / 2;
            for (int column = 0; column < count; column++) {
                MountableButtonWidget server = nodes.get(firstIndex + column).button;
                server.setPosition(rowX + column * (nodeWidth + NODE_GAP), gridY + row * (NODE_HEIGHT + ROW_GAP));
                server.setSize(nodeWidth, NODE_HEIGHT);
            }
        }
    }

    @Override
    protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        layoutNodes();
        updateNodes();
        drawConnections(ctx);
        viewSelector.render(ctx, mouseX, mouseY, 0f);
        for (NodeEntry node : nodes) {
            node.button.render(ctx, mouseX, mouseY, 0f);
        }
    }

    private void drawConnections(IDrawContext ctx) {
        if (nodes.size() < 2) {
            return;
        }
        MountableButtonWidget proxy = nodes.getFirst().button;
        int color = ThemeManager.getColor(ThemeColor.innerBorder);
        int proxyCenter = proxy.getX() + proxy.getWidth() / 2;
        Map<Integer, List<MountableButtonWidget>> rows = new LinkedHashMap<>();
        for (int index = 1; index < nodes.size(); index++) {
            MountableButtonWidget button = nodes.get(index).button;
            rows.computeIfAbsent(button.getY(), ignored -> new ArrayList<>()).add(button);
        }
        int lastBusY = rows.values().stream().mapToInt(row -> row.getFirst().getY() - 8).max().orElse(proxy.getY() + proxy.getHeight());
        ctx.fill(proxyCenter, proxy.getY() + proxy.getHeight(), proxyCenter + 1, lastBusY + 1, color);
        for (List<MountableButtonWidget> row : rows.values()) {
            int busY = row.getFirst().getY() - 8;
            int left = row.getFirst().getX() + row.getFirst().getWidth() / 2;
            int right = row.getLast().getX() + row.getLast().getWidth() / 2;
            ctx.fill(left, busY, right + 1, busY + 1, color);
            for (MountableButtonWidget server : row) {
                int center = server.getX() + server.getWidth() / 2;
                ctx.fill(center, busY, center + 1, server.getY(), color);
            }
        }
    }

    private void updateNodes() {
        NetworkRuntimeSnapshot runtime = snapshot();
        for (NodeEntry node : nodes) {
            NetworkMember member = node.member;
            MountableButtonWidget button = node.button;
            Object state = memberState == null ? null : memberState.apply(member);
            NetworkRuntimeNodePresence presence = livePresence(runtime, member).orElse(null);
            button.setDescription(detail(member, state, presence));
            button.setHiddenText(member.isProxy() ? "Proxy" : member.isManaged() ? "Server" : "External Server");
            button.setAccent(accent(member, state, presence, runtime));
            if (node.power != null) {
                node.power.updateState(stateName(state));
            }
        }
    }

    private static int columns(int width) {
        return Math.clamp(Math.max(1, (width - TREE_MARGIN * 2 + NODE_GAP) / (MIN_NODE_WIDTH + NODE_GAP)), 1, 4);
    }

    private Accent accent(NetworkMember member, Object state, NetworkRuntimeNodePresence presence, NetworkRuntimeSnapshot runtime) {
        if (selectedHeat != NetworkTopologyHeat.STATUS) {
            if (!isRuntimeActive(member, state)) {
                return ThemeManager.getAccent("danger");
            }
            double ratio = heatRatio(member, presence, runtime, selectedHeat);
            return ratio >= 0.8 ? ThemeManager.getAccent("danger") : ThemeManager.getDefaultAccent();
        }
        if (isTransitioning(state)) {
            return ThemeManager.getDefaultAccent();
        }
        if (!isRuntimeActive(member, state)) {
            return ThemeManager.getAccent("danger");
        }
        NetworkRuntimeNodeStatus status = presence == null ? null : presence.status();
        if (status != null) {
            return switch (status) {
                case ONLINE -> ThemeManager.getDefaultAccent();
                case DRAINING, MAINTENANCE -> ThemeManager.getDefaultAccent();
                case OFFLINE, REVOKED -> ThemeManager.getAccent("danger");
            };
        }
        NetworkMemberObservation observation = observations.get(member.nodeId());
        if (observation == null) {
            return ThemeManager.getDefaultAccent();
        }
        return switch (observation.state()) {
            case HEALTHY -> ThemeManager.getDefaultAccent();
            case UNKNOWN, DRIFTED -> ThemeManager.getDefaultAccent();
            case DEGRADED, INSECURE, UNREACHABLE -> ThemeManager.getAccent("danger");
        };
    }

    private double heatRatio(NetworkMember member, NetworkRuntimeNodePresence presence, NetworkRuntimeSnapshot runtime, NetworkTopologyHeat mode) {
        return switch (mode) {
            case STATUS -> 0;
            case PLAYERS -> {
                if (presence == null) {
                    yield 0;
                }
                int maximum = presence.capacity() > 0 ? presence.capacity() : runtime == null ? 1 : runtime.nodes().values().stream().mapToInt(NetworkRuntimeNodePresence::players).max().orElse(1);
                yield maximum < 1 ? 0 : Math.clamp((double) presence.players() / maximum, 0, 1);
            }
            case MSPT -> presence == null || presence.mspt() < 0 ? 0 : Math.clamp((presence.mspt() - 20) / 60, 0, 1);
            case MEMORY -> presence == null || presence.heapMaximum() < 1 ? 0 : Math.clamp((double) presence.heapUsed() / presence.heapMaximum(), 0, 1);
            case TRANSFER_FAILURES -> Math.clamp(transferFailures(member) / 5.0, 0, 1);
        };
    }

    private String detail(NetworkMember member, Object state, NetworkRuntimeNodePresence presence) {
        String lifecycle = lifecycleLabel(member, state);
        if (!lifecycle.isBlank()) {
            return lifecycle;
        }
        if (selectedHeat == NetworkTopologyHeat.TRANSFER_FAILURES) {
            int failures = transferFailures(member);
            return failures + (failures == 1 ? " Failed Transfer" : " Failed Transfers") + " In 24 Hours";
        }
        if (presence == null) {
            return member.address() + ":" + member.port();
        }
        return switch (selectedHeat) {
            case STATUS -> presenceDetail(presence);
            case PLAYERS -> presence.capacity() > 0 ? presence.players() + "/" + presence.capacity() + " Players" : presence.players() + " Players";
            case MSPT -> presence.mspt() < 0 ? "Tick Time Unavailable" : String.format(Locale.ROOT, "%.1f ms Per Tick", presence.mspt());
            case MEMORY -> presence.heapMaximum() < 1 ? "Memory Unavailable" : Math.round((double) presence.heapUsed() / presence.heapMaximum() * 100) + "% Memory Used";
            case TRANSFER_FAILURES -> throw new IllegalStateException("Transfer Failure View Was Already Resolved");
        };
    }

    private String presenceDetail(NetworkRuntimeNodePresence presence) {
        if (presence.status() != NetworkRuntimeNodeStatus.ONLINE) {
            return titleCase(presence.status().name());
        }
        if (presence.capacity() < 1 && presence.tps() < 0) {
            return "Online";
        }
        String players = presence.capacity() > 0 ? presence.players() + "/" + presence.capacity() + " Players" : presence.players() + " Players";
        return presence.tps() < 0 ? players : players + " • " + String.format(Locale.ROOT, "%.1f TPS", presence.tps());
    }

    private static boolean isTransitioning(Object state) {
        return !transitionLabel(state).isBlank();
    }

    private boolean isRuntimeActive(NetworkMember member, Object state) {
        String value = stateName(state);
        return memberState == null || !member.isManaged() || value.equals("RUNNING") || value.equals("SAVING") || value.equals("SAVED");
    }

    private String lifecycleLabel(NetworkMember member, Object state) {
        if (isRuntimeActive(member, state)) {
            return "";
        }
        if (stateName(state).isBlank()) {
            return "Unavailable";
        }
        return switch (stateName(state)) {
            case "STOPPED" -> "Stopped";
            case "STOPPING" -> "Stopping";
            case "INSTALLING" -> "Installing";
            case "STARTING" -> "Starting";
            case "CRASHED" -> "Crashed";
            default -> "";
        };
    }

    private static String transitionLabel(Object state) {
        return switch (stateName(state)) {
            case "STARTING" -> "Starting";
            case "STOPPING" -> "Stopping";
            case "INSTALLING" -> "Installing";
            case "SAVING" -> "Saving";
            default -> "";
        };
    }

    private static String stateName(Object state) {
        if (state == null) {
            return "";
        }
        return state instanceof Enum<?> value ? value.name().toUpperCase(Locale.ROOT) : String.valueOf(state).trim().toUpperCase(Locale.ROOT);
    }

    private Optional<NetworkRuntimeNodePresence> livePresence(NetworkRuntimeSnapshot runtime, NetworkMember member) {
        return runtime == null ? Optional.empty() : runtime.node(member.nodeId());
    }

    private NetworkRuntimeSnapshot snapshot() {
        return runtimeSnapshot == null ? null : runtimeSnapshot.get();
    }

    private int transferFailures(NetworkMember member) {
        Map<String, Integer> heat = transferFailureHeat == null ? Map.of() : transferFailureHeat.get();
        return heat == null ? 0 : heat.getOrDefault(member.nodeId(), 0);
    }

    private String titleCase(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (char character : normalized.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (!isVisible() || !childrenActive || event.button() != ReMouseButton.LEFT) {
            return super.mouseClicked(event);
        }
        if (viewSelector.mouseClicked(event)) {
            return true;
        }
        for (int index = nodes.size() - 1; index >= 0; index--) {
            if (nodes.get(index).button.mouseClicked(event)) {
                return true;
            }
        }
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        return childrenActive && viewSelector.mouseScrolled(event) || super.mouseScrolled(event);
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void renderHintOverlay(IDrawContext context) {
        super.renderHintOverlay(context);
        viewSelector.renderHintOverlay(context);
        nodes.forEach(node -> node.button.renderHintOverlay(context));
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        viewSelector.setVisible(visible);
        nodes.forEach(node -> node.button.setVisible(visible));
    }

    @Override
    public void setActive(boolean active) {
        super.setActive(false);
        childrenActive = active;
        viewSelector.setActive(active);
        nodes.forEach(node -> node.button.setActive(active));
    }

    private static final class NodeEntry {
        private NetworkMember member;
        private final MountableButtonWidget button;
        private final LifecycleButtonWidget power;

        private NodeEntry(NetworkMember member, MountableButtonWidget button, LifecycleButtonWidget power) {
            this.member = member;
            this.button = button;
            this.power = power;
        }
    }
}
