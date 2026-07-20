package com.ecm.admin.dto;

import com.ecm.admin.entity.CustomerProfileAttribute;
import com.ecm.admin.entity.CustomerProfileAttributeMapping;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public class ProfileAttributeDto {

    private Integer id;
    private String key;
    private String label;
    private String valueType;
    private String source;
    private Integer sortOrder;
    private String segments;           // comma-separated segment codes; null/blank = all segments
    private Boolean isActive;
    private String salesforceObject;   // null when source = MANUAL
    private String salesforceField;    // null when source = MANUAL
    private OffsetDateTime createdAt;

    public static ProfileAttributeDto from(CustomerProfileAttribute attr, CustomerProfileAttributeMapping mapping) {
        ProfileAttributeDto dto = new ProfileAttributeDto();
        dto.id = attr.getId();
        dto.key = attr.getKey();
        dto.label = attr.getLabel();
        dto.valueType = attr.getValueType();
        dto.source = attr.getSource();
        dto.sortOrder = attr.getSortOrder();
        dto.segments = attr.getSegments();
        dto.isActive = attr.getIsActive();
        dto.createdAt = attr.getCreatedAt();
        if (mapping != null) {
            dto.salesforceObject = mapping.getSalesforceObject();
            dto.salesforceField = mapping.getSalesforceField();
        }
        return dto;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getSegments() { return segments; }
    public void setSegments(String segments) { this.segments = segments; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getSalesforceObject() { return salesforceObject; }
    public void setSalesforceObject(String salesforceObject) { this.salesforceObject = salesforceObject; }
    public String getSalesforceField() { return salesforceField; }
    public void setSalesforceField(String salesforceField) { this.salesforceField = salesforceField; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public static class Request {
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "must be lower_snake_case")
        private String key;
        @NotBlank @Size(max = 200) private String label;
        @NotBlank private String valueType;
        @NotBlank private String source;
        private Integer sortOrder = 0;
        private String segments;
        private String salesforceObject;
        private String salesforceField;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getValueType() { return valueType; }
        public void setValueType(String valueType) { this.valueType = valueType; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
        public String getSegments() { return segments; }
        public void setSegments(String segments) { this.segments = segments; }
        public String getSalesforceObject() { return salesforceObject; }
        public void setSalesforceObject(String salesforceObject) { this.salesforceObject = salesforceObject; }
        public String getSalesforceField() { return salesforceField; }
        public void setSalesforceField(String salesforceField) { this.salesforceField = salesforceField; }
    }
}
