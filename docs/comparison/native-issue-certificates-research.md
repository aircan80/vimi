# Native text issue provenance research

Existing cached graphs remain readable with unknown issue-channel semantics. A
null issue list is not a certificate of valid text at the generic capture boundary.
This research does not change parsing, caches or comparison admission.

## Observed producer behavior and limits

`Db2Table.decode` and its copy-row path collect decoding issues and return null
when that native list is empty. However, `RowCodec` deliberately preserves null
versus explicit empty lists, and generic `GeneratedTables.store/getOrBuild`
accepts custom rows under the same reader/encoding revision labels. Those labels
cannot retrospectively prove native origin. Completion metadata is captured before
row enumeration in this path; aggregate zero counts also cannot certify rows.

## Proposed future explicit contract

Introduce an audited native content factory with an explicit versioned issue
policy, default UNKNOWN for generic content. Bind the native policy and producer
revision into generated graph identity and metadata. Capture receipts must expose
and validate that policy against the exact graph identity. Old graphs without a
certificate remain UNKNOWN, with no repair, acquisition, migration or inferred
upgrade during local comparison.

Only that certified capture boundary may normalize a native null issue channel
to an explicit empty list. Parser DTOs, generic RowCodec and custom producers retain
their existing semantics. Bounding/truncation must downgrade the affected row to
LIMITED. Explicit issues survive; native certification means detection ran, not
that all rows or localized values are valid.

A PARTIAL table can expose valid readable cells under this policy. It never gains
certified membership, absent rows or full localization from the certificate.

## Required acceptance evidence

- Real Db2Reader/native factory/store/reopen/capture round trip for valid text,
  including known empty text.
- Invalid UTF-8 and missing noninline relations retain distinct typed issues;
  valid sibling fields survive.
- Generic custom rows with identical legacy labels and null channels stay UNKNOWN.
- Old metadata stays UNKNOWN without repair, decode, acquisition or migration.
- Certificate/identity mismatch cannot promote evidence.
- Truncated issue channels remain LIMITED and cannot fabricate known empty cells.
- Parser/C# goldens and RowCodec null/empty round trips remain unchanged.
- Partial/protected sources retain uncertified membership.

An independent WoW-data reviewer researched this contract. Implementation and
exact integration review remain future work; the present comparison behavior
continues to preserve conservative uncertainty and observed raw evidence.
