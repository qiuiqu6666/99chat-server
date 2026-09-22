package com.chat99.server.sticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.sticker.StickerEnums.PackSource;
import com.chat99.server.sticker.StickerEnums.StickerStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class StickerServiceTest {

    @Mock StickerRepository stickerRepository;
    @Mock StickerPackRepository packRepository;
    @Mock StickerPackItemRepository packItemRepository;
    @Mock UserStickerPackRepository userPackRepository;
    @Mock UserStickerPackItemRepository userPackItemRepository;
    @Mock UserStickerFavoriteRepository favoriteRepository;
    @Mock StickerUploadService uploadService;
    @Mock StickerUserInitializer userInitializer;

    private StickerService service;
    private final StickerProperties props = new StickerProperties(
        "stickers/", 2097152, 5242880, 52428800, 480, 95, 480, 10, 20, 120, true,
        "/www/server/ffmpeg/ffmpeg-6.1/ffmpeg", "/www/server/ffmpeg/ffmpeg-6.1/ffprobe", "user_upload", "我的上传", null,
        "favorites", "收藏", 50, true, false, true,
        List.of(new StickerProperties.SystemPack("4350", "默认一", null, 0)));

    @BeforeEach
    void setUp() {
        service = new StickerService(stickerRepository, packRepository, packItemRepository,
            userPackRepository, userPackItemRepository, favoriteRepository,
            uploadService, userInitializer, props);
    }

    @Test
    void listMyPacks_includesSystemPackAndFavorites() {
        UserStickerPack up = new UserStickerPack();
        up.setUserId("u1");
        up.setPackId("4350");
        up.setSortOrder(0);
        when(userPackRepository.findByUserIdOrderBySortOrderAsc("u1")).thenReturn(List.of(up));

        StickerPack pack = new StickerPack();
        pack.setPackId("4350");
        pack.setName("默认一");
        pack.setSource(PackSource.system);
        pack.setRemovable(false);
        when(packRepository.findById("4350")).thenReturn(Optional.of(pack));
        when(favoriteRepository.findByUserIdOrderByFavoritedAtDesc("u1")).thenReturn(List.of());

        var packs = service.listMyPacks("u1");

        assertThat(packs).hasSize(2);
        assertThat(packs.stream().map(StickerService.StickerPackView::packId))
            .containsExactlyInAnyOrder("4350", "favorites");
    }

    @Test
    void getSticker_returnsItemForAnyActiveSticker() {
        Sticker sticker = activeSticker("stk_abc", "u1");
        when(stickerRepository.findById("stk_abc")).thenReturn(Optional.of(sticker));
        when(userPackItemRepository.findByUserIdAndPackIdAndStickerId("u1", "user_upload", "stk_abc"))
            .thenReturn(Optional.empty());

        var view = service.getSticker("stk_abc");

        assertThat(view.stickerId()).isEqualTo("stk_abc");
        assertThat(view.thumbUrl()).isEqualTo("https://cdn/t.webp");
    }

    @Test
    void getSticker_bannedReturns410() {
        Sticker sticker = activeSticker("stk_ban", "u1");
        sticker.setStatus(StickerStatus.banned);
        when(stickerRepository.findById("stk_ban")).thenReturn(Optional.of(sticker));

        assertThatThrownBy(() -> service.getSticker("stk_ban"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(410));
    }

    @Test
    void addCustomItem_allowsNonOwnedSticker() {
        Sticker sticker = activeSticker("stk_other", "owner");
        when(stickerRepository.findByStickerIdAndStatus("stk_other", StickerStatus.active))
            .thenReturn(Optional.of(sticker));
        when(userPackItemRepository.findByUserIdAndPackIdAndStickerId("u1", "user_upload", "stk_other"))
            .thenReturn(Optional.empty());
        when(userPackItemRepository.maxSortOrder("u1", "user_upload")).thenReturn(0);

        service.addCustomItem("u1", "stk_other");

        ArgumentCaptor<UserStickerPackItem> captor = ArgumentCaptor.forClass(UserStickerPackItem.class);
        verify(userPackItemRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getStickerId()).isEqualTo("stk_other");
    }

    @Test
    void batchGetStickers_splitsMissing() {
        Sticker active = activeSticker("stk_ok", "u1");
        Sticker banned = activeSticker("stk_bad", "u2");
        banned.setStatus(StickerStatus.banned);
        when(stickerRepository.findByStickerIdIn(any())).thenReturn(List.of(active, banned));

        var result = service.batchGetStickers(new StickerService.BatchRequest(
            List.of("stk_ok", "stk_bad", "stk_missing")));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).stickerId()).isEqualTo("stk_ok");
        assertThat(result.missing()).containsExactlyInAnyOrder("stk_bad", "stk_missing");
    }

    private static Sticker activeSticker(String id, String owner) {
        Sticker s = new Sticker();
        s.setStickerId(id);
        s.setOwnerUserId(owner);
        s.setThumbUrl("https://cdn/t.webp");
        s.setOriginUrl("https://cdn/o.gif");
        s.setMediaType(StickerEnums.MediaType.gif);
        s.setStatus(StickerStatus.active);
        return s;
    }
}
