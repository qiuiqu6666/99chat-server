package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupTipCustomPushSupportTest {

    @Test
    void prefersPreviewAbstract() {
        Map<String, Object> data = base("member_set_admin");
        data.put("previewAbstract", "张三将李四设置为管理员");
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isEqualTo("张三将李四设置为管理员");
    }

    @Test
    void composesSetAdminWithoutPreview() {
        Map<String, Object> data = base("member_set_admin");
        data.put("opUserName", "张三");
        data.put("memberNames", List.of("李四"));
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isEqualTo("张三将李四设置为管理员");
    }

    @Test
    void composesMemberAddedMultiple() {
        Map<String, Object> data = base("member_added");
        data.put("opUserName", "张三");
        data.put("memberNames", List.of("李四", "王五"));
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isEqualTo("张三邀请李四、王五加入群组");
    }

    @Test
    void composesMemberLeftPrefersMemberName() {
        Map<String, Object> data = base("member_left");
        data.put("opUserName", "张三");
        data.put("memberNames", List.of("李四"));
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isEqualTo("李四退出群聊");
    }

    @Test
    void composesApplyJoinOptionChanged() {
        Map<String, Object> data = base("group_apply_join_option_changed");
        data.put("opUserName", "张三");
        data.put("detail", Map.of("applyJoinOption", "needPermission"));
        assertThat(GroupTipCustomPushSupport.pushBody(data))
            .isEqualTo("张三将申请加群方式修改为管理员审批");
    }

    @Test
    void unknownActionFallsBack() {
        Map<String, Object> data = base("something_else");
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isEqualTo("群提示");
    }

    @Test
    void neverReturnsBusinessId() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessID", "group_tip");
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isEqualTo("群提示");
        assertThat(GroupTipCustomPushSupport.pushBody(data)).isNotEqualTo("group_tip");
    }

    private static Map<String, Object> base(String action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessID", "group_tip");
        data.put("version", 1);
        data.put("action", action);
        data.put("opUserId", "user_a");
        data.put("memberUserIds", List.of("user_b"));
        return data;
    }
}
