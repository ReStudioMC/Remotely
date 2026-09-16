package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowConnectionIdentityPrecedenceTest {
    @Test
    void stablePinIdsRemainAuthoritativeWhenLegacyPinsArePresentOrBlank() {
        FlowConnection connection = FlowConnection.stable("source", "source-stable", "target", "target-stable");

        connection.setSourcePin("legacy-source");
        connection.setTargetPin("");

        assertEquals("source-stable", connection.getSourcePin());
        assertEquals("source-stable", connection.getSourcePinId());
        assertEquals("target-stable", connection.getTargetPin());
        assertEquals("target-stable", connection.getTargetPinId());
    }

    @Test
    void legacyPinsAreUsedWhenStablePinIdsAreAbsentOrBlank() {
        FlowConnection connection = FlowConnection.legacy("source", "legacy-source", "target", "legacy-target");
        connection.setSourcePinId(" ");
        connection.setTargetPinId(null);

        assertEquals("legacy-source", connection.getSourcePin());
        assertEquals("legacy-source", connection.getSourcePinId());
        assertEquals("legacy-target", connection.getTargetPin());
        assertEquals("legacy-target", connection.getTargetPinId());
    }

    @Test
    void deserializationKeepsStableIdentityAndUnknownConnectionFields() {
        JsonObject source = JsonParser.parseString("""
            {"connections":[{"sourceNodeId":"source","sourcePin":"legacy-source","sourcePinId":"source-stable",
            "targetNodeId":"target","targetPin":"legacy-target","targetPinId":"target-stable","unknown":true}]}
            """).getAsJsonObject();

        FlowGraph graph = FlowSerializer.deserialize(source);
        FlowConnection connection = graph.getConnections().getFirst();
        JsonObject output = FlowSerializer.toJsonObject(graph);

        assertEquals("source-stable", connection.getSourcePin());
        assertEquals("target-stable", connection.getTargetPin());
        assertEquals("source-stable", output.getAsJsonArray("connections").get(0).getAsJsonObject().get("sourcePinId").getAsString());
        assertEquals("target-stable", output.getAsJsonArray("connections").get(0).getAsJsonObject().get("targetPinId").getAsString());
        assertEquals(true, output.getAsJsonArray("connections").get(0).getAsJsonObject().get("unknown").getAsBoolean());
    }
}
