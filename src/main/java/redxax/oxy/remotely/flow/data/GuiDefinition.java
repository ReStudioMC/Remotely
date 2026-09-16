package redxax.oxy.remotely.flow.data;

import java.util.ArrayList;
import java.util.List;

public class GuiDefinition {
    private String id;
    private boolean enabled = true;
    private String title;
    private int rows;
    private boolean extendToPlayerInventory;
    private List<GuiElement> elements;
    private String clickSound;
    private String openFlowId;
    private String closeFlowId;
    private int updateIntervalTicks;
    private String updateFlowId;

    public GuiDefinition() {
        this.elements = new ArrayList<>();
    }

    public GuiDefinition(String id, String title, int rows) {
        this.id = id;
        this.title = title;
        this.rows = rows;
        this.elements = new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }

    public boolean isExtendToPlayerInventory() { return extendToPlayerInventory; }
    public void setExtendToPlayerInventory(boolean extendToPlayerInventory) { this.extendToPlayerInventory = extendToPlayerInventory; }

    public List<GuiElement> getElements() { return elements; }
    public void setElements(List<GuiElement> elements) { this.elements = elements; }

    public String getClickSound() { return clickSound; }
    public void setClickSound(String clickSound) { this.clickSound = clickSound; }

    public String getOpenFlowId() { return openFlowId; }
    public void setOpenFlowId(String openFlowId) { this.openFlowId = openFlowId; }

    public String getCloseFlowId() { return closeFlowId; }
    public void setCloseFlowId(String closeFlowId) { this.closeFlowId = closeFlowId; }

    public int getUpdateIntervalTicks() { return updateIntervalTicks; }
    public void setUpdateIntervalTicks(int updateIntervalTicks) { this.updateIntervalTicks = updateIntervalTicks; }

    public String getUpdateFlowId() { return updateFlowId; }
    public void setUpdateFlowId(String updateFlowId) { this.updateFlowId = updateFlowId; }

    public GuiDefinition copy() {
        GuiDefinition copy = new GuiDefinition(id, title, rows);
        copy.setEnabled(enabled);
        copy.setExtendToPlayerInventory(extendToPlayerInventory);
        copy.setClickSound(clickSound);
        copy.setOpenFlowId(openFlowId);
        copy.setCloseFlowId(closeFlowId);
        copy.setUpdateIntervalTicks(updateIntervalTicks);
        copy.setUpdateFlowId(updateFlowId);
        List<GuiElement> copiedElements = new ArrayList<>();
        if (elements != null) {
            for (GuiElement element : elements) {
                if (element != null) {
                    copiedElements.add(element.copy());
                }
            }
        }
        copy.setElements(copiedElements);
        return copy;
    }
}
