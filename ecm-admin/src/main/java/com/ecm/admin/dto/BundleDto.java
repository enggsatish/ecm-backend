package com.ecm.admin.dto;

import lombok.*;
import java.util.List;

/**
 * DTO for a capability bundle, returned by /api/admin/bundles.
 * Bundles are UI-layer groupings only — not enforced at runtime.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BundleDto {
    private Integer      id;
    private String       code;         // e.g. "TASK_PROCESSOR"
    private String       name;         // e.g. "Task Processor"
    private String       description;
    private Boolean      isSystem;
    private List<String> permissions;  // permission codes in this bundle
}
