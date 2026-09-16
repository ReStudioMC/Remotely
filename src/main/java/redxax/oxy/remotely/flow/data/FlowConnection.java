package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonElement;
import redxax.oxy.remotely.nodegraph.editor.GraphConnection;

import java.util.LinkedHashMap;
import java.util.Map;

public class FlowConnection implements GraphConnection {
    private String sourceNodeId;
    private String sourcePin;
    private String sourcePinId;
    private String sourcePinDisplayName;
    private String targetNodeId;
    private String targetPin;
    private String targetPinId;
    private String targetPinDisplayName;
    private String editorSourceNodeId;
    private String editorSourcePin;
    private String editorSourcePinId;
    private String editorSourcePinDisplayName;
    private transient Map<String, JsonElement> opaqueProperties;

    public FlowConnection() {
    }

    public FlowConnection(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePin = sourcePin;
        this.sourcePinId = sourcePin;
        this.targetNodeId = targetNodeId;
        this.targetPin = targetPin;
        this.targetPinId = targetPin;
    }

    public static FlowConnection stable(String sourceNodeId, String sourcePinId, String targetNodeId, String targetPinId) {
        FlowConnection connection = new FlowConnection();
        connection.sourceNodeId = sourceNodeId;
        connection.sourcePinId = sourcePinId;
        connection.targetNodeId = targetNodeId;
        connection.targetPinId = targetPinId;
        return connection;
    }

    public static FlowConnection legacy(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        return new FlowConnection(sourceNodeId, sourcePin, targetNodeId, targetPin);
    }

    public static FlowConnection fromLegacy(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        return legacy(sourceNodeId, sourcePin, targetNodeId, targetPin);
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    @Override
    public String getSourcePin() {
        return preferredPin(sourcePinId, sourcePin);
    }

    public void setSourcePin(String sourcePin) {
        this.sourcePin = sourcePin;
        if (!hasText(this.sourcePinId)) {
            this.sourcePinId = sourcePin;
        }
    }

    public String getSourcePinId() {
        return preferredPin(sourcePinId, sourcePin);
    }

    public void setSourcePinId(String sourcePinId) {
        this.sourcePinId = sourcePinId;
    }

    public String getSourcePinDisplayName() {
        return sourcePinDisplayName != null ? sourcePinDisplayName : getSourcePin();
    }

    public void setSourcePinDisplayName(String sourcePinDisplayName) {
        this.sourcePinDisplayName = sourcePinDisplayName;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(String targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    @Override
    public String getTargetPin() {
        return preferredPin(targetPinId, targetPin);
    }

    public void setTargetPin(String targetPin) {
        this.targetPin = targetPin;
        if (!hasText(this.targetPinId)) {
            this.targetPinId = targetPin;
        }
    }

    public String getTargetPinId() {
        return preferredPin(targetPinId, targetPin);
    }

    public void setTargetPinId(String targetPinId) {
        this.targetPinId = targetPinId;
    }

    public String getTargetPinDisplayName() {
        return targetPinDisplayName != null ? targetPinDisplayName : getTargetPin();
    }

    public void setTargetPinDisplayName(String targetPinDisplayName) {
        this.targetPinDisplayName = targetPinDisplayName;
    }

    public String getEditorSourceNodeId() {
        return editorSourceNodeId;
    }

    public void setEditorSourceNodeId(String editorSourceNodeId) {
        this.editorSourceNodeId = editorSourceNodeId;
    }

    public String getEditorSourcePin() {
        return preferredPin(editorSourcePinId, editorSourcePin);
    }

    public void setEditorSourcePin(String editorSourcePin) {
        this.editorSourcePin = editorSourcePin;
        if (!hasText(this.editorSourcePinId)) {
            this.editorSourcePinId = editorSourcePin;
        }
    }

    public String getEditorSourcePinId() {
        return preferredPin(editorSourcePinId, editorSourcePin);
    }

    public void setEditorSourcePinId(String editorSourcePinId) {
        this.editorSourcePinId = editorSourcePinId;
    }

    public String getEditorSourcePinDisplayName() {
        return editorSourcePinDisplayName != null ? editorSourcePinDisplayName : getEditorSourcePin();
    }

    public void setEditorSourcePinDisplayName(String editorSourcePinDisplayName) {
        this.editorSourcePinDisplayName = editorSourcePinDisplayName;
    }

    void adaptLegacyIdentity() {
        if (sourcePinId == null || sourcePinId.isBlank()) {
            sourcePinId = sourcePin;
        }
        if (targetPinId == null || targetPinId.isBlank()) {
            targetPinId = targetPin;
        }
        if (editorSourcePinId == null || editorSourcePinId.isBlank()) {
            editorSourcePinId = editorSourcePin;
        }
    }

    private static String preferredPin(String stablePin, String legacyPin) {
        return hasText(stablePin) ? stablePin : legacyPin;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
