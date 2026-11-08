# Evidence-Led v2 Authoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let creators generate reviewable v2 behavior from scoped evidence, separately verify engineering behavior and device consistency, and enable engineering-only results for creator demo runs.

**Architecture:** Java persists immutable baseline snapshots and binds proposals/reports to project, package, API draft, evidence and runtime hashes. Worker generates a constrained v2 proposal and replays approved scenarios against the isolated simulator; Vue presents rule-level adoption and separate validation conclusions. Existing interpreter, run origins, leases and creator authentication remain the execution boundary.

**Tech Stack:** Java 8, Spring Boot 2.7, H2/JUnit; Node.js, Playwright, Service Worker/IndexedDB; Vue 3, TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-08-evidence-led-v2-authoring-design.md`

## Global Constraints

- AI calls occur only during authoring, never simulator execution; no model-generated executable script.
- Real-device writes remain individually approved; differential validation never silently writes to a real device.
- Demo engineering pass is not device consistency; `DEMO_ENABLED` permits authenticated creator trial only, not anonymous/learner use.
- No historical version migration/reclassification or local data cleanup in this test-environment phase. Preserve unrelated uncommitted changes and stage only task files.
- Missing, truncated or redacted request values cannot become deterministic state inputs. Unknown APIs and external network requests fail closed.
- Package, behavior, baseline/comparison and runtime hashes bind reports; changed inputs require a new report.

## Review Focus

1. A baseline referring to another project/session/package must be rejected, including a valid observation ID copied from elsewhere (Task 1 test).
2. A model output claiming `CAPTURED` with an unapproved ID or a claim unsupported by the selected observation must not gain captured status (Task 2 test).
3. Switching project/package/API draft while an AI request is in flight must not display or save its stale result (Task 3 test).
4. A browser-visible checkpoint that fails while HTTP assertions pass must prevent a consistency `PASSED` result (Task 4 test).
5. A report with `NOT_TESTED` consistency may authorize only `DEMO_ENABLED`, and no client-selected status can upgrade it (Task 5 test).

## Files and Responsibilities

- `AiBaselineEntity/Repository/Service` persist immutable, scoped scenarios and compare policies; `AiWorkbenchController` exposes authoring operations.
- `ai-worker/src/v2-proposal.js` contains provider request, response normalization and evidence checks; `server.js` wires the internal route only.
- `web-admin/src/views/ai-workbench/V2ProposalReview.vue` owns proposal diff/adoption; `AiWorkbenchView.vue` owns project context and navigation; `ai-workbench.ts` types requests.
- `ai-worker/src/device-differential.js` compares frozen observations and page checkpoints with replay output; `simulator-validator.js` runs the isolated browser replay.
- `AiValidationService` persists distinct conclusions and hashes; `AiSimulatorVersionEntity`, `AiRunService` and the two Vue shells display/enforce demo scope.

---

### Task 1: Freeze an Authorized Baseline

**Files:** Create `skilllab-admin/src/main/java/com/skilllab/admin/ai/AiBaselineEntity.java`, `AiBaselineRepository.java`, `AiBaselineService.java`, `skilllab-admin/src/test/java/com/skilllab/admin/ai/AiBaselineServiceTest.java`; modify `AiWorkbenchController.java`, `web-admin/src/api/ai-workbench.ts` and the capture section of `AiWorkbenchView.vue`.

**Interfaces:** Produce `createBaseline(projectId, packageId, apiDraftId, sessionId, scenario, creatorId): BaselineView` and `getBaseline(projectId, baselineId, creatorId): BaselineView`. Scenario contains ordered approved observation IDs, declared action/checkpoint steps, comparison policies (`EXACT`, `TYPE`, `RANGE`, `STABLE_MAP`, `IGNORE`) and creator-confirmed expected values; view includes immutable `snapshotSha256` and provenance. No raw secrets or arbitrary script selectors.

- [ ] Write JUnit tests `freezesOrderedScopedEvidence`, `rejectsForeignObservationAndPackage`, `rejectsRedactedOrTruncatedCriticalBody`, `rejectsUnapprovedIgnoreAndUnsafeCheckpoint`; assert frozen snapshot/hash and no persisted row on failure.
- [ ] Run `mvn -q -Dtest=AiBaselineServiceTest test` in `skilllab-admin`; expected missing service/test behavior fails.
- [ ] Implement entity/service/controller and typed API; capture UI selects existing persisted observations and declares restrained URL/text/element-visible checkpoints. Keep real write approval in existing session route.
- [ ] Run targeted test and `mvn -q test`; expected zero failures. Run `npm.cmd run build --prefix web-admin`; expected exit 0.
- [ ] Stage only these files and commit `feat(ai): freeze scoped device baselines`.

### Task 2: Generate a Validated v2 Proposal Directly

**Files:** Create `ai-worker/src/v2-proposal.js`, `ai-worker/test/v2-proposal.test.js`, Java `AiV2ProposalService.java`, `AiV2ProposalServiceTest.java`; modify `ai-worker/src/server.js`, `AiWorkbenchController.java`, `AiSimulatorDraftService.java` only where v2 save must check source snapshot.

**Interfaces:** Consume Task 1 `BaselineView` when present; demo authoring may use a scoped API draft alone. Produce `proposeV2(projectId, packageId, apiDraftId, baselineId?, currentDraftId?, goal, creatorId): ProposalView` with complete `skilllab.simulator-behavior.v2` payload, `sourceFingerprint`, `gaps` and rule-level provenance; proposal is not persisted. Without approved observations, proposed behavioral claims remain `INFERRED_PENDING` and cannot satisfy critical evidence gates. New save request includes `sourceFingerprint`, verified against current project/package/API draft/baseline before immutable `createDraftV2`.

- [ ] Write Worker tests `normalizesCompleteV2`, `rejectsInventedCapturedIdAndMatcher`, `marksUnsupportedClaimsPending`, `supportsDemoWithoutBaseline`, `rejectsDangerousRuleAndProviderFailure`; Java tests reject foreign source, stale fingerprint and model failure without a draft row. Explicitly test missing/redacted body cannot create `SET_FROM_REQUEST`.
- [ ] Run `node --test ai-worker/test/v2-proposal.test.js` and `mvn -q -Dtest=AiV2ProposalServiceTest test`; expected red for missing interfaces.
- [ ] Implement a bounded provider call with untrusted evidence in user content; validate via `validateBehaviorV2`, recheck evidence ownership and semantic claims, return gaps. Keep manual v2 editor available without model configuration; do not route through v1 import.
- [ ] Run targeted tests, `npm.cmd test --prefix ai-worker` and `mvn -q test`; expected zero failures.
- [ ] Stage only task files and commit `feat(ai): propose evidence-bound v2 behavior`.

### Task 3: Review and Adopt Proposal Changes

**Files:** Create `web-admin/src/views/ai-workbench/V2ProposalReview.vue` and `V2ProposalReview.test.ts`; modify `AiWorkbenchView.vue`, `AiWorkbenchView.test.ts`, `web-admin/src/api/ai-workbench.ts`.

**Interfaces:** Consume Task 2 `ProposalView`. Produce a local complete v2 working model only after creator acceptance of a rule bundle (rule, required state fields and associated cases). Rejecting/canceling leaves persisted drafts unchanged. Compare by stable rule ID and show matcher, condition, response, operations, evidence IDs/type, gaps and before/after values.

- [ ] Write component tests `adoptsOneBundleWithoutOtherChanges`, `cancelDoesNotPersist`, `staleProjectPackageOrDraftDropsResponse`, `manualEditingWorksWithoutModel`, `saveConflictRetainsReview`; assert source fingerprint is sent on save.
- [ ] Run `npm.cmd test --prefix web-admin`; expected new tests fail.
- [ ] Implement proposal component and replace the old v1 proposal as the new-flow primary action. Preserve saved v1 history and explicit import access without making it a prerequisite. Do not add instructional marketing text or hide the provenance markers.
- [ ] Run `npm.cmd test --prefix web-admin` and `npm.cmd run build --prefix web-admin`; expected zero failures and exit 0. Inspect desktop and 390px layout with Playwright for overlap.
- [ ] Stage only task files and commit `feat(admin): review and adopt v2 proposals`.

### Task 4: Compare Real Baselines Separately from Engineering Replay

**Files:** Create `ai-worker/src/device-differential.js`, `ai-worker/test/device-differential.test.js`; modify `ai-worker/src/simulator-validator.js`, `ai-worker/test/simulator-validator.test.js`, Java `AiValidationEntity.java`, `AiValidationService.java`, `AiValidationServiceTest.java`, `AiWorkbenchController.java`.

**Interfaces:** Consume Task 1 frozen scenario and Task 2 v2 draft. Produce report with existing engineering `status: PASSED|BLOCKED`, plus `consistency: { status: NOT_TESTED|PASSED|BLOCKED|PARTIAL, baselineSha256, comparisonSha256, differences, uncovered }`. Each difference names scenario/step and observed versus simulated values; no baseline produces `NOT_TESTED`.

- [ ] Write tests `noBaselineIsNotTested`, `httpPassVisibleCheckpointFailIsBlocked`, `httpMismatchVisiblePassIsBlocked`, `unreplayableActionIsPartial`, `dynamicFieldUsesOnlyApprovedPolicy`, `changedBaselineOrComparisonRequiresNewReport`. Browser test uses the demo package and asserts no request to real target.
- [ ] Run `node --test ai-worker/test/device-differential.test.js ai-worker/test/simulator-validator.test.js` and `mvn -q -Dtest=AiValidationServiceTest test`; expected red.
- [ ] Implement deterministic ordered response/state comparison and constrained visible checkpoints in isolated Playwright context. Persist report hashes; do not let AI-authored expected values count as real-device evidence. Keep engineering blockers independent from consistency differences.
- [ ] Run Worker and Java full suites; expected zero failures. Re-run browser replay test outside sandbox if localhost restrictions intervene.
- [ ] Stage only task files and commit `feat(simulator): report device differential separately`.

### Task 5: Gate and Display Demo-Only Versions

**Files:** Modify `AiSimulatorVersionEntity.java`, `AiValidationService.java`, `AiRunService.java`, their Java tests, `web-admin/src/views/AiWorkbenchView.vue`, `web-admin/src/api/ai-workbench.ts`, `web-simulator/src/views/SimulatorRunView.vue`, `web-simulator/test/simulator-run.e2e.cjs`, `docs/simulator-authoring.md`.

**Interfaces:** `enableDemo(projectId, versionId, reportId, creatorId, acknowledgement)` requires engineering `PASSED` with consistency `NOT_TESTED` or `PARTIAL` and yields `DEMO_ENABLED`; `startRun` accepts it only for authenticated creator trial. Consistency `PASSED` is recorded separately but this phase does not create a learner/public entry point. No client-supplied level is authoritative.

- [ ] Write Java tests `notTestedCanOnlyEnableDemo`, `blockedEngineeringCannotEnable`, `forgedRequestedLevelCannotUpgrade`, `demoRunRequiresOwnerAndEndsOnLease`; Vue/E2E tests assert prominent “仅演示，未验证真实设备一致性” on version and run pages, and deny anonymous start.
- [ ] Run targeted Java, admin component and shell E2E tests; expected red.
- [ ] Implement server-owned status decision and creator authorization; label reports and versions distinctly. Leave existing `ENABLED` rows untouched and do not infer their consistency. Update test-environment instructions.
- [ ] Run `npm.cmd test --prefix ai-worker`, `mvn -q test` in `skilllab-admin`, `npm.cmd test --prefix web-admin`, both frontend builds, shell E2E, and `git diff --check`; expected zero failures. Exercise demo ZIP upload through enabled creator run; state refresh persists and new run resets. Do not claim real AucSeek fidelity.
- [ ] Stage only task files and commit `feat(simulator): enable creator-only demo versions`.

## Delivery Review

- [ ] Re-read spec against task results: direct v2 AI proposal, evidence provenance, two independent verification conclusions, demo-only launch and no runtime AI.
- [ ] Review the whole branch for authorization, origin isolation, data-leak boundaries, stale snapshots and false consistency claims; fix critical/important findings with failing tests first.
- [ ] Report exact test commands and results, any unavailable real-device verification, and unchanged pre-existing local data/working-tree changes.
