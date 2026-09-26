package redxax.oxy.remotely.adapters;

import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import redxax.oxy.remotely.rematrix.ReContext;
import redxax.oxy.remotely.rematrix.ReScissorStack;
import redxax.oxy.remotely.rematrix.mc.RematrixContext;
import restudio.rescreen.game.tooltip.MinecraftTooltip;
import restudio.rescreen.platform.ClipRect;
import restudio.rescreen.platform.FadeMask;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.IMatrixStack;
import restudio.rescreen.platform.TextDraw;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.ResourceManager;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class MinecraftDrawContextAdapter implements IDrawContext {
    private final ReContext ctx;
    private final IMatrixStack matrices;
    private final Deque<TransformState> transforms = new ArrayDeque<>();
    private Matrix4f transform = new Matrix4f();
    private boolean planarTransform = true;

    private record TransformState(Matrix4f matrix, boolean planar) {
    }

    public MinecraftDrawContextAdapter(@NotNull ReContext ctx) {
        this.ctx = ctx;
        this.matrices = new IMatrixStack() {
            @Override
            public void push() {
                ctx.matrices().push();
                transforms.push(new TransformState(new Matrix4f(transform), planarTransform));
            }

            @Override
            public void pop() {
                ctx.matrices().pop();
                if (!transforms.isEmpty()) {
                    TransformState state = transforms.pop();
                    transform = state.matrix();
                    planarTransform = state.planar();
                }
            }

            @Override
            public void translate(float x, float y, float z) {
                ctx.matrices().translate(x, y, z);
                transform.translate(x, y, z);
            }

            @Override
            public void scale(float x, float y, float z) {
                ctx.matrices().scale(x, y, z);
                transform.scale(x, y, z);
            }

            @Override
            public void rotate(float angle, float x, float y, float z) {
                ctx.matrices().rotate(angle, x, y, z);
                if (x != 0 || y != 0 || z != 1) {
                    planarTransform = false;
                } else {
                    transform.rotateZ((float) Math.toRadians(angle));
                }
            }

            @Override
            public void multiply(float angle) {
                ctx.matrices().multiply(angle);
                transform.rotateZ((float) Math.toRadians(angle));
            }
        };
    }

    @Override
    public IMatrixStack getMatrices() {
        return matrices;
    }

    @Override public void pushScissorState() { ctx.scissors().pushState(); }
    @Override public void popScissorState() { ctx.scissors().popState(); }
    @Override public void clearScissor() { ctx.scissors().clear(); }

    @Override
    public void enableScissor(float x1, float y1, float x2, float y2) {
        ctx.scissors().enable(x1, y1, x2, y2);
    }

    @Override
    public void renderFaded(FadeMask mask, Runnable content) {
        if (mask.width() == 0 || mask.height() == 0) return;
        pushScissorState();
        try {
            enableScissor(mask.x(), mask.y(), mask.x() + mask.width(), mask.y() + mask.height());
            content.run();
            if (mask.leftStrength() > 0f || mask.rightStrength() > 0f) {
                new FadeMask(mask.x(), mask.y(), mask.width(), mask.height(), mask.left(), mask.right(), 0, 0,
                    mask.leftStrength(), mask.rightStrength(), 0f, 0f, mask.background()).drawFallback(this);
            }
            if (mask.top() > 0 && mask.topStrength() > 0f || mask.bottom() > 0 && mask.bottomStrength() > 0f) {
                new FadeMask(mask.x(), mask.y() - 1, mask.width(), mask.height() + 2, 0, 0, mask.top() + 1, mask.bottom() + 1,
                    0f, 0f, mask.topStrength(), mask.bottomStrength(), mask.background()).drawFallback(this);
            }
        } finally {
            popScissorState();
        }
    }

    @Override
    public boolean scissorsContains(int i, int i1) {
        return ctx.scissors().contains(i, i1);
    }

    @Override
    public ClipRect visibleBounds() {
        if (!planarTransform || !(ctx instanceof RematrixContext mc)) {
            return null;
        }
        ClipRect bounds = mc.viewportBounds();
        if (bounds == null) {
            return null;
        }
        ReScissorStack.ScissorBox scissor = ctx.scissors().getCurrent();
        if (scissor != null) {
            bounds = bounds.intersect(new ClipRect(scissor.getX(), scissor.getY(), scissor.getX() + scissor.getWidth(), scissor.getY() + scissor.getHeight()));
        }
        return bounds.inverseTransform(transform.m00(), transform.m01(), transform.m10(), transform.m11(), transform.m30(), transform.m31());
    }

    @Override
    public void disableScissor() {
        ctx.scissors().disable();
    }

    @Override
    public boolean advanceRenderLayer() {
        if (ctx instanceof RematrixContext mc) {
            mc.advanceRenderLayer();
            return true;
        }
        return false;
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        if (ctx instanceof RematrixContext mc) {
            mc.fill(x1, y1, x2, y2, argb);
        }
    }

    @Override
    public void fillGradient(int i, int i1, int i2, int i3, int i4, int i5) {
        fillGradient(i, i1, i2, i3, i4, i5, false);
    }

    @Override
    public void fillGradient(int x1, int y1, int x2, int y2, int color1, int color2, boolean horizontal) {
        if (ctx instanceof RematrixContext mc) {
            mc.fillGradient(x1, y1, x2, y2, color1, color2, horizontal);
        }
    }

    @Override
    public void fillRoundedRectWithBorders(int x, int y, int width, int height, float roundness, int bgColor, int borderColor, int outerBorderColor) {
        if (ctx instanceof RematrixContext mc) {
            mc.fillRoundedRectWithBorders(x, y, width, height, roundness, bgColor, borderColor, outerBorderColor);
        }
    }
    @Override public void drawAnimatedCornerGradient(float v, float v1, float v2, float v3, int i) {}

    @Override
    public void drawText(String text, int x, int y, int color, boolean shadow) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawText(text, x, y, color, shadow);
        }
    }

    @Override
    public void drawRichText(String text, int x, int y, int color, boolean shadow) {
        if (TextRenderer.getTr() instanceof MinecraftTextRendererAdapter adapter) {
            adapter.drawRichText(this, text, x, y, color, shadow);
            return;
        }
        drawText(text, x, y, color, shadow);
    }

    @Override
    public void drawItem(Object item, int x, int y, int z) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawItem(item, x, y, z);
        }
    }

    @Override
    public void drawEntity(Object entity, int x, int y, int z, int size) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawEntity(entity, x, y, z, size);
        }
    }

    @Override
    public void drawPlayer(Object player, int x, int y, int z, int size) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawPlayer(player, x, y, z, size);
        }
    }

    @Override
    public void drawMinecraftTooltip(MinecraftTooltip tooltip, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        ctx.drawMinecraftTooltip(tooltip, mouseX, mouseY, screenWidth, screenHeight);
    }

    @Override
    public void drawMinecraftItemTooltip(Object item, MinecraftTooltip fallback, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        ctx.drawMinecraftItemTooltip(item, fallback, mouseX, mouseY, screenWidth, screenHeight);
    }

    @Override
    public void drawItemPreview(Object item, int x, int y, int z, float scale, float rotationX, float rotationY, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawItemPreview(item, x, y, z, scale, rotationX, rotationY, paused);
        }
    }

    @Override
    public void drawEntityPreview(Object entity, int x, int y, int z, int size, float yaw, float pitch, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawEntityPreview(entity, x, y, z, size, yaw, pitch, paused);
        }
    }

    @Override
    public void drawPlayerPreview(Object player, int x, int y, int z, int size, float yaw, float pitch, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawPlayerPreview(player, x, y, z, size, yaw, pitch, paused);
        }
    }

    @Override
    public void drawEntityMousePreview(Object entity, int x, int y, int z, int size, int mouseX, int mouseY, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawEntityMousePreview(entity, x, y, z, size, mouseX, mouseY, paused);
        }
    }

    @Override
    public void drawPlayerMousePreview(Object player, int x, int y, int z, int size, int mouseX, int mouseY, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawPlayerMousePreview(player, x, y, z, size, mouseX, mouseY, paused);
        }
    }

    @Override
    public void drawEntityRelativeMousePreview(Object entity, int x, int y, int z, int size, float relativeMouseX, float relativeMouseY, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawEntityRelativeMousePreview(entity, x, y, z, size, relativeMouseX, relativeMouseY, paused);
        }
    }

    @Override
    public void drawPlayerRelativeMousePreview(Object player, int x, int y, int z, int size, float relativeMouseX, float relativeMouseY, boolean paused) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawPlayerRelativeMousePreview(player, x, y, z, size, relativeMouseX, relativeMouseY, paused);
        }
    }

    @Override
    public void drawStyledText(Object text, int x, int y, int color, boolean shadow) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawStyledText(text, x, y, color, shadow);
        }
    }

    @Override
    public void drawTextBatch(List<TextDraw> draws) {
        if (draws == null || draws.isEmpty()) return;
        for (TextDraw draw : draws) {
            if (draw == null) continue;
            if (draw.text() instanceof String text) {
                drawText(text, draw.x(), draw.y(), draw.color(), draw.shadow());
            } else {
                drawStyledText(draw.text(), draw.x(), draw.y(), draw.color(), draw.shadow());
            }
        }
    }

    private void drawBufferedImage(BufferedImage image, float x, float y, float width, float height) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawBufferedImage(image, x, y, width, height);
        }
    }

    private void drawPixelArt(BufferedImage bufferedImage, float x, float y, float width, float height) {
        drawBufferedImage(bufferedImage, x, y, width, height);
    }

    @Override
    public void drawBufferedImage(Identifier identifier, float x, float y, float width, float height) {
        drawBufferedImage(ResourceManager.getInstance().resolveImage(identifier), x, y, width, height);
    }

    @Override
    public void drawPixelArt(Identifier identifier, float x, float y, float width, float height) {
        drawBufferedImage(identifier, x, y, width, height);
    }

    @Override
    public void drawImageRegion(Identifier identifier, float x, float y, float width, float height, float sourceX, float sourceY, float sourceWidth, float sourceHeight, boolean pixelated, float alpha) {
        BufferedImage image = ResourceManager.getInstance().resolveImage(identifier);
        if (image == null) return;
        int sx = Math.max(0, Math.min(image.getWidth() - 1, Math.round(sourceX)));
        int sy = Math.max(0, Math.min(image.getHeight() - 1, Math.round(sourceY)));
        int sw = Math.max(1, Math.min(image.getWidth() - sx, Math.round(sourceWidth)));
        int sh = Math.max(1, Math.min(image.getHeight() - sy, Math.round(sourceHeight)));
        if (ctx instanceof RematrixContext mc) {
            var handle = ctx.textures().getTexture(image);
            if (mc.drawNativeTexture(handle.getId(), x, y, width, height, sx, sy, sw, sh, handle.getWidth(), handle.getHeight())) {
                return;
            }
        }
        drawBufferedImage(image.getSubimage(sx, sy, sw, sh), x, y, width, height);
    }

    @Override
    public boolean drawNativeTexture(Object texture, float x, float y, float width, float height, float u, float v, float regionWidth, float regionHeight, float textureWidth, float textureHeight) {
        if (ctx instanceof RematrixContext mc) {
            return mc.drawNativeTexture(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
        }
        return false;
    }

    @Override
    public void drawInvertedRect(float x1, float y1, float x2, float y2) {
        if (ctx instanceof RematrixContext mc) {
            mc.drawInvertedRect(x1, y1, x2, y2);
        }
    }
}
