package com.chat99.sangong.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.image.AvatarCache;
import com.chat99.sangong.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 用 scripts/sample-report-data.json（与 PHP 对照脚本同一数据）渲染五类报表，
 * 输出到 /tmp/sangong-img-compare/java 供与 PHP 版逐像素对比。
 */
class ReportImageGoldenRenderTest {
    private static final Path OUT = Path.of("/tmp/sangong-img-compare/java");
    private static Map<String, Object> sample;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        Files.createDirectories(OUT);
        Path json = Path.of("scripts/sample-report-data.json");
        sample = new ObjectMapper().readValue(Files.readAllBytes(json), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ReportImageService service(String outName) {
        SangongProperties props = new SangongProperties();
        props.getImage().setScale(3);
        props.setStorageDir("data");

        List<SangongUser> userList = new ArrayList<>();
        Map<String, Object> points = (Map<String, Object>) sample.get("points");
        long id = 0;
        for (Map<String, Object> row : (List<Map<String, Object>>) points.get("users")) {
            SangongUser u = new SangongUser();
            u.setId(++id);
            u.setImUserId(String.valueOf(row.get("imUserId")));
            u.setNickname(String.valueOf(row.get("nickname")));
            u.setBalance(((Number) row.get("balance")).longValue());
            userList.add(u);
        }
        UserRepository users = mock(UserRepository.class);
        when(users.listAll()).thenReturn(userList);

        UserService userService = mock(UserService.class);
        when(userService.resolveNickname(any(SangongUser.class)))
            .thenAnswer(inv -> ((SangongUser) inv.getArgument(0)).getNickname());
        MainUserProfileService profiles = mock(MainUserProfileService.class);
        when(profiles.getAvatarUrls(anyList())).thenReturn(Map.of());

        ImService im = mock(ImService.class);
        when(im.getGameGroupId()).thenReturn("g");
        when(im.getAdminStatsGroupId()).thenReturn("s");
        when(im.sendImageToGroup(anyString(), any(ImService.PublishedImage.class))).thenReturn(1L);

        OssImagePublisher oss = mock(OssImagePublisher.class);
        when(oss.uploadJpeg(any(byte[].class), anyString())).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(0);
            Files.write(OUT.resolve(outName), bytes);
            return "https://example/" + outName;
        });
        return new ReportImageService(props, users, userService, im, oss, profiles, new AvatarCache());
    }

    @Test
    void renderPoints() {
        Map<String, Object> out = service("points.jpg").generateAndSendPointsImage(null, "g");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderBet() {
        Map<String, Object> report = (Map<String, Object>) sample.get("bet");
        Map<String, Object> out = service("bet.jpg").generateAndSendBetReport(report, "g");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderSettle() {
        Map<String, Object> report = (Map<String, Object>) sample.get("settle");
        Map<String, Object> out = service("settle.jpg").generateAndSendSettleReport(report, "g");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderTrend() {
        Map<String, Object> report = (Map<String, Object>) sample.get("trend");
        Map<String, Object> out = service("trend.jpg").generateAndSendTrend(report, "g");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderBill() {
        Map<String, Object> bill = (Map<String, Object>) sample.get("bill");
        Map<String, Object> out = service("bill.jpg").generateAndSendSettleBill(bill, "s");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
    }
}
