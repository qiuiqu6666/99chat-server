package com.chat99.server.sticker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StickerUserInitializer {

    private final UserStickerPackRepository userPackRepository;
    private final StickerProperties props;

    public StickerUserInitializer(UserStickerPackRepository userPackRepository, StickerProperties props) {
        this.userPackRepository = userPackRepository;
        this.props = props;
    }

    @Transactional
    public void ensureInstalled(String userId) {
        int order = 0;
        for (StickerProperties.SystemPack sp : props.systemPacks()) {
            ensureUserPack(userId, sp.packId(), order++);
        }
        ensureUserPack(userId, props.userUploadPackId(), order);
    }

    private void ensureUserPack(String userId, String packId, int sortOrder) {
        if (userPackRepository.existsByUserIdAndPackId(userId, packId)) {
            return;
        }
        UserStickerPack row = new UserStickerPack();
        row.setUserId(userId);
        row.setPackId(packId);
        row.setSortOrder(sortOrder);
        userPackRepository.save(row);
    }
}
