package com.chat99.server.adminapi;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserGenerationService.class);

    private final AdminUserGenerationTaskRepository taskRepository;
    private final AdminUserGenerationItemRepository itemRepository;
    private final AdminUserGenerationCrypto crypto;
    private final AdminUserGenerationProperties properties;
    private final AdminUserManagementService userManagementService;
    private final UserRepository userRepository;
    private final UserWalletRepository walletRepository;
    private final AdminAuditService auditService;

    public AdminUserGenerationService(AdminUserGenerationTaskRepository taskRepository,
                                      AdminUserGenerationItemRepository itemRepository,
                                      AdminUserGenerationCrypto crypto,
                                      AdminUserGenerationProperties properties,
                                      AdminUserManagementService userManagementService,
                                      UserRepository userRepository,
                                      UserWalletRepository walletRepository,
                                      AdminAuditService auditService) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.crypto = crypto;
        this.properties = properties;
        this.userManagementService = userManagementService;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.auditService = auditService;
    }

    @Transactional
    public TaskCreatedResponse createTask(HttpServletRequest http, String adminUsername,
                                          String password, int count, String sex) {
        if (count < 1 || count > 100) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "count must be between 1 and 100");
        }
        if (password == null || password.length() < 6 || password.length() > 128) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "password must be 6-128 characters");
        }

        String taskNo = "UGT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now = Instant.now();
        AdminUserGenerationTask task = new AdminUserGenerationTask();
        task.setTaskNo(taskNo);
        task.setCreatedBy(adminUsername);
        task.setRequestedCount(count);
        task.setSex(sex == null || sex.isBlank() ? null : sex.trim());
        task.setPasswordCiphertext(crypto.encrypt(password));
        task.setExpiresAt(now.plus(properties.retentionDays(), ChronoUnit.DAYS));
        taskRepository.save(task);

        String nicknamePrefix = "用户" + taskNo.substring(taskNo.length() - 8);
        for (int i = 0; i < count; i++) {
            AdminUserGenerationItem item = new AdminUserGenerationItem();
            item.setTaskId(task.getId());
            item.setItemIndex(i);
            item.setNickname(nicknamePrefix + String.format("%03d", i + 1));
            itemRepository.save(item);
        }
        auditService.log(http, adminUsername, "user.generation_task.create", null,
            Map.of("task_no", taskNo, "requested_count", count));
        return new TaskCreatedResponse(taskNo, task.getStatus().name(), count, now);
    }

    public TaskPageResponse listTasks(String taskNo, String createdBy, String statusRaw,
                                      Instant createdFrom, Instant createdTo, int page, int pageSize) {
        AdminUserGenerationStatus status = parseStatus(statusRaw);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        Page<AdminUserGenerationTask> result = taskRepository.search(
            blankToNull(taskNo), blankToNull(createdBy), status, createdFrom, createdTo,
            PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new TaskPageResponse(
            result.getContent().stream().map(this::toTaskItem).toList(),
            result.getTotalElements(), safePage, safeSize);
    }

    public TaskDetailResponse getTask(String taskNo) {
        AdminUserGenerationTask task = requireTask(taskNo);
        List<GenerationAccountItem> accounts = itemRepository
            .findByTaskIdOrderByItemIndexAsc(task.getId()).stream()
            .map(this::toAccountItem)
            .toList();
        return new TaskDetailResponse(toTaskItem(task), accounts);
    }

    @Transactional
    public AdminUserGenerationTask claimNextTask() {
        AdminUserGenerationTask task = taskRepository
            .findFirstByStatusAndUpdatedAtBeforeOrderByCreatedAtAsc(
                AdminUserGenerationStatus.running, Instant.now().minus(2, ChronoUnit.MINUTES))
            .or(() -> taskRepository.findFirstByStatusOrderByCreatedAtAsc(
                AdminUserGenerationStatus.pending))
            .orElse(null);
        if (task == null) {
            return null;
        }
        if (task.getStatus() == AdminUserGenerationStatus.pending) {
            Instant now = Instant.now();
            taskRepository.markStatus(task.getId(), AdminUserGenerationStatus.running, now, now);
            task.setStatus(AdminUserGenerationStatus.running);
            task.setStartedAt(now);
        }
        return task;
    }

    public void processTask(AdminUserGenerationTask claimed) {
        AdminUserGenerationTask task = requireTask(claimed.getTaskNo());
        String ciphertext = taskRepository.findPasswordCiphertextById(task.getId());
        String password;
        try {
            if (ciphertext == null || ciphertext.isBlank()) {
                throw new IllegalStateException("password ciphertext missing");
            }
            password = crypto.decrypt(ciphertext);
        } catch (Exception e) {
            log.warn("admin user generation decrypt failed task={} cipherLen={} err={}",
                task.getTaskNo(), ciphertext == null ? -1 : ciphertext.length(), e.toString());
            failWholeTask(task.getId(), "password_decrypt_failed", false);
            return;
        }

        while (true) {
            AdminUserGenerationItem item = claimNextItem(task.getId());
            if (item == null) {
                finishTask(task.getId());
                return;
            }
            try {
                User existing = userRepository.findByNickname(item.getNickname()).orElse(null);
                if (existing != null) {
                    UserWallet wallet = walletRepository.findById(existing.getUserId()).orElse(null);
                    markItemSuccess(task.getId(), item.getId(), existing.getUserId(),
                        wallet == null ? null : wallet.getTronAddress(), null, null);
                    continue;
                }
                AdminUserManagementService.CreateUserResult result = userManagementService.createUser(
                    null, task.getCreatedBy(), item.getNickname(), password, task.getSex());
                markItemSuccess(task.getId(), item.getId(), result.userUid(),
                    result.trxAddress(), result.usdtContract(), result.minDepositUsdt());
            } catch (Exception e) {
                // 重启交接或极短时并发可能使账号已落库但本次调用收到重复错误；
                // 以数据库中的最终账号事实为准，避免把成功结果覆盖成失败。
                User existing = userRepository.findByNickname(item.getNickname()).orElse(null);
                if (existing != null) {
                    UserWallet wallet = walletRepository.findById(existing.getUserId()).orElse(null);
                    markItemSuccess(task.getId(), item.getId(), existing.getUserId(),
                        wallet == null ? null : wallet.getTronAddress(), null, null);
                } else {
                    String code = e instanceof AdminApiException admin
                        ? admin.error() : "internal_error";
                    markItemFailed(task.getId(), item.getId(), code, safeMessage(e));
                }
            }
        }
    }

    @Transactional
    public AdminUserGenerationItem claimNextItem(Long taskId) {
        AdminUserGenerationItem item = itemRepository
            .findFirstByTaskIdAndStatusInOrderByItemIndexAsc(taskId, List.of("pending", "running"))
            .orElse(null);
        if (item != null) {
            item.setStatus("running");
            itemRepository.save(item);
        }
        return item;
    }

    @Transactional
    public void markItemSuccess(Long taskId, Long itemId, String userUid, String trxAddress,
                                String usdtContract, String minDepositUsdt) {
        AdminUserGenerationItem item = itemRepository.findById(itemId).orElseThrow();
        item.setStatus("success");
        item.setUserUid(userUid);
        item.setTrxAddress(trxAddress);
        item.setDepositAddress(trxAddress);
        item.setUsdtContract(usdtContract);
        item.setMinDepositUsdt(minDepositUsdt);
        item.setErrorCode(null);
        item.setErrorMessage(null);
        itemRepository.save(item);
        incrementProgress(taskId, true, null);
    }

    @Transactional
    public void markItemFailed(Long taskId, Long itemId, String code, String message) {
        AdminUserGenerationItem item = itemRepository.findById(itemId).orElseThrow();
        item.setStatus("failed");
        item.setErrorCode(code);
        item.setErrorMessage(message);
        itemRepository.save(item);
        incrementProgress(taskId, false, message);
    }

    @Transactional
    public void finishTask(Long taskId) {
        AdminUserGenerationTask task = taskRepository.findById(taskId).orElseThrow();
        refreshProgress(task);
        if (task.getProcessedCount() < task.getRequestedCount()) {
            return;
        }
        if (task.getSuccessCount() == task.getRequestedCount()) {
            task.setStatus(AdminUserGenerationStatus.success);
            task.setLastError(null);
        } else if (task.getSuccessCount() > 0) {
            task.setStatus(AdminUserGenerationStatus.partial_failed);
        } else {
            task.setStatus(AdminUserGenerationStatus.failed);
        }
        task.setFinishedAt(Instant.now());
        task.setPasswordCiphertext(null);
        taskRepository.save(task);
    }

    @Transactional
    public void failWholeTask(Long taskId, String error) {
        failWholeTask(taskId, error, true);
    }

    @Transactional
    public void failWholeTask(Long taskId, String error, boolean clearPassword) {
        AdminUserGenerationTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(AdminUserGenerationStatus.failed);
        task.setLastError(error);
        task.setFinishedAt(Instant.now());
        if (clearPassword) {
            task.setPasswordCiphertext(null);
        }
        taskRepository.save(task);
    }

    @Transactional
    public int cleanupExpired() {
        List<AdminUserGenerationTask> expired = taskRepository.findByExpiresAtBefore(Instant.now());
        for (AdminUserGenerationTask task : expired) {
            itemRepository.deleteByTaskId(task.getId());
            taskRepository.delete(task);
        }
        return expired.size();
    }

    private void incrementProgress(Long taskId, boolean success, String error) {
        AdminUserGenerationTask task = taskRepository.findById(taskId).orElseThrow();
        refreshProgress(task);
        if (!success) {
            task.setLastError(error);
        }
        taskRepository.save(task);
    }

    private void refreshProgress(AdminUserGenerationTask task) {
        int successCount = Math.toIntExact(
            itemRepository.countByTaskIdAndStatus(task.getId(), "success"));
        int failCount = Math.toIntExact(
            itemRepository.countByTaskIdAndStatus(task.getId(), "failed"));
        task.setSuccessCount(successCount);
        task.setFailCount(failCount);
        task.setProcessedCount(successCount + failCount);
    }

    private AdminUserGenerationTask requireTask(String taskNo) {
        return taskRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> new AdminApiException(
                HttpStatus.NOT_FOUND, "task_not_found", "task_not_found"));
    }

    private TaskItem toTaskItem(AdminUserGenerationTask task) {
        return new TaskItem(
            task.getTaskNo(), task.getStatus().name(), task.getCreatedBy(),
            task.getRequestedCount(), task.getProcessedCount(), task.getSuccessCount(),
            task.getFailCount(), task.getLastError(), task.getStartedAt(), task.getFinishedAt(),
            task.getCreatedAt(), task.getUpdatedAt(), task.getExpiresAt());
    }

    private GenerationAccountItem toAccountItem(AdminUserGenerationItem item) {
        return new GenerationAccountItem(
            item.getItemIndex(), item.getStatus(), item.getUserUid(), item.getNickname(),
            item.getTrxAddress(), item.getDepositAddress(), item.getUsdtContract(),
            item.getMinDepositUsdt(), item.getErrorCode(), item.getErrorMessage());
    }

    private static AdminUserGenerationStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AdminUserGenerationStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new AdminApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid status");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
            : message.substring(0, Math.min(message.length(), 1000));
    }

    public record TaskCreatedResponse(
        String taskNo, String status, int requestedCount, Instant createdAt) {}

    public record TaskItem(
        String taskNo,
        String status,
        String createdBy,
        int requestedCount,
        int processedCount,
        int successCount,
        int failCount,
        String lastError,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
    ) {}

    public record GenerationAccountItem(
        int index,
        String status,
        String userUid,
        String nickname,
        String trxAddress,
        String depositAddress,
        String usdtContract,
        String minDepositUsdt,
        String errorCode,
        String errorMessage
    ) {}

    public record TaskPageResponse(
        List<TaskItem> items, long total, int page, int pageSize) {}

    public record TaskDetailResponse(
        TaskItem task, List<GenerationAccountItem> accounts) {}
}
