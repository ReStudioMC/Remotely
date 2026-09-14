//#if MC >= 26.3
package redxax.oxy.remotely.host;

import com.mojang.blaze3d.platform.InputConstants;
import restudio.rescreen.platform.input.NativeInputMapper;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;

import java.util.Locale;

public final class MinecraftSdlInputMapper implements NativeInputMapper {
    private static final String KEY_PREFIX = "key.keyboard.";

    @Override
    public ReModifierState modifiers(int mods) {
        return new ReModifierState(
            (mods & InputConstants.MOD_SHIFT) != 0,
            (mods & InputConstants.MOD_CONTROL) != 0,
            (mods & InputConstants.MOD_ALT) != 0,
            (mods & InputConstants.MOD_SUPER) != 0,
            (mods & InputConstants.MOD_CAPS_LOCK) != 0,
            (mods & InputConstants.MOD_NUM_LOCK) != 0
        );
    }

    @Override
    public int nativeModifiers(ReModifierState modifiers) {
        if (modifiers == null) return 0;
        int nativeModifiers = 0;
        if (modifiers.shift()) nativeModifiers |= InputConstants.MOD_SHIFT;
        if (modifiers.control()) nativeModifiers |= InputConstants.MOD_CONTROL;
        if (modifiers.alt()) nativeModifiers |= InputConstants.MOD_ALT;
        if (modifiers.superKey()) nativeModifiers |= InputConstants.MOD_SUPER;
        if (modifiers.capsLock()) nativeModifiers |= InputConstants.MOD_CAPS_LOCK;
        if (modifiers.numLock()) nativeModifiers |= InputConstants.MOD_NUM_LOCK;
        return nativeModifiers;
    }

    @Override
    public ReKey key(int keyCode) {
        String name = InputConstants.Type.KEYBOARD.getOrCreate(keyCode).getName();
        if (!name.startsWith(KEY_PREFIX)) return ReKey.UNKNOWN;
        String keyName = name.substring(KEY_PREFIX.length());
        if (keyName.length() == 1 && Character.isDigit(keyName.charAt(0))) {
            return ReKey.valueOf("DIGIT_" + keyName);
        }
        if (keyName.startsWith("keypad.")) {
            keyName = "kp_" + keyName.substring("keypad.".length());
        }
        keyName = switch (keyName) {
            case "kp_period" -> "kp_decimal";
            case "left.win" -> "left_super";
            case "right.win" -> "right_super";
            case "application" -> "menu";
            default -> keyName;
        };
        try {
            return ReKey.valueOf(keyName.replace('.', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ReKey.UNKNOWN;
        }
    }

    @Override
    public ReKeyLocation location(int keyCode) {
        ReKey key = key(keyCode);
        if (key == ReKey.LEFT_SHIFT || key == ReKey.LEFT_CONTROL || key == ReKey.LEFT_ALT || key == ReKey.LEFT_SUPER) {
            return ReKeyLocation.LEFT;
        }
        if (key == ReKey.RIGHT_SHIFT || key == ReKey.RIGHT_CONTROL || key == ReKey.RIGHT_ALT || key == ReKey.RIGHT_SUPER) {
            return ReKeyLocation.RIGHT;
        }
        return key.name().startsWith("KP_") ? ReKeyLocation.NUMPAD : ReKeyLocation.STANDARD;
    }

    @Override
    public ReMouseButton mouseButton(int button) {
        if (button == ReMouseButton.LEFT.code()) return ReMouseButton.LEFT;
        if (button == ReMouseButton.RIGHT.code()) return ReMouseButton.RIGHT;
        if (button == ReMouseButton.MIDDLE.code()) return ReMouseButton.MIDDLE;
        if (button == ReMouseButton.BACK.code()) return ReMouseButton.BACK;
        if (button == ReMouseButton.FORWARD.code()) return ReMouseButton.FORWARD;
        return ReMouseButton.UNKNOWN;
    }

    @Override
    public int nativeKey(ReKey key) {
        if (key == null || key == ReKey.UNKNOWN || key == ReKey.F25) return -1;
        String keyName;
        if (key.name().startsWith("DIGIT_")) {
            keyName = key.name().substring("DIGIT_".length());
        } else if (key.name().startsWith("KP_")) {
            String suffix = key.name().substring("KP_".length()).toLowerCase(Locale.ROOT);
            keyName = "keypad." + ("decimal".equals(suffix) ? "period" : suffix);
        } else {
            keyName = switch (key) {
                case LEFT_SUPER -> "left.win";
                case RIGHT_SUPER -> "right.win";
                case MENU -> "application";
                default -> key.name().toLowerCase(Locale.ROOT).replace('_', '.');
            };
        }
        try {
            return InputConstants.getKey(KEY_PREFIX + keyName).getValue();
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
    }
}
//#endif
