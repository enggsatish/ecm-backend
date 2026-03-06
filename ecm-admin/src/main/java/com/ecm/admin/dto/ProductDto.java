package com.ecm.admin.dto;

import com.ecm.admin.entity.Product;
import com.ecm.admin.entity.ProductCategoryLink;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class ProductDto {

    private Integer id;
    private String productCode;
    private String displayName;
    private String description;
    private String productSchema;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<CategoryLinkDto> categoryLinks;

    public static ProductDto summary(Product p) {
        ProductDto dto = new ProductDto();
        dto.id = p.getId(); dto.productCode = p.getProductCode(); dto.displayName = p.getDisplayName();
        dto.description = p.getDescription(); dto.isActive = p.getIsActive();
        dto.createdAt = p.getCreatedAt(); dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public static ProductDto full(Product p) {
        ProductDto dto = summary(p);
        dto.productSchema = p.getProductSchema();
        if (p.getCategoryLinks() != null)
            dto.categoryLinks = p.getCategoryLinks().stream()
                .filter(l -> Boolean.TRUE.equals(l.getIsActive()))
                .map(CategoryLinkDto::from).collect(Collectors.toList());
        return dto;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProductSchema() { return productSchema; }
    public void setProductSchema(String productSchema) { this.productSchema = productSchema; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<CategoryLinkDto> getCategoryLinks() { return categoryLinks; }
    public void setCategoryLinks(List<CategoryLinkDto> categoryLinks) { this.categoryLinks = categoryLinks; }

    // ── Create / Update request ──────────────────────────────────────────────
    public static class Request {
        @NotBlank @Size(max = 50) private String productCode;
        @NotBlank @Size(max = 200) private String displayName;
        private String description;
        private String productSchema;   // Raw JSON string
        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getProductSchema() { return productSchema; }
        public void setProductSchema(String productSchema) { this.productSchema = productSchema; }
    }

    // ── Category link request ────────────────────────────────────────────────
    public static class CategoryLinkRequest {
        private Integer categoryId;
        private Integer workflowDefinitionId;   // optional
        public Integer getCategoryId() { return categoryId; }
        public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
        public Integer getWorkflowDefinitionId() { return workflowDefinitionId; }
        public void setWorkflowDefinitionId(Integer workflowDefinitionId) { this.workflowDefinitionId = workflowDefinitionId; }
    }

    // ── Category link DTO (embedded in product response) ────────────────────
    public static class CategoryLinkDto {
        private Integer id;
        private Integer categoryId;
        private String categoryCode;
        private String categoryName;
        private Integer workflowDefinitionId;
        private Boolean isActive;
        public static CategoryLinkDto from(ProductCategoryLink link) {
            CategoryLinkDto dto = new CategoryLinkDto();
            dto.id = link.getId(); dto.categoryId = link.getCategory().getId();
            dto.categoryCode = link.getCategory().getCode(); dto.categoryName = link.getCategory().getName();
            dto.workflowDefinitionId = link.getWorkflowDefinitionId(); dto.isActive = link.getIsActive();
            return dto;
        }
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public Integer getCategoryId() { return categoryId; }
        public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
        public String getCategoryCode() { return categoryCode; }
        public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
        public Integer getWorkflowDefinitionId() { return workflowDefinitionId; }
        public void setWorkflowDefinitionId(Integer workflowDefinitionId) { this.workflowDefinitionId = workflowDefinitionId; }
        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    }
}
