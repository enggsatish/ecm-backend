package com.ecm.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Provides a shared RestTemplate bean for ecm-common's internal HTTP clients
 * (e.g. DocumentPromotionClient).
 *
 * @ConditionalOnMissingBean: if a module declares its own RestTemplate (e.g. with
 * custom interceptors), that bean wins and this one is skipped. This prevents
 * double-registration across modules that all depend on ecm-common.
 */
@Configuration
public class InternalWebClientConfig {

    @Bean
    @ConditionalOnMissingBean(RestTemplate.class)
    public RestTemplate internalRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }
}