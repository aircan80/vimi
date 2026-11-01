using System.Text;
using VibeMiner.Builds;
using VibeMiner.Objects;

var path = Path.GetFullPath(args[1]);
await using var store = await CatalogStore.OpenAsync(path, CancellationToken.None);
if (args[0] == "write") {
    string text = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n## seqn = 7\nus|" + new string('a',32) + "|" + new string('b',32) + "|one\n";
    var entry = new HistoryEntry(HistoryKind.Versions,"wow","us",DateTimeOffset.FromUnixTimeMilliseconds(123456),"https://us.version.battle.net/v2/products/wow/versions",new ObjectKey(ObjectKeyType.HistoryDocument,new byte[16]),new ObjectKey(ObjectKeyType.HistoryRecord,Enumerable.Repeat((byte)1,16).ToArray()));
    await store.AddAsync(new ProjectedEntry(entry, VersionsReply.Read(entry,Encoding.UTF8.GetBytes(text))),CancellationToken.None);
} else {
    var list = await store.ListAsync("wow",CancellationToken.None);
    if (list.Builds.Count != 1 || list.Builds[0].BuildConfig != new string('a',32) || list.Builds[0].VersionsName != "one" || !list.Builds[0].Current || list.Builds[0].Regions.Count != 1) throw new Exception("Catalog projection differs");
    Console.WriteLine("VERIFIED");
}
