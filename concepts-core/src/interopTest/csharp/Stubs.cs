namespace VibeMiner.Builds { public sealed record BuildRef(string Product,string BuildConfig,string CdnConfig,string? Region=null); }
namespace VibeMiner.Concepts.Items {
 public sealed record ItemQuery(string Query="",uint? ExactId=null,int Limit=50,string? After=null);
 public enum ItemNameState {Valid,Empty,Invalid,MissingSparse,SparseUnknown,SparseProtected}
 public sealed record ItemName(ItemNameState State,string? Value);
}
namespace VibeMiner.Concepts.Creatures {
 public sealed record CreatureQuery(string Query="",uint? ExactId=null,int Limit=50,string? After=null);
 public enum CreatureNameState {Valid,Empty,Invalid}
 public sealed record CreatureName(CreatureNameState State,string? Value,string? Issue=null);
}
