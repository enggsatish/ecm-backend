package com.ecm.identity.model.dto;

import lombok.*;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Session DTO returned by GET /api/auth/me.
 *
 * Sprint G: Added permissions[] field so the frontend can make
 * permission-aware rendering decisions without a separate API call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionDto {
    private Integer        id;
    private String         oktaSubject;   // Okta sub claim
    private String         email;
    private String         displayName;
    private Integer        departmentId;
    private Set<String>    roles;         // ECM_ADMIN, ECM_BACKOFFICE etc.
    private Set<String>    permissions;   // documents:read, workflow:approve etc.
    private OffsetDateTime lastLogin;
    private String         tokenExpiry;
}
