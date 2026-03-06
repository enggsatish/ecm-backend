package com.ecm.admin.service;

import com.ecm.admin.dto.HierarchyDtos.*;
import com.ecm.admin.entity.ProductLine;
import com.ecm.admin.entity.Segment;
import com.ecm.admin.repository.ProductLineRepository;
import com.ecm.admin.repository.ProductRepository;
import com.ecm.admin.repository.SegmentRepository;
import com.ecm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HierarchyService {

    private final SegmentRepository     segmentRepo;
    private final ProductLineRepository productLineRepo;
    private final ProductRepository     productRepo;   // existing repo in ecm-admin

    // ─── Segments ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SegmentDto> listActiveSegments() {
        return segmentRepo.findByIsActiveTrue()
                .stream().map(this::toSegmentDto).toList();
    }

    @Transactional(readOnly = true)
    public List<SegmentDto> listAllSegments() {
        return segmentRepo.findAll()
                .stream().map(this::toSegmentDto).toList();
    }

    @Transactional
    public SegmentDto createSegment(SegmentRequest req) {
        String code = req.code().toUpperCase();
        if (segmentRepo.existsByCode(code))
            throw new IllegalArgumentException("Segment code already exists: " + code);

        Segment s = Segment.builder()
                .name(req.name())
                .code(code)
                .description(req.description())
                .build();
        s = segmentRepo.save(s);
        log.info("Segment created: id={}, code={}", s.getId(), s.getCode());
        return toSegmentDto(s);
    }

    @Transactional
    public SegmentDto updateSegment(Integer id, SegmentRequest req) {
        Segment s = segmentRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Segment", id));
        s.setName(req.name());
        s.setDescription(req.description());
        if (req.active() != null) s.setIsActive(req.active());
        return toSegmentDto(segmentRepo.save(s));
    }

    // ─── Product Lines ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProductLineDto> listAllProductLines() {
        return productLineRepo.findByIsActiveTrue()
                .stream()
                .map(pl -> toProductLineDto(pl, segmentRepo.findById(pl.getSegmentId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductLineDto> listProductLinesBySegment(Integer segmentId) {
        return productLineRepo.findBySegmentIdAndIsActiveTrue(segmentId)
                .stream()
                .map(pl -> toProductLineDto(pl, segmentRepo.findById(pl.getSegmentId()).orElse(null)))
                .toList();
    }

    @Transactional
    public ProductLineDto createProductLine(ProductLineRequest req) {
        String code = req.code().toUpperCase();
        if (productLineRepo.existsByCode(code))
            throw new IllegalArgumentException("Product line code already exists: " + code);

        Segment seg = segmentRepo.findById(req.segmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Segment", req.segmentId()));

        ProductLine pl = ProductLine.builder()
                .segmentId(req.segmentId())
                .name(req.name())
                .code(code)
                .description(req.description())
                .build();
        pl = productLineRepo.save(pl);
        log.info("ProductLine created: id={}, code={}", pl.getId(), pl.getCode());
        return toProductLineDto(pl, seg);
    }

    @Transactional
    public ProductLineDto updateProductLine(Integer id, ProductLineRequest req) {
        ProductLine pl = productLineRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductLine", id));
        pl.setName(req.name());
        pl.setDescription(req.description());
        if (req.active() != null) pl.setIsActive(req.active());
        Segment seg = segmentRepo.findById(pl.getSegmentId()).orElse(null);
        return toProductLineDto(productLineRepo.save(pl), seg);
    }

    // ─── Full Hierarchy Tree ──────────────────────────────────────────────────

    /**
     * Returns the full Segment → ProductLine → Product tree.
     * Used by the document upload cascading selects.
     * Result is read-only; no write operations here.
     */
    @Transactional(readOnly = true)
    public List<HierarchyNode> getFullHierarchy() {
        return segmentRepo.findByIsActiveTrue().stream().map(seg -> {
            List<ProductLineNode> pls = productLineRepo
                    .findBySegmentIdAndIsActiveTrue(seg.getId()).stream()
                    .map(pl -> {
                        List<ProductSummary> products = productRepo.findByIsActiveTrue().stream()
                                .filter(p -> pl.getId().equals(p.getProductLineId()))
                                .map(p -> new ProductSummary(
                                        p.getId(), p.getProductCode(),
                                        p.getDisplayName(), p.getDescription(),
                                        p.getIsActive()))
                                .toList();
                        return new ProductLineNode(pl.getId(), pl.getName(), pl.getCode(), products);
                    }).toList();
            return new HierarchyNode(seg.getId(), seg.getName(), seg.getCode(), pls);
        }).toList();
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private SegmentDto toSegmentDto(Segment s) {
        return new SegmentDto(s.getId(), s.getName(), s.getCode(),
                s.getDescription(), s.getIsActive(), s.getCreatedAt());
    }

    private ProductLineDto toProductLineDto(ProductLine pl, Segment seg) {
        return new ProductLineDto(
                pl.getId(), pl.getSegmentId(),
                seg != null ? seg.getName() : null,
                pl.getName(), pl.getCode(), pl.getDescription(),
                pl.getIsActive(), pl.getCreatedAt());
    }
}