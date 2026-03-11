package com.ecm.admin.dto;

import lombok.*;
import java.util.List;

/**
 * DTO returned by /api/admin/roles endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDto {
    private Integer      id;
    private String       name;
    private String       description;
    private Boolean      isSystem;
    private Boolean      isActive;
    private List<String> permissions;   // permission codes
    private Long         userCount;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String name;          // must start with ECM_
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddPermissionRequest {
        private String permissionCode;
    }
}
