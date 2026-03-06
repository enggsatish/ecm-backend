package com.ecm.identity.repository;

import com.ecm.identity.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByEntraObjectId(String entraObjectId);

    Optional<User> findByEmail(String email);

    boolean existsByEntraObjectId(String entraObjectId);

    @Query("""
        SELECT u FROM User u
        JOIN FETCH u.roles
        WHERE u.entraObjectId = :subject
        """)
    Optional<User> findByEntraObjectIdWithRoles(
            @Param("subject") String subject);

    @Modifying
    @Query("""
        UPDATE User u
        SET u.lastLogin = :lastLogin
        WHERE u.entraObjectId = :subject
        """)
    void updateLastLogin(
            @Param("subject") String subject,
            @Param("lastLogin") OffsetDateTime lastLogin);

    List<User> findByDepartmentId(Integer departmentId);

    List<User> findByIsActiveTrue();
}