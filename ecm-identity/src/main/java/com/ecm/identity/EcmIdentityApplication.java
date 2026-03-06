package com.ecm.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(
        scanBasePackages = {
                "com.ecm.identity",
                "com.ecm.common"
        }
)
// @EnableCaching REMOVED — it was declared without a CacheManager bean,
// causing Spring to fall back to ConcurrentMapCache (in-memory, not shared,
// dies on restart). Proper Redis-backed caching is configured in
// RedisCacheConfig.java which includes @EnableCaching itself.
@EnableAsync   // needed for AuditAspect's AuditWriter @Async to work
public class EcmIdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(EcmIdentityApplication.class, args);
    }
}