# HTTP activity counters

A JDK-only optional observer shared by the OkHttp network clients and the independent
Questie HTTP client. It stores bounded category/phase counters and application-visible
response-body bytes; it never stores URLs, headers, transport objects or payloads.
There are no threads, listeners or GUI dependencies in this module.

Use one `NetworkActivity` for an application session and pass it to the additive
`NetworkClients(NetworkOptions, TransferObserver)` and
`QuestieRepository.open(Path, Cancellation, TransferObserver)` overloads. Existing
constructors use a no-op observer. Poll `snapshot()` at most four times per second;
`TransferRate` computes a bounded two-second rate using snapshot monotonic timestamps.
Call `reset()` when resuming a hidden UI so hidden-period bytes do not become a spike.

An attempt begins after queue admission, before HTTP connection/header waiting.
Headers and read boundaries distinguish waiting for a response, a pending body
read, and an open transfer handed back to application code. EOF, failure and close
end activity idempotently. Positive bytes returned by a read are counted even if
close raced that read; terminal attempts never become active again. Each successful
read must report its result exactly once. Optional observer RuntimeExceptions are
isolated by the transport's `TransferObserver.safe` adapter.

The total includes actual body bytes read from metadata, archives and partial or
repeated attempts. It excludes cache reads, declared-but-unread content lengths,
expanded/decoded sizes, headers, TLS and transport prefetch/discard not exposed to
the consumer. It is not whole-machine network utilization. No expected-size
percentage or local-processing progress is inferred.

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :network-activity:check :network-clients:check :questie:check
```

Tests use fake clocks, authored bodies and loopback servers; no credentials or
live network services are required. See
[the integration proposal](../docs/network-activity-proposal.md) for GUI ownership
and complete coverage limits.
