package redxax.oxy.remotely.ui.collaboration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.ui.collaboration.CollaborativeWidget;
import restudio.rescreen.ui.collaboration.ScreenCollaborationSurface;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class DesignerCollaborationAuthority {
    private static final Gson GSON = new Gson();
    private static final Map<Screen, Set<AnimatedWidget>> ACCENTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Screen, Set<Widget>> STATEFUL = Collections.synchronizedMap(new WeakHashMap<>());

    public record RemoteFocus(JsonArray path, int color) {
    }

    public record RemoteWidgetState(JsonObject states, long updatedAt) {
    }

    public record Pointer(int x, int y) {
    }

    public static JsonArray path(Screen screen, Widget target) {
        return encodePath(ScreenCollaborationSurface.path(screen, target));
    }

    public static JsonArray cachedPath(Screen screen, Widget target) {
        return encodePath(screen != null ? screen.collaborationPath(target) : ScreenCollaborationSurface.emptyPath());
    }

    public static Widget resolve(Screen screen, JsonElement pathElement) {
        if (screen == null || pathElement == null || !pathElement.isJsonArray()) {
            return null;
        }
        return ScreenCollaborationSurface.resolve(screen, decodePath(pathElement.getAsJsonArray()));
    }

    public static JsonObject widgetStates(Screen screen) {
        JsonObject states = new JsonObject();
        if (screen == null) {
            return states;
        }
        Set<Widget> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Widget root : screen.getScreenWidgetRoots()) {
            collectWidgetStates(screen, root, states, visited);
        }
        for (Widget container : screen.collaborationContainers().values()) {
            collectWidgetStates(screen, container, states, visited);
        }
        return states;
    }

    public static void applyWidgetStates(Screen screen, Collection<RemoteWidgetState> remoteStates) {
        if (screen == null) {
            return;
        }
        Map<String, TimedState> latest = new LinkedHashMap<>();
        if (remoteStates != null) {
            for (RemoteWidgetState remote : remoteStates) {
                if (remote == null || remote.states() == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : remote.states().entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject encoded = entry.getValue().getAsJsonObject();
                    JsonArray path = encoded.has("path") && encoded.get("path").isJsonArray() ? encoded.getAsJsonArray("path") : null;
                    JsonObject state = encoded.has("state") && encoded.get("state").isJsonObject() ? encoded.getAsJsonObject("state") : encoded;
                    String identity = path != null ? path.toString() : "key:" + entry.getKey();
                    TimedState existing = latest.get(identity);
                    if (existing == null || remote.updatedAt() >= existing.updatedAt()) {
                        latest.put(identity, new TimedState(path, entry.getKey(), state, remote.updatedAt()));
                    }
                }
            }
        }
        Widget localFocus = focused(screen);
        Set<Widget> active = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Widget, TimedState> targets = new IdentityHashMap<>();
        for (TimedState state : latest.values()) {
            Widget target = state.path() != null ? resolve(screen, state.path()) : findByKey(screen, state.key());
            if (target == null) {
                continue;
            }
            TimedState existing = targets.get(target);
            if (existing == null || state.updatedAt() >= existing.updatedAt()) {
                targets.put(target, state);
            }
        }
        for (Widget previous : STATEFUL.getOrDefault(screen, Set.of())) {
            if (!targets.containsKey(previous) && !sharesFocus(previous, localFocus)) {
                resetWidgetState(previous);
            }
        }
        for (Map.Entry<Widget, TimedState> entry : targets.entrySet()) {
            Widget target = entry.getKey();
            if (sharesFocus(target, localFocus)) {
                resetWidgetState(target);
                continue;
            }
            JsonObject state = entry.getValue().state();
            CollaborativeWidget.State decoded = decodeState(state);
            if (target instanceof CollaborativeWidget collaborative && decoded != null) {
                collaborative.applyCollaborationState(decoded);
                active.add(target);
            }
        }
        if (active.isEmpty()) {
            STATEFUL.remove(screen);
        } else {
            STATEFUL.put(screen, active);
        }
    }

    public static void clearWidgetStates(Screen screen) {
        if (screen == null) {
            return;
        }
        Set<Widget> previous = STATEFUL.remove(screen);
        if (previous != null) {
            previous.forEach(DesignerCollaborationAuthority::resetWidgetState);
        }
    }

    public static Widget hit(Screen screen, int mouseX, int mouseY, Widget excluded) {
        return screen != null ? screen.collaborationPointer(mouseX, mouseY, excluded).target() : null;
    }

    public static Widget cachedFocus(Screen screen) {
        return screen != null ? screen.collaborationFocusedWidget() : null;
    }

    public static JsonObject pointer(Screen screen, int mouseX, int mouseY, Widget excluded) {
        JsonObject state = new JsonObject();
        state.addProperty("screenX", screen != null && screen.width > 0 ? Math.clamp((double) mouseX / screen.width, 0.0, 1.0) : 0.0);
        state.addProperty("screenY", screen != null && screen.height > 0 ? Math.clamp((double) mouseY / screen.height, 0.0, 1.0) : 0.0);
        ScreenCollaborationSurface.PointerSnapshot pointer = screen != null
            ? screen.collaborationPointer(mouseX, mouseY, excluded)
            : ScreenCollaborationSurface.PointerSnapshot.at(mouseX, mouseY);
        Widget target = pointer.target();
        Container pointerContainer = pointer.container();
        JsonArray path = encodePath(pointer.targetPath());
        if (target != null && !path.isEmpty()) {
            state.add("path", path);
        }
        if (pointerContainer != null) {
            JsonArray containerPath = encodePath(pointer.containerPath());
            if (containerPath.isEmpty()) {
                return state;
            }
            state.add("containerPath", containerPath);
            Container.CollaborationPoint point = pointerContainer.collaborationPoint(mouseX, mouseY);
            state.addProperty("content", true);
            state.addProperty("viewport", point.viewport());
            state.addProperty("x", point.x());
            state.addProperty("y", point.y());
            return state;
        }
        if (target == null || path.isEmpty()) {
            return state;
        }
        if (target instanceof DropDownWidget<?> dropdown && dropdown.isExpanded() && mouseY > target.getY() + target.getHeight()) {
            state.addProperty("extended", true);
            state.addProperty("x", Math.clamp((double) (mouseX - target.getX()) / Math.max(1, target.getWidth()), 0.0, 1.0));
            state.addProperty("y", mouseY - target.getY());
        } else {
            state.addProperty("x", Math.clamp((double) (mouseX - target.getX()) / Math.max(1, target.getWidth()), 0.0, 1.0));
            state.addProperty("y", Math.clamp((double) (mouseY - target.getY()) / Math.max(1, target.getHeight()), 0.0, 1.0));
        }
        return state;
    }

    public static Pointer resolvePointer(Screen screen, JsonObject state) {
        if (screen == null || state == null) {
            return null;
        }
        if (state.has("path") || state.has("containerPath")) {
            Widget target = state.has("path") ? resolve(screen, state.get("path")) : null;
            double x = number(state.get("x"), 0.5);
            double y = number(state.get("y"), 0.5);
            int pointerX;
            int pointerY;
            Widget containerTarget = state.has("containerPath") ? resolve(screen, state.get("containerPath")) : target;
            if (containerTarget instanceof Container container && booleanValue(state.get("content"))) {
                Container.CollaborationPosition position = container.collaborationPosition(
                    new Container.CollaborationPoint(x, y, booleanValue(state.get("viewport"))));
                if (!position.visible()) {
                    return null;
                }
                return new Pointer(position.x(), position.y());
            }
            if (target == null || !target.isVisible()) {
                return null;
            }
            if (booleanValue(state.get("extended"))) {
                pointerX = target.getX() + (int) Math.round(Math.clamp(x, 0.0, 1.0) * target.getWidth());
                pointerY = target.getY() + (int) Math.round(y);
            } else {
                pointerX = target.getX() + (int) Math.round(Math.clamp(x, 0.0, 1.0) * target.getWidth());
                pointerY = target.getY() + (int) Math.round(Math.clamp(y, 0.0, 1.0) * target.getHeight());
            }
            Container panelContainer = panelContainer(screen, state.get("path"));
            if (panelContainer != null && (pointerX < panelContainer.getX() || pointerX > panelContainer.getX() + panelContainer.getWidth()
                || pointerY < panelContainer.getContentTop() || pointerY > panelContainer.getY() + panelContainer.getHeight())) {
                return null;
            }
            return target.isMouseOver(pointerX, pointerY) ? new Pointer(pointerX, pointerY) : null;
        }
        if (!state.has("screenX") || !state.has("screenY")) {
            return null;
        }
        int x = (int) Math.round(Math.clamp(number(state.get("screenX"), 0.0), 0.0, 1.0) * screen.width);
        int y = (int) Math.round(Math.clamp(number(state.get("screenY"), 0.0), 0.0, 1.0) * screen.height);
        return new Pointer(x, y);
    }

    public static void applyFocusAccents(Screen screen, Collection<RemoteFocus> focuses) {
        clearFocusAccents(screen);
        if (screen == null || focuses == null || focuses.isEmpty()) {
            return;
        }
        Map<AnimatedWidget, List<Integer>> colors = new LinkedHashMap<>();
        Widget localFocus = focused(screen);
        for (RemoteFocus focus : focuses) {
            Widget target = focus != null ? resolve(screen, focus.path()) : null;
            if (!(target instanceof AnimatedWidget animated) || animated.selected || sharesFocus(target, localFocus)) {
                continue;
            }
            colors.computeIfAbsent(animated, ignored -> new ArrayList<>()).add(focus.color());
            List<Widget> ancestors = ScreenCollaborationSurface.ancestors(screen, target);
            for (int index = ancestors.size() - 1; index >= 0; index--) {
                Widget ancestor = ancestors.get(index);
                if (ancestor instanceof AnimatedWidget owner && ancestor instanceof CollaborativeWidget && !owner.selected && !sharesFocus(owner, localFocus)) {
                    colors.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(focus.color());
                    break;
                }
            }
        }
        Set<AnimatedWidget> accented = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<AnimatedWidget, List<Integer>> entry : colors.entrySet()) {
            int color = CollaborationVisuals.blend(entry.getValue(), 0xFFFFFFFF);
            Accent accent = CollaborationVisuals.accent(color);
            entry.getKey().setCollaborationAccent(accent);
            accented.add(entry.getKey());
        }
        if (!accented.isEmpty()) {
            ACCENTED.put(screen, accented);
        }
    }

    public static Widget focused(Screen screen) {
        return ScreenCollaborationSurface.focused(screen);
    }

    public static void clearFocusAccents(Screen screen) {
        if (screen == null) {
            return;
        }
        Set<AnimatedWidget> previous = ACCENTED.remove(screen);
        if (previous != null) {
            previous.forEach(widget -> widget.setCollaborationAccent(null));
        }
    }

    private static JsonArray encodePath(ScreenCollaborationSurface.Path path) {
        JsonArray encoded = new JsonArray();
        if (path == null || path.isEmpty()) {
            return encoded;
        }
        if (!path.containerId().isBlank()) {
            encoded.add("panel:" + path.containerId());
        } else if (!path.rootIdentity().isBlank()) {
            JsonObject root = new JsonObject();
            root.addProperty("identity", path.rootIdentity());
            root.addProperty("index", path.rootIndex());
            encoded.add(root);
        } else {
            encoded.add(path.rootIndex());
        }
        for (ScreenCollaborationSurface.Segment segment : path.segments()) {
            if (segment.recursive()) {
                encoded.add("key:" + segment.identity());
            } else if (!segment.identity().isBlank()) {
                JsonObject identity = new JsonObject();
                identity.addProperty("identity", segment.identity());
                identity.addProperty("index", segment.index());
                encoded.add(identity);
            } else {
                encoded.add(segment.index());
            }
        }
        return encoded;
    }

    private static ScreenCollaborationSurface.Path decodePath(JsonArray encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new ScreenCollaborationSurface.Path("", -1, List.of());
        }
        JsonElement root = encoded.get(0);
        String containerId = root.isJsonPrimitive() && root.getAsJsonPrimitive().isString() && root.getAsString().startsWith("panel:")
            ? root.getAsString().substring("panel:".length()) : "";
        String rootIdentity = root.isJsonObject() ? string(root.getAsJsonObject().get("identity")) : "";
        int rootIndex = containerId.isBlank() ? root.isJsonObject() ? integer(root.getAsJsonObject().get("index"), -1) : integer(root, -1) : -1;
        List<ScreenCollaborationSurface.Segment> segments = new ArrayList<>();
        for (int index = 1; index < encoded.size(); index++) {
            JsonElement value = encoded.get(index);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().startsWith("key:")) {
                segments.add(new ScreenCollaborationSurface.Segment(value.getAsString().substring("key:".length()), -1, true));
            } else if (value.isJsonObject()) {
                JsonObject identity = value.getAsJsonObject();
                segments.add(new ScreenCollaborationSurface.Segment(string(identity.get("identity")), integer(identity.get("index"), -1), false));
            } else {
                segments.add(new ScreenCollaborationSurface.Segment("", integer(value, -1), false));
            }
        }
        return new ScreenCollaborationSurface.Path(containerId, rootIndex, rootIdentity, segments);
    }

    private static void collectWidgetStates(Screen screen, Widget widget, JsonObject states, Set<Widget> visited) {
        if (widget == null || !widget.isVisible() || !widget.isCollaborationEnabled() || !visited.add(widget)) {
            return;
        }
        JsonObject state = widget instanceof CollaborativeWidget collaborative && collaborative.hasCollaborationState()
            ? encodeState(collaborative.captureCollaborationState()) : null;
        if (state != null) {
            JsonArray path = path(screen, widget);
            if (!path.isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.add("path", path);
                entry.add("state", state);
                states.add(String.valueOf(states.size()), entry);
            }
        }
        for (Widget child : children(widget)) {
            collectWidgetStates(screen, child, states, visited);
        }
    }

    private static Widget findByKey(Screen screen, String key) {
        return ScreenCollaborationSurface.findByKey(screen, key);
    }

    private static boolean sharesFocus(Widget widget, Widget focus) {
        if (widget == null || focus == null) {
            return false;
        }
        if (ScreenCollaborationSurface.contains(widget, focus) || ScreenCollaborationSurface.contains(focus, widget)) {
            return true;
        }
        String key = widget.getCollaborationKey();
        String focusKey = focus.getCollaborationKey();
        return widget == focus || key != null && !key.isBlank() && focusKey != null && !focusKey.isBlank()
            && (key.startsWith(focusKey + "/") || focusKey.startsWith(key + "/"));
    }

    private static void resetWidgetState(Widget widget) {
        if (widget instanceof CollaborativeWidget collaborative) {
            collaborative.clearCollaborationState();
        }
    }

    private static JsonObject encodeState(CollaborativeWidget.State state) {
        JsonObject encoded = new JsonObject();
        if (state instanceof CollaborativeWidget.DropDownState dropdown) {
            encoded.addProperty("type", "dropdown");
            encoded.addProperty("expanded", dropdown.expanded());
            encoded.addProperty("scroll", dropdown.scrollOffset());
            encoded.addProperty("selected", dropdown.selectedIndex());
            return encoded;
        }
        if (state instanceof CollaborativeWidget.CompactBindingState compact) {
            encoded.addProperty("type", "compact");
            encoded.addProperty("selecting", compact.selecting());
            encoded.addProperty("input", compact.input());
            encoded.addProperty("query", compact.query());
            encoded.addProperty("scroll", compact.scrollOffset());
            return encoded;
        }
        if (state instanceof TextInputWidget.CollaborationState text) {
            encoded.addProperty("type", "text");
            encoded.addProperty("text", text.text());
            encoded.addProperty("cursor", text.cursor());
            encoded.addProperty("selectionStart", text.selectionStart());
            encoded.addProperty("selectionEnd", text.selectionEnd());
            encoded.addProperty("scroll", text.scrollOffset());
            return encoded;
        }
        if (state instanceof ItemSelectorWidget.CollaborationState selector) {
            JsonObject selectorState = GSON.toJsonTree(selector).getAsJsonObject();
            selectorState.addProperty("type", "selector");
            return selectorState;
        }
        if (state instanceof ToggleWidget.CollaborationState toggle) {
            encoded.addProperty("type", "toggle");
            encoded.addProperty("value", toggle.value());
            return encoded;
        }
        if (state instanceof ScrollSelectorWidget.CollaborationState selector) {
            encoded.addProperty("type", "scroll-selector");
            encoded.addProperty("selected", selector.selectedIndex());
            return encoded;
        }
        return null;
    }

    private static CollaborativeWidget.State decodeState(JsonObject state) {
        return switch (string(state.get("type"))) {
            case "dropdown" -> new CollaborativeWidget.DropDownState(booleanValue(state.get("expanded")), (float) number(state.get("scroll"), 0.0),
                integer(state.get("selected"), -1));
            case "compact" -> new CollaborativeWidget.CompactBindingState(booleanValue(state.get("selecting")), string(state.get("input")),
                string(state.get("query")), (float) number(state.get("scroll"), 0.0));
            case "text" -> new TextInputWidget.CollaborationState(string(state.get("text")), integer(state.get("cursor"), 0),
                integer(state.get("selectionStart"), 0), integer(state.get("selectionEnd"), 0), (float) number(state.get("scroll"), 0.0));
            case "selector" -> GSON.fromJson(state, ItemSelectorWidget.CollaborationState.class);
            case "toggle" -> new ToggleWidget.CollaborationState(booleanValue(state.get("value")));
            case "scroll-selector" -> new ScrollSelectorWidget.CollaborationState(integer(state.get("selected"), 0));
            default -> null;
        };
    }

    private record TimedState(JsonArray path, String key, JsonObject state, long updatedAt) {
    }

    private static List<? extends Widget> children(Widget widget) {
        return ScreenCollaborationSurface.children(widget);
    }

    private static Container panelContainer(Screen screen, JsonElement pathElement) {
        if (screen == null || pathElement == null || !pathElement.isJsonArray() || pathElement.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement root = pathElement.getAsJsonArray().get(0);
        if (!root.isJsonPrimitive() || !root.getAsJsonPrimitive().isString() || !root.getAsString().startsWith("panel:")) {
            return null;
        }
        Widget container = screen.collaborationContainers().get(root.getAsString().substring("panel:".length()));
        return container instanceof Container panelContainer ? panelContainer : null;
    }

    private static int integer(JsonElement value, int fallback) {
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static double number(JsonElement value, double fallback) {
        try {
            double number = value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
            return Double.isFinite(number) ? number : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonElement value) {
        try {
            return value != null && value.isJsonPrimitive() && value.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String string(JsonElement value) {
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private DesignerCollaborationAuthority() {
    }
}
