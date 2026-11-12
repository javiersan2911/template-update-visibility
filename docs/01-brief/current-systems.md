# Current systems

All versions of all product templates are stored in a database, and individual templates can
be quickly retrieved given a template ID and a version. Publishing an updated product
template involves writing to this database. Creating a new engagement file involves
reading from this database in order to retrieve the template file to use to create the new
engagement file.
Product templates are essentially zip archives of structured JSON data. You can assume
that you have access to a quick and reliable way to compare two different versions of the
same Product Template and extract a JSON diff. However, the users are non-technical and
would strongly prefer something human-readable.
Notably, the product template database is shared between customer firms, as they all use
the same product templates. This database does not currently retain any information
about Engagement files, which are stored in their own customer-specific databases.
Engagement content is client-confidential audit workpaper material, and some firms are
contractually or legally required to keep their data in-region («EU», «Canada»). Product
template content is not firm-specific.
Engagement files currently store the template ID and version used for their creation.
However, the stored form of an engagement is not a directly queryable record of its current
state — opening one rehydrates it into memory in the pods serving that session, where
volatile financial fields are established. Reading the template information therefore
requires loading the engagement, which is a slow operation (~1 minute per file; this should
be treated as a hard constraint). The same system which loads engagements is also
responsible for handling engagement creation, and for processing the user's
accept/decline decision.
All of these systems are already live. There is no maintenance window, and the pending-
update indicator needs to work for existing engagements, not only newly created ones.
You may assume you can add hooks or events to any actions taken by either the product
template storage system, or by the engagement management system.
