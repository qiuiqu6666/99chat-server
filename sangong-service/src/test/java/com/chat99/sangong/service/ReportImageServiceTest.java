package com.chat99.sangong.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.image.AvatarCache;
import com.chat99.sangong.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReportImageServiceTest {

    private static ReportImageService newService(SangongProperties props, UserRepository users,
                                                 ImService im, OssImagePublisher oss) {
        UserService userService = mock(UserService.class);
        when(userService.resolveNickname(any(SangongUser.class)))
            .thenAnswer(inv -> {
                SangongUser u = inv.getArgument(0);
                return u.getNickname() == null || u.getNickname().isBlank() ? u.getImUserId() : u.getNickname();
            });
        MainUserProfileService profiles = mock(MainUserProfileService.class);
        when(profiles.getAvatarUrls(anyList())).thenReturn(Map.of());
        return new ReportImageService(props, users, userService, im, oss, profiles, new AvatarCache());
    }

    @Test
    void pointsImageUploadsToOssAndSends() {
        SangongProperties props = new SangongProperties();
        props.getImage().setScale(1);

        SangongUser user = new SangongUser();
        user.setId(1);
        user.setImUserId("u1");
        user.setNickname("测试");
        user.setBalance(100);

        UserRepository users = mock(UserRepository.class);
        when(users.listAll()).thenReturn(List.of(user));
        ImService im = mock(ImService.class);
        when(im.getGameGroupId()).thenReturn(" agroup ");
        when(im.sendImageToGroup(anyString(), any(ImService.PublishedImage.class))).thenReturn(1L);
        OssImagePublisher oss = mock(OssImagePublisher.class);
        when(oss.uploadJpeg(any(byte[].class), anyString()))
            .thenReturn("https://bucket.oss.example/sangong/bet-reports/20260718/a.jpg");

        ReportImageService svc = newService(props, users, im, oss);
        Map<String, Object> out = svc.generateAndSendPointsImage(null, "agroup");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
        assertEquals("https://bucket.oss.example/sangong/bet-reports/20260718/a.jpg", out.get("url"));

        // 上传字节应是有效 JPEG（SOI 头 0xFFD8），且 IM 发送用的是 OSS URL
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(oss).uploadJpeg(bytes.capture(), anyString());
        assertTrue(bytes.getValue().length > 2);
        assertEquals((byte) 0xFF, bytes.getValue()[0]);
        assertEquals((byte) 0xD8, bytes.getValue()[1]);
        ArgumentCaptor<ImService.PublishedImage> published =
            ArgumentCaptor.forClass(ImService.PublishedImage.class);
        verify(im).sendImageToGroup(eq("agroup"), published.capture());
        assertEquals("https://bucket.oss.example/sangong/bet-reports/20260718/a.jpg", published.getValue().url());
        assertEquals(bytes.getValue().length, published.getValue().size());
    }

    @Test
    void ossUploadFailureReturnsError() {
        SangongProperties props = new SangongProperties();
        props.getImage().setScale(1);
        SangongUser user = new SangongUser();
        user.setId(1);
        user.setImUserId("u1");
        user.setBalance(100);
        UserRepository users = mock(UserRepository.class);
        when(users.listAll()).thenReturn(List.of(user));
        ImService im = mock(ImService.class);
        OssImagePublisher oss = mock(OssImagePublisher.class);
        when(oss.uploadJpeg(any(byte[].class), anyString())).thenReturn(null);

        ReportImageService svc = newService(props, users, im, oss);
        Map<String, Object> out = svc.generateAndSendPointsImage(null, "agroup");
        assertEquals("OSS_UPLOAD_FAILED", out.get("code"));
    }

    @Test
    void trendImageRendersPlaceholderRows() {
        SangongProperties props = new SangongProperties();
        props.getImage().setScale(1);
        UserRepository users = mock(UserRepository.class);
        ImService im = mock(ImService.class);
        when(im.getGameGroupId()).thenReturn("g");
        when(im.sendImageToGroup(anyString(), any(ImService.PublishedImage.class))).thenReturn(1L);
        OssImagePublisher oss = mock(OssImagePublisher.class);
        when(oss.uploadJpeg(any(byte[].class), anyString()))
            .thenReturn("https://bucket.oss.example/sangong/bet-reports/20260718/t.jpg");

        Map<Integer, Map<String, Object>> doors = new LinkedHashMap<>();
        for (int d = 1; d <= 3; d++) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("amount", null);
            cell.put("compare", null);
            doors.put(d, cell);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodNo", null);
        row.put("time", "");
        row.put("bankerDoor", 0);
        row.put("placeholder", true);
        row.put("doors", doors);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("doorCount", 3);
        report.put("rows", List.of(row));

        ReportImageService svc = newService(props, users, im, oss);
        Map<String, Object> out = svc.generateAndSendTrend(report, "g");
        assertTrue(Boolean.TRUE.equals(out.get("ok")), () -> String.valueOf(out));
    }

    @Test
    void pointsSortMatchesPhpAbsThenValueThenNickname() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("a", 50));
        rows.add(row("b", -100));
        rows.add(row("c", 100));
        rows.add(row("d", -50));
        ReportImageService.sortUsersForPointsTable(rows);
        assertEquals(100L, ReportImageService.lng(rows.get(0).get("balance")));
        assertEquals(-100L, ReportImageService.lng(rows.get(1).get("balance")));
        assertEquals(50L, ReportImageService.lng(rows.get(2).get("balance")));
        assertEquals(-50L, ReportImageService.lng(rows.get(3).get("balance")));
    }

    @Test
    void sharePercentFormatting() {
        assertEquals("33.33", ReportImageService.formatSharePercent(33.333));
        assertEquals("50", ReportImageService.formatSharePercent(50.0));
        assertEquals("12.5", ReportImageService.formatSharePercent(12.50));
    }

    private static Map<String, Object> row(String nickname, long balance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imUserId", nickname);
        m.put("nickname", nickname);
        m.put("balance", balance);
        return m;
    }
}
