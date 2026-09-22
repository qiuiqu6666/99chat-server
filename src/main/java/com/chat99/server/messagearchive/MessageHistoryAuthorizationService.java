package com.chat99.server.messagearchive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class MessageHistoryAuthorizationService {

    public void authorizeC2c(String userId, String peerUserId) {
        if (userId == null || peerUserId == null || userId.isBlank() || peerUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PEER");
        }
        if (userId.equals(peerUserId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PEER");
        }
    }
}