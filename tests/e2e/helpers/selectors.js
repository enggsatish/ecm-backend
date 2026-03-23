/**
 * Shared selectors — centralized so changes propagate to all tests.
 *
 * Convention:
 *   - data-testid selectors: [data-testid="xxx"]
 *   - Text selectors: text=XXX or role-based
 *   - Avoid CSS class selectors (break on Tailwind changes)
 */

// ── Layout ──────────────────────────────────────────────────────────────
export const SIDEBAR = {
  root:          'aside',
  link:          (label) => `aside >> text="${label}"`,
  badge:         (label) => `aside >> text="${label}" >> .. >> span:has-text(/\\d+/)`,
}

export const HEADER = {
  root:          'header',
  title:         'header h1, header h2',
  notificationBell: '[data-testid="notification-bell"]',
  notificationCount: '[data-testid="notification-count"]',
  userMenu:      '[data-testid="user-menu"]',
}

// ── Auth ─────────────────────────────────────────────────────────────────
export const OKTA = {
  usernameInput: 'input[name="identifier"], input[name="username"]',
  passwordInput: 'input[name="credentials.passcode"], input[name="password"], input[type="password"]',
  submitButton:  'input[type="submit"], button[type="submit"]',
  nextButton:    'input[value="Next"], button:has-text("Next"), input[value="Verify"]',
}

// ── Dashboard ───────────────────────────────────────────────────────────
export const DASHBOARD = {
  greeting:      '[data-testid="dashboard-greeting"]',
  statTile:      (label) => `[data-testid="stat-${label}"]`,
}

// ── Documents ───────────────────────────────────────────────────────────
export const DOCUMENTS = {
  uploadBtn:     'button:has-text("Upload")',
  fileInput:     'input[type="file"]',
  categorySelect:'select[data-testid="doc-category"]',
  table:         'table',
  viewBtn:       (row) => `tr:nth-child(${row}) >> button:has(svg)`, // Eye icon
  searchInput:   'input[placeholder*="Search"]',
}

// ── Document Viewer Modal ───────────────────────────────────────────────
export const DOC_VIEWER = {
  modal:         '[data-testid="doc-viewer-modal"]',
  tabs:          (label) => `button:has-text("${label}")`,
  metaRow:       (label) => `text="${label}" >> ..`,
  closeBtn:      '[data-testid="doc-viewer-close"]',
  pipelineTab:   'button:has-text("Pipeline")',
}

// ── Cases ───────────────────────────────────────────────────────────────
export const CASES = {
  newCaseBtn:    'button:has-text("New Case"), button:has-text("New Application")',
  caseList:      '[data-testid="cases-list"]',
  caseCard:      (ref) => `text="${ref}" >> ..`,
  statusBadge:   '[data-testid="case-status"]',
  assignBtn:     'button:has-text("Assign")',
  claimBtn:      'button:has-text("Claim")',
  checklistItem: (name) => `text="${name}" >> ..`,
  uploadInput:   'input[type="file"]',
}

// ── Review Queue ────────────────────────────────────────────────────────
export const REVIEW_QUEUE = {
  unclaimedTab:  'button:has-text("Unassigned"), button:has-text("Unclaimed")',
  myTasksTab:    'button:has-text("My Tasks")',
  claimBtn:      'button:has-text("Claim")',
  approveBtn:    'button:has-text("Approve")',
  rejectBtn:     'button:has-text("Reject")',
  taskRow:       (name) => `tr:has-text("${name}")`,
  commentInput:  'textarea[placeholder*="comment"], textarea[placeholder*="Comment"]',
}

// ── eForms ──────────────────────────────────────────────────────────────
export const EFORMS = {
  formCard:      (name) => `text="${name}" >> ..`,
  fillBtn:       'button:has-text("Fill Form")',
  submitBtn:     'button:has-text("Submit")',
  nextBtn:       'button:has-text("Next")',
  saveDraftBtn:  'button:has-text("Save Draft")',
  reviewBtn:     'button:has-text("Review")',
  confirmBtn:    'button:has-text("Confirm"), button:has-text("Submit")',
  fieldInput:    (key) => `[data-field-key="${key}"], [name="${key}"]`,
}

// ── Workflow Designer ───────────────────────────────────────────────────
export const WORKFLOW = {
  newTemplateBtn: 'button:has-text("New Template")',
  templateRow:    (name) => `tr:has-text("${name}")`,
  publishBtn:     'button:has-text("Publish")',
  actionMenu:     (name) => `tr:has-text("${name}") >> button:has(svg)`,
  mappingsTab:    'button:has-text("Category Mappings")',
  categorySelect: 'select:near(:text("Select category"))',
  workflowSelect: 'select:near(:text("Select workflow"))',
  addMappingBtn:  'button:has-text("Add")',
}

// ── Admin ───────────────────────────────────────────────────────────────
export const ADMIN = {
  usersTab:      'text="Users"',
  productsTab:   'text="Products"',
  inviteBtn:     'button:has-text("Invite")',
}

// ── Session Modal ───────────────────────────────────────────────────────
export const SESSION = {
  expiredModal:  'text="Session Expired" >> ..',
  signInBtn:     'button:has-text("Sign In Again")',
}
