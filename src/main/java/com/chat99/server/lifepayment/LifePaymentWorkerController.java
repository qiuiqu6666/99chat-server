package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentWorkerAuthService.WorkerAuthContext;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/life-payments")
public class LifePaymentWorkerController {

    private final LifePaymentWorkerAuthService authService;
    private final LifePaymentTaskService taskService;
    private final LifePaymentWorkerService workerService;

    public LifePaymentWorkerController(LifePaymentWorkerAuthService authService,
                                       LifePaymentTaskService taskService,
                                       LifePaymentWorkerService workerService) {
        this.authService = authService;
        this.taskService = taskService;
        this.workerService = workerService;
    }

    @PostMapping("/tasks/claim")
    public Map<String, Object> claim(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Worker-Id", required = false) String workerHeader,
        @RequestBody Map<String, Object> body) {
        WorkerAuthContext auth = authService.authenticate(authorization);
        fillWorkerId(body, workerHeader);
        authService.assertWorkerBinding(auth, String.valueOf(body.get("worker_id")));
        @SuppressWarnings("unchecked")
        List<String> support = body.get("support_service_types") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        return taskService.claim(String.valueOf(body.get("worker_id")), support);
    }

    @PostMapping("/tasks/{task_no}/heartbeat")
    public Map<String, Object> heartbeat(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Worker-Id", required = false) String workerHeader,
        @PathVariable("task_no") String taskNo,
        @RequestBody Map<String, Object> body) {
        WorkerAuthContext auth = authService.authenticate(authorization);
        fillWorkerId(body, workerHeader);
        authService.assertWorkerBinding(auth, String.valueOf(body.get("worker_id")));
        return taskService.heartbeat(taskNo, body);
    }

    @PostMapping("/tasks/{task_no}/complete")
    public Map<String, Object> complete(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Worker-Id", required = false) String workerHeader,
        @PathVariable("task_no") String taskNo,
        @RequestBody Map<String, Object> body) {
        WorkerAuthContext auth = authService.authenticate(authorization);
        fillWorkerId(body, workerHeader);
        authService.assertWorkerBinding(auth, String.valueOf(body.get("worker_id")));
        return taskService.complete(taskNo, body);
    }

    @PostMapping("/tasks/{task_no}/fail")
    public Map<String, Object> fail(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Worker-Id", required = false) String workerHeader,
        @PathVariable("task_no") String taskNo,
        @RequestBody Map<String, Object> body) {
        WorkerAuthContext auth = authService.authenticate(authorization);
        fillWorkerId(body, workerHeader);
        authService.assertWorkerBinding(auth, String.valueOf(body.get("worker_id")));
        return taskService.fail(taskNo, body);
    }

    @PostMapping("/workers/register")
    public Map<String, Object> register(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body) {
        WorkerAuthContext auth = authService.authenticate(authorization);
        authService.assertWorkerBinding(auth, String.valueOf(body.get("worker_id")));
        return workerService.register(body);
    }

    @PostMapping("/workers/offline")
    public Map<String, Object> offline(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body) {
        WorkerAuthContext auth = authService.authenticate(authorization);
        authService.assertWorkerBinding(auth, String.valueOf(body.get("worker_id")));
        return workerService.offline(body);
    }

    private static void fillWorkerId(Map<String, Object> body, String workerHeader) {
        if ((body.get("worker_id") == null || String.valueOf(body.get("worker_id")).isBlank())
            && workerHeader != null && !workerHeader.isBlank()) {
            body.put("worker_id", workerHeader.trim());
        }
    }
}
