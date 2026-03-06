package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WorkflowGroupMemberRepository extends JpaRepository<WorkflowGroupMember, Integer> {

    List<WorkflowGroupMember> findByGroupId(Integer groupId);

    @Modifying
    @Query("DELETE FROM WorkflowGroupMember m WHERE m.group.id = :groupId AND m.userId = :userId")
    void deleteByGroupIdAndUserId(Integer groupId, Integer userId);
}
