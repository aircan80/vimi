# Upstream review

- Repository: https://github.com/fitzforge/polestar
- Published folder: `forever-db/`
- Reviewed immutable repository commit: `63bafa4fadbe55fc3000c5bdf3d7f4fc2d5f3b9e`
- Publication version: `2026.11.1`, schema 1, client build `1.60.1.70532`
- Producer commit recorded by upstream: `6d0de256e66674512e453a2990076d84f7e979bb`

Acquisition uses only `api.github.com` to resolve the fixed `next` branch and
`raw.githubusercontent.com` for commit-pinned published files. Redirects, external
manifest paths, mutable raw-file revisions and arbitrary repository paths are refused.
No requests go to Polestar's website, Wago, CMaNGOS, Questie or other upstream sources.

Reviewed documents, retained with every downloaded snapshot:

- [README](https://github.com/fitzforge/polestar/blob/63bafa4fadbe55fc3000c5bdf3d7f4fc2d5f3b9e/forever-db/README.md)
- [NOTICE](https://github.com/fitzforge/polestar/blob/63bafa4fadbe55fc3000c5bdf3d7f4fc2d5f3b9e/forever-db/NOTICE.md)
- [GPL 3 license](https://github.com/fitzforge/polestar/blob/63bafa4fadbe55fc3000c5bdf3d7f4fc2d5f3b9e/forever-db/LICENSE)
- [CC BY 4 license](https://github.com/fitzforge/polestar/blob/63bafa4fadbe55fc3000c5bdf3d7f4fc2d5f3b9e/forever-db/LICENSE-CC-BY-4.0.txt)

A separate temporary-library live check downloaded and validated all 15 data resources
and prepared 211,932 records, including 13,239 NPCs. The retained source files occupied
13,456,335 bytes and the derived SQLite database 33,058,816 bytes at that review. This
check did not write to the user's application library. Normal tests remain offline.
