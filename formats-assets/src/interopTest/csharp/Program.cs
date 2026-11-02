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
