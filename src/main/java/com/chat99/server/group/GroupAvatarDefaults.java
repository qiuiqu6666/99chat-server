package com.chat99.server.group;

import com.chat99.server.oss.OssPublicUrl;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class GroupAvatarDefaults {

    private final GroupProperties props;

    public GroupAvatarDefaults(GroupProperties props) {
        this.props = props;
    }

    public String defaultAvatarUrl() {
        String url = props.defaultAvatarUrl();
        return url == null || url.isBlank() ? null : url.trim();
    }

    /** 有自定义头像则返回 trimmed；否则返回默认群头像（可能为 null）。 */
    public String resolve(String avatarUrl) {
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            return OssPublicUrl.normalizePublicUrl(avatarUrl.trim());
        }
        return defaultAvatarUrl();
    }

    public GroupProfileView applyToView(GroupProfileView view) {
        if (view == null) {
            return null;
        }
        String resolved = resolve(view.avatarUrl());
        if (Objects.equals(resolved, view.avatarUrl())) {
            return view;
        }
        return view.withAvatarUrl(resolved);
    }
}
