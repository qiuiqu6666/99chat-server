package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 统一用户树：代理只是拥有下级的用户，关系按租户独立保存。 */
@Service
public class UserHierarchyService {
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    public UserHierarchyService(NamedParameterJdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc; this.users = users;
    }

    public List<Map<String, Object>> children(long userId, boolean direct) {
        String t = TenantContext.require();
        String sql = direct
            ? "SELECT h.user_id,h.parent_user_id,h.level_no,h.path,u.im_user_id,u.nickname,u.balance " +
              "FROM sangong_user_hierarchy h JOIN sangong_users u ON u.id=h.user_id " +
              "WHERE h.tenant_id=:t AND h.parent_user_id=:u AND h.is_active=1 ORDER BY h.user_id"
            : "SELECT h.user_id,h.parent_user_id,h.level_no,h.path,u.im_user_id,u.nickname,u.balance " +
              "FROM sangong_user_hierarchy h JOIN sangong_users u ON u.id=h.user_id " +
              "WHERE h.tenant_id=:t AND h.path LIKE CONCAT('%/',:u,'/%') AND h.user_id<>:u " +
              "AND h.is_active=1 ORDER BY h.level_no,h.user_id";
        return jdbc.queryForList(sql, new MapSqlParameterSource().addValue("t", t).addValue("u", userId));
    }

    public Map<String, Object> parent(long userId) {
        String t = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.id AS userId,p.im_user_id AS imUserId,p.nickname,p.balance, " +
            "p.player_rebate_pct AS rebatePct, " +
            "h.level_no AS levelNo " +
            "FROM sangong_user_hierarchy h " +
            "JOIN sangong_users p ON p.tenant_id=h.tenant_id AND p.id=h.parent_user_id " +
            "WHERE h.tenant_id=:t AND h.user_id=:u AND h.is_active=1 AND h.parent_user_id IS NOT NULL",
            new MapSqlParameterSource().addValue("t", t).addValue("u", userId));
        if (rows.isEmpty()) return null;
        Map<String, Object> result = new java.util.LinkedHashMap<>(rows.get(0));
        Object pct = result.get("rebatePct");
        double value = pct instanceof Number ? ((Number) pct).doubleValue() : 0d;
        result.put("rebatePer10000", Math.round(value * 100.0d));
        return result;
    }

    @Transactional
    public void bind(long operatorId, long childId) {
        String t = TenantContext.require();
        if (operatorId == childId) throw new IllegalArgumentException("不能绑定自己");
        Map<String, Object> parent = jdbc.queryForMap("SELECT path,level_no FROM sangong_user_hierarchy " +
            "WHERE tenant_id=:t AND user_id=:u FOR UPDATE", new MapSqlParameterSource().addValue("t", t).addValue("u", operatorId));
        Integer cycle = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_user_hierarchy " +
            "WHERE tenant_id=:t AND user_id=:u AND path LIKE CONCAT('%/',:p,'/%')",
            new MapSqlParameterSource().addValue("t", t).addValue("u", operatorId).addValue("p", childId), Integer.class);
        if (cycle != null && cycle > 0) throw new IllegalArgumentException("不能绑定自己的下级");
        users.findById(childId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String path = String.valueOf(parent.get("path")) + childId + "/";
        int level = ((Number) parent.get("level_no")).intValue() + 1;
        jdbc.update("INSERT INTO sangong_user_hierarchy(tenant_id,user_id,parent_user_id,level_no,path) " +
            "VALUES(:t,:u,:p,:l,:path) ON DUPLICATE KEY UPDATE parent_user_id=:p,level_no=:l,path=:path,is_active=1",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childId).addValue("p", operatorId)
                .addValue("l", level).addValue("path", path));
    }

    @Transactional
    public double setPlayerRebate(long operatorId, long childId, double pct) {
        if (pct < 0 || pct > 100) throw new IllegalArgumentException("返水比例必须在 0 到 100 之间");
        String t = TenantContext.require();
        Map<String, Object> child = jdbc.queryForMap("SELECT parent_user_id FROM sangong_user_hierarchy " +
            "WHERE tenant_id=:t AND user_id=:u AND is_active=1 FOR UPDATE",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childId));
        Object parentValue = child.get("parent_user_id");
        if (!(parentValue instanceof Number)) {
            throw new IllegalStateException("下级用户尚未绑定到代理名下");
        }
        long parent = ((Number) parentValue).longValue();
        if (parent != operatorId) throw new IllegalStateException("只能修改直属下级返水比例");
        jdbc.update("INSERT IGNORE INTO sangong_rebate_accounts(tenant_id,user_id,account_type,rebate_pct) " +
            "SELECT :t,:u,'PLAYER_REBATE',COALESCE(player_rebate_pct,0) FROM sangong_users WHERE id=:u",
            new MapSqlParameterSource().addValue("t", t).addValue("u", operatorId));
        jdbc.update("INSERT IGNORE INTO sangong_rebate_accounts " +
            "(tenant_id,user_id,account_type,rebate_pct) " +
            "SELECT tenant_id,user_id,'AGENT_DIFF',rebate_pct FROM sangong_rebate_accounts " +
            "WHERE tenant_id=:t AND user_id=:u AND account_type='PLAYER_REBATE'",
            new MapSqlParameterSource().addValue("t", t).addValue("u", operatorId));
        Map<String, Object> parentAccount = jdbc.queryForMap("SELECT rebate_pct FROM sangong_rebate_accounts " +
            "WHERE tenant_id=:t AND user_id=:u AND account_type='AGENT_DIFF' FOR UPDATE",
            new MapSqlParameterSource().addValue("t", t).addValue("u", operatorId));
        double max = ((Number) parentAccount.getOrDefault("rebate_pct", 0)).doubleValue();
        if (pct > max) throw new IllegalArgumentException("下级返水比例不能超过代理可分配比例");
        Map<String, Object> account = jdbc.queryForMap("SELECT turnover_total,turnover_claimed,rebate_pct " +
            "FROM sangong_rebate_accounts WHERE tenant_id=:t AND user_id=:u AND account_type='PLAYER_REBATE' FOR UPDATE",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childId));
        long pending = ((Number) account.get("turnover_total")).longValue() - ((Number) account.get("turnover_claimed")).longValue();
        if (pending > 0) throw new IllegalStateException("UNCLAIMED_TURNOVER_EXISTS");
        double old = ((Number) account.get("rebate_pct")).doubleValue();
        jdbc.update("UPDATE sangong_rebate_accounts SET rebate_pct=:pct WHERE tenant_id=:t AND user_id=:u AND account_type='PLAYER_REBATE'",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childId).addValue("pct", pct));
        jdbc.update("INSERT INTO sangong_rebate_rate_changes(tenant_id,target_user_id,old_rate,new_rate,operator_user_id,reason) " +
            "VALUES(:t,:target,:old,:new,:operator,'直属上级修改')",
            new MapSqlParameterSource().addValue("t", t).addValue("target", childId).addValue("old", old)
                .addValue("new", pct).addValue("operator", operatorId));
        return pct;
    }

    @Transactional
    public double setTenantUserRebate(long operatorId, long targetUserId, double pct) {
        if (pct < 0 || pct > 100) throw new IllegalArgumentException("返水比例必须在 0 到 100 之间");
        String t = TenantContext.require();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", t).addValue("u", targetUserId);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_users WHERE tenant_id=:t AND id=:u", p, Integer.class);
        if (exists == null || exists == 0) throw new IllegalArgumentException("用户不存在");
        jdbc.update("INSERT IGNORE INTO sangong_rebate_accounts(tenant_id,user_id,account_type,rebate_pct) " +
            "SELECT :t,id,'PLAYER_REBATE',COALESCE(player_rebate_pct,0) FROM sangong_users WHERE tenant_id=:t AND id=:u", p);
        Map<String, Object> account = jdbc.queryForMap("SELECT turnover_total,turnover_claimed,rebate_pct " +
            "FROM sangong_rebate_accounts WHERE tenant_id=:t AND user_id=:u AND account_type='PLAYER_REBATE' FOR UPDATE", p);
        long pending = ((Number) account.get("turnover_total")).longValue() - ((Number) account.get("turnover_claimed")).longValue();
        if (pending > 0) throw new IllegalStateException("UNCLAIMED_TURNOVER_EXISTS");
        // 群主/帮工可对无层级行的租户用户设反水：hierarchy 可选，有上级时才校验不超过上级比例
        List<Map<String, Object>> hierarchyRows = jdbc.queryForList(
            "SELECT parent_user_id FROM sangong_user_hierarchy WHERE tenant_id=:t AND user_id=:u AND is_active=1", p);
        if (!hierarchyRows.isEmpty()) {
            Object parentId = hierarchyRows.get(0).get("parent_user_id");
            if (parentId instanceof Number) {
                double parentPct = rebatePct(t, ((Number) parentId).longValue());
                if (pct > parentPct) throw new IllegalArgumentException("下级返水比例不能超过上级返水比例");
            }
        }
        double old = ((Number) account.get("rebate_pct")).doubleValue();
        jdbc.update("UPDATE sangong_users SET player_rebate_pct=:pct WHERE tenant_id=:t AND id=:u", p.addValue("pct", pct));
        jdbc.update("UPDATE sangong_rebate_accounts SET rebate_pct=:pct WHERE tenant_id=:t AND user_id=:u AND account_type='PLAYER_REBATE'", p.addValue("pct", pct));
        jdbc.update("INSERT INTO sangong_rebate_rate_changes(tenant_id,target_user_id,old_rate,new_rate,operator_user_id,reason) " +
            "VALUES(:t,:target,:old,:new,:operator,'租户管理员修改')",
            new MapSqlParameterSource().addValue("t", t).addValue("target", targetUserId).addValue("old", old)
                .addValue("new", pct).addValue("operator", operatorId));
        return pct;
    }

    @Transactional
    public void bindByOperator(long operatorId, long parentUserId, long childUserId) {
        String t = TenantContext.require();
        if (parentUserId == childUserId) throw new IllegalArgumentException("不能绑定自己");
        assertTenantUser(t, parentUserId);
        assertTenantUser(t, childUserId);
        if (operatorId != parentUserId && !isStaff(operatorId, t)) {
            throw new IllegalStateException("只有上级代理或租户管理员可以绑定下级");
        }
        bindInternal(t, parentUserId, childUserId);
    }

    @Transactional
    public void changeParent(long operatorId, long childUserId, long newParentUserId) {
        String t = TenantContext.require();
        assertTenantUser(t, childUserId);
        assertTenantUser(t, newParentUserId);
        if (operatorId != childUserId && !isStaff(operatorId, t)) {
            throw new IllegalStateException("只有当前用户或租户管理员可以修改上级");
        }
        bindInternal(t, newParentUserId, childUserId);
    }

    @Transactional
    public void unbind(long operatorId, long childUserId) {
        String t = TenantContext.require();
        Map<String, Object> child = jdbc.queryForMap("SELECT parent_user_id FROM sangong_user_hierarchy " +
            "WHERE tenant_id=:t AND user_id=:u AND is_active=1 FOR UPDATE",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childUserId));
        Object parent = child.get("parent_user_id");
        if (!(parent instanceof Number)) throw new IllegalStateException("用户当前没有上级");
        if (operatorId != ((Number) parent).longValue() && !isStaff(operatorId, t)) {
            throw new IllegalStateException("只有当前上级或租户管理员可以解除关系");
        }
        jdbc.update("UPDATE sangong_user_hierarchy SET parent_user_id=NULL,level_no=0,path=CONCAT('/',user_id,'/') " +
            "WHERE tenant_id=:t AND user_id=:u", new MapSqlParameterSource().addValue("t", t).addValue("u", childUserId));
    }

    private void bindInternal(String t, long parentUserId, long childUserId) {
        ensureHierarchyNode(t, parentUserId);
        ensureHierarchyNode(t, childUserId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", t).addValue("u", parentUserId);
        Map<String, Object> parent = jdbc.queryForMap("SELECT path,level_no FROM sangong_user_hierarchy WHERE tenant_id=:t AND user_id=:u AND is_active=1 FOR UPDATE", p);
        Map<String, Object> child = jdbc.queryForMap("SELECT user_id FROM sangong_user_hierarchy WHERE tenant_id=:t AND user_id=:u AND is_active=1 FOR UPDATE",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childUserId));
        Integer cycle = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_user_hierarchy WHERE tenant_id=:t AND user_id=:u AND path LIKE CONCAT('%/',:child,'/%')",
            new MapSqlParameterSource().addValue("t", t).addValue("u", parentUserId).addValue("child", childUserId), Integer.class);
        if (cycle != null && cycle > 0) throw new IllegalArgumentException("不能绑定自己的下级");
        double parentPct = rebatePct(t, parentUserId);
        double childPct = rebatePct(t, childUserId);
        if (parentPct < childPct) throw new IllegalArgumentException("上级返水比例不能低于下级");
        String path = String.valueOf(parent.get("path")) + childUserId + "/";
        int level = ((Number) parent.get("level_no")).intValue() + 1;
        jdbc.update("UPDATE sangong_user_hierarchy SET parent_user_id=:p,level_no=:l,path=:path,is_active=1 WHERE tenant_id=:t AND user_id=:u",
            new MapSqlParameterSource().addValue("t", t).addValue("u", childUserId).addValue("p", parentUserId).addValue("l", level).addValue("path", path));
    }

    private double rebatePct(String tenantId, long userId) {
        Double pct = jdbc.queryForObject("SELECT COALESCE(player_rebate_pct,0) FROM sangong_users WHERE tenant_id=:t AND id=:u",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("u", userId), Double.class);
        return pct == null ? 0d : pct;
    }

    private void ensureHierarchyNode(String tenantId, long userId) {
        jdbc.update("INSERT IGNORE INTO sangong_user_hierarchy(tenant_id,user_id,parent_user_id,level_no,path,is_active) " +
            "SELECT :t,id,NULL,0,CONCAT('/',id,'/'),1 FROM sangong_users WHERE tenant_id=:t AND id=:u",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("u", userId));
    }

    private void assertTenantUser(String tenantId, long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_users WHERE tenant_id=:t AND id=:u", 
            new MapSqlParameterSource().addValue("t", tenantId).addValue("u", userId), Integer.class);
        if (count == null || count == 0) throw new IllegalArgumentException("用户不存在");
    }

    private boolean isStaff(long userId, String tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_tenant_access a JOIN sangong_users u ON u.im_user_id=a.main_user_id " +
            "WHERE a.tenant_id=:t AND u.id=:u AND a.role IN ('owner','admin')",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("u", userId), Integer.class);
        return count != null && count > 0;
    }

}
