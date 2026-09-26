package redxax.oxy.remotely.ui.widgets;

import restudio.rescreen.config.Config;
import restudio.rescreen.platform.FadeMask;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.ImageUtils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReactorPlanWidget extends AnimatedWidget {
    private static final int TEXT_FADE_WIDTH = 10;
    private static final int TEXT_WIDTH_CACHE_LIMIT = 8;
    private static final int PADDING_X = 12;
    private static final int PADDING_Y = 4;
    private static final int CONTENT_Y_OFFSET = 4;
    private static final int ICON_SIZE = 20;
    private static final int TOP_GAP = 4;
    private static final int SUBTITLE_GAP = 1;
    private static final Identifier REACTOR_ICON_ID = ImageUtils.loadIconId("Reactor.png");

    private String title;
    private String subtitle;
    private String specs;
    private String price;
    private Runnable onAction;
    private final Map<String, Integer> textWidths = new LinkedHashMap<>(TEXT_WIDTH_CACHE_LIMIT, 0.75f, true);
    private long textWidthRevision = Long.MIN_VALUE;

    public ReactorPlanWidget(int x, int y, int width, int height, Runnable onSelect) {
        super(x, y, width, height, "");
        this.entranceAnimationEnabled = false;
        this.accentType = ThemeManager.getDefaultAccent();
        this.onAction = onSelect;
        this.title = "Reactor Plan";
        this.subtitle = "Choose A Plan";
        this.specs = "";
        this.price = "";
    }

    public void setContent(String title, String subtitle, String specs, String price) {
        this.title = title == null || title.isBlank() ? "Reactor Plan" : title;
        this.subtitle = subtitle == null || subtitle.isBlank() ? "Choose A Plan" : subtitle;
        this.specs = specs == null ? "" : specs;
        this.price = price == null ? "" : price;
    }

    public void setOnAction(Runnable onAction) {
        this.onAction = onAction;
    }

    public int getPreferredHeight() {
        int specsHeight = ITextRenderer.fontHeight;
        return (PADDING_Y * 2) + CONTENT_Y_OFFSET + ICON_SIZE + 1 + TOP_GAP + specsHeight;
    }

    @Override
    protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        int primaryColor = ThemeManager.getColor(ThemeColor.textHover);
        int dimText = ThemeManager.getColor(ThemeColor.textDark);
        int accent = accentType != null ? accentType.getAccentColor() : ThemeManager.getDefaultAccent().getAccentColor();
        LayoutMetrics metrics = computeLayout();

        if (REACTOR_ICON_ID != null) {
            ctx.drawPixelArt(REACTOR_ICON_ID, metrics.iconX(), metrics.iconY(), ICON_SIZE, ICON_SIZE);
        }

        String displayTitle = title == null ? "" : title.trim();
        drawFadedText(ctx, displayTitle, metrics.textLeftX(), metrics.textLeftX(), metrics.titleY(),
                metrics.titleMaxWidth(), primaryColor, false, true);

        String displaySubtitle = subtitle == null ? "" : subtitle.trim();
        drawFadedText(ctx, displaySubtitle, metrics.textLeftX(), metrics.textLeftX(), metrics.subtitleY(),
                metrics.priceRightX() - metrics.textLeftX(), dimText, false, true);

        String rawPrice = price == null ? "" : price.trim();
        if (!rawPrice.isBlank()) {
            int priceBoundX = metrics.priceRightX() - metrics.priceMaxWidth();
            int priceX = metrics.priceRightX() - textWidth(rawPrice);
            drawFadedText(ctx, rawPrice, priceBoundX, priceX, metrics.priceY(), metrics.priceMaxWidth(), accent, true, false);
        }

        ctx.fill(metrics.dividerX1(), metrics.dividerY(), metrics.dividerX2(), metrics.dividerY() + 1, borderColor);

        String displaySpecs = specs == null || specs.isBlank() ? "" : specs.trim();
        int specsBoundX = metrics.centerX() - metrics.specsMaxWidth() / 2;
        int specsWidth = textWidth(displaySpecs);
        int specsX = specsWidth <= metrics.specsMaxWidth()
                ? metrics.centerX() - specsWidth / 2
                : specsBoundX;
        drawFadedText(ctx, displaySpecs, specsBoundX, specsX, metrics.specsY(), metrics.specsMaxWidth(), dimText, false, true);
    }

    private LayoutMetrics computeLayout() {
        int iconX = getX() + PADDING_X;
        int priceRightX = getX() + getWidth() - PADDING_X;
        int centerX = getX() + (getWidth() / 2);
        int textLeftX = iconX + ICON_SIZE + 8;
        int titleHeight = ITextRenderer.fontHeight;
        int topRowHeight = 20;
        int specsHeight = ITextRenderer.fontHeight;
        int totalHeight = topRowHeight + 1 + TOP_GAP + specsHeight;
        int requiredHeight = totalHeight + (PADDING_Y * 2) + CONTENT_Y_OFFSET;
        int startY = getY() + PADDING_Y + CONTENT_Y_OFFSET;
        if (requiredHeight > getHeight()) {
            startY = getY() + Math.max(0, (getHeight() - totalHeight) / 2);
        }

        int iconY = startY;
        int priceZoneWidth = Math.clamp((int) Math.round(getWidth() * 0.24), 66, 96);
        int titleMaxWidth = Math.max(42, priceRightX - textLeftX - priceZoneWidth - 18);
        int titleY = startY;
        int subtitleY = titleY + titleHeight + SUBTITLE_GAP;
        int priceY = titleY;
        int dividerY = startY + topRowHeight + 1;
        int specsY = dividerY + TOP_GAP;

        return new LayoutMetrics(centerX, iconX, iconY, textLeftX, titleY, subtitleY, priceRightX, priceY, priceZoneWidth, titleMaxWidth, dividerY, iconX, priceRightX, specsY, priceRightX - iconX);
    }

    private record LayoutMetrics(int centerX, int iconX, int iconY, int textLeftX, int titleY, int subtitleY, int priceRightX, int priceY, int priceMaxWidth, int titleMaxWidth, int dividerY, int dividerX1, int dividerX2, int specsY, int specsMaxWidth) {
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (!isVisible() || !isActive()) {
            return false;
        }
        if (event.button() == ReMouseButton.LEFT && isMouseOver(event.x(), event.y())) {
            if (onAction != null) {
                onAction.run();
            }
            return true;
        }
        return super.mouseClicked(event);
    }

    private void drawFadedText(IDrawContext context, String text, int boundX, int textX, int y, int width, int color,
                               boolean fadeLeft, boolean fadeRight) {
        if (text == null || text.isEmpty() || width <= 0) return;
        int fadeWidth = Math.min(TEXT_FADE_WIDTH, width);
        int measuredWidth = textWidth(text);
        float leftStrength = fadeLeft ? Math.clamp((boundX - textX) / (float) fadeWidth, 0f, 1f) : 0f;
        float rightStrength = fadeRight ? Math.clamp((textX + measuredWidth - boundX - width) / (float) fadeWidth, 0f, 1f) : 0f;
        FadeMask mask = new FadeMask(boundX, y - 2, width, ITextRenderer.fontHeight + 2,
                fadeLeft ? fadeWidth : 0, fadeRight ? fadeWidth : 0, 0, 0,
                leftStrength, rightStrength, 0f, 0f,
                ThemeManager.getColor(ThemeColor.background));
        context.renderFaded(mask, () -> context.drawText(text, textX, y, color, Config.shadow));
    }

    private int textWidth(String text) {
        long revision = TextRenderer.metricsRevision();
        if (revision != textWidthRevision) {
            textWidths.clear();
            textWidthRevision = revision;
        }
        Integer cached = textWidths.get(text);
        if (cached != null) return cached;
        int width = TextRenderer.tr.getWidth(text);
        textWidths.put(text, width);
        if (textWidths.size() > TEXT_WIDTH_CACHE_LIMIT) {
            Iterator<String> oldest = textWidths.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return width;
    }
}
