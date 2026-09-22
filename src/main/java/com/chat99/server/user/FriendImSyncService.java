package com.chat99.server.user;

import com.chat99.server.im.ImAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自建好友写成功后同步腾讯 IM SNS。加/删/改备注与本地同一请求完成，失败抛错回滚。
 */
@Service
public class FriendImSyncService {

    private static final Logger log = LoggerFactory.getLogger(FriendImSyncService.class);
    private static final String ADD_SOURCE = "AddSource_Type_Server";

    private final ImAdminClient im;

    public FriendImSyncService(ImAdminClient im) {
        this.im = im;
    }

    public void syncAddBoth(String userA, String userB) {
        trySyncAddBoth(userA, userB);
    }

    public void syncDeleteBoth(String userA, String userB) {
        trySyncDeleteBoth(userA, userB);
    }

    public void syncRemark(String ownerUserId, String peerUserId, String remark) {
        trySyncRemark(ownerUserId, peerUserId, remark);
    }

    void trySyncAddBoth(String userA, String userB) {
        String a = trim(userA);
        String b = trim(userB);
        if (blank(a) || blank(b) || a.equals(b)) {
            return;
        }
        im.addFriendBoth(a, b, ADD_SOURCE, null);
        log.info("friend im sync add ok a={} b={}", a, b);
    }

    void trySyncDeleteBoth(String userA, String userB) {
        String a = trim(userA);
        String b = trim(userB);
        if (blank(a) || blank(b) || a.equals(b)) {
            return;
        }
        im.deleteFriendBoth(a, b);
        log.info("friend im sync delete ok a={} b={}", a, b);
    }

    void trySyncRemark(String ownerUserId, String peerUserId, String remark) {
        String owner = trim(ownerUserId);
        String peer = trim(peerUserId);
        if (blank(owner) || blank(peer) || owner.equals(peer)) {
            return;
        }
        String value = remark == null ? "" : remark.trim();
        im.updateFriendRemark(owner, peer, value);
        log.info("friend im sync remark ok owner={} peer={}", owner, peer);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
