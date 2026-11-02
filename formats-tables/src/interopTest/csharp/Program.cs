using System.Buffers;
using System.Runtime.CompilerServices;
using System.Text;
using VibeMiner.Formats;
using VibeMiner.Formats.Generated;
using VibeMiner.Generated;

var source=new GeneratedTableSource(null,new string('a',32),"definition",new Db2Limits());
if(args[0]=="goldens") {
    await using var store=await GeneratedStore.OpenAsync(args[1]);var tables=new GeneratedTables(store);
    Console.WriteLine("default-options "+source.ReaderOptions);
    Console.WriteLine("empty-options "+new GeneratedTableSource(null,new string('a',32),"definition",new Db2Limits(VerifiedRawIntegerRules:[])).ReaderOptions);
    Console.WriteLine("identity "+tables.IdentityOf(source,TactKeyStatus.None,[]));
    var definition=DbdDefinitionReader.Parse(Encoding.UTF8.GetBytes("COLUMNS\nint ID\nint Value\n\nLAYOUT 01020304\nBUILD 1.2.3.4\n$id,noninline$ID<u32>\nValue<u32>\n"));
    Console.WriteLine("resolved "+ResolvedDefinition.Hash(definition.Blocks[0]));
    var buffer=new ArrayBufferWriter<byte>();
    foreach(var row in new[]{new Db2Row(7,false,null,["7","9"]),new Db2Row(7,false,null,["7","9"],[]),new Db2Row(8,true,7,["café","18446744073709551615"],[new(1,"issue","ff")])}) {RowCodec.Encode(row,buffer);Console.WriteLine("row "+Convert.ToHexStringLower(buffer.WrittenSpan));}
    Console.WriteLine("column "+Encoding.UTF8.GetString(TableJson.Encode(new Db2Column("ID","integer",true))));
    return;
}
await using(var store=await GeneratedStore.OpenAsync(args[1])) {
    var tables=new GeneratedTables(store);
    if(args[0]=="write") {
        var header=new Db2TableHeader(5,"11223344","01020304",1,1,8,[new("ID","integer",true),new("Value","integer",false)],"WDC5");
        var sections=new[]{new Db2Section(0,1,1,"0000000000000000",false)};
        var completion=new Db2Completion(1,1,1,0,0,0,"complete",288);
        var table=await tables.StoreAsync(source,TactKeyStatus.None,new(header,sections,completion,[],Rows()));
        Console.WriteLine(table.Identity);
    } else if(args[0]=="read") {
        var table=await tables.OpenAsync(source,TactKeyStatus.None)??throw new Exception("table missing");
        var rows=await table.ReadPageAsync(0,10);if(rows.Count!=2||rows[0].Values[1]!="9"||rows[1].CopySourceId!=7)throw new Exception("wrong rows");
        Console.WriteLine(table.Identity);
    } else throw new Exception("unknown command");
}
static async IAsyncEnumerable<Db2Row> Rows([EnumeratorCancellation]CancellationToken token=default){token.ThrowIfCancellationRequested();yield return new(7,false,null,["7","9"]);await Task.Yield();yield return new(8,true,7,["8","9"]);}
