package redxax.oxy.remotely.adapters;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
//#if MC >= 1.21.9 || MC >= 26.1
import net.minecraft.network.chat.FontDescription;
//#endif
import net.minecraft.network.chat.Style;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
//#if MC >= 1.21.11 || MC >= 26.1
import net.minecraft.resources.Identifier;
//#endif
//#if MC < 1.21.11 && MC < 26.1
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import restudio.rescreen.debug.RenderProfiler;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.render.TextRenderer;

import java.util.LinkedHashMap;
import java.util.Map;

public class MinecraftTextRendererAdapter implements ITextRenderer {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_TEXT = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final int WIDTH_CACHE_LIMIT = 4_096;
    private final Map<WidthKey, Integer> widthCache = new LinkedHashMap<>(256, 0.75f, true);
    private long widthRevision = -1;
    private Object widthFont;

    public MinecraftTextRendererAdapter() {}

    @Override
    public void draw(IDrawContext ctx, String text, int x, int y, int color, boolean shadow) {
        if (ctx == null || !(RenderProfiler.unwrap(ctx) instanceof MinecraftDrawContextAdapter mcCtx)) return;
        mcCtx.drawText(text, x, y, color, shadow);
    }

    @Override
    public void drawStyled(IDrawContext ctx, Object text, int x, int y, int color, boolean shadow) {
        if (ctx == null || !(RenderProfiler.unwrap(ctx) instanceof MinecraftDrawContextAdapter mcCtx)) return;
        mcCtx.drawStyledText(text, x, y, color, shadow);
    }

    @Override
    public void drawRichText(String text, int x, int y, int color, boolean shadow) {
        drawRichText(null, text, x, y, color, shadow);
    }

    public void drawRichText(IDrawContext ctx, String text, int x, int y, int color, boolean shadow) {
        if (ctx == null || !(RenderProfiler.unwrap(ctx) instanceof MinecraftDrawContextAdapter mcCtx)) return;
        try {
            mcCtx.drawStyledText(toNative(richText(text), Style.EMPTY), x, y, color, shadow);
        } catch (Exception ignored) {
            mcCtx.drawText(text, x, y, color, shadow);
        }
    }

    private net.kyori.adventure.text.Component richText(String text) {
        String normalized = text == null ? "" : text.replace('§', '&');
        if (normalized.indexOf('<') >= 0 && normalized.indexOf('>') >= 0) {
            try {
                return MINI_MESSAGE.deserialize(normalized);
            } catch (Exception ignored) {
            }
        }
        return LEGACY_TEXT.deserialize(normalized);
    }

    private Component toNative(net.kyori.adventure.text.Component component, Style inheritedStyle) {
        Style style = nativeStyle(component, inheritedStyle);
        net.minecraft.network.chat.MutableComponent nativeComponent = Component.literal(component instanceof TextComponent textComponent ? textComponent.content() : "").setStyle(style);
        for (net.kyori.adventure.text.Component child : component.children()) {
            nativeComponent.append(toNative(child, style));
        }
        return nativeComponent;
    }

    private Style nativeStyle(net.kyori.adventure.text.Component component, Style inheritedStyle) {
        net.kyori.adventure.text.format.Style source = component.style();
        Style style = source.color() != null ? inheritedStyle.withColor(source.color().value()) : inheritedStyle;
        style = decoration(source, TextDecoration.BOLD, style, Decoration.BOLD);
        style = decoration(source, TextDecoration.ITALIC, style, Decoration.ITALIC);
        style = decoration(source, TextDecoration.UNDERLINED, style, Decoration.UNDERLINED);
        style = decoration(source, TextDecoration.STRIKETHROUGH, style, Decoration.STRIKETHROUGH);
        return decoration(source, TextDecoration.OBFUSCATED, style, Decoration.OBFUSCATED);
    }

    private Style decoration(net.kyori.adventure.text.format.Style source, TextDecoration decoration, Style style, Decoration nativeDecoration) {
        TextDecoration.State state = source.decoration(decoration);
        if (state == TextDecoration.State.NOT_SET) {
            return style;
        }
        boolean enabled = state == TextDecoration.State.TRUE;
        return switch (nativeDecoration) {
            case BOLD -> style.withBold(enabled);
            case ITALIC -> style.withItalic(enabled);
            case UNDERLINED -> style.withUnderlined(enabled);
            case STRIKETHROUGH -> style.withStrikethrough(enabled);
            case OBFUSCATED -> style.withObfuscated(enabled);
        };
    }

    private enum Decoration {
        BOLD,
        ITALIC,
        UNDERLINED,
        STRIKETHROUGH,
        OBFUSCATED
    }

    @Override
    public void draw(String s, int i, int i1, int i2, boolean b) {
        draw(null, s, i, i1, i2, b);
    }

    @Override
    public int getWidth(String text) {
        return cachedWidth(text, null);
    }

    @Override
    public int getWidth(String s, TextRenderer.FontStyle fontStyle) {
        return getWidth(s);
    }

    @Override
    public int getWidth(String text, Object font) {
        return cachedWidth(text, font);
    }

    private int cachedWidth(String text, Object font) {
        if (text == null || text.isEmpty()) return 0;
        long revision = TextRenderer.metricsRevision();
        Object currentFont = Minecraft.getInstance().font;
        if (revision != widthRevision || currentFont != widthFont) {
            widthCache.clear();
            widthRevision = revision;
            widthFont = currentFont;
        }
        WidthKey key = new WidthKey(text, font);
        Integer cached = widthCache.get(key);
        if (cached != null) return cached;
        int width = nativeWidth(text, font);
        widthCache.put(key, width);
        if (widthCache.size() > WIDTH_CACHE_LIMIT) widthCache.remove(widthCache.keySet().iterator().next());
        return width;
    }

    private int nativeWidth(String text, Object font) {
        //#if MC >= 1.21.11 || MC >= 26.1
        if (font instanceof Identifier rl) {
        //#endif
        //#if MC < 1.21.11 && MC < 26.1
        //$$ if (font instanceof ResourceLocation rl) {
        //#endif
            Component component = Component.literal(text).setStyle(Style.EMPTY.withFont(
                //#if MC >= 1.21.9 || MC >= 26.1
                new FontDescription.Resource(rl)
                //#else
                //$$ rl
                //#endif
            ));
            return Minecraft.getInstance().font.width(component);
        }
        return Minecraft.getInstance().font.width(text);
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        int textWidth = getWidth(text);
        if (textWidth <= maxWidth) {
            return text;
        }
        StringBuilder trimmed = new StringBuilder();
        for (char c : text.toCharArray()) {
            trimmed.append(c);
            if (getWidth(trimmed.toString()) > maxWidth) {
                trimmed.deleteCharAt(trimmed.length() - 1);
                break;
            }
        }
        return trimmed.toString();
    }

    private record WidthKey(String text, Object font) {
    }
}
