# Hotfix-only key unlock proof

`HotfixKeyCryptoTest` uses synthetic keys and authored bytes through the actual ordinary table view and file/DB2 implementations. The base TactKey contains a zero key, while the base TactKeyLookup supplies the matching ID and key name. An actual parsed hotfix cache supplies the only nonzero TactKey row. Existing `BuildTactKeys.load` produces the immutable key snapshot; no second key parser is involved.

The target is a WDC5 table with a clear encrypted-section ID inventory and a Salsa20-encrypted BLTE chunk covering its section records. Without the key, the table is ENCRYPTED and the known row is PROTECTED. With the effective snapshot supplied to the real FileSystem and its status supplied to TablesService, the same encoded bytes decode to READY and value 555. The generated graph identity changes, and a fresh service using the same unlocked snapshot reuses the complete identity.

Run `./gradlew :formats-tables:test --tests '*HotfixKeyCryptoTest'`. No private assets, live credentials, network services, or production key values are used.
