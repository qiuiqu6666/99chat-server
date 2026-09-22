package com.chat99.server.robot;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人模块切流到 robot-service 后（ROBOT_MODULE_ENABLED=false），
 * 主服务 8081 仍作为统一入口，将 robot-service 的路径原样转发到内网 robot-service。
 * 模块开启时本类不加载，由本进程的原 Controller 直接处理。
 * <p>
 * 注意：{@code /api/internal/robot-sync} 不在此列出，统一由 {@link RobotSyncController} 独占，
 * 避免与本地处理入口双映射。
 */
@ConditionalOnProperty(name = "robot.enabled", havingValue = "false")
@RestController
public class RobotServiceProxyController {

    private final RobotServiceHttpProxy robotServiceHttpProxy;

    public RobotServiceProxyController(RobotServiceHttpProxy robotServiceHttpProxy) {
        this.robotServiceHttpProxy = robotServiceHttpProxy;
    }

    @RequestMapping(
        value = {
            "/api/internal/robot-rebate-tasks/**",
            "/api/internal/robot-machines/**",
            "/me/agent/**",
            "/me/rebate/**",
            "/me/robot/**"
        },
        method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        return robotServiceHttpProxy.forward(request);
    }
}
