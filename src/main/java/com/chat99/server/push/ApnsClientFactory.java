package com.chat99.server.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApnsClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ApnsClientFactory.class);

    private final PushProperties props;
    private volatile ApnsClient productionClient;
    private volatile ApnsClient developmentClient;
    private volatile ApnsClient voipClient;

    public ApnsClientFactory(PushProperties props) {
        this.props = props;
    }

    public boolean isReady() {
        PushProperties.Apns apns = props.apns();
        if (!props.enabled() || !apns.enabled()) {
            return false;
        }
        if (apns.bundleId() == null || apns.bundleId().isBlank()) {
            return false;
        }
        if (apns.usesP12()) {
            return apns.p12Path() != null && !apns.p12Path().isBlank();
        }
        return apns.teamId() != null && !apns.teamId().isBlank()
            && apns.keyId() != null && !apns.keyId().isBlank()
            && apns.p8Key() != null && !apns.p8Key().isBlank();
    }

    public boolean isVoipReady() {
        PushProperties.Apns apns = props.apns();
        if (!isReady()) {
            return false;
        }
        if (apns.usesP12()) {
            String voipPath = apns.voipP12Path();
            return voipPath != null && !voipPath.isBlank();
        }
        return true;
    }

    public ApnsClient getClient() {
        return getClient(props.apns().production());
    }

    /** Live Activity token 可能来自 sandbox，按上报 environment 选主机。 */
    public ApnsClient getClient(boolean production) {
        if (!isReady()) {
            throw new IllegalStateException("APNs alert client not configured");
        }
        ApnsClient existing = production ? productionClient : developmentClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = production ? productionClient : developmentClient;
            if (existing != null) {
                return existing;
            }
            ApnsClient created = buildAlertClient(production);
            if (production) {
                productionClient = created;
            } else {
                developmentClient = created;
            }
            return created;
        }
    }

    public ApnsClient getVoipClient() {
        if (!isVoipReady()) {
            throw new IllegalStateException("APNs VoIP client not configured");
        }
        PushProperties.Apns apns = props.apns();
        if (!apns.usesP12()) {
            return getClient();
        }
        ApnsClient existing = voipClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (voipClient != null) {
                return voipClient;
            }
            voipClient = buildVoipClient();
            return voipClient;
        }
    }

    private ApnsClient buildAlertClient(boolean production) {
        PushProperties.Apns apns = props.apns();
        if (apns.usesP12()) {
            return buildP12Client(apns.p12Path(), apns.p12Password(), "alert", production);
        }
        return buildP8Client(apns, production);
    }

    private ApnsClient buildVoipClient() {
        PushProperties.Apns apns = props.apns();
        return buildP12Client(apns.voipP12Path(), apns.voipP12Password(), "voip", apns.production());
    }

    private ApnsClient buildP8Client(PushProperties.Apns apns, boolean production) {
        try {
            String pem = apns.p8Key().replace("\\n", "\n");
            ApnsSigningKey signingKey = ApnsSigningKey.loadFromInputStream(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)),
                apns.teamId(),
                apns.keyId());
            ApnsClientBuilder builder = new ApnsClientBuilder().setSigningKey(signingKey);
            applyServerHost(builder, production);
            log.info("APNs p8 client initialized bundleId={} production={}",
                apns.bundleId(), production);
            return builder.build();
        } catch (IOException | java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Failed to initialize APNs p8 client", e);
        }
    }

    private ApnsClient buildP12Client(String path, String password, String label, boolean production) {
        PushProperties.Apns apns = props.apns();
        try (InputStream in = PushCredentialFiles.openRequired(path)) {
            ApnsClientBuilder builder = new ApnsClientBuilder()
                .setClientCredentials(in, password == null ? "" : password);
            applyServerHost(builder, production);
            log.info("APNs p12 client initialized type={} path={} bundleId={} production={}",
                label, path, apns.bundleId(), production);
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize APNs p12 client type=" + label, e);
        }
    }

    private static void applyServerHost(ApnsClientBuilder builder, boolean production) {
        if (production) {
            builder.setApnsServer(ApnsClientBuilder.PRODUCTION_APNS_HOST);
        } else {
            builder.setApnsServer(ApnsClientBuilder.DEVELOPMENT_APNS_HOST);
        }
    }
}
