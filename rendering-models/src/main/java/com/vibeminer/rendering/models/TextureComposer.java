package com.vibeminer.rendering.models;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Explicit straight-alpha copy/source-over policy for bounded client atlas layers. */
public final class TextureComposer {
    public enum Blend {
        COPY,
        SOURCE_OVER
    }

    public record Layer(
            MeshAppearance.Texture texture, int x, int y, int width, int height, Blend blend) {}

    private TextureComposer() {}

    public static MeshAppearance.Texture compose(
            int width, int height, List<Layer> layers, BooleanSupplier canceled) {
        if (width < 1 || height < 1 || width > 2048 || height > 2048 || layers.size() > 64) {
            throw new IllegalArgumentException("Atlas exceeds preview bounds");
        }
        long work = 0;
        for (var layer : layers) {
            if (layer.x() < 0
                    || layer.y() < 0
                    || layer.width() < 1
                    || layer.height() < 1
                    || layer.x() > width - layer.width()
                    || layer.y() > height - layer.height()) {
                throw new IllegalArgumentException("Layer outside atlas");
            }
            work += (long) layer.width() * layer.height();
        }
        if (work > 64_000_000L) {
            throw new IllegalArgumentException("Atlas work exceeds preview bounds");
        }
        int[] pixels = new int[width * height];
        for (var layer : layers) {
            var source = layer.texture();
            for (int y = 0; y < layer.height(); y++) {
                if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                int sy =
                        Math.min(
                                source.height() - 1,
                                (int) ((long) y * source.height() / layer.height()));
                for (int x = 0; x < layer.width(); x++) {
                    int sx =
                            Math.min(
                                    source.width() - 1,
                                    (int) ((long) x * source.width() / layer.width()));
                    int value = source.pixels[sy * source.width() + sx],
                            target = (layer.y() + y) * width + layer.x() + x;
                    pixels[target] =
                            layer.blend() == Blend.COPY ? value : over(value, pixels[target]);
                }
            }
        }
        return new MeshAppearance.Texture(width, height, pixels);
    }

    private static int over(int source, int destination) {
        int a = source >>> 24, b = destination >>> 24;
        if (a == 255) {
            return source;
        }
        if (a == 0) {
            return destination;
        }
        int denominator = a * 255 + b * (255 - a);
        if (denominator == 0) {
            return 0;
        }
        int alpha = (denominator + 127) / 255,
                red = channel(source >>> 16 & 255, destination >>> 16 & 255, a, b, denominator),
                green = channel(source >>> 8 & 255, destination >>> 8 & 255, a, b, denominator),
                blue = channel(source & 255, destination & 255, a, b, denominator);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int channel(int source, int destination, int a, int b, int denominator) {
        return (source * a * 255 + destination * b * (255 - a) + denominator / 2) / denominator;
    }
}
