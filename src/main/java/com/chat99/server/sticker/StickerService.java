package com.chat99.server.sticker;

import com.chat99.server.sticker.StickerEnums.MediaType;
import com.chat99.server.sticker.StickerEnums.PackSource;
import com.chat99.server.sticker.StickerEnums.StickerStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StickerService {

    public record StickerItemView(
        String stickerId,
        String thumbUrl,
        String originUrl,
        String mediaType,
        Integer width,
        Integer height,
        int sortOrder) {}

    public record StickerPackView(
        String packId,
        String name,
        String iconUrl,
        String source,
        int sortOrder,
        boolean removable,
        List<StickerItemView> stickers) {}

    public record FavoriteItemView(
        String stickerId,
        String thumbUrl,
        String originUrl,
        String mediaType,
        Integer width,
        Integer height,
        Instant favoritedAt) {}

    public record PackOrderRequest(List<String> packIds) {}

    public record FavoriteRequest(String stickerId) {}

    public record CustomItemRequest(String stickerId) {}

    public record BatchRequest(List<String> stickerIds) {}

    public record BatchResponse(List<StickerItemView> items, List<String> missing) {}

    private final StickerRepository stickerRepository;
    private final StickerPackRepository packRepository;
    private final StickerPackItemRepository packItemRepository;
    private final UserStickerPackRepository userPackRepository;
    private final UserStickerPackItemRepository userPackItemRepository;
    private final UserStickerFavoriteRepository favoriteRepository;
    private final StickerUploadService uploadService;
    private final StickerUserInitializer userInitializer;
    private final StickerProperties props;

    public StickerService(StickerRepository stickerRepository,
                          StickerPackRepository packRepository,
                          StickerPackItemRepository packItemRepository,
                          UserStickerPackRepository userPackRepository,
                          UserStickerPackItemRepository userPackItemRepository,
                          UserStickerFavoriteRepository favoriteRepository,
                          StickerUploadService uploadService,
                          StickerUserInitializer userInitializer,
                          StickerProperties props) {
        this.stickerRepository = stickerRepository;
        this.packRepository = packRepository;
        this.packItemRepository = packItemRepository;
        this.userPackRepository = userPackRepository;
        this.userPackItemRepository = userPackItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.uploadService = uploadService;
        this.userInitializer = userInitializer;
        this.props = props;
    }

    @Transactional
    public List<StickerPackView> listMyPacks(String userId) {
        userInitializer.ensureInstalled(userId);
        List<UserStickerPack> installed = userPackRepository.findByUserIdOrderBySortOrderAsc(userId);
        List<StickerPackView> out = new ArrayList<>();
        for (UserStickerPack up : installed) {
            if (props.favoritesPackId().equals(up.getPackId())) {
                continue;
            }
            StickerPack pack = packRepository.findById(up.getPackId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PACK_MISSING"));
            List<StickerItemView> stickers = resolvePackStickers(userId, pack);
            out.add(new StickerPackView(
                pack.getPackId(),
                pack.getName(),
                pack.getIconUrl(),
                pack.getSource().name(),
                up.getSortOrder(),
                pack.isRemovable(),
                stickers));
        }
        out.add(buildFavoritesPackView(userId));
        out.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        return out;
    }

    @Transactional
    public void updatePackOrder(String userId, PackOrderRequest req) {
        userInitializer.ensureInstalled(userId);
        if (req.packIds() == null || req.packIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<UserStickerPack> installed = userPackRepository.findByUserIdOrderBySortOrderAsc(userId);
        Map<String, UserStickerPack> byPack = new HashMap<>();
        for (UserStickerPack up : installed) {
            byPack.put(up.getPackId(), up);
        }
        List<String> ordered = req.packIds().stream()
            .filter(id -> id != null && !id.isBlank())
            .filter(id -> !props.favoritesPackId().equals(id))
            .toList();
        if (ordered.size() != byPack.size() || !byPack.keySet().containsAll(ordered)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PACK_ORDER");
        }
        int order = 0;
        for (String packId : ordered) {
            UserStickerPack row = byPack.get(packId);
            row.setSortOrder(order++);
            userPackRepository.save(row);
        }
    }

    @Transactional
    public void removePack(String userId, String packId) {
        StickerPack pack = packRepository.findById(packId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND"));
        if (!pack.isRemovable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PACK_NOT_REMOVABLE");
        }
        if (!userPackRepository.existsByUserIdAndPackId(userId, packId)) {
            return;
        }
        userPackRepository.deleteByUserIdAndPackId(userId, packId);
    }

    @Transactional(readOnly = true)
    public List<FavoriteItemView> listFavorites(String userId) {
        return favoriteRepository.findByUserIdOrderByFavoritedAtDesc(userId).stream()
            .map(fav -> {
                Sticker s = stickerRepository.findById(fav.getStickerId())
                    .filter(st -> st.getStatus() == StickerStatus.active)
                    .orElse(null);
                if (s == null) {
                    return null;
                }
                return new FavoriteItemView(
                    s.getStickerId(), s.getThumbUrl(), s.getOriginUrl(),
                    s.getMediaType().name(), s.getWidth(), s.getHeight(), fav.getFavoritedAt());
            })
            .filter(v -> v != null)
            .toList();
    }

    @Transactional
    public void addFavorite(String userId, String stickerId) {
        requireActiveSticker(stickerId);
        if (favoriteRepository.findByUserIdAndStickerId(userId, stickerId).isPresent()) {
            return;
        }
        UserStickerFavorite fav = new UserStickerFavorite();
        fav.setUserId(userId);
        fav.setStickerId(stickerId);
        favoriteRepository.save(fav);
    }

    @Transactional
    public void removeFavorite(String userId, String stickerId) {
        favoriteRepository.deleteByUserIdAndStickerId(userId, stickerId);
    }

    @Transactional
    public StickerItemView upload(String userId, MultipartFile file, String mediaTypeHint) throws IOException {
        userInitializer.ensureInstalled(userId);
        String stickerId = newStickerId();
        StickerUploadService.UploadResult uploaded = uploadService.upload(stickerId, file, mediaTypeHint);
        Sticker sticker = new Sticker();
        sticker.setStickerId(stickerId);
        sticker.setOwnerUserId(userId);
        sticker.setThumbUrl(uploaded.thumbUrl());
        sticker.setOriginUrl(uploaded.originUrl());
        sticker.setMediaType(uploaded.mediaType());
        sticker.setWidth(uploaded.width());
        sticker.setHeight(uploaded.height());
        sticker.setStatus(StickerStatus.active);
        stickerRepository.save(sticker);

        int sortOrder = addToUserUploadPack(userId, sticker.getStickerId());
        return toItemView(sticker, sortOrder);
    }

    @Transactional
    public StickerItemView addCustomItem(String userId, String stickerId) {
        userInitializer.ensureInstalled(userId);
        Sticker sticker = requireActiveSticker(stickerId);
        int sortOrder = addToUserUploadPack(userId, sticker.getStickerId());
        return toItemView(sticker, sortOrder);
    }

    @Transactional
    public void removeCustomItem(String userId, String stickerId) {
        if (!stickerRepository.existsById(stickerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "STICKER_NOT_FOUND");
        }
        userPackItemRepository.deleteByUserIdAndPackIdAndStickerId(
            userId, props.userUploadPackId(), stickerId);
    }

    @Transactional(readOnly = true)
    public StickerItemView getSticker(String stickerId) {
        Sticker sticker = stickerRepository.findById(stickerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STICKER_NOT_FOUND"));
        if (sticker.getStatus() != StickerStatus.active) {
            throw new ResponseStatusException(HttpStatus.GONE, "STICKER_UNAVAILABLE");
        }
        int sortOrder = userPackItemRepository
            .findByUserIdAndPackIdAndStickerId(sticker.getOwnerUserId(), props.userUploadPackId(), stickerId)
            .map(UserStickerPackItem::getSortOrder)
            .orElse(0);
        return toItemView(sticker, sortOrder);
    }

    @Transactional(readOnly = true)
    public BatchResponse batchGetStickers(BatchRequest req) {
        if (req.stickerIds() == null || req.stickerIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<String> unique = req.stickerIds().stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .limit(props.batchMaxSize())
            .toList();
        if (unique.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        Map<String, Sticker> found = new LinkedHashMap<>();
        for (Sticker s : stickerRepository.findByStickerIdIn(unique)) {
            found.put(s.getStickerId(), s);
        }
        List<StickerItemView> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : unique) {
            Sticker s = found.get(id);
            if (s == null || s.getStatus() != StickerStatus.active) {
                missing.add(id);
            } else {
                items.add(toItemView(s, 0));
            }
        }
        return new BatchResponse(items, missing);
    }

    private StickerPackView buildFavoritesPackView(String userId) {
        List<StickerItemView> stickers = listFavorites(userId).stream()
            .map(f -> new StickerItemView(
                f.stickerId(), f.thumbUrl(), f.originUrl(), f.mediaType(),
                f.width(), f.height(), 0))
            .toList();
        return new StickerPackView(
            props.favoritesPackId(),
            props.favoritesPackName(),
            "",
            PackSource.custom.name(),
            0,
            false,
            stickers);
    }

    private List<StickerItemView> resolvePackStickers(String userId, StickerPack pack) {
        if (pack.getSource() == PackSource.system) {
            return List.of();
        }
        if (props.userUploadPackId().equals(pack.getPackId())) {
            return userPackItemRepository
                .findByUserIdAndPackIdOrderBySortOrderAsc(userId, props.userUploadPackId())
                .stream()
                .map(item -> stickerRepository.findById(item.getStickerId())
                    .filter(s -> s.getStatus() == StickerStatus.active)
                    .map(s -> toItemView(s, item.getSortOrder()))
                    .orElse(null))
                .filter(v -> v != null)
                .toList();
        }
        return packItemRepository.findByPackIdOrderBySortOrderAsc(pack.getPackId()).stream()
            .map(item -> stickerRepository.findById(item.getStickerId())
                .filter(s -> s.getStatus() == StickerStatus.active)
                .map(s -> toItemView(s, item.getSortOrder()))
                .orElse(null))
            .filter(v -> v != null)
            .toList();
    }

    private int addToUserUploadPack(String userId, String stickerId) {
        var existing = userPackItemRepository.findByUserIdAndPackIdAndStickerId(
            userId, props.userUploadPackId(), stickerId);
        if (existing.isPresent()) {
            return existing.get().getSortOrder();
        }
        int next = userPackItemRepository.maxSortOrder(userId, props.userUploadPackId()) + 1;
        UserStickerPackItem item = new UserStickerPackItem();
        item.setUserId(userId);
        item.setPackId(props.userUploadPackId());
        item.setStickerId(stickerId);
        item.setSortOrder(next);
        userPackItemRepository.save(item);
        return next;
    }

    private Sticker requireActiveSticker(String stickerId) {
        return stickerRepository.findByStickerIdAndStatus(stickerId, StickerStatus.active)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STICKER_NOT_FOUND"));
    }

    private static String newStickerId() {
        return "stk_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static StickerItemView toItemView(Sticker s, int sortOrder) {
        return new StickerItemView(
            s.getStickerId(),
            s.getThumbUrl(),
            s.getOriginUrl(),
            s.getMediaType().name(),
            s.getWidth(),
            s.getHeight(),
            sortOrder);
    }
}
