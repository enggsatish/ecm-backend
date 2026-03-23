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
 * GET    /api/notifications/email-templates        list all
 * GET    /api/notifications/email-templates/{id}   get one
 * PUT    /api/notifications/email-templates/{id}   update
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

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable Integer id,
            @RequestBody UpdateTemplateRequest req) {
        templateService.update(id, req);
        return ResponseEntity.ok(ApiResponse.ok(null, "Template updated"));
    }
}
