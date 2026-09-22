package com.chat99.server.announcement;

import com.chat99.server.notify.SystemNotifyService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnnouncementService {

    private static final int MAX_USER_LIMIT = 50;

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementReadRepository readRepository;
    private final UserRepository userRepository;
    private final SystemNotifyService systemNotifyService;
    private final AnnouncementGlobalPushService globalPushService;
    private final AnnouncementGlobalPushProperties globalPushProps;

    public AnnouncementService(AnnouncementRepository announcementRepository,
                               AnnouncementReadRepository readRepository,
                               UserRepository userRepository,
                               SystemNotifyService systemNotifyService,
                               AnnouncementGlobalPushService globalPushService,
                               AnnouncementGlobalPushProperties globalPushProps) {
        this.announcementRepository = announcementRepository;
        this.readRepository = readRepository;
        this.userRepository = userRepository;
        this.systemNotifyService = systemNotifyService;
        this.globalPushService = globalPushService;
        this.globalPushProps = globalPushProps;
    }

    public record CreateCommand(AnnouncementType type,
                                String targetUserId,
                                String title,
                                String body,
                                String linkUrl,
                                String payloadJson,
                                Integer priority,
                                Instant expireAt,
                                String createdBy) {}

    public record UpdateCommand(String title,
                                String body,
                                String linkUrl,
                                String payloadJson,
                                Integer priority,
                                Instant expireAt,
                                String targetUserId) {}

    public record AnnouncementView(String id,
                                   AnnouncementType type,
                                   String title,
                                   String body,
                                   String linkUrl,
                                   String payloadJson,
                                   int priority,
                                   String targetUserId,
                                   AnnouncementStatus status,
                                   Instant publishAt,
                                   Instant expireAt,
                                   AnnouncementImPushStatus imPushStatus,
                                   String createdBy,
                                   Instant createdAt,
                                   Instant updatedAt) {}

    public record UserAnnouncementView(String id,
                                       AnnouncementType type,
                                       String title,
                                       String body,
                                       String linkUrl,
                                       int priority,
                                       Instant publishAt,
                                       Instant expireAt,
                                       boolean read) {}

    @Transactional
    public AnnouncementView createDraft(CreateCommand cmd) {
        validateCreate(cmd);
        Announcement a = new Announcement();
        a.setType(cmd.type());
        a.setTitle(cmd.title().trim());
        a.setBody(cmd.body());
        a.setLinkUrl(trimToNull(cmd.linkUrl()));
        a.setPayloadJson(trimToNull(cmd.payloadJson()));
        a.setPriority(cmd.priority() != null ? cmd.priority() : 0);
        a.setTargetUserId(cmd.type() == AnnouncementType.PERSONAL ? cmd.targetUserId().trim() : null);
        a.setStatus(AnnouncementStatus.DRAFT);
        a.setExpireAt(cmd.expireAt());
        a.setCreatedBy(trimToNull(cmd.createdBy()));
        return toView(announcementRepository.save(a));
    }

    @Transactional
    public AnnouncementView updateDraft(String id, UpdateCommand cmd) {
        Announcement a = requireDraft(id);
        if (cmd.title() != null) {
            if (cmd.title().isBlank()) {
                throw badRequest("INVALID_TITLE");
            }
            a.setTitle(cmd.title().trim());
        }
        if (cmd.body() != null) {
            if (cmd.body().isBlank()) {
                throw badRequest("INVALID_BODY");
            }
            a.setBody(cmd.body());
        }
        if (cmd.linkUrl() != null) {
            a.setLinkUrl(trimToNull(cmd.linkUrl()));
        }
        if (cmd.payloadJson() != null) {
            a.setPayloadJson(trimToNull(cmd.payloadJson()));
        }
        if (cmd.priority() != null) {
            a.setPriority(cmd.priority());
        }
        if (cmd.expireAt() != null) {
            a.setExpireAt(cmd.expireAt());
        }
        if (a.getType() == AnnouncementType.PERSONAL && cmd.targetUserId() != null) {
            requireUserExists(cmd.targetUserId());
            a.setTargetUserId(cmd.targetUserId().trim());
        }
        return toView(announcementRepository.save(a));
    }

    public AnnouncementView getById(String id) {
        return toView(requireExists(id));
    }

    public List<AnnouncementView> listAdmin(AnnouncementStatus status) {
        List<Announcement> rows = status != null
            ? announcementRepository.findByStatusOrderByUpdatedAtDesc(status)
            : announcementRepository.findAllByOrderByUpdatedAtDesc();
        return rows.stream().map(this::toView).toList();
    }

    @Transactional
    public AnnouncementView schedule(CreateCommand cmd, Instant publishAt) {
        validateCreate(cmd);
        if (publishAt == null || !publishAt.isAfter(Instant.now().plusSeconds(30))) {
            throw badRequest("INVALID_SCHEDULE_TIME");
        }
        Announcement a = new Announcement();
        a.setType(cmd.type());
        a.setTitle(cmd.title().trim());
        a.setBody(cmd.body());
        a.setLinkUrl(trimToNull(cmd.linkUrl()));
        a.setPayloadJson(trimToNull(cmd.payloadJson()));
        a.setPriority(cmd.priority() != null ? cmd.priority() : 0);
        a.setTargetUserId(cmd.type() == AnnouncementType.PERSONAL ? cmd.targetUserId().trim() : null);
        a.setStatus(AnnouncementStatus.SCHEDULED);
        a.setPublishAt(publishAt);
        a.setExpireAt(cmd.expireAt());
        a.setCreatedBy(trimToNull(cmd.createdBy()));
        return toView(announcementRepository.save(a));
    }

    @Transactional
    public AnnouncementView publish(String id) {
        Announcement a = requireDraft(id);
        Instant now = Instant.now();
        a.setStatus(AnnouncementStatus.PUBLISHED);
        a.setPublishAt(now);
        announcementRepository.save(a);
        executePublishPush(a);
        return toView(a);
    }

    @Transactional
    public boolean publishDueScheduled(String id) {
        Announcement a = announcementRepository.findById(id).orElse(null);
        if (a == null || a.getStatus() != AnnouncementStatus.SCHEDULED) {
            return false;
        }
        if (a.getPublishAt() == null || a.getPublishAt().isAfter(Instant.now())) {
            return false;
        }
        a.setStatus(AnnouncementStatus.PUBLISHED);
        announcementRepository.save(a);
        executePublishPush(a);
        return true;
    }

    private void executePublishPush(Announcement a) {
        if (a.getType() == AnnouncementType.PERSONAL) {
            a.setImPushStatus(null);
            announcementRepository.save(a);
            systemNotifyService.sendAnnouncement(a.getTargetUserId(), a);
        } else {
            if (globalPushProps.enabled()) {
                a.setImPushStatus(AnnouncementImPushStatus.PENDING);
            } else {
                a.setImPushStatus(AnnouncementImPushStatus.SKIPPED);
            }
            announcementRepository.save(a);
            if (globalPushProps.enabled()) {
                globalPushService.schedulePushAfterCommit(a.getId());
            }
        }
    }

    @Transactional
    public AnnouncementView revoke(String id) {
        Announcement a = requireExists(id);
        if (a.getStatus() != AnnouncementStatus.PUBLISHED) {
            throw badRequest("NOT_PUBLISHED");
        }
        a.setStatus(AnnouncementStatus.REVOKED);
        return toView(announcementRepository.save(a));
    }

    public List<UserAnnouncementView> listForUser(String userId, boolean unreadOnly, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_USER_LIMIT);
        Instant now = Instant.now();
        List<Announcement> rows = announcementRepository.findVisibleForUser(
            userId, now, PageRequest.of(0, capped));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> readIds = readRepository.findReadAnnouncementIds(
            userId, rows.stream().map(Announcement::getId).toList());
        List<UserAnnouncementView> out = new ArrayList<>();
        for (Announcement a : rows) {
            boolean read = readIds.contains(a.getId());
            if (unreadOnly && read) {
                continue;
            }
            out.add(toUserView(a, read));
        }
        return out;
    }

    public UserAnnouncementView getForUser(String userId, String id) {
        Announcement a = announcementRepository.findVisibleForUserById(id, userId, Instant.now())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        boolean read = readRepository.existsByIdUserIdAndIdAnnouncementId(userId, id);
        return toUserView(a, read);
    }

    @Transactional
    public void markRead(String userId, String id) {
        announcementRepository.findVisibleForUserById(id, userId, Instant.now())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        if (readRepository.existsByIdUserIdAndIdAnnouncementId(userId, id)) {
            return;
        }
        readRepository.save(new AnnouncementRead(userId, id));
    }

    private void validateCreate(CreateCommand cmd) {
        if (cmd.type() == null) {
            throw badRequest("INVALID_TYPE");
        }
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw badRequest("INVALID_TITLE");
        }
        if (cmd.body() == null || cmd.body().isBlank()) {
            throw badRequest("INVALID_BODY");
        }
        if (cmd.type() == AnnouncementType.PERSONAL) {
            if (cmd.targetUserId() == null || cmd.targetUserId().isBlank()) {
                throw badRequest("TARGET_USER_REQUIRED");
            }
            requireUserExists(cmd.targetUserId());
        } else if (cmd.targetUserId() != null && !cmd.targetUserId().isBlank()) {
            throw badRequest("TARGET_USER_NOT_ALLOWED");
        }
    }

    private void requireUserExists(String userId) {
        if (!userRepository.existsByUserId(userId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND");
        }
    }

    private Announcement requireExists(String id) {
        return announcementRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    private Announcement requireDraft(String id) {
        Announcement a = requireExists(id);
        if (a.getStatus() != AnnouncementStatus.DRAFT) {
            throw badRequest("NOT_DRAFT");
        }
        return a;
    }

    private AnnouncementView toView(Announcement a) {
        return new AnnouncementView(
            a.getId(), a.getType(), a.getTitle(), a.getBody(), a.getLinkUrl(), a.getPayloadJson(),
            a.getPriority(), a.getTargetUserId(), a.getStatus(), a.getPublishAt(), a.getExpireAt(),
            a.getImPushStatus(), a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private UserAnnouncementView toUserView(Announcement a, boolean read) {
        return new UserAnnouncementView(
            a.getId(), a.getType(), a.getTitle(), a.getBody(), a.getLinkUrl(),
            a.getPriority(), a.getPublishAt(), a.getExpireAt(), read);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
