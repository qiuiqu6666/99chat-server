package com.chat99.server.lifepayment.yuanren;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大猿人异步回调。须返回纯文本 {@code success}。
 */
@RestController
@RequestMapping("/webhook/life-payment/yuanren")
public class YuanrenNotifyController {

    private final YuanrenFulfillmentService fulfillmentService;

    public YuanrenNotifyController(YuanrenFulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(@RequestParam MultiValueMap<String, String> form) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (form != null) {
            form.forEach((k, vs) -> {
                if (k != null && vs != null && !vs.isEmpty() && vs.get(0) != null) {
                    flat.put(k, vs.get(0));
                }
            });
        }
        return fulfillmentService.handleNotify(flat);
    }
}
