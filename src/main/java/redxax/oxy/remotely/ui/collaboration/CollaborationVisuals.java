package redxax.oxy.remotely.ui.collaboration;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rescreen.theme.Accent;
import restudio.rescreen.platform.assets.ImageAssetRegistry;
import restudio.rescreen.platform.assets.PixelImage;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public final class CollaborationVisuals {
    private static final Map<Identifier, AvatarColor> AVATAR_COLORS = BrowserSafeState.map();
    private static final Map<Integer, Accent> ACCENTS = BrowserSafeState.map();
    private static ImageAssetRegistry avatarRegistry;

    public static int avatarColor(Identifier avatar, int fallback) {
        if (avatar == null) {
            return fallback;
        }
        ImageAssetRegistry registry = ScreenManager.getInstance().imageAssets();
        if (avatarRegistry != registry) {
            AVATAR_COLORS.clear();
            avatarRegistry = registry;
        }
        long revision = registry.imageRevision(avatar);
        AvatarColor cached = AVATAR_COLORS.get(avatar);
        if (cached == null || cached.revision() != revision) {
            cached = new AvatarColor(revision, dominantColor(registry.sampleImage(avatar, 32)));
            AVATAR_COLORS.put(avatar, cached);
        }
        return cached.color() == 0 ? fallback : cached.color();
    }

    private static int dominantColor(PixelImage image) {
        if (image == null) return 0;
        Map<Integer, Integer> counts = new HashMap<>();
        int dominant = 0;
        int maximum = 0;
        for (int index = 0; index < image.width() * image.height(); index++) {
            int pixel = image.argb(index);
            if ((pixel >>> 24) < 128) continue;
            int red = pixel >>> 16 & 0xFF;
            int green = pixel >>> 8 & 0xFF;
            int blue = pixel & 0xFF;
            int high = Math.max(red, Math.max(green, blue));
            int low = Math.min(red, Math.min(green, blue));
            if (high == 0 || (high - low) * 100 / high <= 20) continue;
            int color = 0xFF000000 | pixel & 0x00FFFFFF;
            int count = counts.merge(color, 1, Integer::sum);
            if (count > maximum) {
                dominant = color;
                maximum = count;
            }
        }
        return dominant;
    }

    private record AvatarColor(long revision, int color) {}

    public static int blend(Iterable<Integer> colors, int fallback) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        if (colors != null) {
            for (Integer color : colors) {
                if (color == null) {
                    continue;
                }
                red += color >> 16 & 0xFF;
                green += color >> 8 & 0xFF;
                blue += color & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return fallback;
        }
        int blendedRed = (int) (red / count);
        int blendedGreen = (int) (green / count);
        int blendedBlue = (int) (blue / count);
        return 0xFF000000 | blendedRed << 16 | blendedGreen << 8 | blendedBlue;
    }

    public static Accent accent(int color) {
        return ACCENTS.computeIfAbsent(color,
            value -> Accent.fromBaseColor("Collaborator " + Integer.toHexString(value), value));
    }

    private CollaborationVisuals() {
    }
}
