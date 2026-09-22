package com.chat99.server.lifepayment;

import com.chat99.server.integration.IntegrationApiProperties;
import com.chat99.server.lifepayment.LifePaymentEnums.WorkerStatus;
import org.springframework.stereotype.Service;

@Service
public class LifePaymentWorkerAuthService {

    private final LifePaymentProperties properties;
    private final IntegrationApiProperties integrationApiProperties;
    private final LifePaymentWorkerDeviceRepository workerRepository;

    public LifePaymentWorkerAuthService(LifePaymentProperties properties,
                                        IntegrationApiProperties integrationApiProperties,
                                        LifePaymentWorkerDeviceRepository workerRepository) {
        this.properties = properties;
        this.integrationApiProperties = integrationApiProperties;
        this.workerRepository = workerRepository;
    }

    public record WorkerAuthContext(boolean globalToken, String boundWorkerId) {
    }

    public WorkerAuthContext authenticate(String authorization) {
        String token = extractBearerToken(authorization);
        String global = resolveGlobalToken();
        if (global != null && !global.isBlank() && global.equals(token)) {
            return new WorkerAuthContext(true, null);
        }
        String hash = LifePaymentWorkerTokenSupport.hashToken(token);
        LifePaymentWorkerDevice device = workerRepository.findByWorkerTokenHash(hash)
            .orElseThrow(() -> LifePaymentExceptions.forbidden("FORBIDDEN"));
        if (device.getStatus() == WorkerStatus.disabled) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
        return new WorkerAuthContext(false, device.getWorkerId());
    }

    public void verifyBearer(String authorization) {
        authenticate(authorization);
    }

    public void assertWorkerBinding(WorkerAuthContext auth, String workerId) {
        if (auth.globalToken() || auth.boundWorkerId() == null) {
            return;
        }
        if (workerId == null || workerId.isBlank() || !auth.boundWorkerId().equals(workerId.trim())) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
        String token = authorization.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (token.isBlank()) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
        return token;
    }

    private String resolveGlobalToken() {
        if (properties.getWorkerToken() != null && !properties.getWorkerToken().isBlank()) {
            return properties.getWorkerToken().trim();
        }
        return integrationApiProperties.apiToken();
    }
}
