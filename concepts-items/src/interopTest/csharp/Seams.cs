// Type-only seams for linking unchanged original ItemFacts.cs, not a duplicate implementation.
namespace VibeMiner.Formats {
 public sealed record Db2Column(string Name,string Kind,bool IsId);
 public sealed record Db2TextIssue(int ColumnIndex,string Code,string RawBytesHex);
 public sealed record Db2Row(uint Id,bool IsCopy,uint? CopySourceId,IReadOnlyList<string> Values,IReadOnlyList<Db2TextIssue>? TextIssues=null);
}
namespace VibeMiner.Concepts.Items {
 public enum ItemNameState {Valid,Empty,Invalid,MissingSparse,SparseUnknown,SparseProtected}
 internal static class ItemLayout { public const string ItemTable="Item",SparseTable="ItemSparse"; }
 internal static class ItemsSourceException { public const string SourceInvalid="ITEMS_SOURCE_INVALID"; }
}
