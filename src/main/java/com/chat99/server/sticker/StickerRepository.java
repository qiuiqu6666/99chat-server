package com.chat99.server.sticker;

import com.chat99.server.sticker.StickerEnums.StickerStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StickerRepository extends JpaRepository<Sticker, String> {

    Optional<Sticker> findByStickerIdAndStatus(String stickerId, StickerStatus status);

    List<Sticker> findByStickerIdIn(Collection<String> stickerIds);
}
