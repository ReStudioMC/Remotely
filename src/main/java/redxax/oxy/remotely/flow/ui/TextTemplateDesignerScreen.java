package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextTemplateDesignerScreen extends FocusedJsonResourceDesignerScreen {
    private final CodeEditorWidget contentEditor;
    private boolean syncingEditor;
    private int workspaceWidth;
    private int workspaceHeight;

    public TextTemplateDesignerScreen(StudioScreen owner, String resourceId, JsonObject resource, String serverId, Object parent) {
        super(owner, ReSyncResourceDragPayload.TEXT_TEMPLATE, resourceId, resource, serverId, parent);
        if (jsonText("kind").isBlank()) {
            this.resource.addProperty("kind", "animation");
        }
        contentEditor = new CodeEditorWidget(0, 0, 320, 180);
        contentEditor.setShowLineNumbers(true);
        contentEditor.onChange = value -> writeCentralContent(contentEditor.getText());
        syncCentralEditor();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        workspaceWidth = width;
        workspaceHeight = height;
        layoutContentEditor();
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        layoutContentEditor();
        contentEditor.render(context, mouseX, mouseY, delta);
    }

    @Override
    protected List<String> editorFields() {
        return textTemplateFields();
    }

    @Override
    protected boolean customDropdownField(String field) {
        return "kind".equals(field) || "mode".equals(field);
    }

    @Override
    protected boolean customRebuildOnSelection(String field) {
        return "kind".equals(field) || "mode".equals(field);
    }

    @Override
    protected List<String> modeOptions() {
        return List.of("frames", "typing", "scroll", "bounce", "blink", "pulse", "rainbow", "wave", "wipe", "sparkle");
    }

    @Override
    protected List<String> customSelectorOptions(String field) {
        return switch (field) {
            case "kind" -> List.of("animation", "list", "map");
            case "mode" -> modeOptions();
            default -> null;
        };
    }

    @Override
    protected boolean customCodeField(String field) {
        return "colorsText".equals(field);
    }

    @Override
    protected int customCodeFieldHeight(String field) {
        return customCodeField(field) ? dynamicCodeFieldHeight(field) : -1;
    }

    @Override
    protected String textFieldDescription() {
        return "Animation text.\nSupports MiniMessage tags.";
    }

    @Override
    protected String jsonResourceDescription(String field, String label) {
        if ("kind".equals(field)) {
            return "Text structure.\nanimation: displays changing text frames.\nlist: stores one reusable value per line.\nmap: stores reusable key and value pairs as key = value.";
        }
        return super.jsonResourceDescription(field, label);
    }

    @Override
    protected String modeFieldDescription() {
        return "Text-template animation mode.\nframes: cycle each frame.\ntyping: reveal text over time.\nscroll: moving text window.\nbounce: moving text window that reverses at edges.\nblink: alternate visible and blank.\npulse, rainbow, wave, wipe, sparkle: cosmetic animated text.";
    }

    @Override
    protected boolean remountPanelOnFieldReload() {
        return true;
    }

    @Override
    protected boolean handleSpecialJsonTextWrite(String field, String value) {
        if ("kind".equals(field)) {
            String currentContent = contentEditor.getText();
            resource.addProperty("kind", value == null || value.isBlank() ? "animation" : value.toLowerCase(Locale.ROOT));
            sanitizeLegacyResourceFields();
            writeCentralContent(currentContent);
            syncCentralEditor();
            return true;
        }
        if (!"text".equals(field)) {
            return false;
        }
        putTemplateText(value);
        return true;
    }

    @Override
    protected void sanitizeLegacyResourceFields() {
        resource.remove("colorsText");
        String kind = jsonText("kind").toLowerCase(Locale.ROOT);
        if (kind.isBlank()) {
            kind = "animation";
            resource.addProperty("kind", kind);
        }
        if ("list".equals(kind)) {
            if (!resource.has("values") || !resource.get("values").isJsonArray()) {
                resource.add("values", new JsonArray());
            }
            return;
        }
        if ("map".equals(kind)) {
            if (!resource.has("entries") || !resource.get("entries").isJsonArray()) {
                resource.add("entries", new JsonArray());
            }
            return;
        }
        if (jsonText("mode").isBlank()) {
            resource.addProperty("mode", "frames");
        }
        JsonArray frames = resource.has("frames") && resource.get("frames").isJsonArray() ? resource.getAsJsonArray("frames") : new JsonArray();
        if (frames.isEmpty()) {
            String text = jsonText("text");
            if (text.isBlank()) {
                text = id;
            }
            if (text != null && !text.isBlank()) {
                frames.add(text);
                resource.add("frames", frames);
            }
        }
    }

    @Override
    protected String resourceSummary() {
        return switch (jsonText("kind").toLowerCase(Locale.ROOT)) {
            case "list" -> listValues().size() + " Values";
            case "map" -> mapEntries().size() + " Entries";
            default -> firstFilled(jsonText("text"), "Animation");
        };
    }

    @Override
    protected String resourceDisplayName() {
        return "Text";
    }

    protected List<String> textTemplateFields() {
        String kind = jsonText("kind").toLowerCase(Locale.ROOT);
        if ("list".equals(kind) || "map".equals(kind)) {
            return List.of("kind");
        }
        List<String> fields = new ArrayList<>(List.of("kind", "mode"));
        switch (jsonText("mode").toLowerCase(Locale.ROOT)) {
            case "typing" -> {
                fields.add("frameMillis");
                fields.add("visibleCharacters");
            }
            case "scroll", "scrolling" -> {
                fields.add("frameMillis");
                fields.add("width");
            }
            case "bounce" -> {
                fields.add("frameMillis");
                fields.add("width");
            }
            case "blink" -> {
                fields.add("frameMillis");
            }
            case "pulse", "sparkle" -> {
                fields.add("frameMillis");
                fields.add("color");
                fields.add("secondaryColor");
            }
            case "rainbow" -> {
                fields.add("frameMillis");
            }
            case "wave" -> {
                fields.add("frameMillis");
                fields.add("colorsText");
            }
            case "wipe" -> {
                fields.add("frameMillis");
                fields.add("visibleCharacters");
            }
            default -> {
                fields.add("frameMillis");
            }
        }
        return fields;
    }

    protected List<String> previewTextLines() {
        List<String> lines = new ArrayList<>();
        JsonArray frames = resource.has("frames") && resource.get("frames").isJsonArray() ? resource.getAsJsonArray("frames") : new JsonArray();
        for (JsonElement frame : frames) {
            if (!frame.isJsonNull() && !frame.getAsString().isBlank()) {
                lines.add(frame.getAsString());
            }
        }
        if (!lines.isEmpty()) {
            return lines;
        }
        String text = jsonText("text");
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines.isEmpty() ? List.of("Text") : lines;
    }

    @Override
    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        if (!contentEditor.isMouseOver(event.x(), event.y())) {
            contentEditor.setFocused(false);
            return false;
        }
        boolean handled = contentEditor.mouseClicked(event.retarget(contentEditor, event.x(), event.y()));
        if (handled) {
            contentEditor.setFocused(true);
        }
        return handled;
    }

    @Override
    protected boolean handleResourceMouseReleased(ReMouseEvent event) {
        return contentEditor.mouseReleased(event.retarget(contentEditor, event.x(), event.y()));
    }

    @Override
    protected boolean handleResourceMouseDragged(ReMouseEvent event) {
        return contentEditor.mouseDragged(event.retarget(contentEditor, event.x(), event.y()));
    }

    @Override
    protected boolean handleResourceMouseScrolled(ReScrollEvent event) {
        return contentEditor.mouseScrolled(event.retarget(contentEditor, event.x(), event.y()));
    }

    @Override
    protected boolean handleResourceKeyPressed(ReKeyEvent event) {
        return contentEditor.keyPressed(event.retarget(contentEditor));
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        return contentEditor.textInput(event.retarget(contentEditor)) || super.textInput(event);
    }

    private void layoutContentEditor() {
        int left = Math.max(8, host != null ? host.studioContentBrowserWidth() : 0);
        int right = Math.max(left + 80, workspaceWidth - 8);
        if (studioResourcePanel != null && studioResourcePanel.isVisible() && !studioResourcePanel.isLeftAnchored()) {
            right = Math.max(left + 80, workspaceWidth - studioResourcePanel.getDesiredWidth() - 8);
        }
        int top = studioPanelTop();
        int bottom = Math.max(top + 80, workspaceHeight - studioPanelBottomReserve());
        contentEditor.setX(left);
        contentEditor.setY(top);
        contentEditor.setWidth(Math.max(80, right - left));
        contentEditor.setHeight(Math.max(80, bottom - top));
    }

    private void syncCentralEditor() {
        syncingEditor = true;
        contentEditor.setText(switch (jsonText("kind").toLowerCase(Locale.ROOT)) {
            case "list" -> String.join("\n", listValues());
            case "map" -> String.join("\n", mapEntries().stream().map(entry -> jsonText(entry, "key") + " = " + jsonText(entry, "value")).toList());
            default -> String.join("\n", previewTextLines());
        });
        syncingEditor = false;
    }

    private void writeCentralContent(String value) {
        if (syncingEditor) {
            return;
        }
        if (deferResourceMutation(() -> writeCentralContent(value))) {
            return;
        }
        captureResourceSnapshot();
        String kind = jsonText("kind").toLowerCase(Locale.ROOT);
        if ("list".equals(kind)) {
            JsonArray values = new JsonArray();
            for (String line : textLines(value)) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    values.add(trimmed);
                }
            }
            resource.add("values", values);
            return;
        }
        if ("map".equals(kind)) {
            JsonArray entries = new JsonArray();
            for (String raw : textLines(value)) {
                String line = raw.trim();
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.indexOf('=');
                JsonObject entry = new JsonObject();
                entry.addProperty("key", (separator < 0 ? line : line.substring(0, separator)).trim());
                entry.addProperty("value", separator < 0 ? "" : line.substring(separator + 1).trim());
                entries.add(entry);
            }
            resource.add("entries", entries);
            return;
        }
        JsonArray frames = new JsonArray();
        String first = "";
        for (String line : textLines(value)) {
            if (line.isBlank()) {
                continue;
            }
            frames.add(line);
            if (first.isEmpty()) {
                first = line;
            }
        }
        resource.add("frames", frames);
        resource.addProperty("text", first);
    }

    private List<String> listValues() {
        JsonArray values = resource.has("values") && resource.get("values").isJsonArray() ? resource.getAsJsonArray("values") : new JsonArray();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isJsonPrimitive()) {
                result.add(value.getAsString());
            }
        });
        return result;
    }

    private List<JsonObject> mapEntries() {
        JsonArray entries = resource.has("entries") && resource.get("entries").isJsonArray() ? resource.getAsJsonArray("entries") : new JsonArray();
        List<JsonObject> result = new ArrayList<>();
        entries.forEach(value -> {
            if (value.isJsonObject()) {
                result.add(value.getAsJsonObject());
            }
        });
        return result;
    }

    private static String[] textLines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }
}
