package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupGameIdService {

    private static final int MAX_LEN = 128;

    private final GroupProfileRepository profileRepository;
    private final GroupSettingsRepository settingsRepository;
    private final ImAdminClient imAdminClient;
    private final MeGroupsListCache meGroupsListCache;

    public GroupGameIdService(GroupProfileRepository profileRepository,
                              GroupSettingsRepository settingsRepository,
                              ImAdminClient imAdminClient,
                              MeGroupsListCache meGroupsListCache) {
        this.profileRepository = profileRepository;
        this.settingsRepository = settingsRepository;
        this.imAdminClient = imAdminClient;
        this.meGroupsListCache = meGroupsListCache;
    }

    public String getGameid(String groupId) {
        return profileRepository.findById(groupId)
            .map(profile -> profile.getGameid() == null ? "" : profile.getGameid())
            .orElseGet(() -> settingsRepository.findById(groupId)
                .map(settings -> settings.getGameid() == null ? "" : settings.getGameid())
                .orElse(""));
    }

    @Transactional
    public String setGameid(String groupId, String raw) {
        String value = normalize(raw);
        GroupProfile profile = profileRepository.findById(groupId).orElseGet(() -> {
            GroupProfile created = new GroupProfile();
            created.setGroupId(groupId);
            created.setGroupType("");
            created.setGroupName("");
            created.setDisplayAlias("");
            created.setGameid("");
            return created;
        });
        GroupSettings settings = settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings created = new GroupSettings();
            created.setGroupId(groupId);
            return created;
        });
        profile.setGameid(value);
        settings.setGameid(value);
        profileRepository.save(profile);
        settingsRepository.save(settings);
        imAdminClient.modifyGroupAppDefinedGameid(groupId, value);
        meGroupsListCache.invalidateGroup(groupId);
        return value;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return trimmed;
    }
}
