package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.rescreen.util.JsonTreeParser;

import java.util.ArrayList;
import java.util.List;

public final class CommandBindingContext {
    public String command;
    public List<String> subcommands = new ArrayList<>();
    public Boolean structured = false;

    public static CommandBindingContext fromJson(String payload) {
        JsonElement parsed = JsonTreeParser.parse(payload);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Command Context Must Be An Object");
        }
        JsonObject json = parsed.getAsJsonObject();
        CommandBindingContext context = new CommandBindingContext();
        if (json.has("command") && !json.get("command").isJsonNull()) {
            context.command = json.get("command").getAsString();
        }
        if (json.has("subcommands") && !json.get("subcommands").isJsonNull()) {
            for (JsonElement value : json.getAsJsonArray("subcommands")) {
                context.subcommands.add(value.isJsonNull() ? null : value.getAsString());
            }
        }
        if (json.has("structured") && !json.get("structured").isJsonNull()) {
            context.structured = json.get("structured").getAsBoolean();
        }
        return context;
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        if (command != null) {
            json.addProperty("command", command);
        }
        if (subcommands != null) {
            JsonArray values = new JsonArray();
            subcommands.forEach(values::add);
            json.add("subcommands", values);
        }
        if (structured != null) {
            json.addProperty("structured", structured);
        }
        return JsonTreeParser.write(json);
    }
}
