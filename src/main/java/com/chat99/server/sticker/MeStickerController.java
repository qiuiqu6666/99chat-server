package com.chat99.server.sticker;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeStickerController {

    private final StickerService stickerService;

    public MeStickerController(StickerService stickerService) {
        this.stickerService = stickerService;
    }

    @GetMapping("/me/sticker-packs")
    public Map<String, List<StickerService.StickerPackView>> listPacks(Authentication auth) {
        return Map.of("packs", stickerService.listMyPacks((String) auth.getPrincipal()));
    }

    @PutMapping("/me/sticker-packs/order")
    public Map<String, Boolean> updateOrder(Authentication auth,
                                            @Valid @RequestBody StickerService.PackOrderRequest req) {
        stickerService.updatePackOrder((String) auth.getPrincipal(), req);
        return Map.of("ok", true);
    }

    @DeleteMapping("/me/sticker-packs/{packId}")
    public Map<String, Boolean> removePack(Authentication auth, @PathVariable String packId) {
        stickerService.removePack((String) auth.getPrincipal(), packId);
        return Map.of("ok", true);
    }

    @GetMapping("/me/stickers/favorites")
    public Map<String, Object> listFavorites(Authentication auth) {
        List<StickerService.FavoriteItemView> favorites =
            stickerService.listFavorites((String) auth.getPrincipal());
        return Map.of("favorites", favorites, "items", favorites);
    }

    public record FavoriteBody(@NotBlank String stickerId) {}

    @PostMapping("/me/stickers/favorites")
    public Map<String, Boolean> addFavorite(Authentication auth, @Valid @RequestBody FavoriteBody req) {
        stickerService.addFavorite((String) auth.getPrincipal(), req.stickerId());
        return Map.of("ok", true);
    }

    @DeleteMapping("/me/stickers/favorites/{stickerId}")
    public Map<String, Boolean> removeFavorite(Authentication auth, @PathVariable String stickerId) {
        stickerService.removeFavorite((String) auth.getPrincipal(), stickerId);
        return Map.of("ok", true);
    }

    public record CustomItemBody(@NotBlank String stickerId) {}

    @PostMapping("/me/sticker-packs/custom/items")
    public StickerService.StickerItemView addCustomItem(Authentication auth,
                                                        @Valid @RequestBody CustomItemBody req) {
        return stickerService.addCustomItem((String) auth.getPrincipal(), req.stickerId());
    }

    @DeleteMapping("/me/sticker-packs/custom/items/{stickerId}")
    public Map<String, Boolean> removeCustomItem(Authentication auth, @PathVariable String stickerId) {
        stickerService.removeCustomItem((String) auth.getPrincipal(), stickerId);
        return Map.of("ok", true);
    }
}
