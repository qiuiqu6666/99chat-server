package com.chat99.server.moments;

import com.chat99.server.moments.MomentEnums.Visibility;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MomentSettingsService {

    private static final Set<Integer> ALLOWED_VISIBLE_RANGE_DAYS = Set.of(0, 3, 90, 180, 365);

    private final MomentSettingsRepository settingsRepository;
    private final MomentSettingsBlockedViewerRepository blockedViewerRepository;
    private final MomentSettingsHiddenAuthorRepository hiddenAuthorRepository;
    private final MomentVisibleUserRepository visibleUserRepository;
    private final UserFriendRepository friendRepository;
    private final UserFriendService friendService;
    private final UserRepository userRepository;

    public MomentSettingsService(MomentSettingsRepository settingsRepository,
                                 MomentSettingsBlockedViewerRepository blockedViewerRepository,
                                 MomentSettingsHiddenAuthorRepository hiddenAuthorRepository,
                                 MomentVisibleUserRepository visibleUserRepository,
                                 UserFriendRepository friendRepository,
                                 UserFriendService friendService,
                                 UserRepository userRepository) {
        this.settingsRepository = settingsRepository;
        this.blockedViewerRepository = blockedViewerRepository;
        this.hiddenAuthorRepository = hiddenAuthorRepository;
        this.visibleUserRepository = visibleUserRepository;
        this.friendRepository = friendRepository;
        this.friendService = friendService;
        this.userRepository = userRepository;
    }

    public record SettingsView(String coverUrl, int visibleRangeDays,
                               List<String> blockedViewerIds, List<String> hiddenAuthorIds) {}

    @Transactional(readOnly = true)
    public SettingsView getSettings(String userId) {
        requireActiveUser(userId);
        return toView(userId);
    }

    @Transactional
    public SettingsView updateSettings(String userId, String coverUrl, boolean coverUrlProvided,
                                       Integer visibleRangeDays,
                                       List<String> blockedViewerIds,
                                       List<String> hiddenAuthorIds) {
        requireActiveUser(userId);
        MomentSettings settings = settingsRepository.findByUserId(userId).orElseGet(() -> newSettings(userId));

        if (coverUrlProvided) {
            settings.setCoverUrl(trimToNull(coverUrl));
        }
        if (visibleRangeDays != null) {
            validateVisibleRangeDays(visibleRangeDays);
            settings.setVisibleRangeDays(visibleRangeDays);
        }
        settingsRepository.save(settings);

        if (blockedViewerIds != null) {
            replaceBlockedViewers(userId, normalizePeerIds(blockedViewerIds));
        }
        if (hiddenAuthorIds != null) {
            replaceHiddenAuthors(userId, normalizePeerIds(hiddenAuthorIds));
        }
        return toView(userId);
    }

    @Transactional(readOnly = true)
    public int getVisibleRangeDays(String userId) {
        return settingsRepository.findByUserId(userId)
            .map(MomentSettings::getVisibleRangeDays)
            .orElse(0);
    }

    @Transactional(readOnly = true)
    public Set<String> getHiddenAuthorIds(String userId) {
        return hiddenAuthorRepository.findByUserId(userId).stream()
            .map(MomentSettingsHiddenAuthor::getHiddenUserId)
            .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Set<String> getBlockedViewerIds(String authorUserId) {
        return blockedViewerRepository.findByUserId(authorUserId).stream()
            .map(MomentSettingsBlockedViewer::getBlockedUserId)
            .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Map<String, Set<String>> getBlockedViewerIdsByAuthors(Collection<String> authorUserIds) {
        if (authorUserIds == null || authorUserIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (MomentSettingsBlockedViewer row : blockedViewerRepository.findByUserIdIn(authorUserIds)) {
            result.computeIfAbsent(row.getUserId(), k -> new HashSet<>()).add(row.getBlockedUserId());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public boolean isBlockedViewer(String authorUserId, String viewerUserId) {
        if (authorUserId == null || viewerUserId == null || authorUserId.equals(viewerUserId)) {
            return false;
        }
        return blockedViewerRepository.existsByUserIdAndBlockedUserId(authorUserId, viewerUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Set<String>> getVisibleUsersByMomentIds(Collection<String> momentIds) {
        if (momentIds == null || momentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (MomentVisibleUser row : visibleUserRepository.findByMomentIdIn(momentIds)) {
            result.computeIfAbsent(row.getMomentId(), k -> new HashSet<>()).add(row.getUserId());
        }
        return result;
    }

    @Transactional
    public void saveVisibleUsers(String momentId, Collection<String> userIds) {
        visibleUserRepository.deleteByMomentId(momentId);
        for (String peerId : userIds) {
            MomentVisibleUser row = new MomentVisibleUser();
            row.setMomentId(momentId);
            row.setUserId(peerId);
            visibleUserRepository.save(row);
        }
    }

    public void validateVisibleUserIds(String authorUserId, List<String> userIds) {
        List<String> normalized = normalizePeerIds(userIds);
        if (normalized.isEmpty()) {
            throw badRequest("MOMENT_VISIBILITY_INVALID");
        }
        for (String peerId : normalized) {
            if (peerId.equals(authorUserId)) {
                continue;
            }
            if (!friendService.isMutualActive(authorUserId, peerId)) {
                throw badRequest("MOMENT_VISIBLE_USER_NOT_FRIEND");
            }
        }
    }

    public boolean canViewerSeeMoment(String viewerUserId, Moment moment,
                                      Set<String> authorBlockedViewers,
                                      Set<String> momentVisibleUsers) {
        if (moment == null) {
            return false;
        }
        String authorId = moment.getAuthorUserId();
        if (viewerUserId.equals(authorId)) {
            return true;
        }
        if (authorBlockedViewers != null && authorBlockedViewers.contains(viewerUserId)) {
            return false;
        }
        if (!friendService.isMutualActive(viewerUserId, authorId)) {
            return false;
        }
        Visibility visibility = moment.getVisibility() == null ? Visibility.FRIENDS : moment.getVisibility();
        Set<String> visibleUsers = momentVisibleUsers == null ? Set.of() : momentVisibleUsers;
        return switch (visibility) {
            case FRIENDS -> true;
            case EXCLUDE -> !visibleUsers.contains(viewerUserId);
            case PARTIAL -> visibleUsers.contains(viewerUserId);
        };
    }

    private SettingsView toView(String userId) {
        MomentSettings settings = settingsRepository.findByUserId(userId).orElseGet(() -> newSettings(userId));
        List<String> blocked = blockedViewerRepository.findByUserId(userId).stream()
            .map(MomentSettingsBlockedViewer::getBlockedUserId)
            .sorted()
            .toList();
        List<String> hidden = hiddenAuthorRepository.findByUserId(userId).stream()
            .map(MomentSettingsHiddenAuthor::getHiddenUserId)
            .sorted()
            .toList();
        return new SettingsView(settings.getCoverUrl(), settings.getVisibleRangeDays(), blocked, hidden);
    }

    private MomentSettings newSettings(String userId) {
        MomentSettings settings = new MomentSettings();
        settings.setUserId(userId);
        settings.setVisibleRangeDays(0);
        return settings;
    }

    private void replaceBlockedViewers(String userId, List<String> peerIds) {
        validatePeerList(userId, peerIds);
        blockedViewerRepository.deleteByUserId(userId);
        for (String peerId : peerIds) {
            if (peerId.equals(userId)) {
                continue;
            }
            MomentSettingsBlockedViewer row = new MomentSettingsBlockedViewer();
            row.setUserId(userId);
            row.setBlockedUserId(peerId);
            blockedViewerRepository.save(row);
        }
    }

    private void replaceHiddenAuthors(String userId, List<String> peerIds) {
        validatePeerList(userId, peerIds);
        hiddenAuthorRepository.deleteByUserId(userId);
        for (String peerId : peerIds) {
            if (peerId.equals(userId)) {
                continue;
            }
            MomentSettingsHiddenAuthor row = new MomentSettingsHiddenAuthor();
            row.setUserId(userId);
            row.setHiddenUserId(peerId);
            hiddenAuthorRepository.save(row);
        }
    }

    private void validatePeerList(String userId, List<String> peerIds) {
        for (String peerId : peerIds) {
            if (peerId.equals(userId)) {
                continue;
            }
            if (!userRepository.findByUserId(peerId).filter(u -> u.getStatus() == 1).isPresent()) {
                throw badRequest("MOMENT_VISIBLE_USER_NOT_FRIEND");
            }
        }
    }

    private static void validateVisibleRangeDays(int days) {
        if (!ALLOWED_VISIBLE_RANGE_DAYS.contains(days)) {
            throw badRequest("MOMENT_SETTINGS_INVALID");
        }
    }

    private void requireActiveUser(String userId) {
        userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MOMENT_NOT_FOUND"));
    }

    private static List<String> normalizePeerIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
            .map(MomentSettingsService::trimToNull)
            .filter(id -> id != null)
            .distinct()
            .toList();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
