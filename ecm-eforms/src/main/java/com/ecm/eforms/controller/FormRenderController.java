package com.ecm.eforms.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.service.FormDefinitionService;
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
 *
 * Any authenticated ECM user can render a form (to fill it).
 * Non-privileged users are still blocked from managing definitions.
 */
@RestController
@RequestMapping("/api/eforms/render")
@RequiredArgsConstructor
public class FormRenderController {

    private final FormDefinitionService definitionService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPublished(
            @RequestParam(required = false) String productType) {

        List<Map<String, Object>> forms = definitionService
            .list("PUBLISHED", productType, null, PageRequest.of(0, 100))
            .getContent().stream()
            .map(d -> Map.<String, Object>of(
                "formKey",         d.getFormKey(),
                "name",            d.getName(),
                "description",     d.getDescription() != null ? d.getDescription() : "",
                "productTypeCode", d.getProductTypeCode() != null ? d.getProductTypeCode() : "",
                "formTypeCode",    d.getFormTypeCode()    != null ? d.getFormTypeCode()    : "",
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

    private Map<String, Object> buildRenderPayload(FormDefinition def) {
        return Map.of(
            "formKey",        def.getFormKey(),
            "name",           def.getName(),
            "version",        def.getVersion(),
            "status",         def.getStatus(),
            "schema",         def.getSchema(),
            "uiConfig",       def.getUiConfig()       != null ? def.getUiConfig()       : Map.of(),
            "workflowConfig", def.getWorkflowConfig() != null ? def.getWorkflowConfig() : Map.of(),
            "allowSaveDraft", def.getSchema() != null && def.getSchema().isAllowSaveDraft()
        );
    }
}
