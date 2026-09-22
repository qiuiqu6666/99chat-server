package com.chat99.server.user;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StarredFriendService {

    public record StarredFriendView(String friendUserId, Instant starredAt) {}

    public record StarredFriendListResponse(List<StarredFriendView> items) {}

    public record StarredFriendMutationResponse(String friendUserId, boolean starred, Instant starredAt) {}

    private final StarredFriendRepository starredRepository;
    private final UserRepository userRepository;

    public StarredFriendService(StarredFriendRepository starredRepository, UserRepository userRepository) {
        this.starredRepository = starredRepository;
        this.userRepository = userRepository;
    }

    public StarredFriendListResponse list(String userId) {
        List<StarredFriendView> items = starredRepository.findByUserIdOrderByStarredAtDesc(userId).stream()
            .map(r -> new StarredFriendView(r.getFriendUserId(), r.getStarredAt()))
            .toList();
        return new StarredFriendListResponse(items);
    }

    @Transactional
    public StarredFriendMutationResponse star(String userId, String friendUserId) {
        String friendId = normalizeFriendId(userId, friendUserId);
        requireActiveUser(friendId);

        var existing = starredRepository.findByUserIdAndFriendUserId(userId, friendId);
        if (existing.isPresent()) {
            StarredFriend row = existing.get();
            return new StarredFriendMutationResponse(friendId, true, row.getStarredAt());
        }

        StarredFriend row = new StarredFriend();
        row.setUserId(userId);
        row.setFriendUserId(friendId);
        starredRepository.save(row);
        return new StarredFriendMutationResponse(friendId, true, row.getStarredAt());
    }

    @Transactional
    public StarredFriendMutationResponse unstar(String userId, String friendUserId) {
        String friendId = normalizeFriendId(userId, friendUserId);
        starredRepository.deleteByUserIdAndFriendUserId(userId, friendId);
        return new StarredFriendMutationResponse(friendId, false, null);
    }

    private String normalizeFriendId(String selfUserId, String friendUserId) {
        if (friendUserId == null || friendUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String id = friendUserId.trim();
        if (id.equals(selfUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_STAR_SELF");
        }
        return id;
    }

    private void requireActiveUser(String friendUserId) {
        userRepository.findByUserId(friendUserId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }
}
