package com.chat99.server.oss;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.stereotype.Component;

@Component
public class ImageProcessor {

    public BufferedImage decode(byte[] src) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
        if (img == null) {
            throw new IOException("not a valid image");
        }
        return img;
    }

    public byte[] toJpeg(BufferedImage src, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(src)
            .scale(1.0)
            .outputFormat("jpg")
            .outputQuality(clampQuality(quality))
            .toOutputStream(out);
        return out.toByteArray();
    }

    /** Encode as WebP via imageio-webp. {@code quality} is 0.0–1.0. */
    public byte[] toWebp(BufferedImage src, float quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            if (!ImageIO.write(src, "webp", out)) {
                throw new IOException("webp writer not available");
            }
            return out.toByteArray();
        }
        var writer = writers.next();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            var param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                String[] types = param.getCompressionTypes();
                if (types != null && types.length > 0) {
                    param.setCompressionType(types[0]);
                }
                param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
            }
            writer.write(null, new javax.imageio.IIOImage(src, null, null), param);
            ios.flush();
        } finally {
            writer.dispose();
        }
        if (out.size() == 0) {
            throw new IOException("webp encode produced empty output");
        }
        return out.toByteArray();
    }

    public byte[] resizeKeepAspect(BufferedImage src, int longEdge, int quality) throws IOException {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= longEdge) {
            return toJpeg(src, quality);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(src)
            .size(longEdge, longEdge)
            .outputFormat("jpg")
            .outputQuality(clampQuality(quality))
            .toOutputStream(out);
        return out.toByteArray();
    }

    public byte[] cropCenterSquare(BufferedImage src, int size, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(src)
            .crop(Positions.CENTER)
            .size(size, size)
            .outputFormat("jpg")
            .outputQuality(clampQuality(quality))
            .toOutputStream(out);
        return out.toByteArray();
    }

    private static float clampQuality(int q) {
        if (q <= 0) return 0.85f;
        if (q > 100) return 1.0f;
        return q / 100f;
    }
}
