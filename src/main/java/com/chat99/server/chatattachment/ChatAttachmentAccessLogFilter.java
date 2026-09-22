package com.chat99.server.chatattachment;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class ChatAttachmentAccessLogFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_MDC = "chatAttRequestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentAccessLogFilter.class);
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        return path == null || !path.startsWith("/me/chat/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = incoming == null || incoming.isBlank()
            ? "att_" + UUID.randomUUID().toString().replace("-", "")
            : incoming.trim();
        if (requestId.length() > 64) {
            requestId = requestId.substring(0, 64);
        }
        long startNs = System.nanoTime();
        MDC.put(REQUEST_ID_MDC, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            String path = PATH_HELPER.getPathWithinApplication(request);
            log.info("chat-att http requestId={} method={} path={} status={} ms={}",
                requestId, request.getMethod(), path, response.getStatus(), ms);
            MDC.remove(REQUEST_ID_MDC);
        }
    }

    public static String currentRequestId() {
        String id = MDC.get(REQUEST_ID_MDC);
        return id == null || id.isBlank() ? null : id;
    }
}
