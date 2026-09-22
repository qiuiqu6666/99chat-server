package com.chat99.server.sms;

import com.chat99.server.common.PhoneUtils;
import org.springframework.stereotype.Service;

@Service
public class SmsVerificationService {

    private final SmsCodeStore codeStore;
    private final SmsDeliveryService deliveryService;
    private final AliyunPnvsSmsClient aliyunPnvs;
    private final PhoneUtils phoneUtils;

    public SmsVerificationService(SmsCodeStore codeStore,
                                  SmsDeliveryService deliveryService,
                                  AliyunPnvsSmsClient aliyunPnvs,
                                  PhoneUtils phoneUtils) {
        this.codeStore = codeStore;
        this.deliveryService = deliveryService;
        this.aliyunPnvs = aliyunPnvs;
        this.phoneUtils = phoneUtils;
    }

    public boolean verifyAndConsume(SmsScene scene, String phoneE164, String countryCode, String code) {
        if (codeStore.isMasterCode(code)) {
            codeStore.clearIfPresent(scene, phoneE164);
            return true;
        }
        if (shouldVerifyViaAliyun(countryCode)) {
            return aliyunPnvs.checkVerifyCode(phoneE164, code);
        }
        return codeStore.verifyAndConsume(scene, phoneE164, code);
    }

    public boolean matchesCode(String expected, String submitted) {
        return codeStore.matchesCode(expected, submitted);
    }

    private boolean shouldVerifyViaAliyun(String countryCode) {
        return phoneUtils.isDomestic(countryCode) && deliveryService.usesAliyunDomestic(countryCode);
    }
}
