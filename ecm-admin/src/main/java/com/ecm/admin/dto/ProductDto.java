package com.ecm.admin.dto;

import com.ecm.admin.entity.Product;
import com.ecm.admin.entity.ProductDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ProductDto {

    private Integer id;
    private String productCode;
    private String displayName;
    private String description;
    private String productSchema;
    private String caseWorkflowKey;
    private Integer segmentId;
    private Integer productLineId;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<DocumentTypeDto> documentTypes;

    public static ProductDto summary(Product p) {
        ProductDto dto = new ProductDto();
        dto.id = p.getId(); dto.productCode = p.getProductCode(); dto.displayName = p.getDisplayName();
        dto.description = p.getDescription(); dto.isActive = p.getIsActive();
        dto.caseWorkflowKey = p.getCaseWorkflowKey();
        dto.segmentId = p.getSegmentId(); dto.productLineId = p.getProductLineId();
        dto.createdAt = p.getCreatedAt(); dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public static ProductDto full(Product p) {
        ProductDto dto = summary(p);
        dto.productSchema = p.getProductSchema();
        if (p.getDocumentTypes() != null)
            dto.documentTypes = p.getDocumentTypes().stream()
                .filter(dt -> Boolean.TRUE.equals(dt.getIsActive()))
                .map(DocumentTypeDto::from)
                .collect(Collectors.toList());
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
    public String getCaseWorkflowKey() { return caseWorkflowKey; }
    public void setCaseWorkflowKey(String caseWorkflowKey) { this.caseWorkflowKey = caseWorkflowKey; }
    public Integer getSegmentId() { return segmentId; }
    public void setSegmentId(Integer segmentId) { this.segmentId = segmentId; }
    public Integer getProductLineId() { return productLineId; }
    public void setProductLineId(Integer productLineId) { this.productLineId = productLineId; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<DocumentTypeDto> getDocumentTypes() { return documentTypes; }
    public void setDocumentTypes(List<DocumentTypeDto> documentTypes) { this.documentTypes = documentTypes; }

    // ── Create / Update request ──────────────────────────────────────────────
    public static class Request {
        @NotBlank @Size(max = 50) private String productCode;
        @NotBlank @Size(max = 200) private String displayName;
        private String description;
        private String productSchema;
        private String caseWorkflowKey;
        private Integer segmentId;
        private Integer productLineId;

        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getProductSchema() { return productSchema; }
        public void setProductSchema(String productSchema) { this.productSchema = productSchema; }
        public String getCaseWorkflowKey() { return caseWorkflowKey; }
        public void setCaseWorkflowKey(String caseWorkflowKey) { this.caseWorkflowKey = caseWorkflowKey; }
        public Integer getSegmentId() { return segmentId; }
        public void setSegmentId(Integer segmentId) { this.segmentId = segmentId; }
        public Integer getProductLineId() { return productLineId; }
        public void setProductLineId(Integer productLineId) { this.productLineId = productLineId; }
    }

    // ── Document type request ──────────────────────────────────────────────
    public static class DocumentTypeRequest {
        @NotBlank private String name;
        @NotBlank private String code;
        private Integer categoryId;
        private String sourceType = "UPLOAD";       // EFORM | UPLOAD
        private UUID formDefinitionId;              // only when sourceType = EFORM
        private String onUploadAction = "OCR_ONLY"; // OCR_ONLY | REVIEW_REQUIRED
        private Boolean isRequired = true;
        private Integer sortOrder = 0;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public Integer getCategoryId() { return categoryId; }
        public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public UUID getFormDefinitionId() { return formDefinitionId; }
        public void setFormDefinitionId(UUID formDefinitionId) { this.formDefinitionId = formDefinitionId; }
        public String getOnUploadAction() { return onUploadAction; }
        public void setOnUploadAction(String onUploadAction) { this.onUploadAction = onUploadAction; }
        public Boolean getIsRequired() { return isRequired; }
        public void setIsRequired(Boolean isRequired) { this.isRequired = isRequired; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }

    // ── Document type DTO (embedded in product response) ──────────────────
    public static class DocumentTypeDto {
        private Integer id;
        private Integer categoryId;
        private String categoryCode;
        private String categoryName;
        private String name;
        private String code;
        private String sourceType;
        private UUID formDefinitionId;
        private String onUploadAction;
        private Boolean isRequired;
        private Integer sortOrder;
        private Boolean isActive;

        public static DocumentTypeDto from(ProductDocumentType dt) {
            DocumentTypeDto dto = new DocumentTypeDto();
            dto.id = dt.getId();
            dto.categoryId = dt.getCategory().getId();
            dto.categoryCode = dt.getCategory().getCode();
            dto.categoryName = dt.getCategory().getName();
            dto.name = dt.getName();
            dto.code = dt.getCode();
            dto.sourceType = dt.getSourceType();
            dto.formDefinitionId = dt.getFormDefinitionId();
            dto.onUploadAction = dt.getOnUploadAction();
            dto.isRequired = dt.getIsRequired();
            dto.sortOrder = dt.getSortOrder();
            dto.isActive = dt.getIsActive();
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
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public UUID getFormDefinitionId() { return formDefinitionId; }
        public void setFormDefinitionId(UUID formDefinitionId) { this.formDefinitionId = formDefinitionId; }
        public String getOnUploadAction() { return onUploadAction; }
        public void setOnUploadAction(String onUploadAction) { this.onUploadAction = onUploadAction; }
        public Boolean getIsRequired() { return isRequired; }
        public void setIsRequired(Boolean isRequired) { this.isRequired = isRequired; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    }
}
