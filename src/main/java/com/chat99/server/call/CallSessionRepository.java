package com.chat99.server.call;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallSessionRepository extends JpaRepository<CallSession, String> {

    /**
     * 原子收尾：仅当尚未 ended 时写入；返回 1 表示本调用赢得 finalize，0 表示已被并发路径收尾。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallSession s SET s.endedAt = :endedAt, s.lastEventAt = :endedAt, s.status = :status, "
        + "s.updatedAt = :endedAt WHERE s.callId = :callId AND s.endedAt IS NULL")
    int markEndedIfOpen(
        @Param("callId") String callId,
        @Param("endedAt") Instant endedAt,
        @Param("status") CallSessionStatus status);

    /**
     * 仅扫描「已接听且 ended-accepted 间隔过大」的近期会话，避免 repair 全表 findAll。
     */
    @Query(value = "SELECT * FROM call_session s WHERE s.ended_at IS NOT NULL AND s.accepted_at IS NOT NULL "
        + "AND s.ended_at >= :since "
        + "AND TIMESTAMPDIFF(SECOND, s.accepted_at, s.ended_at) > 60 "
        + "ORDER BY s.ended_at DESC LIMIT 500", nativeQuery = true)
    List<CallSession> findInflatedAnsweredSessionsForRepair(@Param("since") Instant since);

    @Query("SELECT s FROM CallSession s WHERE s.endedAt IS NULL AND s.acceptedAt IS NOT NULL "
        + "AND COALESCE(s.lastEventAt, s.acceptedAt) < :cutoff")
    List<CallSession> findStaleAcceptedSessions(@Param("cutoff") Instant cutoff);

    /**
     * 未接听超时按 {@code startedAt} 判断，避免 LiveKit webhook 刷新 {@code lastEventAt}
     * 导致振铃会话永远扫不到、对方一直忙线。
     */
    @Query("SELECT s FROM CallSession s WHERE s.endedAt IS NULL AND s.acceptedAt IS NULL "
        + "AND s.startedAt < :cutoff")
    List<CallSession> findStaleUnansweredSessions(@Param("cutoff") Instant cutoff);

    @Query("SELECT COUNT(s) > 0 FROM CallSession s WHERE s.endedAt IS NULL AND s.acceptedAt IS NULL "
        + "AND s.calleeUserId = :calleeUserId "
        + "AND (s.callType IS NULL OR s.callType = 'livekit')")
    boolean existsOpenRingingForCallee(@Param("calleeUserId") String calleeUserId);

    @Query("SELECT s FROM CallSession s WHERE s.endedAt IS NULL AND s.acceptedAt IS NULL "
        + "AND s.calleeUserId = :calleeUserId "
        + "AND (s.callType IS NULL OR s.callType = 'livekit')")
    List<CallSession> findOpenRingingForCallee(@Param("calleeUserId") String calleeUserId);

    @Query("SELECT s FROM CallSession s WHERE s.endedAt IS NULL AND s.acceptedAt IS NULL "
        + "AND s.callerUserId = :callerUserId "
        + "AND (s.callType IS NULL OR s.callType = 'livekit')")
    List<CallSession> findOpenRingingForCaller(@Param("callerUserId") String callerUserId);

    /** 主播/用户作为 caller 或 callee 且尚未收尾的通话（LiveKit 与群直播互斥）。 */
    @Query("SELECT s FROM CallSession s WHERE s.endedAt IS NULL "
        + "AND (s.callerUserId = :userId OR s.calleeUserId = :userId) "
        + "ORDER BY s.startedAt DESC")
    List<CallSession> findOpenSessionsForUser(@Param("userId") String userId);
}
