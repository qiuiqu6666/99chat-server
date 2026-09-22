package com.chat99.server.robot;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人台（robot-desk）管理端接口统一入口，路径与查询参数原样转到 {@code robot.service-url}
 * （当前为 http://47.242.90.129）。
 */
@RestController
public class RobotDeskAdminProxyController {

    static final String PREFIX = "/api/admin/robot-desk";

    private final RobotServiceHttpProxy robotServiceHttpProxy;

    public RobotDeskAdminProxyController(RobotServiceHttpProxy robotServiceHttpProxy) {
        this.robotServiceHttpProxy = robotServiceHttpProxy;
    }

    @RequestMapping(
        value = PREFIX + "/**",
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        return robotServiceHttpProxy.forward(request);
    }
}
