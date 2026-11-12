# Template Update Visibility — Requirements Specification

| | |
|---|---|
| **Status** | v1.0 — approved 2026-11-12 |
| **Date** | 2026-11-12 |
| **Sources** | [problem](01-brief/problem.md), [current systems](01-brief/current-systems.md) |

**Legend.** Each requirement is tagged with its origin:
**[G]** given directly by the brief · **[D]** derived from the brief (a necessary consequence) · **[C]** confirmed in clarification (decision `D-n`, §9) · **[P]** proposed, not yet confirmed (open question `OQ-n`, §10).
Priority uses MoSCoW: **M**ust / **S**hould / **C**ould.

---

## Contents

- [1. Purpose](#1-purpose)
- [2. Scope](#2-scope)
- [3. Definitions](#3-definitions)
- [4. Stakeholders](#4-stakeholders)
- [5. Constraints](#5-constraints)
- [6. Assumptions](#6-assumptions)
- [7. Functional Requirements](#7-functional-requirements)
- [8. Non-Functional Requirements](#8-non-functional-requirements)
- [9. Decision Log](#9-decision-log)
- [10. Open Questions](#10-open-questions)
- [11. Out of Scope](#11-out-of-scope)
- [12. Tradeoffs](#12-tradeoffs)

---

## 1. Purpose

Accounting firms work in engagement files created from product templates. Templates are updated during the year, and for every engagement the firm must either apply or decline each published update. This system tells users **which of their engagements have a pending update** and **what the update would change**, so they can make that decision.

## 2. Scope

**In scope:** detecting and displaying pending template updates per engagement; presenting human-readable release notes for the inbound changes, generated per version at publish time; tracking the apply/decline state; handling the template version graph (branches, withdrawals, accumulated updates).

**Out of scope:** see §11.

## 3. Definitions

| Term | Meaning |
|---|---|
| **Engagement file** | All the work a firm does for one client over a year. Stored in a firm-specific database. |
| **Product template** | Versioned zip archive of structured JSON, shared by all firms. ~40 products. |
| **Template version graph** | The set of versions of one product and their ancestry. Not linear: markets branch, versions can be withdrawn. |
| **Market branch** | A line of versions of one product for one market. An engagement stays on one branch for life. |
| **Current version** | The template version an engagement is on now (creation version plus any applied updates). |
| **Offer** | The single version an engagement is currently eligible to move to: the latest non-withdrawn descendant of its current version on its branch. |
| **Pending update** | An offer the user has neither applied nor declined. |
| **Decline pin** | A decline is pinned to the offered version, not to the changes inside it. |
| **Withdrawn version** | A version retracted after publication. |
| **Contains** | Version B contains version A if A is an ancestor of B in the graph. |
| **Release notes** | Human-readable description of what one version changes relative to its parent. Generated from the JSON diff at publish time and stored with the version. Never cumulative. |
| **Notes stack** | The ordered release notes of every version on the path from an engagement's current version to its offer, assembled at read time. |
| **Engagement index** | This system's own record of each engagement's current version and decision state. |

## 4. Stakeholders

| Stakeholder | Interest |
|---|---|
| Firm users (non-technical) | See pending updates at a glance; understand changes; decide. |
| Firm administrators | Oversight across many engagements (up to ~40,000). |
| Content teams (per market) | Publish and withdraw template versions; author release notes. |
| Product | Real-time experience. |
| Finance / Engineering | Run cost under $X per month. |
| Compliance | Data residency and confidentiality of engagement material. |

## 5. Constraints

| ID | Constraint | Origin |
|---|---|---|
| C-1 | Reading an engagement's template information requires loading the engagement: ~1 minute per file. Hard constraint. | G |
| C-2 | Stored engagements are not queryable; state exists only when rehydrated in session pods. | G |
| C-3 | The template database is shared across firms and holds no engagement information. | G |
| C-4 | Engagement data lives in firm-specific databases. Some firms must keep it in region (EU, Canada). | G |
| C-5 | Engagement content is client-confidential audit material. Template content is not firm-specific. | G |
| C-6 | All systems are live. No maintenance window. | G |
| C-7 | The engagement management system owns engagement loading, creation, and processing of apply/decline decisions. | G |
| C-8 | Hooks or events may be added to any action of the template store or the engagement management system. | G |
| C-9 | A fast, reliable JSON diff between two versions of the same template is available. | G |
| C-10 | Total run cost, including inference, under $X per month. | G |

## 6. Assumptions

| ID | Assumption | Status |
|---|---|---|
| A-1 | An engagement belongs to exactly one market branch of one product and does not change branch. | Confirmed · D-2 |
| A-2 | Update decisions already happen today. An existing engagement's current version and past declines can differ from its creation version and are only knowable by loading it. | Confirmed · D-9 |
| A-3 | Engagement metadata (IDs, names, firm, version, decision history) is bound by the same residency rules as engagement content. | Confirmed · D-7 |
| A-4 | Change descriptions depend only on template content, never on engagement content. | Confirmed · D-6 |
| A-5 | Only active engagements receive offers. Archived, finalised or locked ones are excluded. | Confirmed · D-11 |
| A-6 | The platform runs on AWS, with regions available for EU and Canada. | Confirmed · D-12 |

## 7. Functional Requirements

### FR-1 Pending-update visibility

| ID | Requirement | Pri | Origin |
|---|---|---|---|
| FR-1.1 | The system shall show, for each engagement a user can access, whether it has a pending update. | M | G |
| FR-1.2 | The system shall show firm-level aggregates: number of engagements with pending updates, in total and per product. | M | D |
| FR-1.3 | The engagement list shall support filtering by pending status and product, sorting, and pagination, usable for a firm with 40,000 engagements. | M | D |
| FR-1.4 | The indicator shall work for engagements that existed before this system launched. | M | G |
| FR-1.5 | Each engagement shall be in exactly one of these states: *up to date*, *update pending* (optionally marked "last attempt failed"), *declined (nothing newer)*, *decision in progress*, *checking for updates* (not yet indexed). An unindexed engagement shall never be shown as up to date. | M | C · D-10 |
| FR-1.6 | Users shall only see indicators for engagements they are permitted to access. Access is assumed to be per engagement; the index shall mirror assignments from engagement-system events. | M | D · C · D-23 |
| FR-1.8 | Counts shall be per user, over the engagements that user can access. Firm-wide totals shall be visible to administrators only. | M | C · D-23 |
| FR-1.7 | Engagements that become archived, finalised or locked shall stop receiving offers and leave the pending counts. | M | C · D-11 |

### FR-2 Change description

| ID | Requirement | Pri | Origin |
|---|---|---|---|
| FR-2.1 | For a pending update, the system shall present human-readable release notes describing the changes between the engagement's current version and the offered version. | M | G · C · D-5 |
| FR-2.2 | When several updates have accumulated, the system shall present the release notes of every version on the path from current to offered version, in order, each labelled with its version. | M | C · D-3, D-5 |
| FR-2.3 | Versions on that path that the user previously declined shall be marked as such. | S | C · D-1 |
| FR-2.4 | The description shall cover template changes only and shall not refer to the engagement's own content. | M | C · D-6 |
| FR-2.5 | For every published version, release notes shall be generated by a language model from the JSON diff against its parent version, once, and stored with the version. No generation shall occur on the read path. | M | C · D-5 |
| FR-2.8 | In addition to the release notes, the system shall offer a "details" view showing the detailed JSON diff between the engagement's current version and the offered version. It is secondary to the notes, computed with the existing diff tool, and cached per version pair. | S | C · D-14 |
| FR-2.7 | A version shall not become visible as an offer until its release notes exist. A content author shall review and approve, or edit, the generated notes before the version is published. Notes are immutable after publish; correcting them requires publishing a new version. | M | C · D-13, D-15 |
| FR-2.6 | Release notes shall have a light structure (headline, list of changes, a "material change" flag per item) and be written in the language of the version's market. | M | C · D-13, D-25 |

### FR-3 Version graph semantics

| ID | Requirement | Pri | Origin |
|---|---|---|---|
| FR-3.1 | The system shall model template versions as a graph with ancestry, market branches, and a withdrawn flag. Publish events shall carry parent version and market. | M | G · C · D-2 |
| FR-3.2 | An engagement shall have at most one offer: the latest non-withdrawn version that descends from its current version on its branch. Intermediate versions cannot be chosen. | M | C · D-2, D-3 |
| FR-3.3 | A decline shall be recorded against the specific offered version. The engagement's current version shall not change. | M | C · D-1 |
| FR-3.4 | When a newer eligible version is published after a decline, the engagement shall become pending again, even though the new version contains the declined changes. | M | C · D-1 |
| FR-3.5 | When a version is withdrawn: pending offers for it shall be retracted and re-targeted to the latest remaining eligible version, or cleared if none; declines recorded against it shall be voided. Withdrawal affects only the version named; later versions built on it stay valid and offerable unless withdrawn themselves. | M | C · D-4, D-16 |
| FR-3.7 | The withdraw action shall show the content team the later versions built on the withdrawn one and ask whether to withdraw those too. | S | C · D-16 |
| FR-3.8 | In a notes stack, a withdrawn version that is an ancestor of the offer shall be shown as withdrawn with its changes included in the later version. | S | C · D-16 |
| FR-3.6 | Engagements whose current version is withdrawn, whether applied or created from it, shall be flagged to the firm. The flag shall clear automatically when the engagement moves to a later non-withdrawn version; it cannot be dismissed manually. Such engagements are still offered later versions. | S | C · D-4, D-27 |

### FR-4 Decision tracking

| ID | Requirement | Pri | Origin |
|---|---|---|---|
| FR-4.1 | Users shall be able to start an apply or decline decision from the pending-update view. Processing is delegated to the engagement management system. | M | D |
| FR-4.2 | The indicator shall reflect the outcome of a decision once the engagement system reports it. If an apply fails, or no outcome arrives within a timeout, the engagement shall return to *update pending* with a "last attempt failed" marker and a retry option. The failed attempt is kept in history. | M | D · C · D-26 |
| FR-4.3 | The system shall keep an immutable history of decisions: who, when, engagement, from version, offered version, outcome. | M | C · D-8 |
| FR-4.4 | A user shall be able to reverse a decline while the declined version is still the current offer. An apply is not reversible through this system. | S | C · D-8 |
| FR-4.5 | Permission to decide shall follow what the engagement system already enforces. | M | C · D-8 |
| FR-4.6 | Users shall be able to apply or decline for many engagements in one action (by selection, or all pending for a product and version). The action shall run in the background with visible progress and realistic completion estimates. | M | C · D-18 |
| FR-4.8 | A bulk action shall be pinned to one product and version; skip engagements whose offer has changed or which the user may not decide on; report skipped and failed items; and be cancellable for items not yet started. | M | C · D-31 |
| FR-4.9 | A decline shall be recorded in the engagement index immediately, without loading the engagement, and written into the engagement the next time it is loaded for any reason. An apply always requires the load. | M | C · D-30 |
| FR-4.7 | Every decision shall name the version it was made against and shall be rejected, with a prompt to review the newer update, if the engagement's offer has changed or been withdrawn since. An apply already in progress completes; the engagement then becomes pending for the newer version. | M | C · D-19 |

### FR-5 Integration and data acquisition

| ID | Requirement | Pri | Origin |
|---|---|---|---|
| FR-5.1 | The system shall react to template publish and withdraw events from the template store. | M | D |
| FR-5.2 | The system shall react to engagement created, decision processed, and engagement archived/finalised/deleted events from the engagement system. | M | D |
| FR-5.3 | The system shall acquire the current version and last decline of all existing engagements through a throttled, resumable background pass, prioritised by recent use, without a maintenance window. | M | C · D-9, D-10 |
| FR-5.4 | Whenever an engagement is loaded for any reason, its version state shall be captured into the index. | M | C · D-10 |
| FR-5.5 | The system shall periodically reconcile its view against the source systems and repair drift. On conflict, the engagement wins for current version; the index wins for declines not yet written into the engagement. | S | D · D-21, D-30 |

## 8. Non-Functional Requirements

| ID | Category | Requirement | Origin |
|---|---|---|---|
| NFR-1 | Freshness | A published version is reflected in pending indicators within seconds. Target: p99 ≤ 10 s server-side from publish commit. Open pages learn of changes by polling about every 30 s; no live push. | G · C · D-17 |
| NFR-2 | Freshness | A withdrawal is reflected within the same bound as NFR-1. | C · D-17 |
| NFR-3 | Freshness | Release notes are visible in the same bound as the indicator. Generation happens inside the publish step, before the version becomes an offer, and does not count against NFR-1. | D · D-5 |
| NFR-4 | Performance | Engagement list with indicators: p95 ≤ 500 ms per page, including for the largest firm. Firm aggregates: p95 ≤ 300 ms. | C · D-22 |
| NFR-20 | Performance | The diff details view shall load within p95 ≤ 2 s. It uses template content only, so it is global and involves no engagement data or inference. | D · D-14 |
| NFR-5 | Performance | Displaying indicators shall never require loading an engagement (C-1). | D |
| NFR-6 | Scale | 4,000 firms; 800,000 active engagements; 40 products; median firm ~40 engagements, largest ~40,000; ~1 publish per product per week (~40 per week). | G |
| NFR-7 | Scale | One publish may affect on the order of 20,000 engagements on average (800,000 / 40) and must still meet NFR-1. | D |
| NFR-8 | Cost | All-in run cost under $X per month. The design shall include an explicit cost model on AWS using real resource prices, comparing pricing models, and state the resulting monthly figure. Inference spend shall scale with the number of published versions (~170 per month), not with engagements, firms or version pairs. | G · C · D-5, D-12 |
| NFR-9 | Residency | Engagement-derived data, including metadata, for region-bound firms shall be stored and processed only in that region. The engagement index is regional; the version graph and release notes are global. | G · C · D-7 |
| NFR-10 | Confidentiality | Tenant isolation: no firm can observe another firm's engagements or decisions. | D |
| NFR-11 | Availability | Read path 99.9% monthly. Single region per residency zone. If this feature is down, the engagement list shall still work without indicators. | C · D-22 |
| NFR-12 | Correctness | No offer shall be shown for a withdrawn version beyond NFR-2's bound. No eligible offer shall be missed for an indexed engagement. Eventual consistency inside the freshness bound is acceptable. | D |
| NFR-13 | Rollout | Initial acquisition of existing engagements costs ~800,000 load-minutes (~13,300 pod-hours). It shall be throttled below a set share of engagement-system capacity. Planning figure: about one week at ~100 parallel loads. | D · C · D-10 |
| NFR-14 | Auditability | Decision history is immutable, stays readable after the engagement is archived or finalised, and is deleted only when the engagement itself is deleted. | C · D-8, D-28 |
| NFR-15 | Observability | The system shall expose publish-to-visible latency, count of unindexed engagements, event-processing failures, and cost per month. | D |
| NFR-16 | Notes quality | Release notes shall not state changes absent from the diff and shall not omit material changes. Assured by mandatory author review before publish. | C · D-13 |
| NFR-18 | Durability | The engagement system remains the source of truth for an engagement's version. The engagement index is a copy, except for declines not yet written into an engagement (FR-4.9), for which it is the only record. It shall be backed up with point-in-time recovery, because rebuilding it costs ~13,300 pod-hours. | C · D-21 |
| NFR-19 | Bulk load | Bulk decisions shall not degrade interactive engagement sessions and shall be capped per firm. Bulk work shall run on a separate lower-priority pool with fair scheduling across firms, a per-firm concurrency cap, and autoscaling bounded by a fixed monthly spending ceiling. A 40,000-engagement bulk apply is expected to take hours. | C · D-18, D-29 |
| NFR-17 | Confidentiality | Only template content is sent to the inference provider. No engagement or firm data. | D · D-5, D-6 |

## 9. Decision Log

| ID | Decision | Round |
|---|---|---|
| D-1 | A decline pins the specific version offered. A later version containing the declined changes is offered again, with the declined version marked. | 1 |
| D-2 | An engagement is on one market branch for life and is only offered descendants of its current version on that branch. Cross-branch moves are out of scope. | 1 |
| D-3 | Accumulated updates collapse into one offer to the latest version. Intermediate versions cannot be applied. | 1 |
| D-4 | On withdrawal: retract and re-target pending offers; void declines against the withdrawn version; flag engagements that already applied it. Remediation is out of scope. | 1 |
| D-5 | *(Revised in round 2; replaces "human-authored notes only".)* Release notes are generated by a language model once per published version, from the diff against its parent, at publish time, and stored with the version. Notes are per step, never cumulative; accumulated updates are shown by stacking them at read time. | 1 → 2 |
| D-6 | Descriptions cover the template change only, never the engagement's content. | 1 |
| D-7 | Engagement metadata is residency-bound. Regional engagement index; global version graph and release notes. | 1 |
| D-8 | Decline is reversible while it is still the current offer; apply is not; decisions are kept in an immutable history; decision permission follows the engagement system. | 1 |
| D-9 | Assume update decisions already exist today, so existing engagements must be loaded to learn current version and past declines. | 1 |
| D-10 | During rollout, unindexed engagements show "checking for updates". Index is filled by a throttled, prioritised background pass plus capture on open. | 1 |
| D-11 | Only active engagements receive offers. | 1 |
| D-12 | No fixed budget figure. Produce an AWS cost model from real resource prices, comparing pricing models. | 1 |
| D-13 | Generated notes are a draft. A content author must approve or edit them before the version is published. Notes are structured (headline, changes, material flag). | 2 |
| D-14 | *(Revised at sign-off; replaces "no diff view".)* The detailed JSON diff from current to offered version is included as an additional "details" view alongside the release notes. | 2 → sign-off |
| D-15 | Notes cannot be edited after publish, because they are reviewed before publish. A correction requires a new version. | 2 |
| D-16 | Withdrawal affects only the version named; descendants stay valid. The withdraw action prompts the content team about later versions built on it. | 3 |
| D-17 | Freshness: p99 ≤ 10 s server-side; open pages poll about every 30 s; no live push. | 2 |
| D-18 | Bulk apply/decline is in scope, processed in the background with progress and a per-firm cap. Scalability tradeoffs to be made explicit (T-8). | 2 |
| D-19 | Decisions name their version and are rejected if the offer has changed. | 2 |
| D-20 | Notifications outside the application and firm-wide auto policies are future improvements. | 2 |
| D-21 | Engagement system stays source of truth; index is a backed-up copy with point-in-time recovery. | 2 |
| D-22 | 99.9% read availability; list p95 ≤ 500 ms; aggregates p95 ≤ 300 ms; degrade to a list without indicators. | 2 |
| D-23 | Access is per engagement; counts are per user; firm-wide totals for administrators only. | 2 |
| D-24 | Cost model compares serverless (DynamoDB on-demand, Lambda, EventBridge/SQS) with provisioned (Aurora PostgreSQL, ECS Fargate) at on-demand list prices across three regions (one EU, ca-central-1, one US), then shows the effect of savings plans or reserved capacity. One-off rollout compute is reported separately. | 2 |
| D-25 | Notes are written in the market's language. Translation is out of scope. | 2 |
| D-26 | A failed or timed-out apply returns the engagement to pending with a "last attempt failed" marker and retry. No separate failed state. | 3 |
| D-27 | The withdrawn-version flag covers engagements that applied or were created from the version, and clears automatically on moving to a later non-withdrawn version. | 3 |
| D-28 | Decision history lives as long as the engagement and is deleted with it. | 3 |
| D-29 | Bulk capacity: separate lower-priority pool, fair queue across firms, per-firm cap, autoscaling bounded by a monthly spending ceiling. | 3 |
| D-30 | Declines are recorded in the index without loading the engagement and written into it on its next load. | 3 |
| D-31 | Bulk actions are pinned to one product and version, skip changed or unpermitted items, report skips and failures, and can be cancelled for unstarted items. | 3 |

## 10. Open Questions

None. All questions from three clarification rounds are resolved in §9.

## 11. Out of Scope

| ID | Exclusion | Basis |
|---|---|---|
| X-1 | Applying template content to an engagement. | Brief |
| X-2 | Descriptions tailored to an engagement's own content. | D-6 |
| X-3 | Applying an intermediate version rather than the latest. | D-3 |
| X-4 | Accepting individual changes within one update. | D-1 |
| X-5 | Moving an engagement between market branches. | D-2 |
| X-6 | Remediating engagements that applied a withdrawn version. | D-4 |
| X-7 | Generated summaries per (from-version, to-version) pair, and any netting-out of changes across versions. | D-5 |
| X-8 | Offers for archived, finalised or locked engagements. | D-11 |
| X-9 | Notifications outside the application (future improvement). | D-20 |
| X-10 | Firm-wide policies such as auto-apply or auto-decline (future improvement). | D-20 |
| X-12 | Editing release notes after publish. | D-15 |
| X-13 | Translating release notes into other languages. | D-25 |
| X-14 | Live push of changes to open pages. | D-17 |

## 12. Tradeoffs

| ID | Tradeoff | Position |
|---|---|---|
| T-1 | **Compute pending state on read vs fan out on publish.** Deriving it by joining the engagement index against the version graph makes publish O(1); fan-out writes ~20,000 rows per publish but simplifies reads. | Lean: derive on read, with cached aggregates. Design phase. |
| T-2 | **Per-version generated notes vs per-pair summaries vs human-only notes.** Per-version generation bounds inference to one call per publish and keeps it off the read path, but stacked notes do not net out (an addition in v5 and its removal in v6 both appear) and a firm ten versions behind reads ten sets of notes. Per-pair summaries read better but grow with the number of starting versions. Human-only notes cost nothing to run but depend on content-team discipline. | Decided (D-5 revised): per-version generation, stacked at read time. |
| T-3 | **Rollout speed vs load on the engagement system.** | Decided (D-10): throttled pass over about a week, with an explicit unknown state. |
| T-4 | **Central vs regional index.** One global index is simpler and cheaper; regional indexes satisfy residency but multiply fixed cost. | Decided (D-7): regional. |
| T-5 | **Decline pins version vs change set.** Version pinning is simple and auditable but asks the user again on every later version. | Decided (D-1): pin the version. |
| T-6 | **One cumulative offer vs a queue of offers.** | Decided (D-3): single offer. Firms lose the ability to stop at an intermediate version. |
| T-7 | **Strong vs eventual consistency of the indicator.** | Decided (D-17): eventual, 10 s server-side, plus up to ~30 s polling lag on open pages. |
| T-8 | **Bulk decision throughput vs cost vs interactive performance.** If every decision needs the 1-minute load, a 40,000-engagement bulk action is ~670 pod-hours. Scaling pods out finishes sooner but creates cost spikes and competes with interactive sessions; a fixed pool is cheap and safe but takes hours to days; one large firm can starve others without fair scheduling. A decline that skips the load removes most of the cost but makes the index, not the engagement, the first record of that decision. | Decided (D-29, D-30): bounded autoscale with a fair queue, so large bulk applies take hours; declines skip the load, so bulk decline takes seconds and the index is the only record of a decline until the engagement is next loaded. |
| T-9 | **Review gate vs publish speed.** Mandatory author review (D-13) protects accuracy and makes notes immutable (D-15), but a wrong note can only be fixed by a new version, which re-prompts every affected engagement. | Decided (D-13, D-15). |
| T-10 | **Availability vs fixed cost.** 99.9% allows one region per residency zone; 99.99% would need multi-region inside the EU and inside Canada. | Decided (D-22): 99.9%. |
| T-11 | **Diff view vs simplicity.** A JSON diff lets users verify notes against the actual change and shows the net change across accumulated versions, which stacked notes do not. It is technical material in front of non-technical users, so it stays secondary to the notes. | Decided (D-14 revised): include it as an additional view. |
