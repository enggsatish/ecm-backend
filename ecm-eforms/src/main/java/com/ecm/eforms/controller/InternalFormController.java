package com.ecm.eforms.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.service.FormDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal service-to-service endpoints — not exposed via the public API gateway.
 * Permitted without a JWT in EFormsSecurityConfig, same pattern as the DocuSign
 * internal endpoints. Callers identify themselves via X-Internal-Service (not
 * validated as a credential — trust boundary is "not reachable from outside the
 * cluster/gateway", same as the existing docusign/create-envelope endpoint).
 */
@RestController
@RequestMapping("/api/eforms/internal")
@RequiredArgsConstructor
public class InternalFormController {

    private final FormDefinitionService definitionService;

    /**
     * Resolve the document category configured for a published form.
     * Used by ecm-batch's QR fast-path — an eForms-generated QR only encodes the
     * form key, not a category id, so the scanner looks it up here.
     */
    @GetMapping("/definitions/{formKey}/category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCategoryForForm(@PathVariable String formKey) {
        FormDefinition def = definitionService.getPublishedByFormKey(formKey);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("documentCategoryId", def.getDocumentCategoryId());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
