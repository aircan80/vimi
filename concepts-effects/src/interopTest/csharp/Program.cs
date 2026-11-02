using System.Globalization;
using System.Text.Json;
using VibeMiner.Builds;
using VibeMiner.Concepts.ItemEffects;
using VibeMiner.Formats;
using VibeMiner.Formats.Definitions;
using VibeMiner.Formats.Tables;
using VibeMiner.Formats.Testing;
var output=args[0];Directory.CreateDirectory(output);
var build=new BuildRef("wow",new string('a',32),new string('b',32),"us");
var bundled=await BundledDefinitions.OpenAsync(default);
string N(long value)=>value.ToString(CultureInfo.InvariantCulture);
TableSchema Schema(string id,string version,string layout){var selected=bundled.Require(id).Document.SelectFile(version,layout);return TableSchema.FromBlock(selected.Blocks.Single(),selected.Match=="layout"?SchemaMatch.Layout:SchemaMatch.Build,WowLocale.EnUS);}
Db2Column[] Columns(ItemEffectProfile profile)=>[..profile.Columns.Select((n,i)=>new Db2Column(n,profile.IsName&&i==1?"string":"integer",i==0))];
Db2Row Row(uint id,params string[] values)=>new(id,false,null,values);
Db2Row Effect(uint id,long spell,string[]? inputs=null)=>new(id,false,null,[N(id),..inputs??["1","2","3","4","5","6",N(spell),"8","9"]]);
Db2Row Classic(uint id,long parent,long spell)=>new(id,false,null,[..Effect(id,spell).Values,N(parent)]);
Db2Row Bridge(uint id,long effect,long parent)=>Row(id,N(id),N(effect),N(parent));
Db2Row Name(uint id,string text)=>Row(id,N(id),text);
FakeTable Table(ItemEffectProfile profile,Db2Row[] rows,TableState state=TableState.Ready,Dictionary<uint,string>? holes=null,int[]? raw=null){var schema=profile.IsName?Schema("46c66698","12.1.5.70448",profile.Layout):profile.Table=="ItemXItemEffect"?Schema("00cb674f","12.1.5.70448",profile.Layout):Schema("4002a5b1",profile==ItemEffectProfiles.ClassicItemEffect?"1.15.9.70003":"12.1.5.70448",profile.Layout);var columns=Columns(profile);foreach(var i in raw??[])columns[i]=columns[i] with{Interpretation=new("raw-physical-integer-v1",32,false,16,i!=3,"Physical raw evidence.")};return new(profile.FileDataId,profile.Table,state,columns,rows,schema,Counts:new(rows.Length,rows.Length,0,0,"WDC5",profile.Layout),Generated:true,ProtectedIds:holes);}
FakeTable Item()=>new(1001,"Item",TableState.Ready,[new("ID","integer",true),new("ClassID","integer",false)],[Row(1,"1","2")],Generated:true);
object Projection(ItemEffectsPage page)=>new{state=page.State.ToString(),itemId=page.ItemId,link=page.Link?.ToString(),matches=page.Matches,bridge=page.Bridge.State.ToString(),effect=page.Effect.State.ToString(),name=page.Name.State.ToString(),entries=page.Entries.Select(e=>new{linkId=e.Link.Row.Id,parent=e.ParentItemId,effectId=e.ItemEffectId,copy=e.Link.Row.IsCopy,effectState=e.Effect.State.ToString(),fields=e.Effect.Fields?.Select(f=>new{name=f.Name,column=f.ColumnIndex,value=f.Value,evidence=f.Evidence.ToString(),reference=f.CandidateReference?.Table}).ToArray(),spell=e.Effect.SpellId,effectAbsence=e.Effect.Absence?.ToString(),effectKey=e.Effect.ProtectedKey,nameState=e.Name.State.ToString(),text=e.Name.Text,textUnits=e.Name.TextUnits,issue=e.Name.IssueCode,nameAbsence=e.Name.Absence?.ToString(),nameKey=e.Name.ProtectedKey}).ToArray()};
var profiles=new Dictionary<string,FakeTable[]>{
["retail"]=[Item(),Table(ItemEffectProfiles.ItemXItemEffect,[Bridge(500,0,1),Bridge(501,0,1),Bridge(502,-1,1),new(503,true,500,["503","0","1"])]),Table(ItemEffectProfiles.RetailItemEffect,[Effect(0,0)]),Table(ItemEffectProfiles.SpellName,[Name(0," ")])],
["classic"]=[Item(),Table(ItemEffectProfiles.ClassicItemEffect,[Classic(7,1,0),Classic(8,1,-1)]),Table(ItemEffectProfiles.SpellName,[Name(0,"Zero")])],
["partial"]=[Item(),Table(ItemEffectProfiles.ItemXItemEffect,[Bridge(1,3,1),Bridge(2,4,1)]),Table(ItemEffectProfiles.RetailItemEffect,[Effect(0,0)],TableState.Partial,new(){[4]="0123456789ABCDEF"}),Table(ItemEffectProfiles.SpellName,[])],
["raw"]=[Item(),Table(ItemEffectProfiles.ItemXItemEffect,[Bridge(1,0,1)]),Table(ItemEffectProfiles.RetailItemEffect,[Effect(0,0,["255","0","4294967295","-5","-6","65535","0","65535","-1"])],raw:[3]),Table(ItemEffectProfiles.SpellName,[Name(0,"Zero")])],
["field"]=[Item(),Table(ItemEffectProfiles.ItemXItemEffect,[Bridge(1,0,1)]),Table(ItemEffectProfiles.RetailItemEffect,[Effect(0,0,["1","2","32768","4","5","6","0","8","9"])]),Table(ItemEffectProfiles.SpellName,[Name(0,"Zero")])],
["invalid-name"]=[Item(),Table(ItemEffectProfiles.ItemXItemEffect,[Bridge(1,0,1)]),Table(ItemEffectProfiles.RetailItemEffect,[Effect(0,0)]),Table(ItemEffectProfiles.SpellName,[new(0,false,null,["0",""],[new(1,"DB2_TEXT_ENCODING_INVALID","ff")])])]
};
foreach(var (name,tables) in profiles){var memory=new InMemoryTables().Add(build,"enUS",tables);var service=new ItemEffectsService(memory);await using var handle=(await service.ForAsync(build,"enUS",default)).Effects!;var page=await handle.GetAsync(1,new(),TablePriority.Interactive,default);File.WriteAllText(Path.Combine(output,name+".json"),JsonSerializer.Serialize(Projection(page)));}
var binding=ItemEffectQueries.Binding(build,"enUS",1,2);var cursor=new ItemEffectsCursor(binding,2,"fake:identity","ItemXItemEffect");File.WriteAllText(Path.Combine(output,"cursor.txt"),ItemEffectQueries.EncodeCursor(cursor));File.WriteAllText(Path.Combine(output,"binding.txt"),binding);
