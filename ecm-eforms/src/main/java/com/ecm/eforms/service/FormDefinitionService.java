package com.ecm.eforms.service;

import com.ecm.eforms.model.dto.EFormsDtos.*;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.repository.FormDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FormDefinitionService {

    private static final String TENANT = "default";
    private final FormDefinitionRepository repo;

    // ── Create ────────────────────────────────────────────────────────

    public FormDefinition create(CreateFormDefinitionRequest req, String userId) {
        int nextVersion = repo.findMaxVersionByTenantIdAndFormKey(TENANT, req.getFormKey()) + 1;

        FormDefinition def = FormDefinition.builder()
            .tenantId(TENANT)
            .formKey(req.getFormKey())
            .name(req.getName())
            .description(req.getDescription())
            .productTypeCode(req.getProductTypeCode())
            .formTypeCode(req.getFormTypeCode())
            .version(nextVersion)
            .status("DRAFT")
            .schema(req.getSchema())
            .uiConfig(req.getUiConfig())
            .workflowConfig(req.getWorkflowConfig())
            .docuSignConfig(req.getDocuSignConfig())
            .documentTemplateId(req.getDocumentTemplateId())
            .tags(req.getTags())
            .createdBy(userId)
            .updatedBy(userId)
            .build();

        FormDefinition saved = repo.save(def);
        log.info("Form definition created: id={}, formKey={}, version={}", saved.getId(), saved.getFormKey(), saved.getVersion());
        return saved;
    }

    // ── Read ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FormDefinition getById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Form definition not found: " + id));
    }

    @Transactional(readOnly = true)
    public FormDefinition getPublishedByFormKey(String formKey) {
        return repo.findByTenantIdAndFormKeyAndStatus(TENANT, formKey, "PUBLISHED")
            .orElseThrow(() -> new IllegalArgumentException("No published form found for key: " + formKey));
    }

    @Transactional(readOnly = true)
    public FormDefinition getByFormKeyAndVersion(String formKey, int version) {
        return repo.findByTenantIdAndFormKeyAndVersion(TENANT, formKey, version)
            .orElseThrow(() -> new IllegalArgumentException(
                "Form not found: " + formKey + " v" + version));
    }

    @Transactional(readOnly = true)
    public List<FormDefinition> getVersionHistory(String formKey) {
        return repo.findByTenantIdAndFormKeyOrderByVersionDesc(TENANT, formKey);
    }

    @Transactional(readOnly = true)
    public Page<FormDefinition> list(String status, String productType, String formType, Pageable pageable) {
        return repo.findAllWithFilters(TENANT, status, productType, formType, pageable);
    }

    // ── Update (DRAFT only) ───────────────────────────────────────────

    public FormDefinition update(UUID id, UpdateFormDefinitionRequest req, String userId) {
        FormDefinition def = getById(id);
        if (!def.isDraft())
            throw new IllegalStateException("Only DRAFT forms can be updated. Current status: " + def.getStatus());

        if (req.getName()               != null) def.setName(req.getName());
        if (req.getDescription()        != null) def.setDescription(req.getDescription());
        if (req.getProductTypeCode()    != null) def.setProductTypeCode(req.getProductTypeCode());
        if (req.getFormTypeCode()       != null) def.setFormTypeCode(req.getFormTypeCode());
        if (req.getSchema()             != null) def.setSchema(req.getSchema());
        if (req.getUiConfig()           != null) def.setUiConfig(req.getUiConfig());
        if (req.getWorkflowConfig()     != null) def.setWorkflowConfig(req.getWorkflowConfig());
        if (req.getDocuSignConfig()     != null) def.setDocuSignConfig(req.getDocuSignConfig());
        if (req.getDocumentTemplateId() != null) def.setDocumentTemplateId(req.getDocumentTemplateId());
        if (req.getTags()               != null) def.setTags(req.getTags());
        def.setUpdatedBy(userId);

        return repo.save(def);
    }

    // ── Delete (soft-delete DRAFT only) ──────────────────────────────

    public void delete(UUID id, String userId) {
        FormDefinition def = getById(id);
        if (!def.isDraft())
            throw new IllegalStateException("Only DRAFT forms can be deleted");
        def.setStatus("ARCHIVED");
        def.setUpdatedBy(userId);
        repo.save(def);
        log.info("Form definition soft-deleted: id={}", id);
    }
}
