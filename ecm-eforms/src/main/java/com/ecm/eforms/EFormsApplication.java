package com.ecm.eforms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ecm-eforms microservice — port 8084.
 *
 * Scanning covers com.ecm.eforms.* by default.
 * ecm-common beans (EcmJwtConverter, AudienceValidator) are loaded
 * from the ecm-common jar via Spring Boot auto-configuration.
 */
@SpringBootApplication(
        // Scan ecm-common components (security, audit, exception handler)
        scanBasePackages = {"com.ecm.eforms", "com.ecm.common"}
)
@EnableAsync
public class EFormsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EFormsApplication.class, args);
    }
}
