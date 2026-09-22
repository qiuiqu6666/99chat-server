package com.chat99.server.group;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupChangeEventController {

    private final GroupChangeEventService changeEventService;

    public GroupChangeEventController(GroupChangeEventService changeEventService) {
        this.changeEventService = changeEventService;
    }

    @GetMapping("/group/{groupId}/change-events")
    public GroupChangeEventService.GroupChangeEventsResponse listForGroup(
        @PathVariable String groupId,
        @RequestParam(defaultValue = "0") long since,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String actions,
        Authentication auth) {
        return changeEventService.listForGroup(
            groupId, (String) auth.getPrincipal(), since, limit, actions);
    }

    @GetMapping("/me/group-change-events")
    public GroupChangeEventService.MyGroupChangeEventsResponse listForUser(
        @RequestParam(defaultValue = "0") long since,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(required = false) String actions,
        Authentication auth) {
        return changeEventService.listForUser(
            (String) auth.getPrincipal(), since, limit, actions);
    }
}
