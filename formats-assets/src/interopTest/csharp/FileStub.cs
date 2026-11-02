// The pure parser only tests whether its source is this type and has holes. No file adapter is substituted.
namespace VibeMiner.Files;
public abstract class FileReadStream : Stream { public IReadOnlyList<object> Holes => []; }
