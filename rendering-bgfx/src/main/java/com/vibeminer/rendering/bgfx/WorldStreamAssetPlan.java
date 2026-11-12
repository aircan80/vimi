package com.vibeminer.rendering.bgfx;

/**
 * The parent side of one late objects file naming assets by digest (#380 stage 4): its doodad
 * models (no portal set, animation entry or liquid; WMO groups and creatures stay inline for now)
 * and every image, each asset's payload bytes, and the payloads of those the mirror lacks, which
 * its asset pack carries.
 */
final class WorldStreamAssetPlan {
    final WorldStreamObjectsFile.References references;
    final java.util.Map<WorldStreamObjectAssets.Digest, Long> bytes = new java.util.HashMap<>();
    final java.util.List<WorldStreamObjectAssets.Payload> payloads = new java.util.ArrayList<>();

    private WorldStreamAssetPlan(WorldStreamObjectsFile.References references) {
        this.references = references;
    }

    static WorldStreamAssetPlan of(
            WorldStreamObjectsFile.Data data, WorldStreamAssetStore<?> mirror) {
        var assets = WorldStreamObjectAssets.of(data);
        int models = data.modelCount();
        var refModels = new java.util.ArrayList<Integer>();
        for (int m = 0; m < models; m++) {
            if (doodad(data, m)) {
                refModels.add(m);
            }
        }
        var modelIndices = new int[refModels.size()];
        var modelDigests = new WorldStreamObjectAssets.Digest[refModels.size()];
        for (int r = 0; r < modelIndices.length; r++) {
            modelIndices[r] = refModels.get(r);
            modelDigests[r] = assets.get(modelIndices[r]).digest();
        }
        int images = data.imageCount();
        var imageIndices = new int[images];
        var imageDigests = new WorldStreamObjectAssets.Digest[images];
        for (int i = 0; i < images; i++) {
            imageIndices[i] = i;
            imageDigests[i] = assets.get(models + i).digest();
        }
        var plan =
                new WorldStreamAssetPlan(
                        new WorldStreamObjectsFile.References(
                                modelIndices, modelDigests, imageIndices, imageDigests));
        for (int m : modelIndices) {
            var asset = assets.get(m);
            if (plan.bytes.put(asset.digest(), asset.bytes()) == null
                    && !mirror.holds(asset.digest())) {
                plan.payloads.add(WorldStreamObjectAssets.modelPayload(data, m));
            }
        }
        for (int i = 0; i < images; i++) {
            var asset = assets.get(models + i);
            if (plan.bytes.put(asset.digest(), asset.bytes()) == null
                    && !mirror.holds(asset.digest())) {
                plan.payloads.add(WorldStreamObjectAssets.imagePayload(data, i));
            }
        }
        return plan;
    }

    /** A model with no portal set, animation entry or liquid. */
    static boolean doodad(WorldStreamObjectsFile.Data data, int m) {
        for (int s = 0; s < data.portalSetCount(); s++) {
            if (data.portalModel(s) == m) {
                return false;
            }
        }
        for (int i = 0; i < data.animations.count(); i++) {
            if (data.animations.models[i] == m) {
                return false;
            }
        }
        int first = data.models[m * 2], count = data.models[m * 2 + 1];
        for (int i = 0; i < data.liquids.count(); i++) {
            int mesh = data.liquids.meshes[i];
            if (mesh >= first && mesh < first + count) {
                return false;
            }
        }
        return true;
    }
}
