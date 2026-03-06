package com.ecm.admin.repository;

import com.ecm.admin.entity.UserRoleView;
import com.ecm.admin.entity.UserRoleView.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserRoleViewRepository extends JpaRepository<UserRoleView, UserRoleId> {

    @Query("SELECT ur.id.roleId FROM UserRoleView ur WHERE ur.id.userId = :userId")
    List<Integer> findRoleIdsByUserId(@Param("userId") Integer userId);
}
