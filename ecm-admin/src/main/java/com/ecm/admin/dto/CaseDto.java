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
            Boolean returnedFromReview,
            String assignedTo,
            String assignedToName,
            String assignedToGroup,
            String claimedBy,
            String claimedByName,
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
            String status,             // PENDING | UPLOADED | UNDER_REVIEW | APPROVED | REJECTED | WAIVED
            // workflow tracking
            String workflowInstanceId,
            String workflowStatus,     // ACTIVE | COMPLETED | TERMINATED | SUSPENDED
            String currentTaskName,
            String currentTaskAssignee,
            // override tracking
            String overrideStatus,     // PENDING | APPROVED | DENIED
            // verification
            Boolean isVerified,
            String verifiedBy,
            OffsetDateTime verifiedAt
    ) {}

    /** Checklist items grouped by document category — for Customer 360's per-case view. */
    public record CategoryGroup(
            Integer categoryId,     // null group = uncategorized
            String categoryName,
            List<ChecklistItem> items
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

    // ── Override DTOs ──────────────────────────────────────────────────────────

    public record OverrideRequest(
            String reason
    ) {}

    public record ReviewOverrideRequest(
            String decision,           // APPROVED | DENIED
            String reason
    ) {}

    public record AdminBypassRequest(
            String reason
    ) {}

    public record OverrideRequestResponse(
            Integer id,
            UUID caseId,
            Integer checklistItemId,
            String itemName,
            String reason,
            String status,
            String requestedBy,
            OffsetDateTime requestedAt,
            String reviewedBy,
            String reviewReason,
            OffsetDateTime reviewedAt
    ) {}

    // ── Timeline DTO ──────────────────────────────────────────────────────────

    // ── Assignment DTO ──────────────────────────────────────────────────────

    public record AssignCaseRequest(
            String assignTo,           // email/subject (person) — mutually exclusive with assignToGroup
            String assignToName,       // display name
            String assignToGroup,      // role name (group) — mutually exclusive with assignTo
            String comment
    ) {}

    public record ClaimCaseRequest(
            String comment
    ) {}

    // ── Verification DTO ─────────────────────────────────────────────────────

    public record VerifyItemsRequest(
            List<Integer> verifiedItemIds,   // IDs of items checked as verified
            String assignToGroup,            // optional: assign for review after saving
            String assignTo,                 // optional: assign to person for review
            String assignToName
    ) {}

    // ── Request Additional Docs ──────────────────────────────────────────────

    public record RequestAdditionalDocsRequest(
            List<Integer> categoryIds,       // document categories to add as new checklist items
            String comment,                  // reason / what's needed
            String reassignTo,               // person to reassign case to
            String reassignToName
    ) {}

    // ── External Participants ─────────────────────────────────────────────────

    public record AddParticipantRequest(
            String name,
            String email,
            String organization,
            String role,           // LAWYER | APPRAISER | NOTARY | TITLE_COMPANY | OTHER
            String phone
    ) {}

    public record ParticipantResponse(
            Integer id,
            UUID caseId,
            String name,
            String email,
            String organization,
            String role,
            String phone,
            UUID inviteToken,
            OffsetDateTime tokenExpiresAt,
            OffsetDateTime lastAccessedAt,
            String invitedBy,
            Boolean isActive,
            OffsetDateTime createdAt
    ) {}

    public record ShareDocumentsRequest(
            Integer participantId,
            List<Integer> caseDocumentIds
    ) {}

    public record OtpVerifyRequest(
            String email,
            String otp
    ) {}

    public record ExternalCaseView(
            UUID caseId,
            String productName,
            String customerName,
            String caseStatus,
            String participantName,
            String participantRole,
            List<SharedDocument> sharedDocuments,
            List<ExternalUploadDto> uploads
    ) {}

    public record SharedDocument(
            Integer caseDocumentId,
            String documentName,
            String documentTypeName,
            String status,
            UUID documentId          // for download
    ) {}

    public record ExternalUploadDto(
            Integer id,
            String originalFilename,
            Long fileSizeBytes,
            String description,
            OffsetDateTime uploadedAt,
            UUID documentId,
            String participantName,
            String participantRole
    ) {}

    // ── Timeline DTO ──────────────────────────────────────────────────────────

    public record CaseTimelineEvent(
            String eventType,
            String description,
            String detail,
            String actor,
            OffsetDateTime timestamp
    ) {}
}
