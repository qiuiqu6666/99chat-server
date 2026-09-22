package com.chat99.server.sms;

import com.chat99.server.common.PhoneUtils;
import org.springframework.stereotype.Service;

@Service
public class SmsDeliveryService {

    private final SmsProperties props;
    private final SmsCodeStore codeStore;
    private final SmsbaoClient smsbao;
    private final NodeSmsClient nodeSms;
    private final AliyunPnvsSmsClient aliyunPnvs;
    private final PhoneUtils phoneUtils;

    public SmsDeliveryService(SmsProperties props,
                              SmsCodeStore codeStore,
                              SmsbaoClient smsbao,
                              NodeSmsClient nodeSms,
                              AliyunPnvsSmsClient aliyunPnvs,
                              PhoneUtils phoneUtils) {
        this.props = props;
        this.codeStore = codeStore;
        this.smsbao = smsbao;
        this.nodeSms = nodeSms;
        this.aliyunPnvs = aliyunPnvs;
        this.phoneUtils = phoneUtils;
    }

    /**
     * 按场景发送验证码。
     *
     * @param explicitCode 非空时为指定验证码（设备验证）；国内走阿里云固定码，国际走 NodeSMS + Redis。
     */
    public void sendForScene(SmsScene scene, String phoneE164, String countryCode, String explicitCode) {
        if (explicitCode != null && !explicitCode.isBlank()) {
            sendVerificationCode(phoneE164, countryCode, explicitCode);
            return;
        }
        if (useAliyunDomestic(countryCode)) {
            aliyunPnvs.sendDynamicVerifyCode(phoneE164);
            return;
        }
        String code = codeStore.generate();
        codeStore.store(scene, phoneE164, code);
        sendNonAliyun(phoneE164, countryCode, buildContent(countryCode, code));
    }

    public void sendVerificationCode(String phoneE164, String countryCode, String explicitCode) {
        if (useAliyunDomestic(countryCode)) {
            if (explicitCode != null && !explicitCode.isBlank()) {
                aliyunPnvs.sendExplicitVerifyCode(phoneE164, explicitCode);
            } else {
                aliyunPnvs.sendDynamicVerifyCode(phoneE164);
            }
            return;
        }
        if (explicitCode == null || explicitCode.isBlank()) {
            throw new IllegalStateException("international SMS requires explicit code");
        }
        sendNonAliyun(phoneE164, countryCode, buildContent(countryCode, explicitCode));
    }

    private void sendNonAliyun(String phoneE164, String countryCode, String content) {
        if (phoneUtils.isDomestic(countryCode)) {
            smsbao.send(phoneE164, countryCode, content);
            return;
        }
        nodeSms.send(phoneE164, countryCode, content);
    }

    public boolean usesAliyunDomestic(String countryCode) {
        return useAliyunDomestic(countryCode);
    }

    private boolean useAliyunDomestic(String countryCode) {
        return phoneUtils.isDomestic(countryCode) && aliyunPnvs.configured();
    }

    private String buildContent(String countryCode, String code) {
        int minutes = props.aliyunPnvs() != null ? props.aliyunPnvs().templateMinutes() : 5;
        if ("86".equals(countryCode)) {
            return "亲爱的用户 你的验证码是" + code + "有效期为" + minutes + "分钟请尽快验证";
        }
        return "[99chat]Your verification code is: " + code;
    }
}
