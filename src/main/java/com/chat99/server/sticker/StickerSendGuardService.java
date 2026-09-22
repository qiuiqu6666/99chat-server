package com.chat99.server.sticker;

import com.chat99.server.sticker.StickerEnums.StickerStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StickerSendGuardService {

    private static final Logger log = LoggerFactory.getLogger(StickerSendGuardService.class);

    private final StickerRepository stickerRepository;
    private final StickerProperties props;

    public StickerSendGuardService(StickerRepository stickerRepository, StickerProperties props) {
        this.stickerRepository = stickerRepository;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.beforeSendEnabled();
    }

    /**
     * @return reject code such as {@code STICKER_NOT_FOUND} / {@code STICKER_UNAVAILABLE}, or empty if allowed
     */
    public Optional<String> evaluate(Map<String, Object> body) {
        if (!props.beforeSendEnabled()) {
            return Optional.empty();
        }
        List<String> stickerIds = StickerImMessageSupport.extractStickerIds(body);
        if (stickerIds.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> rejectCode = firstRejectCode(stickerIds);
        if (props.beforeSendLogOnly()) {
            log.info("sticker beforeSend stickerIds={} allowed={} enforce={}",
                stickerIds, rejectCode.isEmpty(), props.beforeSendEnforce());
            if (!props.beforeSendEnforce()) {
                return Optional.empty();
            }
        }
        return rejectCode;
    }

    private Optional<String> firstRejectCode(List<String> stickerIds) {
        for (String stickerId : stickerIds) {
            Optional<String> code = validateStickerId(stickerId);
            if (code.isPresent()) {
                return code;
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateStickerId(String stickerId) {
        return stickerRepository.findById(stickerId)
            .map(sticker -> sticker.getStatus() == StickerStatus.active
                ? Optional.<String>empty()
                : Optional.of("STICKER_UNAVAILABLE"))
            .orElse(Optional.of("STICKER_NOT_FOUND"));
    }
}
