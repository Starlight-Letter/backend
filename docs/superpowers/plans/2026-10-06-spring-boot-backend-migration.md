# Spring Boot Backend Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Node.js backend with a Java 17 Spring Boot service while preserving the current PostgreSQL schema and `/api/v1` client contract.

**Architecture:** Spring MVC controllers keep the HTTP contract, application services own transaction boundaries and policies, and JDBC repositories access the existing PostgreSQL tables. Flyway provisions new databases and baselines databases already created by the Node service. The React client remains unchanged.

**Tech Stack:** Java 17, Spring Boot 3, Gradle Wrapper, Spring Web, JDBC, Validation, Flyway, PostgreSQL JDBC, Spring Security Crypto, Argon2, JUnit 5, Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-10-06-spring-boot-backend-migration-design.md`

## Global Constraints

- Keep `/api/v1` paths, `{ data }` and `{ error: { code, message } }` JSON envelopes, cookies, CSRF fields and Korean messages stable for the React client.
- Keep the existing PostgreSQL tables and data; baseline Node-created databases at Flyway version 14.
- Use Java 17 and Spring Boot 3 only. Do not retain Node.js source, scripts, dependencies, or runtime files after the final task.
- Keep 16 KiB request limits, allowlisted CORS with credentials, `x-request-id`, `cache-control: no-store`, KST weekly-question logic, and role boundaries.
- Add a failing focused test before every new production class or changed behavior.

## Review Focus

- An existing Node-created database must not rerun its schema migrations or lose records when Flyway starts.
- A request with an allowed Origin but a missing or wrong CSRF token must not change member or operator state.
- A fifth concurrent distinct report must hide one letter exactly once; duplicate reports must not raise the count.
- Existing Argon2 password hashes and active session cookies must work after the service swap.
- A question whose effective date lies at the Korea Monday boundary must select the same current question as before.

---

### Task 1: Bootstrap the Spring Boot project and database migration compatibility

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradlew`, `gradle/wrapper/*`
- Create: `src/main/java/com/starlight/greetings/StarlightApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V001__bridges_questions.sql` through `V014__stable_csrf_token.sql`
- Create: `src/test/java/com/starlight/greetings/FlywayMigrationIntegrationTest.java`
- Delete later: `package.json`, `package-lock.json`, `src/db/migrations.ts`, `src/db/migrate-cli.ts`, `migrations/*.sql`

**Interfaces:**
- Produces: a Gradle `bootRun` application on port 3000 and a Flyway-managed `DataSource`.

- [ ] Write a failing Testcontainers test that migrates an empty database and asserts three bridges, then starts Flyway with an existing `schema_migrations` table and asserts no migration is rerun.
- [ ] Run `./gradlew test --tests FlywayMigrationIntegrationTest` and confirm it fails because the project is absent.
- [ ] Add Gradle Wrapper, Java 17 Spring Boot dependencies, datasource configuration, and the Flyway migration files; configure baseline version `14` for existing schemas.
- [ ] Run the focused test and then `./gradlew test`; confirm both pass.
- [ ] Commit: `git commit -m "feat: bootstrap Spring Boot database migrations"`.

### Task 2: Establish API envelopes, CORS, cookies, errors, and request limits

**Files:**
- Create: `src/main/java/com/starlight/greetings/web/ApiEnvelope.java`
- Create: `src/main/java/com/starlight/greetings/web/ApiExceptionHandler.java`
- Create: `src/main/java/com/starlight/greetings/web/RequestContextFilter.java`
- Create: `src/main/java/com/starlight/greetings/web/WebConfiguration.java`
- Create: `src/main/java/com/starlight/greetings/web/HealthController.java`
- Test: `src/test/java/com/starlight/greetings/web/ApiContractTest.java`

**Interfaces:**
- Produces: `GET /api/v1/health -> {data:{status:"ok"}}`, error envelopes, request IDs, CORS preflight, secure or development cookie naming.
- Consumes: Task 1 `DataSource`.

- [ ] Write failing MockMvc tests for health, denied Origin, allowed preflight, invalid JSON, and a payload larger than 16 KiB.
- [ ] Run the focused test and confirm missing controller/filter failures.
- [ ] Implement the controller, filter, exception handler, max request configuration, and environment-specific cookie utility.
- [ ] Run focused and complete tests; verify headers and status codes exactly.
- [ ] Commit: `git commit -m "feat: add Spring API contract foundation"`.

### Task 3: Implement bridges and weekly-question reads

**Files:**
- Create: `domain/questions/QuestionSelector.java`
- Create: `infrastructure/bridge/JdbcBridgeRepository.java`
- Create: `application/BridgeQueryService.java`
- Create: `web/BridgeController.java`
- Test: `QuestionSelectorTest.java`, `BridgeControllerIntegrationTest.java`

**Interfaces:**
- Produces: `GET /bridges`, `GET /bridges/{bridgeId}`, and KST-compatible `BridgeResponse` with nullable `currentQuestion`.

- [ ] Write failing tests for display ordering, unknown bridge 404, Monday KST boundary, and previous-question fallback.
- [ ] Run the focused tests and confirm expected failures.
- [ ] Implement repository queries and `QuestionSelector.currentQuestion(Instant now, List<Question>)`.
- [ ] Run focused and complete tests.
- [ ] Commit: `git commit -m "feat: migrate bridge question API"`.

### Task 4: Implement member authentication and session security

**Files:**
- Create: `domain/auth/*`, `infrastructure/auth/JdbcAuthRepository.java`, `application/AuthService.java`
- Create: `web/AuthController.java`, `web/SessionResolver.java`
- Test: `AuthIntegrationTest.java`, `AuthControllerTest.java`

**Interfaces:**
- Produces: signup, login, recovery, logout, current-session, recovery-code rotation, password change, withdrawal, and admin TOTP login endpoints.
- Consumes: Task 2 cookie, Origin, CSRF and error contract.

- [ ] Write failing integration tests for compatible Argon2 verification, one-time recovery, rate limit, session revocation, nickname reuse after withdrawal, CSRF denial, and admin TOTP expiry.
- [ ] Run the focused tests and confirm authentication functionality is absent.
- [ ] Implement password/TOTP codecs, token hashing, JDBC persistence, authorization and cookie responses.
- [ ] Run focused and complete tests.
- [ ] Commit: `git commit -m "feat: migrate account and session API"`.

### Task 5: Implement letters, moderation, and public reading

**Files:**
- Create: `domain/letters/*`, `infrastructure/letter/JdbcLetterRepository.java`, `application/LetterService.java`
- Create: `web/LetterController.java`
- Test: `LetterIntegrationTest.java`, `LetterControllerTest.java`

**Interfaces:**
- Produces: river, detail, write and best-letter endpoints with existing letter JSON fields.
- Consumes: Task 3 bridge question lookup and Task 4 member identity.

- [ ] Write failing tests for current-question-only writing, 150-character limit, idempotency key reuse, content-rule rejection, seed/member mixing, and detail visibility.
- [ ] Run focused tests and confirm the endpoints are absent.
- [ ] Implement letter policy, moderation rule lookup, JDBC transactions, and response mapping.
- [ ] Run focused and complete tests.
- [ ] Commit: `git commit -m "feat: migrate letter APIs"`.

### Task 6: Implement reactions, saves, reports, and best-letter aggregation

**Files:**
- Create: `application/EngagementService.java`, `application/ReportService.java`
- Create: `infrastructure/engagement/*`, `web/EngagementController.java`, `web/ReportController.java`
- Test: `EngagementIntegrationTest.java`, `ReportIntegrationTest.java`

**Interfaces:**
- Produces: engagement state, reaction/save toggles, saved list, best lists, and report creation endpoints.
- Consumes: Task 4 member identity and Task 5 letters.

- [ ] Write failing tests for toggle idempotence, saved hidden-letter placeholder, KST weekly/monthly windows, withdrawal cleanup, duplicate reporting, and two concurrent fifth reports.
- [ ] Run focused tests and confirm failures.
- [ ] Implement unique-record SQL, transactional report counting and hide transition.
- [ ] Run focused and complete tests.
- [ ] Commit: `git commit -m "feat: migrate engagement and report APIs"`.

### Task 7: Implement inquiries, surveys, and anonymous metrics

**Files:**
- Create: `application/InquiryService.java`, `application/MetricsService.java`, `application/SurveyService.java`
- Create: `infrastructure/inquiry/*`, `infrastructure/metrics/*`, `web/InquiryController.java`, `web/EventController.java`, `web/SurveyController.java`
- Test: `InquiryIntegrationTest.java`, `MetricsIntegrationTest.java`, `SurveyIntegrationTest.java`

**Interfaces:**
- Produces: inquiry, event, survey-read and survey-click routes with the established anonymous throttling behavior.

- [ ] Write failing tests for anonymous inquiry throttling, owner-removal requirements, duplicate event IDs, campaign date limits, and aggregate-only metric results.
- [ ] Run focused tests and confirm failures.
- [ ] Implement services, JDBC stores and controllers.
- [ ] Run focused and complete tests.
- [ ] Commit: `git commit -m "feat: migrate inquiry survey and metrics APIs"`.

### Task 8: Implement all operator workflows

**Files:**
- Create: `application/AdminService.java`, `infrastructure/admin/JdbcAdminRepository.java`, `web/AdminController.java`
- Test: `AdminIntegrationTest.java`, `AdminControllerTest.java`

**Interfaces:**
- Produces: operator question, seed letter, report review, direct letter search/review, inquiry resolution, moderation rule, survey configuration, metric and alert routes.
- Consumes: Tasks 3–7 services and Task 4 admin authorization.

- [ ] Write failing tests for immediate/scheduled Monday questions, seed hide/update, review restore/delete audit records, direct search, inquiry state transition, rule activation, and admin-only access.
- [ ] Run focused tests and confirm failures.
- [ ] Implement the operator services, repository transactions and endpoint mapping.
- [ ] Run focused and complete tests.
- [ ] Commit: `git commit -m "feat: migrate operator workflows"`.

### Task 9: Verify React-to-Spring compatibility and local operations

**Files:**
- Create: `docker-compose.test.yml` or adapt it for Gradle Testcontainers use
- Create: `scripts/seed-local-letters.sql`
- Modify: `README.md`
- Test: `src/test/java/com/starlight/greetings/ApiParityIntegrationTest.java`

**Interfaces:**
- Produces: documented `./gradlew bootRun`, `./gradlew test`, database migration and local seed workflow.

- [ ] Write a failing parity test covering the React bootstrap calls (`/me`, bridge, river, letter detail, best, saved, survey).
- [ ] Run the focused test and confirm any remaining mismatch.
- [ ] Configure local environment defaults, Docker test database, starter content script and README commands.
- [ ] Run `./gradlew test`, `./gradlew bootJar`, frontend tests, frontend production build, then manually test signup, writing, reaction, save, report and operator login against port 3000.
- [ ] Commit: `git commit -m "docs: add Spring Boot local operation guide"`.

### Task 10: Remove the Node.js implementation

**Files:**
- Delete: `package.json`, `package-lock.json`, `tsconfig.json`, `src/**/*.ts`, `migrations/`, Node-only scripts and tests
- Modify: `.gitignore`, `README.md`

**Interfaces:**
- Produces: a Java-only backend repository with no Node runtime path.

- [ ] Write a repository-layout assertion or Gradle verification task that requires `build.gradle`, Java source, migration resources, and rejects `package.json` and TypeScript source files.
- [ ] Run it and confirm it fails while Node artifacts exist.
- [ ] Remove Node artifacts only after Tasks 1–9 are green; update ignore rules and README references.
- [ ] Run `./gradlew test` and `./gradlew bootJar`; repeat the React smoke flow against the Spring server.
- [ ] Commit: `git commit -m "refactor: remove Node backend implementation"`.

## Plan self-review

- Each design requirement is covered: schema compatibility (Task 1), API contract (Task 2), all public and operator functions (Tasks 3–8), client compatibility (Task 9), and Node removal (Task 10).
- Review-focus failure modes are tested in Tasks 1, 3, 4 and 6.
- The plan uses a single Java naming scheme and passes only DTOs or service results between layers.
- The implementation is split by independently testable API domains; no task introduces unrelated product behavior.
