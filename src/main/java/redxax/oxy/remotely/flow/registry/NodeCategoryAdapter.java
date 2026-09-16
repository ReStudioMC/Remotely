package redxax.oxy.remotely.flow.registry;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public final class NodeCategoryAdapter extends TypeAdapter<NodeDefinition.NodeCategory> {
    @Override
    public void write(JsonWriter out, NodeDefinition.NodeCategory value) throws IOException {
        out.value(value != null ? value.getId() : null);
    }

    @Override
    public NodeDefinition.NodeCategory read(JsonReader in) throws IOException {
        return NodeDefinition.NodeCategory.fromString(in.nextString());
    }
}
