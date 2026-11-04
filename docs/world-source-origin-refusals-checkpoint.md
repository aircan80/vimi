# Typed negative source outcomes

Implementation of reviewed16e882c on extracted Map helper c6297f5, typed strict
default marker e555dd6 and separate cancellation/end ownership correction f0e2137.
No rendering-bgfx dependency, packet writer, controller or GUI route is introduced.

WorldTerrainStreaming.Refusal is private-constructed, scalar and compatible with
IllegalStateException. Its fixed reason/site uses at most256 ASCII message bytes,
no provider cause and no stack payload. Package-only WorldSourceRefusals factories
accept a finite Site enum. At public source boundaries, at most eight exact standard
async wrappers are inspected; unknown cause getters are never called. Auxiliary
failure on any wrapper/terminal prevents expected classification and becomes fatal.
Unknown provider/program errors remain fatal even when scope validation invalidates
its metadata. Closing a written output is always cleanup, never an expected refusal.

Known source exceptions now originate at explicit guards: output/geometry/worker/
actual atlas pool/native-ID inspector pressure is retryable; explicit default
strict marker/profile limits and unavailable qualified native/numeric/parser/input
profiles are capability-unavailable; actual identity comparisons or own scope/
owner retirement are source-invalidated. Foreign scope/caller mismatch, phase/
thread/duplicate-page/verified-witness contradictions, generation exhaustion,
unknown exceptions and cleanup failure remain fatal. Private strict material
SourceFailure.CHANGED is recognized only by its actual internal type/reason through
clean bounded standard wrappers. Legacy public material behavior is unchanged.

WorldMapProofs converts ONLY CapturedRowsTypes.BoundedReadUnsupportedException at
the strict read invocation, never a provider's plain UnsupportedOperationException.
The marker is a private-constructed UOE subtype made by the default API; no reflection
or implementation guessing is used. Explicit LIMIT is capability-unavailable;
CLOSED/INVALIDATED is retired ownership; CORRUPT or contradictory EOF/row shape is
fatal. Explicit CANCELED remains cancellation even when external token was not set,
preserving healthy proof and allowing retry. Actual private selected-ID unsupported
profile/BUSY/SOURCE_CHANGED statuses remain distinct; mismatched positive witness
is fatal rather than downgraded into a friendly capability refusal.

Actual cached root geometry and all positive/negative source checks still run under
the source-active ownership guard. Typed exceptions do not relax callback/channel/
borrow drain, twelve issued outputs, twelve geometry versions, eighteen atlas slots
or existing metadata/input limits. Genuine full/partial textures and qualified
terminal HEIGHT_FALLBACK stay successful outputs; no refusal is serialized as height.
Actual nullable compositionCacheSha256 is unchanged; materialDigest is separately
specified by the source-neutral packet amendment.

Authored checks include real twelve-output and eighteen-pool reservations with no
writer/extra root IO on pressure and successful recovery; native receipt plus actual
default strict marker versus overridden plain UOE without any legacy read; explicit
Map/WDT/material and absent→present identity changes with R retirement; unknown
provider error remaining fatal despite unknown scope; expected identity refusal
plus throwing cleanup becoming fatal; bounded wrapper/cancellation auxiliary errors,
private constructor/scalar/empty-stack shape and arbitrary cause-getter trap.
Prior provenance tests now assert the precise capability-versus-contradiction split.
Full concepts-world checks pass115 tests. These are source/ownership checks, not
GPU/native-child or movement performance claims.

The future fixed bridge alone maps a CLEAN Refusal reason/site into destination
prepared-refusal metadata without carrying this exception/provider cause. Parent
transport keeps its current table while retiring the unused reservation after real
producer/stop-callback/file drain. Optional.empty maps to unavailable capability at
that bridge without calling legacy providers. Actual SourceInvalidated/capability
refusal is not an automatic repair/retry loop or permission to release a GPU owner.

Independent review followup: activation checks the scope owner before any expected
retirement or busy outcome. A foreign/null scope remains a caller error. Only an
exact JDK CancellationException with no cause or suppression qualifies as clean
cancellation; unknown subclasses become ordinary fatal failures without inspecting
overridable cause/message accessors. Three causal fixtures cover foreign activation,
a subclass containing a cleanup cause, and hostile subclass accessors. The first
two fail against the frozen original checkpoint. Full concept checks pass with the
followup. This does not change source profiles, source bindings, or native admission.

Trusted asynchronous source awaits now preserve the provider future's ORIGINAL failure before
JDK25 join can synthesize CancellationException(cause=original). Package-private WorldSourceFutures
encapsulates that stored failure in a fixed-message standard CompletionException, preserving an
existing CompletionException unchanged. It never unwraps dirty/foreign cancellation or invokes
foreign cause/message accessors; error meaning stays with the existing strict source boundary.
Exact Scope stat/open/read and Map capture/strict-page/native-inspection/stat awaits use it, and
owned material Operation.awaitResult delegates to the same helper. Public legacy read and cleanup
CancelLane.done joins are unchanged. This helper promises neither provider cancellation nor IO
retirement: existing try/finally stream closure, active guard and actual cancellation/material drain
still determine lifetime.

A genuine public Source fixture holds an actual ROOT stream future, observes own cancellation,
completes the future with the original clean stop, and proves stream retirement, original exception
identity, zero material claims and fresh same-scope textured recovery. It was causally RED before
this correction. Dirty cause and foreign-cancellation getter traps remain fatal; helper tests also
pin existing dirty completion-wrapper identity. Full concepts-world check:143 tests pass, with
formatter and both Checkstyle gates. No WorldGlobalScopes changes or source/memory cap changes.
