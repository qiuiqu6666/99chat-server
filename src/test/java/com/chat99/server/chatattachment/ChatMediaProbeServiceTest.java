package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChatMediaProbeServiceTest {

    private final ChatMediaProbeService service = new ChatMediaProbeService(
        ChatAttachmentTestSupport.props(),
        mock(ChatAttachmentOssClient.class),
        mock(ChatAttachmentRepository.class),
        new ObjectMapper());

    @Test
    void parsesFfprobeJson() {
        String raw = """
            {
              "streams": [
                {"codec_name":"aac","codec_type":"audio"},
                {"codec_name":"h264","width":1920,"height":1080,"codec_type":"video"}
              ],
              "format": {"duration":"125.4"}
            }
            """;
        var result = service.parse(raw).orElseThrow();
        assertThat(result.durationMs()).isEqualTo(125_400L);
        assertThat(result.width()).isEqualTo(1920);
        assertThat(result.height()).isEqualTo(1080);
        assertThat(result.codecName()).isEqualTo("h264");
    }

    @Test
    void emptyDurationIsMissing() {
        assertThat(service.parse("{\"format\":{\"duration\":\"0\"}}")).isEmpty();
        assertThat(service.parse("{}")).isEmpty();
        assertThat(service.parse("not-json")).isEmpty();
    }
}
