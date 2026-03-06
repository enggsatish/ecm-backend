package com.ecm.admin.repository;

import com.ecm.admin.entity.ProductLine;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductLineRepository extends JpaRepository<ProductLine, Integer> {

    List<ProductLine> findByIsActiveTrue();

    List<ProductLine> findBySegmentIdAndIsActiveTrue(Integer segmentId);

    boolean existsByCode(String code);
}