package com.ecm.notification.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.notification.service.EmailTemplateService;
import com.ecm.notification.service.EmailTemplateService.EmailTemplateDto;
import com.ecm.notification.service.EmailTemplateService.UpdateTemplateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GET    /api/notifications/email-templates             list all (admin)
 * GET    /api/notifications/email-templates/{id}        get one (admin)
 * GET    /api/notifications/email-templates/by-key/{key} get one by key (any authenticated user)
 * PUT    /api/notifications/email-templates/{id}        update (admin)
 */
@RestController
@RequestMapping("/api/notifications/email-templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService templateService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<List<EmailTemplateDto>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<EmailTemplateDto>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.getById(id)));
    }

    /**
     * Read-only lookup by key for end-user flows that need to display a
     * template's default subject/body (e.g. the DocuSign signing-request step
     * in the eForms fill flow). Deliberately not admin-gated — any
     * authenticated ECM user may read a template, only admins may edit one.
     */
    @GetMapping("/by-key/{key}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<EmailTemplateDto>> getByKey(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.getByKey(key)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable Integer id,
            @RequestBody UpdateTemplateRequest req) {
        templateService.update(id, req);
        return ResponseEntity.ok(ApiResponse.ok(null, "Template updated"));
    }
}
