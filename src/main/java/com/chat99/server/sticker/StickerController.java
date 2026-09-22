package com.chat99.server.sticker;

import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class StickerController {

    private final StickerService stickerService;

    public StickerController(StickerService stickerService) {
        this.stickerService = stickerService;
    }

    @GetMapping("/stickers/{stickerId}")
    public StickerService.StickerItemView getSticker(@PathVariable String stickerId) {
        return stickerService.getSticker(stickerId);
    }

    @PostMapping("/stickers/batch")
    public StickerService.BatchResponse batchGetStickers(@Valid @RequestBody StickerService.BatchRequest req) {
        return stickerService.batchGetStickers(req);
    }

    @PostMapping("/stickers/upload")
    public StickerService.StickerItemView upload(
        Authentication auth,
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "mediaType", required = false) String mediaType) throws IOException {
        return stickerService.upload((String) auth.getPrincipal(), file, mediaType);
    }
}
