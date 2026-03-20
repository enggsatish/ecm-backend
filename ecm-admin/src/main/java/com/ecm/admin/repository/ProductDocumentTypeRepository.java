package com.ecm.admin.repository;

import com.ecm.admin.entity.ProductDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductDocumentTypeRepository extends JpaRepository<ProductDocumentType, Integer> {

    List<ProductDocumentType> findByProductIdAndIsActiveTrueOrderBySortOrder(Integer productId);

    boolean existsByProductIdAndCode(Integer productId, String code);

    Optional<ProductDocumentType> findByProductIdAndId(Integer productId, Integer id);
}
