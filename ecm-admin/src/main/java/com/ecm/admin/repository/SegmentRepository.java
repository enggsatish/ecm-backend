package com.ecm.admin.repository;

import com.ecm.admin.entity.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SegmentRepository extends JpaRepository<Segment, Integer> {

    List<Segment> findByIsActiveTrue();

    Optional<Segment> findByCode(String code);

    boolean existsByCode(String code);
}