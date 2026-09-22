package com.chat99.server.robot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class RobotMachineService {

    private static final int REGISTER_WINDOW_MS = 60_000;
    private static final int REGISTER_MAX_PER_WINDOW = 20;

    private final RobotMachineRepository machineRepository;
    private final RobotGroupBindingRepository bindingRepository;
    private final RobotTenantCleanupRepository tenantCleanupRepository;
    private final TransactionTemplate robotTransactionTemplate;
    private final ConcurrentHashMap<String, RateWindow> registerRate = new ConcurrentHashMap<>();

    public RobotMachineService(
            RobotMachineRepository machineRepository,
            RobotGroupBindingRepository bindingRepository,
            RobotTenantCleanupRepository tenantCleanupRepository,
            @Qualifier("robotTransactionTemplate") TransactionTemplate robotTransactionTemplate) {
        this.machineRepository = machineRepository;
        this.bindingRepository = bindingRepository;
        this.tenantCleanupRepository = tenantCleanupRepository;
        this.robotTransactionTemplate = robotTransactionTemplate;
    }

    /** Validate header machine code exists and is ACTIVE; touch last_seen. */
    public String requireActiveMachineCode(String rawHeader) {
        String code = MachineCodeSupport.canonicalize(rawHeader);
        RobotMachine machine = machineRepository.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid machine code"));
        if (!machine.active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "machine code disabled");
        }
        machineRepository.touchLastSeen(code);
        return code;
    }

    public RegisterResponse register(String clientIp, String label) {
        enforceRegisterRate(clientIp == null ? "unknown" : clientIp);
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = MachineCodeSupport.generate();
            if (machineRepository.findByCode(code).isPresent()) {
                continue;
            }
            machineRepository.insert(code, blankToNull(label));
            return new RegisterResponse(code);
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to allocate machine code");
    }

    /**
     * Full machine-code unregister: cascade tenant rows, all group bindings, then machine row.
     * Irreversible. Repeat call on same code → 401.
     */
    public UnregisterResponse unregister(String rawHeader) {
        String code = requireActiveMachineCode(rawHeader);
        return robotTransactionTemplate.execute(status -> {
            int deletedUpdown = tenantCleanupRepository.deleteUpdownRecords(code);
            int deletedDaily = tenantCleanupRepository.deleteDailySummaries(code);
            int deletedSnapshots = tenantCleanupRepository.deleteSnapshots(code);
            int deletedSyncEvents = tenantCleanupRepository.deleteSyncEvents(code);
            int deletedRuntime = tenantCleanupRepository.deleteRuntimeStates(code);
            int deletedExports = tenantCleanupRepository.deleteExportTasks(code);
            int deletedBindings = bindingRepository.deleteByMachineCode(code);
            int deletedMachine = machineRepository.deleteByCode(code);
            if (deletedMachine <= 0) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid machine code");
            }
            return new UnregisterResponse(
                code,
                deletedBindings,
                deletedSnapshots,
                deletedDaily,
                deletedUpdown,
                deletedRuntime,
                deletedSyncEvents,
                deletedExports);
        });
    }

    public BindResponse bindGroup(String machineCode, String groupId) {
        String code = requireActiveMachineCode(machineCode);
        String imGroupId = MachineCodeSupport.requireGroupId(groupId);

        // 一码可绑多群；一群仍只能绑一码
        var existingByGroup = bindingRepository.findByGroupId(imGroupId);
        if (existingByGroup.isPresent() && !existingByGroup.get().machineCode().equals(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GROUP_ALREADY_BOUND");
        }

        bindingRepository.upsertBind(imGroupId, code);
        RobotGroupBinding binding = bindingRepository.findByGroupId(imGroupId)
            .orElseThrow(() -> new IllegalStateException("binding missing after upsert"));
        return new BindResponse(binding.imGroupId(), binding.machineCode(), binding.enabled(), binding.robotId());
    }

    public EnableResponse enable(String machineCode, String groupId, String robotId) {
        String code = requireActiveMachineCode(machineCode);
        return doEnable(code, groupId, robotId);
    }

    /** App path: group already bound; enable without re-sending machine code. */
    public EnableResponse enableForBoundGroup(String groupId, String robotId) {
        String imGroupId = MachineCodeSupport.requireGroupId(groupId);
        RobotGroupBinding binding = bindingRepository.findByGroupId(imGroupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_BOUND"));
        return doEnable(binding.machineCode(), imGroupId, robotId);
    }

    private EnableResponse doEnable(String machineCode, String groupId, String robotId) {
        String imGroupId = MachineCodeSupport.requireGroupId(groupId);
        if (robotId == null || robotId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ROBOT_ID_REQUIRED");
        }
        String rid = robotId.trim();

        RobotGroupBinding binding = bindingRepository.findByGroupId(imGroupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_BOUND"));
        if (!binding.machineCode().equals(machineCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MACHINE_GROUP_MISMATCH");
        }
        if (!bindingRepository.enable(imGroupId, machineCode, rid)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ENABLE_FAILED");
        }
        return new EnableResponse(imGroupId, machineCode, rid, true);
    }

    /**
     * Resolve IM group → machine_code for App queries.
     * Requires binding exists and enabled.
     */
    public String requireEnabledMachineCodeForGroup(String groupId) {
        String imGroupId = MachineCodeSupport.requireGroupId(groupId);
        RobotGroupBinding binding = bindingRepository.findByGroupId(imGroupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_BOUND"));
        if (!binding.enabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GROUP_NOT_ENABLED");
        }
        return binding.machineCode();
    }

    public GroupStatusResponse getGroupStatus(String groupId) {
        String imGroupId = MachineCodeSupport.requireGroupId(groupId);
        return bindingRepository.findByGroupId(imGroupId)
            .map(b -> new GroupStatusResponse(
                imGroupId,
                true,
                b.enabled(),
                b.robotId(),
                MachineCodeSupport.mask(b.machineCode())))
            .orElseGet(() -> new GroupStatusResponse(imGroupId, false, false, null, null));
    }

    /** Internal (probe): includes full machineCode for Telegram enable. */
    public InternalGroupStatus getInternalGroupStatus(String groupId) {
        String imGroupId = MachineCodeSupport.requireGroupId(groupId);
        return bindingRepository.findByGroupId(imGroupId)
            .map(b -> new InternalGroupStatus(
                imGroupId, true, b.enabled(), b.robotId(), b.machineCode()))
            .orElseGet(() -> new InternalGroupStatus(imGroupId, false, false, null, null));
    }

    private void enforceRegisterRate(String clientIp) {
        long now = System.currentTimeMillis();
        RateWindow window = registerRate.compute(clientIp, (k, prev) -> {
            if (prev == null || now - prev.windowStartMs > REGISTER_WINDOW_MS) {
                return new RateWindow(now, new AtomicInteger(1));
            }
            prev.count.incrementAndGet();
            return prev;
        });
        if (window.count.get() > REGISTER_MAX_PER_WINDOW) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "register rate limited");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RateWindow(long windowStartMs, AtomicInteger count) {}

    public record RegisterResponse(String machineCode) {}

    public record BindResponse(String groupId, String machineCode, boolean enabled, String robotId) {}

    public record EnableResponse(String groupId, String machineCode, String robotId, boolean enabled) {}

    public record UnregisterResponse(
        String machineCode,
        int deletedBindings,
        int deletedSnapshots,
        int deletedDailySummaries,
        int deletedUpdownRecords,
        int deletedRuntimeStates,
        int deletedSyncEvents,
        int deletedExportTasks) {}

    public record GroupStatusResponse(
        String groupId,
        boolean bound,
        boolean enabled,
        String robotId,
        String machineCodeMasked) {}

    public record InternalGroupStatus(
        String groupId,
        boolean bound,
        boolean enabled,
        String robotId,
        String machineCode) {}
}
