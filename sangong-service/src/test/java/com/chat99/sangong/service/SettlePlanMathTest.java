package com.chat99.sangong.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chat99.sangong.domain.SangongBet;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongRoundDraw;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.BetRepository;
import com.chat99.sangong.repository.DrawRepository;
import com.chat99.sangong.repository.RoundRepository;
import com.chat99.sangong.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 结算分账数学：与 PHP SettleService::buildSettlementPlan 对齐。 */
class SettlePlanMathTest {
    private final HandTypeService hands = new HandTypeService();

    private BetRepository betRepo;
    private DrawRepository drawRepo;
    private RoundService rounds;
    private GameSettingsService settings;
    private UserService users;
    private SettleService settle;

    @BeforeEach
    void setUp() {
        betRepo = mock(BetRepository.class);
        drawRepo = mock(DrawRepository.class);
        rounds = mock(RoundService.class);
        settings = mock(GameSettingsService.class);
        users = mock(UserService.class);
        settle = new SettleService(
            new CompareService(),
            mock(DrawService.class),
            rounds,
            mock(BalanceService.class),
            settings,
            users,
            mock(ImService.class),
            mock(BetService.class),
            betRepo,
            drawRepo,
            mock(RoundRepository.class),
            mock(UserRepository.class),
            mock(RealtimeVersionStore.class),
            mock(ImMessageService.class),
            mock(RebateService.class),
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.transaction.PlatformTransactionManager.class));
        when(settings.getDoorCount()).thenReturn(6);
        when(settings.getBankerRakePercent()).thenReturn(5);
        when(users.findById(anyLong())).thenAnswer(inv -> {
            SangongUser u = new SangongUser();
            u.setId(inv.getArgument(0, Long.class));
            u.setNickname("U" + inv.getArgument(0, Long.class));
            return u;
        });
        when(users.resolveNickname(org.mockito.ArgumentMatchers.any(SangongUser.class)))
            .thenAnswer(inv -> inv.getArgument(0, SangongUser.class).getNickname());
    }

    private SangongRoundDraw draw(long roundId, int door, int hundredths) {
        HandTypeService.HandInfo info = hands.analyze(hundredths);
        SangongRoundDraw d = new SangongRoundDraw();
        d.setRoundId(roundId);
        d.setDoor(door);
        d.setAmountHundredths(hundredths);
        d.setHandType(info.handType());
        d.setHandLabel(info.handLabel());
        d.setPointValue(info.pointValue());
        d.setPairValue(info.pairValue());
        d.setCompareValue(info.compareValue());
        return d;
    }

    private SangongBet bet(long userId, int door, long amount) {
        SangongBet b = new SangongBet();
        b.setUserId(userId);
        b.setDoor(door);
        b.setAmount(amount);
        return b;
    }

    private SangongRound round() {
        SangongRound r = new SangongRound();
        r.setId(10);
        r.setSessionId(1);
        r.setPeriodNo(3);
        r.setBankerUserId(201L);
        r.setBankerDoor(4);
        r.setStatus(SangongRound.BETTING);
        return r;
    }

    @Test
    void planMatchesPhpMath() {
        SangongRound round = round();
        // 庄门4=对子55；门1=5点；门2=对子99；门3=牛牛；门5=9点；门6=对子11
        when(drawRepo.listByRound(10)).thenReturn(List.of(
            draw(10, 1, 23), draw(10, 2, 99), draw(10, 3, 46),
            draw(10, 4, 55), draw(10, 5, 9), draw(10, 6, 11)));
        when(betRepo.listByRound(10)).thenReturn(List.of(
            bet(101, 1, 1000),   // 对子55 > 5点 → 庄赢
            bet(102, 2, 2000),   // 对子55 < 对子99 → 闲赢
            bet(101, 3, 500)));  // 对子55 > 牛牛 → 庄赢

        // 合庄池：主庄 3000 + 合庄 1000
        Map<String, Object> coBank = new LinkedHashMap<>();
        coBank.put("poolTotal", 4000L);
        coBank.put("coBankerCount", 1);
        Map<String, Object> m1 = new LinkedHashMap<>(Map.of(
            "userId", 201L, "nickname", "庄", "amount", 3000L, "isMainBanker", true, "sharePercent", 75.0));
        Map<String, Object> m2 = new LinkedHashMap<>(Map.of(
            "userId", 202L, "nickname", "合", "amount", 1000L, "isMainBanker", false, "sharePercent", 25.0));
        coBank.put("members", List.of(m1, m2));
        when(rounds.getCoBankSummary(10)).thenReturn(coBank);

        Map<String, Object> plan = settle.buildSettlementPlan(round);

        assertEquals(3500L, plan.get("totalIdleBets"));
        assertEquals(-500L, plan.get("bankerGross")); // +1000 -2000 +500
        assertEquals(175L, plan.get("rake"));         // floor(3500*5/100)
        assertEquals(-675L, plan.get("bankerNet"));

        // 闲家派彩：仅 user102 赢，2 倍本金
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> playerCredits = (List<Map<String, Object>>) plan.get("playerCredits");
        assertEquals(1, playerCredits.size());
        assertEquals(102L, ((Number) playerCredits.get(0).get("userId")).longValue());
        assertEquals(4000L, ((Number) playerCredits.get(0).get("payout")).longValue());

        // 庄方分摊：floor(-675*3000/4000)=-507, floor(-675*1000/4000)=-169
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bankerCredits = (List<Map<String, Object>>) plan.get("bankerCredits");
        assertEquals(2, bankerCredits.size());
        assertEquals(-507L, ((Number) bankerCredits.get(0).get("delta")).longValue());
        assertEquals(-169L, ((Number) bankerCredits.get(1).get("delta")).longValue());
        // 尾差归系统：-675 - (-676) = 1
        assertEquals(1L, plan.get("systemRemainder"));

        // 玩家汇总
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) plan.get("players");
        assertEquals(2, players.size());
        Map<String, Object> p101 = players.stream()
            .filter(p -> ((Number) p.get("userId")).longValue() == 101L).findFirst().orElseThrow();
        assertEquals(1500L, p101.get("totalBet"));
        assertEquals(1500L, p101.get("totalLoss"));
        assertEquals(-1500L, p101.get("net"));
        Map<String, Object> p102 = players.stream()
            .filter(p -> ((Number) p.get("userId")).longValue() == 102L).findFirst().orElseThrow();
        assertEquals(2000L, p102.get("totalWin"));
        assertEquals(2000L, p102.get("net"));
    }

    @Test
    void singleBankerTakesFullNetWithoutPool() {
        SangongRound round = round();
        when(drawRepo.listByRound(10)).thenReturn(List.of(draw(10, 4, 55), draw(10, 1, 23)));
        when(betRepo.listByRound(10)).thenReturn(List.of(bet(101, 1, 1000)));

        Map<String, Object> coBank = new LinkedHashMap<>();
        coBank.put("poolTotal", 0L);
        coBank.put("coBankerCount", 0);
        coBank.put("members", List.of());
        when(rounds.getCoBankSummary(10)).thenReturn(coBank);

        Map<String, Object> plan = settle.buildSettlementPlan(round);
        // 庄赢 1000，抽水 floor(1000*5/100)=50，净 950 全归庄
        assertEquals(950L, plan.get("bankerNet"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bankerCredits = (List<Map<String, Object>>) plan.get("bankerCredits");
        assertEquals(1, bankerCredits.size());
        assertEquals(201L, ((Number) bankerCredits.get(0).get("userId")).longValue());
        assertEquals(950L, ((Number) bankerCredits.get(0).get("delta")).longValue());
        assertEquals(0L, plan.get("systemRemainder"));
    }

    @Test
    void missingBankerDrawRejected() {
        SangongRound round = round();
        when(drawRepo.listByRound(10)).thenReturn(List.of(draw(10, 1, 23)));
        RuntimeException e = assertThrows(RuntimeException.class, () -> settle.buildSettlementPlan(round));
        assertTrue(e.getMessage().contains("庄门开彩未录入"));
    }

    @Test
    void distributeFloorsTowardNegativeInfinity() {
        Map<String, Object> coBank = new LinkedHashMap<>();
        coBank.put("poolTotal", 3000L);
        Map<String, Object> m1 = new LinkedHashMap<>(Map.of("userId", 1L, "amount", 2000L, "nickname", "a"));
        Map<String, Object> m2 = new LinkedHashMap<>(Map.of("userId", 2L, "amount", 1000L, "nickname", "b"));
        coBank.put("members", List.of(m1, m2));

        List<Map<String, Object>> credits = settle.distributeBankerNet(coBank, -100, 1);
        // floor(-100*2000/3000)=-67, floor(-100*1000/3000)=-34（PHP floor 向负无穷）
        assertEquals(-67L, ((Number) credits.get(0).get("delta")).longValue());
        assertEquals(-34L, ((Number) credits.get(1).get("delta")).longValue());
    }
}
