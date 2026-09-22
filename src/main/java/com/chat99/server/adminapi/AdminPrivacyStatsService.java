package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class AdminPrivacyStatsService {

    private static final int ACTIVE = 1;
    /**
     * 子表 user_id 为 general_ci，users 为 unicode_ci，聚合时统一排序规则。
     * 先一次性聚合再关联，避免按每个用户重复扫描完整的通讯录和相册表。
     */
    private static final String USER_STATS_JOINS = """
        LEFT JOIN (
          SELECT user_id COLLATE utf8mb4_unicode_ci AS user_id, COUNT(*) AS contact_count
          FROM user_contact_item
          WHERE status = :st
          GROUP BY user_id COLLATE utf8mb4_unicode_ci
        ) c ON c.user_id = u.user_id
        LEFT JOIN (
          SELECT user_id COLLATE utf8mb4_unicode_ci AS user_id, COUNT(*) AS album_count
          FROM user_photo
          WHERE status = :st
          GROUP BY user_id COLLATE utf8mb4_unicode_ci
        ) p ON p.user_id = u.user_id
        """;

    private final EntityManager entityManager;

    public AdminPrivacyStatsService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public PrivacySummaryResponse summary() {
        Number totalUsers = (Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM users")
            .getSingleResult();
        Number usersWithContacts = (Number) entityManager
            .createNativeQuery(
                "SELECT COUNT(DISTINCT user_id) FROM user_contact_item WHERE status = :st")
            .setParameter("st", ACTIVE)
            .getSingleResult();
        Number totalContacts = (Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM user_contact_item WHERE status = :st")
            .setParameter("st", ACTIVE)
            .getSingleResult();
        Number usersWithAlbum = (Number) entityManager
            .createNativeQuery(
                "SELECT COUNT(DISTINCT user_id) FROM user_photo WHERE status = :st")
            .setParameter("st", ACTIVE)
            .getSingleResult();
        Number totalAlbumItems = (Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM user_photo WHERE status = :st")
            .setParameter("st", ACTIVE)
            .getSingleResult();
        Number totalPhotos = (Number) entityManager
            .createNativeQuery("""
                SELECT COUNT(*) FROM user_photo
                WHERE status = :st
                  AND (media_type IS NULL OR media_type = 'IMAGE')
                  AND (mime_type IS NULL OR LOWER(mime_type) NOT LIKE 'video/%')
                """)
            .setParameter("st", ACTIVE)
            .getSingleResult();
        Number totalVideos = (Number) entityManager
            .createNativeQuery("""
                SELECT COUNT(*) FROM user_photo
                WHERE status = :st
                  AND (
                    media_type = 'VIDEO'
                    OR (mime_type IS NOT NULL AND LOWER(mime_type) LIKE 'video/%')
                  )
                """)
            .setParameter("st", ACTIVE)
            .getSingleResult();

        return new PrivacySummaryResponse(
            totalUsers.longValue(),
            usersWithContacts.longValue(),
            totalContacts.longValue(),
            usersWithAlbum.longValue(),
            totalAlbumItems.longValue(),
            totalPhotos.longValue(),
            totalVideos.longValue());
    }

    @SuppressWarnings("unchecked")
    public PrivacyUserStatsListResponse listUsers(String keyword, String sort, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        String orderBy = resolveSort(sort);

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (kw != null) {
            where.append(" AND (LOWER(u.user_id) LIKE :kw OR LOWER(u.nickname) LIKE :kw ");
            where.append(" OR u.phone LIKE :kwPlain) ");
        }

        String countSql = "SELECT COUNT(*) FROM users u" + where;

        Query countQuery = entityManager.createNativeQuery(countSql);
        if (kw != null) {
            countQuery.setParameter("kw", "%" + kw + "%");
            countQuery.setParameter("kwPlain", "%" + keyword.trim() + "%");
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();

        String listSql = """
            SELECT u.user_id, u.nickname,
              COALESCE(c.contact_count, 0) AS contact_count,
              COALESCE(p.album_count, 0) AS album_count
            FROM users u
            """ + USER_STATS_JOINS + where
            + " ORDER BY " + orderBy + " LIMIT :lim OFFSET :off";

        Query listQuery = entityManager.createNativeQuery(listSql);
        listQuery.setParameter("st", ACTIVE);
        if (kw != null) {
            listQuery.setParameter("kw", "%" + kw + "%");
            listQuery.setParameter("kwPlain", "%" + keyword.trim() + "%");
        }
        listQuery.setParameter("lim", safeSize);
        listQuery.setParameter("off", (long) (safePage - 1) * safeSize);

        List<Object[]> rows = listQuery.getResultList();
        List<PrivacyUserStatsItem> items = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            items.add(new PrivacyUserStatsItem(
                String.valueOf(row[0]),
                row[1] == null ? "" : String.valueOf(row[1]),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue()));
        }
        return new PrivacyUserStatsListResponse(items, total, safePage, safeSize, safePage * safeSize < total);
    }

    private static String resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "contact_count DESC, album_count DESC, u.user_id ASC";
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "album_count_desc" -> "album_count DESC, contact_count DESC, u.user_id ASC";
            case "contact_count_asc" -> "contact_count ASC, u.user_id ASC";
            case "album_count_asc" -> "album_count ASC, u.user_id ASC";
            case "user_id_asc" -> "u.user_id ASC";
            default -> "contact_count DESC, album_count DESC, u.user_id ASC";
        };
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrivacySummaryResponse(
        long totalUsers,
        long usersWithContacts,
        long totalContactEntries,
        long usersWithAlbum,
        long totalAlbumItems,
        long totalPhotos,
        long totalVideos) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrivacyUserStatsItem(
        String userUid,
        String nickname,
        long contactCount,
        long albumCount) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrivacyUserStatsListResponse(
        List<PrivacyUserStatsItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}
}
