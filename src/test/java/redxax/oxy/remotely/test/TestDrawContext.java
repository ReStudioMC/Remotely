package redxax.oxy.remotely.test;

import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.IMatrixStack;
import restudio.rescreen.util.Identifier;

public final class TestDrawContext implements IDrawContext {
    private final IMatrixStack matrices = new IMatrixStack() {
        @Override
        public void push() {
        }

        @Override
        public void pop() {
        }

        @Override
        public void translate(float x, float y, float z) {
        }

        @Override
        public void scale(float x, float y, float z) {
        }

        @Override
        public void rotate(float angle, float x, float y, float z) {
        }

        @Override
        public void multiply(float angle) {
        }
    };

    @Override
    public IMatrixStack getMatrices() {
        return matrices;
    }

    @Override
    public void pushScissorState() {
    }

    @Override
    public void popScissorState() {
    }

    @Override
    public void clearScissor() {
    }

    @Override
    public void enableScissor(float x1, float y1, float x2, float y2) {
    }

    @Override
    public boolean scissorsContains(int x, int y) {
        return true;
    }

    @Override
    public void disableScissor() {
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
    }

    @Override
    public void fillGradient(int x1, int y1, int x2, int y2, int color1, int color2) {
    }

    @Override
    public void fillGradient(int x1, int y1, int x2, int y2, int color1, int color2, boolean horizontal) {
    }

    @Override
    public void fillRoundedRectWithBorders(int x, int y, int width, int height, float roundness, int bgColor,
                                           int borderColor, int currentOuterBorderColor) {
    }

    @Override
    public void drawAnimatedCornerGradient(float x, float y, float width, float height, int color) {
    }

    @Override
    public void drawText(String text, int x, int y, int color, boolean shadow) {
    }

    @Override
    public void drawItem(Object item, int x, int y, int z) {
    }

    @Override
    public void drawBufferedImage(Identifier id, float x, float y, float width, float height) {
    }

    @Override
    public void drawPixelArt(Identifier id, float x, float y, float width, float height) {
    }

    @Override
    public void drawInvertedRect(float x1, float y1, float x2, float y2) {
    }
}
