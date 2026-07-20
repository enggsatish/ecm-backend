package com.ecm.admin.controller;

import com.ecm.admin.dto.ProfileAttributeDto;
import com.ecm.admin.dto.RelationshipTypeDto;
import com.ecm.admin.service.CustomerProfileSchemaService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Superadmin config for CRM-aware form fill: canonical customer profile
 * attributes, their Salesforce mappings, and customer relationship types
 * (memberships, accounts). Same permission as other integration/schema
 * config screens (DocuSign, AI Gateway) — this is that same class of screen.
 */
@RestController
@RequestMapping("/api/admin/customer-profile-schema")
@PreAuthorize("hasPermission(null, 'admin:configure')")
public class CustomerProfileSchemaController {

    private final CustomerProfileSchemaService service;

    public CustomerProfileSchemaController(CustomerProfileSchemaService service) {
        this.service = service;
    }

    // ── Profile attributes ─────────────────────────────────────────────────────

    @GetMapping("/attributes")
    public ResponseEntity<ApiResponse<List<ProfileAttributeDto>>> listAttributes() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAttributes()));
    }

    @PostMapping("/attributes")
    public ResponseEntity<ApiResponse<ProfileAttributeDto>> createAttribute(
            @Valid @RequestBody ProfileAttributeDto.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createAttribute(req), "Profile attribute created"));
    }

    @PutMapping("/attributes/{id}")
    public ResponseEntity<ApiResponse<ProfileAttributeDto>> updateAttribute(
            @PathVariable Integer id, @Valid @RequestBody ProfileAttributeDto.Request req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateAttribute(id, req), "Profile attribute updated"));
    }

    @DeleteMapping("/attributes/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivateAttribute(@PathVariable Integer id) {
        service.deactivateAttribute(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Profile attribute deactivated"));
    }

    // ── Relationship types ──────────────────────────────────────────────────────

    @GetMapping("/relationship-types")
    public ResponseEntity<ApiResponse<List<RelationshipTypeDto>>> listRelationshipTypes() {
        return ResponseEntity.ok(ApiResponse.ok(service.listRelationshipTypes()));
    }

    @PostMapping("/relationship-types")
    public ResponseEntity<ApiResponse<RelationshipTypeDto>> createRelationshipType(
            @Valid @RequestBody RelationshipTypeDto.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createRelationshipType(req), "Relationship type created"));
    }

    @PutMapping("/relationship-types/{id}")
    public ResponseEntity<ApiResponse<RelationshipTypeDto>> updateRelationshipType(
            @PathVariable Integer id, @Valid @RequestBody RelationshipTypeDto.Request req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateRelationshipType(id, req), "Relationship type updated"));
    }

    @DeleteMapping("/relationship-types/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivateRelationshipType(@PathVariable Integer id) {
        service.deactivateRelationshipType(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Relationship type deactivated"));
    }

    @PostMapping("/relationship-types/{id}/attributes")
    public ResponseEntity<ApiResponse<RelationshipTypeDto.Attribute>> addRelationshipAttribute(
            @PathVariable Integer id, @Valid @RequestBody RelationshipTypeDto.Attribute.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.addRelationshipAttribute(id, req), "Attribute added"));
    }

    @DeleteMapping("/relationship-types/{id}/attributes/{attributeId}")
    public ResponseEntity<ApiResponse<Void>> removeRelationshipAttribute(
            @PathVariable Integer id, @PathVariable Integer attributeId) {
        service.removeRelationshipAttribute(id, attributeId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Attribute removed"));
    }
}
