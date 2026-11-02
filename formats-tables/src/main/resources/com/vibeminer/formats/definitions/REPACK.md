# Java resource packaging

The unmodified WoWDBDefs definition bytes and original manifest, source listing,
license and attribution are copied from the pinned C# reference provider revision
b9a63096cdd99b26967f17f9515377ae62cadfd7. Only compression changed: the original
Brotli stream was decoded and losslessly repacked using deterministic gzip
(mtime=0), supported by the JDK without an additional codec/native dependency.

The decoded stream is 22,554,326 bytes; SHA-256:
73d47b200e9ddc94c67c7f3e3b3c5832c8ab80b554ca06353348986eb6af7dbe.
Every original per-file length and SHA-256 in sources.json was verified during
repacking. Runtime checks the original manifest/source hashes, decoded stream
hash/length, and each requested definition's original hash before parsing.
sources.json intentionally remains unchanged and describes the upstream Brotli
artifact; definitions.gz is the Java distribution's packaging of the same bytes.
