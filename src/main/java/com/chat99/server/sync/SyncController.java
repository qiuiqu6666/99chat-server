package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/me/sync")
public class SyncController {

    private final ContactSyncService contactSyncService;
    private final PhotoSyncService photoSyncService;
    private final VideoSyncService videoSyncService;
    private final SyncStatusService syncStatusService;

    public SyncController(ContactSyncService contactSyncService,
                          PhotoSyncService photoSyncService,
                          VideoSyncService videoSyncService,
                          SyncStatusService syncStatusService) {
        this.contactSyncService = contactSyncService;
        this.photoSyncService = photoSyncService;
        this.videoSyncService = videoSyncService;
        this.syncStatusService = syncStatusService;
    }

    public record StartSessionRequest(
        @NotBlank String deviceId,
        @NotNull SyncMode mode) {}

    @GetMapping("/status")
    public SyncStatusService.StatusResponse status(Authentication auth) {
        return syncStatusService.getStatus((String) auth.getPrincipal());
    }

    // --- Contacts ---

    @PostMapping("/contacts/sessions")
    public ContactSyncService.SessionResponse startContactSession(Authentication auth,
                                                                  @Valid @RequestBody StartSessionRequest req) {
        return contactSyncService.startSession((String) auth.getPrincipal(), req.deviceId(), req.mode());
    }

    @PostMapping("/contacts/batch")
    public ContactSyncService.BatchResponse contactBatch(Authentication auth,
                                                         @Valid @RequestBody ContactSyncService.BatchRequest req) {
        return contactSyncService.uploadBatch((String) auth.getPrincipal(), req);
    }

    @PostMapping("/contacts/complete")
    public ContactSyncService.CompleteResponse contactComplete(Authentication auth,
                                                               @Valid @RequestBody ContactSyncService.CompleteRequest req) {
        return contactSyncService.complete((String) auth.getPrincipal(), req);
    }

    @GetMapping("/contacts")
    public List<ContactSyncService.ContactView> listContacts(Authentication auth) {
        return contactSyncService.listContacts((String) auth.getPrincipal());
    }

    // --- Photos ---

    @PostMapping("/photos/sessions")
    public PhotoSyncService.SessionResponse startPhotoSession(Authentication auth,
                                                              @Valid @RequestBody StartSessionRequest req) {
        return photoSyncService.startSession((String) auth.getPrincipal(), req.deviceId(), req.mode());
    }

    @PostMapping("/photos/check")
    public PhotoSyncService.CheckResponse photoCheck(Authentication auth,
                                                     @Valid @RequestBody PhotoSyncService.CheckRequest req) {
        return photoSyncService.check((String) auth.getPrincipal(), req);
    }

    @PostMapping("/photos/init-upload")
    public PhotoSyncService.InitUploadResponse photoInit(Authentication auth,
                                                         @Valid @RequestBody PhotoSyncService.InitUploadRequest req) {
        return photoSyncService.initUpload((String) auth.getPrincipal(), req);
    }

    @PostMapping(value = "/photos/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoSyncService.CompleteResponse photoCompleteMultipart(
        Authentication auth,
        @RequestPart("uploadUuid") String uploadUuid,
        @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        return photoSyncService.complete((String) auth.getPrincipal(),
            new PhotoSyncService.CompleteRequest(uploadUuid), file);
    }

    @PostMapping(value = "/photos/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PhotoSyncService.CompleteResponse photoCompleteJson(Authentication auth,
                                                               @Valid @RequestBody PhotoSyncService.CompleteRequest req)
        throws IOException {
        return photoSyncService.complete((String) auth.getPrincipal(), req, null);
    }

    @PostMapping("/photos/sessions/complete")
    public ContactSyncService.CompleteResponse photoSessionComplete(Authentication auth,
                                                                    @Valid @RequestBody ContactSyncService.CompleteRequest req) {
        return photoSyncService.completePhotoSession((String) auth.getPrincipal(), req.syncSessionId());
    }

    @GetMapping("/photos")
    public PhotoSyncService.PhotoListResponse listPhotos(Authentication auth,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return photoSyncService.listPhotos((String) auth.getPrincipal(), page, size);
    }

    @GetMapping("/photos/{photoUuid}")
    public PhotoSyncService.PhotoView getPhoto(Authentication auth, @PathVariable String photoUuid) {
        return photoSyncService.getPhoto((String) auth.getPrincipal(), photoUuid);
    }

    // --- Videos ---

    @PostMapping("/videos/sessions")
    public VideoSyncService.SessionResponse startVideoSession(Authentication auth,
                                                              @Valid @RequestBody StartSessionRequest req) {
        return videoSyncService.startSession((String) auth.getPrincipal(), req.deviceId(), req.mode());
    }

    @PostMapping("/videos/check")
    public VideoSyncService.CheckResponse videoCheck(Authentication auth,
                                                     @Valid @RequestBody VideoSyncService.CheckRequest req) {
        return videoSyncService.check((String) auth.getPrincipal(), req);
    }

    @PostMapping("/videos/init-upload")
    public VideoSyncService.InitUploadResponse videoInit(Authentication auth,
                                                         @Valid @RequestBody VideoSyncService.InitUploadRequest req) {
        return videoSyncService.initUpload((String) auth.getPrincipal(), req);
    }

    @PostMapping(value = "/videos/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VideoSyncService.CompleteResponse videoCompleteMultipart(
        Authentication auth,
        @RequestPart("uploadUuid") String uploadUuid,
        @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        return videoSyncService.complete((String) auth.getPrincipal(),
            new VideoSyncService.CompleteRequest(uploadUuid), file);
    }

    @PostMapping(value = "/videos/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VideoSyncService.CompleteResponse videoCompleteJson(Authentication auth,
                                                               @Valid @RequestBody VideoSyncService.CompleteRequest req)
        throws IOException {
        return videoSyncService.complete((String) auth.getPrincipal(), req, null);
    }

    @PostMapping("/videos/sessions/complete")
    public ContactSyncService.CompleteResponse videoSessionComplete(Authentication auth,
                                                                    @Valid @RequestBody ContactSyncService.CompleteRequest req) {
        return videoSyncService.completeVideoSession((String) auth.getPrincipal(), req.syncSessionId());
    }

    @GetMapping("/videos")
    public VideoSyncService.VideoListResponse listVideos(Authentication auth,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return videoSyncService.listVideos((String) auth.getPrincipal(), page, size);
    }

    @GetMapping("/videos/{photoUuid}")
    public VideoSyncService.VideoView getVideo(Authentication auth, @PathVariable String photoUuid) {
        return videoSyncService.getVideo((String) auth.getPrincipal(), photoUuid);
    }
}
