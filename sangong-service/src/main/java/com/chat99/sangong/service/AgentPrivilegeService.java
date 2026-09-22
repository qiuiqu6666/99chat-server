package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongTenantAgentGroup;
import com.chat99.sangong.repository.TenantAgentGroupRepository;
import com.chat99.sangong.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 代理权限闸口：
 *   - 当前请求账号（即 GamePrivilegeFilter 写入的 admin_user_id）
 *   - 必须等于 sangong_tenant_agent_groups.agent_im_user_id（按租户范围查）
 *   - 不允许群主越权划转；划转只能由代理本人发起
 *
 * 走法 B 模型：
 *   - 每租户一份代理实例（sangong_tenant_agent_groups）
 *   - 同一个 IM 用户可以在多个租户代理，但每个 (tenant_id, group_id) 一份
 *   - 不再读 sangong_user_groups.agent_im_user_id（全局字段已被覆盖）
 */
@Service
public class AgentPrivilegeService {
    private final TenantAgentGroupRepository tagRepo;

    public AgentPrivilegeService(TenantAgentGroupRepository tagRepo) {
        this.tagRepo = tagRepo;
    }

    public static String adminUserId(HttpServletRequest request) {
        Object v = request == null ? null : request.getAttribute("admin_user_id");
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 校验：当前请求账号是否为某分组的代理（按当前租户）。
     */
    public void assertIsAgentOf(String mainUserId, long groupId) {
        String tenantId = TenantContext.require();
        if (mainUserId == null || mainUserId.isBlank()) {
            throw new IllegalStateException("NOT_AGENT_OF_GROUP");
        }
        SangongTenantAgentGroup tag = tagRepo.lockByTenantAndGroup(tenantId, groupId);
        if (tag == null) {
            throw new IllegalStateException("AGENT_GROUP_NOT_FOUND");
        }
        if (!tag.isActive()) {
            throw new IllegalStateException("AGENT_GROUP_INACTIVE");
        }
        if (tag.getAgentImUserId() == null || tag.getAgentImUserId().isBlank()) {
            throw new IllegalStateException("AGENT_GROUP_NOT_CONFIGURED");
        }
        if (!mainUserId.equals(tag.getAgentImUserId())) {
            throw new IllegalStateException("NOT_AGENT_OF_GROUP");
        }
    }

    /**
     * 软返回：当前请求账号是否是某分组的代理（按当前租户）。
     */
    public boolean isAgentOf(String mainUserId, long groupId) {
        if (mainUserId == null || mainUserId.isBlank()) return false;
        try {
            String tenantId = TenantContext.require();
            Optional<SangongTenantAgentGroup> tag = tagRepo.findByTenantAndGroup(tenantId, groupId);
            return tag.isPresent() && tag.get().isActive()
                && tag.get().getAgentImUserId() != null
                && mainUserId.equals(tag.get().getAgentImUserId());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 当前请求账号是否就是 group_id 对应的代理（按当前租户）。
     */
    public void assertCanProxyBet(HttpServletRequest request, long playerGroupId) {
        String op = adminUserId(request);
        assertIsAgentOf(op, playerGroupId);
    }

    /**
     * 查找当前账号在当前租户的代理主分组（取第一个 active）。
     */
    public Optional<SangongTenantAgentGroup> findMyAgentGroup(String mainUserId) {
        if (mainUserId == null || mainUserId.isBlank()) return Optional.empty();
        String tenantId = TenantContext.require();
        return tagRepo.findActiveByAgentFirst(tenantId, mainUserId);
    }

    /**
     * 查找当前账号在当前租户的所有代理分组（多群支持）。
     */
    public java.util.List<SangongTenantAgentGroup> findMyAgentGroups(String mainUserId) {
        if (mainUserId == null || mainUserId.isBlank()) return java.util.List.of();
        String tenantId = TenantContext.require();
        return tagRepo.findActiveByAgentInTenant(tenantId, mainUserId);
    }

    // 兼容旧 API（保留以减少改动面）：从请求里提取 admin_user_id 并返回原 Map<String,Object>
    // 的占位逻辑不再使用，但保留旧代码不至于编译报错。
    @Deprecated
    public Map<String, Object> deprecatedFindByIdMap(long groupId) {
        // 此方法仅为兼容旧 import，不应在新代码使用
        return java.util.Map.of();
    }
}