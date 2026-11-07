# Device Simulator Authoring and Runtime Design

Status: Awaiting user approval. This is a design, not authorization to implement.

## Intent

Let authors upload a real device Web package, capture selected capabilities on an authorized device, and use AI to turn the evidence into a runnable simulator. The finished simulator is used inside SkillLab. Authoring a simulator is independent of configuring exercises or exams.

The motivating example is AucSeek configuration: read the setting, enable or disable it, save, and read back the saved value. Detection and search after enabling are outside this example's scope. No real AucSeek package or capture is currently available; repository demo devices can validate the platform, not vendor fidelity.

## Confirmed Requirements

- Keep the upload and real-device capture workflow; do not substitute AI-generated device pages.
- Support partial device capabilities rather than requiring a complete device clone.
- AI participates only during authoring. Normal usage executes frozen rules without model calls, model credentials, or live-device dependencies.
- Prefer captured behavior. AI may propose inferred behavior and recommend additional capture.
- Permit system use when the critical read/write chain is supported by capture and passes validation, even if abnormal branches remain unconfirmed inferences.
- Preserve evidence and inference labels after a version becomes usable.
- Each usage has isolated state. Refreshes within it retain changes; ending it and starting again restores initial state.
- Continue using Service Worker as the HTTP interception mechanism, with a fixed interpreter for AI-authored behavior data.

## Goals and Non-Goals

Goals: a complete authoring-to-system-use path, conditional API behavior, stateful configuration read/write, explicit capability boundaries, repeatable validation, immutable usable versions, and isolation between users and runs.

Non-goals: exercise configuration, guidance overlays, scoring, exam orchestration, standalone website exports, arbitrary AI-generated executable code, complete vendor compatibility, WebSocket emulation, native plugins, hardware detection/search effects, and production-scale worker scheduling.

Anti-goals: treating HTTP 200 as proof of a saved change; silently inventing vendor behavior; forwarding missing requests to a real device; resetting state when a Service Worker is reclaimed; retaining a previous user's state; or making model availability a runtime dependency.

## Current Baseline

- Vue admin workbench accepts ZIPs, captures device traffic, generates API drafts, requests AI behavior proposals, and edits behavior as JSON.
- Java stores immutable SimulatorDraft revisions with package and capture references.
- Worker serves derived package copies at isolated temporary origins and injects a Service Worker bootstrap.
- Mock runtime currently matches method/path, renders state tokens, and supports limited transitions including request-field assignment. State is only in Worker memory.
- Request bodies are not retained. Multiple observations are collapsed into endpoint samples, losing evidence of response branches.
- The general simulator view still uses a hardcoded demo package and same-origin bridge. It does not load published simulator versions.
- Existing worktree changes are an uncommitted prototype. They must be audited and adapted, not treated as completed architecture or overwritten wholesale.

## Approaches

### A. Declarative Behavior Model and Fixed Runtime (Selected)

AI produces typed request matchers, conditions, state transitions, response templates, and missing-evidence notes. A platform-owned interpreter validates and executes them through Service Worker. Reuses current architecture, enables deterministic validation, and restricts untrusted output. Complex unsupported behavior requires platform adapters, not arbitrary scripts.

### B. AI-Generated Per-Simulator Backend (Not Selected)

Generated code runs in separate server sandboxes. More expressive for special protocols but adds code isolation, dependency management, vulnerability review, deployment, and scheduling costs. Not justified for the first configuration read/write chain.

The user accepted A and clarified that authoring-only AI is a hard boundary. This is not a static-response-only approach.

## Authoring and Evidence

1. Upload and analyze a package; preserve the original ZIP and hash. Derived artifacts never overwrite it.
2. Describe the capability scope and prerequisites. Select captured endpoints to include; login/navigation prerequisites may be shared capabilities.
3. Capture initial reads, mutations, and subsequent reads. Suggest missing branches without automatically triggering dangerous writes or intentionally damaging a device.
4. AI proposes rules, state fields, branch conditions, evidence references, and a supplement-capture checklist. Treat all package content and capture content as untrusted data, not model instructions.
5. The author reviews structured interface rules and state mappings, with JSON as an advanced view, then saves a new revision.
6. Run isolated preview and validation; correct or supplement capture as needed.
7. Explicitly enable a frozen simulator version for system use, accepting recorded nonblocking uncertainties.

Capture must preserve bounded, ordered request/response observations rather than only endpoint aggregates. Store method, normalized path, selected query/body fields, status, content type, redacted response sample, timing/order, capture/session IDs, and truncation/redaction markers.

Builder decision: request field capture is opt-in per capture scope. Apply field allowlists plus sensitive-field denial/redaction, never retain passwords, cookies, authorization values, or session tokens. Bound individual retained payloads to 64 KiB. Redacted or truncated values cannot be used as executable rule evidence. Do not send raw secrets to a model. Authentication can use synthetic session state; vendor token values are not reused. Author approval remains mandatory for real-device write requests.

Evidence labels: CAPTURED, INFERRED_PENDING, INFERRED_CONFIRMED. CAPTURED means supported by referenced observations, not guaranteed universal correctness. An inferred rule remains identifiable after confirmation. Later evidence produces a new revision; old versions and reports remain unchanged.

## Behavior Contract

Introduce a versioned v2 contract rather than silently changing v1 semantics. Preserve v1 reading and provide deterministic import into v2; never mutate old saved payloads. Do not carry practice/exam workflow fields into the new runtime contract.

A simulator revision includes package identity/hash, capability scopes, initial typed state, ordered rules, source references, inference notes, supported runtime version, and declared verification cases.

A rule includes:

- Stable ID and explicit priority; method and exact normalized path.
- Optional query/body predicates and state prerequisites.
- Response status, allowed headers, content type, and typed response template.
- Restricted state operations and evidence references.

Builder decision: first-release predicates are equality, existence, type, membership, and numeric range, composed with all/any/not. Operands reference constants, request query/body fields, or state fields through safe structured paths. No eval, functions, arbitrary expressions, network commands, regex supplied by models, or unrestricted header access. Define missing versus null explicitly. Decode URL/form inputs once; conversions must be declared. Reject ambiguous equal-priority rules and unknown operators before saving or running.

Operations are typed SET, SET_FROM_REQUEST, MERGE, and INCREMENT. Disallow prototype-related paths and unsafe object keys recursively; enforce state, rule, depth, and request size limits. Bind read templates to persisted state, not the last captured sample. Validate all paths and referenced rules on server and runtime using shared contract fixtures.

For each request: identify run and version, parse bounded input, choose the highest-priority matching rule against pre-request state, calculate next state, validate/render response, atomically commit state, then emit diagnostic metadata. Failure to parse, validate, render, or persist must not partially update state or report save success. Only explicitly declared successful mutations commit changes. Device-like error branches preserve state unless explicitly modeled otherwise.

Unknown API requests fail closed with a clear unavailable result and diagnostic event. Never forward them to devices or synthesize success. Static resources may be served only from the immutable derived package. Use an explicit package asset manifest/API boundary, not only API path heuristics. Unsupported vendor absolute URLs require reviewed declarative origin/path adaptation; external network access is blocked by default. Missing critical adapters block usability. No arbitrary package rewrite scripts from AI.

## Versions and Validation

Separate editable authoring records, immutable draft revisions, validation reports, and usable simulator versions. A usable version pins package hash, behavior revision/hash, derived artifact hash, interpreter version, and report. Changing any input requires a new version/report.

Authoring status: DRAFT -> VALIDATING -> VALIDATION_FAILED or VALIDATED -> ENABLED. ENABLED means available for system use, not fully faithful or fully reviewed. Disabling a version prevents new runs; active runs remain pinned until ended unless explicitly revoked for safety.

Blocking findings: malformed/unsafe contract, package boot failure, required resources missing, critical read/write verification failure, inferred-only critical behavior, state isolation failure, or external device/network dependencies. Nonblocking findings: unconfirmed inferred abnormal branches, unimplemented out-of-scope capabilities, and noncritical compatibility limitations. Enabling requires explicit author acknowledgement of the report and uncertainties.

Verification cases are simulator engineering checks, not learner tasks or scoring. The author identifies critical capabilities; the report must cover their claimed initial read, successful mutation, read-back, inverse mutation where supported, refresh retention, and reset on a fresh run. Required prerequisites must also work. Captured cases need explicit expected values/assertions. AI may propose cases but cannot declare its own assertions correct without author review.

Reports record case results, matched rules, unmatched APIs, console/runtime errors, external request violations, and assumptions. Run in isolated contexts with real-device egress blocked. Reports are bound to exact artifact inputs. Browser automation uses bounded declarative navigation/actions, not model-generated code.

## System Runtime, No AI

The simulator shell accepts a usable version ID, requests an authorized run, and embeds its dedicated origin. Existing demo guidance modes are not part of this new route. Old demo routes can remain separate.

A RunInstance records run ID, exact version/runtime IDs, authorized owner/access context, status, expiry, and dedicated origin. It is not an AI session and does not require a per-user backend process. The fixed runtime and shared immutable artifacts can be served by a common service.

Each run has an unguessable dedicated origin, keeping uploaded scripts, cookies, local storage, caches, IndexedDB, and Service Worker scope separate from other runs and platform shells. Production requires a separate registrable runtime domain, wildcard DNS/TLS, no shared parent-domain cookies, and authenticated launch. Local *.localhost is a development equivalent only.

Startup must wait for platform Service Worker control before package scripts run. Unknown packages are not trusted with platform credentials. Apply CSP and sandbox restrictions; validate message origin, source, run ID, and message schema in the shell. The runtime surface exposes no model endpoints or keys and no worker-internal control APIs.

Persist simulated state in per-run IndexedDB. Use short readwrite transactions and version/revision checks for atomic read-modify-write across Worker restarts and multiple tabs of the same run. Rule execution inside the transaction is synchronous and bounded; no network/model waits. Initialization creates state only if absent. On quota/storage failure, fail visibly without claiming save success. IndexedDB is browser-local operational state, not secure exam evidence; anti-cheating and scoring are separate work.

Refreshing or reloading the same authorized run resumes it. Device logout changes only simulated login state, not saved configuration. Ending a run invalidates its lease, unmounts the frame, and best-effort clears its storage and registration. Starting again always allocates a new run/origin from initial state; never reuse expired IDs. Tab closure is not reliable proof of end: use best-effort notifications plus a lease/heartbeat and eventual cleanup. Runtime checks an active lease before serving simulated API operations and fails closed after expiry; browser timers and cached pages are not authority to resume an ended run.

Builder defaults for approval: heartbeat every 30 seconds, 10-minute inactivity lease, and an 8-hour absolute maximum. The visible system shell owns explicit end/restart. Resuming after reload is allowed only while the same lease remains valid. Deployments can configure limits later; changing these does not imply AI activity.

## Architecture and Ownership

- Admin Vue: scope selection, capture review, evidence-aware behavior editing, AI proposals, preview diagnostics, reports, and enable/version actions.
- Java: package/capture/revision/report/version/run persistence, authorization, immutable references, lifecycle transitions, and worker requests.
- Worker: capture browser, authoring model calls, package preparation/serving, bounded validation runner. Split authoring/internal routes from public runtime hosting.
- Runtime: fixed behavior interpreter, Service Worker request interception, IndexedDB state, and narrow shell events.
- Simulator Vue: authorized version launch, run lifecycle and isolated frame, without AI or teaching configuration.

Retain Vue 3, Spring Boot, Node/Playwright, and existing repository storage patterns initially. This is a builder recommendation, not a user technology mandate. Runtime registration/lease metadata must survive service restart in persisted storage; never depend solely on the current worker's in-memory preview registry for usable versions. Live browser runtime state need not survive device/browser storage clearing and must never leak into a new run.

## Security and Release Boundaries

Current shared author key and H2/local files are restricted single-machine pilot infrastructure. Introduce a platform authorization adapter for launch/ownership checks; absent end-user identity, only authenticated author test runs are allowed, not anonymous candidate rollout. Full exam integration and identity rollout are separate, but launch endpoints must not accept arbitrary client-asserted ownership.

Never expose uploaded content at a platform origin or model secrets in bundles. Restrict authoring Worker egress independently of app allowlists. Unknown packages cannot register their own replacement workers or access parent credentials; define and test reserved runtime paths and worker-src policy. Diagnose compatibility failures rather than weakening isolation. Dedicated origins protect platform users but do not prove arbitrary package code benign.

## Acceptance

1. The repository demo ZIP goes through upload, captured read/write observations, AI-assisted or manual modeling, validation, enable, and simulator-shell launch.
2. A configuration can be enabled, saved, read back, disabled, and saved again; a Worker restart and page refresh preserve same-run state.
3. Two runs of one version never share state; ending/restarting resets state, including vendor storage. Device logout does not reset configuration.
4. Model service and real-device service can be unavailable throughout normal usage; no AI/live-device requests occur.
5. Missing APIs do not silently succeed or reach a device. Failed mutations do not change state.
6. Captured/inferred branches remain distinguishable. Pending abnormal inferences can be enabled with acknowledgement; missing critical evidence cannot.
7. Changed behavior/package/runtime invalidates old validation for the new candidate but does not mutate existing versions or runs.
8. Invalid conditions, unsafe paths, ambiguous rules, oversized data, unauthorized launch, expired leases, and cross-origin messages are rejected.
9. No exercise prompts, hints, grading, or completion logic is introduced.

## Implementation Sequencing Boundary

After design approval, the implementation plan should sequence contract/evidence changes, deterministic interpreter tests, run persistence/isolation, authoring review, validation/version enablement, then simulator-shell integration and demo end-to-end verification. Audit and reuse the prototype where it fits. Do not claim AucSeek compatibility without a real package and evidence.

## Builder Decisions to Confirm in Review

Proposed defaults: opt-in redacted request-field capture; v2 contract with v1 import; structured editor plus advanced JSON; conservative predicate/operator set; per-run IndexedDB; explicit leases and dedicated origins; immutable enabled versions; restricted author pilot before candidate identity integration. UI layouts and detailed endpoint names are left to implementation planning within these constraints.
