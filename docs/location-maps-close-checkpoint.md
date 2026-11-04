# Location Maps nonblocking close

`LocationMapsService.closeAsync` now marks closing under a short lifecycle monitor, then runs worker shutdown, lifetime cancellation and worker drain in exactly one named virtual close task per service. Repeated close calls reuse the same drain future; there is no per-call queued cancellation task. Caller and monitor never run lower cancellation callbacks. New operations reject immediately once closing is marked.

Cancellation signals are not completion: a blocked callback or accepted source operation keeps the close future pending. Borrowed tables/files remain caller-owned. Errors from cancellation are reported only after the accepted worker drains, preserving the existing close error behavior. This narrow fix changes neither strict source evidence, navigation projection, public capability defaults nor source preparation.

The authored test holds a genuine service navigation capture request and its cancellation callback independently. It proves the close caller returns while cancellation is blocked on another thread, repeated close/new navigation do not wait on the lifecycle monitor, new navigation rejects, and close remains pending until both callback and source work release. The borrowed table is not closed. Restoring the old close method causally fails the caller-return assertion; the corrected method and full module checks pass. No GUI, user library, live service or credentials are involved.

```sh
./gradlew :concepts-locations:check
```
