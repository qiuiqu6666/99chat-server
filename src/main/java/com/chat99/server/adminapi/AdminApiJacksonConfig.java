package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
 * /api/v1 管理端统一 snake_case JSON；仅匹配 adminapi 包内类型，不影响 App 接口 camelCase。
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminApiJacksonConfig implements WebMvcConfigurer {

    private final ObjectMapper adminMapper = Jackson2ObjectMapperBuilder.json()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build();

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(adminMapper) {
            @Override
            public boolean canRead(Class<?> clazz, MediaType mediaType) {
                return isAdminApiType(clazz) && super.canRead(clazz, mediaType);
            }

            @Override
            public boolean canWrite(Class<?> clazz, MediaType mediaType) {
                return isAdminApiType(clazz) && super.canWrite(clazz, mediaType);
            }
        };
        converters.add(0, converter);
    }

    private static boolean isAdminApiType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        String pkg = clazz.getPackageName();
        if (pkg.equals("com.chat99.server.adminapi")
            || pkg.startsWith("com.chat99.server.adminapi.")) {
            return true;
        }
        Class<?> enclosing = clazz.getEnclosingClass();
        return enclosing != null && isAdminApiType(enclosing);
    }
}
