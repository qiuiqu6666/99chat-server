package com.chat99.server.sticker;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.sticker.StickerEnums.MediaType;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StickerUploadService {

    private static final Set<String> STATIC_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> STATIC_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> GIF_TYPES = Set.of("image/gif");
    private static final Set<String> VIDEO_TYPES = Set.of(
        "video/mp4", "video/webm", "video/quicktime", "video/x-m4v");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov", "m4v");

    private final OssClient oss;
    private final ImageProcessor processor;
    private final StickerVideoConverter videoConverter;
    private final StickerProperties props;

    public StickerUploadService(OssClient oss,
                                ImageProcessor processor,
                                StickerVideoConverter videoConverter,
                                StickerProperties props) {
        this.oss = oss;
        this.processor = processor;
        this.videoConverter = videoConverter;
        this.props = props;
    }

    public record UploadResult(
        String thumbUrl,
        String originUrl,
        MediaType mediaType,
        int width,
        int height) {}

    public UploadResult upload(String stickerId, MultipartFile file, String mediaTypeHint) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILE");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }

        String base = props.ossPrefix() + stickerId + "/";

        if (isVideo(file, mediaTypeHint)) {
            if (file.getSize() > props.videoMaxBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
            }
            StickerVideoConverter.ConvertResult converted =
                videoConverter.convert(file.getBytes(), videoInputExt(file));
            return uploadGifBytes(base, converted.gifBytes(), converted.width(), converted.height());
        }

        boolean asGif = isGif(file, mediaTypeHint);
        long max = asGif ? props.gifMaxBytes() : props.staticMaxBytes();
        if (file.getSize() > max) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }

        byte[] raw = file.getBytes();
        if (asGif) {
            return uploadGifBytes(base, raw, null, null);
        }
        return uploadStatic(base, raw, file);
    }

    private UploadResult uploadGifBytes(String base, byte[] raw, Integer widthHint, Integer heightHint)
        throws IOException {
        BufferedImage frame;
        try {
            frame = processor.decode(raw);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILE");
        }
        byte[] thumbJpeg = processor.resizeKeepAspect(frame, props.thumbSize(), props.thumbJpegQuality());
        String originKey = base + "origin.gif";
        String thumbKey = base + "thumb.jpg";
        String originUrl = oss.putBytes(originKey, raw, "image/gif");
        String thumbUrl = oss.putBytes(thumbKey, thumbJpeg, "image/jpeg");
        int width = widthHint != null ? widthHint : frame.getWidth();
        int height = heightHint != null ? heightHint : frame.getHeight();
        return new UploadResult(thumbUrl, originUrl, MediaType.gif, width, height);
    }

    private UploadResult uploadStatic(String base, byte[] raw, MultipartFile file) throws IOException {
        String contentType = resolveStaticContentType(file);
        BufferedImage img;
        try {
            img = processor.decode(raw);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILE");
        }
        byte[] thumbJpeg = processor.resizeKeepAspect(img, props.thumbSize(), props.thumbJpegQuality());
        String ext = extensionForContentType(contentType);
        String originKey = base + "origin." + ext;
        String thumbKey = base + "thumb.jpg";
        String originUrl = oss.putBytes(originKey, raw, contentType);
        String thumbUrl = oss.putBytes(thumbKey, thumbJpeg, "image/jpeg");
        return new UploadResult(thumbUrl, originUrl, MediaType.image, img.getWidth(), img.getHeight());
    }

    private boolean isVideo(MultipartFile file, String mediaTypeHint) {
        if (mediaTypeHint != null && "video".equalsIgnoreCase(mediaTypeHint.trim())) {
            return true;
        }
        String ct = file.getContentType();
        if (ct != null && VIDEO_TYPES.contains(ct.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String ext = fileExtension(file);
        return ext != null && VIDEO_EXT.contains(ext);
    }

    private boolean isGif(MultipartFile file, String mediaTypeHint) {
        if (mediaTypeHint != null && "gif".equalsIgnoreCase(mediaTypeHint.trim())) {
            return true;
        }
        String ct = file.getContentType();
        if (ct != null && GIF_TYPES.contains(ct.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String ext = fileExtension(file);
        return "gif".equals(ext);
    }

    private static String videoInputExt(MultipartFile file) {
        String ext = fileExtension(file);
        return ext != null ? ext : "mp4";
    }

    private static String fileExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveStaticContentType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null && STATIC_TYPES.contains(ct.toLowerCase(Locale.ROOT))) {
            return ct.toLowerCase(Locale.ROOT);
        }
        String ext = fileExtension(file);
        if (ext != null && STATIC_EXT.contains(ext)) {
            return switch (ext) {
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                default -> "image/jpeg";
            };
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA");
    }

    private static String extensionForContentType(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
