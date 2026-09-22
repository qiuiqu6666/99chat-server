package com.chat99.server.sticker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StickerPackBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StickerPackBootstrap.class);

    private final StickerPackRepository packRepository;
    private final StickerProperties props;

    public StickerPackBootstrap(StickerPackRepository packRepository, StickerProperties props) {
        this.packRepository = packRepository;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (StickerProperties.SystemPack sp : props.systemPacks()) {
            ensurePack(sp.packId(), sp.name(), sp.iconUrl(), StickerEnums.PackSource.system, false, sp.sortOrder());
        }
        ensurePack(props.userUploadPackId(), props.userUploadName(), props.userUploadIconUrl(),
            StickerEnums.PackSource.custom, false, 10);
        log.info("sticker packs bootstrap ok");
    }

    private void ensurePack(String packId, String name, String iconUrl, StickerEnums.PackSource source,
                            boolean removable, int sortOrder) {
        if (packRepository.existsById(packId)) {
            return;
        }
        StickerPack pack = new StickerPack();
        pack.setPackId(packId);
        pack.setName(name);
        pack.setIconUrl(iconUrl);
        pack.setSource(source);
        pack.setRemovable(removable);
        pack.setDefaultSortOrder(sortOrder);
        packRepository.save(pack);
    }
}
