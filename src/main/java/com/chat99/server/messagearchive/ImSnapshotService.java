package com.chat99.server.messagearchive;

import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.messagearchive.ImSnapshotModels.ConversationSummary;
import com.chat99.server.messagearchive.ImSnapshotModels.PreloadBucket;
import com.chat99.server.messagearchive.ImSnapshotModels.SnapshotMessage;
import com.chat99.server.messagearchive.ImSnapshotModels.SnapshotResponse;
import com.chat99.server.messagearchive.ImSnapshotQuery.Candidate;
import com.chat99.server.messagearchive.MessageHistoryService.HistoryItem;
import com.chat99.server.messagearchive.MessageHistoryService.HistoryPage;
import com.chat99.server.push.ConversationPinService;
import com.chat99.server.push.ConversationPinService.PinItemView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ImSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ImSnapshotService.class);

    private final MessageArchiveProperties props;
    private final ChatMessageTableRouter tableRouter;
    private final ImSnapshotQuery query;
    private final ConversationRecentRepository recentRepository;
    private final MessageHistoryService historyService;
    private final GroupMemberRepository memberRepository;
    private final ConversationPinService pinService;
    private final ImUserIdService imUserIdService;

    public ImSnapshotService(MessageArchiveProperties props,
                             ChatMessageTableRouter tableRouter,
                             ImSnapshotQuery query,
                             ConversationRecentRepository recentRepository,
                             MessageHistoryService historyService,
                             GroupMemberRepository memberRepository,
                             ConversationPinService pinService,
                             ImUserIdService imUserIdService) {
        this.props = props;
        this.tableRouter = tableRouter;
        this.query = query;
        this.recentRepository = recentRepository;
        this.historyService = historyService;
        this.memberRepository = memberRepository;
        this.pinService = pinService;
        this.imUserIdService = imUserIdService;
    }

    /**
     * @param limitConvParam   旧参数：未传 limitC2c/limitGroup 时走混合 Top N
     * @param limitC2cParam    新参数：单聊会话数（与 limitGroup 任一出现即分路模式）
     * @param limitGroupParam  新参数：群聊会话数
     */
    public SnapshotResponse build(String userId,
                                  Integer limitConvParam,
                                  Integer limitC2cParam,
                                  Integer limitGroupParam,
                                  Integer limitMsgParam) {
        if (userId == null || userId.isBlank()) {
            return SnapshotResponse.emptyDegraded();
        }
        String archiveUserId = imUserIdService.toImAccount(userId);
        MessageArchiveProperties.Snapshot cfg = props.snapshot();
        int limitMsg = clamp(limitMsgParam, cfg.defaultLimitMsg(), cfg.minLimitMsg(), cfg.maxLimitMsg());
        long deadlineNs = System.nanoTime() + cfg.timeoutMs() * 1_000_000L;
        boolean degraded = false;

        try {
            long nowMs = System.currentTimeMillis();
            long sinceMs = nowMs - cfg.recentDays() * 86_400_000L;
            boolean splitMode = limitC2cParam != null || limitGroupParam != null;
            List<PinItemView> pins = loadPinsSafe(userId);

            List<RankedCandidate> ranked;
            if (splitMode) {
                int limitC2c = clamp(limitC2cParam, cfg.defaultLimitC2c(), 1, cfg.maxLimitC2c());
                int limitGroup = clamp(limitGroupParam, cfg.defaultLimitGroup(), 1, cfg.maxLimitGroup());
                List<Candidate> c2cRecent = loadSideCandidates(
                    userId, sinceMs, nowMs, limitC2c, true, cfg.projectionMode(), deadlineNs);
                List<Candidate> groupRecent = timedOut(deadlineNs)
                    ? List.of()
                    : loadSideCandidates(
                        userId, sinceMs, nowMs, limitGroup, false, cfg.projectionMode(), deadlineNs);
                List<RankedCandidate> c2c = mergeSide(pins, "c2c", c2cRecent, limitC2c);
                List<RankedCandidate> groups = mergeSide(pins, "group", groupRecent, limitGroup);
                ranked = new ArrayList<>(c2c.size() + groups.size());
                ranked.addAll(c2c);
                ranked.addAll(groups);
            } else {
                int limitConv = clamp(limitConvParam, cfg.defaultLimitConv(), 1, cfg.maxLimitConv());
                int candidateLimit = Math.min(cfg.maxLimitConv() * Math.max(cfg.candidateOversample(), 1), 150);
                List<Candidate> recent = loadCandidates(
                    userId, sinceMs, nowMs, candidateLimit, cfg.projectionMode(), deadlineNs);
                ranked = mergeAll(pins, recent, limitConv);
            }

            if (timedOut(deadlineNs) && ranked.isEmpty()) {
                return SnapshotResponse.emptyDegraded();
            }

            List<ConversationSummary> conversations = new ArrayList<>();
            List<PreloadBucket> preload = new ArrayList<>();

            for (RankedCandidate rankedCandidate : ranked) {
                if (timedOut(deadlineNs)) {
                    degraded = true;
                    break;
                }
                Candidate candidate = rankedCandidate.candidate();
                try {
                    HistoryPage page = loadPage(archiveUserId, normalizeCandidate(candidate), limitMsg);
                    boolean empty = page == null || page.items() == null || page.items().isEmpty();
                    if (empty) {
                        if (!rankedCandidate.pinned()) {
                            continue;
                        }
                        String conversationId = clientConversationId(candidate);
                        conversations.add(new ConversationSummary(
                            conversationId,
                            candidate.chatType(),
                            candidate.chatType(),
                            clientPeerId(candidate),
                            null,
                            null,
                            true,
                            rankedCandidate.pinnedAt()));
                        preload.add(new PreloadBucket(conversationId, List.of()));
                        continue;
                    }
                    List<HistoryItem> newestFirst = page.items();
                    HistoryItem newest = newestFirst.get(0);
                    List<SnapshotMessage> asc = new ArrayList<>(newestFirst.size());
                    for (int i = newestFirst.size() - 1; i >= 0; i--) {
                        asc.add(toMessage(newestFirst.get(i)));
                    }
                    String conversationId = clientConversationId(candidate);
                    Long lastSeq = newest.msgSeq() != null ? newest.msgSeq() : candidate.lastSeq();
                    conversations.add(new ConversationSummary(
                        conversationId,
                        candidate.chatType(),
                        candidate.chatType(),
                        clientPeerId(candidate),
                        lastSeq,
                        toMessage(newest),
                        rankedCandidate.pinned(),
                        rankedCandidate.pinnedAt()));
                    preload.add(new PreloadBucket(conversationId, asc));
                } catch (Exception e) {
                    degraded = true;
                    log.warn("snapshot conv skipped userLen={} type={} err={}",
                        userId.length(), candidate.chatType(), e.toString());
                }
            }

            if (conversations.isEmpty() && degraded) {
                return SnapshotResponse.emptyDegraded();
            }
            return SnapshotResponse.of(conversations, preload, degraded);
        } catch (Exception e) {
            log.warn("snapshot build failed userLen={} err={}", userId.length(), e.toString());
            return SnapshotResponse.emptyDegraded();
        }
    }

    /** 兼容旧调用：仅 limitConv + limitMsg。 */
    public SnapshotResponse build(String userId, Integer limitConvParam, Integer limitMsgParam) {
        return build(userId, limitConvParam, null, null, limitMsgParam);
    }

    private List<PinItemView> loadPinsSafe(String userId) {
        try {
            return pinService.list(userId).items();
        } catch (Exception e) {
            log.warn("snapshot pin list failed userLen={} err={}", userId.length(), e.toString());
            return List.of();
        }
    }

    /**
     * 分路一侧：置顶（pinnedAt↓）优先占满名额，再用时间候选补齐并去重。
     */
    static List<RankedCandidate> mergeSide(List<PinItemView> pins,
                                           String chatType,
                                           List<Candidate> recent,
                                           int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String type = normalizeType(chatType);
        List<PinItemView> sidePins = new ArrayList<>();
        if (pins != null) {
            for (PinItemView p : pins) {
                if (p != null && type.equals(normalizeType(p.chatType()))
                    && p.peerId() != null && !p.peerId().isBlank()) {
                    sidePins.add(p);
                }
            }
        }
        sidePins.sort(Comparator.comparingLong(PinItemView::pinnedAt).reversed());

        LinkedHashMap<String, RankedCandidate> ordered = new LinkedHashMap<>();
        for (PinItemView p : sidePins) {
            if (ordered.size() >= limit) {
                break;
            }
            String peerId = p.peerId().trim();
            ordered.putIfAbsent(key(type, peerId), new RankedCandidate(
                new Candidate(type, peerId, p.pinnedAt(), null),
                true,
                p.pinnedAt()));
        }
        if (recent != null) {
            List<Candidate> sorted = new ArrayList<>(recent);
            sorted.sort(Comparator.comparingLong(Candidate::lastMs).reversed());
            for (Candidate c : sorted) {
                if (ordered.size() >= limit) {
                    break;
                }
                if (c == null || c.peerId() == null || c.peerId().isBlank()) {
                    continue;
                }
                if (!type.equals(normalizeType(c.chatType()))) {
                    continue;
                }
                String peerId = c.peerId().trim();
                ordered.putIfAbsent(key(type, peerId), new RankedCandidate(c, false, null));
            }
        }
        return List.copyOf(ordered.values());
    }

    /** 混合模式：全部置顶优先，再按时间候选补齐。 */
    static List<RankedCandidate> mergeAll(List<PinItemView> pins, List<Candidate> recent, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<PinItemView> allPins = new ArrayList<>();
        if (pins != null) {
            for (PinItemView p : pins) {
                if (p != null && p.peerId() != null && !p.peerId().isBlank()
                    && ("c2c".equals(normalizeType(p.chatType()))
                        || "group".equals(normalizeType(p.chatType())))) {
                    allPins.add(p);
                }
            }
        }
        allPins.sort(Comparator.comparingLong(PinItemView::pinnedAt).reversed());

        LinkedHashMap<String, RankedCandidate> ordered = new LinkedHashMap<>();
        for (PinItemView p : allPins) {
            if (ordered.size() >= limit) {
                break;
            }
            String type = normalizeType(p.chatType());
            String peerId = p.peerId().trim();
            ordered.putIfAbsent(key(type, peerId), new RankedCandidate(
                new Candidate(type, peerId, p.pinnedAt(), null),
                true,
                p.pinnedAt()));
        }
        if (recent != null) {
            List<Candidate> sorted = new ArrayList<>(recent);
            sorted.sort(Comparator.comparingLong(Candidate::lastMs).reversed());
            for (Candidate c : sorted) {
                if (ordered.size() >= limit) {
                    break;
                }
                if (c == null || c.peerId() == null || c.peerId().isBlank()) {
                    continue;
                }
                String type = normalizeType(c.chatType());
                if (!"c2c".equals(type) && !"group".equals(type)) {
                    continue;
                }
                String peerId = c.peerId().trim();
                ordered.putIfAbsent(key(type, peerId), new RankedCandidate(c, false, null));
            }
        }
        return List.copyOf(ordered.values());
    }

    private List<Candidate> loadSideCandidates(String userId, long sinceMs, long nowMs, int limit,
                                               boolean c2cSide, String mode, long deadlineNs) {
        if (limit <= 0) {
            return List.of();
        }
        if ("legacy".equals(mode)) {
            return loadLegacySide(userId, sinceMs, nowMs, limit, c2cSide);
        }
        List<Candidate> fromRecent = c2cSide
            ? recentRepository.listC2cCandidates(userId, sinceMs, limit)
            : recentRepository.listGroupCandidates(userId, sinceMs, limit);
        fromRecent = sortDescLimit(fromRecent, limit);
        if (!fromRecent.isEmpty() || "recent".equals(mode) || timedOut(deadlineNs)) {
            return fromRecent;
        }
        log.debug("snapshot hybrid side fallback legacy userLen={} c2c={}", userId.length(), c2cSide);
        return loadLegacySide(userId, sinceMs, nowMs, limit, c2cSide);
    }

    private List<Candidate> loadLegacySide(String userId, long sinceMs, long nowMs, int limit, boolean c2cSide) {
        List<String> tables = tableRouter.physicalTablesBetween(sinceMs, nowMs);
        if (c2cSide) {
            return sortDescLimit(query.listC2cCandidates(tables, userId, sinceMs, limit), limit);
        }
        List<String> groupIds = memberRepository.findActiveGroupIdsByUserId(userId);
        return sortDescLimit(query.listGroupCandidates(tables, groupIds, sinceMs, limit), limit);
    }

    private List<Candidate> loadCandidates(String userId, long sinceMs, long nowMs,
                                           int candidateLimit, String mode, long deadlineNs) {
        if ("legacy".equals(mode)) {
            return loadLegacyCandidates(userId, sinceMs, nowMs, candidateLimit, deadlineNs);
        }

        List<Candidate> fromRecent = loadRecentCandidates(userId, sinceMs, candidateLimit, deadlineNs);
        if (!fromRecent.isEmpty() || "recent".equals(mode) || timedOut(deadlineNs)) {
            return fromRecent;
        }
        log.debug("snapshot hybrid fallback to legacy userLen={}", userId.length());
        return loadLegacyCandidates(userId, sinceMs, nowMs, candidateLimit, deadlineNs);
    }

    private List<Candidate> loadRecentCandidates(String userId, long sinceMs,
                                                 int candidateLimit, long deadlineNs) {
        List<Candidate> c2c = recentRepository.listC2cCandidates(userId, sinceMs, candidateLimit);
        if (timedOut(deadlineNs)) {
            return mergeRanked(c2c, List.of(), candidateLimit);
        }
        List<Candidate> groups = recentRepository.listGroupCandidates(userId, sinceMs, candidateLimit);
        return mergeRanked(c2c, groups, candidateLimit);
    }

    private List<Candidate> loadLegacyCandidates(String userId, long sinceMs, long nowMs,
                                                 int candidateLimit, long deadlineNs) {
        List<String> tables = tableRouter.physicalTablesBetween(sinceMs, nowMs);
        List<Candidate> c2c = query.listC2cCandidates(tables, userId, sinceMs, candidateLimit);
        if (timedOut(deadlineNs)) {
            return mergeRanked(c2c, List.of(), candidateLimit);
        }
        List<String> groupIds = memberRepository.findActiveGroupIdsByUserId(userId);
        List<Candidate> groups = query.listGroupCandidates(tables, groupIds, sinceMs, candidateLimit);
        return mergeRanked(c2c, groups, candidateLimit);
    }

    private static List<Candidate> mergeRanked(List<Candidate> c2c, List<Candidate> groups, int limit) {
        List<Candidate> ranked = new ArrayList<>((c2c == null ? 0 : c2c.size()) + (groups == null ? 0 : groups.size()));
        if (c2c != null) {
            ranked.addAll(c2c);
        }
        if (groups != null) {
            ranked.addAll(groups);
        }
        return sortDescLimit(ranked, limit);
    }

    private static List<Candidate> sortDescLimit(List<Candidate> in, int limit) {
        if (in == null || in.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Candidate> ranked = new ArrayList<>(in);
        ranked.sort(Comparator.comparingLong(Candidate::lastMs).reversed());
        if (ranked.size() > limit) {
            return List.copyOf(ranked.subList(0, limit));
        }
        return ranked;
    }

    private HistoryPage loadPage(String userId, Candidate candidate, int limitMsg) {
        if ("group".equals(candidate.chatType())) {
            return historyService.listGroup(userId, candidate.peerId(), null, null, null, limitMsg);
        }
        return historyService.listC2c(userId, candidate.peerId(), null, null, null, limitMsg);
    }

    private Candidate normalizeCandidate(Candidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (!"c2c".equals(candidate.chatType())) {
            return candidate;
        }
        String imPeer = imUserIdService.toImAccount(candidate.peerId());
        if (imPeer.equals(candidate.peerId())) {
            return candidate;
        }
        return new Candidate(candidate.chatType(), imPeer, candidate.lastMs(), candidate.lastSeq());
    }

    private String clientPeerId(Candidate candidate) {
        if (candidate == null || candidate.peerId() == null) {
            return null;
        }
        if ("c2c".equals(candidate.chatType())) {
            return imUserIdService.toBusinessForDisplay(candidate.peerId());
        }
        return candidate.peerId();
    }

    private String clientConversationId(Candidate candidate) {
        if ("c2c".equals(candidate.chatType())) {
            return toConversationId("c2c", imUserIdService.toImAccount(candidate.peerId()));
        }
        return toConversationId(candidate.chatType(), candidate.peerId());
    }

    private SnapshotMessage toMessage(HistoryItem item) {
        long timeSec = item.msgTimeMs() / 1000L;
        String fromUserId = imUserIdService.toBusinessForDisplay(item.fromAccount());
        return new SnapshotMessage(
            item.msgId(),
            item.msgKey(),
            item.msgSeq(),
            fromUserId,
            fromUserId,
            timeSec,
            item.elemType(),
            item.previewText(),
            item.msgBody(),
            item.status());
    }

    static String toConversationId(String chatType, String peerId) {
        if ("group".equals(chatType)) {
            return "group_" + peerId;
        }
        return "c2c_" + peerId;
    }

    static int clamp(Integer value, int defaultValue, int min, int max) {
        int v = value == null ? defaultValue : value;
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static boolean timedOut(long deadlineNs) {
        return System.nanoTime() >= deadlineNs;
    }

    private static String normalizeType(String chatType) {
        if (chatType == null) {
            return "";
        }
        return chatType.trim().toLowerCase(Locale.ROOT);
    }

    private static String key(String chatType, String peerId) {
        return chatType + "\0" + peerId;
    }

    record RankedCandidate(Candidate candidate, boolean pinned, Long pinnedAt) {}
}
