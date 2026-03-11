package com.ecm.admin.repository;

import com.ecm.admin.entity.BundleView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BundleViewRepository extends JpaRepository<BundleView, Integer> {
    List<BundleView> findAllByOrderBySortOrderAsc();
}
