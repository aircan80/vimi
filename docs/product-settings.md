# Product visibility settings

Settings → Products controls which WoW product channels appear in Builds. The
preference is global and persists in versioned `settings_*` tables in the shared
library database. Saved choices override defaults and survive application restart.
The GUI reads cached snapshots and submits bounded asynchronous commands; SQL and
catalog work stay off the JavaFX thread.

Fresh installations enable:

| Product | Channel |
|---|---|
| `wow` | Retail |
| `wow_beta` | Retail Beta |
| `wow_anniversary` | Anniversary |
| `wowf` | Forever |
| `wow_classic_beta` | Forever Beta |
| `wow_cn_beta` | Forever Beta (China) |

There is no separately reviewed Anniversary beta ID in the current product
catalog. Classic Era beta/PTR IDs are not assumed to be Anniversary aliases.
Users can enable additional reviewed or discovered products, or enter an explicit
WoW product ID. Unreviewed discovery remains disabled until selected. Discovered
choices are retained locally so users can enable them later. Restore defaults
clears explicit visibility overrides while preserving the discovered product list.

Disabled products are skipped during per-product catalog reads and refreshes.
The global summary may still be observed to discover new product IDs for Settings.
Turning a product off hides it from browsing; it never deletes build history,
object ownership, saved payloads, preparation receipts or download-all intent.
An already running download policy continues independently of product visibility.
All products may be disabled; Builds then displays an explained empty state.

The service accepts at most 256 product preferences and queues at most 64 pending
changes. Preferences use FULL SQLite commits. Unsupported or incomplete settings
schemas are refused before schema mutation. New contexts load the committed
preference; old readers do not need to hold a database handle.

Validation:

```sh
./gradlew :gui:settingsServiceTest :gui:backendTest
```

Offline fixtures exercise default channels, discovery and custom IDs, explicit
choices across restart, restore-default behavior, retained payloads, callback-safe
shutdown, disabled-product catalog access and unsupported schema refusal. No live
network service or real user data is used.
