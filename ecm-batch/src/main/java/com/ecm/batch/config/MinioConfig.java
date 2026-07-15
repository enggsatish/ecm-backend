package com.ecm.batch.config;

import io.minio.MinioClient;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MinioConfig {

    @Value("${ecm.minio.endpoint}")
    private String endpoint;

    @Value("${ecm.minio.access-key}")
    private String accessKey;

    @Value("${ecm.minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(3, 90, TimeUnit.SECONDS))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .build();

        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }
}
