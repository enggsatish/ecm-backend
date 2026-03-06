package com.ecm.admin.repository;

import com.ecm.admin.entity.DocumentCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentCategoryRepository extends JpaRepository<DocumentCategory, Integer> {
    boolean existsByCode(String code);
    Optional<DocumentCategory> findByCode(String code);
    List<DocumentCategory> findByIsActiveTrueOrderByNameAsc();
    List<DocumentCategory> findByParentIsNullAndIsActiveTrueOrderByNameAsc();
}
