package com.ecm.admin.service;

import com.ecm.admin.dto.TenantConfigDto;
import com.ecm.admin.entity.TenantConfig;
import com.ecm.admin.repository.TenantConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class SystemConfigService {

    private final TenantConfigRepository repo;

    public SystemConfigService(TenantConfigRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public List<TenantConfigDto> listAll() {
        return repo.findAll().stream().map(TenantConfigDto::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TenantConfigDto get(String key) { return TenantConfigDto.from(findOrThrow(key)); }

    /** Upsert a single key */
    public TenantConfigDto upsert(String key, TenantConfigDto.UpdateRequest req) {
        TenantConfig tc = repo.findById(key).orElseGet(() -> {
            TenantConfig n = new TenantConfig(); n.setKey(key); return n;
        });
        tc.setValue(req.getValue());
        if (req.getDescription() != null) tc.setDescription(req.getDescription());
        tc.setUpdatedAt(OffsetDateTime.now());
        return TenantConfigDto.from(repo.save(tc));
    }

    /** Bulk upsert — saves entire config form in one call */
    public List<TenantConfigDto> bulkUpdate(List<TenantConfigDto> configs) {
        return configs.stream().map(dto -> {
            TenantConfig tc = repo.findById(dto.getKey()).orElseGet(() -> {
                TenantConfig n = new TenantConfig(); n.setKey(dto.getKey()); return n;
            });
            tc.setValue(dto.getValue() != null ? dto.getValue() : "");
            if (dto.getDescription() != null) tc.setDescription(dto.getDescription());
            tc.setUpdatedAt(OffsetDateTime.now());
            return TenantConfigDto.from(repo.save(tc));
        }).collect(Collectors.toList());
    }

    private TenantConfig findOrThrow(String key) {
        return repo.findById(key).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Config key not found: " + key));
    }
}
