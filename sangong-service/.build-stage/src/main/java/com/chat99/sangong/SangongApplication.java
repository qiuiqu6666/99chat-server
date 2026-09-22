package com.chat99.sangong;

import com.chat99.sangong.config.SangongProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(SangongProperties.class)
public class SangongApplication {
    public static void main(String[] args) {
        SpringApplication.run(SangongApplication.class, args);
    }
}
