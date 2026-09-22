package com.chat99.sangong.migrate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时自动执行 scripts/migrations/*.sql 下的迁移脚本，按文件名排序。
 * 已执行的脚本写入 sangong_schema_version 表，启动会跳过。
 *
 * 脚本路径同时支持 classpath:db/migration/ 和文件系统 ./scripts/migrations/，
 * 优先使用文件系统（开发期方便覆盖）。
 */
@Component
public class AgentMigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentMigrationRunner.class);

    private static final String VERSION_TABLE = "sangong_schema_version";
    private static final List<String> SEARCH_DIRS = Arrays.asList(
        "./scripts/migrations",
        "scripts/migrations"
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public AgentMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        try {
            // 触发 Hikari lazy 初始化（如果不先 getConnection，spring.datasource 是 lazy bean）
            warmupDataSource();
            ensureVersionTable();
            List<String> files = discoverMigrationFiles();
            if (files.isEmpty()) {
                log.info("migration: no scripts found");
                return;
            }
            Collections.sort(files);
            int applied = 0;
            for (String f : files) {
                if (isApplied(f)) {
                    continue;
                }
                String sql = readFile(f);
                if (sql == null || sql.isBlank()) {
                    continue;
                }
                log.info("migration: applying {} ({} bytes)", f, sql.length());
                applyScript(f, sql);
                applied++;
            }
            log.info("migration: done ({} script(s) applied)", applied);
        } catch (Exception e) {
            log.error("migration: failed - {}", e.getMessage(), e);
            // 不抛出，避免阻塞服务启动；让运维通过日志排查
        }
    }

    private void ensureVersionTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " (" +
            "filename VARCHAR(64) NOT NULL, " +
            "applied_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP, " +
            "PRIMARY KEY (filename)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /**
     * 主动触发一次连接，避免 Spring Boot 3 lazy datasource 还没初始化就被调用。
     * Hikari 默认 lazy，直到第一次 getConnection() 才真正建连。
     */
    private void warmupDataSource() {
        try (var conn = dataSource.getConnection()) {
            log.debug("migration: datasource warmed up ({})", conn.getMetaData().getURL());
        } catch (Exception e) {
            log.warn("migration: datasource warmup failed: {}", e.getMessage());
        }
    }

    private boolean isApplied(String filename) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + VERSION_TABLE + " WHERE filename = ?",
            Integer.class, filename);
        return c != null && c > 0;
    }

    private void applyScript(String filename, String sql) {
        // 多语句 SQL；mysql-connector-j 默认允许在配置了 allowMultiQueries=true 时执行
        jdbc.execute(sql);
        jdbc.update(
            "INSERT INTO " + VERSION_TABLE + "(filename) VALUES(?) " +
            "ON DUPLICATE KEY UPDATE applied_at=CURRENT_TIMESTAMP",
            filename);
    }

    private List<String> discoverMigrationFiles() {
        // 先试文件系统（覆盖优先级最高）
        for (String dir : SEARCH_DIRS) {
            java.io.File d = new java.io.File(dir);
            if (d.isDirectory()) {
                String[] files = d.list((f, name) -> name.endsWith(".sql"));
                if (files != null && files.length > 0) {
                    return Arrays.stream(files).sorted().collect(Collectors.toList());
                }
            }
        }
        // 再试 classpath
        try {
            java.io.File cp = new java.io.File(getClass().getClassLoader()
                .getResource("db/migration").toURI());
            if (cp.isDirectory()) {
                String[] files = cp.list((f, name) -> name.endsWith(".sql"));
                if (files != null) {
                    return Arrays.stream(files).sorted().collect(Collectors.toList());
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private String readFile(String filename) {
        // 先读文件系统
        for (String dir : SEARCH_DIRS) {
            java.io.File f = new java.io.File(dir, filename);
            if (f.isFile()) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    return readAll(in);
                } catch (Exception e) {
                    log.warn("migration: read file {} failed: {}", f, e.getMessage());
                }
            }
        }
        // 再读 classpath
        Resource res = new ClassPathResource("db/migration/" + filename);
        if (res.exists()) {
            try (InputStream in = res.getInputStream()) {
                return readAll(in);
            } catch (Exception e) {
                log.warn("migration: read classpath {} failed: {}", filename, e.getMessage());
            }
        }
        // 兼容：开发期脚本可能在 working dir 的 scripts/migrations 下，但路径未命中
        Resource fs = new FileSystemResource("scripts/migrations/" + filename);
        if (fs.exists()) {
            try (InputStream in = fs.getInputStream()) {
                return readAll(in);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                // 跳过纯 SQL 注释行（行首 -- 或 #）
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}