/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.group;

import com.chat99.server.group.CommonGroupService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommonGroupController {
    private final CommonGroupService commonGroupService;

    public CommonGroupController(CommonGroupService commonGroupService) {
        this.commonGroupService = commonGroupService;
    }

    @GetMapping(value={"/users/{peerUserId}/common-groups"})
    public CommonGroupService.CommonGroupsResponse listCommonGroups(@PathVariable String peerUserId, @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset, Authentication auth) {
        return this.commonGroupService.listCommonGroups((String)auth.getPrincipal(), peerUserId, limit, offset);
    }
}
