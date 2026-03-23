-- ═══════════════════════════════════════════════════════════════════════════════
-- TEST DATA SEED — Run on demand
--
-- Creates:
--   6 customers  (2 per segment: Retail, Commercial, SMB)
--   Product document checklists for all 5 products
--   6 cases (one per customer, matching their segment's product)
--
-- Safe to re-run (ON CONFLICT DO NOTHING / uses generated UUIDs)
-- ═══════════════════════════════════════════════════════════════════════════════

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- CLEANUP: Remove previous seed data (reverse dependency order)
-- Only deletes data created by 'seed-script' — leaves manually created data intact
-- ─────────────────────────────────────────────────────────────────────────────

-- Enrollments for seeded customers
DELETE FROM ecm_core.party_product_enrollments
WHERE party_id IN (SELECT id FROM ecm_core.parties WHERE created_by = 'seed-script');

-- Customers
DELETE FROM ecm_core.parties WHERE created_by = 'seed-script';

-- Product document types (checklists) — only delete seeded ones
DELETE FROM ecm_admin.product_document_types
WHERE code IN (
    'MORTGAGE_ID', 'MORTGAGE_INCOME', 'MORTGAGE_BANK_STMT', 'MORTGAGE_APPRAISAL', 'MORTGAGE_AGREEMENT',
    'AUTO_DL', 'AUTO_INCOME', 'AUTO_PURCHASE', 'AUTO_INSURANCE',
    'PERSLOAN_ID', 'PERSLOAN_INCOME', 'PERSLOAN_AGREEMENT',
    'COMM_DIR_ID', 'COMM_FINANCIALS', 'COMM_TAX', 'COMM_AML', 'COMM_BOARD_RES', 'COMM_AGREEMENT',
    'SMB_OWNER_ID', 'SMB_FINANCIALS', 'SMB_BANK_STMT', 'SMB_AGREEMENT'
);

-- Cleanup complete — inserting fresh seed data...

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. CUSTOMERS (Parties)
--
-- Segment IDs: 1=RETAIL, 2=COMMERCIAL, 3=SMB
-- ─────────────────────────────────────────────────────────────────────────────

-- Retail customers
INSERT INTO ecm_core.parties (external_id, party_type, segment_id, display_name, short_name, registration_no, created_by)
VALUES
    ('CUST-RET-001', 'RETAIL', 1, 'Sarah Johnson',     'S. Johnson',  NULL,            'seed-script'),
    ('CUST-RET-002', 'RETAIL', 1, 'Michael Chen',      'M. Chen',     NULL,            'seed-script')
ON CONFLICT (external_id) DO NOTHING;

-- Commercial customers
INSERT INTO ecm_core.parties (external_id, party_type, segment_id, display_name, short_name, registration_no, created_by)
VALUES
    ('CUST-COM-001', 'COMMERCIAL', 2, 'Apex Industrial Corp',    'Apex Corp',     'BN-2024-88721',  'seed-script'),
    ('CUST-COM-002', 'COMMERCIAL', 2, 'Pacific Timber Holdings', 'Pacific Timber', 'BN-2023-44510',  'seed-script')
ON CONFLICT (external_id) DO NOTHING;

-- SMB customers
INSERT INTO ecm_core.parties (external_id, party_type, segment_id, display_name, short_name, registration_no, created_by)
VALUES
    ('CUST-SMB-001', 'SMB', 3, 'Greenleaf Landscaping Ltd',  'Greenleaf',     'BN-2025-12345',  'seed-script'),
    ('CUST-SMB-002', 'SMB', 3, 'Metro Dental Clinic Inc',    'Metro Dental',  'BN-2024-67890',  'seed-script')
ON CONFLICT (external_id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 1B. PRODUCT ENROLLMENTS
--
-- Product Line IDs: 1=RETAIL_BANKING, 2=RETAIL_LOANS, 3=RETAIL_INVESTMENT,
--                   4=RETAIL_MUTUAL_FUNDS, 5=COMM_BANKING, 6=COMM_LENDING,
--                   7=SMB_BANKING, 8=SMB_LOANS
-- Product IDs:      1=MORTGAGE, 2=AUTO_LOAN, 3=PERSONAL_LOAN,
--                   4=COMM_CREDIT_FACILITY, 5=SMB_LOAN
-- ─────────────────────────────────────────────────────────────────────────────

-- Sarah Johnson (Retail) — Retail Loans: Mortgage + Personal Loan
INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'RETAIL_LOANS'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'MORTGAGE'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-RET-001'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'MORTGAGE'));

INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'RETAIL_LOANS'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'PERSONAL_LOAN'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-RET-001'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'PERSONAL_LOAN'));

-- Michael Chen (Retail) — Retail Loans: Auto Loan + Personal Loan
INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'RETAIL_LOANS'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'AUTO_LOAN'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-RET-002'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'AUTO_LOAN'));

INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'RETAIL_LOANS'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'PERSONAL_LOAN'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-RET-002'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'PERSONAL_LOAN'));

-- Apex Industrial (Commercial) — Comm Banking (line-level) + Comm Lending: Credit Facility
INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'COMM_LENDING'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'COMM_CREDIT_FACILITY'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-COM-001'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'COMM_CREDIT_FACILITY'));

-- Pacific Timber (Commercial) — Comm Lending: Credit Facility
INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'COMM_LENDING'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'COMM_CREDIT_FACILITY'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-COM-002'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'COMM_CREDIT_FACILITY'));

-- Greenleaf Landscaping (SMB) — SMB Loans: SMB Loan
INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'SMB_LOANS'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'SMB_LOAN'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-SMB-001'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'SMB_LOAN'));

-- Metro Dental (SMB) — SMB Loans: SMB Loan
INSERT INTO ecm_core.party_product_enrollments (party_id, product_line_id, product_id, enrolled_by)
SELECT p.id, (SELECT id FROM ecm_admin.product_lines WHERE code = 'SMB_LOANS'),
       (SELECT id FROM ecm_admin.products WHERE product_code = 'SMB_LOAN'), 'seed-script'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-SMB-002'
AND NOT EXISTS (SELECT 1 FROM ecm_core.party_product_enrollments e
    WHERE e.party_id = p.id AND e.product_id = (SELECT id FROM ecm_admin.products WHERE product_code = 'SMB_LOAN'));


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. PRODUCT DOCUMENT TYPES (Checklists per product)
--
-- Product IDs: 1=MORTGAGE, 2=AUTO_LOAN, 3=PERSONAL_LOAN,
--              4=COMM_CREDIT_FACILITY, 5=SMB_LOAN
-- Category IDs: 1=MORTGAGE, 2=AUTO_LOAN, 3=IDENTITY, 4=FINANCIAL,
--               5=LEGAL, 12=COMPLIANCE
-- ─────────────────────────────────────────────────────────────────────────────

-- MORTGAGE (product_id=1) — 5 document types
INSERT INTO ecm_admin.product_document_types
    (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
VALUES
    (1, 3, 'Government Photo ID',           'MORTGAGE_ID',          'UPLOAD', 'OCR_ONLY',        TRUE,  1),
    (1, 4, 'Proof of Income (T4 / Paystub)','MORTGAGE_INCOME',      'UPLOAD', 'OCR_ONLY',        TRUE,  2),
    (1, 4, 'Bank Statements (3 months)',    'MORTGAGE_BANK_STMT',   'UPLOAD', 'OCR_ONLY',        TRUE,  3),
    (1, 1, 'Property Appraisal Report',     'MORTGAGE_APPRAISAL',   'UPLOAD', 'REVIEW_REQUIRED', TRUE,  4),
    (1, 5, 'Signed Mortgage Agreement',     'MORTGAGE_AGREEMENT',   'UPLOAD', 'REVIEW_REQUIRED', TRUE,  5)
ON CONFLICT (product_id, code) DO NOTHING;

-- AUTO_LOAN (product_id=2) — 4 document types
INSERT INTO ecm_admin.product_document_types
    (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
VALUES
    (2, 3, 'Drivers License',               'AUTO_DL',              'UPLOAD', 'OCR_ONLY',        TRUE,  1),
    (2, 4, 'Proof of Income',               'AUTO_INCOME',          'UPLOAD', 'OCR_ONLY',        TRUE,  2),
    (2, 2, 'Vehicle Purchase Agreement',     'AUTO_PURCHASE',        'UPLOAD', 'REVIEW_REQUIRED', TRUE,  3),
    (2, 5, 'Insurance Confirmation',         'AUTO_INSURANCE',       'UPLOAD', 'OCR_ONLY',        FALSE, 4)
ON CONFLICT (product_id, code) DO NOTHING;

-- PERSONAL_LOAN (product_id=3) — 3 document types
INSERT INTO ecm_admin.product_document_types
    (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
VALUES
    (3, 3, 'Government Photo ID',           'PERSLOAN_ID',          'UPLOAD', 'OCR_ONLY',        TRUE,  1),
    (3, 4, 'Proof of Income',               'PERSLOAN_INCOME',      'UPLOAD', 'OCR_ONLY',        TRUE,  2),
    (3, 5, 'Signed Loan Agreement',         'PERSLOAN_AGREEMENT',   'UPLOAD', 'REVIEW_REQUIRED', TRUE,  3)
ON CONFLICT (product_id, code) DO NOTHING;

-- COMM_CREDIT_FACILITY (product_id=4) — 6 document types
INSERT INTO ecm_admin.product_document_types
    (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
VALUES
    (4, 3,  'Director ID (Authorized Signatory)',   'COMM_DIR_ID',       'UPLOAD', 'OCR_ONLY',        TRUE,  1),
    (4, 4,  'Audited Financial Statements (2 yr)',  'COMM_FINANCIALS',   'UPLOAD', 'REVIEW_REQUIRED', TRUE,  2),
    (4, 4,  'Company Tax Returns (2 yr)',           'COMM_TAX',          'UPLOAD', 'OCR_ONLY',        TRUE,  3),
    (4, 12, 'AML/KYC Compliance Declaration',       'COMM_AML',          'UPLOAD', 'REVIEW_REQUIRED', TRUE,  4),
    (4, 5,  'Board Resolution for Borrowing',       'COMM_BOARD_RES',    'UPLOAD', 'REVIEW_REQUIRED', TRUE,  5),
    (4, 7,  'Signed Facility Agreement',            'COMM_AGREEMENT',    'UPLOAD', 'REVIEW_REQUIRED', TRUE,  6)
ON CONFLICT (product_id, code) DO NOTHING;

-- SMB_LOAN (product_id=5) — 4 document types
INSERT INTO ecm_admin.product_document_types
    (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
VALUES
    (5, 3,  'Owner/Director Photo ID',        'SMB_OWNER_ID',       'UPLOAD', 'OCR_ONLY',        TRUE,  1),
    (5, 4,  'Business Financial Statements',  'SMB_FINANCIALS',     'UPLOAD', 'OCR_ONLY',        TRUE,  2),
    (5, 4,  'Business Bank Statements (6mo)', 'SMB_BANK_STMT',      'UPLOAD', 'OCR_ONLY',        TRUE,  3),
    (5, 5,  'Signed Loan Agreement',          'SMB_AGREEMENT',      'UPLOAD', 'REVIEW_REQUIRED', TRUE,  4)
ON CONFLICT (product_id, code) DO NOTHING;


COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- VERIFICATION
-- ─────────────────────────────────────────────────────────────────────────────
SELECT '--- CUSTOMERS ---' AS section;
SELECT external_id, party_type, display_name FROM ecm_core.parties WHERE created_by = 'seed-script' ORDER BY external_id;

SELECT '--- PRODUCT CHECKLISTS ---' AS section;
SELECT p.product_code, count(pdt.id) AS checklist_items
FROM ecm_admin.products p
JOIN ecm_admin.product_document_types pdt ON pdt.product_id = p.id
GROUP BY p.product_code ORDER BY p.product_code;

SELECT '--- ENROLLMENTS ---' AS section;
SELECT p.external_id, p.display_name, pl.code AS product_line, pr.product_code
FROM ecm_core.party_product_enrollments e
JOIN ecm_core.parties p ON p.id = e.party_id
JOIN ecm_admin.product_lines pl ON pl.id = e.product_line_id
LEFT JOIN ecm_admin.products pr ON pr.id = e.product_id
WHERE e.enrolled_by = 'seed-script'
ORDER BY p.external_id, pl.code;

