package com.chat99.server.messagearchive;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.im.ImCallbackVerifier;
import com.chat99.server.push.PushConfigService;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ImMessageRecallService {

    private static final Logger log = LoggerFactory.getLogger(ImMessageRecallService.class);
    private static final int MONTHS_TO_SEARCH = 2;

    private final ImMessageRecallParser parser;
    private final ChatMessageWriteRepository writeRepository;
    private final ChatMessageTableRouter tableRouter;
    private final ImCallbackVerifier callbackVerifier;
    private final PushConfigService pushConfig;
    private final AppSettingService settings;

    public ImMessageRecallService(ImMessageRecallParser parser,
                                  ChatMessageWriteRepository writeRepository,
                                  ChatMessageTableRouter tableRouter,
                                  ImCallbackVerifier callbackVerifier,
                                  PushConfigService pushConfig,
                                  AppSettingService settings) {
        this.parser = parser;
        this.writeRepository = writeRepository;
        this.tableRouter = tableRouter;
        this.callbackVerifier = callbackVerifier;
        this.pushConfig = pushConfig;
        this.settings = settings;
    }

    public boolean isRecallCommand(String command) {
        return parser.isRecallCommand(command);
    }

    public void syncRecall(String sdkAppId,
                           String command,
                           String callbackToken,
                           String sign,
                           String requestTime,
                           String rawBody) {
        verifyAuth(sdkAppId, callbackToken, sign, requestTime);
        Optional<ImMessageRecallEvent> eventOpt = parser.parse(rawBody, command);
        if (eventOpt.isEmpty()) {
            return;
        }
        ImMessageRecallEvent event = eventOpt.get();
        List<String> tables = tableRouter.physicalTablesAround(Instant.ofEpochMilli(event.eventTimeMs()), MONTHS_TO_SEARCH);
        int updated = 0;
        for (String table : tables) {
            updated += writeRepository.batchMarkRevoked(table, event.msgKeys());
        }
        if (updated > 0) {
            log.info("im archive recall synced cmd={} msgKeys={} updated={}",
                command, event.msgKeys().size(), updated);
        } else {
            log.debug("im archive recall no rows updated cmd={} msgKeys={}", command, event.msgKeys());
        }
    }

    private void verifyAuth(String sdkAppId, String callbackToken, String sign, String requestTime) {
        validateSdkAppId(sdkAppId);
        if (sign != null && !sign.isBlank()) {
            callbackVerifier.verifySignature(sign, requestTime);
        } else {
            callbackVerifier.verifyQueryToken(callbackToken);
        }
    }

    private void validateSdkAppId(String sdkAppId) {
        if (sdkAppId == null || sdkAppId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Set<String> allowed = allowedSdkAppIds();
        if (!allowed.isEmpty() && !allowed.contains(sdkAppId.trim())) {
            log.warn("im archive recall rejected sdkAppId={}", sdkAppId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private Set<String> allowedSdkAppIds() {
        String csv = pushConfig.getAllowedSdkAppIds();
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        int configured = settings.getInt(AppSettingService.IM_SDK_APP_ID, 0);
        if (configured != 0) {
            return Set.of(String.valueOf(configured));
        }
        return Set.of();
    }
}
