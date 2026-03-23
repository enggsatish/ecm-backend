package com.ecm.admin.service;

import com.ecm.admin.dto.ProductDto;
import com.ecm.admin.entity.*;
import com.ecm.admin.repository.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepo;
    private final ProductDocumentTypeRepository docTypeRepo;
    private final DocumentCategoryRepository categoryRepo;

    public ProductService(ProductRepository productRepo,
                          ProductDocumentTypeRepository docTypeRepo,
                          DocumentCategoryRepository categoryRepo) {
        this.productRepo = productRepo;
        this.docTypeRepo = docTypeRepo;
        this.categoryRepo = categoryRepo;
    }

    @Transactional(readOnly = true)
    public Page<ProductDto> list(Boolean isActive, Pageable pageable) {
        return (isActive != null ? productRepo.findByIsActive(isActive, pageable)
                : productRepo.findAll(pageable)).map(ProductDto::summary);
    }

    @Transactional(readOnly = true)
    public ProductDto getById(Integer id) { return ProductDto.full(findOrThrow(id)); }

    public ProductDto create(ProductDto.Request req) {
        if (productRepo.existsByProductCode(req.getProductCode().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Product code already exists: " + req.getProductCode());
        Product p = new Product();
        p.setProductCode(req.getProductCode().toUpperCase().trim());
        p.setDisplayName(req.getDisplayName().trim());
        p.setDescription(req.getDescription());
        p.setProductSchema(req.getProductSchemaAsString());
        p.setCaseWorkflowKey(req.getCaseWorkflowKey());
        p.setSegmentId(req.getSegmentId());
        p.setProductLineId(req.getProductLineId());
        return ProductDto.summary(productRepo.save(p));
    }

    public ProductDto update(Integer id, ProductDto.Request req) {
        Product p = findOrThrow(id);
        p.setDisplayName(req.getDisplayName().trim());
        p.setDescription(req.getDescription());
        p.setProductSchema(req.getProductSchemaAsString());
        p.setCaseWorkflowKey(req.getCaseWorkflowKey());
        if (req.getSegmentId() != null) p.setSegmentId(req.getSegmentId());
        if (req.getProductLineId() != null) p.setProductLineId(req.getProductLineId());
        p.setUpdatedAt(OffsetDateTime.now());
        return ProductDto.full(productRepo.save(p));
    }

    public void deactivate(Integer id) {
        Product p = findOrThrow(id);
        p.setIsActive(false);
        p.setUpdatedAt(OffsetDateTime.now());
        productRepo.save(p);
    }

    // ── Document Types (replaces linkCategory / unlinkCategory) ─────────────

    public ProductDto addDocumentType(Integer productId, ProductDto.DocumentTypeRequest req) {
        Product product = findOrThrow(productId);

        if (req.getCategoryId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is required");
        if (req.getName() == null || req.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        if (req.getCode() == null || req.getCode().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");

        DocumentCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Category not found: " + req.getCategoryId()));

        if (docTypeRepo.existsByProductIdAndCode(productId, req.getCode().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document type code already exists for this product: " + req.getCode());

        ProductDocumentType dt = new ProductDocumentType();
        dt.setProduct(product);
        dt.setCategory(category);
        dt.setName(req.getName().trim());
        dt.setCode(req.getCode().toUpperCase().trim());
        dt.setSourceType(req.getSourceType() != null ? req.getSourceType() : "UPLOAD");
        dt.setFormDefinitionId(req.getFormDefinitionId());
        dt.setOnUploadAction(req.getOnUploadAction() != null ? req.getOnUploadAction() : "OCR_ONLY");
        dt.setIsRequired(req.getIsRequired() != null ? req.getIsRequired() : true);
        dt.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        docTypeRepo.save(dt);

        return ProductDto.full(findOrThrow(productId));
    }

    public void removeDocumentType(Integer productId, Integer docTypeId) {
        ProductDocumentType dt = docTypeRepo.findByProductIdAndId(productId, docTypeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document type not found"));
        docTypeRepo.delete(dt);
    }

    private Product findOrThrow(Integer id) {
        return productRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }
}
