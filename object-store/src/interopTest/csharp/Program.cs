using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using VibeMiner.Objects;

// The harness assembly uses the original library's existing InternalsVisibleTo test seam.
if (args[0] == "crash-writer") {
    var requested = Enum.Parse<StorePoint>(args[2]);
    var options = new ObjectStoreOptions { Fault = point => {
        if (point != requested) return;
        Console.WriteLine($"READY {requested}"); Console.Out.Flush();
        if (Console.In.ReadLine() is null) throw new Exception("Parent disconnected before termination");
        throw new Exception("Parent should terminate the blocked writer");
    }};
    await using var child = await ObjectStore.OpenAsync(Path.GetFullPath(args[1]), options);
    var bytes = Encoding.ASCII.GetBytes("crash target");
    await child.PutAsync([new(new ObjectKey(ObjectKeyType.Config,MD5.HashData(bytes)),bytes.Length,bytes)],true);
    throw new Exception("Requested crash boundary was not reached");
}
if (args[0] is "recover-crash" or "recover-rebuild") {
    await using var recovered = await ObjectStore.OpenAsync(Path.GetFullPath(args[1]));
    if (args[0] == "recover-crash" && !recovered.OpenReport.FullIndexCheck) throw new Exception("Killed Java writer was incorrectly marked clean");
    var baseline = Encoding.ASCII.GetBytes("committed baseline"); var target = Encoding.ASCII.GetBytes("crash target");
    var read = await recovered.ReadAsync(new(ObjectKeyType.Config,MD5.HashData(baseline)));
    if (read.State != ReadState.Present || !read.Bytes!.SequenceEqual(baseline)) throw new Exception("Committed baseline was lost");
    bool expected = bool.Parse(args[2]);
    var targetRead = await recovered.ReadAsync(new(ObjectKeyType.Config,MD5.HashData(target)));
    if ((targetRead.State == ReadState.Present) != expected) throw new Exception("Recovered target visibility differs");
    if (expected && !targetRead.Bytes!.SequenceEqual(target)) throw new Exception("Recovered bytes differ");
    if ((await recovered.ReadHistoryAsync(new())).Count != 1) throw new Exception("Committed history was lost");
    var verified = await recovered.VerifyAsync(true);
    if (verified.CorruptCount != 0 || verified.LostCount != 0) throw new Exception("Recovered store failed verification");
    Console.WriteLine("C# recovered an interrupted Java store"); return;
}
if (args[0] == "verify-vectors") {
    using var input = JsonDocument.Parse(await File.ReadAllTextAsync(args[1]));
    int count = 0;
    foreach (var vector in input.RootElement.EnumerateArray()) {
        var key = ObjectKey.Parse((ObjectKeyType)vector.GetProperty("type").GetInt32(), vector.GetProperty("key").GetString()!, (byte)vector.GetProperty("namespace").GetInt32());
        var bytes = Convert.FromBase64String(vector.GetProperty("data").GetString()!);
        long declared = vector.GetProperty("declared").GetInt64();
        string? expected = vector.GetProperty("expected").ValueKind == JsonValueKind.Null ? null : vector.GetProperty("expected").GetString();
        string? actual = ObjectVerification.Default.Check(key, bytes, declared)?.Code;
        if (actual != expected) throw new Exception($"Verification vector {count}: expected {expected ?? "accepted"}, got {actual ?? "accepted"}");
        using var check = ObjectVerification.Default.Begin(key, declared);
        ObjectRefusal? incremental = null;
        for (int at = 0; at < bytes.Length; at += 7) {
            incremental = check.Append(bytes.AsSpan(at, Math.Min(7, bytes.Length-at)));
            if (incremental is not null) break;
        }
        incremental ??= check.Complete();
        if (incremental?.Code != expected) throw new Exception($"Incremental vector {count}: expected {expected ?? "accepted"}, got {incremental?.Code ?? "accepted"}");
        count++;
    }
    Console.WriteLine($"C# matched {count} whole and fragmented verification vectors"); return;
}
if (args[0] == "generated-exchange") {
    await using var generated = await ObjectStore.OpenAsync(Path.GetFullPath(args[1]), new ObjectStoreOptions { Kind = ObjectStoreKind.Generated });
    var key = new ObjectKey(ObjectKeyType.Generated, new byte[16]);
    var read = await generated.ReadAsync(key);
    if (read.State != ReadState.Present || !read.Bytes!.SequenceEqual(new byte[]{1,2,3})) throw new Exception("Generated Java bytes unreadable");
    var second = new byte[16]; second[0] = 1;
    await generated.PutAsync([new(new ObjectKey(ObjectKeyType.Generated, second), 3, new byte[]{4,5,6})], true);
    if ((await generated.ScanAsync()).Live != 2 || (await generated.VerifyAsync(true)).CorruptCount != 0) throw new Exception("Generated rebuild failed");
    Console.WriteLine("C# read/wrote generated data and rebuilt its index"); return;
}
if (args[0] is "stage-exchange" or "stage-save") {
    var bytes = await File.ReadAllBytesAsync(Path.Combine(args[1], "encoded.bin"));
    int headerLength = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(4));
    var key = new ObjectKey(ObjectKeyType.Encoded, MD5.HashData(bytes.AsSpan(0,headerLength)));
    await using var stagedStore = await ObjectStore.OpenAsync(Path.Combine(args[1], "store"));
    await using var stage = await stagedStore.StageAsync(key,bytes.Length);
    int expected = headerLength + System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(12));
    if (args[0] == "stage-save") {
        if (stage.Length != 0) throw new Exception("Expected a new staging");
        await stage.AppendAsync(bytes.AsMemory(0, expected+2));
        Console.WriteLine("C# saved a chunked staging with an unproven tail"); return;
    }
    if (stage.Length != expected) throw new Exception("Java/C# staged proof differs");
    await stage.AppendAsync(bytes.AsMemory((int)stage.Length));
    if ((await stage.CommitAsync()).Outcome != PutOutcome.Stored) throw new Exception("C# staged commit failed");
    Console.WriteLine("C# resumed Java chunked staging and committed the object"); return;
}
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
