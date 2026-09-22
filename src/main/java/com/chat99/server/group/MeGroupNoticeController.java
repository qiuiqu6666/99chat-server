package com.chat99.server.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeGroupNoticeController {

    private final GroupJoinService joinService;
    private final GroupSystemNoticeService noticeService;
    private final MeGroupNoticeChangesService changesService;

    public MeGroupNoticeController(GroupJoinService joinService,
                                   GroupSystemNoticeService noticeService,
                                   MeGroupNoticeChangesService changesService) {
        this.joinService = joinService;
        this.noticeService = noticeService;
        this.changesService = changesService;
    }

    @GetMapping("/me/join-applications")
    public GroupJoinService.MyJoinApplicationListResponse listMyJoinApplications(
        Authentication auth,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset,
        @RequestParam(defaultValue = "true") boolean includeHandled,
        @RequestParam(required = false) String status) {
        return joinService.listMyApplications(
            (String) auth.getPrincipal(), limit, offset, includeHandled, status);
    }

    /**
     * v2 协议：群通知变更快照（按 revision）。
     */
    @GetMapping("/me/group-notices/snapshot")
    public MeGroupNoticeChangesService.SnapshotResponse snapshot(
            Authentication auth,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long snapshotRevision) {
        return changesService.snapshot(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit, snapshotRevision);
    }

    /**
     * v2 协议：群通知变更增量（按 opaqueCursor）。
     */
    @GetMapping("/me/group-notices/changes/v2")
    public MeGroupNoticeChangesService.ChangesResponse changesV2(
            Authentication auth,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return changesService.changes(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit);
    }

    @GetMapping("/me/group-notices")
    public GroupSystemNoticeService.GroupSystemNoticeListResponse listGroupNotices(
        Authentication auth,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset,
        @RequestParam(defaultValue = "0") long since,
        @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return noticeService.listForUser(
            (String) auth.getPrincipal(), limit, offset, since, unreadOnly);
    }

    /**
     * 群系统通知 Inbox 游标增量。
     * {@code since_seq} 过旧返回 410 {@code CURSOR_EXPIRED}，客户端应回退 {@code GET /me/group-notices} 快照。
     */
    @GetMapping("/me/group-notices/changes")
    public MeGroupNoticeChangesService.NoticeChangesResponse listGroupNoticeChanges(
        Authentication auth,
        @RequestParam(name = "since_seq", defaultValue = "0") long sinceSeq,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return changesService.listChangesBySeq((String) auth.getPrincipal(), sinceSeq, limit);
    }

    public record MarkGroupNoticesReadRequest(@NotNull Long readAt) {}

    public record DeleteMyJoinApplicationsBody(@NotEmpty List<Long> applicationIds) {}

    public record DeleteMyGroupNoticesBody(@NotEmpty List<String> noticeIds) {}

    @PutMapping("/me/group-notices/read")
    public void markGroupNoticesRead(Authentication auth,
                                     @Valid @RequestBody MarkGroupNoticesReadRequest body) {
        noticeService.markRead((String) auth.getPrincipal(), body.readAt());
    }

    @DeleteMapping("/me/group-notices/{noticeId}")
    public GroupSystemNoticeService.DeleteNoticesResponse deleteMyGroupNotice(
        Authentication auth,
        @PathVariable String noticeId) {
        return noticeService.dismissNotice((String) auth.getPrincipal(), noticeId);
    }

    @DeleteMapping("/me/group-notices")
    public GroupSystemNoticeService.DeleteNoticesResponse deleteMyGroupNotices(
        Authentication auth,
        @RequestBody(required = false) DeleteMyGroupNoticesBody body) {
        List<String> ids = body == null ? List.of() : body.noticeIds();
        return noticeService.dismissNotices((String) auth.getPrincipal(), ids);
    }

    @DeleteMapping("/me/join-applications/{applicationId}")
    public GroupJoinService.DeleteApplicationsResponse deleteMyJoinApplication(
        Authentication auth,
        @PathVariable long applicationId) {
        return joinService.deleteMyApplication((String) auth.getPrincipal(), applicationId);
    }

    @DeleteMapping("/me/join-applications")
    public GroupJoinService.DeleteApplicationsResponse deleteMyJoinApplications(
        Authentication auth,
        @RequestBody(required = false) DeleteMyJoinApplicationsBody body,
        @RequestParam(required = false) String status) {
        List<Long> ids = body == null ? List.of() : body.applicationIds();
        return joinService.deleteMyApplications((String) auth.getPrincipal(), ids, status);
    }
}
