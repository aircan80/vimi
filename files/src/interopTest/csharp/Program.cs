using System.Buffers;
using System.Security.Cryptography;
using System.Text;
using VibeMiner.Files.Names;
using VibeMiner.Generated;
var csv = "0;A/blp\n9;a\\BLP\n4294967295;é"u8.ToArray();
var target = "0;Z/blp\r\n3;New.skin\n4294967295;é\n"u8.ToArray();
var sha = Convert.ToHexStringLower(SHA256.HashData(csv));
var targetSha = SHA256.HashData(target);
if (args[0] == "delta-write") {
    var delta = await ListfileDelta.EncodeAsync(new MemoryStream(csv),new MemoryStream(target),Convert.FromHexString(sha),targetSha,target.Length,1<<20);
    await File.WriteAllBytesAsync(args[1],delta!); Console.WriteLine("WRITTEN"); return;
}
if (args[0] == "delta-read") {
    var delta=await File.ReadAllBytesAsync(args[1]);var output=new ArrayBufferWriter<byte>();
    await ListfileDelta.ApplyAsync(new MemoryStream(csv),new ReadOnlyMemory<byte>[] {delta},output);
    if (!output.WrittenSpan.SequenceEqual(target)) throw new Exception("Delta target differs");
    Console.WriteLine("VERIFIED"); return;
}
await using var store = await GeneratedStore.OpenAsync(Path.GetFullPath(args[1]));
if(args[0] == "index-write") {
    static async IAsyncEnumerable<ReadOnlyMemory<byte>> Chunks(byte[] bytes) {await Task.Yield();for(int i=0;i<bytes.Length;i+=3)yield return bytes.AsMemory(i,Math.Min(3,bytes.Length-i));}
    await ListfileStore.BuildAsync(store,new ListfileStoreRelease("v1",sha,DateTimeOffset.UnixEpoch,DateTimeOffset.UnixEpoch.AddSeconds(1)),Chunks(csv));
    Console.WriteLine("WRITTEN"); return;
}
var opened=await ListfileStore.OpenAsync(store,"v1",sha);
if(opened.State!=GeneratedReadState.Present||opened.Index is null)throw new Exception("Index missing: "+opened.Problem);
var index=opened.Index;
if(index.Count!=3||await index.NameOfAsync(uint.MaxValue)!="é"||await index.NameOfAsync(0)!="A/blp")throw new Exception("Names differ");
if(!(await index.IdsOfAsync("a/Blp")).SequenceEqual(new uint[]{0,9}))throw new Exception("Paths differ");
Console.WriteLine("VERIFIED");
