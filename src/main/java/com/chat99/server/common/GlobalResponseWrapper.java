package com.chat99.server.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * App 端统一响应包装：成功 2xx 的 JSON 响应包成 {@link ApiResponse}（code=0）。
 *
 * <p>排除运营后台、内部接口与第三方回调（保持各自既定格式）：
 * {@code /admin/**}、{@code /api/v1/**}、{@code /api/internal/**}、{@code /webhook/**}、
 * {@code /integration/**}、{@code /sangong/**}、{@code /group-live/**}、{@code /ai-assistant/**}、{@code /kefu/**}、{@code /chat-media/**}。
 * 错误响应（非 2xx）保持 {@code GlobalExceptionHandler} 既有格式，前端按 HTTP status 判断成功与否。
 */
@RestControllerAdvice
public class GlobalResponseWrapper implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType)
            || org.springframework.http.converter.StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (isExcludedPath(request) || !isSuccessStatus(response) || body instanceof ApiResponse<?>) {
            return body;
        }
        if (body instanceof String s) {
            try {
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return objectMapper.writeValueAsString(ApiResponse.ok(s));
            } catch (JsonProcessingException e) {
                return body;
            }
        }
        if (body instanceof byte[] || body instanceof org.springframework.core.io.Resource) {
            return body;
        }
        return ApiResponse.ok(body);
    }

    private static boolean isExcludedPath(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return path.startsWith("/admin")
            || path.startsWith("/api/v1")
            || path.startsWith("/api/internal")
            || path.startsWith("/webhook")
            || path.startsWith("/integration")
            || path.startsWith("/internal/")
            || path.startsWith("/chat-media/")
            || path.startsWith("/sangong")
            || path.startsWith("/group-live")
            || path.startsWith("/ai-assistant")
            || path.startsWith("/kefu");
    }

    private static boolean isSuccessStatus(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servlet) {
            int status = servlet.getServletResponse().getStatus();
            return status >= 200 && status < 300;
        }
        return true;
    }
}
