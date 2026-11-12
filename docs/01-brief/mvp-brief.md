# Initial implementation: MVP brief

Implement one narrow component in Java: the template-publish fan-out worker. A single
publish may require processing a large number of engagements, and the downstream call
you depend on takes ~1 minute per engagement and is owned by another team with limited
capacity.
Guidelines:
• Demonstrate appropriate handling of concurrency, failure, idempotency and
downstream capacity
• Focus on interfaces, contracts and correctness; tests or validation logic are a plus
This is not a full application build.
