package com.chat99.server.group;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ImGroupIdRemapLookup {

    private final JdbcTemplate jdbc;

    public ImGroupIdRemapLookup(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Optional<String> findDstBySrc(String srcGroupId) {
        if (srcGroupId == null || srcGroupId.isBlank()) {
            return Optional.empty();
        }
        List<String> rows = jdbc.query(
            "SELECT dst_group_id FROM im_group_id_remap WHERE src_group_id = ? LIMIT 1",
            (rs, rowNum) -> rs.getString(1),
            srcGroupId.trim());
        return rows.stream().findFirst();
    }
}
