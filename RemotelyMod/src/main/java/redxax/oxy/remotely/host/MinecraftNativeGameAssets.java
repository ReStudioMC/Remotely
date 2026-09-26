package redxax.oxy.remotely.host;

import net.minecraft.client.Minecraft;
//#if MC >= 1.21.11 || MC >= 26.1
import net.minecraft.resources.Identifier;
//#endif
//#if MC < 1.21.11 && MC < 26.1
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import restudio.rescreen.game.AbstractMinecraftGameAssets;
import restudio.rescreen.game.MinecraftAssetReference;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class MinecraftNativeGameAssets extends AbstractMinecraftGameAssets {
    private static final AtomicLong RESOURCE_REVISION = new AtomicLong();
    private final Minecraft minecraft = Minecraft.getInstance();

    public static void resourcesReloaded() {
        RESOURCE_REVISION.incrementAndGet();
    }

    @Override
    protected long getRevision() {
        return RESOURCE_REVISION.get();
    }

    @Override
    protected byte[] loadBytes(MinecraftAssetReference asset) throws IOException {
        if (asset == null) {
            return null;
        }
        Object identifier = getNativeIdentifier(asset);
        if (identifier == null) {
            return null;
        }
        //#if MC >= 26.2 && MC < 26.3
        if (identifier instanceof Identifier id) {
            if (minecraft.getResourceManager() == null) {
                return null;
            }
            try (InputStream inputStream = minecraft.getResourceManager().open(id)) {
                return inputStream.readAllBytes();
            }
        }
        //#endif
        Object resourceManager = minecraft.getResourceManager();
        if (resourceManager == null) {
            return null;
        }
        try (InputStream inputStream = openStream(resourceManager, identifier)) {
            return inputStream != null ? inputStream.readAllBytes() : null;
        }
    }

    @Override
    public List<MinecraftAssetReference> listAssets(String namespace, String prefix, String suffix) {
        Object resourceManager = minecraft.getResourceManager();
        if (resourceManager == null) {
            return List.of();
        }
        String resolvedNamespace = namespace == null || namespace.isBlank() ? "minecraft" : namespace;
        String resolvedPrefix = prefix == null ? "" : prefix;
        String lookupPrefix = resolvedPrefix.endsWith("/") ? resolvedPrefix.substring(0, resolvedPrefix.length() - 1) : resolvedPrefix;
        String resolvedSuffix = suffix == null ? "" : suffix;
        Predicate<Object> filter = identifier -> {
            MinecraftAssetReference asset = MinecraftAssetReference.of(String.valueOf(identifier));
            return asset.namespace().equals(resolvedNamespace)
                    && asset.path().startsWith(resolvedPrefix)
                    && (resolvedSuffix.isBlank() || asset.path().endsWith(resolvedSuffix));
        };
        try {
            Object listed = invokeValue(resourceManager, "listResources", lookupPrefix, filter);
            if (!(listed instanceof Map<?, ?> resources)) {
                return List.of();
            }
            List<MinecraftAssetReference> assets = new ArrayList<>();
            for (Object identifier : resources.keySet()) {
                if (filter.test(identifier)) {
                    assets.add(MinecraftAssetReference.of(String.valueOf(identifier)));
                }
            }
            assets.sort((first, second) -> first.namespacedPath().compareToIgnoreCase(second.namespacedPath()));
            return List.copyOf(assets);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    @Override
    public Object getNativeIdentifier(MinecraftAssetReference asset) {
        if (asset == null) {
            return null;
        }
        //#if MC >= 1.21.11 || MC >= 26.1
        return Identifier.fromNamespaceAndPath(asset.namespace(), asset.path());
        //#endif
        //#if MC < 1.21.11 && MC >= 1.21.1
        //$$ return ResourceLocation.fromNamespaceAndPath(asset.namespace(), asset.path());
        //#endif
        //#if MC < 1.21.1
        //$$ return new ResourceLocation(asset.namespace(), asset.path());
        //#endif
    }

    private InputStream openStream(Object resourceManager, Object identifier) throws IOException {
        InputStream stream = invokeStream(resourceManager, "open", identifier);
        if (stream != null) {
            return stream;
        }
        Object resource = invokeValue(resourceManager, "getResourceOrThrow", identifier);
        stream = openStream(resource);
        if (stream != null) {
            return stream;
        }
        Object optional = invokeValue(resourceManager, "getResource", identifier);
        if (optional instanceof Optional<?> wrapped) {
            return openStream(wrapped.orElse(null));
        }
        return openStream(optional);
    }

    private InputStream openStream(Object resource) throws IOException {
        if (resource == null) {
            return null;
        }
        if (resource instanceof InputStream inputStream) {
            return inputStream;
        }
        Object opened = invokeValue(resource, "open");
        return opened instanceof InputStream inputStream ? inputStream : null;
    }

    private InputStream invokeStream(Object target, String name, Object... args) throws IOException {
        Object value = invokeValue(target, name, args);
        if (value instanceof InputStream inputStream) {
            return inputStream;
        }
        return openStream(value);
    }

    private Object invokeValue(Object target, String name, Object... args) throws IOException {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), name, args);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getTargetException();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException(cause != null ? cause : exception);
        } catch (ReflectiveOperationException exception) {
            throw new IOException(exception);
        }
    }

    private Method findMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || !matches(method.getParameterTypes(), args)) {
                continue;
            }
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
            }
            return method;
        }
        return null;
    }

    private boolean matches(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            Object argument = args[index];
            if (argument == null) {
                continue;
            }
            if (!wrap(parameterTypes[index]).isAssignableFrom(argument.getClass())) {
                return false;
            }
        }
        return true;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
