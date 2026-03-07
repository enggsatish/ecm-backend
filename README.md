# 🏦 Servus ECM Platform — Backend

> **Enterprise Content Management for Financial Institutions**
> White-label · Multi-tenant · Microservices · Java 21 · Spring Boot 3.3.5

[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-FF6600?logo=rabbitmq)](https://www.rabbitmq.com/)
[![MinIO](https://img.shields.io/badge/MinIO-S3--compatible-C72E49?logo=minio)](https://min.io/)

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Module Structure](#module-structure)
- [Infrastructure Services](#infrastructure-services)
- [Database Schema](#database-schema)
- [Security & Authentication](#security--authentication)
- [Message Flows (RabbitMQ)](#message-flows-rabbitmq)
- [API Reference](#api-reference)
- [Local Development Setup](#local-development-setup)
- [Environment Variables](#environment-variables)
- [Flyway Migrations](#flyway-migrations)
- [Known Issues & Bugs](#known-issues--bugs)
- [Production Deployment](#production-deployment)

---

## Overview

Servus ECM is a white-label Enterprise Content Management platform targeting financial institutions — credit unions, banks, and insurance providers. It manages the full document lifecycle: ingestion, OCR, workflow-based review, electronic forms, digital signing, archiving, and retention enforcement.

### Key Capabilities

| Capability | Module | Status |
|---|---|---|
| SSO login via Okta / Microsoft Entra ID | ecm-identity | ✅ Done |
| Document upload, versioning, storage | ecm-document | ✅ Done |
| API Gateway — routing, rate limiting, circuit breakers | ecm-gateway | ✅ Done |
| BPMN workflow automation (Flowable) | ecm-workflow | ✅ Done |
| Low-code eForm designer + renderer | ecm-eforms | ✅ Done |
| Platform administration & tenant config | ecm-admin | ✅ Done |
| Async OCR (Tika + Tesseract) | ecm-ocr | ✅ Done |
| Email / in-app notifications | ecm-notification | 🔲 Planned |

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                React Frontend  :3000                    │
└──────────────────────┬──────────────────────────────────┘
                       │  HTTPS / Bearer JWT
┌──────────────────────▼──────────────────────────────────┐
│          ECM Gateway  :8080                             │
│   JWT validation · CORS · Rate limit · Circuit breaker  │
│   Correlation ID · Security headers · Retry             │
└───┬──────────┬──────────┬──────────┬──────────┬─────────┘
    │          │          │          │          │
  :8081      :8082      :8083      :8084      :8086
Identity  Document  Workflow   eForms    Admin
    │          │          │          │
    ▼          ▼          ▼          ▼
 PostgreSQL  MinIO    Flowable   DocuSign
 (5 schemas) (4 buckets) (embedded)  (webhook)
    │          │
    └────┬─────┘
         │ RabbitMQ :5672
         │   ecm.document exchange
         │     ├── ocr.request  ──► ecm-ocr :8087
         │     └── document.uploaded ──► ecm-workflow
         │
      Redis :6379          OpenSearch :9200
   (sessions + rate limit)  (full-text search)
```

### Design Principles

- **Single entry point**: All frontend traffic enters through the gateway. Downstream services are never directly exposed.
- **Shared security**: `ecm-common` provides a base `SecurityConfig` inherited by all services. CORS is owned exclusively by the gateway — downstream services disable it to prevent duplicate headers.
- **Schema isolation**: Each module owns its own PostgreSQL schema. Cross-schema writes use `JdbcTemplate`, not JPA, to avoid accidental migration coupling.
- **Soft deletes only**: No `DELETE` statements. All deactivation sets `is_active = false`.
- **Graceful degradation**: RabbitMQ and workflow triggers degrade non-fatally. A failed event publish logs a warning but does not fail the HTTP request.
- **Interface-plus-impl where needed**: `DocumentStorageService` has `MinioDocumentStorageService` (local) and an Azure Blob implementation (production). Single-implementation services (Flowable BPM) use concrete classes directly.

---

## Technology Stack

| Technology | Version | Role |
|---|---|---|
| **Java** | 21 (LTS) | Runtime. Virtual threads (Project Loom) for high-concurrency I/O. |
| **Spring Boot** | 3.3.5 | Application framework across all 8 modules. |
| **Spring Cloud Gateway** | 2023.0.3 | API gateway — routing, rate limiting, circuit breakers, CORS. |
| **Spring Security OAuth2** | Boot-managed | JWT resource server. Validates Okta/Entra tokens. Role extraction from `groups` claim. |
| **Flowable BPM** | 7.0.0 | Embedded BPMN 2.0 engine for workflow automation. |
| **PostgreSQL** | 16 (Alpine) | Primary relational store. 5 logical schemas. |
| **Flyway** | 10.10.0 | Database migrations. Per-module `V*.sql` files. |
| **MinIO** | Latest | S3-compatible object storage for documents. Swappable with Azure Blob. |
| **RabbitMQ** | 3.x | Async event bus. Decouples upload → OCR → workflow pipeline. |
| **Redis** | 7 (Alpine) | Session cache (ecm-identity) + rate-limit counters (ecm-gateway). |
| **OpenSearch** | 1.5.2 | Full-text search index on extracted document text. |
| **Apache Tika** | 3.0.0 | Document content extraction and OCR orchestration. |
| **Tesseract** | via Tika | OCR engine for scanned PDFs and images. |
| **Resilience4j** | Cloud-managed | Circuit breakers, time limiters, retries on gateway routes. |
| **Lombok** | 1.18.34 | Code generation (`@Builder`, `@Slf4j`, etc.). |
| **MapStruct** | 1.5.5 | Compile-time DTO ↔ Entity mapping in ecm-document. |
| **Maven** | 3.9+ | Multi-module build. All dependency versions pinned in root `pom.xml`. |

---

## Module Structure

```
ecm-platform/
├── pom.xml                    ← Root POM — all dependency versions defined here
├── docker-compose.yml         ← Full infrastructure stack
├── infrastructure/
│   └── sql/init.sql           ← Master DB bootstrap (run once on fresh install)
├── startServices.sh           ← Helper to start all services
│
├── ecm-common/                ← Shared library (JAR, not deployable)
├── ecm-identity/              ← Port 8081 — Auth, user provisioning
├── ecm-document/              ← Port 8082 — Document lifecycle
├── ecm-gateway/               ← Port 8080 — API Gateway (entry point)
├── ecm-workflow/              ← Port 8083 — BPMN workflow engine
├── ecm-eforms/                ← Port 8084 — Low-code eForms
├── ecm-admin/                 ← Port 8086 — Platform administration
└── ecm-ocr/                   ← Port 8087 — Async OCR worker
```

---

### `ecm-common` — Shared Library

Not a deployable service. Included as a Maven dependency by all other modules.

| Component | Class | Purpose |
|---|---|---|
| Security config | `SecurityConfig` | Base JWT resource server config. All downstream services extend this. CORS disabled intentionally. |
| JWT converter | `EcmJwtConverter` | Maps Okta `groups` claim → Spring `GrantedAuthority` roles. |
| Audience validator | `AudienceValidator` | Validates JWT `aud` claim. Rejects tokens for wrong client. |
| Audit annotation | `@AuditLog` | Method-level annotation. Triggers `AuditAspect` AOP interceptor. |
| Audit aspect | `AuditAspect` | Captures identity + IP before method runs. Writes to DB asynchronously via `AuditWriter`. |
| Audit writer | `AuditWriter` | `@Async` bean. Writes to `ecm_audit.audit_log`. Non-blocking — does not hold the HTTP thread. |
| Response wrapper | `ApiResponse<T>` | Standard envelope: `{ success, data, message }`. All endpoints return this. |
| Exception handler | `GlobalExceptionHandler` | Converts exceptions to structured `ApiResponse` error responses. |
| Async config | `AsyncConfig` | Thread pool for `@Async` audit writes. |

> **Audit annotation order**: `@AuditLog` must be the **outer** annotation, `@Transactional` the **inner**. This ensures a DB rollback produces a `FAILURE` audit record, not a false `SUCCESS`.

---

### `ecm-identity` — Port 8081

Authentication hub. Provisions users from Okta tokens on first login.

```
src/main/java/com/ecm/identity/
├── config/
│   └── RedisCacheConfig.java    ← Redis cache regions: "users" (10 min), "sessions" (5 min)
├── controller/
│   ├── AuthController.java      ← /api/auth/** endpoints
│   └── UserController.java      ← /api/users/** endpoints
├── model/
│   ├── dto/UserSessionDto.java  ← Returned by /api/auth/me
│   └── entity/User.java         ← Maps to ecm_core.users
├── repository/
│   ├── UserRepository.java
│   └── RoleRepository.java
└── service/
    └── IdentityService.java     ← syncUserFromToken() — upsert on every login
```

**Endpoints:**

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/auth/me` | Any role | Current user session with roles |
| `GET` | `/api/auth/ping` | Any role | Token validation check |
| `POST` | `/api/auth/logout` | Any role | Session invalidation |
| `GET` | `/api/users` | `ECM_ADMIN` | List all users |
| `GET` | `/api/users/me/profile` | Any role | Own profile |

**Redis caching:**
- `users` region — `User` entities keyed by `entraObjectId`. TTL 10 minutes. Evicted on user deactivation.
- `sessions` region — `UserSessionDto` keyed by subject. TTL 5 minutes (aligned with frontend `staleTime`).

---

### `ecm-document` — Port 8082

Core document lifecycle management.

```
src/main/java/com/ecm/document/
├── config/
│   ├── MinioConfig.java            ← MinIO client bean
│   ├── RabbitMqConfig.java         ← Exchange + queue declarations
│   └── OpenSearchDocumentConfig.java
├── controller/
│   ├── DocumentController.java     ← Upload, list, get, download, delete
│   └── DocumentSearchController.java ← Full-text search via OpenSearch
├── entity/
│   ├── Document.java               ← Maps to ecm_core.documents
│   └── DocumentStatus.java         ← PENDING_OCR | OCR_COMPLETED | ACTIVE | ARCHIVED
├── service/
│   ├── DocumentService.java        ← Interface
│   ├── impl/DocumentServiceImpl.java ← Upload logic, RabbitMQ publish
│   ├── DocumentSearchService.java  ← OpenSearch queries
│   └── DocumentIndexSyncService.java ← Keeps OpenSearch in sync
└── storage/
    ├── DocumentStorageService.java      ← Interface (pluggable)
    └── MinioDocumentStorageService.java ← Local implementation
```

**Upload flow:**
1. `POST /api/documents/upload` (multipart) → Gateway → ecm-document
2. File streamed to MinIO at `ecm-documents/{year}/{month}/{uuid}/{filename}`
3. `Document` entity saved to `ecm_core.documents` with `status = PENDING_OCR`
4. `OcrRequestEvent` published → `ecm.document` exchange → `ocr.request` queue → ecm-ocr
5. `DocumentUploadedEvent` published → `document.uploaded` queue → ecm-workflow auto-start

**Document status lifecycle:**
```
PENDING_OCR → OCR_COMPLETED → ACTIVE → ARCHIVED → PURGED
```

**MinIO path format:** `{bucket}/{year}/{month}/{uuid}/{original-filename}`
Example: `ecm-documents/2026/03/550e8400-e29b-41d4-a716-446655440000/mortgage-application.pdf`

---

### `ecm-gateway` — Port 8080

Single entry point. All external traffic passes through here.

```
src/main/java/com/ecm/gateway/
├── config/
│   ├── GatewaySecurityConfig.java  ← JWT validation + CORS (sole owner)
│   ├── RouteConfig.java            ← All route definitions
│   ├── RateLimiterConfig.java      ← Redis-backed per-user rate limiting
│   └── KeyResolverConfig.java      ← Extracts user subject from JWT for rate key
├── filter/
│   ├── CorrelationIdFilter.java    ← Stamps X-Correlation-Id on every request
│   ├── RequestLoggingFilter.java   ← Structured request/response logging
│   └── SecurityHeadersFilter.java ← X-Frame-Options, X-Content-Type-Options, etc.
└── fallback/
    └── FallbackController.java     ← Circuit-breaker fallback (returns 503 JSON)
```

**Route table:**

| Path Prefix | Downstream Service | Circuit Breaker | Timeout |
|---|---|---|---|
| `/api/auth/**` | ecm-identity :8081 | `identity-cb` | 10s |
| `/api/users/**` | ecm-identity :8081 | `identity-cb` | 10s |
| `/api/documents/**` | ecm-document :8082 | `document-cb` | 60s |
| `/api/search/**` | ecm-document :8082 | `document-cb` | 30s |
| `/api/workflow/**` | ecm-workflow :8083 | `workflow-cb` | 15s |
| `/api/forms/**` | ecm-eforms :8084 | `eforms-cb` | 15s |
| `/api/admin/**` | ecm-admin :8086 | `admin-cb` | 15s |

**Circuit breaker config (all instances share these defaults):**
- Opens after **50% failure rate** over last 10 calls
- Waits **30 seconds** before attempting half-open
- Allows **2 test calls** in half-open before deciding to close or reopen
- Counts `IOException`, `TimeoutException`, and `NotFoundException` as failures

**Rate limiting:** Per-user token bucket backed by Redis. Key = JWT `sub` claim. Returns HTTP `429` when limit exceeded.

---

### `ecm-workflow` — Port 8083

Document workflow automation using Flowable BPM engine.

```
src/main/java/com/ecm/workflow/
├── config/
│   ├── FlowableListenersConfig.java   ← Flowable event listeners
│   └── WorkflowRabbitConfig.java
├── controller/
│   ├── WorkflowInstanceController.java  ← Start, list, get, cancel instances
│   ├── WorkflowTaskController.java      ← Task inbox, complete task (approve/reject/info)
│   ├── WorkflowDefinitionController.java ← Admin: CRUD workflow definitions
│   ├── WorkflowTemplateController.java  ← Template DSL management
│   └── WorkflowSlaController.java       ← SLA status and breach reports
├── listener/
│   └── DocumentUploadedListener.java   ← Consumes document.uploaded → auto-starts workflow
├── job/
│   └── SlaBreachDetectionJob.java      ← @Scheduled every 10 min — detects overdue workflows
├── service/
│   ├── WorkflowInstanceService.java    ← Start/complete/cancel process instances
│   ├── EcmTaskService.java             ← Task inbox, complete task with decision
│   ├── WorkflowTemplateService.java    ← Template CRUD + publish
│   ├── BpmnGeneratorService.java       ← JSON DSL → BPMN XML generation
│   ├── FlowableDeploymentService.java  ← Deploy BPMN to Flowable engine
│   ├── TemplateResolverService.java    ← Resolve template by (product, category) hierarchy
│   ├── WorkflowAdminService.java       ← Groups, members, category mappings
│   └── WorkflowSlaService.java        ← SLA tracking CRUD + escalation
└── resources/processes/
    ├── document-single-review.bpmn20.xml  ← 1-step backoffice review
    └── document-dual-review.bpmn20.xml    ← 2-step: triage + underwriter
```

**Task decisions:**

| Decision | `TaskActionRequest.decision` | Effect |
|---|---|---|
| Approve | `APPROVED` | Task complete; if final step → `COMPLETED_APPROVED` |
| Reject | `REJECTED` | Requires comment; workflow → `COMPLETED_REJECTED` |
| Request info | `REQUEST_INFO` | Workflow → `INFO_REQUESTED`; submitter fills inline form |
| Pass (triage) | `PASS` | Forward to next step without decision |

**Workflow instance status values:** `ACTIVE` · `INFO_REQUESTED` · `COMPLETED_APPROVED` · `COMPLETED_REJECTED` · `CANCELLED`

---

### `ecm-eforms` — Port 8084

Low-code electronic forms engine. Most feature-rich module.

```
src/main/java/com/ecm/eforms/
├── controller/
│   ├── FormDefinitionController.java  ← CRUD form definitions (admin/designer)
│   ├── FormDesignerController.java    ← Designer-specific: save draft, publish
│   ├── FormRenderController.java      ← Render published form for fill
│   ├── FormSubmissionController.java  ← Submit, save draft, review queue
│   └── DocuSignWebhookController.java ← HMAC-validated inbound DocuSign events
├── model/
│   ├── entity/
│   │   ├── FormDefinition.java       ← Maps to ecm_forms.form_definitions
│   │   ├── FormSubmission.java       ← Maps to ecm_forms.form_submissions
│   │   └── DocuSignEvent.java        ← Maps to ecm_forms.docusign_events
│   └── schema/                       ← JSONB schema POJOs
│       ├── FormSchema.java           ← Root: sections + globalRules + layout
│       ├── FormSection.java          ← Section: id, title, page, fields[]
│       ├── FormField.java            ← Field: type, label, key, validation, options, rules
│       ├── FieldType.java            ← Enum of all supported field types
│       ├── RuleDsl.java              ← Conditional visibility/required rule DSL
│       └── WorkflowConfig.java       ← Which workflow to auto-start on submit
└── service/
    ├── FormDefinitionService.java     ← CRUD + publish lifecycle
    ├── FormSubmissionService.java     ← Submit, validate, store, trigger workflow
    ├── FormValidationService.java     ← Server-side schema validation
    ├── FormVersioningService.java     ← Enforce one PUBLISHED per (tenant, form_key)
    ├── RuleEngineService.java         ← Server-side rule evaluation
    ├── PdfGenerationService.java      ← Generate PDF from submission data (PDFBox)
    └── DocuSignService.java           ← Envelope creation + HMAC webhook validation
```

**Supported field types:**

`TEXT_INPUT` · `TEXT_AREA` · `NUMBER` · `EMAIL` · `PHONE` · `DATE` · `DROPDOWN` · `OPTION_BUTTON` · `CHECKBOX` · `CHECKBOX_GROUP` · `SECTION_HEADER` · `PARAGRAPH` · `DIVIDER`

**Form definition lifecycle:** `DRAFT` → `PUBLISHED` → `ARCHIVED` → `DEPRECATED`

**Form submission lifecycle:** `DRAFT` → `SUBMITTED` → `PENDING_SIGNATURE` → `SIGNED` → `IN_REVIEW` → `APPROVED` / `REJECTED` → `COMPLETED`

---

### `ecm-admin` — Port 8086

Platform administration, tenant configuration, and product catalogue.

```
src/main/java/com/ecm/admin/
├── controller/
│   ├── UserAdminController.java        ← List users, activate, assign roles
│   ├── DepartmentController.java       ← CRUD departments (hierarchy)
│   ├── DocumentCategoryController.java ← CRUD categories (hierarchy)
│   ├── ProductController.java          ← CRUD products + product lines
│   ├── RetentionPolicyController.java  ← Retention policy management
│   ├── SystemConfigController.java     ← Tenant branding config
│   ├── HierarchyController.java        ← Product line → product → category tree
│   └── AuditLogController.java         ← Audit log search and export
└── service/
    ├── UserAdminService.java           ← Uses JdbcTemplate for cross-schema role writes
    ├── DepartmentService.java
    ├── DocumentCategoryService.java
    ├── ProductService.java
    ├── RetentionPolicyService.java
    ├── SystemConfigService.java
    ├── HierarchyService.java
    └── WorkflowClient.java             ← REST client to ecm-workflow (graceful degradation)
```

> **Cross-schema write pattern**: `UserAdminService` writes to `ecm_core.user_roles` using `JdbcTemplate`, not JPA. The `ecm_core` schema is owned by `ecm-identity`'s Flyway — writing via JPA from `ecm-admin` would bypass migration tracking.

---

### `ecm-ocr` — Port 8087

Asynchronous OCR worker. Not exposed to the frontend. Triggered entirely by RabbitMQ.

```
src/main/java/com/ecm/ocr/
├── listener/
│   └── OcrEventListener.java      ← Consumes ecm.ocr.request queue
├── engine/
│   ├── OcrEngine.java             ← Interface
│   └── TikaOcrEngine.java         ← Tika 3.x + Tesseract implementation
├── pipeline/
│   ├── OcrPipelineService.java    ← Orchestrates: fetch → extract → write-back → index
│   ├── FieldExtractorService.java ← Template-based structured field extraction
│   └── ExtractionTemplate.java   ← Template definition for field extraction
└── service/
    ├── MinioFetchService.java     ← Downloads document from MinIO
    ├── DocumentWritebackService.java ← Updates ecm_core.documents with OCR results
    └── DocumentIndexService.java  ← Publishes extracted text to OpenSearch
```

**OCR pipeline steps:**
1. Receive `OcrRequestEvent` from RabbitMQ (contains `documentId`, `mimeType`, `storagePath`)
2. Fetch binary from MinIO
3. Run Tika auto-detection + Tesseract OCR (configurable: enable/disable Tesseract via `ecm.ocr.tesseract-enabled`)
4. Run `FieldExtractorService` against matching `ExtractionTemplate` for structured fields
5. Write `extracted_text`, `extracted_fields`, `ocr_completed=true`, `status=OCR_COMPLETED` back to PostgreSQL
6. Index extracted text to OpenSearch
7. Publish `OcrCompletedEvent` to notify ecm-document and ecm-workflow

---

## Infrastructure Services

### PostgreSQL — Schema Layout

```
ecmdb
├── ecm_core        ← Shared domain: users, roles, departments, documents, document_categories
├── ecm_audit       ← Immutable audit trail (append-only, partitioned by month)
├── ecm_admin       ← Products, categories (enriched), retention policies, tenant config
│                     + departments VIEW over ecm_core.departments
├── ecm_workflow    ← Workflow groups, definitions, templates, instances, SLA tracking
└── ecm_forms       ← Form definitions (JSONB schema), submissions, DocuSign events
```

Cross-schema references use **soft foreign keys** (integer IDs / UUIDs with no `REFERENCES` constraint) where the referenced table is in another schema. This avoids cross-schema FK dependency chains that would complicate migrations and deployments.

### Redis — Cache & Rate Limiting

| Use | Key Pattern | TTL | Eviction |
|---|---|---|---|
| User entity cache | `users::<entraObjectId>` | 10 min | Manual evict on deactivate |
| Session DTO cache | `sessions::<subject>` | 5 min | TTL expiry |
| Rate limit counters | `gateway:rl:<subject>` | Sliding window | Automatic |

### MinIO — Bucket Layout

| Bucket | Purpose |
|---|---|
| `ecm-documents` | All active uploaded documents |
| `ecm-temp` | In-flight uploads awaiting full processing |
| `ecm-templates` | Base PDF templates for eForm PDF generation |
| `ecm-archive` | Documents past `archive_after_days` retention threshold |

### RabbitMQ — Exchange & Queue Topology

```
Exchange: ecm.document  (topic, durable)
  ├── Routing key: ocr.request       → Queue: ecm.ocr.request
  │                                    Consumer: ecm-ocr
  └── Routing key: document.uploaded → Queue: ecm.workflow.document-uploaded
                                       Consumer: ecm-workflow

Exchange: ecm.ocr (topic, durable)
  └── Routing key: ocr.completed    → Queue: ecm.document.ocr-completed
                                      Consumer: ecm-document

Dead-letter exchange: ecm.dlx  (direct, durable)
  └── All failed messages after retry exhaustion → ecm.dead-letter queue
```

---

## Database Schema

### ecm_core.documents — Full Column Reference

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID PK` | `gen_random_uuid()`. Used in MinIO path and all cross-service references. |
| `name` | `VARCHAR(500)` | Display name. Defaults to `original_filename` if not provided. |
| `original_filename` | `VARCHAR(500)` | Filename as uploaded. Preserved for audit. |
| `mime_type` | `VARCHAR(100)` | e.g. `application/pdf`, `image/jpeg` |
| `file_size_bytes` | `BIGINT` | For quota management and display. |
| `blob_storage_path` | `VARCHAR(1000)` | Full MinIO/Azure path: `{bucket}/{year}/{month}/{uuid}/{filename}` |
| `category_id` | `INTEGER FK` | → `ecm_core.document_categories.id`. Determines workflow template. |
| `department_id` | `INTEGER FK` | → `ecm_core.departments.id`. Access scoping. |
| `uploaded_by` | `INTEGER FK` | → `ecm_core.users.id` |
| `uploaded_by_email` | `VARCHAR(255)` | Denormalised to avoid JOIN on list queries. |
| `status` | `VARCHAR(50)` | `PENDING_OCR` → `OCR_COMPLETED` → `ACTIVE` → `ARCHIVED` |
| `version` | `INTEGER` | Starts at 1. Incremented on re-upload. |
| `parent_doc_id` | `UUID FK` | Self-referential. Points to previous version. |
| `is_latest_version` | `BOOLEAN` | Only the current version is `TRUE`. |
| `ocr_completed` | `BOOLEAN` | Set by ecm-ocr after processing. |
| `extracted_text` | `TEXT` | Raw OCR output. Indexed to OpenSearch. |
| `extracted_fields` | `JSONB` | Structured fields from template-based extraction. |
| `metadata` | `JSONB` | Arbitrary extra metadata. |
| `tags` | `TEXT[]` | Searchable tags. |
| `segment_id` | `INTEGER` | Soft ref → `ecm_admin.segments.id` *(column to be added — see Known Issues)* |
| `product_line_id` | `INTEGER` | Soft ref → `ecm_admin.product_lines.id` *(column to be added — see Known Issues)* |

### ecm_forms.form_definitions — Key Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID PK` | |
| `tenant_id` | `VARCHAR(100)` | Multi-tenancy. Default `'default'`. |
| `form_key` | `VARCHAR(200)` | URL-safe identifier. e.g. `mortgage-application` |
| `version` | `INTEGER` | One `PUBLISHED` version per `(tenant_id, form_key)` enforced by partial unique index. |
| `status` | `VARCHAR(50)` | `DRAFT` → `PUBLISHED` → `ARCHIVED` → `DEPRECATED` |
| `schema` | `JSONB` | Full `FormSchema` DSL: sections, fields, global rules, layout. |
| `workflow_config` | `JSONB` | Which workflow to trigger on submit. |
| `docusign_config` | `JSONB` | DocuSign envelope configuration. |

---

## Security & Authentication

### JWT Flow

```
Browser → Okta/Entra ID login
       ← Authorization code (PKCE)
Browser → Okta token endpoint
       ← Access token (JWT) + ID token
Browser → Gateway (Bearer: <JWT>)
Gateway validates: signature + issuer + audience
Gateway → Downstream service (forwards JWT)
Downstream: EcmJwtConverter extracts roles from `groups` claim
```

### Roles

| Role | Assigned in | Access |
|---|---|---|
| `ECM_ADMIN` | Okta group | Full admin, all modules, tenant config |
| `ECM_DESIGNER` | Okta group | Form designer, create/publish forms |
| `ECM_BACKOFFICE` | Okta group | Documents, workflow task inbox |
| `ECM_REVIEWER` | Okta group | Workflow inbox, eForm review queue |
| `ECM_READONLY` | Okta group | Read-only document access |

Roles are assigned in Okta, not in the ECM database. The ECM `user_roles` table is a **display copy** only. Access decisions are always made from the live JWT claim.

### Method-Level Security Example

```java
@PreAuthorize("hasRole('ECM_ADMIN')")
public ApiResponse<List<UserDto>> listAllUsers() { ... }

@PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
public ApiResponse<List<TaskDto>> getMyTasks() { ... }
```

### Audit Logging

Every `@AuditLog`-annotated method is intercepted by `AuditAspect`:
- Captures: `entraObjectId`, `email`, `sessionId` (Okta `sid` claim), IP address, User-Agent
- Runs the target method
- Records `SUCCESS` or `FAILURE` with payload to `ecm_audit.audit_log` (async, non-blocking)

**Critical**: `@AuditLog` must be the **outer** annotation, `@Transactional` the **inner**. A DB rollback then correctly produces `FAILURE`, not a false `SUCCESS`.

---

## Message Flows (RabbitMQ)

### Document Upload → OCR → Workflow

```
1. POST /api/documents/upload
   └─ DocumentServiceImpl
       ├─ Store in MinIO
       ├─ Save Document entity (status=PENDING_OCR)
       ├─ Publish OcrRequestEvent ──► ecm.document / ocr.request
       └─ Publish DocumentUploadedEvent ──► ecm.document / document.uploaded

2. ecm-ocr (OcrEventListener)
   ├─ Fetch from MinIO
   ├─ Run Tika + Tesseract
   ├─ Write extracted_text + fields back to PostgreSQL
   ├─ Index to OpenSearch
   └─ Publish OcrCompletedEvent ──► ecm.ocr / ocr.completed

3. ecm-workflow (DocumentUploadedListener)
   ├─ Resolve WorkflowTemplate by (productId, categoryId)
   ├─ Start Flowable process instance
   ├─ Create WorkflowInstanceRecord (status=ACTIVE)
   └─ Create WorkflowSlaTracking record
```

---

## API Reference

### Standard Response Envelope

All endpoints return:

```json
{
  "success": true,
  "data": { ... },
  "message": "Operation successful"
}
```

On error:
```json
{
  "success": false,
  "data": null,
  "message": "Validation failed: name must not be blank"
}
```

### Core Endpoints Summary

#### ecm-identity (:8081)
```
GET    /api/auth/me
GET    /api/auth/ping
POST   /api/auth/logout
GET    /api/users                        [ECM_ADMIN]
GET    /api/users/{id}                   [ECM_ADMIN]
GET    /api/users/me/profile
```

#### ecm-document (:8082)
```
POST   /api/documents/upload             multipart/form-data
GET    /api/documents                    ?page=0&size=20&categoryId=&status=
GET    /api/documents/{id}
GET    /api/documents/{id}/download
DELETE /api/documents/{id}              soft delete (status=DELETED)
POST   /api/search                       { "query": "...", "categoryId": null }
```

#### ecm-workflow (:8083)
```
POST   /api/workflow/instances            { documentId, workflowDefinitionId }
GET    /api/workflow/instances            ?status=ACTIVE
GET    /api/workflow/instances/{id}
DELETE /api/workflow/instances/{id}       cancel
GET    /api/workflow/tasks/mine
POST   /api/workflow/tasks/{taskId}/action { decision, comment }
POST   /api/workflow/instances/{id}/provide-info { comment }
GET    /api/workflow/definitions
GET    /api/workflow/templates
POST   /api/workflow/templates/{id}/publish  [ECM_ADMIN]
GET    /api/workflow/sla                  SLA status report
```

#### ecm-eforms (:8084)
```
GET    /api/forms/definitions             ?status=PUBLISHED
GET    /api/forms/definitions/{id}
POST   /api/forms/definitions             [ECM_ADMIN, ECM_DESIGNER]
PUT    /api/forms/definitions/{id}        [ECM_ADMIN, ECM_DESIGNER]
POST   /api/forms/definitions/{id}/publish [ECM_ADMIN, ECM_DESIGNER]
GET    /api/forms/render/{formKey}         public form render
POST   /api/forms/submissions/{formKey}    submit form
GET    /api/forms/submissions/mine
GET    /api/forms/submissions/queue       [ECM_ADMIN, ECM_BACKOFFICE, ECM_REVIEWER]
POST   /api/forms/submissions/{id}/review [ECM_ADMIN, ECM_BACKOFFICE, ECM_REVIEWER]
POST   /api/webhooks/docusign             HMAC validated
```

#### ecm-admin (:8086)
```
GET    /api/admin/users
PUT    /api/admin/users/{id}/roles
PUT    /api/admin/users/{id}/activate
PUT    /api/admin/users/{id}/deactivate
CRUD   /api/admin/departments
CRUD   /api/admin/categories
CRUD   /api/admin/products
CRUD   /api/admin/product-lines
CRUD   /api/admin/segments
CRUD   /api/admin/retention-policies
GET    /api/admin/config/tenant
PUT    /api/admin/config/tenant
GET    /api/admin/audit                   ?eventType=&userId=&from=&to=
```

---

## Local Development Setup

### Prerequisites

- Docker Desktop 4.x or Docker Engine + Compose v2
- Java 21 (Amazon Corretto recommended)
- Maven 3.9+
- Okta Developer account (free) or Microsoft Entra ID tenant

### 1. Clone and start infrastructure

```bash
git clone <repo>
cd ecm-platform

# Start all infrastructure containers
docker compose up -d

# Verify all services are healthy
docker compose ps
```

Services started:
- PostgreSQL on `localhost:5432`
- Redis on `localhost:6379`
- MinIO API on `localhost:9000`, Console on `localhost:9001`
- RabbitMQ AMQP on `localhost:5672`, Management on `localhost:15672`
- OpenSearch on `localhost:9200`

### 2. Initialise the database

```bash
# Run once on a fresh database — creates all schemas and seeds reference data
psql -h localhost -U ecmuser -d ecmdb -f infrastructure/sql/init.sql
```

### 3. Configure Okta

Update `application.yml` in each service (or set environment variables):

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-okta-domain/oauth2/your-auth-server-id

okta:
  oauth2:
    audience: api://your-audience
```

Okta custom auth server must have a `groups` claim returning `ECM_ADMIN`, `ECM_BACKOFFICE`, `ECM_REVIEWER`, `ECM_READONLY`, `ECM_DESIGNER`.

### 4. Build the project

```bash
# From root — builds all modules
mvn clean package -DskipTests
```

### 5. Start services

```bash
# Option A: IntelliJ IDEA — run each Application class directly
# Option B: Terminal (each in its own tab)
cd ecm-identity && mvn spring-boot:run &
cd ecm-document && mvn spring-boot:run &
cd ecm-gateway  && mvn spring-boot:run &
cd ecm-workflow && mvn spring-boot:run &
cd ecm-eforms   && mvn spring-boot:run &
cd ecm-admin    && mvn spring-boot:run &
cd ecm-ocr      && mvn spring-boot:run &
```

### Service Start Order

Start in this order for clean initialisation:
`ecm-identity` → `ecm-document` → `ecm-ocr` → `ecm-workflow` → `ecm-eforms` → `ecm-admin` → `ecm-gateway`

### Useful Dev URLs

| Service | URL | Credentials |
|---|---|---|
| Gateway (API entry point) | http://localhost:8080 | — |
| MinIO Console | http://localhost:9001 | ecmminioadmin / ecmminio@password123 |
| RabbitMQ Management | http://localhost:15672 | ecmrabbit / ecmrabbitpassword |
| OpenSearch | http://localhost:9200 | No auth (dev mode) |
| Actuator health (any service) | http://localhost:808x/actuator/health | — |

---

## Environment Variables

All services support environment variable overrides for every `${...}` placeholder in `application.yml`:

| Variable | Default | Used by |
|---|---|---|
| `OKTA_ISSUER_URI` | `https://integrator-3023444.okta.com/...` | All services |
| `OKTA_AUDIENCE` | `api://sso-default` | All services |
| `DB_HOST` | `localhost` | All services |
| `DB_PORT` | `5432` | All services |
| `DB_NAME` | `ecmdb` | All services |
| `DB_USER` | `ecmuser` | All services |
| `DB_PASS` | `ecmpassword` | All services |
| `REDIS_HOST` | `localhost` | ecm-gateway, ecm-identity |
| `REDIS_PASS` | `ecmredispassword` | ecm-gateway, ecm-identity |
| `MINIO_URL` | `http://localhost:9000` | ecm-document, ecm-ocr |
| `MINIO_ACCESS_KEY` | `ecmminioadmin` | ecm-document, ecm-ocr |
| `MINIO_SECRET_KEY` | `ecmminio@password123` | ecm-document, ecm-ocr |
| `RABBITMQ_HOST` | `localhost` | ecm-document, ecm-workflow, ecm-ocr, ecm-eforms |
| `RABBITMQ_USER` | `ecmrabbit` | All messaging services |
| `RABBITMQ_PASS` | `ecmrabbitpassword` | All messaging services |
| `OPENSEARCH_HOST` | `localhost` | ecm-document, ecm-ocr |
| `CORS_ORIGINS` | `http://localhost:4200,http://localhost:3000` | ecm-gateway |
| `IDENTITY_SERVICE_URL` | `http://localhost:8081` | ecm-gateway |
| `DOCUMENT_SERVICE_URL` | `http://localhost:8082` | ecm-gateway |
| `WORKFLOW_SERVICE_URL` | `http://localhost:8083` | ecm-gateway |
| `EFORMS_SERVICE_URL` | `http://localhost:8084` | ecm-gateway |
| `ADMIN_SERVICE_URL` | `http://localhost:8086` | ecm-gateway |

---

## Flyway Migrations

Each module manages its own migrations in `src/main/resources/db/migration/`. Migration files follow `V{n}__{description}.sql` naming.

The `infrastructure/sql/init.sql` is the **master bootstrap script** for a fresh install. It:
1. Creates all 5 schemas
2. Creates all tables with correct final column set
3. Seeds reference data (roles, departments, document categories, tenant config, product types, form types)

> **Important:** After running `init.sql`, delete any existing `V*.sql` Flyway migration files in each module. Flyway will find no pending migrations and start cleanly. Future schema changes go into new migration files.

---

## Known Issues & Bugs

### 🔴 High Priority

**`segment_id` and `product_line_id` missing from `init.sql`**
`Document.java` maps `@Column(name = "segment_id")` and `@Column(name = "product_line_id")` but these columns are absent from the `ecm_core.documents` table definition in `init.sql`. Hibernate will throw on startup.

*Fix:* Add a new Flyway migration to `ecm-document`:
```sql
-- V1__add_segment_product_columns.sql
ALTER TABLE ecm_core.documents
  ADD COLUMN IF NOT EXISTS segment_id INTEGER,
  ADD COLUMN IF NOT EXISTS product_line_id INTEGER;
```

---

### 🟡 Medium Priority

**Duplicate `AuditLog` class names**
`com.ecm.common.annotation.AuditLog` (annotation) and `com.ecm.common.audit.AuditLog` (entity) share the same simple name. In service classes the wrong import can be silently used by IDEs. Rename one — e.g. the entity to `AuditEntry`.

**MinIO orphan on DB failure**
`DocumentServiceImpl.upload()` stores in MinIO first, then saves to PostgreSQL. If the DB save fails, the MinIO file is orphaned. A compensating cleanup job is noted in comments but not implemented.

**`ecm-notification` module referenced in frontend but not implemented**
`NotificationPreferencesPage.jsx` calls `/api/notifications/**` endpoints. The module is commented out in the root `pom.xml`. These API calls return 404.

**Dual `document_categories` tables with no sync**
`ecm_core.document_categories` and `ecm_admin.document_categories` are separate tables seeded with the same data. A category added in Admin never appears in the ecm-document category dropdown. No sync mechanism exists.

**Missing CHECK constraint on workflow instance status**
`workflow_instance_records.status` is `VARCHAR(30)` with no database-level constraint enforcing valid values. An invalid string can be persisted without rejection.

---

### 🟢 Low Priority

**OpenSearch has no authentication in dev mode**
Any network process can read/write/delete the entire search index. Enable the security plugin for staging and production environments.

**Rate limit key falls back silently if JWT `sub` missing**
`KeyResolverConfig` returns `Mono.empty()` if the subject claim is absent, which effectively disables rate limiting for that request. Add a fallback to the client IP address.

---

## Production Deployment

### Storage

Switch `spring.profiles.active` from `local` to `azure` to activate `AzureBlobDocumentStorageService` instead of MinIO.

### Recommended Azure services

| Dev (Docker) | Production (Azure) |
|---|---|
| PostgreSQL in Docker | Azure Database for PostgreSQL Flexible Server |
| Redis in Docker | Azure Cache for Redis |
| MinIO | Azure Blob Storage |
| RabbitMQ in Docker | Azure Service Bus |
| OpenSearch in Docker | Azure OpenAI (or keep OpenSearch on AKS) |

### Security checklist

- [ ] Rotate all default passwords from `docker-compose.yml`
- [ ] Enable OpenSearch security plugin with TLS + authentication
- [ ] Store all credentials in Azure Key Vault; reference via Spring Cloud Azure Config
- [ ] Set `CORS_ORIGINS` to production frontend domain only
- [ ] Restrict Actuator endpoints to internal network or authenticated callers
- [ ] Enable PostgreSQL SSL (`requiressl=true`)
- [ ] Add `CHECK` constraints on status columns
- [ ] Enable Flyway `validate-on-migrate: true` in production

### Kubernetes / AKS

Each service packages as a Docker image with:
- `ENTRYPOINT ["java", "-jar", "app.jar"]`
- Liveness probe: `GET /actuator/health/liveness`
- Readiness probe: `GET /actuator/health/readiness`
- Resource requests: 256Mi RAM / 0.25 CPU (minimum; scale based on load)

---

*Servus ECM Platform — Backend | Java 21 · Spring Boot 3.3.5 | © 2026*