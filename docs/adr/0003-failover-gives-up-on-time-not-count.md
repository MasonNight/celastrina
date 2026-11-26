# Failover gives up on time, not on a count, and forgets passing failures

Scraper-style Stremio add-ons list many Streams from hosts that are dead or rate limited, and those fail within a
second. v1's "five failures in a row" made Failover give up while working Candidates were still further down the list,
so Failover now gives up only when the Candidates run out or after 90 seconds without playback. Failover memory keeps
only failures that look permanent (HTTP 400, 403, 404, 410, an unreadable format, no first frame once the Address check
has answered, Stalls); a rate limit (429), a temporarily unavailable server (503) or a slow Address check skips the
Stream for that playback only, so a busy host isn't buried for 24 hours.

## Considered Options

- **Keep a count, raised to about ten.** It still punishes long lists of fast failures, and the time limit already
  bounds how long a Household waits.
- **Count only slow failures.** Workable, but two rules for "how long" are harder to explain on the give-up screen
  than one.
- **Honour the server's Retry-After.** Waiting makes the Household wait when other Candidates could play now.

Supersedes the count rule in [Define Failover rules](https://github.com/MasonNight/celastrina/issues/11).
