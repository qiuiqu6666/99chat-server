/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.call;

import com.chat99.server.call.CallRecentService;
import com.chat99.server.call.CallRecordResult;
import com.chat99.server.call.CallRecordUser;
import com.chat99.server.call.CallRecordUserRepository;
import com.chat99.server.call.CallSession;
import com.chat99.server.call.CallSessionRepository;
import com.chat99.server.call.TrtcCallResultMapper;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CallRecentService {
    private static final int MAX_PAGE_SIZE = 50;
    private final CallRecordUserRepository recordRepository;
    private final CallSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public CallRecentService(CallRecordUserRepository recordRepository, CallSessionRepository sessionRepository, UserRepository userRepository) {
        this.recordRepository = recordRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    public RecentPageView listRecent(String userId, String filter, int page, int pageSize) {
        Page<CallRecordUser> records;
        int size = Math.min(Math.max(pageSize, 1), 50);
        int p = Math.max(page, 0);
        PageRequest pageable = PageRequest.of((int)p, (int)size);
        if ("missed".equalsIgnoreCase(filter)) {
            records = this.recordRepository.findByUserIdAndDeletedFalseAndResultOrderByOccurredAtDescIdDesc(userId, CallRecordResult.MISSED, (Pageable)pageable);
        } else if ("all".equalsIgnoreCase(filter) || filter == null || filter.isBlank()) {
            records = this.recordRepository.findByUserIdAndDeletedFalseOrderByOccurredAtDescIdDesc(userId, (Pageable)pageable);
        } else {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_FILTER");
        }
        List<RecentItemView> items = records.getContent().stream().map(r -> this.toView((CallRecordUser)r)).toList();
        return new RecentPageView(items, p, size, records.getTotalElements());
    }

    public Optional<RecentItemView> findRecentItem(String userId, String callId) {
        if (userId == null || userId.isBlank() || callId == null || callId.isBlank()) {
            return Optional.empty();
        }
        return this.recordRepository.findByCallIdAndUserId(callId, userId).filter(r -> !r.isDeleted()).map(this::toView);
    }

    /** 权威会话快照，允许振铃阶段尚未生成通话记录时仍可恢复气泡状态。 */
    @Transactional(readOnly = true)
    public CallStatusView getStatus(String userId, String callId) {
        String normalizedCallId = callId == null ? null : callId.trim();
        if (normalizedCallId == null || normalizedCallId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CALL_ID");
        }
        CallSession session = sessionRepository.findById(normalizedCallId)
            .filter(s -> userId.equals(s.getCallerUserId()) || userId.equals(s.getCalleeUserId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND"));
        CallRecordResult result = recordRepository.findByCallIdAndUserId(normalizedCallId, userId)
            .map(CallRecordUser::getResult)
            .orElse(null);
        int durationSec = result == CallRecordResult.ANSWERED && session.getAcceptedAt() != null
            && session.getEndedAt() != null
            ? Math.max(0, (int) (session.getEndedAt().getEpochSecond() - session.getAcceptedAt().getEpochSecond()))
            : 0;
        return new CallStatusView(
            session.getCallId(), session.getRoomId(), session.getCallType(), session.getMediaType(),
            session.getCallerUserId(), session.getCalleeUserId(), session.getStatus(), result,
            session.getStartedAt(), session.getAcceptedAt(), session.getEndedAt(), durationSec);
    }

    @Transactional
    public void deleteOne(String userId, String callId) {
        CallRecordUser record = this.recordRepository.findByCallIdAndUserId(callId, userId).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "CALL_RECORD_NOT_FOUND"));
        if (record.isDeleted()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "CALL_RECORD_NOT_FOUND");
        }
        record.setDeleted(true);
        this.recordRepository.save(record);
    }

    @Transactional
    public DeleteAllResult deleteAll(String userId, String filter) {
        int deleted;
        if ("missed".equalsIgnoreCase(filter)) {
            deleted = this.recordRepository.softDeleteMissed(userId, CallRecordResult.MISSED, Instant.now());
        } else if ("all".equalsIgnoreCase(filter) || filter == null || filter.isBlank()) {
            deleted = this.recordRepository.softDeleteAll(userId, Instant.now());
        } else {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_FILTER");
        }
        return new DeleteAllResult(true, deleted);
    }

    private RecentItemView toView(CallRecordUser r) {
        User peer;
        String calleeUserId;
        CallSession session = this.sessionRepository.findById(r.getCallId()).orElse(null);
        String mediaType = session == null || session.getMediaType() == null ? "audio" : session.getMediaType();
        boolean selfIsCaller = "outgoing".equalsIgnoreCase(r.getDirection());
        String callerUserId = session != null ? session.getCallerUserId() : null;
        String string = calleeUserId = session != null ? session.getCalleeUserId() : null;
        if (callerUserId == null) {
            String string2 = callerUserId = selfIsCaller ? r.getUserId() : r.getPeerUserId();
        }
        if (calleeUserId == null) {
            calleeUserId = selfIsCaller ? r.getPeerUserId() : r.getUserId();
        }
        String operatorUserId = TrtcCallResultMapper.resolveOperatorUserId((CallRecordResult)r.getResult(), (String)callerUserId, (String)calleeUserId);
        String peerName = null;
        String peerAvatar = null;
        if (r.getPeerUserId() != null && (peer = this.userRepository.findByUserId(r.getPeerUserId()).orElse(null)) != null) {
            peerName = peer.getNickname();
            peerAvatar = peer.getAvatarUrl();
        }
        return new RecentItemView(r.getCallId(), r.getPeerUserId(), peerName, peerAvatar, mediaType, r.getDirection(), r.getResult().name().toLowerCase(), r.getDurationSec(), r.getOccurredAt().toEpochMilli(), callerUserId, operatorUserId);
    }





    public record RecentItemView(String callId, String peerUserId, String peerName, String peerAvatar, String mediaType, String direction, String result, int durationSec, long occurredAt, String callerUserId, String operatorUserId) {}

    public record RecentPageView(List<CallRecentService.RecentItemView> items, int page, int pageSize, long total) {}

    public record CallStatusView(
        String callId, String roomName, String callType, String mediaType,
        String callerUserId, String calleeUserId, CallSessionStatus status, CallRecordResult result,
        Instant startedAt, Instant acceptedAt, Instant endedAt, int durationSec) {}

    public record DeleteAllResult(boolean ok, int deleted) {}
}
