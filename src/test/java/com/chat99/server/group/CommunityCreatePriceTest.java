package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.chat99.server.common.AppSettingRepository;
import com.chat99.server.wallet.WalletCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CommunityCreatePriceTest {
    @Test
    void defaultPriceIsTenThousandPlatformCoins() {
        GroupCreateLimitConfigService config = new GroupCreateLimitConfigService(
            mock(AppSettingRepository.class),
            new GroupCreateLimitProperties(true, 5, 3, 10000, 1000, true, false, true));
        assertThat(config.getCommunityPriceCurrency()).isEqualTo(WalletCurrency.PLATFORM);
        assertThat(config.getCommunityPriceMinor()).isEqualTo(1_000_000L);
    }

    @Test
    void paidGroupIdIsMixedTenCharsAndRequiresUuid() {
        String requestId = "f8f10aa6-7db0-4de0-a23a-0d720a598719";
        String groupId = GroupCreateService.paidGroupId(requestId);
        assertThat(groupId).startsWith("@TGS#_");
        assertThat(GroupCreateService.isMixedGroupToken(groupId.substring("@TGS#_".length()))).isTrue();
        assertThat(GroupCreateService.paidGroupId(requestId)).isEqualTo(groupId);
        assertThat(GroupCreateService.legacyPaidGroupId(requestId))
            .isEqualTo("@TGS#_Pf8f10aa67db04de0a23a0d720a598719");
        assertThatThrownBy(() -> GroupCreateService.paidGroupId("retry-1"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void randomGroupTokenAlwaysMixesLettersAndDigits() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 200; i++) {
            assertThat(GroupCreateService.isMixedGroupToken(GroupCreateService.randomMixedToken(random))).isTrue();
        }
    }
}
