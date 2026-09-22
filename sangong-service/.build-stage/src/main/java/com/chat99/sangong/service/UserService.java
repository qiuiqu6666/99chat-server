package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;
    private final ImService im;
    private final MainUserProfileService mainProfiles;

    public UserService(UserRepository users, @Lazy ImService im, MainUserProfileService mainProfiles) {
        this.users = users;
        this.im = im;
        this.mainProfiles = mainProfiles;
    }

    public SangongUser findOrCreateByImUserId(String imUserId, String nickname) {
        SangongUser user = users.findByImUserId(imUserId).orElse(null);
        if (user != null) {
            resolveNickname(user, nickname);
            return users.findById(user.getId()).orElse(user);
        }
        SangongUser created = users.insert(imUserId, nickname != null ? nickname : imUserId);
        resolveNickname(created, nickname);
        return users.findById(created.getId()).orElse(created);
    }

    public String resolveNickname(SangongUser user) {
        return resolveNickname(user, null);
    }

    /** 展示昵称：回调 hint > 库内昵称 > 主服务资料 > IM portrait_get（兜底）。 */
    public String resolveNickname(SangongUser user, String hint) {
        if (hint != null && !hint.isEmpty() && !hint.equals(user.getImUserId())) {
            if (!hint.equals(user.getNickname())) {
                users.updateNickname(user.getId(), hint);
                user.setNickname(hint);
            }
            return hint;
        }
        String stored = user.getNickname() == null ? "" : user.getNickname().trim();
        if (!stored.isEmpty() && !stored.equals(user.getImUserId())) {
            return stored;
        }
        String fromMain = mainProfiles.getNickname(user.getImUserId());
        if (fromMain == null || fromMain.isEmpty()) {
            fromMain = im.getPortraitNickname(user.getImUserId());
        }
        if (fromMain != null && !fromMain.isEmpty()) {
            if (!fromMain.equals(user.getNickname())) {
                users.updateNickname(user.getId(), fromMain);
                user.setNickname(fromMain);
            }
            return fromMain;
        }
        return !stored.isEmpty() ? stored : String.valueOf(user.getImUserId());
    }

    public SangongUser findByImUserId(String imUserId) {
        return users.findByImUserId(imUserId).orElse(null);
    }

    public SangongUser findById(long id) {
        return users.findById(id).orElse(null);
    }
}
