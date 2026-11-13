# Current systems

> This is the brief as it was given, reformatted for reading. The facts and figures are
> unchanged. Values in **bold** were marked as placeholder values in the original.

## Product template database

- All versions of all product templates are stored in a database.
- An individual template can be retrieved quickly, given a template ID and a version.
- Publishing an updated product template means writing to this database.
- Creating a new engagement file means reading from this database, to retrieve the template
  file that the new engagement file is created from.
- The database is shared between customer firms, because they all use the same product
  templates.
- It does not currently hold any information about engagement files.

## Product templates

Product templates are essentially zip archives of structured JSON data.

You can assume that you have a quick and reliable way to compare two versions of the same
product template and extract a JSON diff. However, the users are non-technical and would
strongly prefer something human-readable.

## Engagement files

### Where they are stored

Engagement files are stored in their own customer-specific databases.

Engagement content is client-confidential audit workpaper material. Some firms are
contractually or legally required to keep their data in-region (**EU**, **Canada**).
Product template content is not firm-specific.

### Reading one is slow

Engagement files currently store the template ID and version used for their creation.

However, the stored form of an engagement is not a directly queryable record of its current
state. Opening one rehydrates it into memory in the pods serving that session, where
volatile financial fields are established.

Reading the template information therefore requires loading the engagement, which is a
slow operation: about 1 minute per file. Treat this as a hard constraint.

### The engagement management system

The same system that loads engagements is also responsible for:

- creating engagements
- processing the user's accept or decline decision

## Operating conditions

- All of these systems are already live.
- There is no maintenance window.
- The pending-update indicator needs to work for existing engagements, not only for newly
  created ones.

## What you may assume

You can add hooks or events to any action taken by either the product template storage
system or the engagement management system.
