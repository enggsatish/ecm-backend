package com.ecm.admin.controller;

import com.ecm.admin.entity.OcrTemplate;
import com.ecm.admin.service.OcrTemplateService;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OCR Template CRUD.
 *
 * GET    /api/admin/ocr-templates            list all
 * GET    /api/admin/ocr-templates/{id}       detail
 * POST   /api/admin/ocr-templates            create
 * PUT    /api/admin/ocr-templates/{id}       update
 * DELETE /api/admin/ocr-templates/{id}       deactivate
 */
@RestController
@RequestMapping("/api/admin/ocr-templates")
@RequiredArgsConstructor
public class OcrTemplateController {

    private final OcrTemplateService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ocr:trigger')")
    public ResponseEntity<ApiResponse<List<OcrTemplate>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ocr:trigger')")
    public ResponseEntity<ApiResponse<OcrTemplate>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ocr:trigger')")
    public ResponseEntity<ApiResponse<OcrTemplate>> create(
            @RequestBody OcrTemplate template,
            @AuthenticationPrincipal Jwt jwt) {
        template.setCreatedBy(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(template), "OCR template created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ocr:trigger')")
    public ResponseEntity<ApiResponse<OcrTemplate>> update(
            @PathVariable Integer id,
            @RequestBody OcrTemplate template) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, template), "OCR template updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ocr:trigger')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Integer id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "OCR template deactivated"));
    }
}
