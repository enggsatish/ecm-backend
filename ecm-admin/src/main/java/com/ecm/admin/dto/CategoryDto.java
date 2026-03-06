package com.ecm.admin.dto;

import com.ecm.admin.entity.DocumentCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class CategoryDto {

    private Integer id;
    private String name;
    private String code;
    private String description;
    private Integer parentId;
    private String parentName;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private List<CategoryDto> children;

    public static CategoryDto from(DocumentCategory cat) {
        CategoryDto dto = new CategoryDto();
        dto.id = cat.getId(); dto.name = cat.getName(); dto.code = cat.getCode();
        dto.description = cat.getDescription(); dto.isActive = cat.getIsActive(); dto.createdAt = cat.getCreatedAt();
        if (cat.getParent() != null) { dto.parentId = cat.getParent().getId(); dto.parentName = cat.getParent().getName(); }
        if (cat.getChildren() != null && !cat.getChildren().isEmpty())
            dto.children = cat.getChildren().stream().filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .map(CategoryDto::from).collect(Collectors.toList());
        return dto;
    }

    public static CategoryDto flat(DocumentCategory cat) {
        CategoryDto dto = new CategoryDto();
        dto.id = cat.getId(); dto.name = cat.getName(); dto.code = cat.getCode();
        dto.description = cat.getDescription(); dto.isActive = cat.getIsActive(); dto.createdAt = cat.getCreatedAt();
        if (cat.getParent() != null) { dto.parentId = cat.getParent().getId(); dto.parentName = cat.getParent().getName(); }
        return dto;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }
    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public List<CategoryDto> getChildren() { return children; }
    public void setChildren(List<CategoryDto> children) { this.children = children; }

    public static class Request {
        @NotBlank @Size(max = 200) private String name;
        @NotBlank @Size(max = 100) private String code;
        private String description;
        private Integer parentId;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getParentId() { return parentId; }
        public void setParentId(Integer parentId) { this.parentId = parentId; }
    }
}
