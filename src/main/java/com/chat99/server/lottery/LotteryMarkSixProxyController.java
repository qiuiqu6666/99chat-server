package com.chat99.server.lottery;

import com.chat99.server.robot.RobotServiceHttpProxy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开奖页统一入口。路径与查询参数原样转到 {@code robot.service-url}
 * （当前为 http://47.242.90.129）。WebSocket {@code /ws} 由
 * {@link LotteryMarkSixWebSocketProxyHandler} 处理。
 */
@RestController
public class LotteryMarkSixProxyController {

    static final String PREFIX = "/api/v1/lotteries/mark-six-demo";

    private final RobotServiceHttpProxy robotServiceHttpProxy;

    public LotteryMarkSixProxyController(RobotServiceHttpProxy robotServiceHttpProxy) {
        this.robotServiceHttpProxy = robotServiceHttpProxy;
    }

    @GetMapping({
        PREFIX + "/config",
        PREFIX + "/draws",
        PREFIX + "/predictions",
        PREFIX + "/overview",
        PREFIX + "/statistics"
    })
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        return robotServiceHttpProxy.forward(request);
    }
}
