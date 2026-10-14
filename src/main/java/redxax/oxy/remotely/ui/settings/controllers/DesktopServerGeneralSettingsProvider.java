package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rebase.instance.Instance;

public final class DesktopServerGeneralSettingsProvider implements ServerGeneralSettingsProvider {
    private final Instance instance;

    public DesktopServerGeneralSettingsProvider(Instance instance) {
        this.instance = instance;
    }

    @Override public String name() { return instance.getName(); }
    @Override public void name(String value) { instance.setName(value); }
    @Override public String property(String key, String fallback) { return instance.getSettings().getProperty(key, fallback); }
    @Override public void setProperty(String key, String value) { instance.getSettings().setProperty(key, value); }
    @Override public String serverProperty(String key, String fallback) { return instance.getServerProperties().getProperty(key, fallback); }
    @Override public void setServerProperty(String key, String value) { instance.getServerProperties().setProperty(key, value); }
}
