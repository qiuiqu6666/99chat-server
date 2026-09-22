package com.chat99.server.user;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.sync.SyncProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContactMatchService {

    private final UserRepository userRepository;
    private final UserFriendRepository friendRepository;
    private final PhoneUtils phoneUtils;
    private final UserPrivacyService privacyService;
    private final ContactMatchRateLimiter rateLimiter;
    private final int maxPhones;

    public ContactMatchService(UserRepository userRepository,
                               UserFriendRepository friendRepository,
                               PhoneUtils phoneUtils,
                               UserPrivacyService privacyService,
                               ContactMatchRateLimiter rateLimiter,
                               SyncProperties syncProperties) {
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.phoneUtils = phoneUtils;
        this.privacyService = privacyService;
        this.rateLimiter = rateLimiter;
        this.maxPhones = syncProperties.maxContactsBatch();
    }

    public record MatchCommand(List<String> phones, String phoneCountry, Boolean includeFriendStatus) {}

    public record MatchResponse(List<ContactMatchItem> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContactMatchItem(
        String phone,
        boolean registered,
        String userId,
        String nickname,
        String avatarUrl,
        Boolean isFriend,
        Long lastActiveAt,
        LastActiveVisibility lastActiveVisibility) {}

    public MatchResponse match(String selfUserId, MatchCommand command) {
        if (command.phones() == null || command.phones().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        rateLimiter.check(selfUserId);

        LinkedHashMap<String, String> normalizedByPhone = new LinkedHashMap<>();
        for (String raw : command.phones()) {
            if (raw == null || raw.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            String e164;
            try {
                e164 = phoneUtils.normalizeContactPhone(raw, command.phoneCountry());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            normalizedByPhone.putIfAbsent(e164, e164);
        }

        if (normalizedByPhone.size() > maxPhones) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOO_MANY_PHONES");
        }

        boolean includeFriendStatus = command.includeFriendStatus() == null || command.includeFriendStatus();
        Map<String, User> usersByPhone = loadActiveUsersByPhone(normalizedByPhone.keySet());
        Set<String> mutualFriendIds = includeFriendStatus
            ? loadMutualFriendIds(selfUserId, usersByPhone.values())
            : Set.of();

        List<ContactMatchItem> items = new ArrayList<>(normalizedByPhone.size());
        for (String phone : normalizedByPhone.keySet()) {
            User user = usersByPhone.get(phone);
            if (user == null) {
                items.add(new ContactMatchItem(phone, false, null, null, null, null, null, null));
                continue;
            }
            Boolean isFriend = includeFriendStatus ? mutualFriendIds.contains(user.getUserId()) : null;
            Long lastActiveAt = privacyService.lastActiveAtEpochMillis(user);
            items.add(new ContactMatchItem(
                phone,
                true,
                user.getUserId(),
                user.getNickname(),
                user.getAvatarUrl(),
                isFriend,
                lastActiveAt,
                privacyService.lastActiveVisibilityOf(user)));
        }
        return new MatchResponse(items);
    }

    private Map<String, User> loadActiveUsersByPhone(Set<String> phones) {
        if (phones.isEmpty()) {
            return Map.of();
        }
        Map<String, User> out = new HashMap<>();
        for (User user : userRepository.findByPhoneIn(phones)) {
            if (user.getStatus() != 1) {
                continue;
            }
            if (!phoneUtils.hasBoundPhone(user.getPhone())) {
                continue;
            }
            out.put(user.getPhone(), user);
        }
        return out;
    }

    private Set<String> loadMutualFriendIds(String selfUserId, Iterable<User> users) {
        List<String> peerIds = new ArrayList<>();
        for (User user : users) {
            if (!selfUserId.equals(user.getUserId())) {
                peerIds.add(user.getUserId());
            }
        }
        if (peerIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(friendRepository.findMutualFriendUserIdsAmong(selfUserId, peerIds));
    }
}
