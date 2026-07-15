-- ═══════════════════════════════════════════════════════════════════════════════
-- TEST CASES SEED — Run on demand (after seed-test-data.sql)
--
-- Creates 6 cases (one per customer) with auto-populated checklists + timeline.
-- Requires: customers + product_document_types from seed-test-data.sql
--
-- Safe to re-run — cleans up previous seed cases first.
-- ═══════════════════════════════════════════════════════════════════════════════

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- CLEANUP: Remove previous seed cases (reverse dependency order)
-- ─────────────────────────────────────────────────────────────────────────────

DELETE FROM ecm_core.case_timeline_events
WHERE case_id IN (SELECT id FROM ecm_core.cases WHERE external_ref LIKE 'LOAN-2026-001%'
                  OR external_ref LIKE 'COMM-2026-002%' OR external_ref LIKE 'SMB-2026-003%');

DELETE FROM ecm_core.case_override_requests
WHERE case_id IN (SELECT id FROM ecm_core.cases WHERE external_ref LIKE 'LOAN-2026-001%'
                  OR external_ref LIKE 'COMM-2026-002%' OR external_ref LIKE 'SMB-2026-003%');

DELETE FROM ecm_core.case_documents
WHERE case_id IN (SELECT id FROM ecm_core.cases WHERE external_ref LIKE 'LOAN-2026-001%'
                  OR external_ref LIKE 'COMM-2026-002%' OR external_ref LIKE 'SMB-2026-003%');

DELETE FROM ecm_core.cases
WHERE external_ref LIKE 'LOAN-2026-001%' OR external_ref LIKE 'COMM-2026-002%' OR external_ref LIKE 'SMB-2026-003%';

-- Cleanup complete — inserting fresh case data...

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. CASES
--
-- Retail:     Sarah Johnson -> Mortgage, Michael Chen -> Auto Loan
-- Commercial: Apex Industrial -> Credit Facility, Pacific Timber -> Credit Facility
-- SMB:        Greenleaf -> SMB Loan, Metro Dental -> SMB Loan
-- ─────────────────────────────────────────────────────────────────────────────

-- Sarah Johnson -> Mortgage Application
INSERT INTO ecm_core.cases (id, external_ref, party_id, product_id, case_type, status, source_system)
SELECT gen_random_uuid(), 'LOAN-2026-00101', p.id,
       (SELECT id FROM ecm_admin.products WHERE product_code = 'MORTGAGE'),
       'LOAN_ORIGINATION', 'NEW', 'ECM'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-RET-001';

-- Michael Chen -> Auto Loan
INSERT INTO ecm_core.cases (id, external_ref, party_id, product_id, case_type, status, source_system)
SELECT gen_random_uuid(), 'LOAN-2026-00102', p.id,
       (SELECT id FROM ecm_admin.products WHERE product_code = 'AUTO_LOAN'),
       'LOAN_ORIGINATION', 'NEW', 'ECM'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-RET-002';

-- Apex Industrial -> Commercial Credit Facility
INSERT INTO ecm_core.cases (id, external_ref, party_id, product_id, case_type, status, source_system)
SELECT gen_random_uuid(), 'COMM-2026-00201', p.id,
       (SELECT id FROM ecm_admin.products WHERE product_code = 'COMM_CREDIT_FACILITY'),
       'LOAN_ORIGINATION', 'NEW', 'ECM'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-COM-001';

-- Pacific Timber -> Commercial Credit Facility
INSERT INTO ecm_core.cases (id, external_ref, party_id, product_id, case_type, status, source_system)
SELECT gen_random_uuid(), 'COMM-2026-00202', p.id,
       (SELECT id FROM ecm_admin.products WHERE product_code = 'COMM_CREDIT_FACILITY'),
       'LOAN_ORIGINATION', 'NEW', 'ECM'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-COM-002';

-- Greenleaf Landscaping -> SMB Loan
INSERT INTO ecm_core.cases (id, external_ref, party_id, product_id, case_type, status, source_system)
SELECT gen_random_uuid(), 'SMB-2026-00301', p.id,
       (SELECT id FROM ecm_admin.products WHERE product_code = 'SMB_LOAN'),
       'LOAN_ORIGINATION', 'NEW', 'ECM'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-SMB-001';

-- Metro Dental -> SMB Loan
INSERT INTO ecm_core.cases (id, external_ref, party_id, product_id, case_type, status, source_system)
SELECT gen_random_uuid(), 'SMB-2026-00302', p.id,
       (SELECT id FROM ecm_admin.products WHERE product_code = 'SMB_LOAN'),
       'LOAN_ORIGINATION', 'NEW', 'ECM'
FROM ecm_core.parties p WHERE p.external_id = 'CUST-SMB-002';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. AUTO-POPULATE CASE CHECKLISTS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO ecm_core.case_documents (case_id, product_document_type_id, status)
SELECT c.id, pdt.id, 'PENDING'
FROM ecm_core.cases c
JOIN ecm_admin.product_document_types pdt ON pdt.product_id = c.product_id AND pdt.is_active = true
WHERE c.external_ref IN (
    'LOAN-2026-00101', 'LOAN-2026-00102',
    'COMM-2026-00201', 'COMM-2026-00202',
    'SMB-2026-00301',  'SMB-2026-00302'
);


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. TIMELINE EVENTS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO ecm_core.case_timeline_events (case_id, event_type, description, actor)
SELECT c.id, 'CASE_CREATED', 'Case created — ' || c.external_ref, 'seed-script'
FROM ecm_core.cases c
WHERE c.external_ref IN (
    'LOAN-2026-00101', 'LOAN-2026-00102',
    'COMM-2026-00201', 'COMM-2026-00202',
    'SMB-2026-00301',  'SMB-2026-00302'
);

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- VERIFICATION
-- ─────────────────────────────────────────────────────────────────────────────
SELECT '--- CASES ---' AS section;
SELECT c.external_ref, c.status, p.display_name AS customer, pr.product_code,
       (SELECT count(*) FROM ecm_core.case_documents cd WHERE cd.case_id = c.id) AS checklist_items
FROM ecm_core.cases c
JOIN ecm_core.parties p ON p.id = c.party_id
JOIN ecm_admin.products pr ON pr.id = c.product_id
ORDER BY c.external_ref;
