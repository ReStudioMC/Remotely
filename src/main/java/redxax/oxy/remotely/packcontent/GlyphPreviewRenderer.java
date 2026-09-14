package redxax.oxy.remotely.packcontent;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rescreen.platform.Clock;
import restudio.rebase.ui.widgets.TerminalTextDecoration;
import restudio.rebase.ui.widgets.editor.TextLineDecoration;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.render.Render;
import restudio.rescreen.text.FontRegistry;
import restudio.rescreen.text.StyledText;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static restudio.rescreen.config.Config.animationsEnabled;
import static restudio.rescreen.config.Config.deltaTime;
import static restudio.rescreen.config.Config.desktopMode;
import static restudio.rescreen.config.Config.globalExpandSpeed;
import static restudio.rescreen.render.TextRenderer.tr;

public class GlyphPreviewRenderer {
    enum PreviewSurface {
        FILE_EDITOR,
        TERMINAL
    }

    private static final char TERMINAL_WIDE_CONTINUATION = '\uE000';
    private static final int HOVER_SIZE = 48;
    private static final int HOVER_MAX_IMAGE_WIDTH = 144;
    private static final long HOVER_TARGET_TTL_MS = 1200L;
    private static final long REFRESH_INTERVAL_MS = 2500L;
    private static final Pattern YAML_GLYPH_ID = Pattern.compile("^([A-Za-z0-9_.-]+):\\s*$");
    private final GlyphPreviewAccess access;
    private final String filePath;
    private final String language;
    private final Clock clock;
    private final BrowserSafeState.BooleanValue refreshing = new BrowserSafeState.BooleanValue(false);
    private final Map<String, GlyphPreviewAccess.Image> loadedImages = new HashMap<>();
    private final Set<String> loadingImages = new HashSet<>();
    private final Set<String> failedImages = new HashSet<>();
    private final GlyphHoverWidget hoverWidget = new GlyphHoverWidget();
    private HoverTarget hoverTarget;
    private PendingHover pendingHover;
    private long hoverTargetSeenMs;
    private long lastRefreshMs;

    public GlyphPreviewRenderer(GlyphPreviewAccess access, String filePath, String language) {
        this(access, filePath, language, Clock.system());
    }

    public GlyphPreviewRenderer(GlyphPreviewAccess access, String filePath, String language, Clock clock) {
        this.access = access;
        this.filePath = filePath;
        this.language = language;
        this.clock = clock;
    }

    public void drawEditor(TextLineDecoration.TextLineDecorationContext context, GlyphPreviewMode mode) {
        refreshIfDue();
        draw(context.drawContext(), context.lineText(), context.drawX(), context.drawY(), context.lineHeight(), context.monospace() ? context.charWidth() : -1, context.mouseX(), context.mouseY(), mode, false, PreviewSurface.FILE_EDITOR);
        drawGlyphConfigPreview(context, mode, false);
    }

    public void drawEditorOverlay(TextLineDecoration.TextLineDecorationOverlayContext context) {
        drawQueuedHover(context.drawContext());
    }

    public void drawTerminalOverlay(TerminalTextDecoration.TerminalTextDecorationOverlayContext context) {
        drawQueuedHover(context.drawContext());
    }

    private void drawQueuedHover(IDrawContext ctx) {
        if (pendingHover != null) {
            drawHover(ctx, pendingHover.preview(), pendingHover.mouseX(), pendingHover.mouseY());
            pendingHover = null;
        } else {
            hoverWidget.hidePreview();
        }
    }

    public void drawTerminal(TerminalTextDecoration.TerminalTextDecorationContext context, GlyphPreviewMode mode) {
        refreshIfDue();
        draw(context.drawContext(), context.text(), context.segmentX(), context.segmentY(), context.lineHeight(), context.charWidth(), context.mouseX(), context.mouseY(), mode, false, PreviewSurface.TERMINAL);
    }

    public boolean replaceTerminal(TerminalTextDecoration.TerminalTextDecorationContext context, GlyphPreviewMode mode) {
        refreshIfDue();
        expireHoverTarget();
        if (mode == null || mode == GlyphPreviewMode.OFF || context.text() == null || context.text().isEmpty()) {
            return false;
        }
        if (!mode.inline()) {
            draw(context.drawContext(), context.text(), context.segmentX(), context.segmentY(), context.lineHeight(), context.charWidth(), context.mouseX(), context.mouseY(), mode, false, PreviewSurface.TERMINAL);
            return false;
        }
        List<GlyphPreviewAccess.Preview> previews = access.resolveGlyphs(context.text());
        if (previews.isEmpty()) {
            return false;
        }
        int rawCursor = 0;
        int visualX = context.segmentX();
        GlyphPreviewAccess.Preview hovered = null;
        for (GlyphPreviewAccess.Preview preview : previews) {
            int start = Math.max(0, Math.min(preview.match().start(), context.text().length()));
            if (start < rawCursor) {
                continue;
            }
            int end = terminalTokenEnd(context.text(), start,
                    Math.max(start, Math.min(preview.match().end(), context.text().length())));
            if (start > rawCursor) {
                String plain = context.text().substring(rawCursor, start);
                drawTerminalPlain(context, plain, visualX);
                visualX += plain.length() * context.charWidth();
            }
            int tokenX = visualX + preview.match().shift();
            GlyphPreviewAccess.Image image = previewImage(preview);
            int imageW = Math.max(context.lineHeight(), context.charWidth());
            if (mode.inline() && image != null) {
                int h = Math.max(8, context.lineHeight() - 2);
                imageW = Math.max(context.charWidth(), Math.round((float) image.width() * h / Math.max(1, image.height())));
                drawPixelArt(context.drawContext(), image, tokenX, context.segmentY() - 1, imageW, h);
            }
            int tokenW = Math.max(context.charWidth(), imageW);
            if (mode.hover() && context.mouseX() >= tokenX && context.mouseX() <= tokenX + tokenW && context.mouseY() >= context.segmentY() - 2 && context.mouseY() <= context.segmentY() + context.lineHeight()) {
                hovered = preview;
                rememberHoverTarget(preview, tokenX - 4, context.segmentY() - 4, tokenX + tokenW + 4, context.segmentY() + context.lineHeight() + 4);
            }
            visualX += tokenW + Math.max(1, context.charWidth() / 3);
            rawCursor = end;
        }
        if (rawCursor < context.text().length()) {
            String plain = context.text().substring(rawCursor);
            drawTerminalPlain(context, plain, visualX);
        }
        if (hovered != null) {
            renderOrQueueHover(context.drawContext(), hovered, context.mouseX(), context.mouseY(), false);
        }
        return true;
    }

    private void drawTerminalPlain(TerminalTextDecoration.TerminalTextDecorationContext context, String text, int x) {
        if (text == null || text.isEmpty()) {
            return;
        }
        context.drawContext().drawStyledText(new StyledText(terminalPlainText(text), context.foregroundColor(), FontRegistry.MONO_FONT), x, context.segmentY(), 0, false);
    }

    static int terminalTokenEnd(String text, int start, int end) {
        boolean rawCharacter = isRawGlyph(text, start, end);
        return rawCharacter && end < text.length() && text.charAt(end) == TERMINAL_WIDE_CONTINUATION ? end + 1 : end;
    }

    private static boolean isRawGlyph(String text, GlyphTagMatch match) {
        return match != null && isRawGlyph(text, match.start(), match.end());
    }

    private static boolean isRawGlyph(String text, int start, int end) {
        return text != null && start >= 0 && start < end && end <= text.length() && text.charAt(start) != '<';
    }

    static boolean rendersPreview(String text, GlyphTagMatch match, PreviewSurface surface) {
        return surface != PreviewSurface.FILE_EDITOR || !isRawGlyph(text, match);
    }

    static String terminalPlainText(String text) {
        return text;
    }

    public boolean openHoveredAsset(double mouseX, double mouseY, int button) {
        expireHoverTarget();
        if (button != ReMouseButton.MIDDLE.code() || hoverTarget == null || mouseX < hoverTarget.x1() || mouseX > hoverTarget.x2() || mouseY < hoverTarget.y1() || mouseY > hoverTarget.y2()) {
            return false;
        }
        return filePath == null ? access.openSource(hoverTarget.preview().glyph()) : access.openAsset(hoverTarget.preview().glyph());
    }

    private void draw(IDrawContext ctx, String text, int drawX, int drawY, int lineHeight, int charWidth, int mouseX, int mouseY, GlyphPreviewMode mode, boolean immediateHover, PreviewSurface surface) {
        expireHoverTarget();
        if (mode == null || mode == GlyphPreviewMode.OFF || text == null || text.isEmpty()) {
            return;
        }
        List<GlyphPreviewAccess.Preview> previews = access.resolveGlyphs(text);
        GlyphPreviewAccess.Preview hovered = null;
        for (GlyphPreviewAccess.Preview preview : previews) {
            if (!rendersPreview(text, preview.match(), surface)) {
                continue;
            }
            int tokenX = drawX + textWidth(text, 0, preview.match().start(), charWidth) + preview.match().shift();
            int textTokenW = Math.max(1, textWidth(text, preview.match().start(), preview.match().end(), charWidth));
            int tokenW = Math.max(lineHeight, textTokenW);
            if (mode.inline()) {
                GlyphPreviewAccess.Image image = previewImage(preview);
                if (image != null) {
                    int h = Math.max(8, lineHeight - 2);
                    int w = Math.max(8, Math.round((float) image.width() * h / Math.max(1, image.height())));
                    drawPixelArt(ctx, image, tokenX, drawY - 1, w, h);
                }
            }
            if (mode.hover() && mouseX >= tokenX && mouseX <= tokenX + tokenW && mouseY >= drawY - 2 && mouseY <= drawY + lineHeight) {
                hovered = preview;
                rememberHoverTarget(preview, tokenX - 4, drawY - 4, tokenX + tokenW + 4, drawY + lineHeight + 4);
            }
        }
        if (hovered != null) {
            renderOrQueueHover(ctx, hovered, mouseX, mouseY, immediateHover);
        }
    }

    private void drawGlyphConfigPreview(TextLineDecoration.TextLineDecorationContext context, GlyphPreviewMode mode, boolean immediateHover) {
        if (mode == null || mode == GlyphPreviewMode.OFF || !isYamlFile(filePath, language)) {
            return;
        }
        String glyphId = glyphConfigId(context.lineText());
        if (glyphId == null) {
            return;
        }
        Optional<GlyphPreviewAccess.Preview> preview = access.resolveGlyph("nexo", glyphId, null);
        if (preview.isEmpty() || !sourceMatchesFile(filePath, preview.get().glyph().sourceFile())) {
            return;
        }
        GlyphPreviewAccess.Image image = previewImage(preview.get());
        if (image == null) {
            return;
        }
        int height = Math.max(8, context.lineHeight() - 2);
        PreviewSize size = previewSizeForHeight(image.width(), image.height(), height, 0);
        int x = context.drawX() + textWidth(context.lineText(), 0, context.lineText().length(), context.monospace() ? context.charWidth() : -1) + 8;
        int y = context.drawY() - 1;
        if (mode.inline()) {
            drawPixelArt(context.drawContext(), image, x, y, size.width(), size.height());
        }
        if (mode.hover() && context.mouseX() >= x && context.mouseX() <= x + size.width() && context.mouseY() >= y && context.mouseY() <= y + size.height()) {
            rememberHoverTarget(preview.get(), x - 4, y - 4, x + size.width() + 4, y + size.height() + 4);
            renderOrQueueHover(context.drawContext(), preview.get(), context.mouseX(), context.mouseY(), immediateHover);
        }
    }

    private void rememberHoverTarget(GlyphPreviewAccess.Preview preview, int x1, int y1, int x2, int y2) {
        hoverTarget = new HoverTarget(preview, x1, y1, x2, y2);
        hoverTargetSeenMs = clock.millis();
    }

    private void expireHoverTarget() {
        if (hoverTarget != null && clock.millis() - hoverTargetSeenMs > HOVER_TARGET_TTL_MS) {
            hoverTarget = null;
        }
    }

    static String glyphConfigId(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = YAML_GLYPH_ID.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static boolean isYamlFile(String filePath, String language) {
        if (filePath == null || !"yaml".equalsIgnoreCase(language)) {
            return false;
        }
        String normalizedPath = normalizePath(filePath);
        int separator = normalizedPath.lastIndexOf('/');
        String name = (separator >= 0 ? normalizedPath.substring(separator + 1) : normalizedPath).toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    static boolean sourceMatchesFile(String filePath, String sourceFile) {
        String file = normalizePath(filePath);
        String source = normalizePath(sourceFile);
        if (file.isEmpty() || source.isEmpty()) {
            return false;
        }
        if (isWindowsPath(file) || isWindowsPath(source)) {
            file = file.toLowerCase(Locale.ROOT);
            source = source.toLowerCase(Locale.ROOT);
        }
        return file.equals(source) || file.endsWith("/" + source) || source.endsWith("/" + file);
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String value = path.trim().replace('\\', '/');
        boolean absolute = value.startsWith("/");
        String drive = isWindowsPath(value) ? value.substring(0, 2) : "";
        int offset = drive.isEmpty() ? 0 : 2;
        while (offset < value.length() && value.charAt(offset) == '/') {
            offset++;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.substring(offset).split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty() && !"..".equals(segments.getLast())) {
                    segments.removeLast();
                } else if (!absolute && drive.isEmpty()) {
                    segments.add(segment);
                }
                continue;
            }
            segments.add(segment);
        }
        String joined = String.join("/", segments);
        if (!drive.isEmpty()) {
            return drive + (joined.isEmpty() ? "/" : "/" + joined);
        }
        return absolute ? "/" + joined : joined;
    }

    private static boolean isWindowsPath(String path) {
        return path != null && path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private void refreshIfDue() {
        if (access == null) {
            return;
        }
        long now = clock.millis();
        if (now - lastRefreshMs < REFRESH_INTERVAL_MS || !refreshing.compareAndSet(false, true)) {
            return;
        }
        lastRefreshMs = now;
        access.refresh().whenComplete((v, e) -> {
            refreshing.set(false);
            if (e == null) failedImages.clear();
        });
    }

    private GlyphPreviewAccess.Image previewImage(GlyphPreviewAccess.Preview preview) {
        GlyphPreviewAccess.Image current = currentFramePreview(preview);
        if (current != null) return current;
        GlyphDefinition glyph = preview == null ? null : preview.glyph();
        GlyphAssetRef ref = glyph == null ? null : glyph.assetRef();
        String assetKey = ref == null ? "" : ref.logicalPath();
        if (assetKey.isBlank()) return null;
        String key = assetKey + "|" + (preview.match().indexStart() == null ? "all" : preview.match().indexStart());
        GlyphPreviewAccess.Image loaded = loadedImages.get(key);
        if (loaded != null) {
            var size = ScreenManager.getInstance().imageAssets().imageSize(loaded.id());
            if (size == null || size.width() <= 0 || size.height() <= 0) {
                return loaded.isRegion() ? null : loaded;
            }
            int width = Math.max(1, size.width() / loaded.columns());
            int height = Math.max(1, size.height() / loaded.rows());
            return new GlyphPreviewAccess.Image(loaded.id(), width, height, loaded.rows(), loaded.columns(), loaded.index());
        }
        if (!failedImages.contains(key) && loadingImages.add(key)) {
            access.loadImage(glyph, preview.match().indexStart()).whenComplete((image, error) -> {
                loadingImages.remove(key);
                if (error == null && image != null) loadedImages.put(key, image);
                else failedImages.add(key);
            });
        }
        return null;
    }

    private GlyphPreviewAccess.Image currentFramePreview(GlyphPreviewAccess.Preview preview) {
        if (preview == null || preview.frames().isEmpty()) {
            return null;
        }
        int total = preview.frames().stream().mapToInt(frame -> Math.max(20, frame.delayMs())).sum();
        if (total <= 0) {
            GlyphPreviewFrame frame = preview.frames().getFirst();
            return frame == null || frame.image() == null ? null : new GlyphPreviewAccess.Image(frame.image(), frame.width(), frame.height());
        }
        int cursor = (int) (clock.millis() % total);
        int elapsed = 0;
        for (GlyphPreviewFrame frame : preview.frames()) {
            elapsed += Math.max(20, frame.delayMs());
            if (cursor < elapsed) {
                return frame == null || frame.image() == null ? null : new GlyphPreviewAccess.Image(frame.image(), frame.width(), frame.height());
            }
        }
        GlyphPreviewFrame frame = preview.frames().getLast();
        return frame == null || frame.image() == null ? null : new GlyphPreviewAccess.Image(frame.image(), frame.width(), frame.height());
    }

    private void renderOrQueueHover(IDrawContext ctx, GlyphPreviewAccess.Preview preview, int mouseX, int mouseY, boolean immediateHover) {
        if (immediateHover) {
            drawHover(ctx, preview, mouseX, mouseY);
            return;
        }
        pendingHover = new PendingHover(preview, mouseX, mouseY);
    }

    private void drawHover(IDrawContext ctx, GlyphPreviewAccess.Preview preview, int mouseX, int mouseY) {
        GlyphPreviewAccess.Image image = previewImage(preview);
        if (image == null) {
            return;
        }
        GlyphDefinition glyph = preview.glyph();
        List<String> lines = Stream.of(
                preview.providerName() + " " + glyph.id(),
                "Source " + access.sourceName(glyph),
                "Size " + image.width() + "x" + image.height(),
                "Ascent " + glyph.ascent() + " Height " + glyph.height(),
                "Font " + (glyph.font() == null || glyph.font().isBlank() ? "Default" : glyph.font()),
                glyph.isGif() ? "Frames " + Math.max(1, glyph.frameCount()) : ""
        ).filter(s -> !s.isBlank()).toList();
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, tr.getWidth(line));
        }
        int padding = 5;
        int lineHeight = ITextRenderer.fontHeight + 2;
        PreviewSize imageSize = previewSizeForHeight(image.width(), image.height(), HOVER_SIZE, HOVER_MAX_IMAGE_WIDTH);
        int width = Math.max(150, imageSize.width() + textWidth + padding * 3);
        int height = Math.max(imageSize.height() + padding * 2, lines.size() * lineHeight + padding * 2);
        HoverBounds bounds = hoverBounds();
        int x = clampHover(mouseX + 14, mouseX - width - 14, width, bounds.left(), bounds.right());
        int y = clampHover(mouseY + 14, mouseY - height - 14, height, bounds.top(), bounds.bottom());
        hoverWidget.setPreview(preview, image, lines, imageSize, padding, lineHeight, width, height);
        hoverWidget.setPosition(x, y);
        hoverWidget.renderHint(ctx);
    }

    private int textWidth(String text, int start, int end, int charWidth) {
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        if (charWidth > 0) {
            return (safeEnd - safeStart) * charWidth;
        }
        return tr.getWidth(text.substring(safeStart, safeEnd));
    }

    private PreviewSize previewSizeForHeight(int imageWidth, int imageHeight, int targetHeight, int maxWidth) {
        int height = Math.max(1, targetHeight);
        int width = Math.max(1, Math.round((float) imageWidth * height / Math.max(1, imageHeight)));
        if (maxWidth > 0 && width > maxWidth) {
            width = maxWidth;
            height = Math.max(1, Math.round((float) imageHeight * width / Math.max(1, imageWidth)));
        }
        return new PreviewSize(width, height);
    }

    private HoverBounds hoverBounds() {
        ScreenManager manager = ScreenManager.getInstance();
        Screen screen = ScreenManager.currentScreen;
        if (screen != null) {
            if (screen.isDesktopWindow()) {
                return insetBounds(0, 0, screen.getWidth(), screen.getHeight());
            }
            if (desktopMode) {
                ScreenManager.DesktopWorkArea area = manager.getDesktopWorkArea();
                return insetBounds(area.x(), area.y(), area.width(), area.height());
            }
            return insetBounds(0, 0, screen.getWidth(), screen.getHeight());
        }
        if (desktopMode) {
            ScreenManager.DesktopWorkArea area = manager.getDesktopWorkArea();
            return insetBounds(area.x(), area.y(), area.width(), area.height());
        }
        return insetBounds(0, 0, manager.getScaledWidth(), manager.getScaledHeight());
    }

    private HoverBounds insetBounds(int x, int y, int width, int height) {
        int left = x + 4;
        int top = y + 4;
        int right = Math.max(left + 1, x + width - 4);
        int bottom = Math.max(top + 1, y + height - 4);
        return new HoverBounds(left, top, right, bottom);
    }

    private int clampHover(int preferred, int flipped, int size, int min, int max) {
        int value = preferred + size <= max ? preferred : flipped;
        return Math.clamp(value, min, Math.max(min, max - size));
    }

    private class GlyphHoverWidget extends AnimatedWidget {
        private GlyphPreviewAccess.Image image;
        private List<String> lines = List.of();
        private PreviewSize imageSize = new PreviewSize(HOVER_SIZE, HOVER_SIZE);
        private String previewKey = "";
        private float visibleWidth;
        private float visibleHeight;
        private int targetWidth;
        private int targetHeight;
        private int padding;
        private int lineHeight;

        private GlyphHoverWidget() {
            super(0, 0, 1, 1, "");
            enableHoverColors = false;
            animateElevation = false;
            entranceAnimationEnabled = false;
            autoSetHovered = false;
        }

        private void setPreview(GlyphPreviewAccess.Preview preview, GlyphPreviewAccess.Image image, List<String> lines, PreviewSize imageSize, int padding, int lineHeight, int targetWidth, int targetHeight) {
            String nextKey = preview.providerName() + "|" + preview.glyph().id() + "|" + image.width() + "x" + image.height() + "|" + lines.hashCode();
            if (!nextKey.equals(previewKey)) {
                visibleWidth = 0f;
                visibleHeight = 0f;
                previewKey = nextKey;
            }
            this.image = image;
            this.lines = lines;
            this.imageSize = imageSize;
            this.padding = padding;
            this.lineHeight = lineHeight;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            setWidth(targetWidth);
            setHeight(targetHeight);
        }

        private void hidePreview() {
            image = null;
            previewKey = "";
            visibleWidth = 0f;
            visibleHeight = 0f;
        }

        private void renderHint(IDrawContext ctx) {
            if (image == null || targetWidth <= 0 || targetHeight <= 0) {
                return;
            }
            if (animationsEnabled) {
                visibleWidth += (targetWidth - visibleWidth) * globalExpandSpeed * deltaTime;
                visibleHeight += (targetHeight - visibleHeight) * globalExpandSpeed * deltaTime;
            } else {
                visibleWidth = targetWidth;
                visibleHeight = targetHeight;
            }
            if (visibleWidth <= 0f || visibleHeight <= 0f) {
                return;
            }
            int drawWidth = Math.max(1, Math.round(visibleWidth));
            int drawHeight = Math.max(1, Math.round(visibleHeight));
            ctx.pushScissorState();
            try {
                ctx.clearScissor();
                ctx.getMatrices().push();
                try {
                    ctx.getMatrices().translate(0, 0, 501);
                    int bg = ThemeManager.getElementBackgroundColor("hint".hashCode() + this.hashCode(), false, false, false, null);
                    int border = ThemeManager.getElementBorderColor("hint".hashCode() + this.hashCode(), false, false, false, null);
                    Render.drawLayeredBox(ctx, getX(), getY(), drawWidth, drawHeight, bg, border);
                    ctx.enableScissor(getX(), getY(), getX() + drawWidth, getY() + drawHeight);
                    try {
                        drawContent(ctx, 0, 0);
                    } finally {
                        ctx.disableScissor();
                    }
                } finally {
                    ctx.getMatrices().pop();
                }
            } finally {
                ctx.popScissorState();
            }
        }

        @Override
        protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
            if (image == null) {
                return;
            }
            int text = ThemeManager.getColor(ThemeColor.text);
            int contentHeight = Math.max(imageSize.height(), lines.size() * lineHeight);
            float textProgress = Math.clamp(targetHeight > 0 ? visibleHeight / targetHeight : 0f, 0f, 1f);
            int imageX = getX() + padding;
            int imageY = getY() + padding + Math.max(0, (contentHeight - imageSize.height()) / 2);
            drawPixelArt(ctx, image, imageX, imageY, imageSize.width(), imageSize.height());
            int tx = imageX + imageSize.width() + padding;
            int ty = getY() + padding + Math.max(0, (contentHeight - lines.size() * lineHeight) / 2) + 1;
            if (textProgress > 0) {
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int lineColor = applyAlpha(text, lineFadeProgress(textProgress, i, lines.size()));
                    ctx.drawText(line, tx, ty, lineColor, false);
                    ty += lineHeight;
                }
            }
        }
    }

    private record HoverTarget(GlyphPreviewAccess.Preview preview, int x1, int y1, int x2, int y2) {}

    private record PendingHover(GlyphPreviewAccess.Preview preview, int mouseX, int mouseY) {}

    private record PreviewSize(int width, int height) {}

    private record HoverBounds(int left, int top, int right, int bottom) {}

    private static void drawPixelArt(IDrawContext context, GlyphPreviewAccess.Image image, float x, float y, float width, float height) {
        if (image == null || image.id() == null) {
            return;
        }
        if (!image.isRegion()) {
            context.drawPixelArt(image.id(), x, y, width, height);
            return;
        }
        var size = ScreenManager.getInstance().imageAssets().imageSize(image.id());
        if (size == null || size.width() <= 0 || size.height() <= 0) {
            return;
        }
        float cellWidth = (float) size.width() / image.columns();
        float cellHeight = (float) size.height() / image.rows();
        int column = image.index() % image.columns();
        int row = image.index() / image.columns();
        context.drawImageRegion(image.id(), x, y, width, height, column * cellWidth, row * cellHeight, cellWidth, cellHeight, true, 1.0f);
    }
}
