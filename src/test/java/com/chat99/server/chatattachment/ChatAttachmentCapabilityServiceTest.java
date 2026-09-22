package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ChatAttachmentCapabilityServiceTest {

    @Test
    void missingProtocolIsIncapable() {
        ChatAttachmentCapabilityService service = new ChatAttachmentCapabilityService(
            ChatAttachmentTestSupport.props(), null, null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Client-Platform", "android");
        req.addHeader("X-App-Version", "3.0.2+7");
        req.addHeader("X-App-Version-Code", "7");
        assertThat(service.read(req).capable()).isFalse();
        req.addHeader("X-Chat-Attachment-Protocol-Version", "1");
        assertThat(service.read(req).capable()).isTrue();
    }

    @Test
    void desktopIsIncapable() {
        ChatAttachmentCapabilityService service = new ChatAttachmentCapabilityService(
            ChatAttachmentTestSupport.props(), null, null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Client-Platform", "windows");
        req.addHeader("X-App-Version", "3.0.2+7");
        req.addHeader("X-App-Version-Code", "7");
        req.addHeader("X-Chat-Attachment-Protocol-Version", "1");
        assertThat(service.read(req).capable()).isFalse();
    }

    @Test
    void oldBuildIsStillCapable() {
        ChatAttachmentCapabilityService service = new ChatAttachmentCapabilityService(
            ChatAttachmentTestSupport.props(), null, null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Client-Platform", "ios");
        req.addHeader("X-App-Version", "3.0.1+6");
        req.addHeader("X-App-Version-Code", "6");
        req.addHeader("X-Chat-Attachment-Protocol-Version", "1");
        assertThat(service.read(req).capable()).isTrue();
    }

    @Test
    void version301Build7IsCapable() {
        ChatAttachmentCapabilityService service = new ChatAttachmentCapabilityService(
            ChatAttachmentTestSupport.props(), null, null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Client-Platform", "android");
        req.addHeader("X-App-Version", "3.0.1");
        req.addHeader("X-App-Version-Code", "7");
        req.addHeader("X-Chat-Attachment-Protocol-Version", "1");
        assertThat(service.read(req).capable()).isTrue();
    }
}
