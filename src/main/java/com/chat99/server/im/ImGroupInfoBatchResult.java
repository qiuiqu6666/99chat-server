package com.chat99.server.im;

import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import java.util.Map;

/** 批量 get_group_info：found 仅含成功项；rateLimited/transportError 时缺失项不可当作已解散。 */
public record ImGroupInfoBatchResult(
    Map<String, GroupAdminInfo> found,
    boolean rateLimited,
    boolean transportError
) {
    public boolean isUnreliable() {
        return rateLimited || transportError;
    }
}
