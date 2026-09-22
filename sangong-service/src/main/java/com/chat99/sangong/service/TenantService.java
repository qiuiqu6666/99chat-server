package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongTenant;
import com.chat99.sangong.repository.SessionRepository;
import com.chat99.sangong.repository.SettingsRepository;
import com.chat99.sangong.repository.TenantAccessRepository;
import com.chat99.sangong.repository.TenantRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
    private final TenantRepository tenants;
    private final TenantAccessRepository access;
    private final SettingsRepository settings;
    private final SessionRepository sessions;
    private final GameSettingsService gameSettings;

    public TenantService(TenantRepository tenants,
                         TenantAccessRepository access,
                         SettingsRepository settings,
                         SessionRepository sessions,
                         GameSettingsService gameSettings) {
        this.tenants = tenants;
        this.access = access;
        this.settings = settings;
        this.sessions = sessions;
        this.gameSettings = gameSettings;
    }

    public List<SangongTenant> listAll() {
        return tenants.listAll();
    }

    public Optional<SangongTenant> find(String tenantId) {
        return tenants.findById(tenantId);
    }

    public Optional<SangongTenant> findActiveByGameGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        return tenants.findByGameGroupId(groupId.trim());
    }

    public SangongTenant require(String tenantId) {
        return tenants.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("租户不存在: " + tenantId));
    }

    // ===== 按账号隔离 =====

    /** 该账号是否可管理该租户：有访问记录，或租户尚未认领（兼容存量数据）。 */
    public boolean canManage(String mainUserId, String tenantId) {
        if (mainUserId == null || mainUserId.isBlank()) {
            return false;
        }
        if (access.find(mainUserId, tenantId).isPresent()) {
            return true;
        }
        return access.countByTenant(tenantId) == 0;
    }

    public boolean isOwner(String mainUserId, String tenantId) {
        return access.find(mainUserId, tenantId)
            .map(a -> TenantAccessRepository.ROLE_OWNER.equals(a.role()))
            .orElse(false);
    }

    public boolean isTenantStaff(String mainUserId, String tenantId) {
        return access.find(mainUserId, tenantId)
            .map(a -> TenantAccessRepository.ROLE_OWNER.equals(a.role())
                || TenantAccessRepository.ROLE_ADMIN.equals(a.role()))
            .orElse(false);
    }

    /** 只返回该账号可见的租户（自己有权限的 + 未认领的）。 */
    public List<Map<String, Object>> listForAdmin(String mainUserId) {
        Map<String, TenantAccessRepository.Access> mine = new LinkedHashMap<>();
        for (var a : access.listByUser(mainUserId)) {
            mine.put(a.tenantId(), a);
        }
        return tenants.listAll().stream()
            .filter(t -> mine.containsKey(t.getTenantId())
                || access.countByTenant(t.getTenantId()) == 0)
            .map(t -> toAdminRow(t, mine.get(t.getTenantId())))
            .toList();
    }

    private Map<String, Object> toAdminRow(SangongTenant t, TenantAccessRepository.Access mine) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", t.getTenantId());
        row.put("name", t.getName());
        row.put("imGroupGameId", t.getImGroupGameId());
        row.put("imGroupAdminStatsId", t.getImGroupAdminStatsId());
        row.put("imGroupLedgerId", t.getImGroupLedgerId());
        row.put("imGroupWaterId", t.getImGroupWaterId());
        row.put("imBotUserId", t.getImBotUserId());
        row.put("active", t.isActive());
        row.put("myRole", mine == null ? null : mine.role());
        row.put("isDefault", mine != null && mine.isDefault());
        row.put("unclaimed", mine == null);
        boolean running = sessions.findRunning(t.getTenantId()).isPresent();
        row.put("sessionStatus", running ? "running" : "idle");
        return row;
    }

    /** 未认领的存量租户：第一个认领的特权账号成为 owner。 */
    @Transactional
    public void claim(String mainUserId, String tenantId) {
        require(tenantId);
        if (access.countByTenant(tenantId) > 0) {
            throw new IllegalStateException("该游戏群已被认领");
        }
        access.upsert(mainUserId, tenantId, TenantAccessRepository.ROLE_OWNER);
    }

    public List<Map<String, Object>> listAccess(String tenantId) {
        return access.listByTenant(tenantId).stream()
            .map(a -> Map.<String, Object>of(
                "imUserId", a.mainUserId(),
                "role", a.role(),
                "isDefault", a.isDefault()))
            .toList();
    }

    @Transactional
    public void grantAccess(String operatorId, String tenantId, String targetUserId, String role) {
        require(tenantId);
        if (!isOwner(operatorId, tenantId)) {
            throw new IllegalStateException("仅群 owner 可管理成员");
        }
        String r = TenantAccessRepository.ROLE_OWNER.equalsIgnoreCase(role)
            ? TenantAccessRepository.ROLE_OWNER : TenantAccessRepository.ROLE_ADMIN;
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new IllegalArgumentException("imUserId 必填");
        }
        if (operatorId.equals(targetUserId.trim())
            && !TenantAccessRepository.ROLE_OWNER.equals(r)
            && access.countOwners(tenantId) <= 1) {
            throw new IllegalStateException("不能降级最后一个 owner");
        }
        access.upsert(targetUserId.trim(), tenantId, r);
    }

    @Transactional
    public void revokeAccess(String operatorId, String tenantId, String targetUserId) {
        require(tenantId);
        if (!isOwner(operatorId, tenantId)) {
            throw new IllegalStateException("仅群 owner 可管理成员");
        }
        var target = access.find(targetUserId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("该账号没有此群的访问权限"));
        if (TenantAccessRepository.ROLE_OWNER.equals(target.role())
            && access.countOwners(tenantId) <= 1) {
            throw new IllegalStateException("不能移除最后一个 owner");
        }
        access.delete(targetUserId, tenantId);
    }

    @Transactional
    public void setDefaultTenant(String mainUserId, String tenantId) {
        if (access.find(mainUserId, tenantId).isEmpty()) {
            throw new IllegalStateException("你没有此群的访问权限");
        }
        access.setDefault(mainUserId, tenantId);
    }

    // ===== 我的配置（一人一份，可再授权帮工） =====

    /**
     * 读取当前账号的默认/唯一绑定配置。
     * 未配置时 {@code configured=false}，前端引导填写下注群/报表群/机器人号。
     */
    public Map<String, Object> getMyConfig(String mainUserId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (mainUserId == null || mainUserId.isBlank()) {
            out.put("configured", false);
            return out;
        }
        var mine = access.listByUser(mainUserId);
        if (mine.isEmpty()) {
            out.put("configured", false);
            return out;
        }
        TenantAccessRepository.Access chosen = mine.stream()
            .filter(TenantAccessRepository.Access::isDefault)
            .findFirst()
            .orElse(mine.get(0));
        SangongTenant t = tenants.findById(chosen.tenantId()).orElse(null);
        if (t == null) {
            out.put("configured", false);
            return out;
        }
        fillConfig(out, t, chosen.role());
        return out;
    }

    /**
     * 保存「我的配置」：
     * <ul>
     *   <li>新群 / 未认领 → 创建或认领，当前账号成为 owner</li>
     *   <li>已是 owner → 更新报表群、水群、机器人号等</li>
     *   <li>别人已管 / 自己只是 admin → 拒绝改配置</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> saveMyConfig(String mainUserId, Map<String, Object> input) {
        if (mainUserId == null || mainUserId.isBlank()) {
            throw new IllegalStateException("未登录");
        }
        String gameGroupId = requireGroupId(str(input.get("imGroupGameId")));
        String name = str(input.get("name"));
        String adminStats = str(input.get("imGroupAdminStatsId"));
        String ledger = str(input.get("imGroupLedgerId"));
        String water = str(input.get("imGroupWaterId"));
        String bot = GameSettingsService.normalizeBotUserId(str(input.get("imBotUserId")));

        Optional<SangongTenant> existing = tenants.findById(gameGroupId);
        if (existing.isEmpty()) {
            existing = tenants.findByGameGroupId(gameGroupId);
        }

        SangongTenant t;
        if (existing.isEmpty()) {
            t = create(mainUserId, name, gameGroupId, adminStats, ledger, water, bot);
        } else {
            t = existing.get();
            int members = access.countByTenant(t.getTenantId());
            var my = access.find(mainUserId, t.getTenantId());
            if (members == 0) {
                access.upsert(mainUserId, t.getTenantId(), TenantAccessRepository.ROLE_OWNER);
            } else if (my.isEmpty()) {
                throw new IllegalStateException("该游戏群已被其他人管理，请联系群主授权");
            } else if (!TenantAccessRepository.ROLE_OWNER.equals(my.get().role())) {
                throw new IllegalStateException("仅群主可修改群配置；你当前是管理员，可做上下分等业务");
            }
            Map<String, Object> patch = new LinkedHashMap<>();
            if (name != null && !name.isBlank()) {
                patch.put("name", name);
            }
            patch.put("imGroupAdminStatsId", adminStats == null ? "" : adminStats);
            patch.put("imGroupLedgerId", ledger == null ? "" : ledger);
            patch.put("imGroupWaterId", water == null ? "" : water);
            patch.put("imBotUserId", bot == null ? "" : bot);
            t = update(t.getTenantId(), patch);
        }
        access.setDefault(mainUserId, t.getTenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        fillConfig(out, t, TenantAccessRepository.ROLE_OWNER);
        return out;
    }

    private void fillConfig(Map<String, Object> out, SangongTenant t, String role) {
        out.put("configured", true);
        out.put("tenantId", t.getTenantId());
        out.put("name", t.getName());
        out.put("imGroupGameId", t.getImGroupGameId());
        out.put("imGroupAdminStatsId", t.getImGroupAdminStatsId());
        out.put("imGroupLedgerId", t.getImGroupLedgerId());
        out.put("imGroupWaterId", t.getImGroupWaterId());
        out.put("imBotUserId", t.getImBotUserId());
        out.put("active", t.isActive());
        out.put("myRole", role);
        out.put("canEditConfig", TenantAccessRepository.ROLE_OWNER.equals(role));
        out.put("canManageMembers", TenantAccessRepository.ROLE_OWNER.equals(role));
        boolean running = sessions.findRunning(t.getTenantId()).isPresent();
        out.put("sessionStatus", running ? "running" : "idle");
    }

    /**
     * 当前账号已绑定的默认/唯一租户 ID；未配置时抛 IllegalStateException。
     */
    public String requireConfiguredTenantId(String mainUserId) {
        Map<String, Object> cfg = getMyConfig(mainUserId);
        if (!Boolean.TRUE.equals(cfg.get("configured")) || cfg.get("tenantId") == null) {
            throw new IllegalStateException("请先完成我的配置（绑定下注群）");
        }
        return String.valueOf(cfg.get("tenantId"));
    }

    // ===== 租户 CRUD =====

    @Transactional
    public SangongTenant create(String creatorUserId, String name, String gameGroupId,
                                String adminStatsGroupId, String ledgerGroupId,
                                String waterGroupId, String botUserId) {
        String gid = requireGroupId(gameGroupId);
        if (tenants.findByGameGroupId(gid).isPresent() || tenants.findById(gid).isPresent()) {
            throw new IllegalArgumentException("该游戏群已注册: " + gid);
        }
        SangongTenant t = new SangongTenant();
        t.setTenantId(gid);
        t.setName(name == null || name.isBlank() ? gid : name.trim());
        t.setImGroupGameId(gid);
        t.setImGroupAdminStatsId(nz(adminStatsGroupId));
        t.setImGroupLedgerId(nz(ledgerGroupId));
        t.setImGroupWaterId(nz(waterGroupId));
        t.setImBotUserId(GameSettingsService.normalizeBotUserId(nz(botUserId)));
        t.setActive(true);
        tenants.insert(t);
        if (creatorUserId != null && !creatorUserId.isBlank()) {
            access.upsert(creatorUserId, gid, TenantAccessRepository.ROLE_OWNER);
        }
        seedSettings(t);
        return t;
    }

    @Transactional
    public SangongTenant update(String operatorId, String tenantId, Map<String, Object> input) {
        if (operatorId == null || operatorId.isBlank() || !isOwner(operatorId, tenantId)) {
            // 未认领时允许第一个操作者更新（与 claim 兼容）
            if (access.countByTenant(tenantId) > 0) {
                throw new IllegalStateException("仅群主可修改群配置");
            }
        }
        return update(tenantId, input);
    }

    @Transactional
    public SangongTenant update(String tenantId, Map<String, Object> input) {
        SangongTenant t = require(tenantId);
        if (input.containsKey("name")) {
            Object n = input.get("name");
            t.setName(n == null ? "" : String.valueOf(n).trim());
        }
        if (input.containsKey("imGroupAdminStatsId")) {
            t.setImGroupAdminStatsId(str(input.get("imGroupAdminStatsId")));
        }
        if (input.containsKey("imGroupLedgerId")) {
            t.setImGroupLedgerId(str(input.get("imGroupLedgerId")));
        }
        if (input.containsKey("imGroupWaterId")) {
            t.setImGroupWaterId(str(input.get("imGroupWaterId")));
        }
        if (input.containsKey("imBotUserId")) {
            t.setImBotUserId(GameSettingsService.normalizeBotUserId(str(input.get("imBotUserId"))));
        }
        if (input.containsKey("active")) {
            Object a = input.get("active");
            t.setActive(a instanceof Boolean b ? b
                : !"false".equalsIgnoreCase(String.valueOf(a)) && !"0".equals(String.valueOf(a)));
        }
        tenants.update(t);
        TenantContext.run(t.getTenantId(), () -> {
            settings.upsert(GameSettingsService.KEY_IM_GROUP_GAME_ID, t.getImGroupGameId());
            settings.upsert(GameSettingsService.KEY_IM_GROUP_ADMIN_STATS_ID, nz(t.getImGroupAdminStatsId()));
            settings.upsert(GameSettingsService.KEY_IM_BOT_USER_ID, nz(t.getImBotUserId()));
            gameSettings.invalidateCache(t.getTenantId());
        });
        return t;
    }

    private void seedSettings(SangongTenant t) {
        TenantContext.run(t.getTenantId(), () -> {
            Map<String, String> defaults = gameSettings.defaults();
            defaults.put(GameSettingsService.KEY_IM_GROUP_GAME_ID, t.getImGroupGameId());
            defaults.put(GameSettingsService.KEY_IM_GROUP_ADMIN_STATS_ID, nz(t.getImGroupAdminStatsId()));
            defaults.put(GameSettingsService.KEY_IM_BOT_USER_ID, nz(t.getImBotUserId()));
            for (Map.Entry<String, String> e : defaults.entrySet()) {
                settings.insertIfAbsent(e.getKey(), e.getValue());
            }
            gameSettings.invalidateCache(t.getTenantId());
        });
    }

    private static String requireGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("imGroupGameId 必填");
        }
        return groupId.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v.trim();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
