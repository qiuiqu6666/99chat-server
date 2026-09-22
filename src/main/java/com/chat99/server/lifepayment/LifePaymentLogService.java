package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifePaymentLogService {

    private final LifePaymentOperationLogRepository logRepository;

    public LifePaymentLogService(LifePaymentOperationLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Transactional
    public void log(ActorType actorType, String actorId, ServiceType serviceType,
                    String orderNo, String taskNo, String action, String message,
                    Object request, Object response) {
        LifePaymentOperationLog log = new LifePaymentOperationLog();
        log.setActorType(actorType);
        log.setActorId(actorId);
        log.setServiceType(serviceType);
        log.setOrderNo(orderNo);
        log.setTaskNo(taskNo);
        log.setAction(action);
        log.setMessage(message);
        if (request != null) {
            log.setRequestJson(LifePaymentSupport.toJson(request));
        }
        if (response != null) {
            log.setResponseJson(LifePaymentSupport.toJson(response));
        }
        logRepository.save(log);
    }
}
