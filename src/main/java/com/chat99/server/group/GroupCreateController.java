package com.chat99.server.group;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupCreateController {

    private final GroupCreateService createService;

    public GroupCreateController(GroupCreateService createService) {
        this.createService = createService;
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupProfileView createGroup(@Valid @RequestBody GroupCreateService.CreateGroupRequest body,
                                        Authentication auth) {
        return createService.createGroup((String) auth.getPrincipal(), body);
    }
}
