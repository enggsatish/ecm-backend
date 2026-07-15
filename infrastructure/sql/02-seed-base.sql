-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM Platform — Base Seed Data
--
-- Contains: roles, departments, modules, permissions, capability bundles,
--           role->permission grants, segments, product lines, categories,
--           products, OCR templates, tenant config, integration configs,
--           workflow definition configs, email templates, label definitions
--
-- Run AFTER init.sql (schema must exist first).
-- Idempotent: uses ON CONFLICT DO NOTHING throughout.
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Roles
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.roles (name, description, is_system) VALUES
    ('ECM_SUPER_ADMIN','System-level super administrator',                     TRUE),
    ('ECM_ADMIN',      'Full system administration access',                    TRUE),
    ('ECM_DESIGNER',   'Can create and publish eForms and workflow templates', TRUE),
    ('ECM_BACKOFFICE', 'Standard back-office document and workflow access',    TRUE),
    ('ECM_REVIEWER',   'Can review and approve workflow tasks',                TRUE),
    ('ECM_READONLY',   'Read-only access to assigned departments',             TRUE)
ON CONFLICT (name) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Super Admin User seed (dev/local only)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.users (email, display_name, is_active)
VALUES ('ecm.superadmin@dev.local', 'ECM Super Admin', TRUE)
ON CONFLICT (email) DO NOTHING;

INSERT INTO ecm_core.user_roles (user_id, role_id)
SELECT u.id, r.id
FROM ecm_core.users u
CROSS JOIN ecm_core.roles r
WHERE u.email = 'ecm.superadmin@dev.local'
  AND r.name = 'ECM_SUPER_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM ecm_core.user_roles ur
    WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Departments
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.departments (name, code) VALUES
    ('Head Office',      'HQ'),
    ('Operations',       'OPS'),
    ('Underwriting',     'UW'),
    ('Back Office',      'BO'),
    ('Document Control', 'DC'),
    ('Finance',          'FIN'),
    ('Human Resources',  'HR'),
    ('Legal',            'LEG'),
    ('IT',               'IT')
ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Email Templates
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.email_templates (template_key, name, subject_template, body_template) VALUES
(
    'OTP_VERIFICATION',
    'OTP Verification Code',
    'ECM — Your Verification Code',
    '<div style="font-family:sans-serif;max-width:400px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Verification Code</h2>
<p style="font-size:32px;font-weight:bold;letter-spacing:8px;color:#111;padding:16px 0;text-align:center;background:#f3f4f6;border-radius:8px">{{otp}}</p>
<p style="color:#6b7280;font-size:14px">This code expires in 10 minutes.</p>
<p style="color:#9ca3af;font-size:12px">If you did not request this code, please ignore this email.</p>
</div>'
),
(
    'PARTICIPANT_INVITE',
    'External Participant Invitation',
    'ECM — You''ve been added to a case',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Hello {{name}},</h2>
<p>You have been added as a <strong>{{role}}</strong> on a case in the ECM platform.</p>
<p>Click the button below to access the case documents:</p>
<p style="text-align:center;padding:16px 0">
<a href="{{inviteLink}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Access Case Portal</a></p>
<p style="color:#6b7280;font-size:14px">You will be asked to verify your email with a one-time code.</p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'USER_INVITE',
    'User Platform Invitation',
    'ECM — You''ve been invited to the platform',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Welcome to ECM Platform</h2>
<p>Hello {{displayName}},</p>
<p>You have been invited to the ECM Platform as <strong>{{role}}</strong>.</p>
<p>Click the button below to sign in:</p>
<p style="text-align:center;padding:16px 0">
<a href="{{signInLink}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Sign In to ECM</a></p>
<p style="color:#6b7280;font-size:14px">Use your organisation credentials to sign in.</p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'TASK_ASSIGNED',
    'Workflow Task Assigned',
    'ECM — New review task: {{taskName}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">New Review Task</h2>
<p>A new task has been assigned to your group: <strong>{{candidateGroup}}</strong></p>
<p><strong>Task:</strong> {{taskName}}</p>
<p><strong>Document:</strong> {{documentName}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/review/documents" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Open Review Queue</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'CASE_STATUS_CHANGED',
    'Case Status Update',
    'ECM — Case {{caseRef}} status changed to {{status}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Case Status Update</h2>
<p>Case <strong>{{caseRef}}</strong> for customer <strong>{{customerName}}</strong> has been updated.</p>
<p><strong>New Status:</strong> {{status}}</p>
{{#reason}}<p><strong>Reason:</strong> {{reason}}</p>{{/reason}}
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/cases/{{caseId}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Case</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'CASE_ASSIGNED',
    'Case Assignment Notification',
    'ECM — Case {{caseRef}} assigned to you',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Case Assigned</h2>
<p>Case <strong>{{caseRef}}</strong> for customer <strong>{{customerName}}</strong> has been assigned to you.</p>
<p><strong>Assigned by:</strong> {{assignedBy}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/cases/{{caseId}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Case</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'DOCUMENT_CLASSIFIED',
    'Document Classified Notification',
    'ECM — Document classified as {{categoryName}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Document Classified</h2>
<p>Your document <strong>{{documentName}}</strong> has been classified.</p>
<p><strong>Category:</strong> {{categoryName}}</p>
<p><strong>Customer:</strong> {{customerName}}</p>
<p><strong>Method:</strong> {{classificationSource}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/documents" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Documents</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'BATCH_COMPLETED',
    'Batch Job Completed',
    'ECM — Batch complete: {{batchName}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Batch Job Complete</h2>
<p>Your batch job <strong>{{batchName}}</strong> has finished processing.</p>
<table style="width:100%;border-collapse:collapse;margin:16px 0">
<tr><td style="padding:8px;border-bottom:1px solid #e5e7eb;color:#6b7280">Total Items</td><td style="padding:8px;border-bottom:1px solid #e5e7eb;font-weight:bold">{{totalCount}}</td></tr>
<tr><td style="padding:8px;border-bottom:1px solid #e5e7eb;color:#6b7280">Succeeded</td><td style="padding:8px;border-bottom:1px solid #e5e7eb;font-weight:bold;color:#059669">{{successCount}}</td></tr>
<tr><td style="padding:8px;color:#6b7280">Failed</td><td style="padding:8px;font-weight:bold;color:#dc2626">{{failedCount}}</td></tr>
</table>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/batch" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Batch Jobs</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'BATCH_FAILURE',
    'Batch Job Failure',
    'ECM — Batch failed: {{batchName}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#dc2626">Batch Job Failed</h2>
<p>Your batch job <strong>{{batchName}}</strong> encountered errors.</p>
<p><strong>Failed items:</strong> {{failedCount}}</p>
<p><strong>Error:</strong> {{errorSummary}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/batch" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Batch Jobs</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'CLASSIFICATION_STALE',
    'Stale Classification Alert',
    'ECM — {{count}} documents awaiting classification',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#d97706">Documents Awaiting Classification</h2>
<p><strong>{{count}}</strong> document(s) have been unclassified for <strong>{{oldestAge}}</strong>.</p>
<p>Please review and classify these documents to ensure timely processing.</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/review/classification" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Open Classification Queue</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'FORM_APPROVED',
    'Form Submission Approved',
    'ECM — Your form submission has been approved',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#059669">Form Approved</h2>
<p>Your form submission has been <strong>approved</strong>.</p>
<p><strong>Decision:</strong> {{decision}}</p>
<p><strong>Comments:</strong> {{comment}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/eforms/submissions/mine" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Submissions</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'FORM_REJECTED',
    'Form Submission Rejected',
    'ECM — Your form submission has been reviewed',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#dc2626">Form Reviewed</h2>
<p>Your form submission has been reviewed.</p>
<p><strong>Decision:</strong> {{decision}}</p>
<p><strong>Comments:</strong> {{comment}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/eforms/submissions/mine" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Submissions</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'DIGEST',
    'Notification Digest',
    '[ECM] You have {{count}} new notifications',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Notification Digest</h2>
<p>You have <strong>{{count}}</strong> new notification(s):</p>
{{items}}
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Open ECM Platform</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'DOCUSIGN_SIGNING_REQUEST',
    'DocuSign Signing Request',
    'Please sign: {{formName}}',
    -- Plain text, not HTML — this feeds DocuSign''s own emailBlurb field,
    -- which DocuSign renders inside its own transactional email template.
    -- (Unlike the other templates above, which ecm-notification sends as HTML itself.)
    'Hello {{signerName}}, please review and sign {{formName}}. You will receive a separate email directly from DocuSign with a link to complete the signing. This request was sent by {{senderName}} via the ECM platform.'
)
ON CONFLICT (template_key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. RBAC Modules
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.modules (code, name, sort_order) VALUES
    ('DOCUMENTS', 'Document Management', 1),
    ('WORKFLOW',  'Workflow & Tasks',     2),
    ('EFORMS',    'Electronic Forms',     3),
    ('CASE',      'Case Management',      4),
    ('ADMIN',     'Administration',       5),
    ('OCR',       'OCR & Scanning',       6),
    ('ARCHIVE',   'Archive & Retention',  7),
    ('BATCH',     'Batch Processing',     8);

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. RBAC Permissions (38 existing + 5 BATCH)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.permissions (module_code, action, code, description) VALUES
    -- DOCUMENTS
    ('DOCUMENTS', 'read',    'documents:read',    'View and download documents'),
    ('DOCUMENTS', 'write',   'documents:write',   'Edit document metadata'),
    ('DOCUMENTS', 'upload',  'documents:upload',  'Upload new documents'),
    ('DOCUMENTS', 'delete',  'documents:delete',  'Soft-delete documents'),
    ('DOCUMENTS', 'archive', 'documents:archive', 'Archive and restore documents'),
    ('DOCUMENTS', 'export',  'documents:export',  'Bulk export documents'),
    -- WORKFLOW
    ('WORKFLOW',  'view',    'workflow:view',    'View workflow instances and tasks'),
    ('WORKFLOW',  'claim',   'workflow:claim',   'Claim unassigned tasks'),
    ('WORKFLOW',  'approve', 'workflow:approve', 'Approve workflow tasks'),
    ('WORKFLOW',  'reject',  'workflow:reject',  'Reject workflow tasks'),
    ('WORKFLOW',  'design',  'workflow:design',  'Create and edit workflow templates'),
    ('WORKFLOW',  'admin',   'workflow:admin',   'Manage all workflow instances'),
    -- EFORMS
    ('EFORMS',    'submit',  'eforms:submit',  'Submit eForms'),
    ('EFORMS',    'review',  'eforms:review',  'Review eForm submissions'),
    ('EFORMS',    'design',  'eforms:design',  'Design eForm templates'),
    ('EFORMS',    'admin',   'eforms:admin',   'Manage all eForm definitions'),
    -- ADMIN
    ('ADMIN',     'users',     'admin:users',     'Manage users and role assignments'),
    ('ADMIN',     'roles',     'admin:roles',     'Create and configure roles'),
    ('ADMIN',     'configure', 'admin:configure', 'System configuration and settings'),
    ('ADMIN',     'audit',     'admin:audit',     'View audit logs'),
    -- OCR
    ('OCR',       'trigger', 'ocr:trigger', 'Trigger OCR processing'),
    ('OCR',       'view',    'ocr:view',    'View OCR results'),
    -- ARCHIVE
    ('ARCHIVE',   'read',    'archive:read',   'View archived documents'),
    ('ARCHIVE',   'manage',  'archive:manage', 'Manage retention policies'),
    -- CASE
    ('CASE',      'VIEW',    'CASE:VIEW',   'View cases and case details'),
    ('CASE',      'CREATE',  'CASE:CREATE', 'Create new cases'),
    ('CASE',      'UPDATE',  'CASE:UPDATE', 'Update case status, verify items, add notes'),
    ('CASE',      'DELETE',  'CASE:DELETE', 'Delete and cancel cases'),
    ('CASE',      'ASSIGN',  'CASE:ASSIGN', 'Assign and reassign cases'),
    ('CASE',      'VERIFY',  'CASE:VERIFY', 'Verify checklist items'),
    -- PRODUCT (under ADMIN module)
    ('ADMIN',     'PRODUCT_VIEW',   'PRODUCT:VIEW',   'View products and catalogue'),
    ('ADMIN',     'PRODUCT_CREATE', 'PRODUCT:CREATE', 'Create products'),
    ('ADMIN',     'PRODUCT_UPDATE', 'PRODUCT:UPDATE', 'Update products and document types'),
    ('ADMIN',     'PRODUCT_DELETE', 'PRODUCT:DELETE', 'Deactivate products'),
    -- CUSTOMER (under ADMIN module)
    ('ADMIN',     'CUSTOMER_VIEW',   'CUSTOMER:VIEW',   'View customers and enrollments'),
    ('ADMIN',     'CUSTOMER_CREATE', 'CUSTOMER:CREATE', 'Create customers'),
    ('ADMIN',     'CUSTOMER_UPDATE', 'CUSTOMER:UPDATE', 'Update customers and enrollments'),
    ('ADMIN',     'CUSTOMER_DELETE', 'CUSTOMER:DELETE', 'Deactivate customers'),
    -- BATCH
    ('BATCH',     'upload',     'batch:upload',     'Upload documents via batch processing'),
    ('BATCH',     'view',       'batch:view',       'View batch job status and progress'),
    ('BATCH',     'review',     'batch:review',     'Work the classification review queue'),
    ('BATCH',     'spot_check', 'batch:spot_check', 'View auto-filed items for QA'),
    ('BATCH',     'admin',      'batch:admin',      'Configure batch settings, watch folders, thresholds');

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. Role -> Permission Grants
-- ─────────────────────────────────────────────────────────────────────────────

-- ECM_ADMIN gets ALL permissions
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r, ecm_core.permissions p
WHERE r.name = 'ECM_ADMIN';

-- ECM_BACKOFFICE
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read', 'documents:write', 'documents:upload',
    'workflow:view',  'workflow:claim',   'workflow:approve', 'workflow:reject',
    'eforms:submit',  'eforms:review',
    'ocr:view',       'archive:read',
    'CASE:VIEW', 'CASE:CREATE', 'CASE:UPDATE', 'CASE:VERIFY', 'CASE:ASSIGN',
    'PRODUCT:VIEW',
    'CUSTOMER:VIEW', 'CUSTOMER:CREATE', 'CUSTOMER:UPDATE',
    'batch:upload', 'batch:view'
)
WHERE r.name = 'ECM_BACKOFFICE';

-- ECM_REVIEWER
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read',
    'workflow:view',  'workflow:approve', 'workflow:reject',
    'eforms:submit',  'eforms:review',
    'archive:read',
    'CASE:VIEW', 'CASE:UPDATE', 'CASE:VERIFY', 'CASE:ASSIGN',
    'PRODUCT:VIEW',
    'CUSTOMER:VIEW',
    'batch:review', 'batch:spot_check', 'batch:view'
)
WHERE r.name = 'ECM_REVIEWER';

-- ECM_DESIGNER
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

-- ECM_READONLY
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN ('documents:read', 'eforms:submit', 'archive:read')
WHERE r.name = 'ECM_READONLY';

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. Capability Bundles
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.capability_bundles (code, name, description, sort_order) VALUES
    ('DOCUMENT_CONTRIBUTOR', 'Document Contributor',  'Upload, view, and manage documents',           1),
    ('TASK_PROCESSOR',       'Task Processor',        'Claim and action workflow tasks',               2),
    ('FORM_REVIEWER',        'Form Reviewer',         'Review and approve eForm submissions',          3),
    ('DESIGNER',             'Designer',              'Design workflows and eForms',                   4),
    ('COMPLIANCE_REVIEWER',  'Compliance Reviewer',   'Compliance audit, archive, export',             5),
    ('BATCH_OPERATOR',       'Batch Operator',        'Upload batches, review classifications, QA',    6);

-- Bundle -> Permission links
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'DOCUMENT_CONTRIBUTOR'
  AND p.code IN ('documents:read', 'documents:write', 'documents:upload', 'workflow:view', 'eforms:submit');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'TASK_PROCESSOR'
  AND p.code IN ('documents:read', 'workflow:view', 'workflow:claim', 'workflow:approve', 'workflow:reject', 'eforms:review', 'ocr:view');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'FORM_REVIEWER'
  AND p.code IN ('documents:read', 'workflow:view', 'workflow:approve', 'workflow:reject', 'eforms:submit', 'eforms:review');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'DESIGNER'
  AND p.code IN ('documents:read', 'workflow:view', 'workflow:design', 'eforms:submit', 'eforms:design', 'ocr:view');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'COMPLIANCE_REVIEWER'
  AND p.code IN ('documents:read', 'documents:export', 'documents:archive', 'workflow:view', 'workflow:approve', 'workflow:reject', 'eforms:review', 'archive:read', 'archive:manage', 'admin:audit');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'BATCH_OPERATOR'
  AND p.code IN ('documents:read', 'documents:upload', 'batch:upload', 'batch:view', 'batch:review', 'batch:spot_check');

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. Segments
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.segments (name, code, description) VALUES
    ('Retail',         'RETAIL',     'Retail banking — individuals and households'),
    ('Commercial',     'COMMERCIAL', 'Commercial banking — mid-market and enterprise'),
    ('Small Business', 'SMB',        'Small business banking — sole traders and small enterprises')
ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. Product Lines
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.product_lines (segment_id, name, code, description) VALUES
    (1, 'Banking',      'RETAIL_BANKING',      'Retail current, savings, and chequing accounts'),
    (1, 'Loans',        'RETAIL_LOANS',        'Retail mortgages, auto loans, personal loans'),
    (1, 'Investment',   'RETAIL_INVESTMENT',   'Retail term deposits and investment accounts'),
    (1, 'Mutual Funds', 'RETAIL_MUTUAL_FUNDS', 'Third-party mutual fund distribution'),
    (2, 'Banking',      'COMM_BANKING',        'Commercial transactional accounts'),
    (2, 'Lending',      'COMM_LENDING',        'Commercial credit facilities and trade finance'),
    (3, 'Banking',      'SMB_BANKING',         'Small business accounts'),
    (3, 'Loans',        'SMB_LOANS',           'Small business lending')
ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. Document Categories
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.document_categories (name, code, description) VALUES
    ('Mortgage',              'MORTGAGE',     'Mortgage application documents'),
    ('Auto Loan',             'AUTO_LOAN',    'Auto loan application documents'),
    ('Identity Verification', 'IDENTITY',     'KYC and identity documents'),
    ('Financial Statements',  'FINANCIAL',    'Income, tax and financial records'),
    ('Legal Agreements',      'LEGAL',        'Signed legal and compliance documents'),
    ('Invoice',               'INV',          'Vendor and customer invoices'),
    ('Contract',              'CTR',          'Legal contracts and agreements'),
    ('HR Document',           'HRD',          'HR records and payslips'),
    ('Report',                'RPT',          'Internal and external reports'),
    ('Correspondence',        'COR',          'Emails and letters'),
    ('Scanned Form',          'SCF',          'Scanned physical forms'),
    ('Compliance',            'COMPLIANCE',   'AML, KYC and regulatory compliance documents'),
    ('Boarding Pass',         'BOARDINGPASS', 'Airline boarding passes'),
    ('Resume',                'RESUME',       'Curriculum vitae and resumes')
ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 12. Products
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.products (product_code, display_name, description, segment_id, product_line_id, product_schema) VALUES
    ('MORTGAGE',
     'Mortgage Application', 'Residential mortgage origination',
     1, 2,
     '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"propertyAddress","label":"Property Address","type":"text","required":true}]}'::jsonb),
    ('AUTO_LOAN',
     'Auto Loan Application', 'Vehicle purchase and refinance loans',
     1, 2,
     '{"fields":[{"key":"vehicleVin","label":"Vehicle VIN","type":"text","required":true},{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true}]}'::jsonb),
    ('PERSONAL_LOAN',
     'Personal Loan', 'Unsecured personal lending',
     1, 2,
     '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"purpose","label":"Loan Purpose","type":"text","required":true}]}'::jsonb),
    ('COMM_CREDIT_FACILITY',
     'Commercial Credit Facility', 'Commercial revolving credit and term loans',
     2, 6,
     '{"fields":[{"key":"facilityAmount","label":"Facility Amount","type":"currency","required":true},{"key":"businessRegNo","label":"Registration No","type":"text","required":true}]}'::jsonb),
    ('SMB_LOAN',
     'Small Business Loan', 'Small business lending products',
     3, 8,
     '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"businessName","label":"Business Name","type":"text","required":true}]}'::jsonb)
ON CONFLICT (product_code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 13. OCR Templates
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.ocr_templates (category_code, name, description, fields, created_by) VALUES
    ('IDENTITY',
     'Identity Document Extraction',
     'Government-issued identity document extraction',
     '[{"fieldName":"full_name","pattern":"(?:name|full name)[:\\\\s]+([A-Z][a-z]+(?:\\\\s[A-Z][a-z]+)+)","defaultValue":""},{"fieldName":"document_number","pattern":"(?:licence no|license no|dl no|passport no|id no|document no)[:\\\\s#]*([A-Z0-9]{5,15})","defaultValue":""},{"fieldName":"date_of_birth","pattern":"(?:date of birth|dob|born)[:\\\\s]*(\\\\d{1,2}[/-]\\\\d{1,2}[/-]\\\\d{2,4})","defaultValue":""},{"fieldName":"expiry_date","pattern":"(?:expiry|expires|expiration|exp)[:\\\\s]*(\\\\d{1,2}[/-]\\\\d{1,2}[/-]\\\\d{2,4})","defaultValue":""}]'::jsonb,
     'system'),
    ('MORTGAGE',
     'Mortgage Document Extraction',
     'Mortgage loan document extraction',
     '[{"fieldName":"loan_amount","pattern":"(?:loan amount|principal)[:\\\\s]*\\\\$?([\\\\d,.]+)","defaultValue":""},{"fieldName":"borrower_name","pattern":"(?:borrower|applicant)[:\\\\s]+([A-Z][a-z]+(?:\\\\s[A-Z][a-z]+)+)","defaultValue":""},{"fieldName":"property_address","pattern":"(?:property address|subject property)[:\\\\s]+([\\\\w\\\\s,]+(?:Ave|St|Rd|Blvd|Dr|Ln|Way)[\\\\w\\\\s,]*)","defaultValue":""},{"fieldName":"interest_rate","pattern":"(?:interest rate|rate)[:\\\\s]*([\\\\d.]+)\\\\s*%","defaultValue":""},{"fieldName":"loan_term_years","pattern":"(?:loan term|term)[:\\\\s]*(\\\\d+)\\\\s*(?:year|yr)","defaultValue":""}]'::jsonb,
     'system'),
    ('RESUME',
     'Resume / CV Extraction',
     'Resume / CV field extraction',
     '[{"fieldName":"full_name","pattern":"^([A-Z][a-z]+(?:\\\\s[A-Z][a-z]+)+)","defaultValue":null},{"fieldName":"email","pattern":"([a-zA-Z0-9._%+\\\\-]+@[a-zA-Z0-9.\\\\-]+\\\\.[a-zA-Z]{2,})","defaultValue":null},{"fieldName":"phone","pattern":"(\\\\+?1[\\\\s.-]?)?\\\\(?\\\\d{3}\\\\)?[\\\\s.-]?\\\\d{3}[\\\\s.-]?\\\\d{4}","defaultValue":null},{"fieldName":"linkedin","pattern":"(linkedin\\\\.com/in/[a-zA-Z0-9\\\\-]+)","defaultValue":null}]'::jsonb,
     'system'),
    ('BOARDINGPASS',
     'Boarding Pass Extraction',
     'Airline boarding pass field extraction',
     '[{"fieldName":"passenger_name","pattern":"(?:name|passenger)[:\\\\s]+([A-Z][A-Z/\\\\s]+[A-Z])","defaultValue":""},{"fieldName":"flight_number","pattern":"(?:flight|flt)[\\\\s#:]*([A-Z]{2}\\\\s?\\\\d{2,5})","defaultValue":""},{"fieldName":"origin","pattern":"(?:from|departure|origin)[:\\\\s]*([A-Z]{3})","defaultValue":""},{"fieldName":"destination","pattern":"(?:to|arrival|destination)[:\\\\s]*([A-Z]{3})","defaultValue":""},{"fieldName":"departure_date","pattern":"(?:date|departs?)[:\\\\s]*(\\\\d{1,2}[/-]\\\\d{1,2}[/-]\\\\d{2,4}|\\\\d{2}[A-Z]{3}\\\\d{2,4})","defaultValue":""},{"fieldName":"departure_time","pattern":"(?:departs?|time|dep)[:\\\\s]*(\\\\d{1,2}:\\\\d{2}(?:\\\\s?[AP]M)?)","defaultValue":""},{"fieldName":"seat","pattern":"(?:seat)[:\\\\s]*([0-9]{1,3}[A-F])","defaultValue":""},{"fieldName":"gate","pattern":"(?:gate)[:\\\\s]*([A-Z]?\\\\d{1,3}[A-Z]?)","defaultValue":""},{"fieldName":"boarding_group","pattern":"(?:boarding|group)[:\\\\s]*(GROUP\\\\s*\\\\d+|[A-Z]\\\\d*)","defaultValue":""},{"fieldName":"ticket_number","pattern":"(?:ticket|eticket|e-ticket)[:\\\\s#]*(\\\\d{3}[-]?\\\\d{10}|\\\\d{13})","defaultValue":""},{"fieldName":"frequent_flyer","pattern":"(?:frequent flyer|aeroplan|mileage|ffn)[:\\\\s#]*([A-Z0-9]{6,12})","defaultValue":""},{"fieldName":"carrier","pattern":"(?:carrier|airline|operated by)[:\\\\s]*([A-Z]{2})","defaultValue":""}]'::jsonb,
     'system');

-- Link OCR templates to their categories (by code)
UPDATE ecm_admin.ocr_templates ot
SET category_id = dc.id
FROM ecm_admin.document_categories dc
WHERE dc.code = ot.category_code;

-- ─────────────────────────────────────────────────────────────────────────────
-- 14. Tenant Config
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.tenant_config (key, value, default_value, description) VALUES
    ('tenant.name',              'ECM Platform', 'ECM Platform', 'Organisation display name'),
    ('tenant.logo_url',          '',             '',             'Logo URL for header branding'),
    ('tenant.support_email',     '',             '',             'Support email shown in UI footer'),
    ('tenant.timezone',          'UTC',          'UTC',          'Default timezone for date display'),
    ('theme.sidebar_bg',        '#002347',      '#002347',      'Sidebar background colour'),
    ('theme.sidebar_active',    '#00A651',      '#00A651',      'Sidebar active item / badge colour'),
    ('theme.header_bg',         '#ffffff',      '#ffffff',      'Header bar background colour'),
    ('theme.header_text',       '#111827',      '#111827',      'Header title text colour'),
    ('theme.accent',            '#4f46e5',      '#4f46e5',      'Buttons, links, focus rings, active states'),
    ('theme.page_bg',           '#f4f6f9',      '#f4f6f9',      'Main content area background'),
    ('webhook.document_indexed.url',    '',  '',             'POST callback URL when document reaches INDEXED status'),
    ('webhook.submission_signed.url',   '',  '',             'POST callback URL when DocuSign signing is confirmed'),
    ('azure.rate_limit_per_second',     '1', '1',            'Azure AI API rate limit (calls/sec). Dev=1, Prod=10+. Set to 0 for unlimited.'),
    ('ocr.pipeline',
     '[{"engine":"glm-ocr","enabled":false,"priority":1,"minConfidence":75,"config":{"url":"http://localhost:11434","model":"glm-ocr","timeout":"120"}},{"engine":"rapidocr","enabled":true,"priority":2,"minConfidence":0,"config":{"url":"http://localhost:8884","apiPath":"/ocr","fileField":"image_file","timeout":"60"}},{"engine":"azure","enabled":true,"priority":3,"minConfidence":0,"config":{}}]',
     '[{"engine":"glm-ocr","enabled":false,"priority":1,"minConfidence":75,"config":{"url":"http://localhost:11434","model":"glm-ocr","timeout":"120"}},{"engine":"rapidocr","enabled":true,"priority":2,"minConfidence":0,"config":{"url":"http://localhost:8884","apiPath":"/ocr","fileField":"image_file","timeout":"60"}},{"engine":"azure","enabled":true,"priority":3,"minConfidence":0,"config":{}}]',
     'Dynamic OCR pipeline engine configuration (JSON array)')
ON CONFLICT (key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 15. Integration Configs
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.integration_configs (tenant_id, system_key, display_name, config, enabled) VALUES
    ('default', 'DOCUSIGN', 'DocuSign eSignature',
     '{"base_url":"https://demo.docusign.net","auth_server":"https://account-d.docusign.com","account_id":"","integration_key":"","impersonated_user_id":""}'::jsonb,
     false),
    ('default', 'AZURE_AI', 'Azure AI Document Intelligence',
     '{"endpoint":"","api_version":"2024-02-29-preview"}'::jsonb,
     false)
ON CONFLICT (tenant_id, system_key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 16. Label Definitions (system defaults)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.label_definitions (label_key, display_name, input_type, is_system, sort_order) VALUES
    ('channel',    'Upload Channel', 'DROPDOWN', TRUE,  1),
    ('source',     'Source',         'DROPDOWN', TRUE,  2),
    ('batch_id',   'Batch ID',       'FREE_TEXT', TRUE, 3),
    ('branch',     'Branch',         'DROPDOWN', FALSE, 4),
    ('department', 'Department',     'DROPDOWN', FALSE, 5),
    ('notes',      'Notes',          'FREE_TEXT', FALSE, 6)
ON CONFLICT (label_key) DO NOTHING;

-- Set allowed values for system dropdowns
UPDATE ecm_admin.label_definitions
SET allowed_values = '["manual-upload","batch-upload","watch-folder","eforms","docusign","migration","external"]'::jsonb
WHERE label_key = 'channel';

UPDATE ecm_admin.label_definitions
SET allowed_values = '["user","batch","system","migration"]'::jsonb
WHERE label_key = 'source';

-- ─────────────────────────────────────────────────────────────────────────────
-- 17. Workflow Definition Configs
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_definition_configs
(name, description, process_key, assigned_role, is_active, sla_hours)
VALUES
    ('General Document Review',
     'Default single-step review by backoffice team.',
     'document-single-review', 'ECM_BACKOFFICE', TRUE, 48),
    ('Underwriter Review',
     'Two-step: backoffice triage then underwriter approval.',
     'document-dual-review', 'ECM_REVIEWER', TRUE, 24),
    ('Compliance Review',
     'Compliance team single-step review. Used for KYC and regulatory documents.',
     'document-compliance-review', 'ECM_REVIEWER', TRUE, 24),
    ('Form Admin Triage Review',
     'Form submission routed by admin to backoffice or reviewer. Reviewer can request additional documents.',
     'form-admin-triage-review', 'ECM_ADMIN', TRUE, 48)
ON CONFLICT (process_key) DO NOTHING;
