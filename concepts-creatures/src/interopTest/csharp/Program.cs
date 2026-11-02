using System.Globalization;
using System.Text;
using VibeMiner.Builds;
using VibeMiner.Concepts.Creatures;
using VibeMiner.Formats;
using VibeMiner.Formats.Definitions;
using VibeMiner.Formats.Models;
var output=args[0];Directory.CreateDirectory(output);
void Write(string name,IEnumerable<string> lines)=>File.WriteAllText(Path.Combine(output,name),string.Join('\n',lines)+"\n",new UTF8Encoding(false));
var build=new BuildRef("wow",new string('a',32),new string('b',32),"us");
var queries=new[]{"","HOG","  ","é","𐐀","<&\""};
Write("queries.tsv",queries.Select(q=>{var binding=CreatureQueries.Binding(build,"enUS",new(q,Limit:2));return Convert.ToBase64String(Encoding.UTF8.GetBytes(q))+"\t"+binding+"\t"+CreatureQueries.EncodeCursor(binding,17,"source-identity");}));
DbdField F(string n,string k,int? bits=null,bool? unsigned=null,int array=1)=>new(new(n,k,true,null,null),bits,unsigned,array,n=="ID",false,false,null);
DbdField[] fields=[F("ID","int",32,false),F("Name_lang","locstring"),F("NameAlt_lang","locstring"),F("Title_lang","locstring"),F("TitleAlt_lang","locstring"),F("Classification","int",8,false),F("CreatureType","int",8,true),F("CreatureFamily","int",16,true),F("StartAnimState","int",8,false),F("DisplayID","int",32,false,4),F("DisplayProbability","float",array:4)];
var columns=fields.SelectMany(f=>Enumerable.Range(0,f.ArrayLength).Select(i=>new Db2Column(f.Column.Name+(f.ArrayLength==1?"":$"[{i}]"),f.Column.Family=="int"?"integer":f.Column.Family=="float"?"float":"string",f.IsId))).ToArray();
var schema=TableSchema.FromBlock(new(["6E14C900"],[],fields,null),SchemaMatch.Build,WowLocale.EnUS);
var layout=CreatureLayout.Resolve(columns,schema);
var results=new List<string>();
foreach(var classification in new[]{"-128","0","1","2","3","4","5","6","7","127"}){
 var row=new Db2Row(7,true,6,["7","Hogger","Alt","Title","",classification,"255","65535","127","100","0","-1","2147483647","1","-0","1E-05","0.5"]);
 var summary=layout.Summary(row);
 results.Add(string.Join('\t',classification,CreatureLayout.ClassificationLabel(classification)??"",string.Join(',',summary.Facts.Select(f=>f.Value)),string.Join(',',summary.Slots.Select(s=>s.DisplayId)),string.Join(',',summary.Slots.Select(s=>s.Probability.ToString("R",CultureInfo.InvariantCulture)))));
}
Write("profiles.tsv",results);
// Original authored model/skin fixture bytes are shared with the already-certified Formats harness.
var asset=args[1];var appearance=new List<string>();
foreach(var variant in new[]{"simple","two-stage","blended","alpha-key","bad-render","external-weight","no-keys","zero-weight"}){
 var model=await M2ModelReader.ReadAsync(File.OpenRead(Path.Combine(asset,$"appearance-{variant}.m2")));
 var skin=await M2SkinReader.ReadAsync(File.OpenRead(Path.Combine(asset,$"appearance-{variant}.skin")),model);
 var refusal=CreatureDisplayRules.GeometryRefusal(model);var drawn=CreatureDisplayRules.DrawnTextures(model,skin);
 appearance.Add(string.Join('\t',variant,refusal?.State.ToString()??"",string.Join(',',drawn.Drawn.Order()),drawn.Refusal?.State.ToString()??"",drawn.Blended));
}
Write("appearance.tsv",appearance);
