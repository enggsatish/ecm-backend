package com.ecm.admin.repository;

import com.ecm.admin.entity.OcrTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OcrTemplateRepository extends JpaRepository<OcrTemplate, Integer> {

    Optional<OcrTemplate> findByCategoryCode(String categoryCode);

    List<OcrTemplate> findByIsActiveTrueOrderByCategoryCode();

    boolean existsByCategoryCode(String categoryCode);
}
