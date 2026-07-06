# Audit Event d474cdf6fac79d09fd6affe3bd3e9aa2656da7f5ed03734f84fa8a5bd0a56cc6

- Timestamp: 2026-07-06T11:53:18Z
- Actor: topherbuckley
- Event: pull_request_review_comment.edited
- Target URL: https://github.com/tekkura/sr-android/pull/270#discussion_r3528597262

## Raw Event

```json
{
  "timestamp": "2026-07-06T11:53:18Z",
  "event_ts": "2026-07-06T11:53:02Z",
  "repo": "tekkura/sr-android",
  "event_name": "pull_request_review_comment",
  "action": "edited",
  "actor": "topherbuckley",
  "run_id": "28789538933",
  "run_attempt": "1",
  "sender": {
    "login": "topherbuckley",
    "html_url": "https://github.com/topherbuckley"
  },
  "issue": null,
  "pull_request": {
    "number": 270,
    "title": "Instrumentation tests/output format",
    "state": "open",
    "html_url": "https://github.com/tekkura/sr-android/pull/270",
    "milestone": {
      "number": 13,
      "title": "InstrumentationTests"
    }
  },
  "comment": {
    "id": 3528597262,
    "html_url": "https://github.com/tekkura/sr-android/pull/270#discussion_r3528597262",
    "created_at": "2026-07-06T11:52:06Z",
    "updated_at": "2026-07-06T11:53:02Z",
    "body": "#codex-review\r\nfixed in [556b936](https://github.com/tekkura/sr-android/pull/270/commits/556b93684f1ba5237dc7a6187027205290c7f16a)\r\n"
  },
  "milestone": null,
  "changes": {
    "body": {
      "from": "#codex-review:\r\nfixed in [556b936](https://github.com/tekkura/sr-android/pull/270/commits/556b93684f1ba5237dc7a6187027205290c7f16a)\r\n"
    }
  },
  "severity": "HIGH",
  "target_url": "https://github.com/tekkura/sr-android/pull/270#discussion_r3528597262",
  "event_id": "d474cdf6fac79d09fd6affe3bd3e9aa2656da7f5ed03734f84fa8a5bd0a56cc6"
}
```

## Field Diffs

### body

```diff
diff --git a/tmp/tmp.pWFyFu8HPd/before b/tmp/tmp.pWFyFu8HPd/after
index 7aa1831c..e122f09c 100644
--- a/tmp/tmp.pWFyFu8HPd/before
+++ b/tmp/tmp.pWFyFu8HPd/after
@@ -1,2 +1,2 @@
-#codex-review:
+#codex-review
 fixed in [556b936](https://github.com/tekkura/sr-android/pull/270/commits/556b93684f1ba5237dc7a6187027205290c7f16a)
```
