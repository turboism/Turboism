# Release API health monitor

The `Release API health monitor` Actions workflow checks `api.turboism.dev`
independently of Cloudflare. It lives in the public product repository, uses a
standard GitHub-hosted runner and Python's standard library, and needs no new
service account or stored secret.

## Checks

- Every ten minutes (UTC minutes 4, 14, 24, 34, 44, 54), and after monitor changes
  reach `main`. Actions also offers **Run workflow** for a manual check.
- GET `/health` and `/v1/releases/{stable,beta,nightly}.json` in parallel. No
  software downloads, synchronization POSTs, counter changes or deployments.
- HTTP errors (including 5xx), timeouts, invalid JSON and unavailable channel
  states are failures. Each failed endpoint gets up to three attempts, twenty
  seconds apart, with a fifteen-second request timeout.
- `not_published`, including an empty Beta channel, is normal. A `degraded`
  health response is usable while the last successful sync is less than one
  hour old. A sync age over one hour raises an alert even with HTTP 200, rather
  than waiting for the API's 24-hour fallback window to expire.

## Notifications and recovery

A confirmed incident opens an issue titled `[API monitor] api.turboism.dev 异常`,
assigns and mentions `RainTrap341`, and includes the failing paths, HTTP statuses,
check time and Actions run link. Notifications arrive through GitHub; email and
mobile delivery depend on the recipient's GitHub notification settings. This is
not a ChatGPT scheduled notification or a Discord webhook.

While that bot-owned incident remains open, later failures are recorded in each
Actions run summary without creating another issue or posting repeated comments.
When all checks pass, the monitor appends a recovery record and closes the issue
in a single API operation. A later new outage opens a new incident. It does not
edit human-created issues, pull requests, product releases or Cloudflare settings.

A workflow success means the monitor ran and delivered any required incident
update, not necessarily that the API was healthy. Read its summary / open incident.
Notification or monitor execution errors fail the workflow; incident failures that
were successfully reported do not trigger redundant Actions failure emails.

GitHub schedules are best-effort and can be delayed or dropped during high load;
this is not a guaranteed ten-minute notification SLA. Public-repository schedules
can be disabled after 60 days without repository activity. Re-enable the workflow
in Actions if that happens. See GitHub's scheduled-workflow and notification docs.

## Tests and safe local checks

```sh
python3 -m unittest discover -s scripts/monitor -p 'test_*.py' -v
python3 scripts/monitor/release_api_monitor.py
```

The first command is entirely offline with simulated GitHub writes. The second
performs read-only live checks without posting anything. `--notify` is restricted
to the trusted main-branch workflow and uses its short-lived `GITHUB_TOKEN` with
only `contents: read` and `issues: write`. Pull request tests cannot notify.
