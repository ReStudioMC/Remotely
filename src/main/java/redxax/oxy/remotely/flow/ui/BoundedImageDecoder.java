package redxax.oxy.remotely.flow.ui;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

final class BoundedImageDecoder {
    private BoundedImageDecoder() {
    }

    static BufferedImage read(Path source, long maxEncodedBytes, long maxPixels, long maxDecodedBytes) {
        if (source == null || maxEncodedBytes < 1L) {
            return null;
        }
        try {
            if (!Files.isRegularFile(source) || Files.size(source) > maxEncodedBytes) {
                return null;
            }
            try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
                return read(input, maxPixels, maxDecodedBytes);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static BufferedImage read(byte[] source, long maxEncodedBytes, long maxPixels, long maxDecodedBytes) {
        if (source == null || maxEncodedBytes < 1L || source.length > maxEncodedBytes) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            return read(input, maxPixels, maxDecodedBytes);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean withinBounds(BufferedImage image, long maxPixels, long maxDecodedBytes) {
        if (image == null || maxPixels < 1L || maxDecodedBytes < 4L) {
            return false;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 1 || height < 1) {
            return false;
        }
        long pixels = (long) width * height;
        long rowBytes = (long) height * 4L;
        long decodedBytes = width > Long.MAX_VALUE / rowBytes
            ? Long.MAX_VALUE : width * rowBytes;
        return pixels <= maxPixels && decodedBytes <= maxDecodedBytes;
    }

    private static BufferedImage read(ImageInputStream input, long maxPixels, long maxDecodedBytes) throws IOException {
        if (input == null || maxPixels < 1L || maxDecodedBytes < 4L) {
            return null;
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            long pixels = (long) width * height;
            if (width < 1 || height < 1 || pixels > maxPixels || pixels > maxDecodedBytes / 4L) {
                return null;
            }
            BufferedImage destination = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setDestination(destination);
            BufferedImage image = reader.read(0, param);
            return image == destination && withinBounds(image, maxPixels, maxDecodedBytes) ? image : null;
        } finally {
            reader.dispose();
        }
    }
}
