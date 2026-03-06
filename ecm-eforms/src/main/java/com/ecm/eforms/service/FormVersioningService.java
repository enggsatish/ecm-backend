package com.ecm.eforms.service;

import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.repository.FormDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages the DRAFT → PUBLISHED → ARCHIVED lifecycle.
 *
 * Publish is atomic: archives the existing PUBLISHED version (if any)
 * and sets the new one to PUBLISHED in the same transaction.
 * The partial unique index on the database enforces the single-published
 * invariant as a safety net.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FormVersioningService {

    private static final String TENANT = "default";
    private final FormDefinitionRepository repo;
    private final FormDefinitionService    definitionService;

    /** DRAFT → PUBLISHED. Auto-archives the previous PUBLISHED version. */
    public FormDefinition publish(UUID id, String userId) {
        FormDefinition def = definitionService.getById(id);
        if (!def.isDraft())
            throw new IllegalStateException("Only DRAFT forms can be published. Status: " + def.getStatus());

        // Archive existing published version first (same transaction)
        int archived = repo.archivePublishedVersion(TENANT, def.getFormKey(), userId);
        log.info("Archived {} previously PUBLISHED version(s) of formKey={}", archived, def.getFormKey());

        def.publish(userId);
        FormDefinition published = repo.save(def);
        log.info("Published: formKey={}, version={}, id={}", def.getFormKey(), def.getVersion(), id);
        return published;
    }

    /** PUBLISHED → ARCHIVED */
    public FormDefinition archive(UUID id, String userId) {
        FormDefinition def = definitionService.getById(id);
        def.archive(userId);
        return repo.save(def);
    }

    /**
     * Clone any version to a new DRAFT.
     *
     * @param sourceId   source form definition UUID
     * @param userId     the cloning user
     * @param newFormKey if non-null, the clone uses a different form key (for templates)
     */
    public FormDefinition clone(UUID sourceId, String userId, String newFormKey) {
        FormDefinition source = definitionService.getById(sourceId);
        String targetKey = (newFormKey != null && !newFormKey.isBlank()) ? newFormKey : source.getFormKey();
        int nextVersion = repo.findMaxVersionByTenantIdAndFormKey(TENANT, targetKey) + 1;

        FormDefinition clone = FormDefinition.builder()
            .tenantId(TENANT)
            .formKey(targetKey)
            .name(source.getName() + (newFormKey == null ? " (Copy)" : ""))
            .description(source.getDescription())
            .productTypeCode(source.getProductTypeCode())
            .formTypeCode(source.getFormTypeCode())
            .version(nextVersion)
            .status("DRAFT")
            .schema(source.getSchema())
            .uiConfig(source.getUiConfig())
            .workflowConfig(source.getWorkflowConfig())
            .docuSignConfig(source.getDocuSignConfig())
            .documentTemplateId(source.getDocumentTemplateId())
            .tags(source.getTags())
            .createdBy(userId)
            .updatedBy(userId)
            .build();

        FormDefinition saved = repo.save(clone);
        log.info("Cloned: source={}, newId={}, formKey={}, version={}", sourceId, saved.getId(), targetKey, nextVersion);
        return saved;
    }

    /** ARCHIVED → DEPRECATED (long-term cleanup) */
    public FormDefinition deprecate(UUID id, String userId) {
        FormDefinition def = definitionService.getById(id);
        if (!"ARCHIVED".equals(def.getStatus()))
            throw new IllegalStateException("Only ARCHIVED forms can be deprecated");
        def.setStatus("DEPRECATED");
        def.setUpdatedBy(userId);
        return repo.save(def);
    }
}
