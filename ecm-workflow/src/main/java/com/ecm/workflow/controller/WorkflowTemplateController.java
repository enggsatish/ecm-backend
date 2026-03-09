package com.ecm.workflow.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.model.entity.WorkflowTemplateMapping;
import com.ecm.workflow.service.WorkflowTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow/templates")
@RequiredArgsConstructor
public class WorkflowTemplateController {

    private final WorkflowTemplateService service;

    // ─── CRUD ────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<List<WorkflowTemplate>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<WorkflowTemplate>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    @AuditLog(event = "TEMPLATE_CREATED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<WorkflowTemplate>> create(
            @Valid @RequestBody CreateTemplateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        WorkflowTemplate created = service.create(
                req.dsl(), req.slaHours(), req.warningThresholdPct(),
                req.escalationHours(), req.escalationGroupKey(),
                jwt.getClaimAsString("email"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Template created"));
    }

    // ─── DSL update (simple / legacy step builder) ───────────────────────────

    @PutMapping("/{id}/dsl")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    @AuditLog(event = "TEMPLATE_DSL_UPDATED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<WorkflowTemplate>> updateDsl(
            @PathVariable Integer id,
            @Valid @RequestBody WorkflowTemplateDsl dsl) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateDsl(id, dsl), "DSL updated"));
    }

    // ─── BPMN XML update (visual designer) ───────────────────────────────────

    /**
     * Stores raw BPMN 2.0 XML authored in the bpmn.io visual designer.
     *
     * The request body must be well-formed BPMN XML (Content-Type: application/xml).
     * Calling this endpoint switches the template's authoring mode to VISUAL so that
     * publish uses this XML directly rather than generating from the JSON DSL.
     *
     * Only DRAFT templates may be updated.
     */
    @PutMapping(
            value = "/{id}/bpmn",
            consumes = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE }
    )
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    @AuditLog(event = "TEMPLATE_BPMN_UPDATED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<WorkflowTemplate>> updateBpmn(
            @PathVariable Integer id,
            @RequestBody String bpmnXml) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.updateBpmnXml(id, bpmnXml), "BPMN XML saved"));
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "TEMPLATE_PUBLISHED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<WorkflowTemplate>> publish(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.publish(id), "Template published and deployed to Flowable"));
    }

    @PostMapping("/{id}/deprecate")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "TEMPLATE_DEPRECATED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<WorkflowTemplate>> deprecate(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.deprecate(id), "Template deprecated"));
    }

    // ─── Preview ─────────────────────────────────────────────────────────────

    /**
     * Preview the BPMN XML that would be deployed on publish — without deploying.
     * Returns stored BPMN XML for VISUAL-source templates; generates from DSL otherwise.
     * Used by the bpmn.io frontend designer to seed the modeler on load.
     */
    @GetMapping(value = "/{id}/preview-bpmn", produces = MediaType.APPLICATION_XML_VALUE)
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    public ResponseEntity<String> previewBpmn(@PathVariable Integer id) {
        return ResponseEntity.ok(service.previewBpmn(id));
    }

    // ─── Mappings ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/mappings")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<List<WorkflowTemplateMapping>>> getMappings(
            @PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getMappings(id)));
    }

    @PostMapping("/{id}/mappings")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "TEMPLATE_MAPPING_ADDED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<WorkflowTemplateMapping>> addMapping(
            @PathVariable Integer id,
            @RequestBody MappingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.ok(service.addMapping(id, req.productId(), req.categoryId(), req.priority()),
                        "Mapping added"));
    }

    @DeleteMapping("/{id}/mappings/{mappingId}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "TEMPLATE_MAPPING_REMOVED", resourceType = "WORKFLOW_TEMPLATE")
    public ResponseEntity<ApiResponse<Void>> removeMapping(
            @PathVariable Integer id, @PathVariable Integer mappingId) {
        service.removeMapping(mappingId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Mapping removed"));
    }

    // ─── Request records ─────────────────────────────────────────────────────

    public record CreateTemplateRequest(
            @Valid WorkflowTemplateDsl dsl,
            Integer slaHours,
            Integer warningThresholdPct,
            Integer escalationHours,
            String escalationGroupKey
    ) {}

    public record MappingRequest(
            Integer productId,
            Integer categoryId,
            Integer priority
    ) {}
}