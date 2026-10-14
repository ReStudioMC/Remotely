package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.ui.widgets.LifecycleButtonWidget;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.StartEntry;
import restudio.rescreen.ui.desktop.StartMenuWidget;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class ServerStartActions implements AutoCloseable {
    public record Source(String id, String label, Identifier icon, Object stamp, Object target) {
        public Source {
            id = Objects.requireNonNull(id);
            label = Objects.requireNonNull(label);
            target = Objects.requireNonNull(target);
        }
    }

    private final class Binding {
        private final Source source;
        private final ServerUiCapabilityProvider capabilities;
        private final Consumer<ServerScreenHost.ServerState> stateListener;
        private final Runnable capabilityListener;
        private ServerScreenHost.ServerState state;
        private ServerUiCapabilityProvider.Availability availability;
        private Map<LifecycleButtonWidget.Operation, StartEntry> entries = Map.of();

        private Binding(Source source) {
            this.source = source;
            capabilities = host.capabilities(host.serverView(source.target()));
            stateListener = ignored -> publish.accept(() -> changed(this));
            capabilityListener = () -> publish.accept(() -> changed(this));
            admit();
            host.addStateListener(source.target(), stateListener);
            capabilities.addCapabilityListener(capabilityListener);
        }

        private boolean admit() {
            ServerScreenHost.ServerState nextState = host.state(source.target());
            if (nextState == ServerScreenHost.ServerState.UNKNOWN) nextState = host.detailsTarget(source.target()).state();
            ServerUiCapabilityProvider.Availability nextAvailability = host.lifecycleAvailability(source.target());
            if (nextState == state && Objects.equals(nextAvailability, availability)) return false;
            state = nextState;
            availability = nextAvailability;
            Map<LifecycleButtonWidget.Operation, StartEntry> next = new LinkedHashMap<>();
            for (LifecycleButtonWidget.Operation operation : OPERATIONS) {
                boolean enabled = supports(operation, state) && availability.available();
                String label = label(operation);
                String detail = source.label() + " • " + stateLabel(state);
                if (!availability.available()) detail += " • " + availability.reason();
                Runnable action = () -> run(this, operation);
                StartEntry.Action control = new StartEntry.Action(operation.name().toLowerCase(Locale.ROOT), label,
                        icon(operation), action, enabled, false);
                next.put(operation, new StartEntry("server-action:" + source.id() + ":" + operation.name(), label + " " + source.label(),
                        detail, StartEntry.Category.ACTIONS, source.icon(), action, List.of(label, source.label(), "server power lifecycle"),
                        source.id(), source.label(), icon(operation), List.of(control)));
            }
            entries = Map.copyOf(next);
            return true;
        }

        private void close() {
            host.removeStateListener(source.target(), stateListener);
            capabilities.removeCapabilityListener(capabilityListener);
        }
    }

    private static final List<LifecycleButtonWidget.Operation> OPERATIONS = List.of(LifecycleButtonWidget.Operation.START,
            LifecycleButtonWidget.Operation.STOP, LifecycleButtonWidget.Operation.RESTART);
    private final ReScreen parent;
    private final RemotelyClient client;
    private final ServerScreenHost host;
    private final Consumer<Runnable> publish;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private Consumer<List<StartEntry>> entriesListener = ignored -> {};
    private StartMenuWidget.Search search;
    private List<StartEntry> entries = List.of();
    private ServerDetailsScreen backgroundOwner;
    private boolean closed;

    public ServerStartActions(ReScreen parent, RemotelyClient client, Consumer<Runnable> publish) {
        this.parent = Objects.requireNonNull(parent);
        this.client = Objects.requireNonNull(client);
        this.host = client.getHost().serverScreenHost(client);
        this.publish = Objects.requireNonNull(publish);
    }

    public void onEntries(Consumer<List<StartEntry>> listener) {
        entriesListener = listener == null ? ignored -> {} : listener;
        entriesListener.accept(entries);
    }

    public void setSources(List<Source> sources) {
        if (closed) return;
        Map<String, Source> next = new LinkedHashMap<>();
        if (sources != null) sources.forEach(source -> next.putIfAbsent(source.id(), source));
        for (Binding binding : List.copyOf(bindings.values())) {
            Source source = next.get(binding.source.id());
            if (Objects.equals(source, binding.source)) continue;
            bindings.remove(binding.source.id());
            binding.close();
        }
        for (Source source : next.values()) bindings.computeIfAbsent(source.id(), ignored -> new Binding(source));
        if (backgroundOwner != null) backgroundOwner.retainLifecycleTargets(next.values().stream().map(Source::target).toList());
        rebuild();
    }

    public void search(StartMenuWidget.Search search) {
        if (closed || Objects.equals(this.search, search)) return;
        this.search = search;
        rebuild();
    }

    private void changed(Binding binding) {
        if (closed || bindings.get(binding.source.id()) != binding) return;
        if (binding.admit()) rebuild();
    }

    private void rebuild() {
        List<StartEntry> next = new ArrayList<>();
        if (search != null && search.active() && (search.category() == null || search.category() == StartEntry.Category.ACTIONS)) {
            LifecycleButtonWidget.Operation operation = operation(search.query());
            if (operation != null) {
                List<Binding> selected = bindings.values().stream()
                        .filter(binding -> search.scopeIds().isEmpty() || search.scopeIds().contains(binding.source.id())).toList();
                if (search.scopeIds().size() > 1 && selected.size() > 1) {
                    List<Binding> captured = List.copyOf(selected);
                    String label = label(operation);
                    long ready = selected.stream().filter(binding -> supports(operation, binding.state) && binding.availability.available()).count();
                    Runnable action = () -> captured.forEach(binding -> run(binding, operation));
                    String names = String.join(", ", selected.stream().map(binding -> binding.source.label()).toList());
                    String detail = names + " • " + ready + " Ready Of " + selected.size();
                    next.add(new StartEntry("server-action:scoped:" + operation.name() + ":" + String.join(",", search.scopeIds()),
                            label + " " + selected.size() + " Servers", detail, StartEntry.Category.ACTIONS, icon(operation), action,
                            List.of(label, names, "servers power lifecycle"), selected.getFirst().source.id(), names, null,
                            List.of(new StartEntry.Action(operation.name().toLowerCase(Locale.ROOT), label, icon(operation), action, ready > 0, false))));
                }
                selected.forEach(binding -> next.add(binding.entries.get(operation)));
            }
        }
        List<StartEntry> result = List.copyOf(next);
        if (result.equals(entries)) return;
        entries = result;
        entriesListener.accept(entries);
    }

    private void run(Binding binding, LifecycleButtonWidget.Operation operation) {
        if (closed || bindings.get(binding.source.id()) != binding) return;
        binding.admit();
        if (!supports(operation, binding.state) || !binding.availability.available()) return;
        ServerDetailsScreen owner = owner(binding.source.target());
        owner.requestLifecycle(binding.source.target(), operation, parent, () -> !closed && bindings.get(binding.source.id()) == binding);
    }

    private ServerDetailsScreen owner(Object target) {
        if (backgroundOwner != null && backgroundOwner.lifecyclePending(target)) return backgroundOwner;
        ScreenManager screens = ScreenManager.getInstance();
        if (screens.getCurrentScreen() instanceof ServerDetailsScreen details && details.servesLifecycle(client)) return details;
        var desktop = screens.getDesktopWindowsOverlay();
        if (desktop != null) {
            for (var window : desktop.getWindows()) {
                if (!window.isClosing() && window.getScreen() instanceof ServerDetailsScreen details && details.servesLifecycle(client)) return details;
            }
        }
        if (backgroundOwner == null) backgroundOwner = ServerDetailsScreen.lifecycleOwner(parent, client);
        return backgroundOwner;
    }

    private static LifecycleButtonWidget.Operation operation(String query) {
        if (query == null) return null;
        String value = query.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        String word = value.split("\\s+", 2)[0];
        if (word.length() < 2) return null;
        if ("restart".startsWith(word) || "reboot".startsWith(word)) return LifecycleButtonWidget.Operation.RESTART;
        if ("start".startsWith(word) || "launch".startsWith(word)) return LifecycleButtonWidget.Operation.START;
        if ("stop".startsWith(word) || "shutdown".startsWith(word)) return LifecycleButtonWidget.Operation.STOP;
        return null;
    }

    private static boolean supports(LifecycleButtonWidget.Operation operation, ServerScreenHost.ServerState state) {
        if (state == null) return false;
        try {
            return LifecycleButtonWidget.supports(operation, InstanceState.valueOf(state.name()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String label(LifecycleButtonWidget.Operation operation) {
        return switch (operation) {
            case START -> "Start";
            case STOP -> "Stop";
            case RESTART -> "Restart";
            case KILL -> "Kill";
        };
    }

    private static Identifier icon(LifecycleButtonWidget.Operation operation) {
        return Identifier.icon(switch (operation) {
            case START -> "start.png";
            case STOP, KILL -> "stop.png";
            case RESTART -> "reload.png";
        });
    }

    private static String stateLabel(ServerScreenHost.ServerState state) {
        if (state == null) return "Unavailable";
        String value = state.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        bindings.values().forEach(Binding::close);
        bindings.clear();
        if (backgroundOwner != null) backgroundOwner.closeLifecycleOwner();
        backgroundOwner = null;
        entries = List.of();
    }
}
