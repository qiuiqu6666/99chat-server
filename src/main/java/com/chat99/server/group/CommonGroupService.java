/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.group;

import com.chat99.server.group.CommonGroupService;
import com.chat99.server.group.GroupAvatarDefaults;
import com.chat99.server.group.GroupMember;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileView;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.user.UserRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommonGroupService {
    private final GroupMemberRepository memberRepository;
    private final GroupProjectionService projection;
    private final GroupAvatarDefaults avatarDefaults;
    private final UserRepository userRepository;
    private final GroupGameService groupGameService;

    public CommonGroupService(GroupMemberRepository memberRepository, GroupProjectionService projection, GroupAvatarDefaults avatarDefaults, UserRepository userRepository, GroupGameService groupGameService) {
        this.memberRepository = memberRepository;
        this.projection = projection;
        this.avatarDefaults = avatarDefaults;
        this.userRepository = userRepository;
        this.groupGameService = groupGameService;
    }

    public CommonGroupsResponse listCommonGroups(String userId, String peerUserId, int limit, int offset) {
        String peer = CommonGroupService.normalizePeerUserId(userId, peerUserId);
        this.requireExistingUser(peer);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        int safeOffset = Math.max(offset, 0);
        Page<GroupMember> page = this.memberRepository.findCommonGroups(userId, peer, (Pageable)PageRequest.of((int)(safeOffset / safeLimit), (int)safeLimit));
        List<GroupProfileView> items = page.getContent().stream().map(member -> this.toView((GroupMember)member)).filter(view -> view != null).toList();
        long total = this.memberRepository.countCommonGroups(userId, peer);
        return new CommonGroupsResponse(peer, items, total, safeLimit, safeOffset);
    }

    private GroupProfileView toView(GroupMember member) {
        boolean gameEnabled = this.groupGameService.isGameEnabled(member.getGroupId());
        return this.avatarDefaults.applyToView((GroupProfileView)this.projection.findProfile(member.getGroupId()).map(profile -> GroupProfileView.forList((GroupProfile)profile, (GroupMember)member, gameEnabled)).orElse(null));
    }

    private void requireExistingUser(String peerUserId) {
        this.userRepository.findByUserId(peerUserId).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private static String normalizePeerUserId(String userId, String peerUserId) {
        if (peerUserId == null || peerUserId.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String peer = peerUserId.trim();
        if (peer.length() > 32) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (userId.equals(peer)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "CANNOT_QUERY_SELF");
        }
        return peer;
    }




    public record CommonGroupsResponse(String peerUserId, List<GroupProfileView> items, long total, int limit, int offset) {}
}
