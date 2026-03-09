-- V6__add_party_external_id_to_documents.sql
-- Sprint-D fix: party_external_id soft reference column.
--
-- This column was referenced by EcmTaskService.enrichTask (partyExternalId process variable)
-- but never existed on the documents table. It is a soft reference (no FK constraint)
-- to ecm_core.parties.external_id, which comes from an external system (CRM, LOS, etc.).
--
-- Also adds extracted_fields jsonb if not already present on the table.
-- (Some deployments may have this from the OCR sprint; the IF NOT EXISTS guard makes
-- this migration safe to run in both cases.)

ALTER TABLE ecm_core.documents
    ADD COLUMN IF NOT EXISTS party_external_id VARCHAR(100);

-- Index for lookups in the task queue enrichment path (EcmTaskService.enrichTask)
CREATE INDEX IF NOT EXISTS idx_documents_party_external_id
    ON ecm_core.documents(party_external_id)
    WHERE party_external_id IS NOT NULL;

-- extracted_fields: added here in case earlier sprint migrations did not include it.
-- The column holds structured OCR output as JSONB (boarding pass fields, invoice line items, etc.)
ALTER TABLE ecm_core.documents
    ADD COLUMN IF NOT EXISTS extracted_fields JSONB;

COMMENT ON COLUMN ecm_core.documents.party_external_id
    IS 'Soft reference to ecm_core.parties.external_id. No FK — party may live in external system.';

COMMENT ON COLUMN ecm_core.documents.extracted_fields
    IS 'Structured fields extracted by OCR engine (JSONB). Boarding pass: passenger, flight, etc.';
