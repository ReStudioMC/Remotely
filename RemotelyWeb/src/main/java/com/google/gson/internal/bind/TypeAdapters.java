package com.google.gson.internal.bind;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import restudio.rescreen.util.JsonTreeParser;

import java.io.IOException;

public final class TypeAdapters {
    public static final TypeAdapter<JsonElement> JSON_ELEMENT = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, JsonElement value) throws IOException {
            out.jsonValue(JsonTreeParser.write(value));
        }

        @Override
        public JsonElement read(JsonReader in) throws IOException {
            return switch (in.peek()) {
                case BEGIN_ARRAY -> {
                    JsonArray array = new JsonArray();
                    in.beginArray();
                    while (in.hasNext()) array.add(read(in));
                    in.endArray();
                    yield array;
                }
                case BEGIN_OBJECT -> {
                    JsonObject object = new JsonObject();
                    in.beginObject();
                    while (in.hasNext()) object.add(in.nextName(), read(in));
                    in.endObject();
                    yield object;
                }
                case STRING -> new JsonPrimitive(in.nextString());
                case NUMBER -> new JsonPrimitive(new LazilyParsedNumber(in.nextString()));
                case BOOLEAN -> new JsonPrimitive(in.nextBoolean());
                case NULL -> {
                    in.nextNull();
                    yield JsonNull.INSTANCE;
                }
                default -> throw new IllegalStateException("Expected A JSON Value At " + in.getPath());
            };
        }
    };

    private TypeAdapters() {
    }
}
