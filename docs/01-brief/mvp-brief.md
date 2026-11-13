# Initial implementation: MVP brief

> This is the brief as it was given, reformatted for reading. The content is unchanged.

## What to build

Implement one narrow component in Java: the **template-publish fan-out worker**.

This is not a full application build.

## Why it is hard

- A single publish may require processing a large number of engagements.
- The downstream call you depend on takes about 1 minute per engagement.
- That downstream system is owned by another team and has limited capacity.

## Guidelines

- Demonstrate appropriate handling of:
  - concurrency
  - failure
  - idempotency
  - downstream capacity
- Focus on interfaces, contracts and correctness.
- Tests or validation logic are a plus.
