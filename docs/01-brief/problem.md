# Problem statement

> This is the brief as it was given, reformatted for reading. The facts and figures are
> unchanged. Figures in **bold** were marked as placeholder values in the original.

## Background

Our customers work in **engagement files**. An engagement file contains all the work that
an accounting firm does for one of its customers over the course of a year.

Engagement files are created from **product templates**, which our content teams in
different markets update regularly.

An engagement file is created with the most recent version of its product template, but
templates receive updates throughout the year. Once an update to a product template is
published, our customers need to evaluate it. For each of their engagement files, the
customer either **applies** the update or **declines** it.

## What users need

- To see at a glance which of their engagement files have pending updates.
- A summary of the pending inbound changes, so they can make the apply or decline decision.

## The task

Design a system that gives users this information in an up-to-date manner.

Actually applying the updated template content is outside the scope of this assignment.

## Scale

| Measure | Value |
|---|---|
| Firms | Roughly **4,000** |
| Active engagement files | Roughly **800,000** |
| Products | **40** |
| Engagements per firm, median | **~40** |
| Engagements per firm, largest | **~40,000** |
| Template updates | Relatively infrequent: about once per week per product |

Engagement counts per firm are heavily skewed, as the median and the largest firm show.

## Constraints

- **Cost.** All-in run cost for the feature, including any inference spend, must stay under
  **$X** per month.
- **Freshness.** Product would like this to be available in real time: within seconds of a
  template being published.

## Template versions are not a simple line

- Markets branch.
- Versions are occasionally withdrawn after publication.
- A firm that declined v5 may later be offered v7, which contains v5's changes.

Consider:

- How you handle several updates accumulating before the user makes the accept or decline
  choice.
- What "declined" actually pins.
