package com.chat99.server.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teautil.models.RuntimeOptions;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.common.PhoneUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AliyunPnvsSmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunPnvsSmsClient.class);
    private static final String ENDPOINT = "dypnsapi.aliyuncs.com";
    /** 号码认证赠送模板：文案含 ${code} 与 ${min}，禁止使用 SMS_* 短信服务模板。 */
    static final String REQUIRED_TEMPLATE_CODE = "100001";

    private final SmsProperties props;
    private final AppSettingService settings;
    private final PhoneUtils phoneUtils;
    private final ObjectMapper json = new ObjectMapper();

    public AliyunPnvsSmsClient(SmsProperties props, AppSettingService settings, PhoneUtils phoneUtils) {
        this.props = props;
        this.settings = settings;
        this.phoneUtils = phoneUtils;
    }

    @PostConstruct
    void logReady() {
        if (!configured()) {
            log.warn("aliyun pnvs not configured (sign/template/ak missing)");
            return;
        }
        SmsProperties.AliyunPnvs pnvs = props.aliyunPnvs();
        log.info("aliyun pnvs ready sign={} template={} akPrefix={}",
            pnvs.signName(), pnvs.templateCode(), akPrefix(resolveAccessKeyId(pnvs).orElse("")));
        if (!isAllowedTemplate(pnvs.templateCode())) {
            log.error("aliyun pnvs template must be {} but configured as {}",
                REQUIRED_TEMPLATE_CODE, pnvs.templateCode());
        }
    }

    public boolean configured() {
        SmsProperties.AliyunPnvs pnvs = props.aliyunPnvs();
        return pnvs != null
            && pnvs.signName() != null && !pnvs.signName().isBlank()
            && pnvs.templateCode() != null && !pnvs.templateCode().isBlank()
            && resolveAccessKeyId(pnvs).isPresent()
            && resolveAccessKeySecret(pnvs).isPresent();
    }

    /** 阿里云动态生成验证码（##code##），校验走 CheckSmsVerifyCode。 */
    public void sendDynamicVerifyCode(String phoneE164) {
        SmsProperties.AliyunPnvs pnvs = requirePnvs();
        String templateParam = buildTemplateParam("##code##", pnvs.templateMinutes());
        SendSmsVerifyCodeRequest request = baseSendRequest(phoneE164, pnvs)
            .setTemplateParam(templateParam)
            .setCodeType(pnvs.codeType())
            .setCodeLength(pnvs.codeLength())
            .setValidTime(pnvs.validTimeSeconds())
            .setReturnVerifyCode(false);
        send(request, phoneE164, "dynamic");
    }

    /** 下发指定验证码（设备验证等场景），校验仍走本地 Redis。 */
    public void sendExplicitVerifyCode(String phoneE164, String code) {
        SmsProperties.AliyunPnvs pnvs = requirePnvs();
        SendSmsVerifyCodeRequest request = baseSendRequest(phoneE164, pnvs)
            .setTemplateParam(buildTemplateParam(code, pnvs.templateMinutes()))
            .setReturnVerifyCode(false);
        send(request, phoneE164, "explicit");
    }

    public boolean checkVerifyCode(String phoneE164, String verifyCode) {
        if (props.devMode()) {
            log.info("[SMS-DEV] aliyun check phone={} code={}", phoneUtils.mask(phoneE164), verifyCode);
            return true;
        }
        requirePnvs();
        try {
            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                .setPhoneNumber(domesticNumber(phoneE164))
                .setVerifyCode(verifyCode)
                .setCountryCode("86");
            String scheme = props.aliyunPnvs().schemeName();
            if (scheme != null && !scheme.isBlank()) {
                request.setSchemeName(scheme.trim());
            }
            CheckSmsVerifyCodeResponse response = client().checkSmsVerifyCodeWithOptions(
                request, new RuntimeOptions());
            if (response == null || response.getBody() == null) {
                return false;
            }
            if (Boolean.TRUE.equals(response.getBody().getSuccess())
                && "OK".equalsIgnoreCase(response.getBody().getCode())) {
                return true;
            }
            String errCode = response.getBody().getCode();
            String errMessage = response.getBody().getMessage();
            log.warn("aliyun check failed phone={} code={} message={}",
                phoneUtils.mask(phoneE164), errCode, errMessage);
            // 业务校验失败（错码/过期等）→ false，由上层映射 SMS_CODE_INVALID
            return false;
        } catch (Exception e) {
            String err = e.getMessage();
            log.warn("aliyun check error phone={} err={}", phoneUtils.mask(phoneE164), err);
            // 阿里云 SDK 常对「验证失败」抛 TeaException，不能当成通道 502
            if (isVerifyCodeBusinessFailure(null, err)) {
                return false;
            }
            throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_FAILED");
        }
    }

    /**
     * 判断阿里云 CheckSmsVerifyCode 是否属于「验证码业务失败」（应映射 SMS_CODE_INVALID），
     * 而非通道/鉴权/网络故障（应映射 SMS_PROVIDER_*）。
     * <p>白名单宜窄：未知失败偏保守按通道错误处理。
     */
    static boolean isVerifyCodeBusinessFailure(String code, String message) {
        String c = code == null ? "" : code.trim();
        String m = message == null ? "" : message.trim();
        if (c.isEmpty() && m.isEmpty()) {
            return false;
        }
        // 现网实测：TeaException message = "code: 400, 验证失败 request id: ..."
        if (m.contains("验证失败") || m.contains("校验失败") || m.contains("验证码错误")
            || m.contains("验证码无效") || m.contains("验证码过期") || m.contains("验证码已过期")
            || m.contains("已过期")) {
            return true;
        }
        String cl = c.toLowerCase();
        String ml = m.toLowerCase();
        // 仅匹配验证码语义，避免 InvalidAccessKeyId 等鉴权错误被误吞
        if (ml.contains("verify code") || ml.contains("verification code")
            || ml.contains("sms verify") || ml.contains("invalid verify")) {
            return true;
        }
        if ("FAIL".equalsIgnoreCase(c)
            || "SMS_VERIFY_FAILED".equalsIgnoreCase(c)
            || "VERIFY_CODE_ERROR".equalsIgnoreCase(c)
            || "VERIFY_CODE_EXPIRED".equalsIgnoreCase(c)
            || "INVALID_VERIFY_CODE".equalsIgnoreCase(c)
            || cl.equals("verifycode.fail")
            || cl.equals("verify_fail")) {
            return true;
        }
        return false;
    }

    static boolean isAllowedTemplate(String templateCode) {
        return REQUIRED_TEMPLATE_CODE.equals(templateCode == null ? "" : templateCode.trim());
    }

    /** 供单测：100001 模板参数必须含 code 与 min。 */
    static String buildTemplateParamJson(String code, int minutes, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(Map.of("code", code, "min", String.valueOf(minutes)));
        } catch (Exception e) {
            throw new IllegalStateException("template param build failed", e);
        }
    }

    private SendSmsVerifyCodeRequest baseSendRequest(String phoneE164, SmsProperties.AliyunPnvs pnvs) {
        SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
            .setPhoneNumber(domesticNumber(phoneE164))
            .setCountryCode("86")
            .setSignName(pnvs.signName())
            .setTemplateCode(pnvs.templateCode());
        if (pnvs.schemeName() != null && !pnvs.schemeName().isBlank()) {
            request.setSchemeName(pnvs.schemeName().trim());
        }
        return request;
    }

    private void send(SendSmsVerifyCodeRequest request, String phoneE164, String mode) {
        if (props.devMode()) {
            log.info("[SMS-DEV] aliyun pnvs send mode={} phone={} sign={} template={} param={}",
                mode, phoneUtils.mask(phoneE164), request.getSignName(),
                request.getTemplateCode(), request.getTemplateParam());
            return;
        }
        String akPrefix = akPrefix(resolveAccessKeyId(props.aliyunPnvs()).orElse(""));
        try {
            SendSmsVerifyCodeResponse response = client().sendSmsVerifyCodeWithOptions(
                request, new RuntimeOptions());
            if (response == null || response.getBody() == null) {
                throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_FAILED");
            }
            if (!Boolean.TRUE.equals(response.getBody().getSuccess())
                || !"OK".equalsIgnoreCase(response.getBody().getCode())) {
                String errCode = response.getBody().getCode();
                String errMessage = response.getBody().getMessage();
                log.warn("aliyun send failed phone={} mode={} sign={} template={} akPrefix={} code={} message={}",
                    phoneUtils.mask(phoneE164), mode, request.getSignName(), request.getTemplateCode(),
                    akPrefix, errCode, errMessage);
                throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_FAILED:" + errCode);
            }
            log.info("aliyun pnvs send ok phone={} mode={} template={}",
                phoneUtils.mask(phoneE164), mode, request.getTemplateCode());
        } catch (SmsRateLimiter.RateLimitedException | SmsbaoClient.SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.warn("aliyun send error phone={} mode={} sign={} template={} akPrefix={} err={}",
                phoneUtils.mask(phoneE164), mode, request.getSignName(), request.getTemplateCode(),
                akPrefix, e.getMessage());
            throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_IO");
        }
    }

    private Client client() throws Exception {
        SmsProperties.AliyunPnvs pnvs = requirePnvs();
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setAccessKeyId(resolveAccessKeyId(pnvs).orElseThrow())
            .setAccessKeySecret(resolveAccessKeySecret(pnvs).orElseThrow());
        config.endpoint = ENDPOINT;
        return new Client(config);
    }

    private Optional<String> resolveAccessKeyId(SmsProperties.AliyunPnvs pnvs) {
        if (pnvs.accessKeyId() != null && !pnvs.accessKeyId().isBlank()) {
            return Optional.of(pnvs.accessKeyId().trim());
        }
        return settings.get(AppSettingService.OSS_ACCESS_KEY_ID);
    }

    private Optional<String> resolveAccessKeySecret(SmsProperties.AliyunPnvs pnvs) {
        if (pnvs.accessKeySecret() != null && !pnvs.accessKeySecret().isBlank()) {
            return Optional.of(pnvs.accessKeySecret().trim());
        }
        return settings.get(AppSettingService.OSS_ACCESS_KEY_SECRET);
    }

    private SmsProperties.AliyunPnvs requirePnvs() {
        if (!configured()) {
            throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_NOT_CONFIGURED");
        }
        SmsProperties.AliyunPnvs pnvs = props.aliyunPnvs();
        if (!isAllowedTemplate(pnvs.templateCode())) {
            log.error("aliyun pnvs rejected template={} required={}",
                pnvs.templateCode(), REQUIRED_TEMPLATE_CODE);
            throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_FAILED:TEMPLATE_NOT_ALLOWED");
        }
        return pnvs;
    }

    private String domesticNumber(String phoneE164) {
        PhoneUtils.Parsed parsed = phoneUtils.parseE164(phoneE164);
        if (!phoneUtils.isDomestic(parsed.countryCode())) {
            throw new IllegalArgumentException("not domestic phone");
        }
        return phoneE164.substring(1 + parsed.countryCode().length());
    }

    private String buildTemplateParam(String code, int minutes) {
        return buildTemplateParamJson(code, minutes, json);
    }

    private static String akPrefix(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return "-";
        }
        return accessKeyId.length() <= 8 ? accessKeyId : accessKeyId.substring(0, 8);
    }
}
