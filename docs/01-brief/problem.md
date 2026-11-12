# Problem statement

Our customers work in “Engagement Files” which contain all the work that an accounting
firm would do for a customer over the course of a year. These engagement files are created
based on “Product Templates”, which are regularly updated by our content teams in
different markets.
Engagement files are initially created with the most recent version of a product template,
but those templates receive updates throughout the year. Once a product template has an
update published, that update needs to be evaluated by our customers. For each of their
engagement files, the customer will either apply the update to an engagement or decline
the update.
Users need to see at a glance which of their engagement files have pending updates, and
users need a summary of the pending inbound changes in order to make that apply/
decline decision. We would like you to design a system that can provide users with that
information in an up-to-date manner, for roughly «4,000» firms and «800,000» active
engagement files across «40» products. Engagement counts per firm are heavily skewed:
the median firm has «~40», the largest «~40,000». All-in run cost for the feature, inclusive of
any inference spend, must stay under «$X»/month. Product template updates are relatively
infrequent, on the order of once per week per product.
Product would like this to be available in real time — within seconds of a template being
published.
Template versions do not form a simple line. Markets branch, versions are occasionally
withdrawn after publication, and a firm that declined v5 may later be offered v7, which
contains v5’s changes. Consider how you handle several updates accumulating before the
user makes the accept/decline choice, and what “declined” actually pins.
Actually applying the updated template content is outside the scope of this assignment.
