package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentDesignerAttributesStaticTest {
    private static final Path SOURCE = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/ContentDesignerScreen.java");
    private static final Path RESCREEN_SOURCE = Path.of(System.getProperty(
        "restudio.rescreen.sourceDir",
        System.getenv().getOrDefault("RESCREEN_SOURCE_DIR", "../ReScreen")
    )).toAbsolutePath().normalize();

    @Test
    void attributesEditorCoversRequiredStates() throws IOException {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");

        assertTrue(source.contains("ATTRIBUTE_SCHEMA_SOURCE"));
        assertTrue(source.contains("Loading"));
        assertTrue(source.contains("attributeSchemaSource(definition.getMaterial())"));
        assertTrue(source.contains("attributeDesignerPanel"));
        assertTrue(source.contains("leftStudioPanel(\"attributeDesignerPanel\")"));
        assertTrue(source.contains("setStudioContentBrowserTemporarilyHidden(true)"));
        assertTrue(source.contains("setStudioContentBrowserTemporarilyHidden(false)"));
        assertTrue(source.contains("private boolean attributeDesignerHidContentBrowser;"));
        assertTrue(source.contains("attributeDesignerHidContentBrowser = true;"));
        assertTrue(source.contains("if (attributeDesignerHidContentBrowser && contentDesignerParent instanceof StudioScreen studioScreen)"));
        assertTrue(source.contains("attributeDesignerHidContentBrowser = false;"));
        assertTrue(source.contains("setFocusedWidget(null);"));
        assertTrue(source.contains("attributePanel.hideImmediately();"));
        assertTrue(source.contains("clearAttributePanelWidgets(attributePanel.container());\n            attributePanel.hideImmediately();"));
        assertTrue(source.contains("attributeHeaderWidget = null;"));
        assertTrue(source.contains("refreshContentPanel();\n        updatePositions();"));
        assertTrue(source.contains("attributeDesignerPanel.show();\n        }\n        updatePositions();"));
        assertTrue(Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java")).contains("!studioContentBrowser.isTemporarilyHidden()"));
        String browserSource = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));
        assertTrue(browserSource.contains("if (temporarilyHidden)"));
        assertTrue(browserSource.contains("screen.clearStudioFocus();"));
        assertTrue(browserSource.contains("sidePanel.hideImmediately();"));
        assertTrue(browserSource.contains("return sidePanel.layoutWidth(8);"));
        assertTrue(browserSource.contains("sidePanel.getConfiguredWidth()"));
        assertTrue(Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioPanel.java")).contains("if (!sidePanel.isVisible())"));
        String mountableSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/MountableButtonWidget.java"));
        String popupSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/PopupWidget.java"));
        assertTrue(mountableSource.contains("public int getHeight()"));
        assertTrue(mountableSource.contains("updateEmbeddedPopupBounds();"));
        assertTrue(mountableSource.contains("this.embeddedPopup.fitContentHeight(true);"));
        assertTrue(mountableSource.contains("embeddedPopup.tick();"));
        assertTrue(mountableSource.contains("embeddedPopup.renderEmbedded(ctx, mouseX, mouseY, deltaTime);"));
        assertTrue(mountableSource.contains("public void tickEmbeddedPopupLayout()"));
        assertTrue(mountableSource.contains("embeddedPopupPreLayoutTicked"));
        assertTrue(mountableSource.contains("PopupWidget previousPopup = this.embeddedPopup;"));
        assertTrue(mountableSource.contains("previousPopup.clearFocus();"));
        assertTrue(mountableSource.contains("previousPopup.visible = false;"));
        assertTrue(mountableSource.contains("this.embeddedPopup.active = false;"));
        assertTrue(mountableSource.contains("super.setHeight(embeddedHeaderHeight);"));
        assertTrue(mountableSource.contains("public void setEmbeddedBody(List<AnimatedWidget> widgets, boolean visible)"));
        assertTrue(mountableSource.contains("public boolean hasVisibleEmbeddedBody()"));
        assertTrue(mountableSource.contains("private boolean embeddedBodyMouseClicked(double mouseX, double mouseY, int button)"));
        assertTrue(mountableSource.contains("dispatchKeyPressed(focusedEmbeddedBodyWidget, event)"));
        assertTrue(mountableSource.contains("if (!hasVisibleEmbeddedPopup() && !hasVisibleEmbeddedBody())"));
        assertTrue(mountableSource.contains("private float embeddedBodyProgress;"));
        assertTrue(mountableSource.contains("private void tickEmbeddedBodyProgress()"));
        assertTrue(mountableSource.contains("embeddedBodyProgress < 0.01f"));
        assertTrue(mountableSource.contains("closeEmbeddedBodyDropdowns();"));
        assertTrue(mountableSource.contains("public void renderEmbeddedOverlays"));
        assertTrue(mountableSource.contains("public boolean mouseClickedEmbeddedOverlay"));
        String containerSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/rescreen/Container.java"));
        String sidePanelSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/rescreen/SidePanel.java"));
        assertTrue(containerSource.contains("tickManagedLayoutDynamicHeights();"));
        assertTrue(containerSource.contains("mountable.tickEmbeddedPopupLayout();"));
        assertTrue(containerSource.contains("mountable.renderEmbeddedOverlays(ctx, mouseX, mouseY);"));
        assertTrue(containerSource.contains("mountable.mouseClickedEmbeddedOverlay(event.retarget(mountable, event.x(), event.y()))"));
        assertTrue(containerSource.contains("public boolean isMouseOverScrollbar(double mouseX, double mouseY)"));
        assertTrue(sidePanelSource.contains("if (innerContainer.isMouseOverScrollbar(mouseX, mouseY))"));
        String dropdownSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/DropDownWidget.java"));
        assertTrue(dropdownSource.contains("public boolean isDropdownVisible()"));
        assertTrue(dropdownSource.contains("return expanded || dropdownAnimationProgress > 0.01f;"));
        assertTrue(popupSource.contains("public void snapAnimatedHeight()"));
        assertTrue(popupSource.contains("public void fitContentHeight()"));
        assertTrue(popupSource.contains("public void fitContentHeight(boolean animateFromCollapsed)"));
        assertTrue(popupSource.contains("public void renderEmbedded"));
        assertTrue(popupSource.contains("row.getExpandedDropdownHeight()"));
        assertTrue(popupSource.contains("titledRow.renderExpandedDropdowns(ctx, mouseX, mouseY);"));
        String titledRowSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/TitledRowWidget.java"));
        String rowSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/RowWidget.java"));
        assertTrue(titledRowSource.contains("public int getExpandedDropdownHeight()"));
        assertTrue(titledRowSource.contains("dropdown.isDropdownVisible()"));
        assertTrue(titledRowSource.contains("public void closeExpandedDropdowns()"));
        assertTrue(rowSource.contains("public void closeExpandedDropdowns()"));
        assertTrue(titledRowSource.contains("dispatchKeyPressed(focusedWidget, event)"));
        assertTrue(titledRowSource.contains("dispatchTextInput(focusedWidget, event)"));
        assertTrue(rowSource.contains("dispatchKeyPressed(focusedWidget, event)"));
        assertTrue(rowSource.contains("dispatchTextInput(focusedWidget, event)"));
        assertTrue(source.contains("contentDesignerParent instanceof StudioScreen"));
        assertTrue(source.contains("Item Attributes"));
        assertTrue(source.contains("Attribute Name"));
        assertTrue(source.contains("Search"));
        assertFalse(source.contains("Reset"));
        assertFalse(source.contains("Search For More"));
        assertTrue(source.contains("No Components"));
        assertTrue(source.contains("Current Attributes"));
        assertFalse(source.contains("Attribute Library"));
        assertTrue(source.contains("componentCatalogStatus"));
        assertFalse(source.contains("libraryStatus"));
        assertTrue(source.contains("attributeHeaderWidget"));
        assertTrue(source.contains("attributeSearchInput"));
        assertTrue(source.contains("refreshAttributeComponentList"));
        assertTrue(source.contains("attributeComponentRowStates"));
        assertTrue(source.contains("attributeStatusRows"));
        assertTrue(source.contains("state.editorWidgets = collectAttributeEditorWidgets"));
        assertTrue(source.contains("state.row.setEmbeddedBody(state.editorWidgets, true)"));
        assertTrue(source.contains("private void markAttributeEditorDirty(String componentId)"));
        assertTrue(source.contains("replaceWidgetsFromIndex(attributePanelStaticWidgetCount"));
        assertTrue(source.contains("replaceWidgetsFromIndex(attributePanelStaticWidgetCount, attributePanelWidgets.subList(attributePanelStaticWidgetCount, attributePanelWidgets.size()), false)"));
        assertTrue(containerSource.contains("replaceWidgetsFromIndex(int startIndex, List<? extends AnimatedWidget> nextWidgets, boolean cleanupRemoved)"));
        assertTrue(containerSource.contains("Set<Widget> nextIdentity = widgetTree(next);"));
        assertTrue(containerSource.contains("if (!nextIdentity.contains(widget))"));
        assertTrue(containerSource.contains("if (!previousPositions.containsKey(widget))"));
        assertTrue(source.contains("ensureAttributeComponentRowStates"));
        assertTrue(source.contains("private void refreshAttributeDesignerContent(boolean preserveScroll)"));
        assertTrue(source.contains("refreshAttributeDesignerContent(true);"));
        assertTrue(source.contains("if (attributePanelStaticWidgetCount > 0)"));
        assertTrue(source.contains("syncAttributeDirtyState();\n            refreshAttributeComponentList(preserveScroll);"));
        assertTrue(source.contains("contentScreen.refreshAttributeDesignerContent(true);"));
        assertFalse(source.contains("contentScreen.refreshAttributeDesigner();"));
        assertTrue(source.contains("syncAttributeDirtyState();\n            refreshAttributeComponentList(false);"));
        assertTrue(source.contains("syncAttributeDirtyState"));
        assertTrue(source.contains("syncAttributeDirtyState();"));
        assertTrue(source.contains("private final Map<String, Object> attributePreviewValues = new LinkedHashMap<>();"));
        assertTrue(source.contains("selectedAttributeComponent = id.equals(selectedAttributeComponent) ? \"\" : id;"));
        assertTrue(source.contains("ensureAttributePreviewValue(id, state != null ? state.item : null);"));
        assertTrue(source.contains("previewAttributeComponents(id, state.item)"));
        assertTrue(source.contains("attributePreviewValues.clear();"));
        assertTrue(source.contains("parts.add(\"Preview\");"));
        assertTrue(source.contains("preloadAttributeSchema();"));
        assertTrue(source.contains("private List<String> catalogOptions(String source)"));
        assertTrue(source.contains("Display Text"));
        assertTrue(source.contains("Trigger Rules"));
        assertTrue(source.contains("addTextRows"));
        assertTrue(source.contains("contentSectionHeader"));
        assertTrue(source.contains("ruleSummary"));
        assertTrue(source.contains("populateAttributeComponents"));
        assertTrue(source.contains("ensureSelectedAttribute"));
        assertTrue(source.contains("addAttributeComponentBlock"));
        assertTrue(source.contains("setAttributeComponentEnabled"));
        assertTrue(source.contains("collectAttributeEditorWidgets"));
        assertFalse(source.contains("attributeEditorPopup"));
        assertFalse(source.contains("state.editorPopup"));
        assertTrue(source.contains("state.row.setEmbeddedBody(state.editorWidgets, true)"));
        assertTrue(source.contains("No Attributes"));
        assertTrue(source.contains("addCommonAttributeEditorRows"));
        assertTrue(source.contains("addSchemaAttributeEditorRows"));
        assertTrue(source.contains("addSchemaEditorRows"));
        assertTrue(source.contains("attributeComponentSchema"));
        assertTrue(source.contains("hasSpecializedAttributeEditor"));
        assertTrue(source.contains("return !attributeComponentSchema(id).isEmpty();"));
        assertTrue(source.contains("schemaArrayText"));
        assertTrue(source.contains("schemaArrayValue"));
        assertFalse(Pattern.compile("\\.size\\([^\\n]*, 20\\)").matcher(source).find());
        assertTrue(source.contains("joinAttributeDescriptionParts"));
        assertTrue(source.contains("cleanAttributeDescriptionPart"));
        assertTrue(source.contains("builder.append(\" | \")"));
        assertTrue(source.contains("String.join(\" | \", parts)"));
        assertFalse(source.contains("String.join(\"  \", parts)"));
        assertFalse(source.contains("Use behavior"));
        assertFalse(source.contains("Food behavior"));
        assertTrue(source.contains("Use Time"));
        assertTrue(source.contains("Use Settings"));
        assertTrue(source.contains("Food Restored"));
        assertTrue(source.contains("Food Settings"));
        assertTrue(source.contains("Glint Shown"));
        assertTrue(source.contains("Damage Cost"));
        assertTrue(source.contains("Shield Disable"));
        assertTrue(source.contains("Slot \" + slotText + \" | Sound"));
        assertTrue(source.contains("addUseCooldownEditorRows"));
        assertTrue(source.contains("Use Cooldown"));
        assertTrue(source.contains("Cooldown Group"));
        assertTrue(source.contains("addUseRemainderEditorRows"));
        assertTrue(source.contains("Use Remainder"));
        assertTrue(source.contains("Remainder"));
        assertTrue(source.contains("addDamageResistantEditorRows"));
        assertTrue(source.contains("Damage Types"));
        assertTrue(source.contains("damageTypeOptions"));
        assertTrue(source.contains("addWeaponEditorRows"));
        assertTrue(source.contains("weaponSummary"));
        assertTrue(source.contains("Attack Damage Cost"));
        assertTrue(source.contains("Block Disable Time"));
        assertTrue(source.contains("addEquippableEditorRows"));
        assertTrue(source.contains("equippableSummary"));
        assertTrue(source.contains("Equip Sound"));
        assertTrue(source.contains("equipmentSlotOptions"));
        assertTrue(source.contains("equipmentAssetOptions"));
        assertTrue(source.contains("soundOptions"));
        assertTrue(source.contains("Show Particles"));
        assertTrue(source.contains("Model Data"));
        assertTrue(source.contains("addDyedColorEditorRows"));
        assertTrue(source.contains("formatColorValue"));
        assertTrue(source.contains("return parseColorValue(dyedColorRgb(previous), text);"));
        assertTrue(source.contains("return 16777215;"));
        assertFalse(source.contains("dyedColorTooltip"));
        assertTrue(source.contains("addEnchantmentsEditorRows"));
        assertTrue(source.contains("enchantmentLevels"));
        assertTrue(source.contains("addEnchantmentEntryRow"));
        assertTrue(source.contains("attributeAddSearchRow(\"Enchantment\""));
        assertTrue(source.contains("attributeEntryRow(\"Enchantment \" + index"));
        assertTrue(source.contains("return new LinkedHashMap<>(levels);"));
        assertFalse(source.contains("enchantmentsTooltip"));
        assertTrue(source.contains("componentId + \".value\", enchantableValue(value)"));
        assertTrue(source.contains("Map.of(\"value\", 10)"));
        assertTrue(source.contains("addPotionContentsEditorRows"));
        assertTrue(source.contains("potionOptions"));
        assertTrue(source.contains("addJukeboxPlayableEditorRows"));
        assertTrue(source.contains("private String jukeboxPlayableValue"));
        assertTrue(source.contains("return normalizeMinecraftKey(song != null && !song.isBlank() ? song : \"13\");"));
        assertTrue(source.contains("return \"minecraft:13\";"));
        assertTrue(source.contains("addAttributeModifiersEditorRows"));
        assertTrue(source.contains("addAttributeModifierEntryRow"));
        assertTrue(source.contains("attributeValueLabel(\"server:minecraft:attribute\", type)"));
        assertTrue(source.contains("attributeOperationDropdown"));
        assertTrue(source.contains("attributeOperationLabel"));
        assertTrue(source.contains("case \"add_value\" -> \"Add\""));
        assertFalse(methodBody(source, "private void addAttributeModifierEntryRow").contains("Modifier Mode"));
        assertFalse(methodBody(source, "private void addAttributeModifierEntryRow").contains("typeInput"));
        assertFalse(methodBody(source, "private void addAttributeModifierEntryRow").contains("attributeSearchButton"));
        assertTrue(source.contains("add_multiplied_base"));
        assertTrue(source.contains("add_multiplied_total"));
        assertTrue(source.contains("updateAttributeModifierEntry"));
        assertTrue(source.contains("attributeModifiersValue"));
        assertTrue(source.contains("private List<Object> attributeModifiersValue"));
        assertTrue(source.contains("return List.of(Map.of(\n                \"type\", \"minecraft:attack_damage\""));
        assertFalse(source.contains("attributeModifiersTooltip"));
        assertFalse(source.contains("value.put(\"modifiers\", modifiers);"));
        assertTrue(source.contains("parseAttributeModifierLine"));
        assertTrue(source.contains("addTrimEditorRows"));
        assertTrue(source.contains("trimMaterialOptions"));
        assertTrue(source.contains("trimPatternOptions"));
        assertTrue(source.contains("trimSummary"));
        assertFalse(source.contains("componentId + \".show_in_tooltip\""));
        assertTrue(source.contains("addFireworkExplosionEditorRows"));
        assertTrue(source.contains("addFireworksEditorRows"));
        assertTrue(source.contains("defaultFireworkExplosion"));
        assertTrue(source.contains("colorListText"));
        assertTrue(source.contains("parseColorList"));
        assertTrue(source.contains("addBannerPatternsEditorRows"));
        assertTrue(source.contains("bannerPatternsValue"));
        assertTrue(source.contains("parseBannerPatternLine"));
        assertTrue(source.contains("addBlockPredicateEditorRows"));
        assertTrue(source.contains("addBlockPredicateEntryRow"));
        assertTrue(source.contains("Block Rule \" + (index + 1)"));
        assertTrue(source.contains("updateBlockPredicateEntry"));
        assertTrue(source.contains("blockPredicateValue"));
        assertTrue(source.contains("private List<Object> blockPredicateValue"));
        assertFalse(source.contains("blockPredicateTooltip"));
        assertFalse(source.contains("value.put(\"predicates\", predicates);"));
        assertTrue(source.contains("normalizeBlockPredicate"));
        assertTrue(source.contains("jukeboxSongOptions"));
        assertTrue(source.contains("instrumentOptions"));
        assertTrue(source.contains("addPresenceEditorRow"));
        assertTrue(source.contains("minecraft:attribute_modifiers"));
        assertTrue(source.contains("minecraft:trim"));
        assertTrue(source.contains("minecraft:firework_explosion"));
        assertTrue(source.contains("minecraft:fireworks"));
        assertTrue(source.contains("minecraft:banner_patterns"));
        assertTrue(source.contains("minecraft:charged_projectiles"));
        assertTrue(source.contains("minecraft:bundle_contents"));
        assertTrue(source.contains("minecraft:container"));
        assertTrue(source.contains("minecraft:can_break"));
        assertTrue(source.contains("minecraft:can_place_on"));
        assertTrue(source.contains("\"minecraft:hide_tooltip\".equals(entry.getKey())"));
        assertTrue(source.contains("\"minecraft:hide_additional_tooltip\".equals(entry.getKey())"));
        assertTrue(source.contains("minecraft:enchantable"));
        assertTrue(source.contains("minecraft:instrument"));
        assertTrue(source.contains("minecraft:jukebox_playable"));
        assertTrue(source.contains("minecraft:glider"));
        assertTrue(source.contains("materialModelOptions"));
        assertTrue(source.contains("server:minecraft:sound"));
        assertTrue(source.contains("server:minecraft:instrument"));
        assertTrue(source.contains("server:minecraft:jukebox_song"));
        assertTrue(source.contains("server:minecraft:trim_material"));
        assertTrue(source.contains("server:minecraft:trim_pattern"));
        assertTrue(source.contains("server:minecraft:attribute"));
        assertTrue(source.contains("server:minecraft:banner_pattern"));
        assertTrue(source.contains("server:minecraft:enchantment"));
        assertTrue(source.contains("server:minecraft:block"));
        assertTrue(source.contains("server:minecraft:dye_color"));
        assertTrue(source.contains("server:minecraft:damage_type"));
        assertFalse(source.contains("minecraft:sharpness\", \"minecraft:efficiency\", \"minecraft:protection"));
        assertFalse(source.contains("minecraft:sentry\", \"minecraft:dune\", \"minecraft:coast\", \"minecraft:wild"));
        assertFalse(source.contains("minecraft:13\", \"minecraft:cat\", \"minecraft:blocks\", \"minecraft:chirp"));
        String optionMetadataSource = Files.readString(Path.of("../ReSync/src/main/java/restudio/resync/modules/flow/BuiltinOptionCatalogService.java"));
        assertTrue(optionMetadataSource.contains("catalog(\"attribute\""));
        assertTrue(optionMetadataSource.contains("catalog(\"banner_pattern\""));
        assertTrue(optionMetadataSource.contains("catalog(\"damage_type\""));
        assertTrue(optionMetadataSource.contains("catalog(\"dye_color\""));
        assertTrue(optionMetadataSource.contains("catalog(\"instrument\""));
        assertTrue(optionMetadataSource.contains("catalog(\"jukebox_song\""));
        assertTrue(optionMetadataSource.contains("catalog(\"potion\""));
        assertTrue(optionMetadataSource.contains("catalog(\"trim_material\""));
        assertTrue(optionMetadataSource.contains("catalog(\"trim_pattern\""));
        assertTrue(source.contains("addItemStackListEditorRows"));
        assertTrue(source.contains("addItemStackEntryRow"));
        assertTrue(source.contains("updateItemStackEntry"));
        assertTrue(source.contains("itemStackListValue"));
        assertTrue(source.contains("parseSlottedItemStackLine"));
        assertTrue(source.contains("simpleItemStack"));
        assertTrue(source.contains("attributeEntryRow"));
        assertTrue(source.contains("attributeCompactInput"));
        assertTrue(source.contains("attributeSearchButton"));
        assertTrue(source.contains("attributeDeleteButton"));
        assertTrue(source.contains("attributeAddSearchRow"));
        assertTrue(source.contains("updateAttributeComponentRoot"));
        assertTrue(source.contains("removeListAttributeEntry"));
        assertTrue(source.contains("attributeLinePicker"));
        assertTrue(source.contains("attributeColorPicker"));
        assertTrue(source.contains("parseSearchEditorValue"));
        assertTrue(source.contains("showStudioSelector(options, selected, selectorX, selectorY"));
        assertFalse(source.contains("renderActiveSearchSelector(context, mouseX, mouseY, delta)"));
        assertTrue(source.contains("appendEditorLine"));
        assertTrue(source.contains("dyeColorHex"));
        assertTrue(source.contains("dyeColorOptions"));
        assertTrue(source.contains("enchantmentOptions"));
        assertTrue(source.contains("attributeTypeOptions"));
        assertTrue(source.contains("bannerPatternOptions"));
        assertTrue(source.contains("blockOptions"));
        assertTrue(source.contains("input.runOnChange()"));
        assertTrue(source.contains("addLoreEditorRow"));
        assertTrue(source.contains("editor.setShowLineNumbers(false)"));
        assertTrue(source.contains("editor.setWordWrap(true)"));
        assertTrue(source.contains("tooltipHiddenComponents"));
        assertTrue(source.contains("hiddenTooltipComponentOptions"));
        assertTrue(source.contains("search.setOnChange"));
        assertTrue(source.contains("components.putIfAbsent(id"));
        assertTrue(source.contains("activeAttributeComponents"));
        assertTrue(source.contains("selectedAttributeComponent"));
        assertTrue(source.contains("attributeRestoreSearchFocus"));
        assertTrue(source.contains("commitAttributeDesignerDraft"));
        assertTrue(source.contains("refreshContentPanelIfAttributeDesignerClosed"));
        assertTrue(source.contains("refreshContentPanelIfAttributeDesignerClosed();\n        syncAttributeDirtyState();"));
        assertTrue(source.contains("hideAttributeDesigner"));
        assertTrue(source.contains("isAttributeDesignerInteractive"));
        assertTrue(source.contains("copyAttributeComponents"));
        assertTrue(source.contains("copyAttributeValue"));
        assertTrue(source.contains("normalizeAttributeComponents"));
        assertTrue(source.contains("normalizeAttributeComponentValue"));
        assertTrue(source.contains("activateNormalizedAttributeComponents"));
        assertTrue(source.contains("setProperty(\"components\", copyAttributeComponents(normalized))"));
        assertTrue(source.contains("tooltip.put(\"hide_tooltip\", true)"));
        assertTrue(source.contains("normalized.put(\"minecraft:damage_resistant\", Map.of(\"types\", \"#minecraft:is_fire\"))"));
        assertTrue(source.contains("\"minecraft:attribute_modifiers\".equals(id) && value instanceof Map<?, ?> map && map.containsKey(\"modifiers\")"));
        assertTrue(source.contains("map.containsKey(\"predicates\")"));
        assertTrue(source.contains("!\"show_in_tooltip\".equals(entry.getKey().toString())"));
        assertTrue(source.contains("\"minecraft:dyed_color\".equals(id) && value instanceof Map<?, ?> map"));
        assertTrue(source.contains("\"minecraft:jukebox_playable\".equals(id) && value instanceof Map<?, ?> map"));
        assertTrue(source.contains("map.containsKey(\"levels\")"));
        assertTrue(source.contains("updateNestedAttributeComponent"));
        assertTrue(source.contains("attributeDraftComponents"));
        assertTrue(source.contains("attributeDraftComponents(components)"));
        assertTrue(source.contains("Map<String, Object> draft = attributeDraftComponents(activeAttributeComponents != null ? activeAttributeComponents : current)"));
        assertTrue(source.contains("String relativePath = path != null && path.startsWith(componentId + \".\")"));
        assertTrue(source.contains("setProperty(\"components\", quickEditMode ? copyAttributeComponents(activeAttributeComponents) : persistedAttributeComponents(activeAttributeComponents))"));
        assertTrue(source.contains("persistedQuickEditComponents"));
        assertTrue(source.contains("editedAttributeComponents"));
        assertTrue(source.contains("attributeSearchAliases"));
        assertTrue(source.contains("metadataSearchText"));
        assertTrue(source.contains("metadataValue"));
        assertTrue(source.contains("metadataInt"));
        assertTrue(source.contains("metadataBoolean"));
        assertTrue(source.contains("metadataString"));
        assertFalse(source.contains("HIDDEN_ITEM_COMPONENTS"));
        assertTrue(source.contains("minecraft:dyed_color"));
        assertTrue(source.contains("minecraft:enchantments"));
        assertTrue(source.contains("minecraft:stored_enchantments"));
        assertTrue(source.contains("minecraft:potion_contents"));
        assertTrue(source.contains("minecraft:use_cooldown"));
        assertTrue(source.contains("minecraft:use_remainder"));
        assertTrue(source.contains("minecraft:damage_resistant"));
        assertTrue(source.contains("minecraft:weapon"));
        assertTrue(source.contains("minecraft:equippable"));
        assertTrue(source.contains("hasIntentionalAttributeEditor"));
        assertTrue(source.contains("shouldOfferAttributeComponent"));
        assertTrue(source.contains("displayAttributeComponents"));
        assertFalse(source.contains("attributeComponentWritable"));
        assertFalse(source.contains("filter(this::attributeComponentWritable)"));
        assertTrue(source.contains("state.toggle.setActive(true)"));
        assertTrue(source.contains("metadataBoolean(item, \"default\", false)"));
        assertTrue(source.contains("metadataValue(item, \"defaultValue\")"));
        assertTrue(source.contains("isPrimaryAttributeForMaterial"));
        assertTrue(source.contains("materialContains"));
        assertTrue(source.contains("availableComponents(displayComponents, catalog, query, material)"));
        assertTrue(source.contains("List<OptionCatalogItem> available"));
        assertTrue(source.contains("filter(item -> matchesAttributeSearch(item.getValue(), query, item))"));
        assertTrue(source.contains("attributeBrowseGroup(id, item)"));
        assertTrue(source.contains("attributeBrowseGroupRank(left)"));
        assertTrue(source.contains("activeComponents(displayComponents, catalog)"));
        assertTrue(source.contains("attributeBrowseGroupRank(leftItem, left)"));
        assertTrue(source.contains("metadataBoolean(left, \"applicable\", false)"));
        assertTrue(source.contains("metadataBoolean(left, \"recommended\", false)"));
        assertTrue(source.contains("query != null && !query.isBlank()"));
        assertTrue(source.contains("case \"minecraft:trim\" -> materialContains(value, \"HELMET\", \"CHESTPLATE\", \"LEGGINGS\", \"BOOTS\")"));
        assertTrue(source.contains("case \"minecraft:charged_projectiles\" -> materialContains(value, \"CROSSBOW\")"));
        assertTrue(source.contains("case \"minecraft:bundle_contents\" -> materialContains(value, \"BUNDLE\")"));
        assertTrue(source.contains("case \"minecraft:container\" -> materialContains(value, \"SHULKER_BOX\")"));
        assertTrue(countOccurrences(source, "case \"minecraft:weapon\"") == 1);
        assertTrue(source.contains("attributeBrowseRank"));
        assertTrue(source.contains("attributeBrowseGroup"));
        assertTrue(source.contains("attributeBrowseGroupDescription"));
        assertTrue(source.contains("attributeSectionHeader(group, attributeBrowseGroupDescription(group), rowWidth)"));
        assertTrue(source.contains("case \"Data\" -> 11"));
        assertTrue(source.contains("case \"Data\" -> \"Custom Component Data\""));
        assertTrue(source.contains("if (query == null || query.isBlank())"));
        assertTrue(source.contains("return false;"));
        assertTrue(source.contains("humanizeAttributeToken"));
        assertTrue(source.contains("currentAttributeValue"));
        assertTrue(source.contains("case \"show_in_tooltip\" -> \"Show In Tooltip\""));
        assertTrue(source.contains("case \"consume_seconds\" -> \"Use Time\""));
        assertTrue(source.contains("case \"use_cooldown\" -> \"Use Cooldown\""));
        assertTrue(source.contains("case \"use_remainder\" -> \"Use Remainder\""));
        assertTrue(source.contains("case \"damage_resistant\" -> \"Damage Resistant\""));
        assertTrue(source.contains("case \"weapon\" -> \"Weapon\""));
        assertTrue(source.contains("case \"has_consume_particles\" -> \"Show Particles\""));
        assertTrue(source.contains("attributeEditorDescription"));
        assertTrue(source.contains(".description(attributeEditorDescription(title))"));
        assertTrue(source.contains("Item Stacks Stored Inside This Container-Like Component"));
        assertTrue(source.contains("Blocks Or Tags This Item Is Allowed To Break In Adventure Mode"));
        assertTrue(source.contains("Primary Explosion Colors As Hex Values Or Picked Dye Colors"));
        assertTrue(source.contains("Searches The Server Enchantment Registry And Adds A Level One Entry"));
        assertTrue(source.contains("Searches The Attribute Registry And Adds A New Modifier Row"));
        assertTrue(source.contains("Searches The Block Registry And Adds A Block Predicate Row"));
        assertTrue(source.contains("editor.notifyTextChanged();"));
        assertTrue(source.contains("Unsupported Attribute"));
        assertTrue(source.contains("Remove It Or Use A Supported Attribute"));
        assertTrue(source.contains("hint(\"Edit Attributes\")"));
        assertFalse(source.contains("hint(\"Advanced\")"));
        assertFalse(source.contains("default -> \"Advanced\""));
        assertFalse(source.contains("Advanced Attribute"));
        assertFalse(source.contains("metadata.get(\"editableJson\")"));
        assertFalse(source.contains("Boolean.TRUE.equals(metadata.get(\"advanced\"))"));
        assertTrue(source.contains("attributePanel.keyPressed"));
        assertTrue(source.contains("attributePanel.textInput"));
        assertTrue(source.contains("rightStudioPanel(\"contentPanel\")"));
        assertTrue(source.contains(".collapsible(\"Content Inspector\")"));
        assertTrue(source.contains(".dismissible(\"Item Attributes\")"));
        assertTrue(source.contains("attributePanel.isLeftAnchored()"));
        assertTrue(source.contains("fitWidth -= attributePanel.layoutWidth(8)"));
        assertTrue(source.contains("fitWidth -= contentPanel.layoutWidth(8)"));
        assertTrue(source.contains("container.getScrollOffset()"));
        assertTrue(source.contains("container.setTargetScrollOffset(previousScroll)"));
        assertTrue(source.contains("private AnimatedWidget selectedAttributeRowWidget;"));
        assertTrue(source.contains("private void refreshAttributeDesigner(boolean preserveScroll)"));
        assertTrue(source.contains("selectedAttributeRowWidget = null;"));
        assertTrue(source.contains("container.scrollToWidget(selectedAttributeRowWidget)"));
        assertTrue(source.contains("container.setScrollOffset(0)"));
        assertTrue(source.contains("selectedAttributeRowWidget = state.row;"));
        assertTrue(source.contains("refreshAttributeComponentList(false);"));
        assertTrue(source.contains("searchPanel.focusFirstFocusableChild()"));
        assertFalse(source.contains("attributeComponentContainer"));
        assertFalse(source.contains("attributeComponentContainerHeight"));
        assertFalse(source.contains("Save Changes"));
        assertFalse(source.contains("Discard"));
        assertFalse(source.contains("Done"));
        assertFalse(source.contains("shown >= 80"));
        assertFalse(source.contains("More Components"));
        assertFalse(source.contains("\"Active\""));
        assertFalse(source.contains("\"Available\""));
        assertFalse(source.contains("\"Recommended\""));
        assertFalse(source.contains("\"Installed\""));
        assertFalse(source.contains("\"Filter\""));
        assertFalse(source.contains("\"Find\""));
        assertFalse(source.contains("\"Find Component\""));
        assertFalse(source.contains("\"Component Name\""));
        assertFalse(source.contains("\"Search Components\""));
        assertFalse(source.contains(".label(\"Find\")"));
        assertFalse(source.contains("return label.replace('_', ' ')"));
        assertFalse(source.contains("filter(item -> !\"Default\".equalsIgnoreCase(item.getGroup()))"));
        assertFalse(source.contains("filter(id -> !\"minecraft:custom_model_data\".equals(id))"));
        assertFalse(source.contains("new PopupWidget.Builder(\"Components\")"));
        assertFalse(source.contains("new PopupWidget.Builder(\"Rules\")"));
        assertFalse(source.contains("new PopupWidget.Builder(\"Text\")"));
        assertFalse(source.contains("showRulesPopup"));
        assertFalse(source.contains("showLegacyPopup"));
        assertFalse(source.contains("addContentActionRows"));
        assertFalse(source.contains("popupDropdown"));
        assertFalse(source.contains("activeAttributesPopup"));
        assertFalse(source.contains("setAntiOutOfBound(true)"));
        assertFalse(source.contains("builder.addRow(\"Raw\""));
        assertFalse(source.contains("Advanced JSON"));
        assertFalse(source.contains("activeAttributeRawDraft"));
        assertFalse(source.contains("componentOverride"));
        assertFalse(source.contains("attributePanelDropdowns"));
        assertFalse(source.contains("renderAttributePanelDropdownOverlays"));
        assertFalse(source.contains("clickExpandedAttributePanelDropdown"));
    }

    @Test
    void attributesEditorUsesServerDescriptionsWithoutGenericJsonEditor() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("metadata.get(\"schema\")"));
        assertTrue(source.contains("schemaMap"));
        assertTrue(source.contains("defaultValueForSchema"));
        assertTrue(source.contains("item.getDescription()"));
        assertFalse(source.contains("JsonParser"));
        assertFalse(source.contains("jsonToValue"));
        assertFalse(source.contains("attributeTextEditor"));
        assertFalse(source.contains("arrayControls"));
        assertFalse(source.contains("schemaFor"));
        assertFalse(source.contains("schemaChoices"));
    }

    @Test
    void listAttributeEditorsUseStructuredRowsInsteadOfTextAreas() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(methodBody(source, "private void addEnchantmentsEditorRows").contains("CodeEditorWidget"));
        assertFalse(methodBody(source, "private void addAttributeModifiersEditorRows").contains("CodeEditorWidget"));
        assertFalse(methodBody(source, "private void addItemStackListEditorRows").contains("CodeEditorWidget"));
        assertFalse(methodBody(source, "private void addBlockPredicateEditorRows").contains("CodeEditorWidget"));
        assertTrue(source.contains("attributeEntryRow(\"Enchantment \""));
        assertTrue(source.contains("attributeEntryRow(attributeValueLabel(\"server:minecraft:attribute\", type)"));
        assertTrue(source.contains("attributeEntryRow(\"Item \""));
        assertTrue(source.contains("attributeEntryRow(\"Block Rule \""));
    }

    private String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int brace = source.indexOf('{', start);
        assertTrue(brace > start, signature);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        return "";
    }

    @Test
    void obsoleteComponentsAreOnlyMigrationInputs() throws IOException {
        String source = Files.readString(SOURCE);

        String specialized = source.substring(source.indexOf("private boolean hasSpecializedAttributeEditor"), source.indexOf("private boolean matchesAttributeSearch"));
        String primary = source.substring(source.indexOf("private boolean isPrimaryAttributeForMaterial"), source.indexOf("private boolean materialContains"));
        String defaults = source.substring(source.indexOf("private Object defaultCommonComponentValue"), source.indexOf("private TitledRowWidget textRow"));
        String editors = source.substring(source.indexOf("private boolean addCommonAttributeEditorRows"), source.indexOf("private void addUnsupportedAttributeEditorRows"));

        assertFalse(specialized.contains("minecraft:hide_tooltip"));
        assertFalse(specialized.contains("minecraft:hide_additional_tooltip"));
        assertFalse(specialized.contains("minecraft:fire_resistant"));
        assertFalse(primary.contains("minecraft:hide_tooltip"));
        assertFalse(primary.contains("minecraft:hide_additional_tooltip"));
        assertFalse(primary.contains("minecraft:fire_resistant"));
        assertFalse(defaults.contains("minecraft:hide_tooltip"));
        assertFalse(defaults.contains("minecraft:hide_additional_tooltip"));
        assertFalse(defaults.contains("minecraft:fire_resistant"));
        assertFalse(editors.contains("minecraft:hide_tooltip"));
        assertFalse(editors.contains("minecraft:hide_additional_tooltip"));
        assertFalse(editors.contains("minecraft:fire_resistant"));
    }

    @Test
    void selectedAttributeEditorIsExpandedInsideCurrentAttributeRow() throws IOException {
        String source = Files.readString(SOURCE);

        int currentListIndex = source.indexOf("attributeSectionHeader(\"Current Attributes\"");
        int blockIndex = source.indexOf("addAttributeComponentBlock(container, displayComponents, id, catalog.get(id), rowWidth)");
        int rowIndex = source.indexOf("MountableButtonWidget row = attributeComponentRow(id, components, item, rowWidth)");
        int editorIndex = source.indexOf("state.row.setEmbeddedBody(state.editorWidgets, true)");
        int refreshIndex = source.indexOf("private void refreshAttributeComponentList(boolean preserveScroll)");

        assertTrue(currentListIndex >= 0);
        assertTrue(blockIndex > currentListIndex);
        assertTrue(rowIndex >= 0);
        assertTrue(editorIndex > rowIndex);
        assertTrue(refreshIndex >= 0);
        assertFalse(source.contains("attributeSectionHeader(\"Attribute Library\""));
        assertFalse(source.contains("attributeSectionHeader(\"Editor\""));
        assertFalse(source.contains("addSelectedAttributeEditor"));
    }

    @Test
    void embeddedAttributeBodyRendersBehindTheRowHeader() throws IOException {
        String mountableSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/MountableButtonWidget.java"));
        int drawContentStart = mountableSource.indexOf("protected void drawContent(IDrawContext ctx, int mouseX, int mouseY)");
        int drawContentEnd = mountableSource.indexOf("protected void renderEmbeddedContent", drawContentStart);
        assertTrue(drawContentStart >= 0);
        assertTrue(drawContentEnd > drawContentStart);
        String drawContent = mountableSource.substring(drawContentStart, drawContentEnd);

        int bodyRenderIndex = drawContent.indexOf("renderEmbeddedContent(ctx, mouseX, mouseY);");
        int textRenderIndex = drawContent.indexOf("ctx.drawText(trimmedName");
        int mountedRenderIndex = drawContent.lastIndexOf("widget.render(ctx, mouseX, mouseY, deltaTime);");

        assertTrue(bodyRenderIndex >= 0);
        assertTrue(textRenderIndex > bodyRenderIndex);
        assertTrue(mountedRenderIndex > textRenderIndex);
        assertTrue(mountableSource.indexOf("widget.render(ctx, mouseX, mouseY, deltaTime);", mountableSource.indexOf("protected void renderEmbeddedContent")) >= 0);
        assertTrue(mountableSource.contains("updateEmbeddedBodyBounds();"));
        assertTrue(mountableSource.contains("isOverEmbeddedBody(mouseX, mouseY)"));
    }

    @Test
    void selectingActiveAttributeDoesNotReplaceTheAttributeList() throws IOException {
        String source = Files.readString(SOURCE);
        String toggleSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/widgets/ToggleWidget.java"));
        int methodStart = source.indexOf("private void handleAttributeComponentClick(String id)");
        int methodEnd = source.indexOf("\n    private void setAttributeComponentEnabled", methodStart);
        int setValueStart = toggleSource.indexOf("public void setValue(boolean value)");
        int setValueEnd = toggleSource.indexOf("\n    public void toggle()", setValueStart);
        assertTrue(methodStart >= 0);
        assertTrue(methodEnd > methodStart);
        assertTrue(setValueStart >= 0);
        assertTrue(setValueEnd > setValueStart);
        String method = source.substring(methodStart, methodEnd);
        String setValueMethod = toggleSource.substring(setValueStart, setValueEnd);

        assertTrue(method.contains("syncAttributeRowsInPlace();"));
        assertTrue(method.contains("ensureAttributePreviewValue(id, state != null ? state.item : null);"));
        assertTrue(method.contains("container.updateWidgetPositions();"));
        assertTrue(method.contains("container.scrollToWidget(selectedAttributeRowWidget);"));
        assertFalse(method.contains("addDefaultComponent(current"));
        assertFalse(method.contains("refreshAttributeComponentList(false);"));
        assertFalse(setValueMethod.contains("onChange"));
    }

    @Test
    void editingNestedAttributeValuesDoesNotReplaceTheAttributeList() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private void updateNestedAttributeComponent(");
        int methodEnd = source.indexOf("\n    @SuppressWarnings(\"unchecked\")", methodStart);
        assertTrue(methodStart >= 0);
        assertTrue(methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(method.contains("boolean wasActive = draft.containsKey(componentId);"));
        assertTrue(method.contains("attributePreviewValues.put(componentId, updated);"));
        assertTrue(method.contains("syncAttributeRowsInPlace();"));
        assertTrue(method.contains("commitAttributeDesignerDraft(draft);"));
        assertTrue(method.contains("if (wasActive != draft.containsKey(componentId))"));
        assertTrue(method.contains("refreshAttributeComponentList(false);"));
        assertFalse(method.contains("updateAttributeDesignerDraft(draft);"));
    }

    @Test
    void sidePanelScrollbarTakesPriorityOverResizeHandle() throws IOException {
        String containerSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/rescreen/Container.java"));
        String sidePanelSource = Files.readString(reScreenSource("src/main/java/restudio/rescreen/ui/rescreen/SidePanel.java"));

        assertTrue(containerSource.contains("int lineWidth = Math.max(4, scrollbarWidth);"));
        assertTrue(containerSource.contains("public boolean isMouseOverScrollbar(double mouseX, double mouseY)"));
        assertTrue(containerSource.contains("if (startScrollbarInteraction(event))"));
        assertTrue(containerSource.contains("return getX() + getEffectiveWidth() + 2;"));
        assertTrue(sidePanelSource.contains("int resizeHandleX = anchor == Anchor.LEFT ? panelX + (int) animatedWidth : panelX - 1;"));
        int scrollbarGuardIndex = sidePanelSource.indexOf("if (innerContainer.isMouseOverScrollbar(mouseX, mouseY))");
        int resizeXIndex = sidePanelSource.indexOf("int resizeHandleX = anchor == Anchor.LEFT");
        int containerClickIndex = sidePanelSource.indexOf("if (Widget.dispatchMouseClicked(innerContainer, event.retarget(innerContainer, mouseX, mouseY)))");
        int resizeStartIndex = sidePanelSource.indexOf("isResizing = true;");

        assertTrue(scrollbarGuardIndex >= 0);
        assertTrue(resizeXIndex > scrollbarGuardIndex);
        assertTrue(containerClickIndex >= 0);
        assertTrue(resizeStartIndex < containerClickIndex);
        assertTrue(sidePanelSource.contains("width(newWidth);"));
        assertFalse(sidePanelSource.contains("desiredWidth = Math.max(minWidth, Math.min(newWidth, host.getWidth() * maxWidthRatio / 100));"));
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static Path reScreenSource(String relativePath) {
        return RESCREEN_SOURCE.resolve(relativePath);
    }
}
