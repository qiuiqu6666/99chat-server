package com.chat99.server.chatattachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatMediaProbeService {

    private static final Logger log = LoggerFactory.getLogger(ChatMediaProbeService.class);

    public record ProbeResult(long durationMs, Integer width, Integer height, String codecName) {}

    private final ChatAttachmentProperties props;
    private final ChatAttachmentOssClient oss;
    private final ChatAttachmentRepository attachmentRepository;
    private final ObjectMapper json;

    public ChatMediaProbeService(ChatAttachmentProperties props,
                                 ChatAttachmentOssClient oss,
                                 ChatAttachmentRepository attachmentRepository,
                                 ObjectMapper json) {
        this.props = props;
        this.oss = oss;
        this.attachmentRepository = attachmentRepository;
        this.json = json;
    }

    public ChatAttachment fillIfMissing(ChatAttachment attachment) {
        if (attachment == null || attachment.getKind() != ChatAttachmentKind.video) {
            return attachment;
        }
        if (ChatMediaMetadata.sanitizeDurationMs(attachment.getDurationMs()) != null) {
            return attachment;
        }
        Optional<ProbeResult> probed = probe(attachment);
        if (probed.isEmpty()) {
            return attachment;
        }
        ProbeResult result = probed.get();
        attachment.setDurationMs(result.durationMs());
        if (ChatMediaMetadata.sanitizePx(attachment.getWidth()) == null) {
            attachment.setWidth(result.width());
        }
        if (ChatMediaMetadata.sanitizePx(attachment.getHeight()) == null) {
            attachment.setHeight(result.height());
        }
        ChatMediaMetadata.markSource(attachment, ChatMetadataProvenance.server);
        return attachmentRepository.save(attachment);
    }

    Optional<ProbeResult> probe(ChatAttachment attachment) {
        String ffprobe = ffprobeBinary();
        if (ffprobe == null) {
            log.warn("chat media probe skipped ffprobe missing attachmentId={}", attachment.getAttachmentId());
            return Optional.empty();
        }
        String source;
        try {
            source = oss.presignGet(attachment.getObjectKey(), 120, null, null);
        } catch (RuntimeException e) {
            log.warn("chat media probe presign failed attachmentId={} err={}",
                attachment.getAttachmentId(), e.getMessage());
            return Optional.empty();
        }
        try {
            String raw = exec(List.of(
                ffprobe,
                "-v", "error",
                "-show_entries", "format=duration:stream=width,height,codec_name",
                "-of", "json",
                source), props.mediaProbeTimeoutSeconds());
            return parse(raw);
        } catch (Exception e) {
            log.warn("chat media probe failed attachmentId={} err={}",
                attachment.getAttachmentId(), e.getMessage());
            return Optional.empty();
        }
    }

    Optional<ProbeResult> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = json.readTree(raw);
            double seconds = 0D;
            JsonNode format = root.path("format");
            if (format.hasNonNull("duration")) {
                seconds = format.path("duration").asDouble(0D);
            }
            if (seconds <= 0D) {
                return Optional.empty();
            }
            long durationMs = Math.round(seconds * 1000D);
            if (ChatMediaMetadata.sanitizeDurationMs(durationMs) == null) {
                return Optional.empty();
            }
            Integer width = null;
            Integer height = null;
            String codec = null;
            JsonNode streams = root.path("streams");
            if (streams.isArray()) {
                for (JsonNode stream : streams) {
                    Integer w = ChatMediaMetadata.sanitizePx(
                        stream.hasNonNull("width") ? stream.path("width").asInt() : null);
                    Integer h = ChatMediaMetadata.sanitizePx(
                        stream.hasNonNull("height") ? stream.path("height").asInt() : null);
                    if (w != null && h != null) {
                        width = w;
                        height = h;
                        codec = stream.path("codec_name").asText(null);
                        break;
                    }
                }
            }
            return Optional.of(new ProbeResult(durationMs, width, height, codec));
        } catch (Exception e) {
            log.warn("chat media probe parse failed err={}", e.getMessage());
            return Optional.empty();
        }
    }

    private String exec(List<String> cmd, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffprobe timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffprobe exit=" + process.exitValue());
        }
        return output;
    }

    private String ffprobeBinary() {
        String configured = props.ffprobePath();
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        Path bundled = Path.of("/www/server/ffmpeg/ffmpeg-6.1/ffprobe");
        if (Files.isExecutable(bundled)) {
            return bundled.toString();
        }
        Path usr = Path.of("/usr/bin/ffprobe");
        if (Files.isExecutable(usr)) {
            return usr.toString();
        }
        return null;
    }
}
