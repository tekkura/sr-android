# Audit Event 18eebfbdab378ece2cdb2ee690be7dadbd6faf0f884c02dc8ec327a7331df4eb

- Timestamp: 2026-09-12T06:01:37Z
- Actor: topherbuckley
- Event: pull_request_review_comment.created
- Target URL: https://github.com/tekkura/sr-android/pull/325#discussion_r3995338461

## Raw Event

```json
{
  "timestamp": "2026-09-12T06:01:37Z",
  "event_ts": "2026-09-12T06:01:22Z",
  "repo": "tekkura/sr-android",
  "event_name": "pull_request_review_comment",
  "action": "created",
  "actor": "topherbuckley",
  "run_id": "34677056160",
  "run_attempt": "1",
  "sender": {
    "login": "topherbuckley",
    "html_url": "https://github.com/topherbuckley"
  },
  "issue": null,
  "pull_request": {
    "number": 325,
    "title": "Communication framing: CRC length-prefix protocol",
    "state": "open",
    "html_url": "https://github.com/tekkura/sr-android/pull/325",
    "milestone": null
  },
  "comment": {
    "id": 3995338461,
    "html_url": "https://github.com/tekkura/sr-android/pull/325#discussion_r3995338461",
    "created_at": "2026-09-12T06:01:22Z",
    "updated_at": "2026-09-12T06:01:22Z",
    "body": "#codex-reply\nFixed in 61223f05ef2dfa6788ccac2de8ff53a21dc1c347\n\n`onReady` now runs outside `lifecycleLock`; the manager only reacquires the lock afterward to confirm the same run is still active before scheduling the writer. Added regression coverage for readiness callbacks that re-enter `SerialCommManager`.\n"
  },
  "milestone": null,
  "changes": null,
  "severity": "INFO",
  "target_url": "https://github.com/tekkura/sr-android/pull/325#discussion_r3995338461",
  "event_id": "18eebfbdab378ece2cdb2ee690be7dadbd6faf0f884c02dc8ec327a7331df4eb"
}
```
