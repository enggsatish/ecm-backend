-- V2__add_party_to_form_submissions.sql
--
-- Adds party_external_id to ecm_forms.form_submissions so the submitter's
-- chosen party (selected in Step 1 of FormFillPage) is stored with the submission.
--
-- Why a soft reference (VARCHAR) and not a FK to ecm_core.parties?
--   1. ecm-eforms must never take a hard dependency on ecm-identity's schema.
--   2. The party may originate from an external CRM / LOS — external_id is the
--      stable cross-system identifier, not the internal UUID.
--   3. Matches the same pattern used in ecm_core.documents.party_external_id
--      (V6__add_party_external_id_to_documents.sql).
--
-- The value stored here is PartyDto.externalId (a string like "CUST-00042").
-- WorkflowCompletedListener copies it into ecm_core.documents.party_external_id
-- when promoting an approved submission to a document record.

ALTER TABLE ecm_forms.form_submissions
    ADD COLUMN IF NOT EXISTS party_external_id VARCHAR(100);

COMMENT ON COLUMN ecm_forms.form_submissions.party_external_id
    IS 'Soft reference to ecm_core.parties.external_id. No FK. '
       'Set from SubmitFormRequest.partyExternalId at submission time.';

-- Index — used when backoffice searches submissions by customer
CREATE INDEX IF NOT EXISTS idx_form_submissions_party_external_id
    ON ecm_forms.form_submissions(party_external_id)
    WHERE party_external_id IS NOT NULL;