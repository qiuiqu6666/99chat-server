package com.chat99.server.push;

import com.chat99.server.realtime.ConversationArchiveRealtimePublisher;
import com.chat99.server.realtime.ConversationFolderRealtimePublisher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationFolderService {

    private static final int MAX_NAME_LEN = 32;
    private static final int MAX_FOLDERS_PER_USER = 20;
    private static final int MAX_MEMBER_ITEMS = 100;

    private final UserConversationFolderRepository folderRepository;
    private final UserConversationFolderMemberRepository memberRepository;
    private final UserConversationArchiveRepository archiveRepository;
    private final ConversationFolderRealtimePublisher folderRealtime;
    private final ConversationArchiveRealtimePublisher archiveRealtime;

    public ConversationFolderService(UserConversationFolderRepository folderRepository,
                                     UserConversationFolderMemberRepository memberRepository,
                                     UserConversationArchiveRepository archiveRepository,
                                     ConversationFolderRealtimePublisher folderRealtime,
                                     ConversationArchiveRealtimePublisher archiveRealtime) {
        this.folderRepository = folderRepository;
        this.memberRepository = memberRepository;
        this.archiveRepository = archiveRepository;
        this.folderRealtime = folderRealtime;
        this.archiveRealtime = archiveRealtime;
    }

    public record MemberView(String chatType, String peerId, long updatedAt) {}

    public record FolderView(
        String folderId,
        String name,
        String scope,
        int sortOrder,
        long updatedAt,
        long createdAt,
        List<MemberView> members) {}

    public record FolderListResponse(List<FolderView> folders, long serverTime) {}

    public record MembersMutationResponse(boolean ok, String folderId, int count, long updatedAt) {}

    public record MoveMemberResponse(boolean ok, String folderId, String chatType, String peerId, long updatedAt) {}

    public record ReplaceResponse(boolean ok, int folderCount, long updatedAt) {}

    public record UpsertFolderRequest(String folderId, String name, String scope, Integer sortOrder) {}

    public record MemberMutationItem(String chatType, String peerId, boolean inFolder) {}

    public record ReplaceFolderItem(
        String folderId,
        String name,
        String scope,
        Integer sortOrder,
        List<ReplaceMemberItem> members) {}

    public record ReplaceMemberItem(String chatType, String peerId) {}

    public record MoveMemberRequest(String chatType, String peerId, String toFolderId) {}

    @Transactional(readOnly = true)
    public FolderListResponse list(String userId, String scope) {
        requireUserId(userId);
        String normalizedScope = scope == null || scope.isBlank() ? null : normalizeScope(scope);
        List<UserConversationFolder> folders = normalizedScope == null
            ? folderRepository.findByUserIdOrderByScopeAscSortOrderAscCreatedAtAsc(userId)
            : folderRepository.findByUserIdAndScopeOrderBySortOrderAscCreatedAtAsc(userId, normalizedScope);
        Map<String, List<MemberView>> membersByFolder = new HashMap<>();
        for (UserConversationFolderMember member : memberRepository.findByUserId(userId)) {
            membersByFolder
                .computeIfAbsent(member.getFolderId(), k -> new ArrayList<>())
                .add(new MemberView(member.getChatType(), member.getPeerId(), member.getUpdatedAt()));
        }
        List<FolderView> views = new ArrayList<>(folders.size());
        for (UserConversationFolder folder : folders) {
            List<MemberView> members = membersByFolder.getOrDefault(folder.getFolderId(), List.of());
            views.add(toView(folder, members));
        }
        return new FolderListResponse(views, System.currentTimeMillis());
    }

    @Transactional
    public FolderView upsert(String userId, UpsertFolderRequest req) {
        requireUserId(userId);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String name = normalizeName(req.name());
        String nameKey = nameKeyOf(name);
        long nowMs = System.currentTimeMillis();
        String folderId = req.folderId() == null || req.folderId().isBlank()
            ? newFolderId()
            : req.folderId().trim();
        UserConversationFolder existing = folderRepository
            .findById(new UserConversationFolderId(userId, folderId))
            .orElse(null);
        assertNameAvailable(userId, nameKey, folderId);
        if (existing == null) {
            String scope = req.scope() == null || req.scope().isBlank()
                ? "all"
                : normalizeScope(req.scope());
            long count = folderRepository.countByUserId(userId);
            if (count >= MAX_FOLDERS_PER_USER) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FOLDER_LIMIT_EXCEEDED");
            }
            int sortOrder = req.sortOrder() != null
                ? req.sortOrder()
                : folderRepository.findMaxSortOrderByUserId(userId).orElse(-1) + 1;
            UserConversationFolder created = new UserConversationFolder();
            created.setUserId(userId);
            created.setFolderId(folderId);
            created.setName(name);
            created.setNameKey(nameKey);
            created.setScope(scope);
            created.setSortOrder(sortOrder);
            created.setCreatedAt(nowMs);
            created.setUpdatedAt(nowMs);
            folderRepository.save(created);
            folderRealtime.singleChanged(userId, folderId, "upsert", nowMs);
            return toView(created, List.of());
        }
        if (req.scope() != null && !req.scope().isBlank()) {
            String scope = normalizeScope(req.scope());
            if (!scope.equals(existing.getScope())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FOLDER_SCOPE_IMMUTABLE");
            }
        }
        existing.setName(name);
        existing.setNameKey(nameKey);
        if (req.sortOrder() != null) {
            existing.setSortOrder(req.sortOrder());
        }
        existing.setUpdatedAt(nowMs);
        folderRepository.save(existing);
        List<MemberView> members = memberRepository.findByUserIdAndFolderId(userId, folderId).stream()
            .map(m -> new MemberView(m.getChatType(), m.getPeerId(), m.getUpdatedAt()))
            .toList();
        folderRealtime.singleChanged(userId, folderId, "upsert", nowMs);
        return toView(existing, members);
    }

    @Transactional
    public Map<String, Object> delete(String userId, String folderId) {
        requireUserId(userId);
        if (folderId == null || folderId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String id = folderId.trim();
        long nowMs = System.currentTimeMillis();
        memberRepository.deleteByUserIdAndFolderId(userId, id);
        folderRepository.deleteByUserIdAndFolderId(userId, id);
        folderRealtime.singleChanged(userId, id, "delete", nowMs);
        return Map.of("ok", true, "folderId", id, "updatedAt", nowMs);
    }

    @Transactional
    public MembersMutationResponse setMembers(String userId, String folderId, List<MemberMutationItem> items) {
        requireUserId(userId);
        if (folderId == null || folderId.isBlank() || items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (items.size() > MAX_MEMBER_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String id = folderId.trim();
        UserConversationFolder folder = folderRepository
            .findById(new UserConversationFolderId(userId, id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND"));
        long nowMs = System.currentTimeMillis();
        boolean movedAcrossFolders = false;
        for (MemberMutationItem item : items) {
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            NormalizedConversation conv = normalizeConversation(userId, item.chatType(), item.peerId());
            if (item.inFolder()) {
                // 不校验 chatType == folder.scope：scope=all 可混放 c2c+group
                // 唯一约束 (user_id,chat_type,peer_id)：必须先清其他组，再写入当前组
                int removedOthers = memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
                    userId, conv.chatType(), conv.peerId(), id);
                if (removedOthers > 0) {
                    movedAcrossFolders = true;
                }
                upsertMemberExclusive(userId, id, conv, nowMs);
                clearArchiveIfPresent(userId, conv, nowMs);
            } else {
                memberRepository.deleteById(new UserConversationFolderMemberId(
                    userId, id, conv.chatType(), conv.peerId()));
            }
        }
        folder.setUpdatedAt(nowMs);
        folderRepository.save(folder);
        if (movedAcrossFolders) {
            // 跨组迁移会影响多个 folder 视图，batch 让多端整表对齐
            folderRealtime.batchChanged(userId, nowMs);
        } else {
            folderRealtime.singleChanged(userId, id, "members", nowMs);
        }
        return new MembersMutationResponse(true, id, items.size(), nowMs);
    }

    /**
     * 原子迁移：出旧组 + 进 toFolderId + 清归档。
     * 等价于 PUT .../{toFolderId}/members {inFolder:true}，但语义更清晰。
     */
    @Transactional
    public MoveMemberResponse moveMember(String userId, MoveMemberRequest req) {
        requireUserId(userId);
        if (req == null || req.toFolderId() == null || req.toFolderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String toFolderId = req.toFolderId().trim();
        folderRepository
            .findById(new UserConversationFolderId(userId, toFolderId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND"));
        NormalizedConversation conv = normalizeConversation(userId, req.chatType(), req.peerId());
        long nowMs = System.currentTimeMillis();
        memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
            userId, conv.chatType(), conv.peerId(), toFolderId);
        upsertMemberExclusive(userId, toFolderId, conv, nowMs);
        clearArchiveIfPresent(userId, conv, nowMs);
        UserConversationFolder folder = folderRepository
            .findById(new UserConversationFolderId(userId, toFolderId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND"));
        folder.setUpdatedAt(nowMs);
        folderRepository.save(folder);
        folderRealtime.batchChanged(userId, nowMs);
        return new MoveMemberResponse(true, toFolderId, conv.chatType(), conv.peerId(), nowMs);
    }

    @Transactional
    public ReplaceResponse replaceAll(String userId, List<ReplaceFolderItem> folders) {
        requireUserId(userId);
        if (folders == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<PreparedFolder> prepared = new ArrayList<>(folders.size());
        Set<String> folderIds = new HashSet<>();
        Set<String> nameKeys = new HashSet<>();
        if (folders.size() > MAX_FOLDERS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FOLDER_LIMIT_EXCEEDED");
        }
        // 先收集「会话 → 最后一次出现的 folderId」，保证最终一会话一组
        Map<String, String> peerOwnerFolder = new LinkedHashMap<>();
        Map<String, NormalizedConversation> peerConv = new HashMap<>();
        List<ReplaceFolderItem> ordered = new ArrayList<>(folders);
        for (ReplaceFolderItem item : ordered) {
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            String scope = item.scope() == null || item.scope().isBlank()
                ? "all"
                : normalizeScope(item.scope());
            String name = normalizeName(item.name());
            String nameKey = nameKeyOf(name);
            if (!nameKeys.add(nameKey)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "FOLDER_NAME_CONFLICT");
            }
            String folderId = item.folderId() == null || item.folderId().isBlank()
                ? newFolderId()
                : item.folderId().trim();
            if (!folderIds.add(folderId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            List<NormalizedConversation> members = new ArrayList<>();
            if (item.members() != null) {
                for (ReplaceMemberItem member : item.members()) {
                    if (member == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
                    }
                    NormalizedConversation conv = normalizeConversation(
                        userId, member.chatType(), member.peerId());
                    String peerKey = peerKey(conv);
                    peerOwnerFolder.put(peerKey, folderId);
                    peerConv.put(peerKey, conv);
                    members.add(conv);
                }
            }
            int sortOrder = item.sortOrder() != null ? item.sortOrder() : prepared.size();
            prepared.add(new PreparedFolder(folderId, name, nameKey, scope, sortOrder, members));
        }

        // 按 owner 过滤：同一会话只保留最后出现的那一组
        Map<String, List<NormalizedConversation>> exclusiveMembers = new HashMap<>();
        for (PreparedFolder folder : prepared) {
            exclusiveMembers.put(folder.folderId(), new ArrayList<>());
        }
        for (Map.Entry<String, String> e : peerOwnerFolder.entrySet()) {
            exclusiveMembers.get(e.getValue()).add(peerConv.get(e.getKey()));
        }

        long nowMs = System.currentTimeMillis();
        memberRepository.deleteByUserId(userId);
        folderRepository.deleteByUserId(userId);

        for (PreparedFolder folder : prepared) {
            UserConversationFolder row = new UserConversationFolder();
            row.setUserId(userId);
            row.setFolderId(folder.folderId());
            row.setName(folder.name());
            row.setNameKey(folder.nameKey());
            row.setScope(folder.scope());
            row.setSortOrder(folder.sortOrder());
            row.setCreatedAt(nowMs);
            row.setUpdatedAt(nowMs);
            folderRepository.save(row);
            for (NormalizedConversation conv : exclusiveMembers.getOrDefault(folder.folderId(), List.of())) {
                UserConversationFolderMember member = new UserConversationFolderMember();
                member.setUserId(userId);
                member.setFolderId(folder.folderId());
                member.setChatType(conv.chatType());
                member.setPeerId(conv.peerId());
                member.setUpdatedAt(nowMs);
                memberRepository.save(member);
                UserConversationArchiveId archiveId =
                    new UserConversationArchiveId(userId, conv.chatType(), conv.peerId());
                if (archiveRepository.existsById(archiveId)) {
                    archiveRepository.deleteById(archiveId);
                }
            }
        }
        folderRealtime.batchChanged(userId, nowMs);
        archiveRealtime.batchChanged(userId, nowMs);
        return new ReplaceResponse(true, prepared.size(), nowMs);
    }

    /** 归档某会话时调用：从所有分组移除该成员。返回是否曾存在成员行。 */
    @Transactional
    public boolean removePeerFromAllFolders(String userId, String chatType, String peerId) {
        requireUserId(userId);
        NormalizedConversation conv = normalizeConversation(userId, chatType, peerId);
        long existing = memberRepository.countByUserIdAndChatTypeAndPeerId(
            userId, conv.chatType(), conv.peerId());
        if (existing <= 0) {
            return false;
        }
        memberRepository.deleteByUserIdAndChatTypeAndPeerId(userId, conv.chatType(), conv.peerId());
        return true;
    }

    private void upsertMemberExclusive(
        String userId, String folderId, NormalizedConversation conv, long nowMs) {
        UserConversationFolderMember member = memberRepository
            .findById(new UserConversationFolderMemberId(
                userId, folderId, conv.chatType(), conv.peerId()))
            .orElseGet(() -> {
                UserConversationFolderMember created = new UserConversationFolderMember();
                created.setUserId(userId);
                created.setFolderId(folderId);
                created.setChatType(conv.chatType());
                created.setPeerId(conv.peerId());
                return created;
            });
        member.setUpdatedAt(nowMs);
        memberRepository.saveAndFlush(member);
    }

    private boolean clearArchiveIfPresent(String userId, NormalizedConversation conv, long nowMs) {
        UserConversationArchiveId archiveId =
            new UserConversationArchiveId(userId, conv.chatType(), conv.peerId());
        if (!archiveRepository.existsById(archiveId)) {
            return false;
        }
        archiveRepository.deleteById(archiveId);
        archiveRealtime.singleChanged(userId, conv.chatType(), conv.peerId(), false, null, nowMs);
        return true;
    }

    private void assertNameAvailable(String userId, String nameKey, String folderId) {
        if (folderRepository.existsByUserIdAndNameKeyAndFolderIdNot(userId, nameKey, folderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FOLDER_NAME_CONFLICT");
        }
    }

    private static FolderView toView(UserConversationFolder folder, List<MemberView> members) {
        return new FolderView(
            folder.getFolderId(),
            folder.getName(),
            folder.getScope(),
            folder.getSortOrder(),
            folder.getUpdatedAt(),
            folder.getCreatedAt(),
            members == null ? List.of() : members);
    }

    private static String newFolderId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalizeName(String name) {
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (trimmed.length() > MAX_NAME_LEN) {
            return trimmed.substring(0, MAX_NAME_LEN);
        }
        return trimmed;
    }

    private static String nameKeyOf(String normalizedName) {
        return normalizedName.toLowerCase(Locale.ROOT);
    }

    private static String peerKey(NormalizedConversation conv) {
        return conv.chatType() + '\0' + conv.peerId();
    }

    private static String normalizeScope(String scope) {
        String value = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (!"all".equals(value) && !"c2c".equals(value) && !"group".equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return value;
    }

    private static NormalizedConversation normalizeConversation(String userId, String chatType, String peerId) {
        String type = chatType == null ? "" : chatType.trim().toLowerCase(Locale.ROOT);
        String peer = peerId == null ? "" : peerId.trim();
        if (!"c2c".equals(type) && !"group".equals(type) || peer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if ("c2c".equals(type) && peer.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return new NormalizedConversation(type, peer);
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private record NormalizedConversation(String chatType, String peerId) {}

    private record PreparedFolder(
        String folderId,
        String name,
        String nameKey,
        String scope,
        int sortOrder,
        List<NormalizedConversation> members) {}
}
