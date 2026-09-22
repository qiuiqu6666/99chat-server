package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ActorType;
import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.WorkerStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifePaymentWorkerService {

    private final LifePaymentWorkerDeviceRepository workerRepository;
    private final LifePaymentLogService logService;

    public LifePaymentWorkerService(LifePaymentWorkerDeviceRepository workerRepository,
                                    LifePaymentLogService logService) {
        this.workerRepository = workerRepository;
        this.logService = logService;
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> body) {
        String workerId = requireText(body, "worker_id");
        @SuppressWarnings("unchecked")
        List<String> supportRaw = body.get("support_service_types") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        List<ServiceType> supportTypes = LifePaymentSupport.parseServiceTypes(supportRaw);
        Instant now = Instant.now();

        LifePaymentWorkerDevice worker = workerRepository.findByWorkerId(workerId).orElseGet(() -> {
            LifePaymentWorkerDevice created = new LifePaymentWorkerDevice();
            created.setWorkerId(workerId);
            return created;
        });
        if (worker.getStatus() == WorkerStatus.disabled) {
            throw LifePaymentExceptions.forbidden("FORBIDDEN");
        }
        worker.setDeviceId(asText(body.get("device_id")));
        worker.setDeviceName(asText(body.get("device_name")));
        worker.setSupportServiceTypes(LifePaymentSupport.joinServiceTypes(supportTypes));
        worker.setAppVersion(asText(body.get("app_version")));
        worker.setStatus(WorkerStatus.online);
        worker.setLastOnlineAt(now);
        worker.setLastHeartbeatAt(now);
        workerRepository.save(worker);

        logService.log(ActorType.worker, workerId, null, null, null,
            "worker_register", "worker online", body, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("worker_id", workerId);
        resp.put("status", WorkerStatus.online.name());
        resp.put("server_time", LifePaymentSupport.formatTime(now));
        return resp;
    }

    @Transactional
    public Map<String, Object> offline(Map<String, Object> body) {
        String workerId = requireText(body, "worker_id");
        Instant now = Instant.now();
        LifePaymentWorkerDevice worker = workerRepository.findByWorkerId(workerId)
            .orElseThrow(() -> LifePaymentExceptions.notFound("order_not_found"));
        if (worker.getStatus() != WorkerStatus.disabled) {
            worker.setStatus(WorkerStatus.offline);
        }
        worker.setLastOfflineAt(now);
        worker.setLastHeartbeatAt(now);
        workerRepository.save(worker);
        logService.log(ActorType.worker, workerId, null, null, null,
            "worker_offline", asText(body.get("reason")), body, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("worker_id", workerId);
        resp.put("status", worker.getStatus().name());
        return resp;
    }

    private static String requireText(Map<String, Object> body, String key) {
        String v = asText(body.get(key));
        if (v == null || v.isBlank()) {
            throw LifePaymentExceptions.badRequest("INVALID_INPUT");
        }
        return v;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
