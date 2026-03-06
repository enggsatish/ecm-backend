package com.ecm.admin.dto;

import com.ecm.admin.entity.Department;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class DepartmentDto {

    private Integer id;
    private String name;
    private String code;
    private Integer parentId;
    private String parentName;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private List<DepartmentDto> children;

    /** Full tree — includes nested children */
    public static DepartmentDto from(Department dept) {
        DepartmentDto dto = new DepartmentDto();
        dto.id = dept.getId();
        dto.name = dept.getName();
        dto.code = dept.getCode();
        dto.isActive = dept.getIsActive();
        dto.createdAt = dept.getCreatedAt();
        if (dept.getParent() != null) {
            dto.parentId = dept.getParent().getId();
            dto.parentName = dept.getParent().getName();
        }
        if (dept.getChildren() != null && !dept.getChildren().isEmpty()) {
            dto.children = dept.getChildren().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .map(DepartmentDto::from)
                .collect(Collectors.toList());
        }
        return dto;
    }

    /** Flat — no children populated */
    public static DepartmentDto flat(Department dept) {
        DepartmentDto dto = new DepartmentDto();
        dto.id = dept.getId();
        dto.name = dept.getName();
        dto.code = dept.getCode();
        dto.isActive = dept.getIsActive();
        dto.createdAt = dept.getCreatedAt();
        if (dept.getParent() != null) {
            dto.parentId = dept.getParent().getId();
            dto.parentName = dept.getParent().getName();
        }
        return dto;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }
    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public List<DepartmentDto> getChildren() { return children; }
    public void setChildren(List<DepartmentDto> children) { this.children = children; }
}
