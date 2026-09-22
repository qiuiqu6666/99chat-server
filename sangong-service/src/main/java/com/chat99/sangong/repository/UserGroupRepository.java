package com.chat99.sangong.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class UserGroupRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public UserGroupRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("SELECT * FROM sangong_user_groups ORDER BY id", new MapSqlParameterSource());
    }
    public Optional<Map<String, Object>> findById(long id) {
        var list = jdbc.queryForList("SELECT * FROM sangong_user_groups WHERE id=:id", new MapSqlParameterSource("id", id));
        return list.stream().findFirst();
    }
    public Optional<Map<String, Object>> findByCode(String code) {
        var list = jdbc.queryForList("SELECT * FROM sangong_user_groups WHERE code=:c", new MapSqlParameterSource("c", code));
        return list.stream().findFirst();
    }

    /** 查找某 IM 用户作为代理的分组（不跨租户隔离：由 sangong_users.group_id 推导）。 */
    public Optional<Map<String, Object>> findByAgent(String agentImUserId) {
        var list = jdbc.queryForList(
            "SELECT * FROM sangong_user_groups WHERE agent_im_user_id=:a AND is_agent_group=1 ORDER BY id LIMIT 1",
            new MapSqlParameterSource("a", agentImUserId));
        return list.stream().findFirst();
    }

    public long insert(String code, String name, String note, boolean isActive) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO sangong_user_groups(code,name,note,is_active,created_at,updated_at) VALUES(:c,:n,:o,:a,NOW(),NOW())",
            new MapSqlParameterSource().addValue("c", code).addValue("n", name).addValue("o", note).addValue("a", isActive),
            kh, new String[]{"id"});
        return kh.getKey().longValue();
    }

    /**
     * 升级版 insert：写入代理分组所需全部字段。
     * 若 agentImUserId == null 或 blank，则默认空（群主后续可改）。
     */
    public long insertAgentGroup(String code, String name, String note, boolean isActive,
                                 String agentImUserId, double maxRebatePct) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sangong_user_groups(code,name,agent_im_user_id,max_rebate_pct,is_agent_group,
              note,is_active,created_at,updated_at)
            VALUES(:c,:n,:a,:mr,1,:o,:act,NOW(),NOW())
            """,
            new MapSqlParameterSource()
                .addValue("c", code)
                .addValue("n", name)
                .addValue("a", agentImUserId == null ? "" : agentImUserId)
                .addValue("mr", Math.max(0d, maxRebatePct))
                .addValue("o", note == null ? "" : note)
                .addValue("act", isActive),
            kh, new String[]{"id"});
        return kh.getKey().longValue();
    }

    public void update(long id, String code, String name, String note, Boolean isActive) {
        jdbc.update("""
            UPDATE sangong_user_groups SET code=COALESCE(:c,code), name=COALESCE(:n,name),
            note=COALESCE(:o,note), is_active=COALESCE(:a,is_active), updated_at=NOW() WHERE id=:id
            """, new MapSqlParameterSource().addValue("c", code).addValue("n", name)
                .addValue("o", note).addValue("a", isActive).addValue("id", id));
    }

    /**
     * 更新代理分组字段（owner 操作；agent_im_user_id / max_rebate_pct / is_agent_group）。
     * 入参为 null 表示不改。
     */
    public void updateAgentFields(long id, String agentImUserId, Double maxRebatePct, Boolean isAgentGroup) {
        jdbc.update("""
            UPDATE sangong_user_groups SET
              agent_im_user_id=COALESCE(:a, agent_im_user_id),
              max_rebate_pct=COALESCE(:mr, max_rebate_pct),
              is_agent_group=COALESCE(:ag, is_agent_group),
              updated_at=NOW()
            WHERE id=:id
            """,
            new MapSqlParameterSource()
                .addValue("a", agentImUserId)
                .addValue("mr", maxRebatePct)
                .addValue("ag", isAgentGroup)
                .addValue("id", id));
    }

    /**
     * 计算某分组内的本租户玩家数。sangong_user_groups 是跨租户共用的模板表，但挂上来的玩家
     * 仍按 tenant 隔离；不加 tenant 过滤会让 A 群群主看到 B 群挂在同一分组的玩家数。
     */
    public int countUsers(long groupId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sangong_users WHERE group_id=:g AND tenant_id=:t",
            new MapSqlParameterSource().addValue("g", groupId)
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require()),
            Integer.class);
        return c == null ? 0 : c;
    }
}