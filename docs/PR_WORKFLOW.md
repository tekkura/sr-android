# PR Workflow (sr-android)

This repo uses milestone-scoped base branches and a migration guide per milestone. Follow this workflow so reviews are consistent and automated checks behave predictably.

## 1. Branching Plan (Milestone Base Branch)

- Each milestone uses a dedicated base branch:
  - milestone/[milestone-name]

- The first commit on milestone/[milestone-name] must include the milestone migration guide:
  - docs/migrations/[MilestoneName].md

- The migration guide is the primary checklist for all reviewers (including Codex). It defines what "correct" means for that milestone.

- If the migration guide is unclear or needs updates, open a PR against:
  - milestone/[milestone-name]
  (Do not patch the guide ad-hoc inside unrelated feature PRs.)

- All work branches for the milestone must open PRs against the milestone base branch (not main):
  - [milestone-name]/[feature-branch] -> milestone/[milestone-name]

### Editing the guide

- In the process of development or review the migration guide might be considered outdated if:
  - The initial requirements were missing some important considerations
  - The requirements have changed
  - The implementation plan has changed


- If that happens, the migration guide can, and needs to be updated. The update process:
  1. If the migration guide is still in development and no PRs containing it had been opened, just make another commit or rebase the existing one
  2. If PR containing the migration guide is in review process, dismiss all reviews and notify all reviewers that the migration guide had changed
  3. If migration guide is already merged into the milestone/[milestone-name] branch and it is the only PR merged there, open a new PR against the milestone/[milestone-name] branch with the corresponding changes and explanation of the intent behind them.
  4. If migration guide is already merged into the milestone/[milestone-name] branch and it is not the only PR merged there: 
    4.1 Check all previous PRs to see if they need to be updated in accordance with the new migration guide.
    4.2 Repeat step 3 for each task where deemed necessary.
    
## 2. Branch Hygiene

- Rebase your work branch onto the milestone base branch before requesting review/merge.
- Do not use GitHub "Update branch" (it creates merge commits).

- If a rebase invalidates prior approvals:
  - Add a short note in the PR (example: "Rebased onto milestone/<name>; no functional changes except conflict resolution.")
  - If the repo owner is available, they may bypass merge rules to avoid churn rather than waiting for re-approval.

## 3. Codex Review Usage

### Triggers
- Full review trigger (PR comment): #codex-review
- Thread follow-up trigger (inline review thread reply): #codex-reply (+ optional context)

### Behavior Notes
- #codex-review attempts to match new findings to existing unresolved threads and reply there instead of always opening new threads (matching is helpful but not perfect).
- #codex-reply responds with AGREE / DISAGREE / NEEDS_INFO plus evidence, and asks to resolve only on AGREE.

### Guidelines
- Avoid repeated #codex-review runs unless there is a meaningful change, since duplicates create noise.
- If Codex produces duplicates, resolve the most recent instance with a resolution note and link duplicates to the canonical thread.

## 4. Required Checks

PRs are expected to pass:
- rebase-check
- build-test

codex-review is expected to be run, and all findings must be either:
- resolved, or
- explicitly justified (with enough context for a future reader to understand why it was not changed)

## 5. Merge Flow

- Work branches merge into the milestone base branch after approvals/checks:
  - [milestone-name]/[feature-branch] -> milestone/[milestone-name]

- The milestone base branch merges into main when the milestone scope is complete:
  - milestone/[milestone-name] -> main

## 6. Workflow Source Behavior

- codex-review runs from the workflow configuration on main.
- codex-reply depends on the workflow configuration in the PR base branch.

### Practical Implication
- Rebase onto the latest milestone/[milestone-name] before using #codex-reply.

## 7. Resolving Review Threads (Auditability Requirement)

When marking a review thread as resolved, the last comment in that thread must include a short resolution note so the decision is understandable later without re-reading the full diff.

A resolution note must be one of:
- Fixed in <commit hash> (optionally: "by changing A -> B")
- Keeping as-is because <brief rationale>
- Duplicate of <link to the canonical thread> where it was decided (use this for repeated Codex comments)

### Notes
- "Outdated" is not the same as "resolved." Outdated only means GitHub can't anchor the comment cleanly.
- We do not require documenting every historical/duplicate comment. We require the most recent instance of each independent issue in the PR to have a resolution note.
