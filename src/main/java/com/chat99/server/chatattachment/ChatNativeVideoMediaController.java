package com.chat99.server.chatattachment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatNativeVideoMediaController {

    private final ChatNativeVideoMediaService mediaService;

    public ChatNativeVideoMediaController(ChatNativeVideoMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @RequestMapping(value = "/chat-media/v1/{token:.+}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void media(HttpServletRequest request,
                      HttpServletResponse response,
                      @PathVariable("token") String token) {
        mediaService.write(request, response, token);
    }
}
