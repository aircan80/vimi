// Unmodified pure VMIM class excerpt from pinned Images/BlpImages.cs (4fd92d2).
using System.Buffers.Binary;
namespace VibeMiner.Formats.Images;

internal static class BlpImagePayload
{
    private const int Version = 1;
    private const int Fixed = 4 + 4 + 4 * 3 + 4 + 4 * 3 + 4 + 4;
    private const int MipBytes = 4 * 3 + 8 * 2 + 1;

    public static long Size(Blp2Image image) => Fixed + (long)image.Mips.Count * MipBytes + image.Rgba.Length;

    public static byte[] Write(Blp2Image image)
    {
        var bytes = new byte[Size(image)];
        "VMIM"u8.CopyTo(bytes);
        var at = 4;
        void I(int value) { BinaryPrimitives.WriteInt32LittleEndian(bytes.AsSpan(at), value); at += 4; }
        void L(long value) { BinaryPrimitives.WriteInt64LittleEndian(bytes.AsSpan(at), value); at += 8; }
        I(Version); I(image.Width); I(image.Height); I(image.RowStride);
        bytes[at++] = (byte)image.Encoding; bytes[at++] = image.AlphaDepth; bytes[at++] = image.AlphaEncoding; bytes[at++] = (byte)image.MipFlags;
        I(image.MipLevel); I(image.BaseWidth); I(image.BaseHeight); I(image.Mips.Count);
        foreach (var mip in image.Mips) { I(mip.Level); I(mip.Width); I(mip.Height); L(mip.Offset); L(mip.ByteLength); bytes[at++] = (byte)mip.Storage; }
        I(image.Rgba.Length);
        image.Rgba.CopyTo(bytes, at);
        return bytes;
    }

    public static Blp2Image? Read(byte[] bytes, int level, Blp2Limits limits)
    {
        if (bytes.Length < Fixed || !bytes.AsSpan(0, 4).SequenceEqual("VMIM"u8)) return null;
        var at = 4;
        int I() { var v = BinaryPrimitives.ReadInt32LittleEndian(bytes.AsSpan(at)); at += 4; return v; }
        long L() { var v = BinaryPrimitives.ReadInt64LittleEndian(bytes.AsSpan(at)); at += 8; return v; }
        if (I() != Version) return null;
        int width = I(), height = I(), stride = I();
        var encoding = (Blp2Encoding)bytes[at++]; byte depth = bytes[at++], alphaEncoding = bytes[at++], flags = bytes[at++];
        int mipLevel = I(), baseWidth = I(), baseHeight = I(), count = I();
        if (!Enum.IsDefined(encoding) || !Blp2Decoder.IsSupported(encoding, depth, alphaEncoding) || !Blp2Decoder.IsMipFlags(flags)
            || count is < 1 or > 16 || mipLevel != level || mipLevel >= count
            || bytes.Length < Fixed + (long)count * MipBytes) return null;
        var mips = new Blp2Mip[count];
        for (var i = 0; i < count; i++)
        {
            int mipNumber = I(), mipWidth = I(), mipHeight = I(); long offset = L(), length = L(); var storage = (Blp2MipStorage)bytes[at++];
            if (mipNumber != i || !Enum.IsDefined(storage) || mipWidth < 1 || mipHeight < 1 || offset < 1 || length < 1) return null;
            mips[i] = new(mipNumber, mipWidth, mipHeight, offset, length, storage);
        }
        var rgbaLength = I();
        var selected = mips[mipLevel];
        if (width != selected.Width || height != selected.Height || selected.Storage != Blp2MipStorage.Exact
            || baseWidth != mips[0].Width || baseHeight != mips[0].Height || baseWidth > limits.MaxDimension || baseHeight > limits.MaxDimension
            || stride != (long)width * 4 || rgbaLength != (long)width * height * 4 || rgbaLength > limits.MaxRgbaBytes
            || bytes.Length - at != rgbaLength) return null;
        return new(width, height, stride, bytes.AsSpan(at).ToArray(), encoding, depth, alphaEncoding, mipLevel, baseWidth, baseHeight, mips.AsReadOnly(), flags);
    }
}
