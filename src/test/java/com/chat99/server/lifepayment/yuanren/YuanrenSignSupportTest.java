package com.chat99.server.lifepayment.yuanren;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YuanrenSignSupportTest {

    @Test
    void signIsStableForSortedParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mobile", "18866667777");
        params.put("out_trade_num", "ABC1111");
        params.put("product_id", "11");
        params.put("userid", "10001");
        params.put("notify_url", "http://www.abc.com/yuanren");
        String sign = YuanrenSignSupport.sign(params, "aaaaaaaaaaaaaaaaaaa");
        assertThat(sign).isEqualTo(YuanrenSignSupport.sign(params, "aaaaaaaaaaaaaaaaaaa"));
        assertThat(sign).matches("[0-9A-F]{32}");
        assertThat(YuanrenSignSupport.verify(YuanrenSignSupport.withSign(params, "aaaaaaaaaaaaaaaaaaa"),
            "aaaaaaaaaaaaaaaaaaa")).isTrue();
    }
}
