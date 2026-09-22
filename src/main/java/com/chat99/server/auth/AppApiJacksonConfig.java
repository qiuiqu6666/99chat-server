package com.chat99.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * App 认证等接口保持文档约定的 camelCase JSON（deviceId、nextStep 等）。
 * 优先级高于 {@link com.chat99.server.adminapi.AdminApiJacksonConfig}，避免被管理端 snake_case 转换器误读。
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE - 1)
public class AppApiJacksonConfig implements WebMvcConfigurer {

    private final ObjectMapper appMapper = Jackson2ObjectMapperBuilder.json().build();

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(appMapper) {
            @Override
            public boolean canRead(Class<?> clazz, MediaType mediaType) {
                return isAuthBodyType(clazz) && super.canRead(clazz, mediaType);
            }

            @Override
            public boolean canWrite(Class<?> clazz, MediaType mediaType) {
                return isAuthBodyType(clazz) && super.canWrite(clazz, mediaType);
            }
        };
        converters.add(0, converter);
    }

    static boolean isAuthBodyType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        String pkg = clazz.getPackageName();
        if (pkg.startsWith("com.chat99.server.auth")) {
            return true;
        }
        Class<?> enclosing = clazz.getEnclosingClass();
        return enclosing != null && isAuthBodyType(enclosing);
    }
}
