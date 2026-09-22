package com.chat99.server.moments;

import com.chat99.server.auth.AuthProperties;
import com.chat99.server.realtime.MomentRealtimePublisher;
import com.chat99.server.realtime.MomentRealtimePublisher.MomentChangedCommittedEvent;
import com.chat99.server.moments.MomentEnums.MediaType;
import com.chat99.server.moments.MomentEnums.NotificationType;
import com.chat99.server.moments.MomentEnums.Visibility;
import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MomentService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_COMMENT_LENGTH = 500;
    private static final int MAX_MEDIA_COUNT = 9;
    private static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024;
    private static final int FEED_FETCH_MULTIPLIER = 3;
    private static final int MAX_FEED_FETCH_ROUNDS = 5;
    private static final int PREVIEW_LIKES = 8;
    private static final int PREVIEW_COMMENTS = 2;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime", "video/x-m4v");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov", "m4v");

    private final MomentRepository momentRepository;
    private final MomentMediaRepository mediaRepository;
    private final MomentLikeRepository likeRepository;
    private final MomentCommentRepository commentRepository;
    private final MomentNotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final UserFriendRepository friendRepository;
    private final UserFriendService friendService;
    private final MomentSettingsService settingsService;
    private final MomentRealtimePublisher momentRealtimePublisher;
    private final AuthProperties authProps;
    private final OssClient oss;
    private final ImageProcessor imageProcessor;

    public MomentService(MomentRepository momentRepository,
                         MomentMediaRepository mediaRepository,
                         MomentLikeRepository likeRepository,
                         MomentCommentRepository commentRepository,
                         MomentNotificationRepository notificationRepository,
                         UserRepository userRepository,
                         UserFriendRepository friendRepository,
                         UserFriendService friendService,
                         MomentSettingsService settingsService,
                         MomentRealtimePublisher momentRealtimePublisher,
                         AuthProperties authProps,
                         OssClient oss,
                         ImageProcessor imageProcessor) {
        this.momentRepository = momentRepository;
        this.mediaRepository = mediaRepository;
        this.likeRepository = likeRepository;
        this.commentRepository = commentRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.friendService = friendService;
        this.settingsService = settingsService;
        this.momentRealtimePublisher = momentRealtimePublisher;
        this.authProps = authProps;
        this.oss = oss;
        this.imageProcessor = imageProcessor;
    }

    public record UserSnapshot(String userId, String nickname, String avatarUrl, String remark) {}

    public record UserMomentSnapshot(String userId, String nickname, String avatarUrl, String remark,
                                     Integer visibleRangeDays) {}

    public record LikePreviewView(UserSnapshot user, Long createdAt) {}

    public record MediaView(String mediaId, String type, String url, String thumbUrl, Integer width, Integer height,
                            Integer durationSec, Long sizeBytes) {}

    public record CommentView(String commentId, UserSnapshot author, String replyToCommentId,
                              UserSnapshot replyToUser, String text, Long createdAt, boolean canDelete) {}

    public record MomentItem(String momentId, UserSnapshot author, String text, List<MediaView> mediaList,
                             String location, String visibility, List<String> visibleUserIds, Long createdAt,
                             Long updatedAt, boolean likedByMe, long likeCount, List<LikePreviewView> likesPreview,
                             long commentCount, List<CommentView> commentsPreview, Boolean canDelete) {}

    public record MomentDetail(String momentId, UserSnapshot author, String text, List<MediaView> mediaList,
                               String location, String visibility, Long createdAt, Long updatedAt,
                               boolean likedByMe, long likeCount, List<LikePreviewView> likes,
                               long commentCount, List<CommentView> comments, boolean canDelete) {}

    public record PageView<T>(List<T> items, String nextCursor, boolean hasMore) {}

    public record UserMomentPage(Integer visibleRangeDays, UserMomentSnapshot user, List<MomentItem> items,
                                 String nextCursor, boolean hasMore) {}

    public record PublishRequest(String text, List<String> mediaIds, String location, String visibility,
                                 List<String> visibleUserIds) {}

    public record DeleteMomentResult(String momentId, boolean deleted) {}

    public record LikeResult(String momentId, boolean likedByMe, long likeCount, List<LikePreviewView> likesPreview) {}

    public record CommentRequest(String text, String replyToCommentId) {}

    public record CommentCreateResult(String momentId, long commentCount, CommentView comment) {}

    public record CommentDeleteResult(String momentId, String commentId, boolean deleted, long commentCount) {}

    public record NotificationPreviewMedia(String type, String thumbUrl) {}

    public record NotificationView(String notificationId, String type, UserSnapshot actor, String momentId,
                                   UserSnapshot momentAuthor, String momentText,
                                   NotificationPreviewMedia momentPreviewMedia, CommentView comment,
                                   UserSnapshot replyToUser, Long createdAt, boolean read) {}

    public record NotificationPage(List<NotificationView> items, String nextCursor, boolean hasMore,
                                   long unreadCount) {}

    public record NotificationReadRequest(List<String> notificationIds, Boolean readAll) {}

    public record NotificationReadResult(int updatedCount, long unreadCount) {}

    public record CoverUploadResult(String coverUrl) {}

    @Transactional(readOnly = true)
    public PageView<MomentItem> feed(String viewerUserId, String cursor, Integer pageSize) {
        int size = normalizePageSize(pageSize);
        Set<String> hiddenAuthors = settingsService.getHiddenAuthorIds(viewerUserId);
        List<String> authorIds = visibleAuthorIds(viewerUserId).stream()
            .filter(id -> !hiddenAuthors.contains(id))
            .toList();
        if (authorIds.isEmpty()) {
            return new PageView<>(List.of(), null, false);
        }
        return fetchFilteredMomentPage(viewerUserId, authorIds, null, parseCursor(cursor), size);
    }

    @Transactional(readOnly = true)
    public UserMomentPage userMoments(String viewerUserId, String targetUserId, String cursor, Integer pageSize) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw badRequest("MOMENT_NOT_FOUND");
        }
        requireCanViewUser(viewerUserId, targetUserId);
        User target = requireActiveUser(targetUserId);
        int visibleRangeDays = settingsService.getVisibleRangeDays(targetUserId);
        int size = normalizePageSize(pageSize);
        Instant minCreatedAt = minCreatedAtForRange(visibleRangeDays, viewerUserId, targetUserId);
        PageView<MomentItem> page = fetchFilteredMomentPage(viewerUserId, List.of(targetUserId), minCreatedAt,
            parseCursor(cursor), size);
        UserMomentSnapshot user = userMomentSnapshot(viewerUserId, target, visibleRangeDays);
        return new UserMomentPage(visibleRangeDays, user, page.items(), page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public MomentDetail detail(String viewerUserId, String momentId) {
        Moment moment = requireVisibleMoment(viewerUserId, momentId);
        return toDetail(viewerUserId, moment);
    }

    @Transactional
    public MediaView uploadMedia(String userId, MultipartFile file, String typeRaw, String clientMediaId)
        throws IOException {
        MediaType type = parseMediaType(typeRaw);
        if (clientMediaId != null && !clientMediaId.isBlank()) {
            Optional<MomentMedia> existing = mediaRepository.findByOwnerUserIdAndClientMediaId(
                userId, clientMediaId.trim());
            if (existing.isPresent()) {
                return toMediaView(existing.get());
            }
        }
        if (file == null || file.isEmpty()) {
            throw badRequest("UPLOAD_TYPE_NOT_ALLOWED");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }

        String mediaId = "mom_media_" + shortId();
        UploadedMedia uploaded = type == MediaType.IMAGE
            ? uploadImage(mediaId, file)
            : uploadVideo(mediaId, file);

        MomentMedia row = new MomentMedia();
        row.setMediaId(mediaId);
        row.setOwnerUserId(userId);
        row.setType(type);
        row.setUrl(uploaded.url());
        row.setThumbUrl(uploaded.thumbUrl());
        row.setObjectKey(uploaded.objectKey());
        row.setThumbObjectKey(uploaded.thumbObjectKey());
        row.setWidth(uploaded.width());
        row.setHeight(uploaded.height());
        row.setDurationSec(uploaded.durationSec());
        row.setSizeBytes(file.getSize());
        row.setClientMediaId(blankToNull(clientMediaId));
        mediaRepository.save(row);
        return toMediaView(row);
    }

    @Transactional
    public CoverUploadResult uploadCover(String userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw badRequest("UPLOAD_TYPE_NOT_ALLOWED");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        try {
            if (file.getSize() > IMAGE_MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_FILE_TOO_LARGE");
            }
            if (!isAllowed(file, IMAGE_TYPES, IMAGE_EXT)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UPLOAD_TYPE_NOT_ALLOWED");
            }
            byte[] raw = file.getBytes();
            BufferedImage img = imageProcessor.decode(raw);
            String ext = imageExtension(file);
            String base = "moments/cover/" + userId + "/";
            byte[] thumb = imageProcessor.resizeKeepAspect(img, 1080, 85);
            String objectKey = base + "origin." + ext;
            String url = oss.putBytes(objectKey, raw, contentTypeForExt(ext));
            oss.putBytes(base + "thumb.jpg", thumb, "image/jpeg");
            return new CoverUploadResult(url);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MOMENT_COVER_UPLOAD_FAILED");
        }
    }

    @Transactional
    public MomentItem publish(String userId, PublishRequest req, String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        if (key != null) {
            Optional<Moment> existing = momentRepository.findByAuthorUserIdAndIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                Moment existingMoment = existing.get();
                Map<String, Set<String>> visibleUsers = settingsService.getVisibleUsersByMomentIds(
                    List.of(existingMoment.getMomentId()));
                return toItem(userId, existingMoment, contextFor(List.of(existingMoment), userId, visibleUsers));
            }
        }
        String text = normalizeText(req == null ? null : req.text());
        List<String> mediaIds = normalizeIds(req == null ? null : req.mediaIds());
        if ((text == null || text.isBlank()) && mediaIds.isEmpty()) {
            throw badRequest("MOMENT_EMPTY_CONTENT");
        }
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw badRequest("MOMENT_TEXT_TOO_LONG");
        }
        if (mediaIds.size() > MAX_MEDIA_COUNT) {
            throw badRequest("MOMENT_MEDIA_TOO_MANY");
        }
        Visibility visibility = parseVisibility(req == null ? null : req.visibility());
        List<String> visibleUserIds = normalizeIds(req == null ? null : req.visibleUserIds());
        validateVisibilityForPublish(userId, visibility, visibleUserIds);
        List<MomentMedia> media = requireOwnedUnboundMedia(userId, mediaIds);

        Moment moment = new Moment();
        moment.setMomentId("mom_" + shortId());
        moment.setAuthorUserId(userId);
        moment.setText(text);
        moment.setLocation(trimToMax(req == null ? null : req.location(), 100));
        moment.setVisibility(visibility);
        moment.setIdempotencyKey(key);
        momentRepository.save(moment);

        if (!visibleUserIds.isEmpty()) {
            settingsService.saveVisibleUsers(moment.getMomentId(), visibleUserIds);
        }

        for (MomentMedia item : media) {
            item.setMomentId(moment.getMomentId());
        }
        mediaRepository.saveAll(media);
        MomentItem item = toItem(userId, moment, contextFor(List.of(moment), userId, Map.of(
            moment.getMomentId(), new HashSet<>(visibleUserIds))));
        momentRealtimePublisher.publish(new MomentChangedCommittedEvent(
            "created",
            moment.getMomentId(),
            userId,
            audienceForMoment(userId, moment, visibleUserIds),
            null,
            null,
            null,
            null,
            null,
            null));
        return item;
    }

    @Transactional
    public DeleteMomentResult deleteMoment(String userId, String momentId) {
        Moment moment = momentRepository.findByMomentId(momentId)
            .filter(m -> m.getStatus() == Moment.STATUS_ACTIVE)
            .orElseThrow(() -> notFound("MOMENT_NOT_FOUND"));
        if (!moment.getAuthorUserId().equals(userId)) {
            throw forbidden("MOMENT_NOT_OWNER");
        }
        moment.setStatus(Moment.STATUS_DELETED);
        momentRepository.save(moment);
        momentRealtimePublisher.publish(new MomentChangedCommittedEvent(
            "deleted",
            momentId,
            userId,
            audienceForMoment(userId, moment, visibleUserIdsForMoment(momentId)),
            null,
            null,
            null,
            null,
            null,
            null));
        return new DeleteMomentResult(momentId, true);
    }

    @Transactional
    public LikeResult like(String userId, String momentId) {
        Moment moment = requireVisibleMoment(userId, momentId);
        if (likeRepository.findByMomentIdAndUserId(momentId, userId).isEmpty()) {
            MomentLike like = new MomentLike();
            like.setMomentId(momentId);
            like.setUserId(userId);
            try {
                likeRepository.save(like);
                createNotificationIfNeeded(moment.getAuthorUserId(), userId, NotificationType.LIKE, momentId,
                    null, null);
            } catch (DataIntegrityViolationException ignored) {
                // Repeated likes are idempotent.
            }
        }
        LikeResult result = likeResult(userId, momentId, true);
        momentRealtimePublisher.publish(new MomentChangedCommittedEvent(
            "like_changed",
            momentId,
            moment.getAuthorUserId(),
            likeEventAudience(moment.getAuthorUserId(), userId),
            userId,
            true,
            result.likeCount(),
            null,
            null,
            null));
        return result;
    }

    @Transactional
    public LikeResult unlike(String userId, String momentId) {
        Moment moment = requireVisibleMoment(userId, momentId);
        likeRepository.deleteByMomentIdAndUserId(momentId, userId);
        LikeResult result = likeResult(userId, momentId, false);
        momentRealtimePublisher.publish(new MomentChangedCommittedEvent(
            "like_changed",
            momentId,
            moment.getAuthorUserId(),
            likeEventAudience(moment.getAuthorUserId(), userId),
            userId,
            false,
            result.likeCount(),
            null,
            null,
            null));
        return result;
    }

    @Transactional
    public CommentCreateResult comment(String userId, String momentId, CommentRequest req, String idempotencyKey) {
        Moment moment = requireVisibleMoment(userId, momentId);
        String key = blankToNull(idempotencyKey);
        if (key != null) {
            Optional<MomentComment> existing = commentRepository.findByMomentIdAndAuthorUserIdAndIdempotencyKey(
                momentId, userId, key);
            if (existing.isPresent()) {
                return new CommentCreateResult(momentId,
                    commentRepository.countByMomentIdAndStatus(momentId, MomentComment.STATUS_ACTIVE),
                    toCommentView(userId, existing.get(), userMapForComment(existing.get()), remarksForComment(userId, existing.get()), moment.getAuthorUserId()));
            }
        }
        String text = req == null ? null : trimToNull(req.text());
        if (text == null) {
            throw badRequest("MOMENT_COMMENT_EMPTY");
        }
        if (text.length() > MAX_COMMENT_LENGTH) {
            throw badRequest("MOMENT_COMMENT_TOO_LONG");
        }

        MomentComment parent = null;
        String replyToCommentId = blankToNull(req.replyToCommentId());
        if (replyToCommentId != null) {
            parent = commentRepository.findByCommentId(replyToCommentId)
                .filter(c -> c.getStatus() == MomentComment.STATUS_ACTIVE)
                .filter(c -> c.getMomentId().equals(momentId))
                .orElseThrow(() -> notFound("MOMENT_COMMENT_NOT_FOUND"));
        }

        MomentComment comment = new MomentComment();
        comment.setCommentId("cmt_" + shortId());
        comment.setMomentId(momentId);
        comment.setAuthorUserId(userId);
        comment.setText(text);
        comment.setIdempotencyKey(key);
        if (parent != null) {
            comment.setReplyToCommentId(parent.getCommentId());
            comment.setReplyToUserId(parent.getAuthorUserId());
        }
        commentRepository.save(comment);

        if (parent != null) {
            createNotificationIfNeeded(parent.getAuthorUserId(), userId, NotificationType.COMMENT_REPLY,
                momentId, comment.getCommentId(), parent.getAuthorUserId());
        } else {
            createNotificationIfNeeded(moment.getAuthorUserId(), userId, NotificationType.COMMENT,
                momentId, comment.getCommentId(), null);
        }

        long count = commentRepository.countByMomentIdAndStatus(momentId, MomentComment.STATUS_ACTIVE);
        CommentCreateResult result = new CommentCreateResult(momentId, count,
            toCommentView(userId, comment, userMapForComment(comment), remarksForComment(userId, comment), moment.getAuthorUserId()));
        String replyTargetUserId = parent == null ? null : parent.getAuthorUserId();
        momentRealtimePublisher.publish(new MomentChangedCommittedEvent(
            "comment_created",
            momentId,
            moment.getAuthorUserId(),
            commentEventAudience(moment.getAuthorUserId(), replyTargetUserId, userId),
            userId,
            null,
            null,
            comment.getCommentId(),
            comment.getReplyToCommentId(),
            count));
        return result;
    }

    @Transactional
    public CommentDeleteResult deleteComment(String userId, String momentId, String commentId) {
        Moment moment = momentRepository.findByMomentId(momentId)
            .filter(m -> m.getStatus() == Moment.STATUS_ACTIVE)
            .orElseThrow(() -> notFound("MOMENT_NOT_FOUND"));
        MomentComment comment = commentRepository.findByCommentId(commentId)
            .filter(c -> c.getMomentId().equals(momentId))
            .filter(c -> c.getStatus() == MomentComment.STATUS_ACTIVE)
            .orElseThrow(() -> notFound("MOMENT_COMMENT_NOT_FOUND"));
        if (!comment.getAuthorUserId().equals(userId) && !moment.getAuthorUserId().equals(userId)) {
            throw forbidden("MOMENT_FORBIDDEN");
        }
        comment.setStatus(MomentComment.STATUS_DELETED);
        commentRepository.save(comment);
        return new CommentDeleteResult(momentId, commentId, true,
            commentRepository.countByMomentIdAndStatus(momentId, MomentComment.STATUS_ACTIVE));
    }

    @Transactional(readOnly = true)
    public NotificationPage notifications(String userId, String cursor, Integer pageSize) {
        int size = normalizePageSize(pageSize);
        Cursor c = parseCursor(cursor);
        List<MomentNotification> rows = notificationRepository.findPage(userId, c.createdAt(), c.id(),
            PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<MomentNotification> pageRows = hasMore ? rows.subList(0, size) : rows;
        NotificationContext ctx = notificationContext(userId, pageRows);
        List<NotificationView> items = pageRows.stream().map(n -> toNotificationView(userId, n, ctx)).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
            ? cursorOf(pageRows.get(pageRows.size() - 1).getCreatedAt(), pageRows.get(pageRows.size() - 1).getNotificationId())
            : null;
        return new NotificationPage(items, nextCursor, hasMore,
            notificationRepository.countByRecipientUserIdAndReadFalse(userId));
    }

    @Transactional
    public NotificationReadResult markNotificationsRead(String userId, NotificationReadRequest req) {
        int updated;
        if (req != null && Boolean.TRUE.equals(req.readAll())) {
            updated = notificationRepository.markAllRead(userId);
        } else {
            List<String> ids = normalizeIds(req == null ? null : req.notificationIds());
            updated = ids.isEmpty() ? 0 : notificationRepository.markRead(userId, ids);
        }
        return new NotificationReadResult(updated, notificationRepository.countByRecipientUserIdAndReadFalse(userId));
    }

    private PageView<MomentItem> fetchFilteredMomentPage(String viewerUserId, List<String> authorIds,
                                                         Instant minCreatedAt, Cursor cursor, int pageSize) {
        Cursor current = cursor == null ? new Cursor(null, null) : cursor;
        List<Moment> collected = new ArrayList<>();
        boolean dbHasMore = true;
        int rounds = 0;
        boolean singleAuthor = authorIds.size() == 1;

        while (collected.size() < pageSize + 1 && dbHasMore && rounds < MAX_FEED_FETCH_ROUNDS) {
            rounds++;
            int batchSize = Math.max(pageSize * FEED_FETCH_MULTIPLIER, pageSize + 1);
            List<Moment> batchRows = fetchMomentRows(authorIds, current, batchSize);
            if (batchRows.isEmpty()) {
                break;
            }
            dbHasMore = batchRows.size() >= batchSize;
            Set<String> authorSet = batchRows.stream().map(Moment::getAuthorUserId).collect(Collectors.toSet());
            Map<String, Set<String>> blockedByAuthor = settingsService.getBlockedViewerIdsByAuthors(authorSet);
            Map<String, Set<String>> visibleUsersByMoment = settingsService.getVisibleUsersByMomentIds(
                batchRows.stream().map(Moment::getMomentId).toList());

            for (Moment moment : batchRows) {
                current = new Cursor(moment.getCreatedAt(), moment.getMomentId());
                if (minCreatedAt != null && moment.getCreatedAt().isBefore(minCreatedAt)) {
                    if (singleAuthor) {
                        dbHasMore = false;
                    }
                    continue;
                }
                Set<String> blocked = blockedByAuthor.getOrDefault(moment.getAuthorUserId(), Set.of());
                Set<String> visibleUsers = visibleUsersByMoment.getOrDefault(moment.getMomentId(), Set.of());
                if (!settingsService.canViewerSeeMoment(viewerUserId, moment, blocked, visibleUsers)) {
                    continue;
                }
                collected.add(moment);
                if (collected.size() >= pageSize + 1) {
                    break;
                }
            }
            if (collected.size() >= pageSize + 1) {
                break;
            }
        }
        return toMomentPage(viewerUserId, collected, pageSize);
    }

    private List<Moment> fetchMomentRows(List<String> authorIds, Cursor cursor, int batchSize) {
        if (authorIds.size() == 1) {
            return momentRepository.findUserMoments(authorIds.get(0), cursor.createdAt(), cursor.id(),
                PageRequest.of(0, batchSize));
        }
        return momentRepository.findFeed(authorIds, cursor.createdAt(), cursor.id(), PageRequest.of(0, batchSize));
    }

    private PageView<MomentItem> toMomentPage(String viewerUserId, List<Moment> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<Moment> pageRows = hasMore ? rows.subList(0, size) : rows;
        MomentContext ctx = contextFor(pageRows, viewerUserId);
        List<MomentItem> items = pageRows.stream().map(m -> toItem(viewerUserId, m, ctx)).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
            ? cursorOf(pageRows.get(pageRows.size() - 1).getCreatedAt(), pageRows.get(pageRows.size() - 1).getMomentId())
            : null;
        return new PageView<>(items, nextCursor, hasMore);
    }

    private MomentItem toItem(String viewerUserId, Moment m, MomentContext ctx) {
        long likeCount = ctx.likeCount().getOrDefault(m.getMomentId(), 0L);
        long commentCount = ctx.commentCount().getOrDefault(m.getMomentId(), 0L);
        List<LikePreviewView> likesPreview = ctx.likesPreviewByMoment().getOrDefault(m.getMomentId(), List.of()).stream()
            .map(like -> toLikePreview(viewerUserId, like, ctx.users(), ctx.remarks()))
            .filter(Objects::nonNull)
            .toList();
        List<CommentView> commentsPreview = ctx.commentsByMoment().getOrDefault(m.getMomentId(), List.of()).stream()
            .map(c -> toCommentView(viewerUserId, c, ctx.users(), ctx.remarks(), m.getAuthorUserId()))
            .toList();
        List<String> visibleUserIds = ctx.visibleUsersByMoment().getOrDefault(m.getMomentId(), Set.of()).stream()
            .sorted()
            .toList();
        return new MomentItem(
            m.getMomentId(),
            snapshot(viewerUserId, ctx.users().get(m.getAuthorUserId()), ctx.remarks()),
            nullToEmpty(m.getText()),
            ctx.mediaByMoment().getOrDefault(m.getMomentId(), List.of()).stream().map(this::toMediaView).toList(),
            nullToEmpty(m.getLocation()),
            m.getVisibility().name(),
            visibleUserIds.isEmpty() ? List.of() : visibleUserIds,
            millis(m.getCreatedAt()),
            millis(m.getUpdatedAt()),
            ctx.likedByMe().contains(m.getMomentId()),
            likeCount,
            likesPreview,
            commentCount,
            commentsPreview,
            m.getAuthorUserId().equals(viewerUserId));
    }

    private MomentDetail toDetail(String viewerUserId, Moment m) {
        List<MomentMedia> media = mediaRepository.findByMomentIdOrderByIdAsc(m.getMomentId());
        List<MomentLike> likes = likeRepository.findByMomentIdOrderByCreatedAtAsc(m.getMomentId());
        List<MomentComment> comments = commentRepository.findByMomentIdAndStatusOrderByCreatedAtAsc(
            m.getMomentId(), MomentComment.STATUS_ACTIVE);
        Set<String> userIds = new HashSet<>();
        userIds.add(m.getAuthorUserId());
        likes.forEach(l -> userIds.add(l.getUserId()));
        comments.forEach(c -> {
            userIds.add(c.getAuthorUserId());
            if (c.getReplyToUserId() != null) {
                userIds.add(c.getReplyToUserId());
            }
        });
        Map<String, User> users = usersById(userIds);
        Map<String, String> remarks = remarksByPeerId(viewerUserId, userIds);
        List<LikePreviewView> likeViews = likes.stream()
            .map(l -> toLikePreview(viewerUserId, l, users, remarks))
            .filter(Objects::nonNull)
            .toList();
        return new MomentDetail(
            m.getMomentId(),
            snapshot(viewerUserId, users.get(m.getAuthorUserId()), remarks),
            nullToEmpty(m.getText()),
            media.stream().map(this::toMediaView).toList(),
            nullToEmpty(m.getLocation()),
            m.getVisibility().name(),
            millis(m.getCreatedAt()),
            millis(m.getUpdatedAt()),
            likeRepository.existsByMomentIdAndUserId(m.getMomentId(), viewerUserId),
            likes.size(),
            likeViews,
            comments.size(),
            comments.stream().map(c -> toCommentView(viewerUserId, c, users, remarks, m.getAuthorUserId())).toList(),
            m.getAuthorUserId().equals(viewerUserId));
    }

    private MomentContext contextFor(List<Moment> moments, String viewerUserId) {
        List<String> momentIds = moments.stream().map(Moment::getMomentId).toList();
        Map<String, Set<String>> visibleUsersByMoment = settingsService.getVisibleUsersByMomentIds(momentIds);
        return contextFor(moments, viewerUserId, visibleUsersByMoment);
    }

    private MomentContext contextFor(List<Moment> moments, String viewerUserId,
                                     Map<String, Set<String>> visibleUsersByMoment) {
        List<String> momentIds = moments.stream().map(Moment::getMomentId).toList();
        Set<String> userIds = moments.stream().map(Moment::getAuthorUserId).collect(Collectors.toSet());
        Map<String, List<MomentMedia>> mediaByMoment = momentIds.isEmpty() ? Map.of()
            : mediaRepository.findByMomentIdIn(momentIds).stream()
                .collect(Collectors.groupingBy(MomentMedia::getMomentId, LinkedHashMap::new, Collectors.toList()));
        Map<String, Long> likeCount = new HashMap<>();
        Map<String, List<MomentLike>> likesPreviewByMoment = new HashMap<>();
        Set<String> likedByMe = new HashSet<>();
        if (!momentIds.isEmpty()) {
            for (MomentLike like : likeRepository.findByMomentIdInOrderByCreatedAtDesc(momentIds)) {
                likeCount.merge(like.getMomentId(), 1L, Long::sum);
                if (like.getUserId().equals(viewerUserId)) {
                    likedByMe.add(like.getMomentId());
                }
                List<MomentLike> preview = likesPreviewByMoment.computeIfAbsent(like.getMomentId(), k -> new ArrayList<>());
                if (preview.size() < PREVIEW_LIKES) {
                    preview.add(like);
                    userIds.add(like.getUserId());
                }
            }
        }
        Map<String, Long> commentCount = new HashMap<>();
        Map<String, List<MomentComment>> commentsByMoment = new HashMap<>();
        if (!momentIds.isEmpty()) {
            for (MomentComment comment : commentRepository.findByMomentIdInAndStatusOrderByCreatedAtAsc(
                momentIds, MomentComment.STATUS_ACTIVE)) {
                commentCount.merge(comment.getMomentId(), 1L, Long::sum);
                List<MomentComment> preview = commentsByMoment.computeIfAbsent(comment.getMomentId(), k -> new ArrayList<>());
                if (preview.size() < PREVIEW_COMMENTS) {
                    preview.add(comment);
                    userIds.add(comment.getAuthorUserId());
                    if (comment.getReplyToUserId() != null) {
                        userIds.add(comment.getReplyToUserId());
                    }
                }
            }
        }
        Map<String, User> users = usersById(userIds);
        Map<String, String> remarks = remarksByPeerId(viewerUserId, userIds);
        return new MomentContext(mediaByMoment, likeCount, likesPreviewByMoment, likedByMe, commentCount,
            commentsByMoment, users, remarks, visibleUsersByMoment == null ? Map.of() : visibleUsersByMoment);
    }

    private NotificationContext notificationContext(String viewerUserId, List<MomentNotification> rows) {
        Set<String> momentIds = rows.stream().map(MomentNotification::getMomentId).collect(Collectors.toSet());
        Set<String> commentIds = rows.stream().map(MomentNotification::getCommentId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Moment> moments = momentIds.stream()
            .map(id -> momentRepository.findByMomentId(id).orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(Moment::getMomentId, Function.identity()));
        Map<String, MomentComment> comments = commentIds.stream()
            .map(id -> commentRepository.findByCommentId(id).orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(MomentComment::getCommentId, Function.identity()));
        Map<String, MomentMedia> firstMedia = new HashMap<>();
        if (!momentIds.isEmpty()) {
            mediaRepository.findByMomentIdIn(momentIds).stream()
                .sorted(Comparator.comparing(MomentMedia::getId))
                .forEach(m -> firstMedia.putIfAbsent(m.getMomentId(), m));
        }
        Set<String> userIds = new HashSet<>();
        rows.forEach(n -> {
            userIds.add(n.getActorUserId());
            if (n.getReplyToUserId() != null) {
                userIds.add(n.getReplyToUserId());
            }
        });
        moments.values().forEach(m -> userIds.add(m.getAuthorUserId()));
        comments.values().forEach(c -> {
            userIds.add(c.getAuthorUserId());
            if (c.getReplyToUserId() != null) {
                userIds.add(c.getReplyToUserId());
            }
        });
        Map<String, User> users = usersById(userIds);
        Map<String, String> remarks = remarksByPeerId(viewerUserId, userIds);
        return new NotificationContext(moments, comments, firstMedia, users, remarks);
    }

    private NotificationView toNotificationView(String viewerUserId, MomentNotification n, NotificationContext ctx) {
        Moment moment = ctx.moments().get(n.getMomentId());
        MomentComment comment = n.getCommentId() == null ? null : ctx.comments().get(n.getCommentId());
        MomentMedia media = ctx.firstMedia().get(n.getMomentId());
        return new NotificationView(
            n.getNotificationId(),
            n.getType().name(),
            snapshot(viewerUserId, ctx.users().get(n.getActorUserId()), ctx.remarks()),
            n.getMomentId(),
            moment == null ? null : snapshot(viewerUserId, ctx.users().get(moment.getAuthorUserId()), ctx.remarks()),
            moment == null ? "" : nullToEmpty(moment.getText()),
            media == null ? null : new NotificationPreviewMedia(media.getType().name(), media.getThumbUrl()),
            comment == null ? null : toCommentView(viewerUserId, comment, ctx.users(), ctx.remarks(),
                moment == null ? null : moment.getAuthorUserId()),
            n.getReplyToUserId() == null ? null : snapshot(viewerUserId, ctx.users().get(n.getReplyToUserId()), ctx.remarks()),
            millis(n.getCreatedAt()),
            n.isRead());
    }

    private CommentView toCommentView(String viewerUserId, MomentComment c, Map<String, User> users,
                                      Map<String, String> remarks, String momentAuthorUserId) {
        return new CommentView(
            c.getCommentId(),
            snapshot(viewerUserId, users.get(c.getAuthorUserId()), remarks),
            c.getReplyToCommentId(),
            c.getReplyToUserId() == null ? null : snapshot(viewerUserId, users.get(c.getReplyToUserId()), remarks),
            c.getText(),
            millis(c.getCreatedAt()),
            c.getAuthorUserId().equals(viewerUserId) || Objects.equals(momentAuthorUserId, viewerUserId));
    }

    private Map<String, User> userMapForComment(MomentComment comment) {
        Set<String> ids = new HashSet<>();
        ids.add(comment.getAuthorUserId());
        if (comment.getReplyToUserId() != null) {
            ids.add(comment.getReplyToUserId());
        }
        return usersById(ids);
    }

    private Map<String, String> remarksForComment(String viewerUserId, MomentComment comment) {
        Set<String> ids = new HashSet<>();
        if (comment.getReplyToUserId() != null) {
            ids.add(comment.getReplyToUserId());
        }
        return remarksByPeerId(viewerUserId, ids);
    }

    private LikePreviewView toLikePreview(String viewerUserId, MomentLike like, Map<String, User> users,
                                          Map<String, String> remarks) {
        UserSnapshot user = snapshot(viewerUserId, users.get(like.getUserId()), remarks);
        if (user == null) {
            return null;
        }
        return new LikePreviewView(user, millis(like.getCreatedAt()));
    }

    private UserSnapshot snapshot(String viewerUserId, User user, Map<String, String> remarks) {
        if (user == null) {
            return null;
        }
        String remark = viewerUserId.equals(user.getUserId())
            ? ""
            : nullToEmpty(remarks == null ? null : remarks.get(user.getUserId()));
        return new UserSnapshot(user.getUserId(), nullToEmpty(user.getNickname()), resolveAvatarUrl(user.getAvatarUrl()), remark);
    }

    private String resolveAvatarUrl(String raw) {
        if (raw != null && !raw.isBlank()) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed;
            }
        }
        String fallback = authProps.avatarUrl();
        return fallback == null || fallback.isBlank() ? "" : fallback.trim();
    }

    private Map<String, String> remarksByPeerId(String viewerUserId, Collection<String> peerIds) {
        if (viewerUserId == null || peerIds == null || peerIds.isEmpty()) {
            return Map.of();
        }
        Set<String> peers = peerIds.stream()
            .filter(id -> id != null && !id.isBlank() && !id.equals(viewerUserId))
            .collect(Collectors.toSet());
        if (peers.isEmpty()) {
            return Map.of();
        }
        return friendRepository.findByUserIdAndFriendUserIdInAndStatus(viewerUserId, peers, UserFriend.STATUS_ACTIVE)
            .stream()
            .filter(row -> row.getRemark() != null && !row.getRemark().isBlank())
            .collect(Collectors.toMap(UserFriend::getFriendUserId, UserFriend::getRemark, (a, b) -> a));
    }

    private MediaView toMediaView(MomentMedia m) {
        return new MediaView(m.getMediaId(), m.getType().name(), m.getUrl(), m.getThumbUrl(),
            m.getWidth(), m.getHeight(), m.getDurationSec(), m.getSizeBytes());
    }

    private Map<String, User> usersById(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByUserIdIn(ids).stream()
            .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));
    }

    private List<String> visibleAuthorIds(String viewerUserId) {
        List<UserFriend> rows = friendRepository.findByUserIdAndStatus(viewerUserId, UserFriend.STATUS_ACTIVE);
        List<String> peers = rows.stream().map(UserFriend::getFriendUserId).toList();
        Set<String> ids = new HashSet<>();
        ids.add(viewerUserId);
        if (!peers.isEmpty()) {
            ids.addAll(friendRepository.findMutualFriendUserIdsAmong(viewerUserId, peers));
        }
        return new ArrayList<>(ids);
    }

    private UserMomentSnapshot userMomentSnapshot(String viewerUserId, User user, int visibleRangeDays) {
        UserSnapshot base = snapshot(viewerUserId, user, Map.of());
        if (base == null) {
            return null;
        }
        return new UserMomentSnapshot(base.userId(), base.nickname(), base.avatarUrl(), base.remark(), visibleRangeDays);
    }

    private Instant minCreatedAtForRange(int visibleRangeDays, String viewerUserId, String targetUserId) {
        if (viewerUserId.equals(targetUserId) || visibleRangeDays <= 0) {
            return null;
        }
        return Instant.now().minus(visibleRangeDays, ChronoUnit.DAYS);
    }

    private List<String> visibleUserIdsForMoment(String momentId) {
        return settingsService.getVisibleUsersByMomentIds(List.of(momentId))
            .getOrDefault(momentId, Set.of()).stream().sorted().toList();
    }

    private void validateVisibilityForPublish(String userId, Visibility visibility, List<String> visibleUserIds) {
        if (visibility == Visibility.FRIENDS) {
            if (!visibleUserIds.isEmpty()) {
                throw badRequest("MOMENT_VISIBILITY_INVALID");
            }
            return;
        }
        if (visibility == Visibility.EXCLUDE || visibility == Visibility.PARTIAL) {
            settingsService.validateVisibleUserIds(userId, visibleUserIds);
            return;
        }
        throw badRequest("MOMENT_VISIBILITY_INVALID");
    }

    private List<String> audienceForMoment(String authorUserId, Moment moment, List<String> visibleUserIds) {
        Set<String> audience = new HashSet<>();
        audience.add(authorUserId);
        Set<String> blocked = settingsService.getBlockedViewerIds(authorUserId);
        Set<String> visibleSet = new HashSet<>(visibleUserIds);
        List<UserFriend> friendRows = friendRepository.findByUserIdAndStatus(authorUserId, UserFriend.STATUS_ACTIVE);
        List<String> peerIds = friendRows.stream().map(UserFriend::getFriendUserId).toList();
        if (!peerIds.isEmpty()) {
            for (String friendId : friendRepository.findMutualFriendUserIdsAmong(authorUserId, peerIds)) {
                if (settingsService.canViewerSeeMoment(friendId, moment, blocked, visibleSet)) {
                    audience.add(friendId);
                }
            }
        }
        return new ArrayList<>(audience);
    }

    private List<String> likeEventAudience(String authorUserId, String actorUserId) {
        Set<String> audience = new HashSet<>();
        audience.add(authorUserId);
        if (actorUserId != null) {
            audience.add(actorUserId);
        }
        return new ArrayList<>(audience);
    }

    private List<String> commentEventAudience(String authorUserId, String replyTargetUserId, String actorUserId) {
        Set<String> audience = new HashSet<>();
        audience.add(authorUserId);
        if (replyTargetUserId != null) {
            audience.add(replyTargetUserId);
        }
        if (actorUserId != null) {
            audience.add(actorUserId);
        }
        return new ArrayList<>(audience);
    }

    private void requireCanViewUser(String viewerUserId, String targetUserId) {
        if (viewerUserId.equals(targetUserId)) {
            return;
        }
        if (!friendService.isMutualActive(viewerUserId, targetUserId)) {
            throw forbidden("MOMENT_FORBIDDEN");
        }
        if (settingsService.isBlockedViewer(targetUserId, viewerUserId)) {
            throw forbidden("MOMENT_FORBIDDEN");
        }
    }

    private Moment requireVisibleMoment(String viewerUserId, String momentId) {
        Moment moment = momentRepository.findByMomentId(momentId)
            .filter(m -> m.getStatus() == Moment.STATUS_ACTIVE)
            .orElseThrow(() -> notFound("MOMENT_NOT_FOUND"));
        requireCanViewUser(viewerUserId, moment.getAuthorUserId());
        Set<String> blocked = settingsService.getBlockedViewerIds(moment.getAuthorUserId());
        Set<String> visibleUsers = settingsService.getVisibleUsersByMomentIds(List.of(momentId))
            .getOrDefault(momentId, Set.of());
        if (!settingsService.canViewerSeeMoment(viewerUserId, moment, blocked, visibleUsers)) {
            throw forbidden("MOMENT_FORBIDDEN");
        }
        if (!viewerUserId.equals(moment.getAuthorUserId())) {
            int visibleRangeDays = settingsService.getVisibleRangeDays(moment.getAuthorUserId());
            Instant minCreatedAt = minCreatedAtForRange(visibleRangeDays, viewerUserId, moment.getAuthorUserId());
            if (minCreatedAt != null && moment.getCreatedAt().isBefore(minCreatedAt)) {
                throw forbidden("MOMENT_FORBIDDEN");
            }
        }
        return moment;
    }

    private User requireActiveUser(String userId) {
        return userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> notFound("MOMENT_NOT_FOUND"));
    }

    private List<MomentMedia> requireOwnedUnboundMedia(String userId, List<String> mediaIds) {
        if (mediaIds.isEmpty()) {
            return List.of();
        }
        List<MomentMedia> rows = mediaRepository.findByMediaIdIn(mediaIds);
        Map<String, MomentMedia> byId = rows.stream().collect(Collectors.toMap(MomentMedia::getMediaId, Function.identity()));
        List<MomentMedia> ordered = new ArrayList<>();
        for (String id : mediaIds) {
            MomentMedia media = byId.get(id);
            if (media == null || !media.getOwnerUserId().equals(userId) || media.getMomentId() != null) {
                throw badRequest("MOMENT_MEDIA_INVALID");
            }
            ordered.add(media);
        }
        return ordered;
    }

    private UploadedMedia uploadImage(String mediaId, MultipartFile file) throws IOException {
        if (file.getSize() > IMAGE_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_FILE_TOO_LARGE");
        }
        if (!isAllowed(file, IMAGE_TYPES, IMAGE_EXT)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UPLOAD_TYPE_NOT_ALLOWED");
        }
        byte[] raw = file.getBytes();
        BufferedImage img;
        try {
            img = imageProcessor.decode(raw);
        } catch (IOException e) {
            throw badRequest("UPLOAD_TYPE_NOT_ALLOWED");
        }
        String ext = imageExtension(file);
        String base = "moments/" + mediaId + "/";
        byte[] thumb = imageProcessor.resizeKeepAspect(img, 360, 85);
        String objectKey = base + "origin." + ext;
        String thumbKey = base + "thumb.jpg";
        String url = oss.putBytes(objectKey, raw, contentTypeForExt(ext));
        String thumbUrl = oss.putBytes(thumbKey, thumb, "image/jpeg");
        return new UploadedMedia(url, thumbUrl, objectKey, thumbKey, img.getWidth(), img.getHeight(), null);
    }

    private UploadedMedia uploadVideo(String mediaId, MultipartFile file) throws IOException {
        if (file.getSize() > VIDEO_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_FILE_TOO_LARGE");
        }
        if (!isAllowed(file, VIDEO_TYPES, VIDEO_EXT)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UPLOAD_TYPE_NOT_ALLOWED");
        }
        String ext = extension(file);
        String base = "moments/" + mediaId + "/";
        String objectKey = base + "origin." + (ext == null ? "mp4" : ext);
        String thumbKey = base + "thumb.jpg";
        String url = oss.putBytes(objectKey, file.getBytes(), safeContentType(file, "video/mp4"));
        String thumbUrl = oss.putBytes(thumbKey, defaultVideoThumb(), "image/jpeg");
        return new UploadedMedia(url, thumbUrl, objectKey, thumbKey, null, null, null);
    }

    private void createNotificationIfNeeded(String recipientUserId, String actorUserId, NotificationType type,
                                            String momentId, String commentId, String replyToUserId) {
        if (recipientUserId == null || recipientUserId.equals(actorUserId)) {
            return;
        }
        MomentNotification n = new MomentNotification();
        n.setNotificationId("noti_" + shortId());
        n.setRecipientUserId(recipientUserId);
        n.setActorUserId(actorUserId);
        n.setType(type);
        n.setMomentId(momentId);
        n.setCommentId(commentId);
        n.setReplyToUserId(replyToUserId);
        notificationRepository.save(n);
    }

    private LikeResult likeResult(String viewerUserId, String momentId, boolean likedByMe) {
        long count = likeRepository.countByMomentId(momentId);
        List<MomentLike> likes = likeRepository.findByMomentIdOrderByCreatedAtDesc(momentId, PageRequest.of(0, PREVIEW_LIKES));
        Set<String> userIds = likes.stream().map(MomentLike::getUserId).collect(Collectors.toSet());
        Map<String, User> users = usersById(userIds);
        Map<String, String> remarks = remarksByPeerId(viewerUserId, userIds);
        return new LikeResult(momentId, likedByMe, count,
            likes.stream().map(l -> toLikePreview(viewerUserId, l, users, remarks)).filter(Objects::nonNull).toList());
    }

    private static int normalizePageSize(Integer raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (raw < 1 || raw > MAX_PAGE_SIZE) {
            throw badRequest("INVALID_PAGE_SIZE");
        }
        return raw;
    }

    private static Cursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Cursor(null, null);
        }
        int pos = raw.indexOf('_');
        if (pos <= 0 || pos == raw.length() - 1) {
            throw badRequest("INVALID_CURSOR");
        }
        try {
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, pos))), raw.substring(pos + 1));
        } catch (NumberFormatException e) {
            throw badRequest("INVALID_CURSOR");
        }
    }

    private static String cursorOf(Instant createdAt, String id) {
        return millis(createdAt) + "_" + id;
    }

    private static MediaType parseMediaType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("UPLOAD_TYPE_NOT_ALLOWED");
        }
        try {
            return MediaType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("UPLOAD_TYPE_NOT_ALLOWED");
        }
    }

    private static Visibility parseVisibility(String raw) {
        if (raw == null || raw.isBlank()) {
            return Visibility.FRIENDS;
        }
        try {
            return Visibility.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("MOMENT_VISIBILITY_INVALID");
        }
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().map(MomentService::trimToNull).filter(Objects::nonNull).distinct().toList();
    }

    private static String normalizeText(String text) {
        String trimmed = trimToNull(text);
        return trimmed == null ? null : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToMax(String value, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String blankToNull(String value) {
        return trimToNull(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long millis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isAllowed(MultipartFile file, Set<String> allowedTypes, Set<String> allowedExt) {
        String ct = file.getContentType();
        if (ct != null && allowedTypes.contains(ct.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String ext = extension(file);
        return ext != null && allowedExt.contains(ext);
    }

    private static String extension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String imageExtension(MultipartFile file) {
        String ext = extension(file);
        if ("png".equals(ext) || "webp".equals(ext)) {
            return ext;
        }
        return "jpg";
    }

    private static String contentTypeForExt(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private static String safeContentType(MultipartFile file, String fallback) {
        String ct = file.getContentType();
        return ct == null || ct.isBlank() ? fallback : ct;
    }

    private static byte[] defaultVideoThumb() throws IOException {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(32, 32, 32));
            g.fillRect(0, 0, 640, 360);
            g.setColor(new Color(255, 255, 255, 220));
            Polygon triangle = new Polygon(new int[]{280, 280, 390}, new int[]{120, 240, 180}, 3);
            g.fillPolygon(triangle);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException forbidden(String code) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, code);
    }

    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    private record Cursor(Instant createdAt, String id) {}

    private record UploadedMedia(String url, String thumbUrl, String objectKey, String thumbObjectKey,
                                 Integer width, Integer height, Integer durationSec) {}

    private record MomentContext(Map<String, List<MomentMedia>> mediaByMoment,
                                 Map<String, Long> likeCount,
                                 Map<String, List<MomentLike>> likesPreviewByMoment,
                                 Set<String> likedByMe,
                                 Map<String, Long> commentCount,
                                 Map<String, List<MomentComment>> commentsByMoment,
                                 Map<String, User> users,
                                 Map<String, String> remarks,
                                 Map<String, Set<String>> visibleUsersByMoment) {}

    private record NotificationContext(Map<String, Moment> moments,
                                       Map<String, MomentComment> comments,
                                       Map<String, MomentMedia> firstMedia,
                                       Map<String, User> users,
                                       Map<String, String> remarks) {}
}
