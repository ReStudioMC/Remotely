package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonParser;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrowserJsonReaderTest {
    @Test
    void readsNestedValuesWithoutLosingNumberPrecision() {
        var json = JsonParser.parseString("{\"revision\":9007199254740993,\"values\":[true,null,{\"name\":\"A\\nB\"}],\"decimal\":1.234567890123456789}").getAsJsonObject();

        assertEquals("9007199254740993", json.get("revision").getAsString());
        assertEquals("1.234567890123456789", json.get("decimal").getAsString());
        assertTrue(json.getAsJsonArray("values").get(1).isJsonNull());
        assertEquals("A\nB", json.getAsJsonArray("values").get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void streamingAdapterConsumesOnlyOneValue() throws Exception {
        try (JsonReader reader = new JsonReader(new StringReader("[{\"id\":\"first\"},42]"))) {
            reader.beginArray();
            assertEquals("first", TypeAdapters.JSON_ELEMENT.read(reader).getAsJsonObject().get("id").getAsString());
            assertEquals(42, TypeAdapters.JSON_ELEMENT.read(reader).getAsInt());
            reader.endArray();
        }
    }
}
