package com.ecm.admin.dto;

import lombok.*;
import java.util.List;

/**
 * DTO for a single permission entry, returned by /api/admin/permissions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDto {
    private Integer id;
    private String  code;          // e.g. "documents:read"
    private String  moduleCode;    // e.g. "DOCUMENTS"
    private String  action;        // e.g. "read"
    private String  description;
    private Boolean isActive;
}
