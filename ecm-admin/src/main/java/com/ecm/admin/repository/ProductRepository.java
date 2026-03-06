package com.ecm.admin.repository;

import com.ecm.admin.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {
    boolean existsByProductCode(String productCode);
    Optional<Product> findByProductCode(String productCode);
    Page<Product> findByIsActive(Boolean isActive, Pageable pageable);
    List<Product> findByIsActiveTrue();
}
