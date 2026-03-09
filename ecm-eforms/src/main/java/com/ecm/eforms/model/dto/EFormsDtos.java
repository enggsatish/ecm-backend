package com.ecm.eforms.model.dto;

import com.ecm.eforms.model.schema.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All request/response DTOs for ecm-eforms in one file.
 * Avoids many single-class files for thin data holders.
 *
 * Sections:
 *   FormDefinition DTOs  — create, update, full response, summary
 *   FormSubmission DTOs  — submit request, review request, full response, summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EFormsDtos {

    // ════════════════════════════════════════════════════════════
    // FormDefinition — Requests
    // ════════════════════════════════════════════════════════════

    @Data
    public static class CreateFormDefinitionRequest {
        @NotBlank private String      formKey;
        @NotBlank private String      name;
        private String                description;
        private String                productTypeCode;
        private String                formTypeCode;
        @NotNull  private FormSchema  schema;
        private Map<String, Object>   uiConfig;
        private WorkflowConfig        workflowConfig;
        private DocuSignFormConfig    docuSignConfig;
        private UUID                  documentTemplateId;
        private List<String>          tags;
    }

    @Data
    public static class UpdateFormDefinitionRequest {
        private String                name;
        private String                description;
        private String                productTypeCode;
        private String                formTypeCode;
        private FormSchema            schema;
        private Map<String, Object>   uiConfig;
        private WorkflowConfig        workflowConfig;
        private DocuSignFormConfig    docuSignConfig;
        private UUID                  documentTemplateId;
        private List<String>          tags;
    }

    // ════════════════════════════════════════════════════════════
    // FormDefinition — Responses
    // ════════════════════════════════════════════════════════════

    @Data
    public static class FormDefinitionDto {
        private UUID                  id;
        private String                tenantId;
        private String                formKey;
        private String                name;
        private String                description;
        private String                productTypeCode;
        private String                formTypeCode;
        private Integer               version;
        private String                status;
        private FormSchema            schema;
        private Map<String, Object>   uiConfig;
        private WorkflowConfig        workflowConfig;
        private DocuSignFormConfig    docuSignConfig;
        private UUID                  documentTemplateId;
        private List<String>          tags;
        private String                createdBy;
        private String                updatedBy;
        private OffsetDateTime        createdAt;
        private OffsetDateTime        updatedAt;
        private OffsetDateTime        publishedAt;
    }

    @Data
    public static class FormDefinitionSummary {
        private UUID           id;
        private String         formKey;
        private String         name;
        private String         description;
        private String         productTypeCode;
        private String         formTypeCode;
        private Integer        version;
        private String         status;
        private List<String>   tags;
        private OffsetDateTime createdAt;
        private OffsetDateTime publishedAt;
        private Long           submissionCount; // populated separately
    }

    // ════════════════════════════════════════════════════════════
    // FormSubmission — Requests
    // ════════════════════════════════════════════════════════════

    @Data
    public static class SubmitFormRequest {
        @NotBlank private String             formKey;
        private Integer                      formVersion;      // null = use latest PUBLISHED
        @NotNull  private Map<String, Object> submissionData;
        private boolean                      draft   = false;
        private String                       channel = "WEB";
        private UUID                         existingSubmissionId;
        /** Soft ref to PartyDto.externalId — set from Step 1 of FormFillPage. Optional. */
        private String                       partyExternalId;
    }

    @Data
    public static class ReviewSubmissionRequest {
        @NotBlank private String status;      // IN_REVIEW | APPROVED | REJECTED
        private String           reviewNotes;
        private String           assignedTo;  // optional re-assign
    }

    // ════════════════════════════════════════════════════════════
    // FormSubmission — Responses
    // ════════════════════════════════════════════════════════════

    @Data
    public static class FormSubmissionDto {
        private UUID                 id;
        private String               tenantId;
        private UUID                 formDefinitionId;
        private String               formKey;
        private Integer              formVersion;
        private Map<String, Object>  submissionData;
        private String               status;
        private String               submittedBy;
        private String               submittedByName;
        private OffsetDateTime       submittedAt;
        private String               partyExternalId;     // soft ref to party
        private String               docuSignEnvelopeId;
        private String               docuSignStatus;
        private OffsetDateTime       docuSignSentAt;
        private OffsetDateTime       docuSignCompletedAt;
        private UUID                 signedDocumentId;
        private UUID                 draftDocumentId;
        private String               workflowInstanceId;
        private String               assignedTo;
        private OffsetDateTime       assignedAt;
        private String               reviewNotes;
        private String               reviewedBy;
        private OffsetDateTime       reviewedAt;
        private OffsetDateTime       createdAt;
        private OffsetDateTime       updatedAt;
    }

    @Data
    public static class FormSubmissionSummary {
        private UUID           id;
        private String         formKey;
        private String         formName;       // populated from join
        private Integer        formVersion;
        private String         status;
        private String         submittedByName;
        private OffsetDateTime submittedAt;
        private String         assignedTo;
        private String         docuSignStatus;
        private OffsetDateTime updatedAt;
    }
}