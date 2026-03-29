# Audit Event 9aae8ce88ae0afb39f6cde1db66fd8eafda68ea53be2a39e2e3623d3e1a27b60

- Timestamp: 2026-03-29T10:05:36Z
- Actor: topherbuckley
- Event: issue_comment.created
- Target URL: https://github.com/tekkura/sr-android/pull/222#issuecomment-4149835218

## Raw Event

```json
{
  "timestamp": "2026-03-29T10:05:36Z",
  "event_ts": "2026-03-29T10:05:20Z",
  "repo": "tekkura/sr-android",
  "event_name": "issue_comment",
  "action": "created",
  "actor": "topherbuckley",
  "run_id": "23706662523",
  "run_attempt": "1",
  "sender": {
    "login": "topherbuckley",
    "html_url": "https://github.com/topherbuckley"
  },
  "issue": {
    "number": 222,
    "title": "feat(ROS): Implement rosbridge-test app",
    "state": "open",
    "html_url": "https://github.com/tekkura/sr-android/pull/222",
    "milestone": {
      "number": 7,
      "title": "ROS"
    }
  },
  "comment": {
    "id": 4149835218,
    "html_url": "https://github.com/tekkura/sr-android/pull/222#issuecomment-4149835218",
    "created_at": "2026-03-29T10:05:20Z",
    "updated_at": "2026-03-29T10:05:20Z",
    "body": "Reproduced the smoke test against rosbridge and found that the publish step is currently broken.\n\nWhat I verified:\n- Android connects successfully to rosbridge\n- Android subscribes successfully to `/test_from_ros`\n- The app sends `{\"op\":\"publish\",\"topic\":\"/test_from_android\",\"msg\":{\"data\":\"...\"}}`\n\nWhat rosbridge reports:\n- `publish: Cannot infer topic type for topic /test_from_android as it is not yet advertised`\n\nSo the current implementation is not yet satisfying the documented smoke test publish path. The client needs to advertise `/test_from_android` as `std_msgs/msg/String` before publishing, and ideally surface that publish pass/fail result in the app/logs as described in the migration guide.\n\nSeparately, I’ve opened a supporting PR that adds a repo-local Docker-based rosbridge setup so this smoke test can be reproduced without a host ROS install:\n- #223 https://github.com/tekkura/sr-android/pull/223\n\nI’d recommend merging that Docker support first so this PR can be re-tested in the cleaner, repeatable environment, then re-checking the publish path after the advertise fix.\n"
  },
  "milestone": null,
  "changes": null,
  "severity": "INFO",
  "target_url": "https://github.com/tekkura/sr-android/pull/222#issuecomment-4149835218",
  "event_id": "9aae8ce88ae0afb39f6cde1db66fd8eafda68ea53be2a39e2e3623d3e1a27b60"
}
```
