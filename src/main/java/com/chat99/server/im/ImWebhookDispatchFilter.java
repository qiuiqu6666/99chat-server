package com.chat99.server.im;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
public class ImWebhookDispatchFilter extends OncePerRequestFilter {

    static final String ON_POOL = "im.webhook.on-pool";
    private static final byte[] BUSY = "{\"ErrorCode\":1,\"ErrorInfo\":\"BUSY\",\"ActionStatus\":\"OK\"}"
        .getBytes(StandardCharsets.UTF_8);

    private static final Logger log = LoggerFactory.getLogger(ImWebhookDispatchFilter.class);

    private final ImWebhookDispatchProperties props;
    private final Executor executor;

    public ImWebhookDispatchFilter(ImWebhookDispatchProperties props,
                                   @Qualifier(ImWebhookExecutorConfig.BEAN_NAME) Executor executor) {
        this.props = props;
        this.executor = executor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/webhook/im/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // Tomcat 在过滤器返回后会 release 掉 FilterChain。跨线程 chain.doFilter 会得到 servlet=null、空响应。
        // 回调改由 Controller 的 WebAsyncTask 放到 im-webhook 线程上执行。
        chain.doFilter(request, response);
    }
}
