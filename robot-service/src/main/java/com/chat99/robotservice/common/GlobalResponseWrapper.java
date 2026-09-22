package com.chat99.robotservice.common;

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
 * 与主服务 GlobalResponseWrapper 行为一致：
 * /me/** 成功 2xx 包装为 {code:0,message:"ok",data:...}；/api/internal/** 保持裸 JSON。
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
        return path.startsWith("/api/internal") || path.startsWith("/error");
    }

    private static boolean isSuccessStatus(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servlet) {
            int status = servlet.getServletResponse().getStatus();
            return status >= 200 && status < 300;
        }
        return true;
    }
}
