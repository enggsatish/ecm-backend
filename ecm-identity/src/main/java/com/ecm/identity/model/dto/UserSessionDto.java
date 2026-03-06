package com.ecm.identity.model.dto;

import lombok.*;
import java.time.OffsetDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionDto {
    private Integer     id;
    private String      oktaSubject;      // Okta sub claim
    private String      email;
    private String      displayName;
    private Integer     departmentId;
    private Set<String> roles;            // ECM_ADMIN, ECM_BACKOFFICE etc.
    private OffsetDateTime lastLogin;
    private String      tokenExpiry;
}