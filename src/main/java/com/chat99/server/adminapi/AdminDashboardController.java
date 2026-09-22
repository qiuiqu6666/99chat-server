package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminDashboardController {

    private final AdminDashboardService dashboard;

    public AdminDashboardController(AdminDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** P0 首页看板汇总：用户 / 群组 / 消息 / 资金 / 系统错误摘要。 */
    @GetMapping("/overview")
    public AdminDashboardService.OverviewResponse overview(Authentication auth) {
        AdminAccess.requirePermission(auth, "dashboard.view");
        return dashboard.overview();
    }

    /** 多日注册与登录去重（可选，供趋势表使用）。 */
    @GetMapping("/daily-metrics")
    public AdminDashboardService.DailyMetricsResponse dailyMetrics(
        Authentication auth,
        @RequestParam(defaultValue = "30") int days,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "30") int pageSize) {
        AdminAccess.requirePermission(auth, "dashboard.view");
        return dashboard.dailyMetrics(days, page, pageSize);
    }
}
