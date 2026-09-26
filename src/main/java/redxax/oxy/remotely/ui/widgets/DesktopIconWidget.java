package redxax.oxy.remotely.ui.widgets;

import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.FadeMask;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.WidgetCleanup;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.ResourceManager;

import java.util.Objects;
import java.util.function.BiConsumer;

import static redxax.oxy.remotely.RemotelyClient.tr;

public class DesktopIconWidget extends AnimatedWidget implements WidgetCleanup {
    private static final int TEXT_FADE_WIDTH = 10;
    private Instance serverInfo;
    private Identifier iconId;
    private boolean ownsIconId;
    private final boolean isCreateButton;
    private String cachedName;
    private int cachedNameWidth;
    private long nameMetricsRevision = Long.MIN_VALUE;

    private BiConsumer<DesktopIconWidget, Integer> onClick;

    public static class Builder extends AnimatedWidget.Builder<DesktopIconWidget, Builder> {
        public Builder(Instance serverInfo, boolean isCreateButton, Identifier iconId) {
            super(new DesktopIconWidget(0, 0, 34, 34, serverInfo, isCreateButton, iconId));
        }

        public Builder onClick(BiConsumer<DesktopIconWidget, Integer> consumer) {
            widget.onClick = consumer;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    public DesktopIconWidget(int x, int y, int width, int height, Instance serverInfo, boolean isCreateButton, Identifier iconId) {
        super(x, y, width, height,(isCreateButton ? "New Server" : serverInfo.getName()));
        setCursorHoverReactive(true);
        this.serverInfo = serverInfo;
        this.isCreateButton = isCreateButton;
        setIcon(iconId);
        this.animateLayout = true;
    }

    @Override
    protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        int iconSize = 32;
        int iconX = getX() + (getWidth() - iconSize) / 2;
        int iconY = getY() + 1;

        if (iconId != null) {
            ctx.drawPixelArt(iconId, iconX, iconY, iconSize, iconSize);
        }

        String name = getMessage();
        int textY = iconY + iconSize + 4;
        int textWidth = getWidth() + 4;
        int textX = getX() - 2;
        int nameWidth = getNameWidth(name);
        if (nameWidth <= textWidth) {
            textX += (textWidth - nameWidth) / 2;
        }
        int resolvedTextX = textX;

        Accent niceAccent = ThemeManager.getAccent("nice");
        Accent dangerAccent = ThemeManager.getAccent("danger");
        Accent defaultAccent = ThemeManager.getDefaultAccent();

        int fadeWidth = Math.min(TEXT_FADE_WIDTH, textWidth);
        FadeMask mask = FadeMask.text(getX() - 2, textY - 2, textWidth, ITextRenderer.fontHeight + 2,
                resolvedTextX, nameWidth, fadeWidth, ThemeManager.getColor(ThemeColor.background));
        ctx.renderFaded(mask, () -> ctx.drawText(name, resolvedTextX, textY, textColor, Config.shadow));
        if (hint.isEmpty()) {
            setHint(name);
        }
        if (serverInfo != null) {
            if (serverInfo.getState() == InstanceState.RUNNING || serverInfo.getState() == InstanceState.STARTING) {
                accentType = niceAccent;
            } else if (serverInfo.getState() == InstanceState.CRASHED) {
                accentType = dangerAccent;
            } else {
                accentType = defaultAccent;
            }
        } else if (isCreateButton) {
            accentType = defaultAccent;
        }

        if (accentType == niceAccent) {
            ctx.drawAnimatedCornerGradient(x, y, width, height, niceAccent.getAccentColor());
        }
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (isMouseOver(event.x(), event.y())) {
            if (onClick != null) {
                onClick.accept(this, event.nativeButton());
            }
            return true;
        }
        return false;
    }

    public Instance getInstance() {
        return serverInfo;
    }

    private int getNameWidth(String name) {
        long revision = TextRenderer.metricsRevision();
        if (!Objects.equals(cachedName, name) || nameMetricsRevision != revision) {
            cachedName = name;
            nameMetricsRevision = revision;
            cachedNameWidth = tr.getWidth(name);
        }
        return cachedNameWidth;
    }

    public void setInstance(Instance serverInfo) {
        this.serverInfo = serverInfo;
        if (serverInfo != null && !isCreateButton) {
            setMessage(serverInfo.getName());
            setHint(serverInfo.getName());
        }
    }

    public boolean isCreateButton() {
        return isCreateButton;
    }

    public void setIcon(Identifier iconId) {
        releaseOwnedIcon();
        this.iconId = iconId;
        this.ownsIconId = false;
    }

    public void setGeneratedIcon(Identifier iconId) {
        releaseOwnedIcon();
        this.iconId = iconId;
        this.ownsIconId = iconId != null && iconId.type() == Identifier.Type.GENERATED_IMAGE;
        if (ownsIconId) {
            ResourceManager.getInstance().retainImage(iconId);
        }
    }

    public Identifier getIconId() {
        return iconId;
    }

    @Override
    public void cleanup() {
        releaseOwnedIcon();
        iconId = null;
    }

    private void releaseOwnedIcon() {
        if (ownsIconId && iconId != null) {
            ResourceManager.getInstance().releaseImage(iconId);
        }
        ownsIconId = false;
    }
}
