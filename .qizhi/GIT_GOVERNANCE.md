# QiZhi Git governance

Git-only standard: **v1.3.0** at full commit **a07f432d5cf645a281f54ff35a219b85f0234667**. Public reusable executor pinned to **1b6ec2336d3e80a589d69879b9bd5b8c63488701**. This supplements, never silently replaces, existing project-specific business/AI or module Standards Baseline.

Rules: new branches `type/scope-topic`; new non-merge commit subjects and PR titles `type(scope): description`; PR + passing CI before merging; default **Create a merge commit**; never rewrite production history, tags, or business results. Generated GitHub merge titles are exempt. Existing in-flight PRs may have naming-only exceptions recorded in `.qizhi/governance.lock.json`; project CI is never excepted.

Canonical source: https://github.com/Asteroid-B-612-ZS/QiZhi_CostTools_Standards/blob/v1.3.0/standards/GIT_WORKFLOW_GOVERNANCE_STANDARD.md

Governance CI runs on PR, push and a weekly schedule. If branch protection cannot be enabled, a green check does not itself stop an administrator from merging; latest check results still require review.
