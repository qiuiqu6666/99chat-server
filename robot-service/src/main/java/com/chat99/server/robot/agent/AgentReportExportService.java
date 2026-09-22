package com.chat99.server.robot.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class AgentReportExportService {

    private static final Logger log = LoggerFactory.getLogger(AgentReportExportService.class);

    static final int CSV_ONLY_ROW_LIMIT = 500;
    static final int SYNC_ROW_LIMIT = 5000;
    static final int ASYNC_ZIP_ROW_LIMIT = 50000;
    static final int RETENTION_DAYS = 7;

    private final AgentRebateService rebateService;
    private final AgentPlayerRepository playerRepository;
    private final AgentReportExportRepository exportRepository;
    private final Path storageDir;

    public AgentReportExportService(
            AgentRebateService rebateService,
            AgentPlayerRepository playerRepository,
            AgentReportExportRepository exportRepository,
            @Value("${robot.agent-export.storage-dir:data/agent-export}") String storageDir) {
        this.rebateService = rebateService;
        this.playerRepository = playerRepository;
        this.exportRepository = exportRepository;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public ExportCreatedResponse submitExport(String machineCode, String userId, ExportRequest request) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        validateRequest(request);
        AgentReportExportFileBuilder.ExportPayload payload = buildPayload(context, userId, request);
        validateFileTypeRules(request, payload.rowCount());

        String taskNo = "ARE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(RETENTION_DAYS);
        AgentReportExportTask task = new AgentReportExportTask(
            0L,
            taskNo,
            userId,
            context.playerGroupId(),
            context.player().playerNo(),
            displayName(context.player()),
            request.startDate(),
            request.endDate(),
            request.normalizedFileType(),
            request.includeAgentDetail(),
            request.includePlayerDetail(),
            "PENDING",
            0,
            null,
            null,
            null,
            null,
            payload.rowCount(),
            null,
            LocalDateTime.now(),
            null,
            null,
            expiresAt);
        exportRepository.insert(task);

        boolean async = payload.rowCount() > SYNC_ROW_LIMIT;
        if (!async) {
            processTask(taskNo);
            AgentReportExportTask completed = requireOwnedTask(userId, taskNo);
            return toCreatedResponse(completed, false);
        }
        return new ExportCreatedResponse(taskNo, "PENDING", 0, true, null);
    }

    public ExportTaskResponse getTask(String userId, String taskNo) {
        return toTaskResponse(requireOwnedTask(userId, taskNo));
    }

    public DownloadFile download(String userId, String taskNo) {
        AgentReportExportTask task = requireOwnedTask(userId, taskNo);
        if (!task.completed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPORT_NOT_READY");
        }
        if (task.filePath() == null || task.fileName() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND");
        }
        if (task.expiresAt() != null && task.expiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "EXPORT_EXPIRED");
        }
        Path file = storageDir.resolve(task.filePath()).normalize();
        if (!file.startsWith(storageDir) || !Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND");
        }
        try {
            byte[] data = Files.readAllBytes(file);
            String contentType = task.contentType() == null ? "application/octet-stream" : task.contentType();
            return new DownloadFile(task.fileName(), contentType, data);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_READ_FAILED");
        }
    }

    public void processTask(String taskNo) {
        AgentReportExportTask task = exportRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> new IllegalStateException("task not found: " + taskNo));
        try {
            exportRepository.markProgress(taskNo, 20);
            ExportRequest request = new ExportRequest(
                task.startDate(),
                task.endDate(),
                task.fileType(),
                task.includeAgentDetail(),
                task.includePlayerDetail());
            AgentPlayerContext context = new AgentPlayerContext(task.playerGroupId(), requirePlayer(task));
            AgentReportExportFileBuilder.ExportPayload payload = buildPayload(context, task.requesterUserId(), request);
            exportRepository.markProgress(taskNo, 60);

            AgentReportExportFileBuilder.BuiltFile built = AgentReportExportFileBuilder.build(payload);
            byte[] output = built.data();
            String fileName = built.fileName();
            String contentType = built.contentType();
            if (payload.rowCount() >= ASYNC_ZIP_ROW_LIMIT) {
                output = zip(fileName, built.data());
                fileName = fileName.replace(".xlsx", ".zip").replace(".txt", ".zip");
                contentType = "application/zip";
            }

            Files.createDirectories(storageDir);
            String relativePath = taskNo + "/" + fileName;
            Path target = storageDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, output);

            exportRepository.markCompleted(
                taskNo, relativePath, fileName, output.length, contentType, payload.rowCount());
        } catch (Exception e) {
            log.error("agent export failed taskNo={}", taskNo, e);
            exportRepository.markFailed(taskNo, e.getMessage() == null ? "EXPORT_FAILED" : e.getMessage());
        }
    }

    public int cleanupExpired() {
        return exportRepository.deleteExpired(LocalDateTime.now());
    }

    public boolean processNextPending() {
        return exportRepository.claimNextPendingId()
            .flatMap(exportRepository::findById)
            .map(task -> {
                processTask(task.taskNo());
                return true;
            })
            .orElse(false);
    }

    private AgentPlayerRow requirePlayer(AgentReportExportTask task) {
        return playerRepository.findPlayer(task.playerGroupId(), task.requesterUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND"));
    }

    private AgentReportExportFileBuilder.ExportPayload buildPayload(AgentPlayerContext context, String userId, ExportRequest request) {
        AgentRebateService.AgentHistoryRebateResponse history = rebateService.getHistoryRebate(
            context.playerGroupId(), userId, request.startDate(), request.endDate());
        List<AgentDailySummaryRow> detailRows = playerRepository.findVisibleDailySummaries(
            context.playerGroupId(), userId, request.startDate(), request.endDate());
        Map<String, AgentPlayerRow> snapshotByWxid = indexSnapshots(context.playerGroupId(), userId);

        List<AgentReportExportFileBuilder.DetailRow> agentRows = new ArrayList<>();
        List<AgentReportExportFileBuilder.DetailRow> playerRows = new ArrayList<>();
        for (AgentDailySummaryRow row : detailRows) {
            if (row.wxid().equals(userId)) {
                continue;
            }
            boolean isAgent = playerRepository.hasDescendants(context.playerGroupId(), row.wxid());
            AgentReportExportFileBuilder.DetailRow detail = toDetailRow(row, snapshotByWxid);
            if (isAgent) {
                if (request.includeAgentDetail()) {
                    agentRows.add(detail);
                }
            } else if (request.includePlayerDetail()) {
                playerRows.add(detail);
            }
        }

        int rowCount = history.days().size() + agentRows.size() + playerRows.size();
        return new AgentReportExportFileBuilder.ExportPayload(
            request.normalizedFileType(),
            context.player().playerNo(),
            displayName(context.player()),
            request.startDate(),
            request.endDate(),
            request.includeAgentDetail(),
            request.includePlayerDetail(),
            history.days(),
            agentRows,
            playerRows,
            rowCount);
    }

    private Map<String, AgentPlayerRow> indexSnapshots(String playerGroupId, String userId) {
        Map<String, AgentPlayerRow> map = new HashMap<>();
        for (AgentPlayerRow row : playerRepository.findVisibleSnapshots(playerGroupId, userId)) {
            map.put(row.wxid(), row);
        }
        return map;
    }

    private static AgentReportExportFileBuilder.DetailRow toDetailRow(
            AgentDailySummaryRow row, Map<String, AgentPlayerRow> snapshotByWxid) {
        String parentNo = null;
        if (row.directParentWxid() != null) {
            AgentPlayerRow parent = snapshotByWxid.get(row.directParentWxid());
            if (parent != null) {
                parentNo = parent.playerNo();
            }
        }
        return new AgentReportExportFileBuilder.DetailRow(
            row.businessDate(),
            row.playerNo(),
            row.displayName(),
            parentNo,
            row.totalFlow(),
            row.totalProfitLoss(),
            row.totalProfitLoss() == null
                ? AgentRebateMath.zero()
                : AgentRebateMath.money(row.totalProfitLoss().negate()),
            row.totalRebate(),
            row.pendingRebate(),
            row.balance(),
            row.totalUp(),
            row.totalDown());
    }

    private AgentReportExportTask requireOwnedTask(String userId, String taskNo) {
        AgentReportExportTask task = exportRepository.findByTaskNo(taskNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EXPORT_TASK_NOT_FOUND"));
        if (!task.ownedBy(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EXPORT_TASK_FORBIDDEN");
        }
        return task;
    }

    private AgentPlayerContext requireAgentContext(String machineCode, String userId) {
        AgentPlayerContext context = playerRepository.findPlayer(machineCode, userId)
            .map(player -> new AgentPlayerContext(machineCode, player))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND"));
        if (!AgentRebateMath.isAgentByRebateRate(context.player().rebateRate())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_AGENT");
        }
        return context;
    }

    private static void validateRequest(ExportRequest request) {
        if (request.startDate() == null || request.endDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DATE_RANGE_REQUIRED");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }
        if (request.startDate().plusDays(93).isBefore(request.endDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LARGE");
        }
        String fileType = request.normalizedFileType();
        if (!"TXT".equals(fileType) && !"CSV".equals(fileType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
        }
    }

    private static void validateFileTypeRules(ExportRequest request, int rowCount) {
        String fileType = request.normalizedFileType();
        if (rowCount >= CSV_ONLY_ROW_LIMIT && "TXT".equals(fileType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TXT_NOT_ALLOWED_FOR_LARGE_EXPORT");
        }
        if ((request.includeAgentDetail() || request.includePlayerDetail()) && rowCount >= CSV_ONLY_ROW_LIMIT
                && "TXT".equals(fileType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DETAIL_EXPORT_REQUIRES_CSV");
        }
    }

    private static String displayName(AgentPlayerRow player) {
        if (player.displayName() != null && !player.displayName().isBlank()) {
            return player.displayName();
        }
        return player.nickname() != null ? player.nickname() : player.wxid();
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private ExportCreatedResponse toCreatedResponse(AgentReportExportTask task, boolean async) {
        String downloadPath = task.completed()
            ? "/me/agent/rebate/history/export/" + task.taskNo() + "/download"
            : null;
        return new ExportCreatedResponse(
            task.taskNo(),
            task.taskStatus(),
            task.progress(),
            async,
            downloadPath);
    }

    private ExportTaskResponse toTaskResponse(AgentReportExportTask task) {
        String downloadPath = task.completed()
            ? "/me/agent/rebate/history/export/" + task.taskNo() + "/download"
            : null;
        return new ExportTaskResponse(
            task.taskNo(),
            task.taskStatus(),
            task.progress(),
            task.fileName(),
            task.fileSize(),
            task.rowCount(),
            task.errorMessage(),
            downloadPath);
    }

    public record ExportRequest(
        LocalDate startDate,
        LocalDate endDate,
        String fileType,
        boolean includeAgentDetail,
        boolean includePlayerDetail) {

        public ExportRequest(LocalDate startDate, LocalDate endDate, String fileType,
                             Boolean includeDetail, Boolean includeAgentDetail, Boolean includePlayerDetail) {
            this(
                startDate,
                endDate,
                fileType,
                Boolean.TRUE.equals(includeAgentDetail) || Boolean.TRUE.equals(includeDetail),
                Boolean.TRUE.equals(includePlayerDetail) || Boolean.TRUE.equals(includeDetail));
        }

        String normalizedFileType() {
            return fileType == null ? "CSV" : fileType.trim().toUpperCase();
        }
    }

    public record ExportCreatedResponse(
        String taskNo,
        String taskStatus,
        int progress,
        boolean async,
        String downloadPath) {}

    public record ExportTaskResponse(
        String taskNo,
        String taskStatus,
        int progress,
        String fileName,
        Long fileSize,
        int rowCount,
        String errorMessage,
        String downloadPath) {}

    public record DownloadFile(String fileName, String contentType, byte[] data) {}
}
