package com.ecm.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class CaseDto {

    // ── Response DTO ──────────────────────────────────────────────────────────

    public record CaseResponse(
            UUID   id,
            String externalRef,
            UUID   partyId,
            String partyDisplayName,
            String partyExternalId,
            Integer productId,
            String productName,
            String caseType,
            String status,
            String assignedTo,
            String assignedToName,
            String sourceSystem,
            String sourceRef,
            String processInstanceId,
            Object metadata,           // JSONB — contains notes array
            OffsetDateTime openedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            List<ChecklistItem> checklist
    ) {}

    public record ChecklistItem(
            Integer id,
            Integer productDocumentTypeId,
            String documentTypeName,
            String documentTypeCode,
            String sourceType,         // EFORM | UPLOAD
            Boolean isRequired,
            Integer categoryId,        // for OCR template selection on upload
            UUID formDefinitionId,     // for EFORM type
            String formKey,            // for EFORM type — navigate to /eforms/fill/{formKey}
            UUID documentId,           // null until uploaded
            String documentName,       // null until uploaded
            String status              // PENDING | UPLOADED | UNDER_REVIEW | APPROVED | REJECTED | WAIVED
    ) {}

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public record CreateCaseRequest(
            @NotNull UUID partyId,
            @NotNull Integer productId,
            String caseType,           // LOAN_ORIGINATION, ACCOUNT_OPENING, etc.
            String externalRef,        // LOS reference (optional)
            String sourceSystem,       // ECM | LOS | ONLINE_BANKING
            String sourceRef,          // originating system's reference
            String assignedTo,         // primary FA (Okta subject)
            String assignedToName
    ) {}

    public record UpdateCaseStatusRequest(
            String status,
            String comment
    ) {}

    public record AddNoteRequest(
            String note
    ) {}

    public record CaseNote(
            String note,
            String author,
            String timestamp
    ) {}

    public record UploadChecklistDocumentRequest(
            @NotNull Integer checklistItemId,
            @NotNull UUID documentId
    ) {}

    public record WaiveChecklistItemRequest(
            String reason
    ) {}
}
