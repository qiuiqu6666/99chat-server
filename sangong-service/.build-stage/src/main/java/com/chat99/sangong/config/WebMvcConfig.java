package com.chat99.sangong.config;

import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final SangongProperties props;

    public WebMvcConfig(SangongProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = Path.of(props.getStorageDir(), "bet-reports").toAbsolutePath().normalize()
            .toUri().toString();
        if (!dir.endsWith("/")) dir = dir + "/";
        registry.addResourceHandler("/bet-reports/**")
            .addResourceLocations(dir, "file:public/bet-reports/");
    }
}
