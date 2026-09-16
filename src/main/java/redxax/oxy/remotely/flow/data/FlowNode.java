package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonElement;
import redxax.oxy.remotely.nodegraph.editor.GraphNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class FlowNode implements GraphNode {
    public static final int CURRENT_VERSION = 1;
    private String type;
    private int version;
    private double x;
    private double y;
    private Map<String, Object> inputValues;
    private transient Map<String, JsonElement> opaqueProperties;

    public FlowNode() {
        this.version = CURRENT_VERSION;
        this.inputValues = new HashMap<>();
    }

    public FlowNode(String type, double x, double y, Map<String, Object> inputValues) {
        this.type = type;
        this.version = CURRENT_VERSION;
        this.x = x;
        this.y = y;
        this.inputValues = inputValues != null ? inputValues : new HashMap<>();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public Map<String, Object> getInputValues() {
        return inputValues;
    }

    public void setInputValues(Map<String, Object> inputValues) {
        this.inputValues = inputValues;
    }

    @Override
    public void setInputValue(String key, Object value) {
        inputValues.put(key, value);
    }

    public Map<String, JsonElement> getOpaqueProperties() {
        if (opaqueProperties == null) {
            opaqueProperties = new LinkedHashMap<>();
        }
        return opaqueProperties;
    }

    Map<String, JsonElement> peekOpaqueProperties() {
        return opaqueProperties;
    }

    public void setOpaqueProperties(Map<String, JsonElement> opaqueProperties) {
        this.opaqueProperties = opaqueProperties != null ? new LinkedHashMap<>(opaqueProperties) : new LinkedHashMap<>();
    }
}
