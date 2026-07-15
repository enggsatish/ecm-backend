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
  viewBtn:       (row) => `tr:nth-child(${row}) button[title="View document"], tr:nth-child(${row}) button[aria-label="View"]`,
  searchInput:   'input[placeholder*="Search"]',
  // Three-dot menu
  actionMenuBtn: (row) => `tr:nth-child(${row}) button[aria-label="More actions"]`,
  lockMenuItem:  'button:has-text("Lock Document")',
  unlockMenuItem:'button:has-text("Unlock Document")',
  archiveMenuItem:'button:has-text("Archive")',
  deleteMenuItem:'button:has-text("Delete")',
  // Status & badges
  statusBadge:   (row) => `tr:nth-child(${row}) span:has-text("Active"), tr:nth-child(${row}) span:has-text("Processing")`,
  lockBadge:     'span:has-text("You"), span:has-text("Locked")',
  caseBadge:     'span:has-text("Case:")',
}

// ── Document Viewer Modal ───────────────────────────────────────────────
export const DOC_VIEWER = {
  modal:         '[data-testid="doc-viewer-modal"]',
  tabs:          (label) => `button:has-text("${label}")`,
  metaRow:       (label) => `text="${label}" >> ..`,
  closeBtn:      '[data-testid="doc-viewer-close"]',
  pipelineTab:   'button:has-text("Pipeline")',
  // PDF Annotation viewer
  addCommentBtn: 'button:has-text("Add Comment")',
  commentsBtn:   'button:has-text("Comments"), button:has-text("comment")',
  pdfCanvas:     'canvas',
  annotationPin: (index) => `button:has-text("${index}")`,
  commentInput:  'textarea[placeholder*="comment"]',
  saveAnnotation:'button:has-text("Save")',
  resolveBtn:    'button:has-text("Resolve")',
  // Pipeline branches
  ocrBranch:     'text="OCR"',
  reviewBranch:  'text="Review"',
  esignBranch:   'text="eSign"',
}

// ── Cases ───────────────────────────────────────────────────────────────
export const CASES = {
  newCaseBtn:    'button:has-text("New Case"), button:has-text("New Application")',
  caseList:      '[data-testid="cases-list"]',
  caseRow:       (ref) => `tr:has-text("${ref}")`,
  caseCard:      (ref) => `text="${ref}" >> ..`,
  statusBadge:   '[data-testid="case-status"]',
  assignBtn:     'button:has-text("Assign")',
  claimBtn:      'button:has-text("Claim")',
  // Owner column
  ownerBadge:    'td span:has-text("working"), td span:has-text("Unassigned")',
  // State machine transitions
  startWorkingBtn: 'button:has-text("Start Working")',
  submitForReviewBtn: 'button:has-text("Submit for Review")',
  // Checklist
  checklistItem: (name) => `text="${name}" >> ..`,
  uploadInput:   'input[type="file"]',
  eyeBtn:        (name) => `text="${name}" >> .. >> button[title="View document"], text="${name}" >> .. >> button:has(svg.lucide-eye)`,
  // Actions menu on checklist items
  actionsBtn:    'button:has-text("Actions")',
  startWorkflowItem: 'button:has-text("Start Workflow")',
  sendForSignatureItem: 'button:has-text("Send for Signature")',
  markCompleteItem: 'button:has-text("Mark as Complete")',
  waiveItem:     'button:has-text("Waive")',
  overrideItem:  'button:has-text("Override"), button:has-text("Bypass")',
  // Completed item
  reopenBtn:     'button:has-text("Reopen")',
  completedBadge:'text="Completed"',
  // Add document request
  addDocRequestBtn: 'button:has-text("Add Document Request")',
  categoryDropdown: 'select:has(option:has-text("Select document category"))',
  customNameInput: 'input[placeholder*="Power of Attorney"]',
  addToChecklistBtn: 'button:has-text("Add to Checklist")',
  // Send for Signature modal
  signerEmailInput: 'input[type="email"][placeholder*="signer"]',
  signerNameInput: 'input[placeholder*="John Smith"]',
  autoPlacement:  'input[value="auto"]',
  lastPagePlacement: 'input[value="lastPage"]',
  specificPlacement: 'input[value="specific"]',
  sendForSignatureBtn: 'button:has-text("Send for Signature")',
  // Lock indicators
  awaitingSignatureBadge: 'text="Awaiting Signature"',
  underReviewBadge: 'text="Under Review"',
  signedBadge:   'text="Signed"',
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
  fillBtn:       'button:has-text("Fill Form"), a:has-text("Fill Form")',
  submitBtn:     'button:has-text("Submit")',
  nextBtn:       'button:has-text("Next")',
  saveDraftBtn:  'button:has-text("Save Draft")',
  reviewBtn:     'button:has-text("Review")',
  confirmBtn:    'button:has-text("Confirm"), button:has-text("Submit")',
  fieldInput:    (key) => `[data-field-key="${key}"], [name="${key}"]`,
  // eSign fields
  signatureField: '[data-field-type="SIGNATURE"]',
  initialsField:  '[data-field-type="INITIALS"]',
  signerEmailField: '[data-field-type="SIGNER_EMAIL"]',
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

// ── SLA Dashboard ─────────────────────────────────────────────────────
export const SLA = {
  page:          'h1:has-text("SLA"), h2:has-text("SLA")',
  onTrackCard:   'text="On Track" >> ..',
  warningCard:   'text="Warning" >> ..',
  breachedCard:  'text="Breached" >> ..',
  viewLink:      'a:has-text("View"), button:has-text("View")',
  searchInput:   'input[placeholder*="Search"]',
}

// ── Admin ───────────────────────────────────────────────────────────────
export const ADMIN = {
  usersTab:      'text="Users"',
  productsTab:   'text="Products"',
  inviteBtn:     'button:has-text("Invite")',
  integrationsLink: 'text="Integrations"',
  settingsLink:  'text="Settings"',
}

// ── Session Modal ───────────────────────────────────────────────────────
export const SESSION = {
  expiredModal:  'text="Session Expired" >> ..',
  signInBtn:     'button:has-text("Sign In Again")',
}
