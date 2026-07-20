package com.ecm.admin.dto;

import com.ecm.admin.entity.CustomerRelationshipAttribute;
import com.ecm.admin.entity.CustomerRelationshipType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.stream.Collectors;

public class RelationshipTypeDto {

    private Integer id;
    private String name;
    private String salesforceObject;
    private String salesforceParentField;
    private String parentObject;
    private String segments;
    private Integer sortOrder;
    private Boolean isActive;
    private List<Attribute> attributes;

    public static RelationshipTypeDto from(CustomerRelationshipType type) {
        RelationshipTypeDto dto = new RelationshipTypeDto();
        dto.id = type.getId();
        dto.name = type.getName();
        dto.salesforceObject = type.getSalesforceObject();
        dto.salesforceParentField = type.getSalesforceParentField();
        dto.parentObject = type.getParentObject();
        dto.segments = type.getSegments();
        dto.sortOrder = type.getSortOrder();
        dto.isActive = type.getIsActive();
        dto.attributes = type.getAttributes() == null ? List.of()
                : type.getAttributes().stream().map(Attribute::from).collect(Collectors.toList());
        return dto;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSalesforceObject() { return salesforceObject; }
    public void setSalesforceObject(String salesforceObject) { this.salesforceObject = salesforceObject; }
    public String getSalesforceParentField() { return salesforceParentField; }
    public void setSalesforceParentField(String salesforceParentField) { this.salesforceParentField = salesforceParentField; }
    public String getParentObject() { return parentObject; }
    public void setParentObject(String parentObject) { this.parentObject = parentObject; }
    public String getSegments() { return segments; }
    public void setSegments(String segments) { this.segments = segments; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public List<Attribute> getAttributes() { return attributes; }
    public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }

    public static class Attribute {
        private Integer id;
        private String key;
        private String label;
        private String valueType;
        private String salesforceField;
        private Integer sortOrder;

        public static Attribute from(CustomerRelationshipAttribute a) {
            Attribute dto = new Attribute();
            dto.id = a.getId(); dto.key = a.getKey(); dto.label = a.getLabel();
            dto.valueType = a.getValueType(); dto.salesforceField = a.getSalesforceField();
            dto.sortOrder = a.getSortOrder();
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
        public String getSalesforceField() { return salesforceField; }
        public void setSalesforceField(String salesforceField) { this.salesforceField = salesforceField; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

        public static class Request {
            @NotBlank @Size(max = 100) private String key;
            @NotBlank @Size(max = 200) private String label;
            @NotBlank private String valueType;
            @NotBlank private String salesforceField;
            private Integer sortOrder = 0;

            public String getKey() { return key; }
            public void setKey(String key) { this.key = key; }
            public String getLabel() { return label; }
            public void setLabel(String label) { this.label = label; }
            public String getValueType() { return valueType; }
            public void setValueType(String valueType) { this.valueType = valueType; }
            public String getSalesforceField() { return salesforceField; }
            public void setSalesforceField(String salesforceField) { this.salesforceField = salesforceField; }
            public Integer getSortOrder() { return sortOrder; }
            public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
        }
    }

    public static class Request {
        @NotBlank @Size(max = 100) private String name;
        @NotBlank private String salesforceObject;
        @NotBlank private String salesforceParentField;
        @NotBlank private String parentObject;
        private String segments;
        private Integer sortOrder = 0;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSalesforceObject() { return salesforceObject; }
        public void setSalesforceObject(String salesforceObject) { this.salesforceObject = salesforceObject; }
        public String getSalesforceParentField() { return salesforceParentField; }
        public void setSalesforceParentField(String salesforceParentField) { this.salesforceParentField = salesforceParentField; }
        public String getParentObject() { return parentObject; }
        public void setParentObject(String parentObject) { this.parentObject = parentObject; }
        public String getSegments() { return segments; }
        public void setSegments(String segments) { this.segments = segments; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }
}
