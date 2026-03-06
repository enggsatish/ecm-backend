package com.ecm.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ECM Workflow Service — port 8083.
 *
 * Provides Flowable BPM-based document review workflows with:
 *  - Category-based auto-trigger (RabbitMQ listener on document upload events)
 *  - Manual trigger (REST API — uploader selects workflow type)
 *  - Role-based assignment (ECM_BACKOFFICE, ECM_REVIEWER)
 *  - Group-based assignment (product-specific teams configured in DB)
 *
 * @EnableAsync required for @AuditLog AOP async writes (from ecm-common).
 */
@SpringBootApplication(
        // Scan ecm-common components (security, audit, exception handler)
        scanBasePackages = {"com.ecm.workflow", "com.ecm.common"}
)
@EnableAsync
@EnableScheduling
public class WorkflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
