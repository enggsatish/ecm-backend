package com.ecm.eforms.controller;

import com.ecm.eforms.mapper.FormMapper;
import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.model.dto.EFormsDtos.FormDefinitionDto;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.service.FormDefinitionService;
import com.ecm.eforms.service.FormVersioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle actions for form designers.
 *
 * POST /api/eforms/definitions/{id}/publish    DRAFT → PUBLISHED
 * POST /api/eforms/definitions/{id}/archive    PUBLISHED → ARCHIVED
 * POST /api/eforms/definitions/{id}/clone      any → new DRAFT
 * POST /api/eforms/definitions/{id}/deprecate  ARCHIVED → DEPRECATED
 * GET  /api/eforms/definitions/{id}/preview    schema preview (no submit)
 */
@RestController
@RequestMapping("/api/eforms/definitions")
@RequiredArgsConstructor
public class FormDesignerController {

    private final FormVersioningService versioningService;
    private final FormDefinitionService definitionService;
    private final FormMapper            formMapper;

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> publish(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toDto(versioningService.publish(id, jwt.getSubject()))));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> archive(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toDto(versioningService.archive(id, jwt.getSubject()))));
    }

    /** Body (optional): { "newFormKey": "OTHER-FORM-KEY" } */
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> clone(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        String newKey = (body != null) ? body.get("newFormKey") : null;
        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toDto(versioningService.clone(id, jwt.getSubject(), newKey))));
    }

    @PostMapping("/{id}/deprecate")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> deprecate(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toDto(versioningService.deprecate(id, jwt.getSubject()))));
    }

    /**
     * Preview endpoint: returns the full schema with previewMode=true flag.
     * The frontend suppresses the submit button when previewMode is set.
     */
    @GetMapping("/{id}/preview")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(@PathVariable UUID id) {
        FormDefinition def = definitionService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "formKey",      def.getFormKey(),
                "name",         def.getName(),
                "version",      def.getVersion(),
                "status",       def.getStatus(),
                "schema",       def.getSchema(),
                "uiConfig",     def.getUiConfig()       != null ? def.getUiConfig()       : Map.of(),
                "docuSignConfig", def.getDocuSignConfig() != null ? def.getDocuSignConfig() : Map.of(),
                "previewMode",  true
        )));
    }
}