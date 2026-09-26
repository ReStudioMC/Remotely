package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.packcontent.GlyphPreviewAccess;
import redxax.oxy.remotely.packcontent.GlyphPreviewMode;
import redxax.oxy.remotely.packcontent.GlyphPreviewRenderer;
import redxax.oxy.remotely.ui.server.ServerTerminal;
import redxax.oxy.remotely.ui.server.ServerTerminalPlatform;
import restudio.rebase.ui.widgets.TerminalTextDecoration;
import restudio.rescreen.platform.input.ReMouseEvent;

import java.util.function.Supplier;

final class BrowserServerTerminalPlatform implements ServerTerminalPlatform {
    private final GlyphPreviewAccess access;
    private final Supplier<GlyphPreviewMode> mode;
    private GlyphPreviewRenderer renderer;

    BrowserServerTerminalPlatform(GlyphPreviewAccess access, Supplier<GlyphPreviewMode> mode) {
        this.access = access;
        this.mode = mode;
    }

    @Override
    public void configure(ServerTerminal terminal) {
        renderer = new GlyphPreviewRenderer(access, null, null);
        terminal.setTextDecoration(new TerminalTextDecoration() {
            @Override
            public Prepared prepare(String text) {
                return renderer.prepareTerminal(text, mode.get());
            }

            @Override
            public boolean draw(TerminalTextDecorationContext context) {
                return renderer.replaceTerminal(context, mode.get());
            }

            @Override
            public void afterDraw(TerminalTextDecorationOverlayContext context) {
                renderer.drawTerminalOverlay(context);
            }

            @Override
            public boolean mouseClicked(ReMouseEvent event) {
                return renderer.openHoveredAsset(event.x(), event.y(), event.nativeButton());
            }
        });
        access.refresh();
    }

    @Override
    public void detach(ServerTerminal terminal) {
        terminal.setTextDecoration(null);
        renderer = null;
    }
}
