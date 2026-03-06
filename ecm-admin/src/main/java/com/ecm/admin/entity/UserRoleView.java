package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;
import java.util.Objects;

/** Read-only view of ecm_core.user_roles join table */
@Entity
@Immutable
@Table(name = "user_roles", schema = "ecm_core")
public class UserRoleView {

    @EmbeddedId
    private UserRoleId id;

    public UserRoleId getId() { return id; }

    @Embeddable
    public static class UserRoleId implements Serializable {

        @Column(name = "user_id")
        private Integer userId;

        @Column(name = "role_id")
        private Integer roleId;

        public UserRoleId() {}
        public UserRoleId(Integer userId, Integer roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        public Integer getUserId() { return userId; }
        public Integer getRoleId() { return roleId; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof UserRoleId that)) return false;
            return Objects.equals(userId, that.userId) && Objects.equals(roleId, that.roleId);
        }

        @Override
        public int hashCode() { return Objects.hash(userId, roleId); }
    }
}
