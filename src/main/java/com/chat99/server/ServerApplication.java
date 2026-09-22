package com.chat99.server;

import com.chat99.server.admin.AdminProperties;
import com.chat99.server.adminapi.AdminApiProperties;
import com.chat99.server.adminapi.AdminUserGenerationProperties;
import com.chat99.server.common.TrustedProxyProperties;
import com.chat99.server.auth.AuthProperties;
import com.chat99.server.auth.QrLoginProperties;
import com.chat99.server.group.GroupCreateLimitProperties;
import com.chat99.server.group.GroupFanoutProperties;
import com.chat99.server.group.GroupPrivacyProperties;
import com.chat99.server.group.GroupProjectionSyncProperties;
import com.chat99.server.group.GroupProperties;
import com.chat99.server.sticker.StickerProperties;
import com.chat99.server.im.restqueue.ImRestQueueProperties;
import com.chat99.server.im.ImProperties;
import com.chat99.server.im.ImCallbackProperties;
import com.chat99.server.official.OfficialAccountProperties;
import com.chat99.server.platform.PlatformProperties;
import com.chat99.server.call.CallProperties;
import com.chat99.server.livekit.LiveKitProperties;
import com.chat99.server.sync.SyncProperties;
import com.chat99.server.chatattachment.ChatAttachmentProperties;
import com.chat99.server.oss.OssProperties;
import com.chat99.server.security.JwtProperties;
import com.chat99.server.sms.SmsProperties;
import com.chat99.server.user.DeviceProperties;
import com.chat99.server.user.GamePrivilegeProperties;
import com.chat99.server.user.NicknameProperties;
import com.chat99.server.user.PlatformIdProperties;
import com.chat99.server.user.LocationProperties;
import com.chat99.server.user.PresenceProperties;
import com.chat99.server.realtime.RealtimeProperties;
import com.chat99.server.user.UserFriendProperties;
import com.chat99.server.user.UserFriendSyncProperties;
import com.chat99.server.favorite.FavoriteProperties;
import com.chat99.server.announcement.AnnouncementGlobalPushProperties;
import com.chat99.server.notify.NotifyFriendBackfillProperties;
import com.chat99.server.notify.PlatformWalletNoticeProperties;
import com.chat99.server.notify.SystemNotifyProperties;
import com.chat99.server.telegram.TelegramOpsProperties;
import com.chat99.server.integration.IntegrationApiProperties;
import com.chat99.server.lifepayment.LifePaymentProperties;
import com.chat99.server.messagearchive.MessageArchiveProperties;
import com.chat99.server.push.PushProperties;
import com.chat99.server.robot.RobotSyncProperties;
import com.chat99.server.wallet.WalletCurrencyCatalogProperties;
import com.chat99.server.wallet.WalletOrderCardGuardProperties;
import com.chat99.server.wallet.WalletProperties;
import com.chat99.server.lifepayment.yuanren.YuanrenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    ImProperties.class,
    ImRestQueueProperties.class,
    ImCallbackProperties.class,
    JwtProperties.class,
    SmsProperties.class,
    PlatformIdProperties.class,
    NicknameProperties.class,
    DeviceProperties.class,
    GamePrivilegeProperties.class,
    PresenceProperties.class,
    LocationProperties.class,
    AuthProperties.class,
    QrLoginProperties.class,
    AdminProperties.class,
    AdminApiProperties.class,
    OssProperties.class,
    ChatAttachmentProperties.class,
    OfficialAccountProperties.class,
    SyncProperties.class,
    GroupPrivacyProperties.class,
    GroupProperties.class,
    GroupFanoutProperties.class,
    GroupCreateLimitProperties.class,
    GroupProjectionSyncProperties.class,
    StickerProperties.class,
    WalletProperties.class,
    WalletOrderCardGuardProperties.class,
    WalletCurrencyCatalogProperties.class,
    PlatformProperties.class,
    CallProperties.class,
    LiveKitProperties.class,
    FavoriteProperties.class,
    SystemNotifyProperties.class,
    PlatformWalletNoticeProperties.class,
    TelegramOpsProperties.class,
    NotifyFriendBackfillProperties.class,
    RealtimeProperties.class,
    UserFriendProperties.class,
    UserFriendSyncProperties.class,
    AnnouncementGlobalPushProperties.class,
    PushProperties.class,
    MessageArchiveProperties.class,
    IntegrationApiProperties.class,
    LifePaymentProperties.class,
    YuanrenProperties.class,
    AdminUserGenerationProperties.class,
    TrustedProxyProperties.class,
    RobotSyncProperties.class
})
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
