# ECM E2E Tests (Playwright)

## Prerequisites

- Node.js 20+
- All ECM services running (gateway, identity, document, workflow, eforms, admin, notification)
- ECM frontend running on `http://localhost:3000`
- Okta test user accounts (one per role)

## Setup

```bash
cd tests/e2e

# Install dependencies
npm install

# Install Chromium browser
npx playwright install chromium

# Create credentials file
cp .env.test.template .env.test
# Edit .env.test — fill in Okta test user credentials
```

## Running Tests

```bash
# Run all suites
npm test

# Run with browser visible (for debugging)
npm run test:headed

# Run Playwright UI mode (interactive)
npm run test:ui

# Run a specific suite
npm run test:auth
npm run test:cases
npm run test:eforms

# Debug a specific test (step through)
npm run test:debug
```

## Test Suites

| Suite | File | What it tests |
|-------|------|---------------|
| 01-auth | `specs/01-auth.spec.js` | Login, dashboard, sidebar role visibility |
| 02-document-upload | `specs/02-document-upload.spec.js` | Upload → OCR → workflow trigger → pipeline view |
| 03-case-lifecycle | `specs/03-case-lifecycle.spec.js` | Create case → upload docs → assign → review → complete |
| 04-eform-submit | `specs/04-eform-submit.spec.js` | Fill form → submit → review queue → approve |
| 05-workflow-designer | `specs/05-workflow-designer.spec.js` | Create template → publish → category mapping |
| 06-notifications | `specs/06-notifications.spec.js` | Bell count, dropdown, preferences |
| 07-role-access | `specs/07-role-access.spec.js` | Role guards, restricted URL access |
| 08-session-renewal | `specs/08-session-renewal.spec.js` | Token renewal, expiry modal, session storage |

## Architecture

```
e2e/
├── fixtures/           Auth setup + test data helpers
│   ├── setup-auth.js   Logs into Okta per role, caches sessions
│   └── auth.fixture.js Provides adminPage, reviewerPage, etc.
├── pages/              Page Object Model (one class per page)
├── specs/              Test suites (numbered for run order)
├── helpers/            Shared selectors + wait utilities
└── auth/               Cached session files (gitignored)
```

## Auth Caching

On first run, Playwright automates the Okta login for each role and saves the session to `auth/*.json`. Subsequent runs skip login and reuse the cached session. Sessions auto-refresh if older than 30 minutes.

To force re-login:
```bash
rm auth/*.json
npm test
```

## Viewing Reports

After a test run:
```bash
npm run report
```
Opens an interactive HTML report with screenshots, traces, and video for failed tests.
