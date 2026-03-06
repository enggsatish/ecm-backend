package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowTemplateMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface WorkflowTemplateMappingRepository extends JpaRepository<WorkflowTemplateMapping, Integer> {

    /**
     * Resolution query — returns candidates ordered by specificity:
     *  1. Exact product+category match (priority lowest first)
     *  2. Product-only match (null category)
     *  3. Category-only match (null product)
     */
    @Query("""
        SELECT m FROM WorkflowTemplateMapping m
        JOIN FETCH m.template t
        WHERE m.isActive = true
          AND t.status = 'PUBLISHED'
          AND m.categoryId = :categoryId
          AND (m.productId = :productId OR m.productId IS NULL)
        ORDER BY
            CASE WHEN m.productId IS NOT NULL THEN 0 ELSE 1 END,
            m.priority ASC
        """)
    List<WorkflowTemplateMapping> findCandidates(
            @Param("productId") Integer productId,
            @Param("categoryId") Integer categoryId);

    List<WorkflowTemplateMapping> findByTemplateId(Integer templateId);
}