using System.Security.Cryptography;
using System.Text.Json;
using VibeMiner.Formats.Models;
using VibeMiner.Formats.Testing;
var directory = args[0]; Directory.CreateDirectory(directory);
foreach(var version in new uint[]{264,272,274}) {
    var modelBytes = ModelFixtures.Model(version); var skinBytes = ModelFixtures.Skin(version);
    File.WriteAllBytes(Path.Combine(directory,$"model-{version}.m2"), modelBytes);
    File.WriteAllBytes(Path.Combine(directory,$"skin-{version}.skin"),skinBytes);
    var model=await M2ModelReader.ReadAsync(new MemoryStream(modelBytes));
    var skin=await M2SkinReader.ReadAsync(new MemoryStream(skinBytes),model);
    var geometry=M2StaticGeometry.Create(model,skin);
    File.WriteAllText(Path.Combine(directory,$"model-{version}.json"),JsonSerializer.Serialize(new {model.Version,model.Name,model.BaseOffset,model.SourceBytes,model.BaseBytes,model.SkinFileDataIds,model.LodSkinFileDataIds,model.RetainedBytes,Vertices=model.Vertices.Count,geometry.Indices,Minimum=new[]{geometry.Bounds.Minimum.X,geometry.Bounds.Minimum.Y,geometry.Bounds.Minimum.Z},Maximum=new[]{geometry.Bounds.Maximum.X,geometry.Bounds.Maximum.Y,geometry.Bounds.Maximum.Z}}));
    if(version==274){
        var geo=await M2GeometrySkinReader.ReadAsync(new MemoryStream(skinBytes),model);
        var binding=new GeometrySourceBinding(new(new string('1',32),new string('2',32),new string('3',64),1024,modelBytes.Length,"content-blte-v1"),new(new string('4',32),new string('5',32),new string('6',64),512,skinBytes.Length,"content-blte-v1"),123,900,0);
        using var output=new MemoryStream();var artifact=await ModelGeometryArtifacts.BuildAsync(geo,binding,output);
        File.WriteAllBytes(Path.Combine(directory,"geometry.vmg1"),output.ToArray());
        File.WriteAllText(Path.Combine(directory,"geometry.json"),JsonSerializer.Serialize(artifact));
    }
}

var simple = VibeMiner.Formats.Tests.Models.AppearanceModels.Simple();
var variants = new Dictionary<string,VibeMiner.Formats.Tests.Models.AppearanceModels.Model> {
 ["simple"] = simple,
 ["zero-weight"] = simple with { WeightKeys = [[[0]]] },
 ["no-keys"] = simple with { WeightKeys = [[[]]] },
 ["external-weight"] = simple with { SequenceFlags = [0] },
 ["two-stage"] = simple with { Textures = [new(0,3,1234),new(11)], Combos = [0,1], TransformLookup = [65535,65535] },
 ["blended"] = simple with { Materials = [(0,2)] },
 ["alpha-key"] = simple with { Materials = [(4,1)] },
 ["bad-render"] = simple with { Materials = [(2,0)] },
 ["shared-empty-slices"] = simple with { WeightStress = (32,4096,true) },
 ["unique-empty-slices"] = simple with { WeightStress = (32,4096,false) }
};
foreach(var (name, profile) in variants) {
 var mb = VibeMiner.Formats.Tests.Models.AppearanceModels.ModelBytes(profile);
 var batch = new VibeMiner.Formats.Tests.Models.AppearanceModels.Batch();
 if(name=="two-stage") batch=batch with { Shader=0x8000,TextureCount=2 };
 if(name=="alpha-key") batch=batch with { Shader=0x10 };
 var sb=VibeMiner.Formats.Tests.Models.AppearanceModels.SkinBytes([batch]);
 var parsed=await M2ModelReader.ReadAsync(new MemoryStream(mb));
 var parsedSkin=await M2GeometrySkinReader.ReadAsync(new MemoryStream(sb),parsed);
 var facts=await M2Appearance.ReadModelAsync(new MemoryStream(mb),parsed);
 var plan=await M2Appearance.AdmitAsync(new MemoryStream(sb),parsedSkin,facts);
 if (mb.Length > 65536) {
  using var compressed=File.Create(Path.Combine(directory,"appearance-"+name+".m2.gz"));
  using var gzip=new System.IO.Compression.GZipStream(compressed,System.IO.Compression.CompressionLevel.SmallestSize);gzip.Write(mb);
 } else File.WriteAllBytes(Path.Combine(directory,"appearance-"+name+".m2"),mb);
 File.WriteAllBytes(Path.Combine(directory,"appearance-"+name+".skin"),sb);
 File.WriteAllText(Path.Combine(directory,"appearance-"+name+".json"),JsonSerializer.Serialize(new{facts.StaticWeights,Plan=plan}));
}
