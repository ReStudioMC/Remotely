package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import restudio.rebase.settings.controllers.MinecraftAssetsSettingsProvider;
import restudio.rebase.settings.controllers.SettingsActionCapability;
import restudio.rescreen.ui.rescreen.ReScreen;

public final class BrowserMinecraftAssetsSettingsProvider implements MinecraftAssetsSettingsProvider {
    private static final String MANAGED = "Minecraft Assets Are Provided By ReStudio";
    private final Settings settings;

    public BrowserMinecraftAssetsSettingsProvider(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public boolean installed() {
        return !version().isBlank();
    }

    @Override
    public String statusBadge() {
        return installed() ? "Ready" : "Unavailable";
    }

    @Override
    public String statusDescription() {
        String active = version();
        return active.isBlank() ? "Shared Minecraft Assets Are Unavailable" : "Minecraft " + active + " Assets Are Ready";
    }

    @Override
    public SettingsActionCapability installCapability() {
        return SettingsActionCapability.unavailable("settings.minecraft-assets.install", MANAGED);
    }

    @Override
    public SettingsActionCapability openCapability() {
        return SettingsActionCapability.unavailable("settings.minecraft-assets.open", "The Browser Has No Local Assets Directory");
    }

    @Override
    public SettingsActionCapability removeCapability() {
        return SettingsActionCapability.unavailable("settings.minecraft-assets.remove", MANAGED);
    }

    @Override
    public void install(ReScreen screen) {
        throw new UnsupportedOperationException(MANAGED);
    }

    @Override
    public void open() {
        throw new UnsupportedOperationException("The Browser Has No Local Assets Directory");
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException(MANAGED);
    }

    @JSBody(script = "const assets = window.__reScreenMinecraftAssets; return assets ? assets.version : '';")
    private static native String version();
}
