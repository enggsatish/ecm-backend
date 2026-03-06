package com.ecm.eforms.model.schema;

import lombok.Data;
import java.util.List;

/**
 * DocuSign signing ceremony configuration stored as JSONB in
 * form_definitions.docusign_config.
 *
 * When requiresSignature is true, FormSubmissionService calls
 * DocuSignService.createEnvelope() after the form is submitted.
 */
@Data
public class DocuSignFormConfig {
    private boolean       requiresSignature  = false;
    private List<Signatory> signatories;
    private String        emailSubject       = "Please sign this document";
    private String        emailBody;
    private int           expiryDays         = 30;
    private boolean       allowDecline       = false;
    private boolean       allowReassign      = false;

    @Data
    public static class Signatory {
        private String  role;               // e.g. "Applicant", "Co-Applicant"
        private int     routingOrder = 1;   // signing sequence
        private String  nameFromField;      // submission_data key for signer name
        private String  emailFromField;     // submission_data key for signer email
        private String  staticEmail;        // used when emailFromField is null
        private String  staticName;
    }
}
