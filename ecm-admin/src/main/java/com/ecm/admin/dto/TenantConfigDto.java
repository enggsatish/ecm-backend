package com.ecm.admin.dto;

import com.ecm.admin.entity.TenantConfig;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;

public class TenantConfigDto {

    private String key;
    private String value;
    private String description;
    private OffsetDateTime updatedAt;

    public static TenantConfigDto from(TenantConfig tc) {
        TenantConfigDto dto = new TenantConfigDto();
        dto.key = tc.getKey(); dto.value = tc.getValue();
        dto.description = tc.getDescription(); dto.updatedAt = tc.getUpdatedAt();
        return dto;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static class UpdateRequest {
        @NotBlank private String value;
        private String description;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class BulkUpdateRequest {
        private List<TenantConfigDto> configs;
        public List<TenantConfigDto> getConfigs() { return configs; }
        public void setConfigs(List<TenantConfigDto> configs) { this.configs = configs; }
    }
}
