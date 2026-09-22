package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.TenantAgentGroupRepository;
import com.chat99.sangong.repository.UserGroupRepository;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class UserGroupService {
    private final UserGroupRepository groups;
    private final UserRepository users;
    private final TenantAgentGroupRepository tagRepo;

    public UserGroupService(UserGroupRepository groups, UserRepository users, TenantAgentGroupRepository tagRepo) {
        this.groups = groups;
        this.users = users;
        this.tagRepo = tagRepo;
    }

    public List<Map<String, Object>> listGroups() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : groups.listAll()) {
            out.add(formatGroup(row));
        }
        return out;
    }

    public Map<String, Object> findById(long id) {
        return groups.findById(id).orElse(null);
    }

    public Map<String, Object> create(Map<String, Object> input) {
        String name = str(input.get("name")).trim();
        if (name.isEmpty()) {
            throw new RuntimeException("分组名称不能为空");
        }
        String code = normalizeCode(input.get("code"));
        if (code != null && groups.findByCode(code).isPresent()) {
            throw new RuntimeException("分组编号已存在");
        }
        boolean isActive = !input.containsKey("isActive") || Boolean.TRUE.equals(toBool(input.get("isActive")));
        long id = groups.insert(code, name, str(input.get("note")).trim(), isActive);
        return formatGroup(groups.findById(id).orElseThrow());
    }

    public Map<String, Object> update(long id, Map<String, Object> input) {
        Map<String, Object> group = groups.findById(id)
            .orElseThrow(() -> new RuntimeException("分组不存在"));
        String name = null;
        if (input.containsKey("name")) {
            name = str(input.get("name")).trim();
            if (name.isEmpty()) {
                throw new RuntimeException("分组名称不能为空");
            }
        }
        String code = null;
        if (input.containsKey("code")) {
            code = normalizeCode(input.get("code"));
            if (code != null) {
                var existing = groups.findByCode(code);
                if (existing.isPresent() && ((Number) existing.get().get("id")).longValue() != id) {
                    throw new RuntimeException("分组编号已存在");
                }
            }
        }
        String note = input.containsKey("note") ? str(input.get("note")).trim() : null;
        Boolean isActive = input.containsKey("isActive") ? toBool(input.get("isActive")) : null;
        groups.update(id, code, name, note, isActive);
        return formatGroup(groups.findById(id).orElseThrow());
    }

    public SangongUser assignUser(SangongUser user, Long groupId) {
        if (groupId == null || groupId == 0) {
            users.updateGroupId(user.getId(), null);
            return users.findById(user.getId()).orElse(user);
        }
        Map<String, Object> group = groups.findById(groupId)
            .orElseThrow(() -> new RuntimeException("分组不存在"));
        if (!isActive(group)) {
            throw new RuntimeException("分组已停用");
        }
        users.updateGroupId(user.getId(), groupId);
        return users.findById(user.getId()).orElse(user);
    }

    /** 按编号分组：code 为空或 0 表示移除分组；不存在则自动创建。 */
    public Map<String, Object> assignUserByCode(SangongUser user, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        String normalized = normalizeCode(code);
        if (normalized == null || "0".equals(normalized)) {
            out.put("user", assignUser(user, null));
            out.put("groupCreated", false);
            out.put("group", null);
            return out;
        }
        Map<String, Object> existing = groups.findByCode(normalized).orElse(null);
        Map<String, Object> group = existing;
        boolean created = false;
        if (group == null) {
            long id = groups.insert(normalized, normalized + "组", "", true);
            group = groups.findById(id).orElseThrow();
            created = true;
        }
        if (!isActive(group)) {
            throw new RuntimeException("分组已停用");
        }
        long groupId = ((Number) group.get("id")).longValue();
        users.updateGroupId(user.getId(), groupId);
        out.put("user", users.findById(user.getId()).orElse(user));
        out.put("groupCreated", created);
        out.put("group", formatGroup(group));
        return out;
    }

    public Map<String, Object> formatUserGroup(Long groupId) {
        if (groupId == null) {
            return null;
        }
        Map<String, Object> group = groups.findById(groupId).orElse(null);
        if (group == null) {
            return null;
        }
        // 走法 B:代理配置来自 sangong_tenant_agent_groups（按租户）
        var tag = tagRepo.findByTenantAndGroup(TenantContext.require(), groupId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", ((Number) group.get("id")).longValue());
        out.put("code", group.get("code"));
        out.put("name", group.get("name"));
        if (tag != null) {
            out.put("agentImUserId", tag.getAgentImUserId());
            out.put("maxRebatePct", tag.getMaxRebatePct());
            out.put("agentActive", tag.isActive());
        } else {
            // 兜底:本租户未配置代理实例，从老字段读(向后兼容老数据)
            out.put("agentImUserId", group.get("agent_im_user_id"));
            out.put("maxRebatePct", toDouble(group.get("max_rebate_pct")));
        }
        return out;
    }

    /**
     * 校验玩家返水比例不超过所属代理分组的上限。
     * 走法 B:走 sangong_tenant_agent_groups 读本租户代理 max_rebate_pct。
     * @throws RuntimeException 当超过时
     */
    public void validatePlayerRebateWithinGroup(long groupId, double playerRebatePct) {
        var tag = tagRepo.findByTenantAndGroup(TenantContext.require(), groupId).orElse(null);
        if (tag == null) {
            throw new RuntimeException("本租户未配置代理分组: " + groupId);
        }
        double maxPct = tag.getMaxRebatePct();
        if (playerRebatePct > maxPct) {
            throw new RuntimeException("playerRebatePct=" + playerRebatePct + " 超过代理上限 " + maxPct);
        }
    }

    public Map<String, Object> formatGroup(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", id);
        out.put("code", row.get("code"));
        out.put("name", row.get("name"));
        out.put("note", row.get("note"));
        out.put("isActive", isActive(row));
        out.put("userCount", groups.countUsers(id));
        return out;
    }

    private boolean isActive(Map<String, Object> row) {
        Object v = row.get("is_active");
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return true;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static Boolean toBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(o));
    }
    private static double toDouble(Object o) {
        if (o == null) return 0d;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
    private static String normalizeCode(Object code) {
        if (code == null) return null;
        String v = String.valueOf(code).trim();
        return v.isEmpty() ? null : v;
    }
}
