package com.chat99.server.sticker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StickerVideoConverter {

    private static final Logger log = LoggerFactory.getLogger(StickerVideoConverter.class);

    public record ConvertResult(byte[] gifBytes, int width, int height) {}

    private final StickerProperties props;
    private volatile Boolean available;
    private volatile String resolvedFfmpeg;
    private volatile String resolvedFfprobe;

    public StickerVideoConverter(StickerProperties props) {
        this.props = props;
    }

    public boolean isAvailable() {
        if (available != null) {
            return available;
        }
        synchronized (this) {
            if (available != null) {
                return available;
            }
            available = resolveBinaries();
            if (!available) {
                log.warn("ffmpeg not available configuredPath={}", props.ffmpegPath());
            }
            return available;
        }
    }

    public ConvertResult convert(byte[] videoBytes, String inputExt) throws IOException {
        if (!props.videoConversionEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_CONVERSION_DISABLED");
        }
        if (!isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "FFMPEG_NOT_CONFIGURED");
        }

        Path workDir = Files.createTempDirectory("sticker-video-");
        Path input = workDir.resolve("input." + sanitizeExt(inputExt));
        Path output = workDir.resolve("output.gif");
        try {
            Files.write(input, videoBytes);
            double duration = probeDuration(input);
            if (duration > props.videoMaxDurationSeconds() + 0.05) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VIDEO_TOO_LONG");
            }

            int[] attempts = buildAttemptMatrix();
            IOException lastError = null;
            for (int i = 0; i < attempts.length; i += 2) {
                int size = attempts[i];
                int fps = attempts[i + 1];
                Files.deleteIfExists(output);
                runFfmpeg(input, output, size, fps);
                byte[] gif = Files.readAllBytes(output);
                if (gif.length <= props.gifMaxBytes()) {
                    int[] dim = probeGifDimensions(output);
                    return new ConvertResult(gif, dim[0], dim[1]);
                }
                log.debug("gif too large after conversion size={} fps={} bytes={}", size, fps, gif.length);
            }
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "VIDEO_CONVERSION_FAILED");
        } finally {
            cleanup(workDir);
        }
    }

    private int[] buildAttemptMatrix() {
        int base = props.videoOutputSize() > 0 ? props.videoOutputSize() : props.thumbSize();
        int fps = props.videoMaxFps() > 0 ? props.videoMaxFps() : 15;
        return new int[] {
            base, fps,
            base, Math.max(8, fps - 5),
            Math.max(120, base - 60), Math.max(8, fps - 5)
        };
    }

    private void runFfmpeg(Path input, Path output, int size, int fps)
        throws IOException, InterruptedException {
        String filter = String.format(Locale.ROOT,
            "fps=%d,scale=%d:%d:force_original_aspect_ratio=decrease:flags=lanczos,"
                + "split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer",
            fps, size, size);
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegBin());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.toString());
        cmd.add("-t");
        cmd.add(String.valueOf(props.videoMaxDurationSeconds()));
        cmd.add("-vf");
        cmd.add(filter);
        cmd.add("-loop");
        cmd.add("0");
        cmd.add(output.toString());
        exec(cmd, props.videoConversionTimeoutSeconds());
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("ffmpeg produced empty gif");
        }
    }

    private double probeDuration(Path input) throws IOException, InterruptedException {
        List<String> cmd = List.of(
            ffprobeBin(),
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            input.toString());
        String out = exec(cmd, 15).trim();
        if (out.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(out);
        } catch (NumberFormatException e) {
            log.warn("unable to parse ffprobe duration output={}", out);
            return 0;
        }
    }

    private int[] probeGifDimensions(Path gif) throws IOException, InterruptedException {
        List<String> cmd = List.of(
            ffprobeBin(),
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-of", "csv=p=0:s=x",
            gif.toString());
        String out = exec(cmd, 15).trim();
        int x = out.indexOf('x');
        if (x > 0) {
            try {
                return new int[] {
                    Integer.parseInt(out.substring(0, x)),
                    Integer.parseInt(out.substring(x + 1))
                };
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        int size = props.videoOutputSize() > 0 ? props.videoOutputSize() : props.thumbSize();
        return new int[] {size, size};
    }

    private String ffmpegBin() {
        return resolvedFfmpeg != null ? resolvedFfmpeg : props.ffmpegPath();
    }

    private String ffprobeBin() {
        return resolvedFfprobe != null ? resolvedFfprobe : props.ffprobePath();
    }

    private boolean resolveBinaries() {
        String ffmpeg = firstWorking(List.of(
            props.ffmpegPath(),
            "/usr/bin/ffmpeg",
            "/www/server/ffmpeg/ffmpeg-6.1/ffmpeg"));
        String ffprobe = firstWorking(List.of(
            props.ffprobePath(),
            "/usr/bin/ffprobe",
            "/www/server/ffmpeg/ffmpeg-6.1/ffprobe"));
        if (ffmpeg == null || ffprobe == null) {
            return false;
        }
        resolvedFfmpeg = ffmpeg;
        resolvedFfprobe = ffprobe;
        log.info("sticker ffmpeg ready ffmpeg={} ffprobe={}", ffmpeg, ffprobe);
        return true;
    }

    private String firstWorking(List<String> candidates) {
        for (String path : candidates) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                exec(List.of(path, "-version"), 5);
                return path;
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private String exec(List<String> cmd, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("command timed out: " + String.join(" ", cmd));
        }
        if (process.exitValue() != 0) {
            throw new IOException("command failed (" + process.exitValue() + "): "
                + String.join(" ", cmd) + " :: " + output);
        }
        return output;
    }

    private static String sanitizeExt(String ext) {
        if (ext == null || ext.isBlank()) {
            return "mp4";
        }
        String cleaned = ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return cleaned.isBlank() ? "mp4" : cleaned;
    }

    private static void cleanup(Path dir) {
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.debug("failed to cleanup temp dir {}", dir, e);
        }
    }
}
