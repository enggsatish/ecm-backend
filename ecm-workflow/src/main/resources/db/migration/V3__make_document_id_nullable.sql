
SET search_path TO ecm_workflow;

-- Allow NULL document_id for form-triggered workflow instances.
-- Document-triggered instances always supply a UUID; form-triggered ones
-- don't have a document until approval creates one.
ALTER TABLE workflow_instance_records
    ALTER COLUMN document_id DROP NOT NULL;

-- Store the form submission UUID for traceability and for WorkflowCompletedListener
-- in ecm-eforms to find the FormSubmission when the process ends.
ALTER TABLE workflow_instance_records
    ADD COLUMN IF NOT EXISTS submission_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_wir_submission_id
    ON workflow_instance_records (submission_id)
    WHERE submission_id IS NOT NULL;

-- Useful for filtering form vs document workflows in admin views
CREATE INDEX IF NOT EXISTS idx_wir_document_id
    ON workflow_instance_records (document_id)
    WHERE document_id IS NOT NULL;
