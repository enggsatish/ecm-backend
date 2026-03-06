package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.CategoryWorkflowMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CategoryWorkflowMappingRepository
        extends JpaRepository<CategoryWorkflowMapping, Integer> {

    Optional<CategoryWorkflowMapping> findByCategoryIdAndIsActiveTrue(Integer categoryId);
}
