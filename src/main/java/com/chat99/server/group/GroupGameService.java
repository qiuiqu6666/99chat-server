package com.chat99.server.group;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupGameService {

    private final GroupSettingsRepository settingsRepository;
    private final MeGroupsListCache meGroupsListCache;

    public GroupGameService(GroupSettingsRepository settingsRepository,
                            MeGroupsListCache meGroupsListCache) {
        this.settingsRepository = settingsRepository;
        this.meGroupsListCache = meGroupsListCache;
    }

    public boolean isGameEnabled(String groupId) {
        return settingsRepository.findById(groupId)
            .map(GroupSettings::isGameEnabled)
            .orElse(false);
    }

    @Transactional
    public boolean setGameEnabled(String groupId, boolean gameEnabled) {
        GroupSettings row = settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings created = new GroupSettings();
            created.setGroupId(groupId);
            return created;
        });
        row.setGameEnabled(gameEnabled);
        settingsRepository.save(row);
        meGroupsListCache.invalidateGroup(groupId);
        return gameEnabled;
    }
}
