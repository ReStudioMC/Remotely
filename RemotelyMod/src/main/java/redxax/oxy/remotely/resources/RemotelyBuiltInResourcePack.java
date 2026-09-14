//#if FABRIC
package redxax.oxy.remotely.resources;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
//#if MC >= 1.21.1
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
//#endif
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import redxax.oxy.remotely.Constants;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

public final class RemotelyBuiltInResourcePack {
    private static final String ID = Constants.ID;
    private static final Component TITLE = Component.literal("Remotely");

    private RemotelyBuiltInResourcePack() {
    }

    public static Pack create(String id) {
        Path root = rootPath();
        if (root == null) {
            return null;
        }
        //#if MC >= 1.21.1
        PackLocationInfo location = new PackLocationInfo(id, TITLE, PackSource.BUILT_IN, Optional.empty());
        return Pack.readMetaAndCreate(location, supplier(root), PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, true));
        //#else
        //$$ Pack.ResourcesSupplier supplier = packId -> Files.isDirectory(root)
        //$$     ? new PathPackResources(packId, root, false)
        //$$     : new FilePackResources(packId, root.toFile(), false);
        //$$ return Pack.readMetaAndCreate(id, TITLE, true, supplier, PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
        //#endif
    }

    public static String id() {
        return ID;
    }

    private static Path rootPath() {
        URL location = RemotelyBuiltInResourcePack.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path)) {
                return path;
            }
            if (Files.isDirectory(path) && Files.exists(path.resolve("pack.mcmeta"))) {
                return path;
            }
        } catch (IllegalArgumentException | URISyntaxException ignored) {
        }
        URL metadata = RemotelyBuiltInResourcePack.class.getClassLoader().getResource("pack.mcmeta");
        if (metadata != null && "file".equals(metadata.getProtocol())) {
            try {
                Path metadataPath = Paths.get(metadata.toURI());
                Path parent = metadataPath.getParent();
                if (parent != null && Files.exists(parent.resolve("assets"))) {
                    return parent;
                }
            } catch (IllegalArgumentException | URISyntaxException ignored) {
            }
        }
        return null;
    }

    //#if MC >= 1.21.1
    private static Pack.ResourcesSupplier supplier(Path root) {
        if (Files.isRegularFile(root)) {
            return new FilePackResources.FileResourcesSupplier(root);
        }
        return new Pack.ResourcesSupplier() {
            //#if MC >= 26.3
            @Override
            public PackResources openMetadata(PackLocationInfo location) {
                return new PathPackResources(location, root);
            }

            @Override
            public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
                return Stream.of(new PathPackResources(location, root));
            }
            //#else
            //$$ @Override
            //$$ public PackResources openPrimary(PackLocationInfo location) {
            //$$     return new PathPackResources(location, root);
            //$$ }

            //$$ @Override
            //$$ public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
            //$$     return new PathPackResources(location, root);
            //$$ }
            //#endif
        };
    }
    //#endif
}
//#endif
