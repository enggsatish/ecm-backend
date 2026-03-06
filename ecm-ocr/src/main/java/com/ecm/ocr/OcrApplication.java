package com.ecm.ocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ECM OCR Service — port 8087.
 *
 * Consumes document.ocr.request events from RabbitMQ, extracts text
 * via Apache Tika, writes back to ecm_core.documents via JdbcTemplate
 * (cross-schema — no JPA entity ownership), and indexes to OpenSearch.
 *
 * Does NOT own a DB schema. No Flyway. No user-facing REST API in Sprint D.
 *
 * scanBasePackages includes com.ecm.common to load:
 *   SecurityConfig, EcmJwtConverter, AudienceValidator,
 *   AuditAspect, GlobalExceptionHandler
 *
 * @EnableAsync — required for AuditAspect async writer from ecm-common.
 */
@SpringBootApplication(
        scanBasePackages = {"com.ecm.ocr", "com.ecm.common"},
        exclude = {
                ElasticsearchDataAutoConfiguration.class,
                ElasticsearchRepositoriesAutoConfiguration.class,
                ReactiveElasticsearchRepositoriesAutoConfiguration.class,
                ElasticsearchClientAutoConfiguration.class,
                ElasticsearchRestClientAutoConfiguration.class
        }
)
@EnableAsync
public class OcrApplication {
    public static void main(String[] args) {
        SpringApplication.run(OcrApplication.class, args);
    }
}

