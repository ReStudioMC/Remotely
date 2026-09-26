package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.platform.Sha256;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.HostActionHandler;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.UiTasks;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MotdDesignerScreen extends FocusedJsonResourceDesignerScreen {
    private static final Map<String, Identifier> MOTD_ICON_CACHE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
            boolean remove = size() > 48;
            if (remove) {
                ScreenManager.getInstance().imageAssets().releaseImage(eldest.getValue());
            }
            return remove;
        }
    };

    public MotdDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.MOTD_PROFILE, resourceId, resource, serverId, parent);
    }

    @Override
    protected int previewLeftReserve() {
        return host != null ? host.studioContentBrowserWidth() : 0;
    }

    @Override
    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderMotdRealPreview(context, previewX, previewY, previewWidth, previewHeight, text, muted);
    }

    @Override
    protected List<String> editorFields() {
        return motdFields();
    }

    @Override
    protected void appendResourcePanelWidgets(List<AnimatedWidget> widgets, int rowWidth) {
        widgets.add(motdIconUploadRow(rowWidth));
    }

    @Override
    protected boolean remountPanelOnFieldReload() {
        return true;
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        return "playerCountMode".equals(field) ? List.of("real", "hidden", "fixed") : null;
    }

    @Override
    protected boolean customRebuildOnSelection(String field) {
        return "playerCountMode".equals(field);
    }

    @Override
    protected boolean customDropdownField(String field) {
        return "playerCountMode".equals(field);
    }

    @Override
    protected boolean customCodeField(String field) {
        return "motdText".equals(field);
    }

    @Override
    protected int customCodeFieldHeight(String field) {
        return "motdText".equals(field) ? 46 : -1;
    }

    @Override
    protected boolean handleSpecialJsonTextWrite(String field, String value) {
        if ("playerCountMode".equals(field) && !"fixed".equalsIgnoreCase(value)) {
            resource.remove("onlinePlayers");
            resource.remove("maxPlayers");
        }
        return false;
    }

    @Override
    protected void sanitizeLegacyResourceFields() {
        resource.remove("mode");
        resource.remove("frames");
        resource.remove("frameMillis");
        resource.remove("rotationMillis");
        resource.remove("line1Frames");
        resource.remove("line2Frames");
        resource.remove("versionText");
        resource.remove("protocolVersion");
        resource.remove("protocolText");
        resource.remove("match");
        resource.remove("samplePlayers");
        resource.remove("countMode");
        resource.remove("fakePlayers");
        resource.remove("overrideMaxPlayers");
        if (!"fixed".equalsIgnoreCase(jsonText("playerCountMode"))) {
            resource.remove("onlinePlayers");
            resource.remove("maxPlayers");
        }
    }

    @Override
    protected String resourceSummary() {
        return firstFilled(jsonPathText("motdText"), "Server List");
    }

    @Override
    protected String resourceDisplayName() {
        return "MOTD";
    }

    protected void renderMotdRealPreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int text, int muted) {
        int top = previewY + Math.max(20, previewHeight / 2 - 38);
        Identifier iconId = motdPreviewIconId();
        String[] lines = motdPreviewLines();
        if (iconId != null) {
            context.drawPixelArt(iconId, previewX + 12, top + 16, 32, 32);
        }
        drawFormattedLine(context, "Cool Server", previewX + 48, top + 16, text, true);
        drawFormattedLine(context, lines[0], previewX + 48, top + 28, text, true);
        drawFormattedLine(context, lines[1], previewX + 48, top + 37, muted, true);
        context.drawText(sampleCountText(), previewX + previewWidth - 74, top + 16, 0xFFFFFFFF, false);
    }

    protected Identifier motdPreviewIconId() {
        String hash = jsonText("iconHash");
        if (!hash.isBlank()) {
            synchronized (MOTD_ICON_CACHE) {
                Identifier cached = MOTD_ICON_CACHE.get(hash);
                if (cached != null) {
                    return cached;
                }
            }
        }
        String data = jsonText("iconData");
        if (data.isBlank()) {
            return Identifier.icon("fullPanel.png");
        }
        try {
            String encoded = stripImageDataPrefix(data);
            byte[] bytes = Base64.getDecoder().decode(encoded);
            String key = hash.isBlank() ? sha256(bytes) : hash;
            synchronized (MOTD_ICON_CACHE) {
                Identifier cached = MOTD_ICON_CACHE.get(key);
                if (cached != null) {
                    return cached;
                }
                String source = data.startsWith("data:") ? data : "data:image/png;base64," + encoded;
                Identifier id = ScreenManager.getInstance().imageAssets().registerRemoteImage(source);
                if (id == null) {
                    return Identifier.icon("fullPanel.png");
                }
                MOTD_ICON_CACHE.put(key, id);
                return id;
            }
        } catch (Exception ignored) {
            return Identifier.icon("fullPanel.png");
        }
    }

    protected AnimatedWidget motdIconUploadRow(int rowWidth) {
        MountableButtonWidget button = new MountableButtonWidget.Builder(jsonText("iconHash").isBlank() ? "Upload Icon" : "Replace Icon")
            .description(jsonText("iconHash").isBlank() ? "No Icon" : "Icon Ready")
            .onClick(this::pickMotdIcon)
            .build();
        button.setSize(rowWidth, 30);
        Identifier iconId = motdPreviewIconId();
        if (iconId != null) {
            button.setIcon(iconId);
        }
        ReSyncStudioPanelState.disableEntrance(button);
        return button;
    }

    protected void pickMotdIcon() {
        ScreenManager.getInstance().hostActions().pickImagesAsData(false, files -> {
            if (files == null || files.isEmpty()) {
                return;
            }
            HostActionHandler.SelectedFile file = files.getFirst();
            UiTasks.runBackground(() -> loadMotdIcon(file.dataUrl()));
        });
    }

    protected void loadMotdIcon(String dataUrl) {
        ScreenManager.getInstance().hostActions().resizeImageAsPng(dataUrl, 64, 64, this::applyMotdIcon);
    }

    private void applyMotdIcon(String dataUrl) {
        try {
            if (dataUrl == null || dataUrl.isBlank()) {
                throw new IllegalArgumentException("Invalid Image");
            }
            String encoded = stripImageDataPrefix(dataUrl);
            byte[] bytes = Base64.getDecoder().decode(encoded);
            String hash = sha256(bytes);
            synchronized (MOTD_ICON_CACHE) {
                if (!MOTD_ICON_CACHE.containsKey(hash)) {
                    String source = dataUrl.startsWith("data:") ? dataUrl : "data:image/png;base64," + encoded;
                    Identifier iconId = ScreenManager.getInstance().imageAssets().registerRemoteImage(source);
                    if (iconId != null) {
                        MOTD_ICON_CACHE.put(hash, iconId);
                    }
                }
            }
            ScreenManager.getInstance().execute(() -> {
                resource.addProperty("iconHash", hash);
                resource.addProperty("iconData", encoded);
                resource.addProperty("icon", "motd-icons/" + hash + ".png");
                reloadFields();
                save();
            });
        } catch (Exception e) {
            ScreenManager.getInstance().execute(() -> new Notification("Icon Upload Failed", e.getMessage(), Notification.Type.ERROR));
        }
    }

    protected String stripImageDataPrefix(String data) {
        int comma = data.indexOf(',');
        return data.startsWith("data:image/") && comma >= 0 ? data.substring(comma + 1) : data;
    }

    protected String sha256(byte[] bytes) {
        return Sha256.hex(bytes);
    }

    protected List<String> motdFields() {
        List<String> fields = new ArrayList<>(List.of("motdText", "priority", "playerCountMode"));
        if ("fixed".equalsIgnoreCase(jsonText("playerCountMode"))) {
            fields.add("onlinePlayers");
            fields.add("maxPlayers");
        }
        return fields;
    }

    protected String[] motdPreviewLines() {
        String line1 = jsonText("line1").isBlank() ? "<green>ReSync Server" : firstMotdLine(jsonText("line1"));
        String line2 = jsonText("line2").isBlank() ? "<gray>Flow Powered" : firstMotdLine(jsonText("line2"));
        return new String[]{line1, line2};
    }

    protected String sampleCountText() {
        String mode = jsonText("playerCountMode");
        if ("hidden".equalsIgnoreCase(mode)) {
            return "§8???";
        }
        String online = jsonText("onlinePlayers");
        String max = jsonText("maxPlayers");
        return ("§7" + (online.isBlank() ? "12" : online)) + "§8/§7" + (max.isBlank() ? "80" : max);
    }
}
