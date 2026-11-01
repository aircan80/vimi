using System.Text;
using VibeMiner.Generated;
using VibeMiner.Generated.RecordFiles;

var folder = Path.GetFullPath(args[1]);
var key = GeneratedKey.Hash(Encoding.UTF8.GetBytes("interop-record-file"));
static uint Id(int i) => i == 0 ? uint.MaxValue : (uint)(3000 - i) * 2;
static byte[] Payload(int i) => Encoding.UTF8.GetBytes($"record-{i}-" + new string((char)('a' + i % 26), 20 + i % 79));
if (args[0] == "busy") {
    try { await using var unexpected = await GeneratedStore.OpenAsync(folder); }
    catch (GeneratedStoreException e) when (e.Code == GeneratedStoreCodes.Busy) { Console.WriteLine("BUSY"); return; }
    throw new Exception("Java writer did not exclude C# writer");
}
await using var store = await GeneratedStore.OpenAsync(folder);
if (args[0] == "hold") {
    Console.WriteLine("READY"); Console.Out.Flush(); Console.ReadLine(); return;
}
if (args[0] == "write") {
    using var writer = new RecordFileWriter(store, new RecordFileOptions { BlockBytes = 512, SegmentBytes = 2048 });
    for (int i = 0; i < 3000; i++) await writer.AddAsync(Id(i), Payload(i));
    await writer.CompleteAsync(key, "metadata"u8.ToArray());
    Console.WriteLine("WRITTEN"); return;
}
var open = await RecordFile.OpenAsync(store, key);
if (open.State != GeneratedReadState.Present || open.File is null) throw new Exception("Manifest is not present");
var file = open.File;
if (file.Count != 3000 || !file.Metadata.Span.SequenceEqual("metadata"u8)) throw new Exception("Manifest differs");
var ordinal = 0;
await foreach (var record in file.ReadAllAsync()) {
    if (record.Ordinal != ordinal || record.Id != Id(ordinal) || !record.Bytes.Span.SequenceEqual(Payload(ordinal))) throw new Exception("Record differs");
    ordinal++;
}
if (ordinal != 3000) throw new Exception("Record count differs");
foreach (int i in new[] { 0, 1, 999, 1024, 2047, 2999 }) {
    var record = await file.FindAsync(Id(i));
    if (record is null || record.Value.Ordinal != i || !record.Value.Bytes.Span.SequenceEqual(Payload(i))) throw new Exception("Lookup differs");
}
if ((await file.FindAtOrAfterAsync(3))?.Id != 4) throw new Exception("Ceiling differs");
var page = await file.ReadAsync(1020, 20);
for (int i=0; i<page.Count; i++) if (page[i].Id != Id(1020+i)) throw new Exception("Page differs");
Console.WriteLine("VERIFIED");
