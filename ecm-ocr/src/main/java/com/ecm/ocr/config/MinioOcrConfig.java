package com.ecm.ocr.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioOcrConfig {

    @Value("${ecm.minio.endpoint}")
    private String endpoint;
    @Value("${ecm.minio.access-key}")
    private String accessKey;
    @Value("${ecm.minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
