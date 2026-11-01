using System.Security.Cryptography;
using System.Text;
using VibeMiner.Objects;

var folder = Path.GetFullPath(args[1]);
if (args[0] == "busy") {
    try { await using var unexpected = await ObjectStore.OpenAsync(folder); throw new Exception("Writer exclusion failed"); }
    catch (ObjectStoreException e) when (e.Code == "OBJECT_STORE_BUSY") { Console.WriteLine("C# excluded by Java writer"); }
    return;
}
await using var store = await ObjectStore.OpenAsync(folder);
var java = Encoding.UTF8.GetBytes("written by Java");
var csharp = Encoding.UTF8.GetBytes("written by C#");
ObjectKey Key(byte[] bytes) => new(ObjectKeyType.Config, MD5.HashData(bytes));
if (args[0] == "exchange") {
    var read = await store.ReadAsync(Key(java));
    if (read.State != ReadState.Present || !read.Bytes!.SequenceEqual(java)) throw new Exception("Java bytes unreadable");
    if ((await store.ReadHistoryAsync(new())).Count < 1) throw new Exception("Java history unreadable");
    await store.PutAsync([new(Key(csharp), csharp.Length, csharp)], true);
    await store.AppendHistoryAsync(new(HistoryKind.Versions, "wow", "us", DateTimeOffset.UnixEpoch.AddSeconds(1), "https://example.test", csharp));
    if ((await store.ScanAsync()).Corrupt != 0 || (await store.VerifyAsync(true)).CorruptCount != 0) throw new Exception("C# rebuild/verify failed");
    Console.WriteLine("C# read Java data/history, wrote data/history and rebuilt the index");
} else if (args[0] == "hold") {
    Console.WriteLine("READY"); await Console.In.ReadLineAsync();
}
