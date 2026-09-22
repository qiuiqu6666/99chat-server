package com.chat99.server.user;

import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AvatarPreviewController {

    private final UserRepository userRepository;
    private final GroupProfileRepository groupProfileRepository;

    public AvatarPreviewController(UserRepository userRepository,
                                   GroupProfileRepository groupProfileRepository) {
        this.userRepository = userRepository;
        this.groupProfileRepository = groupProfileRepository;
    }

    public record AvatarPreviewResponse(String previewUrl, int avatarVersion) {}

    @GetMapping("/users/{userId}/avatar-preview")
    public AvatarPreviewResponse getUserAvatarPreview(@PathVariable String userId) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (user.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        String previewUrl = user.getAvatarPreviewUrl();
        if (previewUrl == null || previewUrl.isBlank()) {
            previewUrl = user.getAvatarUrl();
        }
        return new AvatarPreviewResponse(previewUrl, user.getAvatarVersion());
    }

    @GetMapping("/groups/{groupId}/avatar-preview")
    public AvatarPreviewResponse getGroupAvatarPreview(@PathVariable String groupId) {
        GroupProfile profile = groupProfileRepository.findById(groupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
        String previewUrl = profile.getAvatarPreviewUrl();
        if (previewUrl == null || previewUrl.isBlank()) {
            previewUrl = profile.getAvatarUrl();
        }
        return new AvatarPreviewResponse(previewUrl, profile.getAvatarVersion());
    }
}
