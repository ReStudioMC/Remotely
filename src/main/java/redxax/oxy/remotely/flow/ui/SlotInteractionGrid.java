package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rescreen.platform.IDrawContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SlotInteractionGrid {
    private static final Map<Long, Long> slotRevealStarts = new HashMap<>();
    private static final Map<Integer, Long> groupRevealStarts = new HashMap<>();
    private static final BrowserSafeState.IntegerValue animationScopes = new BrowserSafeState.IntegerValue();
    private static final long SLOT_REVEAL_NANOS = 220_000_000L;
    private static final long RIPPLE_NANOS_PER_PIXEL = 300_000L;
    private static final long REVEAL_STATE_RETENTION_NANOS = 2_000_000_000L;

    enum HighlightReveal {
        GROUP,
        RIPPLE
    }

    private SlotInteractionGrid() {
    }

    record SlotRect(int slot, int x, int y, int size) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + size && mouseY >= y && mouseY <= y + size;
        }

        int centerX() {
            return x + size / 2;
        }

        int centerY() {
            return y + size / 2;
        }
    }

    static int hitSlot(Collection<SlotRect> slots, int mouseX, int mouseY) {
        for (SlotRect slot : slots) {
            if (slot.contains(mouseX, mouseY)) {
                return slot.slot();
            }
        }
        return -1;
    }

    static Stroke beginStroke(Collection<SlotRect> slots, int mouseX, int mouseY) {
        Stroke stroke = new Stroke(slots);
        stroke.moveTo(mouseX, mouseY);
        return stroke;
    }

    static List<Integer> slotRange(int startSlot, int endSlot, int columns, int rows) {
        if (startSlot < 0 || endSlot < 0 || columns <= 0 || rows <= 0) {
            return List.of();
        }
        int startRow = startSlot / columns;
        int startCol = startSlot % columns;
        int endRow = endSlot / columns;
        int endCol = endSlot % columns;
        int minRow = Math.min(startRow, endRow);
        int maxRow = Math.max(startRow, endRow);
        int minCol = Math.min(startCol, endCol);
        int maxCol = Math.max(startCol, endCol);
        if (maxRow >= rows) {
            return List.of();
        }
        List<Integer> slots = new ArrayList<>();
        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                slots.add(row * columns + col);
            }
        }
        return slots;
    }

    static void drawHighlight(IDrawContext context, int x, int y, int width, int height, int color, boolean outline) {
        drawHighlight(context, x, y, width, height, color, outline, (x * 31 + y) * 31 + width * 17 + height);
    }

    static void drawHighlight(IDrawContext context, int x, int y, int width, int height, int color, boolean outline, int animationKey) {
        drawHighlights(context, List.of(new SlotRect(0, x, y, Math.min(width, height))), color, outline, animationKey);
    }

    static int animationScope() {
        return animationScopes.incrementAndGet();
    }

    static int animationKey(String namespace, int scope, int nonce) {
        return (namespace + ":" + scope + ":" + nonce).hashCode();
    }

    static void drawHighlights(IDrawContext context, Collection<SlotRect> slots, int color, boolean outline, int animationKey) {
        drawHighlights(context, slots, color, outline, animationKey, Integer.MIN_VALUE, Integer.MIN_VALUE, HighlightReveal.GROUP);
    }

    static void drawHighlights(IDrawContext context, Collection<SlotRect> slots, int color, boolean outline, int animationKey, int originX, int originY) {
        drawHighlights(context, slots, color, outline, animationKey, originX, originY, HighlightReveal.GROUP);
    }

    static void drawHighlights(IDrawContext context, Collection<SlotRect> slots, int color, boolean outline, int animationKey, int originX, int originY, HighlightReveal reveal) {
        if (color == 0) {
            return;
        }
        List<SlotRect> rects = slots != null ? new ArrayList<>(slots) : List.of();
        if (rects.isEmpty()) {
            return;
        }
        rects.sort((first, second) -> Integer.compare(first.slot(), second.slot()));
        long now = System.nanoTime();
        pruneRevealState(now);
        int minX = rects.stream().mapToInt(SlotRect::x).min().orElse(0);
        int minY = rects.stream().mapToInt(SlotRect::y).min().orElse(0);
        int maxX = rects.stream().mapToInt(rect -> rect.x() + rect.size()).max().orElse(0);
        int maxY = rects.stream().mapToInt(rect -> rect.y() + rect.size()).max().orElse(0);
        int revealOriginX = originX != Integer.MIN_VALUE ? originX : (minX + maxX) / 2;
        int revealOriginY = originY != Integer.MIN_VALUE ? originY : (minY + maxY) / 2;
        if (reveal == HighlightReveal.RIPPLE) {
            drawRippleHighlights(context, rects, color, outline, animationKey, revealOriginX, revealOriginY, now);
            return;
        }
        drawGroupHighlights(context, rects, color, outline, animationKey, revealOriginX, revealOriginY, minX, minY, maxX, maxY, now);
    }

    static void drawCollaborativeHighlights(IDrawContext context, Collection<SlotRect> slots, Collection<Integer> localPreview,
                                             Collection<Integer> localSelection, int previewColor, int selectionColor,
                                             SlotCollaborationAuthority collaboration, int animationScope, String namespace,
                                             int originX, int originY) {
        Set<Integer> preview = localPreview != null ? Set.copyOf(localPreview) : Set.of();
        Set<Integer> selected = localSelection != null ? Set.copyOf(localSelection) : Set.of();
        Map<Integer, Integer> remote = collaboration != null ? collaboration.remoteColors() : Map.of();
        Map<Integer, List<SlotRect>> remoteRects = new LinkedHashMap<>();
        List<SlotRect> previewRects = new ArrayList<>();
        List<SlotRect> selectedRects = new ArrayList<>();
        for (SlotRect rect : slots != null ? slots : List.<SlotRect>of()) {
            if (preview.contains(rect.slot())) {
                previewRects.add(rect);
            } else if (selected.contains(rect.slot())) {
                selectedRects.add(rect);
            } else if (remote.containsKey(rect.slot())) {
                remoteRects.computeIfAbsent(remote.get(rect.slot()), ignored -> new ArrayList<>()).add(rect);
            }
        }
        drawHighlights(context, previewRects, previewColor, false, animationKey(namespace + "_preview", animationScope, preview.hashCode()), originX, originY, HighlightReveal.RIPPLE);
        drawHighlights(context, selectedRects, selectionColor, true, animationKey(namespace + "_selected", animationScope, selected.hashCode()), originX, originY, HighlightReveal.GROUP);
        remoteRects.forEach((color, rects) -> drawHighlights(context, rects, color, true,
            animationKey(namespace + "_remote", animationScope, 31 * color + rects.hashCode()), originX, originY, HighlightReveal.GROUP));
    }

    private static void drawGroupHighlights(IDrawContext context, List<SlotRect> rects, int color, boolean outline, int animationKey, int originX, int originY, int minX, int minY, int maxX, int maxY, long now) {
        float progress = groupRevealProgress(animationKey, now);
        int clipLeft = Math.round(originX + (minX - originX) * progress);
        int clipTop = Math.round(originY + (minY - originY) * progress);
        int clipRight = Math.round(originX + (maxX - originX) * progress);
        int clipBottom = Math.round(originY + (maxY - originY) * progress);
        int fillAlpha = Math.round(0x55 * progress);
        int fill = (color & 0x00FFFFFF) | (fillAlpha << 24);
        int inset = Math.round((1f - easeOutBack(progress)) * 2f);
        for (SlotRect rect : rects) {
            int left = Math.max(rect.x() + inset, clipLeft);
            int top = Math.max(rect.y() + inset, clipTop);
            int right = Math.min(rect.x() + rect.size() - inset, clipRight);
            int bottom = Math.min(rect.y() + rect.size() - inset, clipBottom);
            if (right > left && bottom > top) {
                context.fill(left, top, right, bottom, fill);
            }
        }
        if (outline) {
            float borderStrength = progress >= 1f ? selectionPulse(now) : progress;
            int borderAlpha = Math.round(0xCC * borderStrength);
            int border = (color & 0x00FFFFFF) | (borderAlpha << 24);
            for (SlotRect rect : rects) {
                drawOuterBorders(context, rects, rect, inset, border, clipLeft, clipTop, clipRight, clipBottom);
            }
        }
    }

    private static void drawRippleHighlights(IDrawContext context, List<SlotRect> rects, int color, boolean outline, int animationKey, int originX, int originY, long now) {
        for (SlotRect rect : rects) {
            float progress = slotRevealProgress(animationKey, rect, originX, originY, now);
            if (progress <= 0f) {
                continue;
            }
            int inset = Math.round((1f - easeOutBack(progress)) * rect.size() * 0.42f);
            int left = rect.x() + inset;
            int top = rect.y() + inset;
            int right = rect.x() + rect.size() - inset;
            int bottom = rect.y() + rect.size() - inset;
            if (right <= left || bottom <= top) {
                continue;
            }
            int clipLeft = Math.round(rect.centerX() + (left - rect.centerX()) * progress);
            int clipTop = Math.round(rect.centerY() + (top - rect.centerY()) * progress);
            int clipRight = Math.round(rect.centerX() + (right - rect.centerX()) * progress);
            int clipBottom = Math.round(rect.centerY() + (bottom - rect.centerY()) * progress);
            int fillAlpha = Math.round(0x55 * progress);
            int fill = (color & 0x00FFFFFF) | (fillAlpha << 24);
            fillClipped(context, left, top, right, bottom, fill, clipLeft, clipTop, clipRight, clipBottom);
        }
        if (outline) {
            for (SlotRect rect : rects) {
                float progress = slotRevealProgress(animationKey, rect, originX, originY, now);
                if (progress <= 0f) {
                    continue;
                }
                int inset = Math.round((1f - easeOutBack(progress)) * rect.size() * 0.42f);
                int left = rect.x() + inset;
                int top = rect.y() + inset;
                int right = rect.x() + rect.size() - inset;
                int bottom = rect.y() + rect.size() - inset;
                if (right <= left || bottom <= top) {
                    continue;
                }
                int clipLeft = Math.round(rect.centerX() + (left - rect.centerX()) * progress);
                int clipTop = Math.round(rect.centerY() + (top - rect.centerY()) * progress);
                int clipRight = Math.round(rect.centerX() + (right - rect.centerX()) * progress);
                int clipBottom = Math.round(rect.centerY() + (bottom - rect.centerY()) * progress);
                int borderAlpha = Math.round(0xCC * progress);
                int border = (color & 0x00FFFFFF) | (borderAlpha << 24);
                drawOuterBorders(context, rects, rect, inset, border, clipLeft, clipTop, clipRight, clipBottom);
            }
        }
    }

    private static float groupRevealProgress(int animationKey, long now) {
        groupRevealStarts.putIfAbsent(animationKey, now);
        long elapsed = now - groupRevealStarts.get(animationKey);
        float raw = Math.min(1f, elapsed / (float) SLOT_REVEAL_NANOS);
        return smoothStep(raw);
    }

    private static float slotRevealProgress(int animationKey, SlotRect rect, int originX, int originY, long now) {
        long revealKey = revealKey(animationKey, rect.slot());
        long rippleDelay = (long) (Math.hypot(rect.centerX() - originX, rect.centerY() - originY) * RIPPLE_NANOS_PER_PIXEL);
        slotRevealStarts.putIfAbsent(revealKey, now + rippleDelay);
        long revealStart = slotRevealStarts.get(revealKey);
        float raw = Math.min(1f, Math.max(0f, (now - revealStart) / (float) SLOT_REVEAL_NANOS));
        return smoothStep(raw);
    }

    private static long revealKey(int animationKey, int slotId) {
        return ((long) animationKey << 32) | (slotId & 0xFFFFFFFFL);
    }

    private static void pruneRevealState(long now) {
        if (slotRevealStarts.size() > 2048) {
            slotRevealStarts.entrySet().removeIf(entry -> now - entry.getValue() > REVEAL_STATE_RETENTION_NANOS);
        }
        if (groupRevealStarts.size() > 512) {
            groupRevealStarts.entrySet().removeIf(entry -> now - entry.getValue() > REVEAL_STATE_RETENTION_NANOS);
        }
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float easeOutBack(float t) {
        float overshoot = 1.18f;
        float inv = t - 1f;
        return 1f + (overshoot + 1f) * inv * inv * inv + overshoot * inv * inv;
    }

    private static float selectionPulse(long now) {
        float wave = (float) Math.sin(now * 2.4e-9);
        return 0.88f + 0.12f * (0.5f + 0.5f * wave);
    }

    private static void drawOuterBorders(IDrawContext context, List<SlotRect> rects, SlotRect source, int inset, int border, int clipLeft, int clipTop, int clipRight, int clipBottom) {
        int left = source.x() + inset;
        int top = source.y() + inset;
        int right = source.x() + source.size() - inset;
        int bottom = source.y() + source.size() - inset;
        if (right <= left || bottom <= top) {
            return;
        }
        if (!hasNeighbor(rects, source, -1, 0)) {
            fillClipped(context, left, top, left + 1, bottom, border, clipLeft, clipTop, clipRight, clipBottom);
        }
        if (!hasNeighbor(rects, source, 1, 0)) {
            fillClipped(context, right - 1, top, right, bottom, border, clipLeft, clipTop, clipRight, clipBottom);
        }
        if (!hasNeighbor(rects, source, 0, -1)) {
            fillClipped(context, left, top, right, top + 1, border, clipLeft, clipTop, clipRight, clipBottom);
        }
        if (!hasNeighbor(rects, source, 0, 1)) {
            fillClipped(context, left, bottom - 1, right, bottom, border, clipLeft, clipTop, clipRight, clipBottom);
        }
    }

    private static void fillClipped(IDrawContext context, int left, int top, int right, int bottom, int color, int clipLeft, int clipTop, int clipRight, int clipBottom) {
        int clippedLeft = Math.max(left, clipLeft);
        int clippedTop = Math.max(top, clipTop);
        int clippedRight = Math.min(right, clipRight);
        int clippedBottom = Math.min(bottom, clipBottom);
        if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
            context.fill(clippedLeft, clippedTop, clippedRight, clippedBottom, color);
        }
    }

    private static boolean hasNeighbor(List<SlotRect> rects, SlotRect source, int dx, int dy) {
        int x = source.x() + dx * source.size();
        int y = source.y() + dy * source.size();
        for (SlotRect rect : rects) {
            if (rect != source && rect.x() == x && rect.y() == y && rect.size() == source.size()) {
                return true;
            }
        }
        return false;
    }

    static final class Stroke {
        private final List<SlotRect> slots;
        private final Set<Integer> touchedSlots = new LinkedHashSet<>();
        private int lastX = Integer.MIN_VALUE;
        private int lastY = Integer.MIN_VALUE;

        private Stroke(Collection<SlotRect> slots) {
            this.slots = slots != null ? new ArrayList<>(slots) : List.of();
        }

        void moveTo(int mouseX, int mouseY) {
            if (lastX == Integer.MIN_VALUE || lastY == Integer.MIN_VALUE) {
                addHit(mouseX, mouseY);
                lastX = mouseX;
                lastY = mouseY;
                return;
            }
            int dx = mouseX - lastX;
            int dy = mouseY - lastY;
            int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)) / Math.max(1, smallestSlotSize() / 2));
            for (int i = 1; i <= steps; i++) {
                int x = lastX + Math.round(dx * (i / (float) steps));
                int y = lastY + Math.round(dy * (i / (float) steps));
                addHit(x, y);
            }
            lastX = mouseX;
            lastY = mouseY;
        }

        List<Integer> slots() {
            return new ArrayList<>(touchedSlots);
        }

        boolean isEmpty() {
            return touchedSlots.isEmpty();
        }

        private void addHit(int mouseX, int mouseY) {
            int slot = hitSlot(slots, mouseX, mouseY);
            if (slot >= 0) {
                touchedSlots.add(slot);
            }
        }

        private int smallestSlotSize() {
            int size = Integer.MAX_VALUE;
            for (SlotRect slot : slots) {
                if (slot.size() > 0) {
                    size = Math.min(size, slot.size());
                }
            }
            return size == Integer.MAX_VALUE ? 16 : size;
        }
    }
}
