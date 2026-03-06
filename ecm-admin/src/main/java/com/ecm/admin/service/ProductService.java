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
    private final ProductCategoryLinkRepository linkRepo;
    private final DocumentCategoryRepository categoryRepo;
    private final WorkflowClient workflowClient;

    public ProductService(ProductRepository productRepo,
                          ProductCategoryLinkRepository linkRepo,
                          DocumentCategoryRepository categoryRepo,
                          WorkflowClient workflowClient) {
        this.productRepo = productRepo;
        this.linkRepo = linkRepo;
        this.categoryRepo = categoryRepo;
        this.workflowClient = workflowClient;
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
        p.setProductSchema(req.getProductSchema());
        return ProductDto.summary(productRepo.save(p));
    }

    public ProductDto update(Integer id, ProductDto.Request req) {
        Product p = findOrThrow(id);
        p.setDisplayName(req.getDisplayName().trim());
        p.setDescription(req.getDescription());
        p.setProductSchema(req.getProductSchema());
        p.setUpdatedAt(OffsetDateTime.now());
        return ProductDto.full(productRepo.save(p));
    }

    public void deactivate(Integer id) {
        Product p = findOrThrow(id);
        p.setIsActive(false);
        p.setUpdatedAt(OffsetDateTime.now());
        productRepo.save(p);
    }

    public ProductDto linkCategory(Integer productId, ProductDto.CategoryLinkRequest req) {
        Product product = findOrThrow(productId);
        if (req.getCategoryId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is required");
        DocumentCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Category not found: " + req.getCategoryId()));
        if (linkRepo.existsByProductIdAndCategoryId(productId, req.getCategoryId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category already linked");
        ProductCategoryLink link = new ProductCategoryLink();
        link.setProduct(product);
        link.setCategory(category);
        link.setWorkflowDefinitionId(req.getWorkflowDefinitionId());
        linkRepo.save(link);
        if (req.getWorkflowDefinitionId() != null)
            workflowClient.createCategoryMapping(req.getCategoryId(), req.getWorkflowDefinitionId());
        return ProductDto.full(findOrThrow(productId));
    }

    public void unlinkCategory(Integer productId, Integer categoryId) {
        ProductCategoryLink link = linkRepo.findByProductIdAndCategoryId(productId, categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Category link not found"));
        Integer wfId = link.getWorkflowDefinitionId();
        Integer linkId = link.getId();
        linkRepo.delete(link);
        if (wfId != null) workflowClient.deleteCategoryMapping(linkId);
    }

    private Product findOrThrow(Integer id) {
        return productRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }
}
