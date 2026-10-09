package redxax.oxy.remotely.servers.reproxy;

import redxax.oxy.remotely.servers.ReProxyManager;
import restudio.rebase.ui.screens.editor.EditorDecorationBinding;
import restudio.rebase.ui.screens.resources.ResourceForwarding;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rebase.ui.widgets.editor.EditorDocumentSnapshot;
import restudio.rebase.ui.widgets.editor.TextLineDecoration;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.ClipRect;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.List;

public final class PluginConfigDecoration implements TextLineDecoration {
    private static final Identifier LINK = Identifier.icon("link");
    private static final Identifier DISCONNECT = Identifier.icon("closeReverse");
    private static final Identifier ATTENTION = Identifier.icon("info");
    private static final Identifier LOADING = Identifier.animatedIcon("loadingBlue");
    private final CodeEditorWidget editor;
    private final PluginForwarding forwarding;
    private final ReProxyIntegrations.Integration integration;
    private final String path;
    private final boolean local;
    private final Runnable diskChanged;
    private final Button address = new Button(LINK, 3, null);
    private final Button disconnect = new Button(DISCONNECT, 2, this::unlink);
    private long revision = -1;
    private ReProxyIntegrations.Integration lineIntegration;
    private int portLine;
    private boolean unlinking;
    private boolean closed;
    private String unlinkWaitingHint = "";
    private boolean painted;
    private boolean clickable;
    private ClipRect clip;

    private PluginConfigDecoration(EditorDecorationBinding binding, PluginForwarding forwarding, ReProxyIntegrations.Integration integration,
                                   String path, boolean local) {
        editor = binding.editor();
        this.forwarding = forwarding;
        this.integration = integration;
        this.path = path;
        this.local = local;
        diskChanged = binding.diskChanged();
        binding.cleanup().accept(() -> {
            closed = true;
            address.cleanup();
            disconnect.cleanup();
        });
    }

    public static TextLineDecoration bind(EditorDecorationBinding binding, PluginForwarding forwarding, boolean local) {
        if (binding == null || binding.editor() == null || binding.filePath() == null || forwarding == null) return null;
        String source = binding.filePath().asString().replace('\\', '/');
        String file = source;
        while (file.startsWith("/")) file = file.substring(1);
        ReProxyIntegrations.Integration integration = integration(forwarding, file);
        if (integration == null) {
            String root = binding.workspaceRoot() == null ? "" : binding.workspaceRoot().asString().replace('\\', '/');
            while (root.endsWith("/") && !root.isEmpty()) root = root.substring(0, root.length() - 1);
            String prefix = root + "/";
            if (!root.isEmpty() && (source.startsWith(prefix) || root.contains(":") && source.regionMatches(true, 0, prefix, 0, prefix.length()))) {
                file = source.substring(prefix.length());
                integration = integration(forwarding, file);
            }
        }
        if (integration == null) return null;
        forwarding.admitConfig(file);
        binding.cleanup().accept(forwarding.watchConfig(file, binding.diskChanged()));
        return new PluginConfigDecoration(binding, forwarding, integration, file, local);
    }

    private static ReProxyIntegrations.Integration integration(PluginForwarding forwarding, String path) {
        ReProxyIntegrations.Integration admitted = forwarding.configIntegration(path);
        if (admitted != null) return admitted;
        for (ReProxyIntegrations.Integration integration : ReProxyIntegrations.catalog()) {
            if (integration.paths().contains(path)) return integration;
        }
        return null;
    }

    public static TextLineDecoration compose(TextLineDecoration first, TextLineDecoration second) {
        if (first == null) return second;
        if (second == null) return first;
        return new TextLineDecoration() {
            @Override
            public void draw(TextLineDecorationContext context) {
                first.draw(context);
                second.draw(context);
            }

            @Override
            public void afterDraw(TextLineDecorationOverlayContext context) {
                first.afterDraw(context);
                second.afterDraw(context);
            }

            @Override
            public boolean mouseClicked(TextLineDecorationClickContext context) {
                return second.mouseClicked(context) || first.mouseClicked(context);
            }
        };
    }

    @Override
    public void draw(TextLineDecorationContext context) {
        if (closed) return;
        ResourceForwarding.State state = forwarding.configState(path);
        if (!state.visible()) return;
        EditorDocumentSnapshot document = editor.getDocumentSnapshot();
        ReProxyIntegrations.Integration definition = forwarding.configIntegration(path);
        if (definition == null) definition = integration;
        if (revision != document.revision() || !definition.equals(lineIntegration)) {
            revision = document.revision();
            lineIntegration = definition;
            portLine = portLine(document, definition);
        }
        if (context.lineIndex() != portLine) return;
        boolean busy = unlinking || state.busy();
        String label = busy ? waitingLabel(state.hint()) : state.connected() ? state.address().isBlank() ? "Linked" : state.address() : "Needs Attention";
        notifyUnlinkWait(state);
        if (!label.equals(address.getMessage())) address.setMessage(label);
        Identifier icon = busy ? LOADING : state.connected() ? LINK : ATTENTION;
        if (address.getIconId() != icon) address.setIcon(icon);
        int height = Math.max(11, context.lineHeight());
        int y = context.drawY() - (height - ITextRenderer.fontHeight) / 2 - 1;
        address.setHeight(height);
        disconnect.setHeight(height);
        disconnect.setWidth(height);
        IDrawContext draw = context.drawContext();
        clip = draw.visibleBounds();
        int right = editor.getContentEndX() - 2;
        int left = editor.getContentStartX() + 2;
        if (clip != null) {
            right = Math.min(right, (int) Math.floor(clip.right()) - 2);
            left = Math.max(left, (int) Math.ceil(clip.left()) + 2);
        }
        int available = right - left - disconnect.getWidth() - 3;
        if (available < height) return;
        address.limitWidth(available);
        disconnect.setPosition(right - disconnect.getWidth(), y);
        address.setPosition(disconnect.getX() - address.getWidth() - 3, y);
        disconnect.setActive(!unlinking);
        String status = state.hint().isBlank() ? state.connected() ? local ? "ReProxy Linked" : "Public Port Linked" : "Connection Needs Attention" : state.hint();
        address.setHint(state.address().isBlank() ? status : status + "\n" + state.address());
        disconnect.setHint(unlinking ? status : "Disconnect " + definition.name());
        address.top = clip == null ? editor.getVisibleContentTop() : clip.top();
        disconnect.top = address.top;
        painted = clip == null ? draw.scissorsContains(disconnect.getX(), y + height / 2)
                : clip.intersects(disconnect.getX(), y, right, y + height);
    }

    @Override
    public void afterDraw(TextLineDecorationOverlayContext context) {
        IDrawContext draw = context.drawContext();
        if (painted && !closed) {
            draw.pushScissorState();
            try {
                if (clip != null) draw.enableScissor((float) clip.left(), (float) clip.top(), (float) clip.right(), (float) clip.bottom());
                address.render(draw, context.mouseX(), context.mouseY(), context.delta());
                disconnect.render(draw, context.mouseX(), context.mouseY(), context.delta());
            } finally {
                draw.popScissorState();
            }
            address.renderHintOverlay(draw);
            disconnect.renderHintOverlay(draw);
        } else {
            address.hideHint();
            disconnect.hideHint();
        }
        clickable = painted && !closed;
        painted = false;
    }

    @Override
    public boolean mouseClicked(TextLineDecorationClickContext context) {
        if (closed || !forwarding.configState(path).visible() || !contains(context.mouseX(), context.mouseY())) return false;
        if (context.button() == 0 && !unlinking) disconnect.onClick(context.mouseX(), context.mouseY(), context.button());
        return true;
    }

    private void unlink() {
        if (closed || unlinking) return;
        unlinking = true;
        unlinkWaitingHint = "";
        try {
            forwarding.unlinkConfig(path).whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> finish(failure)));
            notifyUnlinkWait(forwarding.configState(path));
        } catch (RuntimeException failure) {
            finish(failure);
        }
    }

    private static String waitingLabel(String hint) {
        if (hint.startsWith("Stop ")) return "Stop Server";
        if (hint.startsWith("Start ")) return "Start Server";
        if (hint.startsWith("Choose ")) return "Choose Address";
        if (hint.startsWith("Enable ")) return "Enable Plugin";
        return "Updating";
    }

    private void notifyUnlinkWait(ResourceForwarding.State state) {
        String hint = state.hint();
        if (closed || !unlinking || !state.busy() || !(hint.startsWith("Stop ") || hint.startsWith("Start ") || hint.startsWith("Choose ") || hint.startsWith("Enable "))
                || hint.equals(unlinkWaitingHint)) return;
        unlinkWaitingHint = hint;
        new Notification("Plugin Disconnect Queued", hint, Notification.Type.INFO);
    }

    private void finish(Throwable failure) {
        if (closed) return;
        unlinking = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof Async.Cancellation) return;
        if (failure == null) {
            new Notification("Plugin Settings Applied", forwarding.configState(path).hint(), Notification.Type.INFO);
            diskChanged.run();
            return;
        }
        new Notification("Plugin Connection Failed", ReProxyManager.failureMessage(failure), Notification.Type.ERROR);
    }

    private boolean contains(double x, double y) {
        return (painted || clickable) && disconnect.isMouseOver(x, y)
                && (clip == null || x >= clip.left() && x < clip.right() && y >= clip.top() && y < clip.bottom());
    }

    private static int portLine(EditorDocumentSnapshot document, ReProxyIntegrations.Integration integration) {
        if (integration.format() == ReProxyIntegrations.Format.HOCON) {
            try { return ReProxyHocon.line(document.text(), integration.portKey()); } catch (IllegalArgumentException ignored) { return 0; }
        }
        String section = "";
        List<String> scopes = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int blockIndent = -1;
        for (int index = 0; index < document.lineCount(); index++) {
            String line = document.line(index);
            String trimmed = stripComment(line).strip();
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("//")) continue;
            int indent = line.length() - line.stripLeading().length();
            if (integration.format() == ReProxyIntegrations.Format.TOML) {
                if (trimmed.startsWith("[")) {
                    section = trimmed.endsWith("]") && !trimmed.startsWith("[[") ? unquote(trimmed.substring(1, trimmed.length() - 1).strip()) : "";
                    continue;
                }
                int split = separator(trimmed, '=');
                if (split < 0) continue;
                String key = unquote(trimmed.substring(0, split).strip());
                if ((section.isEmpty() ? key : section + "." + key).equals(integration.portKey())) return index;
            } else if (integration.format() == ReProxyIntegrations.Format.YAML) {
                if (blockIndent >= 0 && indent > blockIndent) continue;
                blockIndent = -1;
                while (!indents.isEmpty() && indent <= indents.getLast()) {
                    indents.removeLast();
                    scopes.removeLast();
                }
                int split = separator(trimmed, ':');
                if (split < 0) continue;
                String key = unquote(trimmed.substring(0, split).strip());
                if (!key.matches("[A-Za-z0-9_-]+")) continue;
                String path = scopes.isEmpty() ? key : String.join(".", scopes) + "." + key;
                if (path.equals(integration.portKey())) return index;
                String value = trimmed.substring(split + 1).strip();
                if (value.isEmpty()) {
                    scopes.add(key);
                    indents.add(indent);
                } else if (value.startsWith("|") || value.startsWith(">")) blockIndent = indent;
            } else if (key(trimmed, integration.portKey())) return index;
        }
        return 0;
    }

    private static String unquote(String value) {
        if (value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int separator(String value, char separator) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) escaped = false;
            else if (quote == '"' && character == '\\') escaped = true;
            else if (quote == 0 && (character == '\'' || character == '"')) quote = character;
            else if (character == quote) quote = 0;
            else if (quote == 0 && character == separator) return index;
        }
        return -1;
    }

    private static String stripComment(String value) {
        int comment = separator(value, '#');
        return comment < 0 ? value : value.substring(0, comment);
    }

    private static boolean key(String line, String key) {
        if (!line.startsWith(key)) return false;
        String suffix = line.substring(key.length()).stripLeading();
        return suffix.startsWith("=") || suffix.startsWith(":");
    }

    private static final class Button extends IconButton {
        private double top;
        private int widthLimit = Integer.MAX_VALUE;

        private Button(Identifier icon, int padding, Runnable action) {
            super(0, 0, 11, 11, "", icon);
            this.action = action;
            iconSize = 8;
            iconPadding = padding;
            centered = true;
            autoWidthOnTextChange = true;
            entranceAnimationEnabled = false;
            animateElevation = false;
            enableGradient = false;
            flat = true;
            setAnimateLayout(false);
            updateWidth();
        }

        private void limitWidth(int width) {
            if (widthLimit == width) return;
            widthLimit = width;
            updateWidth();
        }

        @Override
        protected void updateWidth() {
            super.updateWidth();
            if (super.getWidth() > widthLimit) setWidth(widthLimit);
        }

        private void hideHint() {
            setHovered(false);
            updateHint();
        }

        @Override
        protected float hintAnchorY() {
            float y = super.hintAnchorY();
            return y - hintTargetHeight - 5 < top ? y + hintAnchorHeight() + hintTargetHeight + 9 : y;
        }
    }
}
