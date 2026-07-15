package com.ecm.eforms.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.service.FormDefinitionService;
import com.ecm.eforms.service.PdfGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Form Rendering API — supplies the published schema to the fill UI.
 *
 * GET /api/eforms/render                       list PUBLISHED forms (form picker)
 * GET /api/eforms/render/{formKey}             latest PUBLISHED schema
 * GET /api/eforms/render/{formKey}/v/{version} specific version schema
 * GET /api/eforms/render/{formKey}/blank-pdf   blank printable PDF (no data, no submission)
 *
 * Any authenticated ECM user can render a form (to fill it).
 * Non-privileged users are still blocked from managing definitions.
 */
@RestController
@RequestMapping("/api/eforms/render")
@RequiredArgsConstructor
public class FormRenderController {

    private final FormDefinitionService definitionService;
    private final PdfGenerationService  pdfService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPublished() {

        List<Map<String, Object>> forms = definitionService
            .list("PUBLISHED", PageRequest.of(0, 100))
            .getContent().stream()
            .map(d -> Map.<String, Object>of(
                "id",              d.getId().toString(),
                "formKey",         d.getFormKey(),
                "name",            d.getName(),
                "description",     d.getDescription() != null ? d.getDescription() : "",
                "version",         d.getVersion(),
                "estimatedMinutes",
                    d.getSchema() != null && d.getSchema().getEstimatedMinutes() != null
                        ? d.getSchema().getEstimatedMinutes() : 0
            ))
            .toList();

        return ResponseEntity.ok(ApiResponse.ok(forms));
    }

    @GetMapping("/{formKey}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPublished(@PathVariable String formKey) {
        return ResponseEntity.ok(ApiResponse.ok(
            buildRenderPayload(definitionService.getPublishedByFormKey(formKey))));
    }

    @GetMapping("/{formKey}/v/{version}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getVersion(
            @PathVariable String formKey, @PathVariable Integer version) {
        return ResponseEntity.ok(ApiResponse.ok(
            buildRenderPayload(definitionService.getByFormKeyAndVersion(formKey, version))));
    }

    @GetMapping("/{formKey}/blank-pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> getBlankPdf(@PathVariable String formKey) {
        FormDefinition def = definitionService.getPublishedByFormKey(formKey);
        byte[] pdf = pdfService.generateBlank(def);
        String filename = formKey + "-blank.pdf";

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    private Map<String, Object> buildRenderPayload(FormDefinition def) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("formKey",        def.getFormKey());
        payload.put("name",           def.getName());
        payload.put("version",        def.getVersion());
        payload.put("status",         def.getStatus());
        payload.put("documentCategoryId", def.getDocumentCategoryId());
        payload.put("schema",         def.getSchema());
        payload.put("uiConfig",       def.getUiConfig()       != null ? def.getUiConfig()       : Map.of());
        payload.put("workflowConfig", def.getWorkflowConfig() != null ? def.getWorkflowConfig() : Map.of());
        payload.put("docuSignConfig", def.getDocuSignConfig() != null ? def.getDocuSignConfig() : Map.of());
        payload.put("allowSaveDraft", def.getSchema() != null && def.getSchema().isAllowSaveDraft());
        return payload;
    }
}
