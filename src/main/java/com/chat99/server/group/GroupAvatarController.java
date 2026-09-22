package com.chat99.server.group;

import com.chat99.server.oss.OssProperties;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class GroupAvatarController {

    public record UploadResponse(String originUrl, String previewUrl, String thumbUrl, int avatarVersion) {}

    private static final Logger log = LoggerFactory.getLogger(GroupAvatarController.class);

    private final GroupAccessService access;
    private final GroupAvatarService avatarService;
    private final GroupProfileService profileService;
    private final OssProperties props;

    public GroupAvatarController(GroupAccessService access,
                                 GroupAvatarService avatarService,
                                 GroupProfileService profileService,
                                 OssProperties props) {
        this.access = access;
        this.avatarService = avatarService;
        this.profileService = profileService;
        this.props = props;
    }

    @PostMapping("/group/{groupId}/avatar")
    public UploadResponse uploadGroupAvatar(@PathVariable String groupId,
                                           @RequestParam("file") MultipartFile file,
                                           Authentication auth) throws IOException {
        log.info("uploadGroupAvatar entered groupId={} contentType={} size={}",
            groupId, file.getContentType(), file.getSize());
        String userId = (String) auth.getPrincipal();
        access.requireAdminRole(groupId, userId);

        String base = props.groupAvatarPrefix() + groupId + "/" + Instant.now().toEpochMilli()
            + "_" + UUID.randomUUID().toString().substring(0, 8);
        GroupAvatarService.UploadResult r = avatarService.uploadAvatar(file, base);
        GroupProfileView updated = profileService.updateAvatar(groupId, userId, r);
        UploadResponse response = new UploadResponse(r.originUrl(), r.previewUrl(), r.thumbUrl(), updated.avatarVersion());
        log.info("group avatar uploaded groupId={} userId={} previewUrl={} avatarVersion={}",
            groupId, userId, response.previewUrl(), response.avatarVersion());
        return response;
    }

    @PostMapping("/group/avatar/upload")
    public UploadResponse uploadPendingAvatar(@RequestParam("file") MultipartFile file,
                                             Authentication auth) throws IOException {
        String userId = (String) auth.getPrincipal();
        String base = props.groupAvatarPrefix() + "pending/" + userId + "/" + Instant.now().toEpochMilli()
            + "_" + UUID.randomUUID().toString().substring(0, 8);
        GroupAvatarService.UploadResult r = avatarService.uploadAvatar(file, base);
        return new UploadResponse(r.originUrl(), r.previewUrl(), r.thumbUrl(), 0);
    }
}
