-- ─────────────────────────────────────────────────────────────────────────────
-- ecm-identity  V5__rbac_permissions.sql
--
-- Sprint G-1: RBAC Permission Architecture
--
-- Place at: ecm-identity/src/main/resources/db/migration/V5__rbac_permissions.sql
-- Applies to schema: ecm_core  (controlled by ecm-identity Flyway)
--
-- IMPORTANT: Verify current max Flyway version before applying.
--   SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
-- If current max != 4, rename this file to V{N+1}__rbac_permissions.sql
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Extend roles table — additive only, no existing columns removed
ALTER TABLE ecm_core.roles
    ADD COLUMN IF NOT EXISTS is_system  BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_active  BOOLEAN      NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

-- 2. Module registry — seeded at migration time, not user-editable
CREATE TABLE ecm_core.modules (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

-- 3. Permission registry — one row per fine-grained action
CREATE TABLE ecm_core.permissions (
    id          SERIAL       PRIMARY KEY,
    module_code VARCHAR(50)  NOT NULL REFERENCES ecm_core.modules(code),
    action      VARCHAR(50)  NOT NULL,
    code        VARCHAR(100) NOT NULL UNIQUE,        -- e.g. "documents:read"
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    UNIQUE (module_code, action)
);

-- 4. Role-permission join — admin-managed at runtime
CREATE TABLE ecm_core.role_permissions (
    role_id       INTEGER      NOT NULL REFERENCES ecm_core.roles(id),
    permission_id INTEGER      NOT NULL REFERENCES ecm_core.permissions(id),
    granted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by    VARCHAR(255),
    PRIMARY KEY (role_id, permission_id)
);

-- 5. Capability bundles — UI-layer groupings only, not enforced at runtime
CREATE TABLE ecm_core.capability_bundles (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT true,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE ecm_core.bundle_permissions (
    bundle_id     INTEGER NOT NULL REFERENCES ecm_core.capability_bundles(id),
    permission_id INTEGER NOT NULL REFERENCES ecm_core.permissions(id),
    PRIMARY KEY (bundle_id, permission_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- SEED DATA
-- ─────────────────────────────────────────────────────────────────────────────

-- 6. Seed modules
INSERT INTO ecm_core.modules (code, name, sort_order) VALUES
    ('DOCUMENTS', 'Document Management', 1),
    ('WORKFLOW',  'Workflow & Tasks',     2),
    ('EFORMS',    'Electronic Forms',     3),
    ('ADMIN',     'Administration',       4),
    ('OCR',       'OCR & Scanning',       5),
    ('ARCHIVE',   'Archive & Retention',  6);

-- 7. Seed permissions (24 total)
INSERT INTO ecm_core.permissions (module_code, action, code, description) VALUES
    -- Documents (6)
    ('DOCUMENTS', 'read',    'documents:read',    'View and download documents'),
    ('DOCUMENTS', 'write',   'documents:write',   'Edit document metadata'),
    ('DOCUMENTS', 'upload',  'documents:upload',  'Upload new documents'),
    ('DOCUMENTS', 'delete',  'documents:delete',  'Soft-delete documents'),
    ('DOCUMENTS', 'archive', 'documents:archive', 'Archive and restore documents'),
    ('DOCUMENTS', 'export',  'documents:export',  'Bulk export documents'),
    -- Workflow (6)
    ('WORKFLOW',  'view',    'workflow:view',    'View workflow instances and tasks'),
    ('WORKFLOW',  'claim',   'workflow:claim',   'Claim unassigned tasks'),
    ('WORKFLOW',  'approve', 'workflow:approve', 'Approve workflow tasks'),
    ('WORKFLOW',  'reject',  'workflow:reject',  'Reject workflow tasks'),
    ('WORKFLOW',  'design',  'workflow:design',  'Create and edit workflow templates'),
    ('WORKFLOW',  'admin',   'workflow:admin',   'Manage all workflow instances'),
    -- eForms (4)
    ('EFORMS',    'submit',  'eforms:submit',  'Submit eForms'),
    ('EFORMS',    'review',  'eforms:review',  'Review eForm submissions'),
    ('EFORMS',    'design',  'eforms:design',  'Design eForm templates'),
    ('EFORMS',    'admin',   'eforms:admin',   'Manage all eForm definitions'),
    -- Admin (4)
    ('ADMIN',     'users',     'admin:users',     'Manage users and role assignments'),
    ('ADMIN',     'roles',     'admin:roles',     'Create and configure roles'),
    ('ADMIN',     'configure', 'admin:configure', 'System configuration and settings'),
    ('ADMIN',     'audit',     'admin:audit',     'View audit logs'),
    -- OCR (2)
    ('OCR',       'trigger', 'ocr:trigger', 'Trigger OCR processing'),
    ('OCR',       'view',    'ocr:view',    'View OCR results'),
    -- Archive (2)
    ('ARCHIVE',   'read',    'archive:read',   'View archived documents'),
    ('ARCHIVE',   'manage',  'archive:manage', 'Manage retention policies');

-- 8. Mark existing system roles as is_system = true
UPDATE ecm_core.roles
SET is_system = true
WHERE name IN ('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER', 'ECM_DESIGNER', 'ECM_READONLY');

-- 9. ECM_ADMIN: all 24 permissions
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r, ecm_core.permissions p
WHERE r.name = 'ECM_ADMIN';

-- 10. ECM_BACKOFFICE bundle
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read', 'documents:write', 'documents:upload',
    'workflow:view',  'workflow:claim',   'workflow:approve', 'workflow:reject',
    'eforms:submit',  'eforms:review',
    'ocr:view',       'archive:read'
)
WHERE r.name = 'ECM_BACKOFFICE';

-- 11. ECM_REVIEWER bundle
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read',
    'workflow:view',  'workflow:approve', 'workflow:reject',
    'eforms:submit',  'eforms:review',
    'archive:read'
)
WHERE r.name = 'ECM_REVIEWER';

-- 12. ECM_DESIGNER bundle
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read',
    'workflow:view',   'workflow:design',
    'eforms:submit',   'eforms:design',
    'ocr:view'
)
WHERE r.name = 'ECM_DESIGNER';

-- 13. ECM_READONLY bundle
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN ('documents:read', 'eforms:submit', 'archive:read')
WHERE r.name = 'ECM_READONLY';

-- 14. Seed capability bundles (UI-layer convenience groupings)
INSERT INTO ecm_core.capability_bundles (code, name, description, sort_order) VALUES
    ('DOCUMENT_CONTRIBUTOR', 'Document Contributor',
     'Upload, view, and manage documents', 1),
    ('TASK_PROCESSOR',       'Task Processor',
     'Claim and action workflow tasks', 2),
    ('FORM_REVIEWER',        'Form Reviewer',
     'Review and approve eForm submissions', 3),
    ('DESIGNER',             'Designer',
     'Design workflows and eForms', 4),
    ('COMPLIANCE_REVIEWER',  'Compliance Reviewer',
     'Compliance audit, archive, export', 5);

-- 15. Bundle → permission links (informational only, not enforced at runtime)
-- DOCUMENT_CONTRIBUTOR
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id
FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'DOCUMENT_CONTRIBUTOR'
  AND p.code IN ('documents:read', 'documents:write', 'documents:upload',
                 'workflow:view', 'eforms:submit');

-- TASK_PROCESSOR
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id
FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'TASK_PROCESSOR'
  AND p.code IN ('documents:read',
                 'workflow:view', 'workflow:claim', 'workflow:approve', 'workflow:reject',
                 'eforms:review', 'ocr:view');

-- FORM_REVIEWER
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id
FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'FORM_REVIEWER'
  AND p.code IN ('documents:read',
                 'workflow:view', 'workflow:approve', 'workflow:reject',
                 'eforms:submit', 'eforms:review');

-- DESIGNER
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id
FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'DESIGNER'
  AND p.code IN ('documents:read',
                 'workflow:view', 'workflow:design',
                 'eforms:submit', 'eforms:design',
                 'ocr:view');

-- COMPLIANCE_REVIEWER
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id
FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'COMPLIANCE_REVIEWER'
  AND p.code IN ('documents:read', 'documents:export', 'documents:archive',
                 'workflow:view', 'workflow:approve', 'workflow:reject',
                 'eforms:review',
                 'archive:read', 'archive:manage',
                 'admin:audit');

-- ─────────────────────────────────────────────────────────────────────────────
-- VALIDATION QUERIES (run after migration to verify)
-- ─────────────────────────────────────────────────────────────────────────────
-- SELECT table_name FROM information_schema.tables
--   WHERE table_schema = 'ecm_core'
--   AND table_name IN ('modules','permissions','role_permissions','capability_bundles','bundle_permissions');
-- -- Expected: 5 rows
--
-- SELECT count(*) FROM ecm_core.permissions;
-- -- Expected: 24
--
-- SELECT name, is_system FROM ecm_core.roles WHERE is_system = true;
-- -- Expected: ECM_ADMIN, ECM_BACKOFFICE, ECM_REVIEWER, ECM_DESIGNER, ECM_READONLY
--
-- SELECT count(*) FROM ecm_core.role_permissions rp
--   JOIN ecm_core.roles r ON r.id = rp.role_id WHERE r.name = 'ECM_ADMIN';
-- -- Expected: 24
