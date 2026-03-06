package com.ecm.identity.model.dto;

import lombok.*;
import java.time.OffsetDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDto {
    private Integer        id;
    private String         oktaSubject;
    private String         email;
    private String         displayName;
    private Integer        departmentId;
    private String         departmentName;
    private Set<String>    roles;
    private Boolean        isActive;
    private OffsetDateTime lastLogin;
    private OffsetDateTime createdAt;
}