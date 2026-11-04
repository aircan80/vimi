# Parent frame acknowledgement gate checkpoint

Implements only package-private `WorldStreamParentFrameGate` from the approved parent transport
proposal (`d08b775` + `91af541`). No public facade, process launch/arbiter, source producer,
packet writer, frame polling thread or GUI is wired by this checkpoint.

Before entering the existing mapped-frame decoder, the gate requires a matching observed
CAMERA_APPLIED policy/dimensions AND an exact matching pending COMMITTED transaction/revision.
The requested camera policy alone cannot authorize a detached pixel allocation. Camera-only
updates retain their policy floor and permit monotonically newer lagging cameras during drag;
a policy epoch change clears the old camera ACK and advances that floor. Rejected optional
commits retain the old content. A resize can use the same content receipt: historical commit
policy is not a substitute for the new camera-policy acknowledgement.

The gate snapshots metadata under its short monitor, performs mapping IO outside it, and
rechecks current acknowledgement/terminal state after decoding. The later frame mailbox must
check `current` again before transfer and call `presented` to retire older delivery sequence/
camera values. The gate stores one desired camera, one camera ACK, one pending commit and
scalar presentation fields; no pixels, mapping, source or resident-list ownership is retained.
It deliberately does not replace the future bounded parent transaction/status reducer.

Three authored tests cover real CRC-valid mapped pixels before/after both acknowledgements,
resize versus historical commit policy, continuous-drag lagging frames, standing-still content
change, wrong receipt/nonce, rejected commit retaining old world, policy relabel refusal and
terminal retirement. Removing only the actual camera-ACK requirement makes the real-mapping
assertion fail; restored source passes. No native initialization or game data is involved.

Implementation ledger correction: WorldFrameFileV2 actually owns a reused64KiB CRC scratch,
not the provisional1.6MiB allowance in the parent proposal. Mapping4,800,256 + CRC65,536 + two
facade detached arrays3,200,000 + one later GUI upload array1,600,000 is9,665,792logical bytes.
The earlier11,200,256 ceiling is conservative; concrete mailbox/poller ownership still needs
review before claiming that aggregate runtime peak. GUI WritableImage and lower source-helper/
prepared-metadata/child/JVM/driver owners remain separate.
