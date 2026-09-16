package redxax.oxy.remotely.flow.data;

import java.util.ArrayList;
import java.util.List;

public class Visual {
    private String material;
    private Integer modelData;
    private String presetReference;
    private List<String> lore;
    private String name;
    private boolean enchanted;
    private List<String> itemFlags;
    private String headTexture;

    public Visual() {
        this.lore = new ArrayList<>();
        this.itemFlags = new ArrayList<>();
    }

    public Visual(String material) {
        this();
        this.material = material;
    }

    public Visual(String material, String name) {
        this(material);
        this.name = name;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public Integer getModelData() {
        return modelData;
    }

    public void setModelData(Integer modelData) {
        this.modelData = modelData;
    }

    public String getPresetReference() {
        return presetReference;
    }

    public void setPresetReference(String presetReference) {
        this.presetReference = presetReference;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore != null ? lore : new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnchanted() {
        return enchanted;
    }

    public void setEnchanted(boolean enchanted) {
        this.enchanted = enchanted;
    }

    public List<String> getItemFlags() {
        return itemFlags;
    }

    public void setItemFlags(List<String> itemFlags) {
        this.itemFlags = itemFlags != null ? itemFlags : new ArrayList<>();
    }

    public String getHeadTexture() {
        return headTexture;
    }

    public void setHeadTexture(String headTexture) {
        this.headTexture = headTexture;
    }

    public Visual copy() {
        Visual copy = new Visual();
        copy.setMaterial(this.material);
        copy.setModelData(this.modelData);
        copy.setPresetReference(this.presetReference);
        if (this.lore != null) {
            copy.getLore().addAll(this.lore);
        }
        copy.setName(this.name);
        copy.setEnchanted(this.enchanted);
        if (this.itemFlags != null) {
            copy.getItemFlags().addAll(this.itemFlags);
        }
        copy.setHeadTexture(this.headTexture);
        return copy;
    }
}
