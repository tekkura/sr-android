# Issue/Milestone Audit Log

This directory stores append-only audit records for issue and milestone mutations.

## Log file

- `audit/events.ndjson`
- `audit/LAST_100.md` (generated rolling human-readable view)

Each line is one JSON event record.

## Event coverage

Workflow: `.github/workflows/issue-milestone-audit-log.yml`

- `milestone`: `created`, `edited`, `opened`, `closed`, `deleted`
- `issues`: `opened`, `edited`, `deleted`, `closed`, `reopened`, `assigned`, `unassigned`, `labeled`, `unlabeled`, `milestoned`, `demilestoned`, `locked`, `unlocked`, `transferred`, `pinned`, `unpinned`

## Required repository settings

1. Protect branch `audit-log`.
2. Allow GitHub Actions to push to `audit-log`.
3. Protect workflow files (`.github/workflows/*`) via `CODEOWNERS` if you need strict governance over audit workflow changes.

## Notes

- This log is intended as an in-repo audit trail visible to all collaborators.
- Keep branch protection strict to preserve audit integrity.
- `audit/events.ndjson` is the canonical source; `audit/LAST_100.md` is a generated convenience view.
