package com.ecm.admin.dto;

import com.ecm.admin.entity.AdminUserView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public class AdminUserDto {

    private Integer id;
    private String email;
    private String displayName;
    private String entraObjectId;       // was oktaSubject — fixed to match init.sql
    private Integer departmentId;
    private String departmentName;
    private Boolean isActive;
    private OffsetDateTime lastLogin;
    private OffsetDateTime createdAt;
    private List<String> roles;

    public static AdminUserDto from(AdminUserView u, String departmentName, List<String> roles) {
        AdminUserDto dto = new AdminUserDto();
        dto.id             = u.getId();
        dto.email          = u.getEmail();
        dto.displayName    = u.getDisplayName();
        dto.entraObjectId  = u.getEntraObjectId();
        dto.departmentId   = u.getDepartmentId();
        dto.departmentName = departmentName;
        dto.isActive       = u.getIsActive();
        dto.lastLogin      = u.getLastLogin();
        dto.createdAt      = u.getCreatedAt();
        dto.roles          = roles;
        return dto;
    }

    public Integer getId()                       { return id; }
    public void setId(Integer id)                { this.id = id; }
    public String getEmail()                     { return email; }
    public void setEmail(String email)           { this.email = email; }
    public String getDisplayName()               { return displayName; }
    public void setDisplayName(String v)         { this.displayName = v; }
    public String getEntraObjectId()             { return entraObjectId; }
    public void setEntraObjectId(String v)       { this.entraObjectId = v; }
    public Integer getDepartmentId()             { return departmentId; }
    public void setDepartmentId(Integer v)       { this.departmentId = v; }
    public String getDepartmentName()            { return departmentName; }
    public void setDepartmentName(String v)      { this.departmentName = v; }
    public Boolean getIsActive()                 { return isActive; }
    public void setIsActive(Boolean v)           { this.isActive = v; }
    public OffsetDateTime getLastLogin()         { return lastLogin; }
    public void setLastLogin(OffsetDateTime v)   { this.lastLogin = v; }
    public OffsetDateTime getCreatedAt()         { return createdAt; }
    public void setCreatedAt(OffsetDateTime v)   { this.createdAt = v; }
    public List<String> getRoles()               { return roles; }
    public void setRoles(List<String> roles)     { this.roles = roles; }

    // ── Update request ─────────────────────────────────────────────────────
    public static class UpdateRequest {
        @Size(max = 200)
        private String displayName;
        private Integer departmentId;
        private Boolean isActive;

        public String getDisplayName()           { return displayName; }
        public void setDisplayName(String v)     { this.displayName = v; }
        public Integer getDepartmentId()         { return departmentId; }
        public void setDepartmentId(Integer v)   { this.departmentId = v; }
        public Boolean getIsActive()             { return isActive; }
        public void setIsActive(Boolean v)       { this.isActive = v; }
    }

    // ── Role assignment request ────────────────────────────────────────────
    public static class RoleRequest {
        @NotBlank
        private String roleName;
        public String getRoleName()              { return roleName; }
        public void setRoleName(String v)        { this.roleName = v; }
    }
}
