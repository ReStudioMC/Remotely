package redxax.oxy.remotely.packcontent;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class DesktopGlyphFrameLoader {
    private final PackContentContext context;

    DesktopGlyphFrameLoader(PackContentContext context) {
        this.context = context;
    }

    List<GlyphPreviewFrame> load(GlyphAssetRef asset, int rows, int columns, Integer requestedIndex) throws Exception {
        if (asset == null || asset.resolvedPath() == null) return List.of();
        Path local = PackContentAssetCache.get().localPath(context, Path.of(asset.resolvedPath()));
        if (local == null || !Files.exists(local)) return List.of();
        if (asset.gif()) return readGifFrames(local);
        BufferedImage image = ImageIO.read(local.toFile());
        if (image == null) return List.of();
        if (rows > 1 || columns > 1) {
            int total = Math.max(1, rows * columns);
            int index = Math.clamp(requestedIndex == null ? 0 : requestedIndex, 0, total - 1);
            int cellWidth = Math.max(1, image.getWidth() / Math.max(1, columns));
            int cellHeight = Math.max(1, image.getHeight() / Math.max(1, rows));
            int x = index % Math.max(1, columns);
            int y = index / Math.max(1, columns);
            int width = Math.min(cellWidth, image.getWidth() - x * cellWidth);
            int height = Math.min(cellHeight, image.getHeight() - y * cellHeight);
            return frameList(image.getSubimage(x * cellWidth, y * cellHeight, width, height), 100);
        }
        return frameList(image, 100);
    }

    private List<GlyphPreviewFrame> readGifFrames(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return frameList(ImageIO.read(new ByteArrayInputStream(bytes)), 100);
            ImageReader reader = readers.next();
            reader.setInput(stream);
            int count = reader.getNumImages(true);
            List<GlyphPreviewFrame> frames = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                GlyphPreviewFrame frame = DesktopGlyphPreviewFrame.of(reader.read(index), gifDelay(reader.getImageMetadata(index)));
                if (frame != null) frames.add(frame);
            }
            reader.dispose();
            return frames;
        }
    }

    private List<GlyphPreviewFrame> frameList(BufferedImage image, int delayMs) {
        GlyphPreviewFrame frame = DesktopGlyphPreviewFrame.of(image, delayMs);
        return frame == null ? List.of() : List.of(frame);
    }

    private int gifDelay(IIOMetadata metadata) {
        if (metadata == null) return 100;
        try {
            Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            Node graphics = findNode(root, "GraphicControlExtension");
            if (graphics == null) return 100;
            NamedNodeMap attributes = graphics.getAttributes();
            Node delay = attributes == null ? null : attributes.getNamedItem("delayTime");
            return delay == null ? 100 : Math.max(20, Integer.parseInt(delay.getNodeValue()) * 10);
        } catch (Exception ignored) {
            return 100;
        }
    }

    private Node findNode(Node node, String name) {
        if (node == null) return null;
        if (name.equals(node.getNodeName())) return node;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node found = findNode(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
