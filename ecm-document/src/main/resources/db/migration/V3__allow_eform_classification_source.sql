-- Adds EFORM as a valid classification_source — set on documents promoted from a
-- FormSubmission (DocumentPromotionClient), where category/fields are already
-- known and the OCR pipeline skips classification. See OcrPipelineService.
ALTER TABLE ecm_core.documents
    DROP CONSTRAINT IF EXISTS ck_classification_source;

ALTER TABLE ecm_core.documents
    ADD CONSTRAINT ck_classification_source
    CHECK (classification_source IS NULL OR classification_source IN
        ('MANUAL', 'AUTO_CLASSIFIED', 'AUTO_CLASSIFIED_VERIFIED', 'MANUAL_VERIFIED',
         'QR_CODE', 'MIGRATION', 'BATCH', 'VERIFIED', 'EFORM'));
