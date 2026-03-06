package com.ecm.admin.dto;

import com.ecm.admin.entity.RetentionPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public class RetentionPolicyDto {

    private Integer id;
    private String name;
    private Integer categoryId;
    private String productCode;
    private Integer archiveAfterDays;
    private Integer purgeAfterDays;
    private Boolean isActive;
    private OffsetDateTime createdAt;

    public static RetentionPolicyDto from(RetentionPolicy rp) {
        RetentionPolicyDto dto = new RetentionPolicyDto();
        dto.id = rp.getId(); dto.name = rp.getName(); dto.categoryId = rp.getCategoryId();
        dto.productCode = rp.getProductCode(); dto.archiveAfterDays = rp.getArchiveAfterDays();
        dto.purgeAfterDays = rp.getPurgeAfterDays(); dto.isActive = rp.getIsActive(); dto.createdAt = rp.getCreatedAt();
        return dto;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public Integer getArchiveAfterDays() { return archiveAfterDays; }
    public void setArchiveAfterDays(Integer v) { this.archiveAfterDays = v; }
    public Integer getPurgeAfterDays() { return purgeAfterDays; }
    public void setPurgeAfterDays(Integer v) { this.purgeAfterDays = v; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public static class Request {
        @NotBlank @Size(max = 200) private String name;
        private Integer categoryId;
        private String productCode;
        @NotNull @Positive private Integer archiveAfterDays = 365;
        @NotNull @Positive private Integer purgeAfterDays = 2555;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getCategoryId() { return categoryId; }
        public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }
        public Integer getArchiveAfterDays() { return archiveAfterDays; }
        public void setArchiveAfterDays(Integer v) { this.archiveAfterDays = v; }
        public Integer getPurgeAfterDays() { return purgeAfterDays; }
        public void setPurgeAfterDays(Integer v) { this.purgeAfterDays = v; }
    }
}
