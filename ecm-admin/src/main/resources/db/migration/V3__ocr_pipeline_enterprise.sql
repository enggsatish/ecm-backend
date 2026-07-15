-- ============================================================================
-- V3: Enterprise OCR Pipeline — field normalization, extraction templates,
--     confidence scoring, training quality, customer matching
-- ============================================================================

-- ─── Phase 1: Field Normalization ────────────────────────────────────────────
-- Maps raw engine field names to canonical names per category.
-- e.g., Azure "FirstName" → "first_name", "DOB" → "date_of_birth"

CREATE TABLE IF NOT EXISTS ecm_admin.field_mappings (
    id              SERIAL PRIMARY KEY,
    category_code   VARCHAR(50)  NOT NULL,
    raw_name        VARCHAR(100) NOT NULL,
    canonical_name  VARCHAR(100) NOT NULL,
    field_type      VARCHAR(20)  DEFAULT 'STRING',  -- STRING, DATE, CURRENCY, NUMBER, BOOLEAN
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    UNIQUE (category_code, raw_name)
);

COMMENT ON TABLE ecm_admin.field_mappings IS 'Maps raw OCR engine field names to canonical names per category';

-- Seed common mappings for IDENTITY
INSERT INTO ecm_admin.field_mappings (category_code, raw_name, canonical_name, field_type) VALUES
    -- Azure camelCase → snake_case
    ('IDENTITY', 'FirstName', 'first_name', 'STRING'),
    ('IDENTITY', 'First Name', 'first_name', 'STRING'),
    ('IDENTITY', 'fname', 'first_name', 'STRING'),
    ('IDENTITY', 'given_name', 'first_name', 'STRING'),
    ('IDENTITY', 'given_names', 'first_name', 'STRING'),
    ('IDENTITY', 'GivenNames', 'first_name', 'STRING'),
    ('IDENTITY', 'LastName', 'last_name', 'STRING'),
    ('IDENTITY', 'Last Name', 'last_name', 'STRING'),
    ('IDENTITY', 'lname', 'last_name', 'STRING'),
    ('IDENTITY', 'surname', 'last_name', 'STRING'),
    ('IDENTITY', 'Surname', 'last_name', 'STRING'),
    ('IDENTITY', 'family_name', 'last_name', 'STRING'),
    ('IDENTITY', 'MiddleName', 'middle_name', 'STRING'),
    ('IDENTITY', 'Middle Name', 'middle_name', 'STRING'),
    ('IDENTITY', 'DateOfBirth', 'date_of_birth', 'DATE'),
    ('IDENTITY', 'Date of Birth', 'date_of_birth', 'DATE'),
    ('IDENTITY', 'DOB', 'date_of_birth', 'DATE'),
    ('IDENTITY', 'dob', 'date_of_birth', 'DATE'),
    ('IDENTITY', 'birth_date', 'date_of_birth', 'DATE'),
    ('IDENTITY', 'DocumentNumber', 'document_number', 'STRING'),
    ('IDENTITY', 'Document Number', 'document_number', 'STRING'),
    ('IDENTITY', 'operator_license_number', 'document_number', 'STRING'),
    ('IDENTITY', 'operator''s_licence', 'document_number', 'STRING'),
    ('IDENTITY', 'license_number', 'document_number', 'STRING'),
    ('IDENTITY', 'passport_number', 'document_number', 'STRING'),
    ('IDENTITY', 'DateOfExpiration', 'expiry_date', 'DATE'),
    ('IDENTITY', 'Expiry Date', 'expiry_date', 'DATE'),
    ('IDENTITY', 'expiration_date', 'expiry_date', 'DATE'),
    ('IDENTITY', 'license_expiration_date', 'expiry_date', 'DATE'),
    ('IDENTITY', 'CountryRegion', 'nationality', 'STRING'),
    ('IDENTITY', 'country_region', 'nationality', 'STRING'),
    ('IDENTITY', 'Sex', 'sex', 'STRING'),
    ('IDENTITY', 'gender', 'sex', 'STRING'),
    ('IDENTITY', 'Gender', 'sex', 'STRING'),
    ('IDENTITY', 'Address', 'address', 'STRING'),
    ('IDENTITY', 'issue_date', 'issuance_date', 'DATE'),
    ('IDENTITY', 'IssuedDate', 'issuance_date', 'DATE'),
    -- Common mappings for other categories
    ('INVOICE', 'InvoiceTotal', 'invoice_total', 'CURRENCY'),
    ('INVOICE', 'Invoice Total', 'invoice_total', 'CURRENCY'),
    ('INVOICE', 'Total Amount', 'invoice_total', 'CURRENCY'),
    ('INVOICE', 'total_amount', 'invoice_total', 'CURRENCY'),
    ('INVOICE', 'InvoiceDate', 'invoice_date', 'DATE'),
    ('INVOICE', 'Invoice Date', 'invoice_date', 'DATE'),
    ('INVOICE', 'VendorName', 'vendor_name', 'STRING'),
    ('INVOICE', 'Vendor Name', 'vendor_name', 'STRING'),
    ('INVOICE', 'BillTo', 'bill_to', 'STRING'),
    ('INVOICE', 'Bill To', 'bill_to', 'STRING'),
    ('INVOICE', 'InvoiceNumber', 'invoice_number', 'STRING'),
    ('INVOICE', 'Invoice Number', 'invoice_number', 'STRING'),
    ('INVOICE', 'invoice_no', 'invoice_number', 'STRING'),
    ('RECEIPT', 'MerchantName', 'merchant_name', 'STRING'),
    ('RECEIPT', 'Merchant Name', 'merchant_name', 'STRING'),
    ('RECEIPT', 'TransactionDate', 'transaction_date', 'DATE'),
    ('RECEIPT', 'Transaction Date', 'transaction_date', 'DATE'),
    ('RECEIPT', 'Total', 'total', 'CURRENCY'),
    ('RECEIPT', 'Subtotal', 'subtotal', 'CURRENCY'),
    ('FINANCIAL', 'AccountNumber', 'account_number', 'STRING'),
    ('FINANCIAL', 'Account Number', 'account_number', 'STRING'),
    ('FINANCIAL', 'account_no', 'account_number', 'STRING'),
    ('FINANCIAL', 'StatementDate', 'statement_date', 'DATE'),
    ('FINANCIAL', 'Statement Date', 'statement_date', 'DATE'),
    ('TAX', 'TaxYear', 'tax_year', 'STRING'),
    ('TAX', 'Tax Year', 'tax_year', 'STRING'),
    ('TAX', 'Employer', 'employer_name', 'STRING'),
    ('BOARDINGPASS', 'PassengerName', 'passenger_name', 'STRING'),
    ('BOARDINGPASS', 'Passenger Name', 'passenger_name', 'STRING'),
    ('BOARDINGPASS', 'FlightNumber', 'flight_number', 'STRING'),
    ('BOARDINGPASS', 'Flight Number', 'flight_number', 'STRING')
ON CONFLICT (category_code, raw_name) DO NOTHING;

-- ─── Phase 3: Extraction Templates ──────────────────────────────────────────
-- DB-driven field definitions per category. Replaces hardcoded CATEGORY_FIELDS map.
-- Drives: prompt generation, field validation, confidence scoring.

CREATE TABLE IF NOT EXISTS ecm_admin.extraction_templates (
    id              SERIAL PRIMARY KEY,
    category_code   VARCHAR(50)  NOT NULL,
    field_name      VARCHAR(100) NOT NULL,
    field_type      VARCHAR(20)  DEFAULT 'STRING',  -- STRING, DATE, CURRENCY, NUMBER, BOOLEAN
    required        BOOLEAN      DEFAULT FALSE,
    display_order   INTEGER      DEFAULT 0,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW(),
    UNIQUE (category_code, field_name)
);

COMMENT ON TABLE ecm_admin.extraction_templates IS 'Expected fields per document category — drives prompt generation, validation, and confidence scoring';

-- Seed from current CATEGORY_FIELDS map
INSERT INTO ecm_admin.extraction_templates (category_code, field_name, field_type, required, display_order, description) VALUES
    -- IDENTITY
    ('IDENTITY', 'full_name',        'STRING',  FALSE, 1,  'Full name (synthesized from parts)'),
    ('IDENTITY', 'first_name',       'STRING',  TRUE,  2,  'First/given name(s) — may include middle names'),
    ('IDENTITY', 'middle_name',      'STRING',  FALSE, 3,  'Middle name(s)'),
    ('IDENTITY', 'last_name',        'STRING',  TRUE,  4,  'Family name / surname'),
    ('IDENTITY', 'document_number',  'STRING',  TRUE,  5,  'License / passport / ID number'),
    ('IDENTITY', 'date_of_birth',    'DATE',    TRUE,  6,  'Date of birth'),
    ('IDENTITY', 'expiry_date',      'DATE',    FALSE, 7,  'Document expiry date'),
    ('IDENTITY', 'issuance_date',    'DATE',    FALSE, 8,  'Document issue date'),
    ('IDENTITY', 'nationality',      'STRING',  FALSE, 9,  'Country of citizenship'),
    ('IDENTITY', 'sex',              'STRING',  FALSE, 10, 'Gender / sex'),
    ('IDENTITY', 'address',          'STRING',  FALSE, 11, 'Residential address'),
    -- BOARDINGPASS
    ('BOARDINGPASS', 'passenger_name',    'STRING',  TRUE,  1, 'Passenger full name'),
    ('BOARDINGPASS', 'flight_number',     'STRING',  TRUE,  2, 'Flight number'),
    ('BOARDINGPASS', 'departure_date',    'DATE',    TRUE,  3, 'Departure date'),
    ('BOARDINGPASS', 'departure_airport', 'STRING',  FALSE, 4, 'Departure airport code'),
    ('BOARDINGPASS', 'arrival_airport',   'STRING',  FALSE, 5, 'Arrival airport code'),
    ('BOARDINGPASS', 'seat_number',       'STRING',  FALSE, 6, 'Seat assignment'),
    ('BOARDINGPASS', 'gate',              'STRING',  FALSE, 7, 'Boarding gate'),
    ('BOARDINGPASS', 'boarding_time',     'STRING',  FALSE, 8, 'Boarding time'),
    -- INVOICE
    ('INVOICE', 'invoice_number',  'STRING',   TRUE,  1, 'Invoice reference number'),
    ('INVOICE', 'invoice_date',    'DATE',     TRUE,  2, 'Invoice date'),
    ('INVOICE', 'vendor_name',     'STRING',   TRUE,  3, 'Vendor / supplier name'),
    ('INVOICE', 'bill_to',         'STRING',   FALSE, 4, 'Bill-to name / company'),
    ('INVOICE', 'amount_due',      'CURRENCY', FALSE, 5, 'Amount due'),
    ('INVOICE', 'invoice_total',   'CURRENCY', TRUE,  6, 'Total invoice amount'),
    ('INVOICE', 'payment_terms',   'STRING',   FALSE, 7, 'Payment terms'),
    -- RECEIPT
    ('RECEIPT', 'merchant_name',    'STRING',   TRUE,  1, 'Merchant / store name'),
    ('RECEIPT', 'transaction_date', 'DATE',     TRUE,  2, 'Transaction date'),
    ('RECEIPT', 'total',            'CURRENCY', TRUE,  3, 'Total amount'),
    ('RECEIPT', 'subtotal',         'CURRENCY', FALSE, 4, 'Subtotal before tax'),
    ('RECEIPT', 'payment_method',   'STRING',   FALSE, 5, 'Payment method'),
    -- MORTGAGE
    ('MORTGAGE', 'borrower_name',    'STRING',   TRUE,  1, 'Borrower full name'),
    ('MORTGAGE', 'loan_amount',      'CURRENCY', TRUE,  2, 'Loan principal amount'),
    ('MORTGAGE', 'interest_rate',    'STRING',   TRUE,  3, 'Interest rate'),
    ('MORTGAGE', 'property_address', 'STRING',   TRUE,  4, 'Property address'),
    ('MORTGAGE', 'loan_term',        'STRING',   FALSE, 5, 'Loan term (years)'),
    ('MORTGAGE', 'lender_name',      'STRING',   FALSE, 6, 'Lender / bank name'),
    -- FINANCIAL
    ('FINANCIAL', 'account_number',   'STRING',   TRUE,  1, 'Account number'),
    ('FINANCIAL', 'account_holder',   'STRING',   FALSE, 2, 'Account holder name'),
    ('FINANCIAL', 'statement_date',   'DATE',     TRUE,  3, 'Statement date'),
    ('FINANCIAL', 'opening_balance',  'CURRENCY', FALSE, 4, 'Opening balance'),
    ('FINANCIAL', 'closing_balance',  'CURRENCY', FALSE, 5, 'Closing balance'),
    -- LEGAL
    ('LEGAL', 'party_names',      'STRING', TRUE,  1, 'Names of parties'),
    ('LEGAL', 'agreement_date',   'DATE',   TRUE,  2, 'Agreement / execution date'),
    ('LEGAL', 'effective_date',   'DATE',   FALSE, 3, 'Effective date'),
    ('LEGAL', 'document_title',   'STRING', FALSE, 4, 'Document title'),
    ('LEGAL', 'jurisdiction',     'STRING', FALSE, 5, 'Legal jurisdiction'),
    -- COMPLIANCE
    ('COMPLIANCE', 'subject_name',       'STRING', TRUE,  1, 'Subject of compliance check'),
    ('COMPLIANCE', 'verification_date',  'DATE',   TRUE,  2, 'Date of verification'),
    ('COMPLIANCE', 'risk_level',         'STRING', FALSE, 3, 'Risk assessment level'),
    ('COMPLIANCE', 'verifier',           'STRING', FALSE, 4, 'Verifier name / organization'),
    ('COMPLIANCE', 'compliance_type',    'STRING', FALSE, 5, 'Type of compliance check'),
    -- RESUME
    ('RESUME', 'candidate_name',    'STRING', TRUE,  1, 'Candidate full name'),
    ('RESUME', 'email',             'STRING', FALSE, 2, 'Email address'),
    ('RESUME', 'phone',             'STRING', FALSE, 3, 'Phone number'),
    ('RESUME', 'current_role',      'STRING', FALSE, 4, 'Current job title'),
    ('RESUME', 'years_experience',  'STRING', FALSE, 5, 'Years of experience'),
    -- TAX
    ('TAX', 'tax_year',               'STRING',   TRUE,  1, 'Tax year'),
    ('TAX', 'employer_name',          'STRING',   TRUE,  2, 'Employer name'),
    ('TAX', 'wages',                  'CURRENCY', TRUE,  3, 'Total wages'),
    ('TAX', 'federal_tax_withheld',   'CURRENCY', FALSE, 4, 'Federal tax withheld'),
    ('TAX', 'taxpayer_name',          'STRING',   FALSE, 5, 'Taxpayer name')
ON CONFLICT (category_code, field_name) DO NOTHING;


-- ─── Phase 2: Confidence Configuration ──────────────────────────────────────
-- Per-category confidence thresholds and scoring weights.

CREATE TABLE IF NOT EXISTS ecm_admin.category_confidence_config (
    id                      SERIAL PRIMARY KEY,
    category_code           VARCHAR(50) NOT NULL UNIQUE,
    auto_accept_threshold   DECIMAL(5,2) DEFAULT 85.00,   -- >= this → ACTIVE (no review)
    review_threshold        DECIMAL(5,2) DEFAULT 50.00,   -- >= this → NEEDS_REVIEW
    reject_threshold        DECIMAL(5,2) DEFAULT 20.00,   -- < this → UNCLASSIFIED
    -- Scoring weights (must sum to 1.0)
    weight_llm_confidence   DECIMAL(3,2) DEFAULT 0.30,
    weight_field_match      DECIMAL(3,2) DEFAULT 0.35,
    weight_keyword_score    DECIMAL(3,2) DEFAULT 0.20,
    weight_training_sim     DECIMAL(3,2) DEFAULT 0.15,
    created_at              TIMESTAMPTZ  DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  DEFAULT NOW()
);

COMMENT ON TABLE ecm_admin.category_confidence_config IS 'Per-category confidence thresholds and scoring weights for composite classification';

-- Seed default config for all known categories
INSERT INTO ecm_admin.category_confidence_config (category_code) VALUES
    ('IDENTITY'), ('BOARDINGPASS'), ('INVOICE'), ('RECEIPT'),
    ('MORTGAGE'), ('FINANCIAL'), ('LEGAL'), ('COMPLIANCE'),
    ('RESUME'), ('TAX')
ON CONFLICT (category_code) DO NOTHING;

-- Add classification_details column to documents for confidence breakdown
ALTER TABLE ecm_core.documents
    ADD COLUMN IF NOT EXISTS classification_details JSONB;

COMMENT ON COLUMN ecm_core.documents.classification_details IS 'Confidence breakdown: llm_confidence, field_match, keyword_score, engine, matched_fields, etc.';


-- ─── Phase 4: Training Data Quality ─────────────────────────────────────────
-- Replace glm_ocr_examples with lifecycle-managed training data.

CREATE TABLE IF NOT EXISTS ecm_admin.training_examples (
    id               BIGSERIAL PRIMARY KEY,
    category_code    VARCHAR(50)  NOT NULL,
    source           VARCHAR(20)  NOT NULL,     -- AZURE, MANUAL, REGION, CORRECTION, IMPORT
    status           VARCHAR(20)  DEFAULT 'CANDIDATE',  -- CANDIDATE, VERIFIED, ACTIVE, RETIRED
    expected_fields  JSONB        NOT NULL,     -- the "correct" extracted fields
    document_hash    VARCHAR(64),               -- SHA-256 for dedup
    sample_document_id UUID,                    -- reference to original document (nullable)
    accuracy_score   DECIMAL(5,2),              -- rolling accuracy when used as few-shot
    times_used       INTEGER      DEFAULT 0,    -- how many times injected into prompts
    times_correct    INTEGER      DEFAULT 0,    -- how many times downstream matched
    verified_by      VARCHAR(255),
    verified_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  DEFAULT NOW(),
    created_by       VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_training_examples_category
    ON ecm_admin.training_examples (category_code, status);
CREATE INDEX IF NOT EXISTS idx_training_examples_hash
    ON ecm_admin.training_examples (document_hash);

COMMENT ON TABLE ecm_admin.training_examples IS 'Lifecycle-managed OCR training examples with accuracy tracking';

-- Migrate existing glm_ocr_examples → training_examples
INSERT INTO ecm_admin.training_examples (category_code, source, status, expected_fields, document_hash, created_at, created_by)
SELECT category_code, source, 'ACTIVE', expected_output, document_hash, created_at, created_by
FROM ecm_admin.glm_ocr_examples
ON CONFLICT DO NOTHING;


-- ─── Phase 5: Customer Matching (pg_trgm for fuzzy search) ──────────────────

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Trigram index for fuzzy name matching on parties
CREATE INDEX IF NOT EXISTS idx_parties_name_trgm
    ON ecm_core.parties USING gin (display_name gin_trgm_ops);

-- Also useful: index on external_id for exact matching
CREATE INDEX IF NOT EXISTS idx_parties_external_id
    ON ecm_core.parties (external_id) WHERE external_id IS NOT NULL;
