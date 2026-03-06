package com.ecm.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@SpringBootApplication(
        // Explicitly scan both packages so ecm-common beans are always found:
        // SecurityConfig, AuditAspect, EcmJwtConverter, GlobalExceptionHandler
        // Without this, Spring Boot scans com.ecm.document only — common beans
        // load via autoconfiguration which is fragile and order-dependent.
        scanBasePackages = {
                "com.ecm.document",
                "com.ecm.common"
        }
)
@EnableAsync   // Required for AuditAspect's async writer bean to work
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class DocumentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}