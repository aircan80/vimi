using System.Text;
using VibeMiner.Formats;
using VibeMiner.Concepts.Items;
var output=args[0];Directory.CreateDirectory(output);
var names=new[]{"InventoryType","Description_lang","OverallQualityID","AllowableRaces[0]","AllowableRaces[1]","DmgVariance"};
var columns=names.Select(n=>new Db2Column(n,n.EndsWith("_lang")?"string":"integer",false)).ToArray();
var itemColumns=new[]{new Db2Column("InventoryType","integer",false)};
var source=new Db2Row(4294967295,false,null,new[]{"17","Literal ß","4","18446744073709551615","-9223372036854775808","-0"});
var rows=new List<string>();
void Add(string name,ItemFactSet set){rows.Add(name+"|"+set.State+"|"+set.Sparse+"|"+set.Code);foreach(var f in set.Facts){rows.Add(f.Key+"|"+f.State+"|"+f.Agreement);foreach(var v in f.Values)rows.Add(v.Table+"|"+v.Column+"|"+v.ColumnIndex+"|"+v.RowId+"|"+Convert.ToBase64String(Encoding.UTF8.GetBytes(v.Value))+"|"+v.Label);}}
var item=new Db2Row(4294967295,false,null,new[]{"13"});
Add("present",ItemFacts.Project(itemColumns,columns,item,source,ItemSparseState.Present));
Add("invalid",ItemFacts.Project(itemColumns,columns,item,source with {TextIssues=new[]{new Db2TextIssue(1,"DB2_TEXT_UTF8","FF")}},ItemSparseState.Present));
foreach(var state in new[]{ItemSparseState.Missing,ItemSparseState.Unknown,ItemSparseState.Protected})Add(state.ToString(),ItemFacts.Project(itemColumns,state==ItemSparseState.Unknown?null:columns,item,null,state));
File.WriteAllText(Path.Combine(output,"facts.tsv"),string.Join("\n",rows)+"\n",new UTF8Encoding(false));
