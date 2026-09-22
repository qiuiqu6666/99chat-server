package com.chat99.server.im;

import com.chat99.server.common.AppSettingService;
import com.tencentyun.TLSSigAPIv2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserSigService {

    private static final Logger log = LoggerFactory.getLogger(UserSigService.class);

    private final ImProperties props;
    private final int sdkAppId;
    private final TLSSigAPIv2 api;

    public UserSigService(AppSettingService settings, ImProperties props) {
        this.props = props;
        this.sdkAppId = settings.getInt(AppSettingService.IM_SDK_APP_ID, 0);
        String key = settings.get(AppSettingService.IM_KEY).orElse(null);
        if (sdkAppId == 0 || key == null) {
            log.warn("app_setting IM_SDK_APP_ID / IM_KEY not configured; UserSig signing disabled");
            this.api = null;
        } else {
            this.api = new TLSSigAPIv2(sdkAppId, key);
        }
    }

    public String sign(String userId) {
        if (api == null) {
            throw new IllegalStateException("IM not configured");
        }
        return api.genUserSig(userId, props.userSigExpireSeconds());
    }

    public int expireSeconds() {
        return props.userSigExpireSeconds();
    }

    public int sdkAppId() {
        return sdkAppId;
    }
}
