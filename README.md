# Template Update Visibility

Design and first implementation of a system that tells accounting firms which of their
engagement files have a pending template update, and what that update changes.

## Where to start

| If you want to… | Read |
|---|---|
| Understand the task as it was given | [Problem statement](docs/01-brief/problem.md), then [Current systems](docs/01-brief/current-systems.md) |
| See what the system must do | [Requirements](docs/02-requirements.md) |
| See how it is designed, and why | [Architecture](docs/03-architecture.md), starting with [§1 Overview and key decisions](docs/03-architecture.md#1-overview-and-key-decisions) |
| Review the Java code | [fanout-worker/README.md](fanout-worker/README.md) |

## Documents, in reading order

| # | Document | What it is | Length |
|---|---|---|---|
| 1 | [Problem statement](docs/01-brief/problem.md) | The original brief: scale, freshness, cost and version-graph expectations | 1 page |
| 1 | [Current systems](docs/01-brief/current-systems.md) | What already exists, including the ~1 minute engagement load | 1 page |
| 1 | [MVP brief](docs/01-brief/mvp-brief.md) | The narrow Java component to implement | ½ page |
| 2 | [Requirements](docs/02-requirements.md) | Functional and non-functional requirements, decision log, tradeoffs | ~300 lines |
| 3 | [Architecture](docs/03-architecture.md) | Full system design with diagrams, cost model and ADRs | ~1,800 lines |

The two long documents open with a linked table of contents.

## Architecture shortcuts

| Topic | Section |
|---|---|
| The eight key decisions | [§1](docs/03-architecture.md#1-overview-and-key-decisions) |
| How pending state is computed | [§6](docs/03-architecture.md#6-version-graph-offer-computation-and-engagement-state) |
| Data model | [§7](docs/03-architecture.md#7-data-model) |
| Sequence diagrams for publish, withdraw, apply, decline | [§9](docs/03-architecture.md#9-key-flows) |
| The Load Dispatcher and how the Java MVP maps onto it | [§11](docs/03-architecture.md#11-load-dispatcher-bulk-decisions-and-the-mvp-worker) |
| Cost model | [§16](docs/03-architecture.md#16-cost-model) |
| Failure modes | [§18](docs/03-architecture.md#18-failure-modes) |
| Tradeoffs and ADRs | [§20](docs/03-architecture.md#20-tradeoffs-and-adrs) |

## Code

| Module | What it is | Run |
|---|---|---|
| [fanout-worker](fanout-worker/README.md) | Java 21 library: the template-publish fan-out worker (MVP) | `cd fanout-worker && mvn test` |

## Layout

```
README.md               this index
docs/
  01-brief/             the task as given (problem, current systems, MVP brief)
  02-requirements.md    what the system must do
  03-architecture.md    how it is designed
fanout-worker/          Java implementation of the MVP worker
```
