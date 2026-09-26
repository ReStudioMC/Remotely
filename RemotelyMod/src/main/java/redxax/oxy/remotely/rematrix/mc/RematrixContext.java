package redxax.oxy.remotely.rematrix.mc;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
//#if MC >= 1.21.9 || MC >= 26.1
import net.minecraft.core.ClientAsset;
//#endif
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#endif
//#if MC >= 1.20.1 && MC < 26.1
//$$ import net.minecraft.client.gui.GuiGraphics;
//#endif
//#if MC >= 1.21.6 && MC < 26.1
//$$ import net.minecraft.client.gui.screens.Screen;
//$$ import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
//$$ import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
//#endif
//#if MC < 1.21.4
//$$ import net.minecraft.client.renderer.GameRenderer;
//#endif
//#if MC >= 1.21.5 || MC >= 26.1
import net.minecraft.client.renderer.RenderPipelines;
//#endif
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
//#if MC >= 26.1
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//#endif
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//#if MC >= 1.21.1
import net.minecraft.core.registries.BuiltInRegistries;
//#endif
//#if MC < 1.21.1
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//#endif
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
//#if MC >= 1.21.9 || MC >= 26.1
import net.minecraft.network.chat.FontDescription;
//#endif
//#if MC < 1.21.6 && MC < 26.1
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
//#if MC < 1.21.6 && MC < 26.1
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//#endif
//#if MC >= 1.21.11 || MC >= 26.1
import net.minecraft.resources.Identifier;
//#endif
//#if MC < 1.21.11 && MC < 26.1
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
//#if MC >= 1.21.1
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
//#endif
//#if MC >= 1.21.1 && MC < 1.21.9
//$$ import net.minecraft.client.resources.PlayerSkin;
//#endif
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
//#if MC >= 1.21.4 || MC >= 26.1
import net.minecraft.world.entity.EntitySpawnReason;
//#endif
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
//#if MC >= 1.21.9 || MC >= 26.1
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
//#endif
//#if MC >= 1.21.1 && MC < 1.21.9
//$$ import net.minecraft.world.entity.player.PlayerModelPart;
//#endif
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
//#if MC < 1.21.1
//$$ import net.minecraft.nbt.CompoundTag;
//$$ import net.minecraft.nbt.ListTag;
//$$ import net.minecraft.nbt.StringTag;
//#endif
//#if MC < 1.20.1
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.gui.GuiComponent;
//#endif
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import redxax.oxy.remotely.rematrix.*;
import redxax.oxy.remotely.rematrix.ReContext;
import redxax.restudio.Remodel.util.SkinFetcher;
import restudio.rescreen.debug.DebugDrawStats;
import restudio.rescreen.game.MinecraftGameItem;
import restudio.rescreen.game.MinecraftGameEntity;
import restudio.rescreen.game.MinecraftGameEntities;
import restudio.rescreen.game.MinecraftGameItems;
import restudio.rescreen.game.MinecraftRenderEntity;
import restudio.rescreen.game.tooltip.MinecraftTextComponent;
import restudio.rescreen.game.tooltip.MinecraftTextComponents;
import restudio.rescreen.game.tooltip.MinecraftTooltip;
import restudio.rescreen.game.tooltip.MinecraftTooltipLine;
import restudio.rescreen.game.MinecraftRenderItem;
import restudio.rescreen.platform.ClipRect;
import restudio.rescreen.text.StyledText;
import restudio.rescreen.util.ResourceManager;

public final class RematrixContext implements ReContext {
    private static final Map<BufferedImage, CachedTexture> TEXTURE_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int STYLED_TEXT_CACHE_LIMIT = 4_096;
    private static final Map<StyledTextKey, Component> STYLED_TEXT_CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static final Consumer<BufferedImage> IMAGE_RELEASE_LISTENER = RematrixContext::releaseTexture;
    private static final Map<MinecraftRenderItem, ItemStack> ITEM_STACK_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final long PLAYER_SKIN_RETRY_DELAY_MS = 60000L;
    private static final Map<String, BufferedImage> PLAYER_SKIN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> PLAYER_SKIN_FAILURES = new ConcurrentHashMap<>();
    private static final Set<String> PLAYER_SKIN_FETCHING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger PREVIEW_ENTITY_IDS = new AtomicInteger(-1);
    private static final int SELECTION_COLOR = 0xFF0000FF;
    private static final float PLAYER_HEAD_MOUSE_Y_OFFSET = 0.32f;

    static {
        ResourceManager.getInstance().addImageReleaseListener(IMAGE_RELEASE_LISTENER);
    }

    //#if MC >= 26.1
    private final GuiGraphicsExtractor graphics;
    //#endif
    //#if MC >= 1.20.1 && MC < 26.1
    //$$ private final GuiGraphics graphics;
    //#endif
    //#if MC < 1.20.1
    //$$ private final PoseStack graphics;
    //#endif
    private final float scissorScale;
    private final ReMatrixStack matrices;
    private final ReScissorStack scissors;
    private int nativeScissorDepth;
    private final ReTextureCache textures;
    private final ReTextBridge textBridge;
    private final Map<MinecraftRenderEntity, Entity> entityCache = Collections.synchronizedMap(new WeakHashMap<>());
    private Object entityCacheLevel;
    private EntityRenderDispatcher entityRenderDispatcher;
    private Method entityRenderStateExtractor;
    private Method guiEntityRenderer;
    private boolean entityRenderDispatcherChecked;
    private boolean entityRenderStateExtractorChecked;
    private boolean guiEntityRendererChecked;
    private Method inventoryEntityRenderer;
    private boolean inventoryEntityRendererChecked;
    private Method inventoryMouseEntityRenderer;
    private boolean inventoryMouseEntityRendererChecked;

    //#if MC >= 26.1
    public RematrixContext(@NotNull GuiGraphicsExtractor graphics) {
        this(graphics, 1.0f);
    }
    //#endif
    //#if MC >= 1.20.1 && MC < 26.1
    //$$ public RematrixContext(@NotNull GuiGraphics graphics) {
    //$$     this(graphics, 1.0f);
    //$$ }
    //#endif
    //#if MC < 1.20.1
    //$$ public RematrixMcContext(@NotNull PoseStack graphics) {
    //$$     this(graphics, 1.0f);
    //$$ }
    //#endif

    //#if MC >= 26.1
    public RematrixContext(@NotNull GuiGraphicsExtractor graphics, float scissorScale) {
        this.graphics = graphics;
        this.scissorScale = scissorScale > 0f ? scissorScale : 1f;
        this.matrices = new McMatrixStack(graphics.pose());
        this.scissors = new McScissorStack();
        this.textBridge = new McTextBridge();
        this.textures = new McTextureCache();
    }
    //#endif
    //#if MC >= 1.20.1 && MC < 26.1
    //$$ public RematrixContext(@NotNull GuiGraphics graphics, float scissorScale) {
    //$$     this.graphics = graphics;
    //$$     this.scissorScale = scissorScale > 0f ? scissorScale : 1f;
    //$$     this.matrices = new McMatrixStack(graphics.pose());
    //$$     this.scissors = new McScissorStack();
    //$$     this.textBridge = new McTextBridge();
    //$$     this.textures = new McTextureCache();
    //$$ }
    //#endif
    //#if MC < 1.20.1
    //$$ public RematrixMcContext(@NotNull PoseStack graphics, float scissorScale) {
    //$$     this.graphics = graphics;
    //$$     this.scissorScale = scissorScale > 0f ? scissorScale : 1f;
    //$$     this.matrices = new McMatrixStack(graphics);
    //$$     this.scissors = new McScissorStack();
    //$$     this.textBridge = new McTextBridge();
    //$$     this.textures = new McTextureCache();
    //$$ }
    //#endif

    @Override
    public ReMatrixStack matrices() {
        return matrices;
    }

    @Override
    public ReScissorStack scissors() {
        return scissors;
    }

    public ClipRect viewportBounds() {
        var window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale() * scissorScale;
        return scale > 0 ? new ClipRect(0, 0, window.getWidth() / scale, window.getHeight() / scale) : null;
    }

    @Override
    public ReTextureCache textures() {
        return textures;
    }

    @Override
    public ReTextBridge text() {
        return textBridge;
    }

    @Override
    public Object graphics() {
        return graphics;
    }

    @Override
    public void drawItem(Object item, int x, int y, int z) {
        if (item == null) return;
        ItemStack renderStack = null;
        if (item instanceof ItemStack stack) {
            renderStack = stack;
        } else {
            MinecraftRenderItem renderItem = adaptRenderItem(item);
            renderStack = ITEM_STACK_CACHE.get(renderItem);
            if (renderStack == null) {
                renderStack = createItemStack(renderItem);
                if (renderStack != null && !renderStack.isEmpty()) {
                    ITEM_STACK_CACHE.put(renderItem, renderStack);
                }
            }
        }
        if (renderStack == null || renderStack.isEmpty()) return;
        renderItemWithScissor(renderStack, x, y, z);
    }

    @Override
    public void drawItemPreview(Object item, int x, int y, int z, float scale, float rotationX, float rotationY, boolean paused) {
        if (item == null) return;
        matrices.push();
        matrices.translate(x + 8.0f, y + 8.0f, z);
        matrices.scale(scale, scale, scale);
        matrices.rotate(rotationX, 1.0f, 0.0f, 0.0f);
        matrices.rotate(rotationY, 0.0f, 1.0f, 0.0f);
        drawItem(item, -8, -8, z);
        matrices.pop();
    }

    @Override
    public void drawEntity(Object entity, int x, int y, int z, int size) {
        drawEntityPreview(entity, x, y, z, size, 30.0f, 0.0f, false);
    }

    @Override
    public void drawPlayer(Object player, int x, int y, int z, int size) {
        drawPlayerPreview(player, x, y, z, size, 30.0f, 0.0f, false);
    }

    @Override
    public void drawEntityPreview(Object entity, int x, int y, int z, int size, float yaw, float pitch, boolean paused) {
        if (entity == null || size <= 0) return;
        Entity renderEntity = adaptEntityInstance(entity, false);
        if (renderEntity == null) return;
        renderEntityUnclipped(renderEntity, x, y, z, size, yaw, pitch);
    }

    @Override
    public void drawPlayerPreview(Object player, int x, int y, int z, int size, float yaw, float pitch, boolean paused) {
        if (size <= 0) return;
        MinecraftRenderEntity renderEntity = adaptRenderPlayer(player);
        Entity playerEntity = adaptEntityInstance(renderEntity, true);
        if (playerEntity == null) return;
        renderEntityUnclipped(playerEntity, x, y, z, size, yaw, pitch);
    }

    @Override
    public void drawEntityMousePreview(Object entity, int x, int y, int z, int size, int mouseX, int mouseY, boolean paused) {
        drawEntityRelativeMousePreview(entity, x, y, z, size, mouseX - (x + size / 2.0f), mouseY - (y + size / 2.0f), paused);
    }

    @Override
    public void drawPlayerMousePreview(Object player, int x, int y, int z, int size, int mouseX, int mouseY, boolean paused) {
        drawPlayerRelativeMousePreview(player, x, y, z, size, mouseX - (x + size / 2.0f), mouseY - (y + size / 2.0f), paused);
    }

    @Override
    public void drawEntityRelativeMousePreview(Object entity, int x, int y, int z, int size, float relativeMouseX, float relativeMouseY, boolean paused) {
        if (entity == null || size <= 0) return;
        Entity renderEntity = adaptEntityInstance(entity, false);
        if (renderEntity == null) return;
        float yaw = (float) Math.atan(-relativeMouseX / 40.0f) * 20.0f;
        float pitch = (float) Math.atan(-relativeMouseY / 40.0f) * 20.0f;
        renderEntityUnclipped(renderEntity, x, y, z, size, yaw, pitch);
    }

    @Override
    public void drawPlayerRelativeMousePreview(Object player, int x, int y, int z, int size, float relativeMouseX, float relativeMouseY, boolean paused) {
        if (size <= 0) return;
        MinecraftRenderEntity renderEntity = adaptRenderPlayer(player);
        Entity playerEntity = adaptEntityInstance(renderEntity, true);
        if (playerEntity == null) return;
        int mouseX = Math.round(x + size / 2.0f + relativeMouseX);
        int mouseY = Math.round(y + size / 2.0f + relativeMouseY + playerPreviewHeadMouseYOffset(size));
        renderEntityUnclipped(playerEntity, x, y, z, size, 0.0f, 0.0f, mouseX, mouseY);
    }

    @Override
    public void drawMinecraftItemTooltip(Object item, MinecraftTooltip fallback, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        ItemStack stack = null;
        if (item instanceof ItemStack itemStack) {
            stack = itemStack;
        } else {
            MinecraftRenderItem renderItem = adaptRenderItem(item);
            if (renderItem != null) {
                stack = ITEM_STACK_CACHE.get(renderItem);
                if (stack == null) {
                    stack = createItemStack(renderItem);
                    if (stack != null && !stack.isEmpty()) {
                        ITEM_STACK_CACHE.put(renderItem, stack);
                    }
                }
            }
        }
        if (stack != null && !stack.isEmpty()) {
            ItemStack tooltipStack = stack;
            drawTooltipOnTop(() -> {
                //#if MC >= 26.1
                graphics.setTooltipForNextFrame(Minecraft.getInstance().font, tooltipStack,
                    Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale));
                //#endif
                //#if MC >= 1.21.11 && MC < 26.1
                //$$ Identifier tooltipStyle = tooltipStack.get(DataComponents.TOOLTIP_STYLE);
                //$$ graphics.renderTooltip(Minecraft.getInstance().font, createClientTooltipComponents(Screen.getTooltipFromItem(Minecraft.getInstance(), tooltipStack), tooltipStack.getTooltipImage()), Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale), DefaultTooltipPositioner.INSTANCE, tooltipStyle);
                //#endif
                //#if MC >= 1.21.6 && MC < 1.21.11
                //$$ ResourceLocation tooltipStyle = tooltipStack.get(DataComponents.TOOLTIP_STYLE);
                //$$ graphics.renderTooltip(Minecraft.getInstance().font, createClientTooltipComponents(Screen.getTooltipFromItem(Minecraft.getInstance(), tooltipStack), tooltipStack.getTooltipImage()), Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale), DefaultTooltipPositioner.INSTANCE, tooltipStyle);
                //#endif
                //#if MC >= 1.20.1 && MC < 1.21.6
                //$$ graphics.renderTooltip(Minecraft.getInstance().font, tooltipStack, Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale));
                //#endif
                //#if MC < 1.20.1
                //$$ graphics.renderTooltip(Minecraft.getInstance().font, tooltipStack, Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale));
                //#endif
            });
            return;
        }
        drawMinecraftTooltip(fallback, mouseX, mouseY, screenWidth, screenHeight);
    }

    @Override
    public void drawMinecraftTooltip(MinecraftTooltip tooltip, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        for (MinecraftTooltipLine line : tooltip.lines()) {
            lines.add(toNativeComponent(line.component()));
        }
        if (lines.isEmpty()) {
            return;
        }
        drawTooltipOnTop(() -> {
            //#if MC >= 26.1
            graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines,
                Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale));
            //#endif
            //#if MC >= 1.21.11 && MC < 26.1
            //$$ graphics.renderTooltip(Minecraft.getInstance().font, createClientTooltipComponents(lines, Optional.empty()), Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale), DefaultTooltipPositioner.INSTANCE, (Identifier) null);
            //#endif
            //#if MC >= 1.21.6 && MC < 1.21.11
            //$$ graphics.renderTooltip(Minecraft.getInstance().font, createClientTooltipComponents(lines, Optional.empty()), Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale), DefaultTooltipPositioner.INSTANCE, (ResourceLocation) null);
            //#endif
            //#if MC >= 1.20.1 && MC < 1.21.6
            //$$ graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale));
            //#endif
            //#if MC < 1.20.1
            //$$ graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, Math.round(mouseX * scissorScale), Math.round(mouseY * scissorScale));
            //#endif
        });
    }

    //#if MC >= 1.21.6 && MC < 26.1
    //$$ private List<ClientTooltipComponent> createClientTooltipComponents(List<Component> lines, Optional<TooltipComponent> image) {
    //$$     List<ClientTooltipComponent> components = new ArrayList<>(lines.size() + (image.isPresent() ? 1 : 0));
    //$$     for (Component line : lines) {
    //$$         components.add(ClientTooltipComponent.create(line.getVisualOrderText()));
    //$$     }
    //$$     image.ifPresent(tooltipComponent -> components.add(Math.min(1, components.size()), ClientTooltipComponent.create(tooltipComponent)));
    //$$     return components;
    //$$ }
    //#endif

    private Component toNativeComponent(MinecraftTextComponent component) {
        if (component == null) {
            return Component.empty();
        }
        MutableComponent nativeComponent;
        if (component.sprite() != null && component.sprite().id() != null) {
            nativeComponent = Component.literal(component.sprite().id());
        } else if (component.translate() != null && !component.translate().isBlank()) {
            nativeComponent = Component.translatable(component.translate(), component.with().stream().map(this::toNativeComponent).toArray());
        } else {
            String text = component.text();
            if (text == null) {
                text = component.fallback();
            }
            nativeComponent = Component.literal(text != null ? text : "");
        }
        nativeComponent.setStyle(toNativeStyle(component));
        for (MinecraftTextComponent extra : component.extra()) {
            nativeComponent.append(toNativeComponent(extra));
        }
        return nativeComponent;
    }

    private Style toNativeStyle(MinecraftTextComponent component) {
        Style style = Style.EMPTY;
        if (component.color() != null) {
            style = style.withColor(component.color() & 0xFFFFFF);
        }
        if (component.bold() != null) {
            style = style.withBold(component.bold());
        }
        if (component.italic() != null) {
            style = style.withItalic(component.italic());
        }
        if (component.underlined() != null) {
            style = style.withUnderlined(component.underlined());
        }
        if (component.strikethrough() != null) {
            style = style.withStrikethrough(component.strikethrough());
        }
        if (component.obfuscated() != null) {
            style = style.withObfuscated(component.obfuscated());
        }
        if (component.font() != null && !component.font().isBlank()) {
            String font = component.font();
            String namespace = "minecraft";
            String path = font;
            if (font.contains(":")) {
                String[] parts = font.split(":", 2);
                namespace = parts[0];
                path = parts.length > 1 ? parts[1] : "";
            }
            //#if MC >= 1.21.11 || MC >= 26.1
            Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
            //#endif
            //#if MC >= 1.21.1 && MC < 1.21.11
            //$$ ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
            //#endif
            //#if MC < 1.21.1
            //$$ ResourceLocation id = new ResourceLocation(namespace, path);
            //#endif
            //#if MC >= 1.21.9 || MC >= 26.1
            style = style.withFont(new FontDescription.Resource(id));
            //#endif
            //#if MC < 1.21.9 && MC < 26.1
            //$$ style = style.withFont(id);
            //#endif
        }
        return style;
    }

    private MinecraftRenderItem adaptRenderItem(Object item) {
        if (item instanceof MinecraftRenderItem renderItem) {
            return renderItem;
        }
        if (item instanceof MinecraftGameItem gameItem) {
            return gameItem.asRenderItem();
        }
        if (item instanceof String id) {
            return MinecraftRenderItem.of(id, 1);
        }
        if (item instanceof Map<?, ?> map) {
            return MinecraftGameItems.fromMap(map);
        }
        return null;
    }

    private Entity adaptEntityInstance(Object entity, boolean forcePlayer) {
        if (entity instanceof Entity nativeEntity) {
            return nativeEntity;
        }
        MinecraftRenderEntity renderEntity = forcePlayer ? adaptRenderPlayer(entity) : adaptRenderEntity(entity);
        if (renderEntity == null) {
            return null;
        }
        return createEntity(renderEntity);
    }

    private MinecraftRenderEntity adaptRenderPlayer(Object player) {
        if (player instanceof MinecraftRenderEntity renderEntity) {
            return new MinecraftRenderEntity("minecraft:player", renderEntity.name(), renderEntity.texture(), renderEntity.skin(), renderEntity.slim(), renderEntity.baby(), renderEntity.tag());
        }
        if (player instanceof MinecraftGameEntity gameEntity) {
            MinecraftRenderEntity renderEntity = gameEntity.asRenderEntity();
            return new MinecraftRenderEntity("minecraft:player", renderEntity.name(), renderEntity.texture(), renderEntity.skin(), renderEntity.slim(), renderEntity.baby(), renderEntity.tag());
        }
        if (player instanceof String name) {
            return MinecraftGameEntities.player(name);
        }
        if (player instanceof Map<?, ?> map) {
            return MinecraftGameEntities.playerFromMap(map);
        }
        return MinecraftGameEntities.player(null);
    }

    private MinecraftRenderEntity adaptRenderEntity(Object entity) {
        if (entity instanceof MinecraftRenderEntity renderEntity) {
            return renderEntity;
        }
        if (entity instanceof MinecraftGameEntity gameEntity) {
            return gameEntity.asRenderEntity();
        }
        if (entity instanceof String id) {
            return MinecraftRenderEntity.of(id);
        }
        if (entity instanceof Map<?, ?> map) {
            return MinecraftGameEntities.fromMap(map);
        }
        return null;
    }

    private Entity createEntity(MinecraftRenderEntity renderEntity) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = currentEntityLevel(minecraft);
        if (entityCacheLevel != level) {
            entityCache.clear();
            entityCacheLevel = level;
        }
        if (renderEntity.isPlayer()) {
            return createPlayerEntity(renderEntity);
        }
        if (level == null || renderEntity.id() == null) {
            return null;
        }
        Entity cached = entityCache.get(renderEntity);
        if (cached != null) {
            applyRenderEntityData(cached, renderEntity);
            prepareSyntheticPreviewEntity(cached);
            return cached;
        }
        String namespace = "minecraft";
        String path = renderEntity.id();
        if (path.contains(":")) {
            String[] parts = path.split(":", 2);
            namespace = parts[0];
            path = parts.length > 1 ? parts[1] : "";
        }
        if (!isValidMinecraftNamespace(namespace) || !isValidMinecraftPath(path)) {
            return null;
        }
        //#if MC >= 1.21.11 || MC >= 26.1
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        //#endif
        //#if MC >= 1.21.1 && MC < 1.21.11
        //$$ ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        //#endif
        //#if MC < 1.21.1
        //$$ ResourceLocation id = new ResourceLocation(namespace, path);
        //#endif
        Optional<EntityType<?>> optionalType = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (optionalType.isEmpty()) {
            return null;
        }
        EntityType<?> type = optionalType.get();
        //#if MC >= 1.21.4 || MC >= 26.1
        Entity entity = type.create(level, EntitySpawnReason.COMMAND);
        //#endif
        //#if MC < 1.21.4 && MC < 26.1
        //$$ Entity entity = type.create(level);
        //#endif
        if (entity != null) {
            assignPreviewEntityId(entity);
            applyRenderEntityData(entity, renderEntity);
            prepareSyntheticPreviewEntity(entity);
            entityCache.put(renderEntity, entity);
            return entity;
        }
        return null;
    }

    private Level currentEntityLevel(Minecraft minecraft) {
        if (minecraft.level != null) {
            return minecraft.level;
        }
        return minecraft.player != null ? minecraft.player.level() : null;
    }

    private LivingEntity createPlayerEntity(MinecraftRenderEntity renderEntity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (isCurrentPlayerRequest(renderEntity)) {
            return minecraft.player;
        }
        if (minecraft.level == null) {
            return minecraft.player;
        }
        String name = renderEntity.name() != null && !renderEntity.name().isBlank() ? renderEntity.name() : "Player";
        String skinUsername = previewPlayerSkinUsername(renderEntity);
        String profileName = skinUsername != null && !skinUsername.isBlank() ? skinUsername : name;
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + profileName).getBytes(StandardCharsets.UTF_8));
        Entity cached = entityCache.get(renderEntity);
        if (cached instanceof LivingEntity livingEntity) {
            if (shouldRecreatePlayerEntity(renderEntity, cached)) {
                entityCache.remove(renderEntity);
            } else {
                applyRenderEntityData(livingEntity, renderEntity);
                prepareSyntheticPreviewEntity(livingEntity);
                return livingEntity;
            }
        }
        //#if MC >= 1.21.1 || MC >= 26.1
        PlayerSkin skin = previewPlayerSkin(renderEntity);
        RemotePlayer player = skin != null ? new PreviewRemotePlayer(minecraft.level, new GameProfile(uuid, profileName), skin) : new RemotePlayer(minecraft.level, new GameProfile(uuid, profileName));
        //#endif
        //#if MC < 1.21.1 && MC < 26.1
        //$$ RemotePlayer player = new RemotePlayer(minecraft.level, new GameProfile(uuid, profileName));
        //#endif
        assignPreviewEntityId(player);
        applyRenderEntityData(player, renderEntity);
        prepareSyntheticPreviewEntity(player);
        entityCache.put(renderEntity, player);
        return player;
    }

    private boolean shouldRecreatePlayerEntity(MinecraftRenderEntity renderEntity, Entity cached) {
        //#if MC >= 1.21.1 || MC >= 26.1
        return !(cached instanceof PreviewRemotePlayer) && previewPlayerSkinImage(renderEntity) != null;
        //#endif
        //#if MC < 1.21.1 && MC < 26.1
        //$$ return false;
        //#endif
    }

    //#if MC >= 1.21.1 || MC >= 26.1
    private PlayerSkin previewPlayerSkin(MinecraftRenderEntity renderEntity) {
        BufferedImage image = previewPlayerSkinImage(renderEntity);
        if (image == null) {
            return null;
        }
        ReTextureHandle handle = textures.getTexture(image);
        //#if MC >= 1.21.11 || MC >= 26.1
        if (!(handle.getId() instanceof Identifier id)) {
            return null;
        }
        ClientAsset.Texture texture = new ClientAsset.Texture() {
            @Override
            public Identifier id() {
                return id;
            }

            @Override
            public Identifier texturePath() {
                return id;
            }
        };
        //#endif
        //#if MC >= 1.21.9 && MC < 1.21.11
        //$$ if (!(handle.getId() instanceof ResourceLocation id)) {
        //$$     return null;
        //$$ }
        //$$ ClientAsset.Texture texture = new ClientAsset.Texture() {
        //$$     @Override
        //$$     public ResourceLocation id() {
        //$$         return id;
        //$$     }
        //$$
        //$$     @Override
        //$$     public ResourceLocation texturePath() {
        //$$         return id;
        //$$     }
        //$$ };
        //#endif
        //#if MC >= 1.21.9 || MC >= 26.1
        PlayerModelType modelType = renderEntity.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        return new PlayerSkin(texture, null, null, modelType, false);
        //#endif
        //#if MC >= 1.21.1 && MC < 1.21.9
        //$$ if (!(handle.getId() instanceof ResourceLocation id)) {
        //$$     return null;
        //$$ }
        //$$ PlayerSkin.Model modelType = renderEntity.slim() ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        //$$ return new PlayerSkin(id, "", null, null, modelType, false);
        //#endif
    }
    //#endif

    private BufferedImage previewPlayerSkinImage(MinecraftRenderEntity renderEntity) {
        var skinId = renderEntity.skin();
        if (skinId != null) {
            BufferedImage image = ResourceManager.getInstance().resolveImage(skinId);
            if (image != null) {
                return image;
            }
        }
        String username = previewPlayerSkinUsername(renderEntity);
        if (username == null || username.isBlank()) {
            return null;
        }
        String key = username.toLowerCase(Locale.ROOT);
        BufferedImage cached = PLAYER_SKIN_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        long lastFailure = PLAYER_SKIN_FAILURES.getOrDefault(key, 0L);
        if (lastFailure > 0L && System.currentTimeMillis() - lastFailure < PLAYER_SKIN_RETRY_DELAY_MS) {
            return null;
        }
        if (PLAYER_SKIN_FETCHING.add(key)) {
            String fetchUsername = username;
            CompletableFuture.supplyAsync(() -> SkinFetcher.getSkin(fetchUsername))
                .thenAccept(skin -> {
                    if (skin != null) {
                        PLAYER_SKIN_CACHE.put(key, skin);
                        PLAYER_SKIN_FAILURES.remove(key);
                    } else {
                        PLAYER_SKIN_FAILURES.put(key, System.currentTimeMillis());
                    }
                    PLAYER_SKIN_FETCHING.remove(key);
                })
                .exceptionally(error -> {
                    PLAYER_SKIN_FAILURES.put(key, System.currentTimeMillis());
                    PLAYER_SKIN_FETCHING.remove(key);
                    return null;
                });
        }
        return null;
    }

    private String previewPlayerSkinUsername(MinecraftRenderEntity renderEntity) {
        Map<String, Object> tag = renderEntity.tag();
        if (tag != null && !tag.isEmpty()) {
            Object skin = tag.get("skin");
            if (skin instanceof Map<?, ?> skinMap) {
                String username = stringValue(skinMap.get("username"));
                if (username != null && !username.isBlank()) {
                    return username;
                }
            }
            String username = stringValue(tag.get("skin.username"));
            if (username == null || username.isBlank()) {
                username = stringValue(tag.get("skinUsername"));
            }
            if (username != null && !username.isBlank()) {
                return username;
            }
        }
        return renderEntity.name();
    }

    private boolean isCurrentPlayerRequest(MinecraftRenderEntity renderEntity) {
        return renderEntity.name() == null
            && renderEntity.texture() == null
            && renderEntity.skin() == null
            && !renderEntity.slim()
            && !renderEntity.baby()
            && renderEntity.tag().isEmpty();
    }

    private void applyRenderEntityData(Entity entity, MinecraftRenderEntity renderEntity) {
        String name = renderEntity.name();
        Map<String, Object> tag = renderEntity.tag();
        if ((name == null || name.isBlank()) && tag != null) {
            name = stringValue(tag.get("CustomName"));
            if (name == null) {
                name = stringValue(tag.get("customName"));
            }
        }
        if (name != null && !name.isBlank()) {
            entity.setCustomName(Component.literal(name));
        }
        if (entity instanceof LivingEntity livingEntity) {
            boolean baby = renderEntity.baby();
            if (!baby && tag != null) {
                baby = booleanValue(tag.get("IsBaby"), booleanValue(tag.get("baby"), false));
            }
            applyBabyFlag(livingEntity, baby);
            applyEquipment(livingEntity, tag);
        }
    }

    private void prepareSyntheticPreviewEntity(Entity entity) {
        entity.setPos(0.0d, 0.0d, 0.0d);
        entity.xo = entity.getX();
        entity.yo = entity.getY();
        entity.zo = entity.getZ();
        entity.xOld = entity.getX();
        entity.yOld = entity.getY();
        entity.zOld = entity.getZ();
        entity.tickCount = 0;
    }

    private void assignPreviewEntityId(Entity entity) {
        //#if MC >= 26.2
        entity.setId(PREVIEW_ENTITY_IDS.getAndDecrement());
        //#endif
    }

    private void applyEquipment(LivingEntity entity, Map<String, Object> tag) {
        Object equipment = tag != null ? tag.get("equipment") : null;
        Map<?, ?> equipmentMap = equipment instanceof Map<?, ?> map ? mergedEquipmentMap(tag, map) : tag != null ? tag : Map.of();
        applyEquipmentSlot(entity, equipmentMap, EquipmentSlot.MAINHAND, "mainHand", "main_hand", "mainhand", "hand");
        applyEquipmentSlot(entity, equipmentMap, EquipmentSlot.OFFHAND, "offHand", "off_hand", "offhand");
        applyEquipmentSlot(entity, equipmentMap, EquipmentSlot.HEAD, "helmet", "head");
        applyEquipmentSlot(entity, equipmentMap, EquipmentSlot.CHEST, "chestplate", "chest");
        applyEquipmentSlot(entity, equipmentMap, EquipmentSlot.LEGS, "leggings", "legs");
        applyEquipmentSlot(entity, equipmentMap, EquipmentSlot.FEET, "boots", "feet");
    }

    private Map<String, Object> mergedEquipmentMap(Map<String, Object> tag, Map<?, ?> equipment) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        copyEquipmentSlot(merged, "mainHand", equipment, tag, "mainHand", "main_hand", "mainhand", "hand");
        copyEquipmentSlot(merged, "offHand", equipment, tag, "offHand", "off_hand", "offhand");
        copyEquipmentSlot(merged, "helmet", equipment, tag, "helmet", "head");
        copyEquipmentSlot(merged, "chestplate", equipment, tag, "chestplate", "chest");
        copyEquipmentSlot(merged, "leggings", equipment, tag, "leggings", "legs");
        copyEquipmentSlot(merged, "boots", equipment, tag, "boots", "feet");
        return merged;
    }

    private void copyEquipmentSlot(Map<String, Object> target, String key, Map<?, ?> primary, Map<?, ?> secondary, String... aliases) {
        Object value = firstEquipmentValue(primary, aliases);
        if (value == null) {
            value = firstEquipmentValue(secondary, aliases);
        }
        if (value != null) {
            target.put(key, value);
        }
    }

    private void applyEquipmentSlot(LivingEntity entity, Map<?, ?> equipment, EquipmentSlot slot, String... keys) {
        Object value = firstEquipmentValue(equipment, keys);
        if (value == null) {
            entity.setItemSlot(slot, ItemStack.EMPTY);
            return;
        }
        MinecraftGameItem item = adaptRenderItem(value);
        if (item == null || item.isEmpty()) {
            entity.setItemSlot(slot, ItemStack.EMPTY);
            return;
        }
        ItemStack stack = createItemStack(item);
        if (stack == null || stack.isEmpty()) {
            entity.setItemSlot(slot, ItemStack.EMPTY);
            return;
        }
        entity.setItemSlot(slot, stack);
    }

    private Object firstEquipmentValue(Map<?, ?> equipment, String... keys) {
        for (String key : keys) {
            Object value = equipment.get(key);
            if (value instanceof String string && string.isBlank()) {
                continue;
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void applyBabyFlag(LivingEntity entity, boolean baby) {
        if (entity instanceof AgeableMob ageableMob) {
            ageableMob.setBaby(baby);
            return;
        }
        invokeBooleanEntityMethod(entity, "setBaby", baby);
    }

    private void invokeBooleanEntityMethod(Entity entity, String name, boolean value) {
        Class<?> current = entity.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals(name) && types.length == 1 && types[0] == boolean.class) {
                    try {
                        method.setAccessible(true);
                        method.invoke(entity, value);
                    } catch (Exception ignored) {
                    }
                    return;
                }
            }
            current = current.getSuperclass();
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        return String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string)) {
                return true;
            }
            if ("false".equalsIgnoreCase(string)) {
                return false;
            }
        }
        return fallback;
    }

    private void renderEntityUnclipped(Entity entity, int x, int y, int z, int size, float yaw, float pitch) {
        renderEntityUnclipped(entity, x, y, z, size, yaw, pitch, null, null);
    }

    private void renderEntityUnclipped(Entity entity, int x, int y, int z, int size, float yaw, float pitch, Integer mouseX, Integer mouseY) {
        renderEntityDirect(entity, x, y, z, size, yaw, pitch, mouseX, mouseY);
    }

    private void renderEntityDirect(Entity entity, int x, int y, int z, int size, float yaw, float pitch, Integer mouseX, Integer mouseY) {
        matrices.push();
        try {
            matrices.translate(0.0f, 0.0f, z);
            int screenX = Math.round(transformPoseX(x, y));
            int screenY = Math.round(transformPoseY(x, y));
            int screenSize = Math.max(1, Math.round(size * transformPoseScale()));
            Float screenMouseX = mouseX != null && mouseY != null ? transformPoseX(mouseX, mouseY) : null;
            Float screenMouseY = mouseX != null && mouseY != null ? transformPoseY(mouseX, mouseY) : null;
            float oldYRot = entity.getYRot();
            float oldXRot = entity.getXRot();
            float oldYRotO = entity.yRotO;
            float oldXRotO = entity.xRotO;
            if (entity instanceof LivingEntity livingEntity) {
                float oldBodyRot = livingEntity.yBodyRot;
                float oldHeadRot = livingEntity.yHeadRot;
                float oldBodyRotO = livingEntity.yBodyRotO;
                float oldHeadRotO = livingEntity.yHeadRotO;
                try {
                    applyPreviewRotations(entity, livingEntity, yaw, pitch);
                    syncEntityOldRot(entity);
                    renderEntityPose(entity, livingEntity, screenX, screenY, screenSize, yaw, pitch, screenMouseX, screenMouseY);
                } finally {
                    entity.setYRot(oldYRot);
                    entity.setXRot(oldXRot);
                    restoreEntityOldRot(entity, oldYRotO, oldXRotO);
                    livingEntity.yBodyRot = oldBodyRot;
                    livingEntity.yHeadRot = oldHeadRot;
                    livingEntity.yBodyRotO = oldBodyRotO;
                    livingEntity.yHeadRotO = oldHeadRotO;
                }
                return;
            }
            entity.setYRot(180.0f + yaw * 2.0f);
            entity.setXRot(-pitch);
            syncEntityOldRot(entity);
            try {
                renderEntityPose(entity, null, screenX, screenY, screenSize, yaw, pitch, screenMouseX, screenMouseY);
            } finally {
                entity.setYRot(oldYRot);
                entity.setXRot(oldXRot);
                restoreEntityOldRot(entity, oldYRotO, oldXRotO);
            }
        } finally {
            matrices.pop();
        }
    }

    private void syncEntityOldRot(Entity entity) {
        entity.yRotO = entity.getYRot();
        entity.xRotO = entity.getXRot();
    }

    private void restoreEntityOldRot(Entity entity, float yRotO, float xRotO) {
        entity.yRotO = yRotO;
        entity.xRotO = xRotO;
    }

    private void applyPreviewRotations(Entity entity, LivingEntity livingEntity, float yaw, float pitch) {
        entity.setYRot(180.0f + yaw * 2.0f);
        entity.setXRot(-pitch);
        livingEntity.yBodyRot = 180.0f + yaw;
        livingEntity.yHeadRot = entity.getYRot();
        livingEntity.yBodyRotO = livingEntity.yBodyRot;
        livingEntity.yHeadRotO = livingEntity.yHeadRot;
    }

    private void renderEntityPose(Entity entity, LivingEntity livingEntity, int screenX, int screenY, int size, float yaw, float pitch, Float mouseX, Float mouseY) {
        boolean rendered = false;
        pushIdentityPose();
        try {
            if (livingEntity != null) {
                rendered = invokeInventoryEntityRenderer(livingEntity, screenX, screenY, size, yaw, pitch, mouseX, mouseY);
            }
            if (!rendered) {
                invokeGuiEntityRenderer(entity, screenX, screenY, size, yaw, pitch);
            }
        } finally {
            popIdentityPose();
        }
    }

    private float transformPoseX(float x, float y) {
        //#if MC >= 1.21.6 || MC >= 26.1
        return graphics.pose().m00() * x + graphics.pose().m10() * y + graphics.pose().m20();
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ Matrix4f pose = graphics.pose().last().pose();
        //$$ return pose.m00() * x + pose.m10() * y + pose.m30();
        //#endif
    }

    private float transformPoseY(float x, float y) {
        //#if MC >= 1.21.6 || MC >= 26.1
        return graphics.pose().m01() * x + graphics.pose().m11() * y + graphics.pose().m21();
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ Matrix4f pose = graphics.pose().last().pose();
        //$$ return pose.m01() * x + pose.m11() * y + pose.m31();
        //#endif
    }

    private float transformPoseScale() {
        //#if MC >= 1.21.6 || MC >= 26.1
        float scaleX = (float) Math.sqrt(graphics.pose().m00() * graphics.pose().m00() + graphics.pose().m01() * graphics.pose().m01());
        float scaleY = (float) Math.sqrt(graphics.pose().m10() * graphics.pose().m10() + graphics.pose().m11() * graphics.pose().m11());
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ Matrix4f pose = graphics.pose().last().pose();
        //$$ float scaleX = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        //$$ float scaleY = (float) Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11());
        //#endif
        float scale = Math.max(scaleX, scaleY);
        return scale > 0.0f ? scale : 1.0f;
    }

    private void pushIdentityPose() {
        //#if MC >= 1.21.6 || MC >= 26.1
        graphics.pose().pushMatrix();
        graphics.pose().identity();
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ graphics.pose().pushPose();
        //$$ graphics.pose().last().pose().identity();
        //#endif
    }

    private void popIdentityPose() {
        //#if MC >= 1.21.6 || MC >= 26.1
        graphics.pose().popMatrix();
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ graphics.pose().popPose();
        //#endif
    }

    private boolean invokeGuiEntityRenderer(Entity entity, int x, int y, int size, float yaw, float pitch) {
        Object state = entityRenderState(entity);
        if (state == null) {
            return false;
        }
        float scale = Math.max(1.0f, size / 2.0f);
        Quaternionf bodyRotation = previewBodyRotation(yaw, pitch);
        Quaternionf headRotation = previewHeadRotation(pitch);
        int[] bounds = expandedEntityBounds(x, y, size);
        //#if MC >= 26.1
        if (state instanceof EntityRenderState renderState) {
            try {
                graphics.entity(renderState, scale, entityPreviewTranslation(), bodyRotation, headRotation, bounds[0], bounds[1], bounds[2], bounds[3]);
                return true;
            } catch (Exception ignored) {
            }
        }
        //#endif
        Method renderMethod = guiEntityRenderer();
        if (renderMethod == null) {
            return false;
        }
        try {
            renderMethod.invoke(graphics, state, scale, entityPreviewTranslation(), bodyRotation, headRotation, bounds[0], bounds[1], bounds[2], bounds[3]);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object entityRenderState(Entity entity) {
        //#if MC >= 26.1
        EntityRenderDispatcher dispatcher = entityRenderDispatcher();
        return dispatcher != null ? dispatcher.extractEntity(entity, 1.0f) : null;
        //#endif
        //#if MC < 26.1
        //$$ Method method = entityRenderStateExtractor();
        //$$ if (method == null) {
        //$$     return null;
        //$$ }
        //$$ try {
        //$$     return method.invoke(entityRenderDispatcher(), entity, 1.0f);
        //$$ } catch (Exception ignored) {
        //$$     return null;
        //$$ }
        //#endif
    }

    private Method entityRenderStateExtractor() {
        if (entityRenderStateExtractorChecked) {
            return entityRenderStateExtractor;
        }
        entityRenderStateExtractorChecked = true;
        EntityRenderDispatcher dispatcher = entityRenderDispatcher();
        if (dispatcher == null) {
            return null;
        }
        Method fallback = null;
        for (Method method : dispatcher.getClass().getMethods()) {
            if (!isEntityRenderStateExtractor(method)) {
                continue;
            }
            if (isEntityRenderStateType(method.getReturnType())) {
                method.setAccessible(true);
                entityRenderStateExtractor = method;
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            entityRenderStateExtractor = fallback;
            return fallback;
        }
        return null;
    }

    private EntityRenderDispatcher entityRenderDispatcher() {
        if (entityRenderDispatcherChecked) {
            return entityRenderDispatcher;
        }
        entityRenderDispatcherChecked = true;
        Minecraft minecraft = Minecraft.getInstance();
        for (Method method : minecraft.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && EntityRenderDispatcher.class.isAssignableFrom(method.getReturnType())) {
                try {
                    method.setAccessible(true);
                    entityRenderDispatcher = (EntityRenderDispatcher) method.invoke(minecraft);
                    return entityRenderDispatcher;
                } catch (Exception ignored) {
                }
            }
        }
        Class<?> current = minecraft.getClass();
        while (current != null) {
            for (var field : current.getDeclaredFields()) {
                if (EntityRenderDispatcher.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        entityRenderDispatcher = (EntityRenderDispatcher) field.get(minecraft);
                        return entityRenderDispatcher;
                    } catch (Exception ignored) {
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean isEntityRenderStateExtractor(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        return types.length == 2
            && types[0].isAssignableFrom(Entity.class)
            && types[1] == float.class
            && returnType != void.class
            && returnType != int.class
            && !returnType.isPrimitive()
            && !Entity.class.isAssignableFrom(returnType);
    }

    private Method guiEntityRenderer() {
        if (guiEntityRendererChecked) {
            return guiEntityRenderer;
        }
        guiEntityRendererChecked = true;
        Method fallback = null;
        for (Method method : graphics.getClass().getMethods()) {
            if (!isGuiEntityRenderer(method)) {
                continue;
            }
            if (isEntityRenderStateType(method.getParameterTypes()[0])) {
                method.setAccessible(true);
                guiEntityRenderer = method;
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            guiEntityRenderer = fallback;
            return fallback;
        }
        return null;
    }

    private boolean isGuiEntityRenderer(Method method) {
        Class<?>[] types = method.getParameterTypes();
        return types.length == 9
            && !types[0].isPrimitive()
            && types[1] == float.class
            && types[2].isAssignableFrom(Vector3f.class)
            && types[3].isAssignableFrom(Quaternionf.class)
            && types[4].isAssignableFrom(Quaternionf.class)
            && types[5] == int.class
            && types[6] == int.class
            && types[7] == int.class
            && types[8] == int.class;
    }

    private boolean invokeInventoryEntityRenderer(LivingEntity entity, int x, int y, int size, float yaw, float pitch, Float mouseX, Float mouseY) {
        //#if MC >= 26.1
        try {
            int[] bounds = expandedEntityBounds(x, y, size);
            float centerX = (bounds[0] + bounds[2]) * 0.5f;
            float centerY = (bounds[1] + bounds[3]) * 0.5f;
            float actualCenterX = x + size * 0.5f;
            float actualCenterY = y + size * 0.5f;
            float previewMouseX = mouseX != null ? mouseX + centerX - actualCenterX : mouseCoordinate(centerX, yaw);
            float previewMouseY = mouseY != null ? mouseY + centerY - actualCenterY : mouseCoordinate(centerY, pitch);
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, bounds[0], bounds[1], bounds[2], bounds[3], Math.max(1, size / 2), 0.0f, previewMouseX, previewMouseY, entity);
            return true;
        } catch (Exception ignored) {
        }
        //#endif
        Method mouseMethod = inventoryMouseEntityRenderer();
        if (mouseMethod != null) {
            try {
                int[] bounds = expandedEntityBounds(x, y, size);
                float centerX = (bounds[0] + bounds[2]) * 0.5f;
                float centerY = (bounds[1] + bounds[3]) * 0.5f;
                float actualCenterX = x + size * 0.5f;
                float actualCenterY = y + size * 0.5f;
                float previewMouseX = mouseX != null ? mouseX + centerX - actualCenterX : mouseCoordinate(centerX, yaw);
                float previewMouseY = mouseY != null ? mouseY + centerY - actualCenterY : mouseCoordinate(centerY, pitch);
                mouseMethod.invoke(null, graphics, bounds[0], bounds[1], bounds[2], bounds[3], Math.max(1, size / 2), 0.0f, previewMouseX, previewMouseY, entity);
                return true;
            } catch (Exception ignored) {
            }
        }
        Method method = inventoryEntityRenderer();
        if (method == null) {
            return false;
        }
        try {
            float scale = Math.max(1.0f, size / 2.0f);
            if (method.getParameterCount() == 10 && method.getParameterTypes()[6].isAssignableFrom(Vector3f.class)) {
                Quaternionf bodyRotation = previewBodyRotation(yaw, pitch);
                Quaternionf headRotation = previewHeadRotation(pitch);
                int[] bounds = expandedEntityBounds(x, y, size);
                method.invoke(null, graphics, bounds[0], bounds[1], bounds[2], bounds[3], scale, entityPreviewTranslation(), bodyRotation, headRotation, entity);
                return true;
            }
            method.invoke(null, graphics, x + size / 2, y + size, Math.max(1, size / 2), yaw * 2.0f, pitch, entity);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Method inventoryEntityRenderer() {
        if (inventoryEntityRendererChecked) {
            return inventoryEntityRenderer;
        }
        inventoryEntityRendererChecked = true;
        for (Method method : InventoryScreen.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && isModernInventoryEntityRenderer(types)) {
                method.setAccessible(true);
                inventoryEntityRenderer = method;
                return method;
            }
        }
        for (Method method : InventoryScreen.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && isLegacyInventoryEntityRenderer(types)) {
                method.setAccessible(true);
                inventoryEntityRenderer = method;
                return method;
            }
        }
        return null;
    }

    private Method inventoryMouseEntityRenderer() {
        if (inventoryMouseEntityRendererChecked) {
            return inventoryMouseEntityRenderer;
        }
        inventoryMouseEntityRendererChecked = true;
        for (Method method : InventoryScreen.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && isMouseInventoryEntityRenderer(types)) {
                method.setAccessible(true);
                inventoryMouseEntityRenderer = method;
                return method;
            }
        }
        return null;
    }

    private int[] expandedEntityBounds(int x, int y, int size) {
        int padding = Math.max(96, size * 2);
        return new int[]{x - padding, y - padding, x + size + padding, y + size + padding};
    }

    private float playerPreviewHeadMouseYOffset(int size) {
        return size * PLAYER_HEAD_MOUSE_Y_OFFSET;
    }

    private Vector3f entityPreviewTranslation() {
        return new Vector3f();
    }

    private float mouseCoordinate(float center, float rotation) {
        return center - (float) Math.tan(rotation / 20.0f) * 40.0f;
    }

    private Quaternionf previewBodyRotation(float yaw, float pitch) {
        return new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw * 2.0f));
    }

    private Quaternionf previewHeadRotation(float pitch) {
        return new Quaternionf().rotateX((float) Math.toRadians(pitch));
    }

    private boolean isModernInventoryEntityRenderer(Class<?>[] types) {
        return types.length == 10
            && types[0].isInstance(graphics)
            && types[1] == int.class
            && types[2] == int.class
            && types[3] == int.class
            && types[4] == int.class
            && (types[5] == int.class || types[5] == float.class)
            && types[6].isAssignableFrom(Vector3f.class)
            && types[7].isAssignableFrom(Quaternionf.class)
            && types[8].isAssignableFrom(Quaternionf.class)
            && types[9].isAssignableFrom(LivingEntity.class);
    }

    private boolean isMouseInventoryEntityRenderer(Class<?>[] types) {
        return types.length == 10
            && types[0].isInstance(graphics)
            && types[1] == int.class
            && types[2] == int.class
            && types[3] == int.class
            && types[4] == int.class
            && types[5] == int.class
            && types[6] == float.class
            && types[7] == float.class
            && types[8] == float.class
            && types[9].isAssignableFrom(LivingEntity.class);
    }

    private boolean isLegacyInventoryEntityRenderer(Class<?>[] types) {
        return types.length == 7
            && types[0].isInstance(graphics)
            && types[1] == int.class
            && types[2] == int.class
            && types[3] == int.class
            && types[4] == float.class
            && types[5] == float.class
            && types[6].isAssignableFrom(LivingEntity.class);
    }

    private boolean isEntityRenderStateType(Class<?> type) {
        return type.getName().equals("net.minecraft.client.renderer.entity.state.EntityRenderState")
            || type.getSimpleName().equals("EntityRenderState");
    }

    private void renderItemWithScissor(ItemStack stack, int x, int y, int z) {
        renderItemDirect(stack, x, y, z);
    }

    private void renderItemDirect(ItemStack stack, int x, int y, int z) {
        //#if MC >= 26.1
        graphics.item(stack, x, y, z);
        //#endif
        //#if MC >= 1.21.6 && MC < 26.1
        //$$ graphics.renderItem(stack, x, y, 0);
        //#endif
        //#if MC >= 1.21.4 && MC < 1.21.6
        //$$ graphics.renderItem(stack, x, y, 0, 0);
        //#endif
        //#if MC >= 1.20.1 && MC < 1.21.4
        //$$ graphics.renderItem(stack, x, y, 0, 0);
        //#endif
    }

    private ItemStack createItemStack(MinecraftGameItem item) {
        if (item == null || item.id() == null || item.id().isBlank()) return ItemStack.EMPTY;
        String id = item.id();
        //#if MC >= 1.21.11 || MC >= 26.1
        Identifier rl = Identifier.fromNamespaceAndPath("minecraft", "air");
        //#endif
        //#if MC >= 1.21.1 && MC < 1.21.11
        //$$ ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("minecraft", "air");
        //#endif
        //#if MC < 1.21.1
        //$$ ResourceLocation rl = new ResourceLocation("minecraft", "air");
        //#endif
        String namespace = "minecraft";
        String path = id;
        if (id.contains(":")) {
            String[] parts = id.split(":", 2);
            if (parts.length > 0 && !parts[0].isBlank()) {
                namespace = parts[0];
            }
            path = parts.length > 1 ? parts[1] : "";
        }
        if (!isValidMinecraftNamespace(namespace) || !isValidMinecraftPath(path)) {
            return ItemStack.EMPTY;
        }
        //#if MC >= 1.21.11 || MC >= 26.1
        rl = Identifier.fromNamespaceAndPath(namespace, path);
        ItemStack stack = BuiltInRegistries.ITEM.getOptional(rl).map(this::createItemStackSafely).orElseGet(this::barrierItemStack);
        //#endif
        //#if MC >= 1.21.1 && MC < 1.21.11
        //$$ rl = ResourceLocation.fromNamespaceAndPath(namespace, path);
        //$$ ItemStack stack = BuiltInRegistries.ITEM.getOptional(rl).map(this::createItemStackSafely).orElseGet(this::barrierItemStack);
        //#endif
        //#if MC < 1.21.1
        //$$ rl = new ResourceLocation(namespace, path);
        //$$ ItemStack stack = BuiltInRegistries.ITEM.getOptional(rl).map(this::createItemStackSafely).orElseGet(this::barrierItemStack);
        //#endif
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int count = item.count();
        if (count > 0) {
            stack.setCount(Math.min(count, stack.getMaxStackSize()));
        }
        String name = item.name();
        if (name != null && !name.isBlank()) {
            //#if MC >= 1.21.1
            stack.set(DataComponents.CUSTOM_NAME, toNativeItemName(MinecraftTextComponents.fromValue(name)));
            //#endif
            //#if MC < 1.21.1
            //$$ stack.setHoverName(toNativeItemName(MinecraftTextComponents.fromValue(name)));
            //#endif
        }
        applyItemModelComponent(stack, item);
        Integer modelData = item.modelData();
        if (modelData == null) {
            modelData = customModelDataComponent(item);
        }
        if (modelData != null) {
            //#if MC >= 1.21.4
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(modelData.floatValue()), List.of(), List.of(), List.of()));
            //#endif
            //#if MC >= 1.21.1 && MC < 1.21.4
            //$$ stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelData));
            //#endif
            //#if MC < 1.21.1
            //$$ CompoundTag tag = stack.getOrCreateTag();
            //$$ tag.putInt("CustomModelData", modelData);
            //#endif
        }
        applyItemVisualComponents(stack, item);
        List<String> lore = item.lore();
        if (lore != null && !lore.isEmpty()) {
            //#if MC >= 1.21.1
            List<Component> components = new ArrayList<>(lore.size());
            List<Component> styledComponents = new ArrayList<>(lore.size());
            for (String line : lore) {
                if (line != null && !line.isBlank()) {
                    MinecraftTextComponent component = MinecraftTextComponents.fromValue(line);
                    components.add(toNativeComponent(component));
                    styledComponents.add(toNativeLore(component));
                }
            }
            if (!components.isEmpty()) {
                stack.set(DataComponents.LORE, new ItemLore(components, styledComponents));
            }
            //#endif
            //#if MC < 1.21.1
            //$$ ListTag list = new ListTag();
            //$$ for (String line : lore) {
            //$$     if (line != null && !line.isBlank()) {
            //$$         list.add(StringTag.valueOf(Component.Serializer.toJson(toNativeLore(MinecraftTextComponents.fromValue(line)))));
            //$$     }
            //$$ }
            //$$ if (!list.isEmpty()) {
            //$$     CompoundTag tag = stack.getOrCreateTag();
            //$$     CompoundTag display = tag.contains("display") ? tag.getCompound("display") : new CompoundTag();
            //$$     display.put("Lore", list);
            //$$     tag.put("display", display);
            //$$ }
            //#endif
        }
        return stack;
    }

    private void applyItemVisualComponents(ItemStack stack, MinecraftGameItem item) {
        //#if MC >= 1.21.1
        Integer color = dyedItemColor(item);
        if (color != null) {
            Object dyedColor = createDyedItemColor(color);
            if (dyedColor != null) {
                setDataComponent(stack, DataComponents.DYED_COLOR, dyedColor);
            }
        }
        ArmorTrimTag trim = armorTrim(item);
        if (trim != null) {
            Object nativeTrim = createArmorTrim(trim);
            if (nativeTrim != null) {
                setDataComponent(stack, DataComponents.TRIM, nativeTrim);
            }
        }
        //#endif
    }

    //#if MC >= 1.21.1
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setDataComponent(ItemStack stack, DataComponentType type, Object value) {
        stack.set(type, value);
    }
    //#endif

    private Object createDyedItemColor(int color) {
        try {
            Class<?> type = Class.forName("net.minecraft.world.item.component.DyedItemColor");
            try {
                return type.getConstructor(int.class).newInstance(color & 0xFFFFFF);
            } catch (NoSuchMethodException ignored) {
                return type.getConstructor(int.class, boolean.class).newInstance(color & 0xFFFFFF, true);
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Object createArmorTrim(ArmorTrimTag trim) {
        try {
            String armorTrimClassName = className("net.minecraft.world.item.equipment.trim.ArmorTrim", "net.minecraft.world.item.armortrim.ArmorTrim");
            if (armorTrimClassName == null) {
                return null;
            }
            Object material = trimRegistryHolder("TRIM_MATERIAL", trim.material());
            Object pattern = trimRegistryHolder("TRIM_PATTERN", trim.pattern());
            if (material == null || pattern == null) {
                return null;
            }
            Class<?> holderType = Class.forName("net.minecraft.core.Holder");
            return Class.forName(armorTrimClassName).getConstructor(holderType, holderType).newInstance(material, pattern);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Object trimRegistryHolder(String registryFieldName, String id) {
        try {
            Level level = currentEntityLevel(Minecraft.getInstance());
            if (level == null) {
                return null;
            }
            Object location = resourceLocation(id);
            if (location == null) {
                return null;
            }
            Object registryKey = Class.forName("net.minecraft.core.registries.Registries").getField(registryFieldName).get(null);
            Class<?> resourceKeyType = Class.forName("net.minecraft.resources.ResourceKey");
            Object entryKey = resourceKeyType.getMethod("create", resourceKeyType, location.getClass()).invoke(null, registryKey, location);
            Object registry = trimRegistry(level.registryAccess(), resourceKeyType, registryKey);
            if (registry == null) {
                return null;
            }
            Object holder = registry.getClass().getMethod("get", resourceKeyType).invoke(registry, entryKey);
            if (holder instanceof Optional<?> optionalHolder && optionalHolder.isPresent()) {
                return optionalHolder.get();
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }

    private Object trimRegistry(Object registryAccess, Class<?> resourceKeyType, Object registryKey) {
        try {
            Method lookup = registryAccess.getClass().getMethod("lookup", resourceKeyType);
            Object value = lookup.invoke(registryAccess, registryKey);
            if (value instanceof Optional<?> optional) {
                return optional.orElse(null);
            }
            if (value != null) {
                return value;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method registries = registryAccess.getClass().getMethod("registries", resourceKeyType);
            Object value = registries.invoke(registryAccess, registryKey);
            if (value instanceof Optional<?> optional) {
                return optional.orElse(null);
            }
            if (value != null) {
                return value;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method lookupOrThrow = registryAccess.getClass().getMethod("lookupOrThrow", resourceKeyType);
            return lookupOrThrow.invoke(registryAccess, registryKey);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private String className(String... names) {
        for (String name : names) {
            try {
                Class.forName(name);
                return name;
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private void applyItemModelComponent(ItemStack stack, MinecraftGameItem item) {
        Map<String, Object> components = components(item);
        if (components.isEmpty()) {
            return;
        }
        String model = stringValue(firstComponentValue(components, "minecraft:item_model", "item_model"));
        if (model == null || model.isBlank()) {
            return;
        }
        //#if MC >= 1.21.11 || MC >= 26.1
        Identifier modelId = resourceLocation(model);
        //#endif
        //#if MC >= 1.21.4 && MC < 1.21.11
        //$$ ResourceLocation modelId = resourceLocation(model);
        //#endif
        //#if MC >= 1.21.4 || MC >= 26.1
        if (modelId != null) {
            stack.set(DataComponents.ITEM_MODEL, modelId);
        }
        //#endif
    }

    private Integer customModelDataComponent(MinecraftGameItem item) {
        Map<String, Object> components = components(item);
        if (components.isEmpty()) {
            return null;
        }
        return integerObject(firstComponentValue(components, "minecraft:custom_model_data", "custom_model_data"));
    }

    private Integer dyedItemColor(MinecraftGameItem item) {
        if (item == null || item.tag() == null || item.tag().isEmpty()) {
            return null;
        }
        Map<String, Object> tag = item.tag();
        Integer color = colorValue(firstComponentValue(tag, "minecraft:dyed_color", "dyed_color", "minecraft:color", "color"));
        if (color != null) {
            return color;
        }
        Map<String, Object> display = mapValue(tag.get("display"));
        color = colorValue(firstComponentValue(display, "color", "Color"));
        if (color != null) {
            return color;
        }
        Map<String, Object> components = components(item);
        color = colorValue(firstComponentValue(components, "minecraft:dyed_color", "dyed_color", "minecraft:color", "color"));
        return color;
    }

    private ArmorTrimTag armorTrim(MinecraftGameItem item) {
        if (item == null || item.tag() == null || item.tag().isEmpty()) {
            return null;
        }
        Map<String, Object> trim = firstMap(item.tag(), "minecraft:trim", "trim", "Trim");
        if (trim.isEmpty()) {
            trim = firstMap(components(item), "minecraft:trim", "trim");
        }
        String material = stringObject(firstComponentValue(trim, "material", "minecraft:material"));
        String pattern = stringObject(firstComponentValue(trim, "pattern", "minecraft:pattern"));
        if (material == null || pattern == null) {
            return null;
        }
        return new ArmorTrimTag(material, pattern);
    }

    private Object firstComponentValue(Map<String, Object> components, String... keys) {
        for (String key : keys) {
            Object value = components.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> firstMap(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Map<String, Object> value = mapValue(map.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return Map.of();
    }

    private Map<String, Object> components(MinecraftGameItem item) {
        if (item == null || item.tag() == null || item.tag().isEmpty()) {
            return Map.of();
        }
        return mapValue(item.tag().get("components"));
    }

    private Integer colorValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            String cleaned = string.trim();
            if (cleaned.startsWith("#")) {
                try {
                    return Integer.parseUnsignedInt(cleaned.substring(1), 16);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
                try {
                    return Integer.parseUnsignedInt(cleaned.substring(2), 16);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            try {
                return Integer.parseInt(cleaned);
            } catch (NumberFormatException ignored) {
                try {
                    return Integer.parseUnsignedInt(cleaned, 16);
                } catch (NumberFormatException ignoredAgain) {
                    return null;
                }
            }
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("rgb");
            if (nested == null) {
                nested = map.get("color");
            }
            if (nested == null) {
                nested = map.get("value");
            }
            return colorValue(nested);
        }
        return null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private Integer integerObject(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("value");
            if (nested == null) {
                nested = map.get("model");
            }
            if (nested == null) {
                nested = map.get("data");
            }
            if (nested == null) {
                nested = map.get("float");
            }
            if (nested == null) {
                nested = map.get("number");
            }
            if (nested == null) {
                nested = map.get("floats");
            }
            if (nested == null) {
                nested = map.get("values");
            }
            return integerObject(nested);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : integerObject(list.get(0));
        }
        return null;
    }

    private String stringObject(Object value) {
        if (value instanceof String string && !string.isBlank()) {
            return string.trim();
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("id");
            if (nested == null) {
                nested = map.get("name");
            }
            if (nested == null) {
                nested = map.get("value");
            }
            return stringObject(nested);
        }
        return null;
    }

    //#if MC >= 1.21.11 || MC >= 26.1
    private Identifier resourceLocation(String value) {
    //#endif
    //#if MC >= 1.21.1 && MC < 1.21.11
    //$$ private ResourceLocation resourceLocation(String value) {
    //#endif
    //#if MC < 1.21.1
    //$$ private ResourceLocation resourceLocation(String value) {
    //#endif
        if (value == null || value.isBlank()) {
            return null;
        }
        String namespace = "minecraft";
        String path = value.trim();
        if (path.contains(":")) {
            String[] parts = path.split(":", 2);
            namespace = parts.length > 0 && !parts[0].isBlank() ? parts[0] : namespace;
            path = parts.length > 1 ? parts[1] : "";
        }
        if (!isValidMinecraftNamespace(namespace) || !isValidMinecraftPath(path)) {
            return null;
        }
        //#if MC >= 1.21.11 || MC >= 26.1
        return Identifier.fromNamespaceAndPath(namespace, path);
        //#endif
        //#if MC >= 1.21.1 && MC < 1.21.11
        //$$ return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //#endif
        //#if MC < 1.21.1
        //$$ return new ResourceLocation(namespace, path);
        //#endif
    }

    private boolean isValidMinecraftNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private boolean isValidMinecraftPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.' && c != '/') {
                return false;
            }
        }
        return true;
    }

    private ItemStack createItemStackSafely(Item item) {
        try {
            return new ItemStack(item);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private ItemStack barrierItemStack() {
        return createItemStackSafely(Items.BARRIER);
    }

    private Component toNativeItemName(MinecraftTextComponent component) {
        return toNativeComponent(component).copy().withStyle(style -> style.withItalic(false));
    }

    private Component toNativeLore(MinecraftTextComponent component) {
        MutableComponent nativeComponent = toNativeComponent(component).copy();
        if (!hasExplicitColor(component)) {
            nativeComponent.withStyle(ChatFormatting.DARK_GRAY);
        }
        if (!hasExplicitItalic(component)) {
            nativeComponent.withStyle(style -> style.withItalic(true));
        }
        return nativeComponent;
    }

    private boolean hasExplicitColor(MinecraftTextComponent component) {
        if (component == null) {
            return false;
        }
        if (component.color() != null) {
            return true;
        }
        for (MinecraftTextComponent extra : component.extra()) {
            if (hasExplicitColor(extra)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitItalic(MinecraftTextComponent component) {
        if (component == null) {
            return false;
        }
        if (component.italic() != null) {
            return true;
        }
        for (MinecraftTextComponent extra : component.extra()) {
            if (hasExplicitItalic(extra)) {
                return true;
            }
        }
        return false;
    }

    //#if MC >= 26.1
    public GuiGraphicsExtractor getGraphics() {
        return graphics;
    }
    //#endif
    //#if MC >= 1.20.1 && MC < 26.1
    //$$ public GuiGraphics getGraphics() {
    //$$     return graphics;
    //$$ }
    //#endif
    //#if MC < 1.20.1
    //$$ public PoseStack getGraphics() {
    //$$     return graphics;
    //$$ }
    //#endif

    public void drawText(String text, int x, int y, int color, boolean shadow) {
        //#if MC < 1.21.6 && MC < 26.1
        //$$ if (((color >>> 24) & 0xFF) <= 4) {
        //$$     return;
        //$$ }
        //#endif
        //#if MC >= 26.1
        graphics.text(Minecraft.getInstance().font, text, x, y, color, shadow);
        //#endif
        //#if MC >= 1.20.1 && MC < 26.1
        //$$ {
        //$$     if (shadow) {
        //$$         graphics.drawString(Minecraft.getInstance().font, text, x, y, color, true);
        //$$         return;
        //$$     }
        //$$     graphics.drawString(Minecraft.getInstance().font, text, x, y, color, false);
        //$$ }
        //#endif
        //#if MC < 1.20.1
        //$$ if (shadow) {
        //$$     Minecraft.getInstance().font.drawShadow(graphics, text, x, y, color);
        //$$     return;
        //$$ }
        //$$ Minecraft.getInstance().font.draw(graphics, text, x, y, color);
        //#endif
    }

    public void drawStyledText(Object text, int x, int y, int color, boolean shadow) {
        if (text instanceof StyledText styledText) {
            //#if MC < 1.21.6 && MC < 26.1
            //$$ if (((styledText.color >>> 24) & 0xFF) <= 4) return;
            //#endif
            //#if MC >= 1.21.6 || MC >= 26.1
            if (((styledText.color >>> 24) & 0xFF) == 0) return;
            //#endif
            drawStyledText(styledTextComponent(styledText), x, y, styledText.color, shadow);
            return;
        }
        if (text instanceof Component component) {
            //#if MC < 1.21.6 && MC < 26.1
            //$$ if (((color >>> 24) & 0xFF) <= 4) {
            //$$     return;
            //$$ }
            //#endif
            //#if MC >= 26.1
            graphics.text(Minecraft.getInstance().font, component, x, y, color, shadow);
            //#endif
            //#if MC >= 1.20.1 && MC < 26.1
            //$$ {
            //$$     if (shadow) {
            //$$         graphics.drawString(Minecraft.getInstance().font, component, x, y, color, true);
            //$$         return;
            //$$     }
            //$$     graphics.drawString(Minecraft.getInstance().font, component, x, y, color, false);
            //$$ }
            //#endif
            //#if MC < 1.20.1
            //$$ if (shadow) {
            //$$     Minecraft.getInstance().font.drawShadow(graphics, component, x, y, color);
            //$$     return;
            //$$ }
            //$$ Minecraft.getInstance().font.draw(graphics, component, x, y, color);
            //#endif
            return;
        }
        drawText(String.valueOf(text), x, y, color, shadow);
    }

    private static synchronized Component styledTextComponent(StyledText text) {
        StyledTextKey key = new StyledTextKey(text.text, text.font);
        Component cached = STYLED_TEXT_CACHE.get(key);
        if (cached != null) return cached;
        MutableComponent component = Component.literal(text.text);
        //#if MC >= 1.21.11 || MC >= 26.1
        if (text.font instanceof Identifier font) {
        //#endif
        //#if MC < 1.21.11 && MC < 26.1
        //$$ if (text.font instanceof ResourceLocation font) {
        //#endif
            //#if MC >= 1.21.9 || MC >= 26.1
            component.setStyle(Style.EMPTY.withFont(new FontDescription.Resource(font)));
            //#endif
            //#if MC < 1.21.9 && MC < 26.1
            //$$ component.setStyle(Style.EMPTY.withFont(font));
            //#endif
        }
        STYLED_TEXT_CACHE.put(key, component);
        if (STYLED_TEXT_CACHE.size() > STYLED_TEXT_CACHE_LIMIT) {
            StyledTextKey oldest = STYLED_TEXT_CACHE.keySet().iterator().next();
            STYLED_TEXT_CACHE.remove(oldest);
        }
        return component;
    }

    private record StyledTextKey(String text, Object font) {
    }

    public void fill(int x1, int y1, int x2, int y2, int argb) {
        DebugDrawStats.recordFill();
        //#if MC >= 1.20.1
        graphics.fill(x1, y1, x2, y2, argb);
        //#endif
        //#if MC < 1.20.1
        //$$ GuiComponent.fill(graphics, x1, y1, x2, y2, argb);
        //#endif
    }

    public void fillRoundedRectWithBorders(int x, int y, int width, int height, float roundness, int bgColor, int borderColor, int outerBorderColor) {
        float outerBorderWidth = 1f;
        float innerBorderWidth = 1f;
        fillRoundedRect(x - outerBorderWidth, y - outerBorderWidth, width + 2 * outerBorderWidth, height + 2 * outerBorderWidth, roundness + outerBorderWidth, outerBorderColor);
        fillRoundedRect(x, y, width, height, roundness, borderColor);
        fillRoundedRect(x + innerBorderWidth, y + innerBorderWidth, width - 2 * innerBorderWidth, height - 2 * innerBorderWidth, Math.max(0, roundness - innerBorderWidth), bgColor);
    }

    public void fillRoundedRect(float x, float y, float width, float height, float radius, int color) {
        float r = Math.min(radius, Math.min(width, height) / 2.0f);
        if (r <= 0) {
            fill((int) x, (int) y, (int) (x + width), (int) (y + height), color);
            return;
        }
        float alpha = ((color >> 24) & 0xFF) / 255.0f;
        if (alpha <= 0f) return;
        int left = (int) Math.floor(x);
        int top = (int) Math.floor(y);
        int right = (int) Math.ceil(x + width);
        int bottom = (int) Math.ceil(y + height);
        int innerLeft = (int) Math.ceil(x + r);
        int innerRight = (int) Math.floor(x + width - r);
        int innerTop = (int) Math.ceil(y + r);
        int innerBottom = (int) Math.floor(y + height - r);
        if (innerLeft < innerRight) {
            fill(innerLeft, top, innerRight, bottom, color);
        }
        if (innerTop < innerBottom) {
            fill(left, innerTop, innerLeft, innerBottom, color);
            fill(innerRight, innerTop, right, innerBottom, color);
        }
        int ri = (int) Math.ceil(r);
        float rSq = r * r;
        for (int dy = 0; dy < ri; dy++) {
            float fy = r - dy - 0.5f;
            float dx = (float) Math.sqrt(Math.max(0f, rSq - fy * fy));
            int ix = (int) Math.ceil(dx);
            int yTop = top + dy;
            int yBottom = bottom - dy - 1;
            int leftStart = innerLeft - ix;
            int rightEnd = innerRight + ix;
            if (yTop >= top && yTop < bottom) {
                fill(leftStart, yTop, innerLeft, yTop + 1, color);
                fill(innerRight, yTop, rightEnd, yTop + 1, color);
            }
            if (yBottom >= top && yBottom < bottom) {
                fill(leftStart, yBottom, innerLeft, yBottom + 1, color);
                fill(innerRight, yBottom, rightEnd, yBottom + 1, color);
            }
        }
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int color1, int color2, boolean horizontal) {
        int length = horizontal ? x2 - x1 : y2 - y1;
        if (length <= 0) return;
        if (!horizontal && length > 32) {
            //#if MC >= 1.20.1
            graphics.fillGradient(x1, y1, x2, y2, color1, color2);
            //#endif
            //#if MC < 1.20.1
            //$$ GuiComponent.fillGradient(graphics, x1, y1, x2, y2, color1, color2);
            //#endif
            return;
        }
        float a1 = (float) (color1 >> 24 & 255);
        float r1 = (float) (color1 >> 16 & 255);
        float g1 = (float) (color1 >> 8 & 255);
        float b1 = (float) (color1 & 255);
        float a2 = (float) (color2 >> 24 & 255);
        float r2 = (float) (color2 >> 16 & 255);
        float g2 = (float) (color2 >> 8 & 255);
        float b2 = (float) (color2 & 255);
        int stripCount = Math.min(length, 32);
        for (int strip = 0; strip < stripCount; strip++) {
            int stripStart = strip * length / stripCount;
            int stripEnd = (strip + 1) * length / stripCount;
            float center = (stripStart + stripEnd - 1) * 0.5f;
            float t = length == 1 ? 0.0f : center / (length - 1);
            int a = (int) (a1 * (1 - t) + a2 * t);
            int r = (int) (r1 * (1 - t) + r2 * t);
            int g = (int) (g1 * (1 - t) + g2 * t);
            int b = (int) (b1 * (1 - t) + b2 * t);
            int interpolatedColor = (a << 24) | (r << 16) | (g << 8) | b;
            if (horizontal) graphics.fill(x1 + stripStart, y1, x1 + stripEnd, y2, interpolatedColor);
            else graphics.fill(x1, y1 + stripStart, x2, y1 + stripEnd, interpolatedColor);
        }
    }

    public void drawBufferedImage(BufferedImage image, float x, float y, float width, float height) {
        if (image == null) return;
        ReTextureHandle handle = textures.getTexture(image);
        if (handle == null) return;
        int dw = Math.max(1, (int) Math.ceil(width <= 0 ? handle.getWidth() : width));
        int dh = Math.max(1, (int) Math.ceil(height <= 0 ? handle.getHeight() : height));
        //#if MC >= 1.21.11 || MC >= 26.1
        graphics.blit(RenderPipelines.GUI_TEXTURED, (Identifier) handle.getId(), (int) Math.round(x), (int) Math.round(y), 0f, 0f, dw, dh, handle.getWidth(), handle.getHeight(), handle.getWidth(), handle.getHeight());
        //#endif
        //#if MC >= 1.21.9 && MC < 1.21.11
        //$$ graphics.blit(RenderPipelines.GUI_TEXTURED, (ResourceLocation) handle.getId(), (int) Math.round(x), (int) Math.round(y), 0f, 0f, dw, dh, handle.getWidth(), handle.getHeight(), handle.getWidth(), handle.getHeight());
        //#endif
        //#if MC >= 1.21.6 && MC < 1.21.9
        //$$ graphics.blit(RenderPipelines.GUI_TEXTURED, (ResourceLocation) handle.getId(), (int) Math.round(x), (int) Math.round(y), 0f, 0f, dw, dh, handle.getWidth(), handle.getHeight(), handle.getWidth(), handle.getHeight());
        //#endif
        //#if MC >= 1.21.5 && MC < 1.21.6
        //$$ graphics.blit(RenderType::guiTextured, (ResourceLocation) handle.getId(), (int) Math.round(x), (int) Math.round(y), 0f, 0f, dw, dh, handle.getWidth(), handle.getHeight(), handle.getWidth(), handle.getHeight());
        //#endif
        //#if MC >= 1.21.4 && MC < 1.21.5
        //$$ graphics.blit(RenderType::guiTextured, (ResourceLocation) handle.getId(), (int) Math.round(x), (int) Math.round(y), 0f, 0f, dw, dh, handle.getWidth(), handle.getHeight(), handle.getWidth(), handle.getHeight());
        //#endif
        //#if MC >= 1.20.1 && MC < 1.21.4
        //$$ graphics.blit((ResourceLocation) handle.getId(), (int) Math.round(x), (int) Math.round(y), dw, dh, 0f, 0f, handle.getWidth(), handle.getHeight(), handle.getWidth(), handle.getHeight());
        //#endif
        //#if MC < 1.20.1
        //$$ RenderSystem.enableBlend();
        //$$ RenderSystem.defaultBlendFunc();
        //$$ RenderSystem.setShader(GameRenderer::getPositionTexShader);
        //$$ RenderSystem.setShaderTexture(0, (ResourceLocation) handle.getId());
        //$$ Matrix4f matrix = graphics.pose().last().pose();
        //$$ Tesselator tesselator = Tesselator.getInstance();
        //$$ BufferBuilder builder = tesselator.getBuilder();
        //$$ builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        //$$ float minU = 0.0f;
        //$$ float minV = 0.0f;
        //$$ float maxU = 1.0f;
        //$$ float maxV = 1.0f;
        //$$ builder.vertex(matrix, x, y + dh, 0).uv(minU, maxV).endVertex();
        //$$ builder.vertex(matrix, x + dw, y + dh, 0).uv(maxU, maxV).endVertex();
        //$$ builder.vertex(matrix, x + dw, y, 0).uv(maxU, minV).endVertex();
        //$$ builder.vertex(matrix, x, y, 0).uv(minU, minV).endVertex();
        //$$ tesselator.end();
        //$$ RenderSystem.disableBlend();
        //#endif
    }

    public boolean drawNativeTexture(Object texture, float x, float y, float width, float height, float u, float v, float regionWidth, float regionHeight, float textureWidth, float textureHeight) {
        if (texture == null) return false;
        int dw = Math.max(1, (int) Math.ceil(width));
        int dh = Math.max(1, (int) Math.ceil(height));
        int rw = Math.max(1, (int) Math.ceil(regionWidth));
        int rh = Math.max(1, (int) Math.ceil(regionHeight));
        int tw = Math.max(1, (int) Math.ceil(textureWidth));
        int th = Math.max(1, (int) Math.ceil(textureHeight));
        //#if MC >= 1.21.11 || MC >= 26.1
        if (texture instanceof Identifier id) {
            //#if MC >= 26.2 && MC < 26.3
            if (id.getPath().startsWith("textures/gui/container/")) {
                return false;
            }
            //#endif
            graphics.blit(RenderPipelines.GUI_TEXTURED, id, (int) Math.round(x), (int) Math.round(y), u, v, dw, dh, rw, rh, tw, th);
            return true;
        }
        //#endif
        //#if MC >= 1.21.9 && MC < 1.21.11
        //$$ if (texture instanceof ResourceLocation id) {
        //$$     graphics.blit(RenderPipelines.GUI_TEXTURED, id, (int) Math.round(x), (int) Math.round(y), u, v, dw, dh, rw, rh, tw, th);
        //$$     return true;
        //$$ }
        //#endif
        //#if MC >= 1.21.6 && MC < 1.21.9
        //$$ if (texture instanceof ResourceLocation id) {
        //$$     graphics.blit(RenderPipelines.GUI_TEXTURED, id, (int) Math.round(x), (int) Math.round(y), u, v, dw, dh, rw, rh, tw, th);
        //$$     return true;
        //$$ }
        //#endif
        //#if MC >= 1.21.5 && MC < 1.21.6
        //$$ if (texture instanceof ResourceLocation id) {
        //$$     graphics.blit(RenderType::guiTextured, id, (int) Math.round(x), (int) Math.round(y), u, v, dw, dh, rw, rh, tw, th);
        //$$     return true;
        //$$ }
        //#endif
        //#if MC >= 1.21.4 && MC < 1.21.5
        //$$ if (texture instanceof ResourceLocation id) {
        //$$     graphics.blit(RenderType::guiTextured, id, (int) Math.round(x), (int) Math.round(y), u, v, dw, dh, rw, rh, tw, th);
        //$$     return true;
        //$$ }
        //#endif
        //#if MC >= 1.20.1 && MC < 1.21.4
        //$$ if (texture instanceof ResourceLocation id) {
        //$$     graphics.blit(id, (int) Math.round(x), (int) Math.round(y), dw, dh, u, v, rw, rh, tw, th);
        //$$     return true;
        //$$ }
        //#endif
        return false;
    }

    public void drawInvertedRect(float x1, float y1, float x2, float y2) {
        if (x1 == x2 || y1 == y2) return;
        float minX = Math.min(x1, x2);
        float minY = Math.min(y1, y2);
        float maxX = Math.max(x1, x2);
        float maxY = Math.max(y1, y2);
        //#if MC >= 1.21.11 || MC >= 26.1
        graphics.textHighlight((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY), true);
        //#endif
        //#if MC >= 1.21.8 && MC < 1.21.11
        //$$ graphics.textHighlight((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY));
        //#endif
        //#if MC >= 1.21.6 && MC < 1.21.8
        //$$ graphics.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, (int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY), SELECTION_COLOR);
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ graphics.fill(RenderType.guiTextHighlight(), (int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY), SELECTION_COLOR);
        //#endif
        //#if MC < 1.20.1
        //$$ float alpha = ((SELECTION_COLOR >> 24) & 0xFF) / 255.0f;
        //$$ float red = 0.0f;
        //$$ float green = 0.0f;
        //$$ float blue = (SELECTION_COLOR & 0xFF) / 255.0f;
        //$$ RenderSystem.enableColorLogicOp();
        //$$ RenderSystem.logicOp(GL11.GL_OR_REVERSE);
        //$$ RenderSystem.disableBlend();
        //$$ RenderSystem.setShaderColor(red, green, blue, alpha);
        //$$ RenderSystem.setShader(GameRenderer::getPositionColorShader);
        //$$ Matrix4f matrix = graphics.pose().last().pose();
        //$$ Tesselator tesselator = Tesselator.getInstance();
        //$$ BufferBuilder builder = tesselator.getBuilder();
        //$$ builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        //$$ builder.vertex(matrix, minX, minY, 0).color(red, green, blue, alpha).endVertex();
        //$$ builder.vertex(matrix, maxX, minY, 0).color(red, green, blue, alpha).endVertex();
        //$$ builder.vertex(matrix, maxX, maxY, 0).color(red, green, blue, alpha).endVertex();
        //$$ builder.vertex(matrix, minX, maxY, 0).color(red, green, blue, alpha).endVertex();
        //$$ tesselator.end();
        //$$ RenderSystem.disableColorLogicOp();
        //$$ RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        //#endif
    }

    private static final class McMatrixStack implements ReMatrixStack {
        //#if MC >= 1.21.6 || MC >= 26.1
        private final org.joml.Matrix3x2fStack pose;

        private McMatrixStack(org.joml.Matrix3x2fStack pose) {
            this.pose = pose;
        }
        //#endif

        //#if MC < 1.21.6 && MC < 26.1
        //$$ private final PoseStack pose;
        //$$
        //$$ private McMatrixStack(PoseStack pose) {
        //$$     this.pose = pose;
        //$$ }
        //#endif

        @Override
        public void push() {
            //#if MC >= 1.21.6 || MC >= 26.1
            pose.pushMatrix();
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ pose.pushPose();
            //#endif
        }

        @Override
        public void pop() {
            //#if MC >= 1.21.6 || MC >= 26.1
            pose.popMatrix();
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ pose.popPose();
            //#endif
        }

        @Override
        public void translate(float x, float y, float z) {
            //#if MC >= 1.21.6 || MC >= 26.1
            pose.translate(x, y);
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ pose.translate(x, y, z);
            //#endif
        }

        @Override
        public void scale(float x, float y, float z) {
            //#if MC >= 1.21.6 || MC >= 26.1
            pose.scale(x, y);
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ pose.scale(x, y, z);
            //#endif
        }

        @Override
        public void rotate(float angle, float x, float y, float z) {
            //#if MC >= 1.21.6 || MC >= 26.1
            pose.rotate((float) Math.toRadians(angle));
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ pose.mulPose(new Quaternionf().fromAxisAngleDeg(x, y, z, angle));
            //#endif
        }

        @Override
        public void multiply(float angle) {
            //#if MC >= 1.21.6 || MC >= 26.1
            pose.rotate((float) Math.toRadians(angle));
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ pose.mulPose(new Quaternionf().fromAxisAngleDeg(0f, 0f, 1f, angle));
            //#endif
        }
    }

    private final class McScissorStack implements ReScissorStack {
        private final Deque<float[]> stack = new ArrayDeque<>();
        private final Deque<List<float[]>> stateStack = new ArrayDeque<>();

        @Override
        public void pushState() {
            List<float[]> snapshot = new ArrayList<>(stack.size());
            for (float[] entry : stack) {
                snapshot.add(new float[]{entry[0], entry[1], entry[2], entry[3]});
            }
            stateStack.push(snapshot);
        }

        @Override
        public void popState() {
            if (stateStack.isEmpty()) return;
            List<float[]> snapshot = stateStack.pop();
            while (nativeScissorDepth > 0) {
                disableNativeScissor();
                nativeScissorDepth--;
            }
            stack.clear();
            for (float[] entry : snapshot) {
                stack.addLast(new float[]{entry[0], entry[1], entry[2], entry[3]});
            }
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                if (applyScissor(snapshot.get(i))) {
                    nativeScissorDepth++;
                }
            }
        }

        @Override
        public void clear() {
            while (nativeScissorDepth > 0) {
                disableNativeScissor();
                nativeScissorDepth--;
            }
            stack.clear();
        }

        @Override
        public void enable(float x1, float y1, float x2, float y2) {
            float reqX = x1;
            float reqY = y1;
            float reqW = x2 - x1;
            float reqH = y2 - y1;

            if (!stack.isEmpty()) {
                float[] parent = stack.peek();
                float parentX = parent[0];
                float parentY = parent[1];
                float parentW = parent[2];
                float parentH = parent[3];

                float intersectX1 = Math.max(reqX, parentX);
                float intersectY1 = Math.max(reqY, parentY);
                float intersectX2 = Math.min(reqX + reqW, parentX + parentW);
                float intersectY2 = Math.min(reqY + reqH, parentY + parentH);

                reqX = intersectX1;
                reqY = intersectY1;
                reqW = Math.max(0, intersectX2 - intersectX1);
                reqH = Math.max(0, intersectY2 - intersectY1);
            }

            float[] entry = new float[]{reqX, reqY, reqW, reqH};
            stack.push(entry);
            if (applyScissor(entry)) {
                nativeScissorDepth++;
            }
        }

        @Override
        public void disable() {
            if (stack.isEmpty()) return;
            stack.pop();
            if (nativeScissorDepth > 0) {
                disableNativeScissor();
                nativeScissorDepth--;
            }
        }

        @Override
        public boolean contains(int x, int y) {
            if (stack.isEmpty()) return true;
            float[] entry = stack.peek();
            return x >= entry[0] && x < entry[0] + entry[2] && y >= entry[1] && y < entry[1] + entry[3];
        }

        @Override
        public ScissorBox getCurrent() {
            if (stack.isEmpty()) return null;
            float[] entry = stack.peek();
            return new ScissorBox((int) entry[0], (int) entry[1], (int) entry[2], (int) entry[3]);
        }

    }

    private record ArmorTrimTag(String material, String pattern) {
    }

    //#if MC >= 1.21.1 || MC >= 26.1
    private static final class PreviewRemotePlayer extends RemotePlayer {
        private final PlayerSkin skin;

        private PreviewRemotePlayer(ClientLevel level, GameProfile profile, PlayerSkin skin) {
            super(level, profile);
            this.skin = skin;
        }

        @Override
        public PlayerSkin getSkin() {
            return skin;
        }

        @Override
        public boolean isModelPartShown(PlayerModelPart part) {
            return true;
        }
    }
    //#endif

    private record CachedTexture(ReTextureHandle handle, DynamicTexture texture, NativeImage image, long revision) {
    }

    private static void releaseTexture(BufferedImage image) {
        CachedTexture cached;
        synchronized (TEXTURE_CACHE) {
            cached = TEXTURE_CACHE.remove(image);
        }
        if (cached != null) {
            closeTexture(cached);
        }
    }

    private static void closeTexture(CachedTexture cached) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            cached.texture().close();
        } else {
            minecraft.execute(cached.texture()::close);
        }
    }

    private final class McTextureCache implements ReTextureCache {
        private int[] imagePixels = new int[0];

        @Override
        public ReTextureHandle getTexture(BufferedImage image) {
            long revision = ResourceManager.getInstance().imageRevision(image);
            CachedTexture existing = TEXTURE_CACHE.get(image);
            if (existing != null && existing.revision() == revision) return existing.handle();
            synchronized (TEXTURE_CACHE) {
                CachedTexture again = TEXTURE_CACHE.get(image);
                if (again != null && again.revision() == revision) return again.handle();
                if (again != null && again.image().getWidth() == image.getWidth() && again.image().getHeight() == image.getHeight()) {
                    writeNativeImage(image, again.image());
                    again.texture().upload();
                    CachedTexture updated = new CachedTexture(again.handle(), again.texture(), again.image(), revision);
                    TEXTURE_CACHE.put(image, updated);
                    return updated.handle();
                }
                int width = image.getWidth();
                int height = image.getHeight();
                NativeImage nativeImage = new NativeImage(width, height, true);
                writeNativeImage(image, nativeImage);
                //#if MC >= 1.21.5 || MC >= 26.1
                DynamicTexture dynamicTexture = new DynamicTexture(() -> "rematrix", nativeImage);
                //#endif
                //#if MC < 1.21.5 && MC < 26.1
                //$$ DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
                //#endif
                TextureManager textureManager = Minecraft.getInstance().getTextureManager();
                //#if MC >= 1.21.11 || MC >= 26.1
                Identifier id = Identifier.fromNamespaceAndPath("rematrix", "img_" + System.identityHashCode(image));
                textureManager.register(id, dynamicTexture);
                //#endif
                //#if MC < 1.21.11 && MC >= 1.21.1
                //$$ ResourceLocation id = ResourceLocation.fromNamespaceAndPath("rematrix", "img_" + System.identityHashCode(image));
                //$$ textureManager.register(id, dynamicTexture);
                //#endif
                //#if MC < 1.21.1
                //$$ ResourceLocation id = new ResourceLocation("rematrix", "img_" + System.identityHashCode(image));
                //$$ textureManager.register(id, dynamicTexture);
                //#endif
                ReTextureHandle handle = new ReTextureHandle(id, width, height);
                TEXTURE_CACHE.put(image, new CachedTexture(handle, dynamicTexture, nativeImage, revision));
                return handle;
            }
        }

        private void writeNativeImage(BufferedImage source, NativeImage destination) {
            int width = source.getWidth();
            int height = source.getHeight();
            int pixelCount = width * height;
            if (imagePixels.length < pixelCount) imagePixels = new int[pixelCount];
            source.getRGB(0, 0, width, height, imagePixels, 0, width);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = imagePixels[y * width + x];
                    int abgr = (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16);
                    //#if MC >= 1.21.5 || MC >= 26.1
                    destination.setPixelABGR(x, y, abgr);
                    //#endif
                    //#if MC >= 1.21.4 && MC < 1.21.5
                    //$$ destination.setPixel(x, y, argb);
                    //#endif
                    //#if MC < 1.21.4
                    //$$ destination.setPixelRGBA(x, y, abgr);
                    //#endif
                }
            }
        }

        @Override
        public void clear() {
            List<CachedTexture> cachedTextures;
            synchronized (TEXTURE_CACHE) {
                cachedTextures = new ArrayList<>(TEXTURE_CACHE.values());
                TEXTURE_CACHE.clear();
            }
            cachedTextures.forEach(RematrixContext::closeTexture);
        }
    }

    private final class McTextBridge implements ReTextBridge {
        @Override
        public int getWidth(String text) {
            return Minecraft.getInstance().font.width(text);
        }

        @Override
        public int getWidth(String text, Object font) {
            //#if MC >= 1.21.11 || MC >= 26.1
            if (font instanceof Identifier rl) {
            //#endif
            //#if MC < 1.21.11 && MC < 26.1
            //$$ if (font instanceof ResourceLocation rl) {
            //#endif
                MutableComponent component = Component.literal(text);
                //#if MC >= 1.21.9 || MC >= 26.1
                component.setStyle(Style.EMPTY.withFont(new FontDescription.Resource(rl)));
                //#endif
                //#if MC < 1.21.9 && MC < 26.1
                //$$ component.setStyle(Style.EMPTY.withFont(rl));
                //#endif
                return Minecraft.getInstance().font.width(component);
            }
            return getWidth(text);
        }

        @Override
        public String trimToWidth(String text, int maxWidth) {
            int textWidth = getWidth(text);
            if (textWidth <= maxWidth) {
                return text;
            }
            StringBuilder trimmed = new StringBuilder();
            for (char c : text.toCharArray()) {
                trimmed.append(c);
                if (getWidth(trimmed.toString()) > maxWidth) {
                    trimmed.deleteCharAt(trimmed.length() - 1);
                    break;
                }
            }
            return trimmed.toString();
        }
    }

    private void drawTooltipOnTop(Runnable draw) {
        //#if MC >= 1.21.6 || MC >= 26.1
        graphics.nextStratum();
        //#endif
        draw.run();
    }

    private void disableNativeScissor() {
        //#if MC >= 1.20.1
        graphics.disableScissor();
        //#endif
    }

    public void advanceRenderLayer() {
        //#if MC >= 1.21.6 || MC >= 26.1
        graphics.nextStratum();
        //#elseif MC >= 1.20.1
        //$$ graphics.flush();
        //$$ matrices.translate(0, 0, 1000.0f);
        //#else
        //$$ matrices.translate(0, 0, 1000.0f);
        //#endif
    }

    private boolean applyScissor(float[] scissor) {
        //#if MC >= 1.20.1
        double x1 = alignedScissorEdge(scissor[0] * (double) scissorScale);
        double y1 = alignedScissorEdge(scissor[1] * (double) scissorScale);
        double x2 = alignedScissorEdge((scissor[0] + scissor[2]) * (double) scissorScale);
        double y2 = alignedScissorEdge((scissor[1] + scissor[3]) * (double) scissorScale);
        int ix = (int) Math.floor(x1);
        int iy = (int) Math.floor(y1);
        int iw = Math.max(0, (int) Math.ceil(x2) - ix);
        int ih = Math.max(0, (int) Math.ceil(y2) - iy);
        //#if MC >= 1.21.6 || MC >= 26.1
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.identity();
        graphics.enableScissor(ix, iy, ix + iw, iy + ih);
        pose.popMatrix();
        //#endif
        //#if MC < 1.21.6 && MC < 26.1
        //$$ graphics.pose().pushPose();
        //$$ graphics.pose().last().pose().identity();
        //$$ graphics.enableScissor(ix, iy, ix + iw, iy + ih);
        //$$ graphics.pose().popPose();
        //#endif
        return true;
        //#endif
        //#if MC < 1.20.1
        //$$ return false;
        //#endif
    }

    private static double alignedScissorEdge(double value) {
        double whole = Math.rint(value);
        return Math.abs(value - whole) <= Math.ulp((float) value) * 2d ? whole : value;
    }

    //#if MC < 1.20.1
    //$$ private void applyScissor(int x, int y, int width, int height) {
    //$$     int windowHeight = Minecraft.getInstance().getWindow().getHeight();
    //$$     int scaledX = (int) (x * scissorScale);
    //$$     int scaledY = (int) (y * scissorScale);
    //$$     int scaledWidth = (int) (width * scissorScale);
    //$$     int scaledHeight = (int) (height * scissorScale);
    //$$     RenderSystem.enableScissor(scaledX, windowHeight - (scaledY + scaledHeight), scaledWidth, scaledHeight);
    //$$ }
    //#endif
}
