package com.chat99.server.favorite;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MessageFavoriteController {

    private final MessageFavoriteService favoriteService;

    public MessageFavoriteController(MessageFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping("/me/favorites")
    public MessageFavoriteService.FavoriteListResponse list(
        Authentication auth,
        @RequestParam(required = false) FavoriteType type,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return favoriteService.list(userId(auth), type, page, size);
    }

    @GetMapping("/me/favorites/{id}")
    public MessageFavoriteService.FavoriteItemView get(Authentication auth, @PathVariable String id) {
        return favoriteService.get(userId(auth), id);
    }

    @PostMapping("/me/favorites")
    public MessageFavoriteService.FavoriteItemView create(
        Authentication auth,
        @Valid @RequestBody CreateFavoriteBody body) throws IOException {
        return favoriteService.create(userId(auth), body.toRequest());
    }

    @PostMapping(value = "/me/favorites/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageFavoriteService.FavoriteItemView upload(
        Authentication auth,
        @RequestParam(value = "file", required = false) MultipartFile file,
        @RequestParam(value = "snapshot", required = false) MultipartFile snapshot,
        @RequestParam(value = "metadata", required = false) String metadata) throws IOException {
        return favoriteService.upload(userId(auth), file, snapshot, metadata);
    }

    @PutMapping("/me/favorites/{id}")
    public MessageFavoriteService.FavoriteItemView update(
        Authentication auth,
        @PathVariable String id,
        @Valid @RequestBody MessageFavoriteService.UpdateFavoriteRequest body) {
        return favoriteService.update(userId(auth), id, body);
    }

    @DeleteMapping("/me/favorites/{id}")
    public void delete(Authentication auth, @PathVariable String id) {
        favoriteService.delete(userId(auth), id);
    }

    public record BatchDeleteBody(@NotEmpty List<String> ids) {}

    @DeleteMapping("/me/favorites")
    public MessageFavoriteService.BatchDeleteResponse deleteBatch(
        Authentication auth,
        @Valid @RequestBody BatchDeleteBody body) {
        return favoriteService.deleteBatch(userId(auth), body.ids());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateFavoriteBody(
        @JsonAlias({"messageType", "favoriteType", "msgType"}) FavoriteType type,
        @JsonAlias({"elemType", "elementType"}) Integer elemType,
        @JsonAlias({"content", "body", "note"}) String text,
        @JsonAlias({"mediaUrl", "url"}) String remoteMediaUrl,
        String remoteThumbUrl,
        Integer durationSec,
        String sourceMsgId,
        String sourceConvId,
        String sourceSenderName,
        String sourceConvLabel,
        String remark) {

        MessageFavoriteService.CreateFavoriteRequest toRequest() {
            FavoriteType resolvedType = type;
            if (resolvedType == null && elemType != null) {
                resolvedType = FavoriteType.fromImElemType(elemType);
            }
            if (resolvedType == null) {
                resolvedType = FavoriteType.inferFromPayload(text, remoteMediaUrl, durationSec);
            }
            return new MessageFavoriteService.CreateFavoriteRequest(
                resolvedType, text, remoteMediaUrl, remoteThumbUrl, durationSec,
                sourceMsgId, sourceConvId, sourceSenderName, sourceConvLabel, remark);
        }
    }

    private static String userId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return (String) auth.getPrincipal();
    }
}
