package com.ecm.admin.repository;

import com.ecm.admin.entity.ProductCategoryLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductCategoryLinkRepository extends JpaRepository<ProductCategoryLink, Integer> {
    List<ProductCategoryLink> findByProductIdAndIsActiveTrue(Integer productId);
    Optional<ProductCategoryLink> findByProductIdAndCategoryId(Integer productId, Integer categoryId);
    boolean existsByProductIdAndCategoryId(Integer productId, Integer categoryId);
}
