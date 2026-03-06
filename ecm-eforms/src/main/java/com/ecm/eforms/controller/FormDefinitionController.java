package com.ecm.eforms.controller;

import com.ecm.eforms.mapper.FormMapper;
import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.model.dto.EFormsDtos.*;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.service.FormDefinitionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Form Definition CRUD.
 *
 * POST   /api/eforms/definitions              create DRAFT
 * GET    /api/eforms/definitions              list (filter by status/productType/formType)
 * GET    /api/eforms/definitions/{id}         full definition
 * GET    /api/eforms/definitions/{id}/versions version history
 * PUT    /api/eforms/definitions/{id}         update (DRAFT only)
 * DELETE /api/eforms/definitions/{id}         soft-delete (DRAFT only)
 */
@RestController
@RequestMapping("/api/eforms/definitions")
@RequiredArgsConstructor
public class FormDefinitionController {

    private final FormDefinitionService definitionService;
    private final FormMapper            formMapper;

    @PostMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> create(
            @Valid @RequestBody CreateFormDefinitionRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        FormDefinition def = definitionService.create(req, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(formMapper.toDto(def)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER','ECM_BACKOFFICE','ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<Page<FormDefinitionSummary>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String formType,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<FormDefinition> defs = definitionService.list(status, productType, formType,
                PageRequest.of(page, size, Sort.by("updatedAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(defs.map(formMapper::toSummary)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER','ECM_BACKOFFICE','ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toDto(definitionService.getById(id))));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<List<FormDefinitionSummary>>> getVersions(@PathVariable UUID id) {
        FormDefinition def = definitionService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toSummaryList(definitionService.getVersionHistory(def.getFormKey()))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<FormDefinitionDto>> update(
            @PathVariable UUID id,
            @RequestBody UpdateFormDefinitionRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(ApiResponse.ok(
                formMapper.toDto(definitionService.update(id, req, jwt.getSubject()))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_DESIGNER')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {

        definitionService.delete(id, jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}