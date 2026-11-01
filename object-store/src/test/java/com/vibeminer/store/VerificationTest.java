package com.vibeminer.store;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.vibeminer.store.StoreTypes.*;
import static org.junit.jupiter.api.Assertions.*;

final class VerificationTest {
    @Test void sharedReferenceVectorsMatchForAnyFragmentSize() {
        for (var v : VerificationVectors.all()) {
            Refusal whole = Verification.CDN.check(v.key(),v.bytes(),v.declared());
            assertEquals(v.expected(),whole==null?null:whole.code());
            for (int fragment : new int[]{1,7,64,65536}) {
                ObjectCheck check = Verification.CDN.begin(v.key(),v.declared()); Refusal refused = null;
                for (int at=0;at<v.bytes().length;at+=fragment) {
                    refused=check.append(v.bytes(),at,Math.min(fragment,v.bytes().length-at));
                    if(refused!=null) break;
                }
                if(refused==null) refused=check.complete();
                assertEquals(v.expected(),refused==null?null:refused.code());
            }
        }
    }
    ObjectKey key(int type, byte[] bytes) { return new ObjectKey(type, Format.hash("MD5",bytes)); }
    void refusal(String code,ObjectKey key,byte[] bytes) {
        assertEquals(code,Verification.CDN.check(key,bytes,bytes.length).code());
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7})
    void appliesTypeNamespaceAndSizeBoundsInReferenceOrder(int type) {
        Verification v=type==7 ? Verification.GENERATED : Verification.CDN;
        ObjectKey key=new ObjectKey(type,new byte[16]); long max=switch(type) {
            case 1,5 -> 16L<<20; case 2,3,7 -> 64L<<20; case 4 -> 512L<<20; default -> 1136;
        };
        assertEquals("OBJECT_NAMESPACE_UNSUPPORTED",v.check(new ObjectKey(type,1,key.bytes()),new byte[0],0).code());
        for (long length:new long[]{-1,0,max+1,Long.MAX_VALUE}) {
            assertEquals("OBJECT_TOO_LARGE",v.check(key,new byte[0],length).code());
            assertEquals("OBJECT_TOO_LARGE",v.begin(key,length).complete().code());
        }
        assertEquals("OBJECT_SIZE_MISMATCH",v.check(key,new byte[0],max).code());
        assertEquals("OBJECT_SIZE_MISMATCH",v.begin(key,max).complete().code());
        assertEquals("OBJECT_KEY_TYPE_UNSUPPORTED",v.check(new ObjectKey(255,1,key.bytes()),new byte[0],0).code());
    }
    @ParameterizedTest @ValueSource(ints={1,5})
    void acceptsMd5ObjectsIncrementallyAndKeepsRefusalsSticky(int type) {
        byte[] bytes={7,8,9}; ObjectKey key=key(type,bytes); ObjectCheck check=Verification.CDN.begin(key,bytes.length);
        for(byte b:bytes) assertNull(check.append(new byte[]{b})); assertNull(check.complete());
        assertEquals(0,check.provenLength());
        ObjectCheck truncated=Verification.CDN.begin(key,bytes.length);
        assertNull(truncated.append(new byte[]{7})); Refusal refused=truncated.complete();
        assertEquals("OBJECT_SIZE_MISMATCH",refused.code()); assertSame(refused,truncated.append(bytes)); assertSame(refused,truncated.complete());
        refusal("OBJECT_KEY_MISMATCH",key,new byte[]{7,8,0});
    }
    @Test void verifiesFragmentedBlteHeadersChunksAndProvenPrefixes() {
        EncodedFixture f=EncodedFixture.sample(); ObjectCheck check=Verification.CDN.begin(f.key(),f.bytes().length);
        for(int i=0;i<f.bytes().length;i++) {
            assertNull(check.append(new byte[]{f.bytes()[i]}));
            long expected=i+1<f.headerLength()?0:i+1<f.firstChunkEnd()?f.headerLength():i+1<f.bytes().length?f.firstChunkEnd():f.bytes().length;
            assertEquals(expected,check.provenLength());
        }
        assertNull(check.complete());
        byte[] corrupt=f.bytes().clone();corrupt[corrupt.length-1]^=1;
        check=Verification.CDN.begin(f.key(),corrupt.length);
        assertEquals("OBJECT_BLTE_INVALID",check.append(corrupt).code());assertEquals(f.firstChunkEnd(),check.provenLength());
    }
    @Test void blteHeaderRefusalsFollowReferencePrecedence() {
        EncodedFixture f=EncodedFixture.sample(); byte[] bytes=f.bytes().clone(); bytes[8]=14;
        refusal("OBJECT_KEY_MISMATCH",f.key(),bytes);
        refusal("OBJECT_BLTE_UNSUPPORTED",key(4,Arrays.copyOf(bytes,f.headerLength())),bytes);
        bytes=f.bytes().clone();bytes[11]=3;
        refusal("OBJECT_BLTE_INVALID",key(4,Arrays.copyOf(bytes,f.headerLength())),bytes);
        bytes=f.bytes().clone();ByteBuffer.wrap(bytes).putInt(12,0);
        refusal("OBJECT_BLTE_INVALID",key(4,Arrays.copyOf(bytes,f.headerLength())),bytes);
        bytes=f.bytes().clone();ByteBuffer.wrap(bytes).putInt(12,5);
        refusal("OBJECT_SIZE_MISMATCH",key(4,Arrays.copyOf(bytes,f.headerLength())),bytes);
        for(int size:new int[]{1,7,8}) { byte[] shortBytes=Arrays.copyOf(new byte[]{'B','L','T','E',0,0,0,0},size);refusal("OBJECT_BLTE_INVALID",key(4,shortBytes),shortBytes); }
        byte[] headerless={'B','L','T','E',0,0,0,0,'N',1};
        assertNull(Verification.CDN.check(key(4,headerless),headerless,headerless.length));
        headerless[9]=2;refusal("OBJECT_KEY_MISMATCH",new ObjectKey(4,new byte[16]),headerless);
    }
    @ParameterizedTest @ValueSource(ints={2,3})
    void validatesIndexFooterGeometryTocAndEveryPage(int type) {
        for(int pages:new int[]{0,1,2}) {
            byte[] bytes=index(type,pages); int footer=bytes.length-28; ObjectKey key=key(type,Arrays.copyOfRange(bytes,footer,bytes.length));
            assertNull(Verification.CDN.check(key,bytes,bytes.length));
            if(pages>0) { byte[] bad=bytes.clone();bad[0]^=1;refusal("OBJECT_INDEX_INVALID",key,bad); }
            byte[] bad=bytes.clone();bad[footer+20]^=1;
            refusal("OBJECT_INDEX_INVALID",key(type,Arrays.copyOfRange(bad,footer,bad.length)),bad);
            bad=bytes.clone();bad[footer+8]=2;checksumFooter(bad);
            refusal("OBJECT_INDEX_UNSUPPORTED",key(type,Arrays.copyOfRange(bad,footer,bad.length)),bad);
            bad=bytes.clone();Format.le(bad).putInt(footer+16,Integer.MAX_VALUE);checksumFooter(bad);
            refusal("OBJECT_INDEX_INVALID",key(type,Arrays.copyOfRange(bad,footer,bad.length)),bad);
        }
        refusal("OBJECT_INDEX_INVALID",key(type,new byte[27]),new byte[27]);
    }
    byte[] index(int type,int pages) {
        byte[] bytes=new byte[pages*(4096+24)+28];int footer=bytes.length-28;
        for(int i=0;i<pages;i++) {
            Arrays.fill(bytes,i*4096,(i+1)*4096,(byte)(i+1));
            System.arraycopy(Format.hash("MD5",Arrays.copyOfRange(bytes,i*4096,(i+1)*4096)),0,bytes,pages*4096+pages*16+i*8,8);
        }
        System.arraycopy(Format.hash("MD5",Arrays.copyOfRange(bytes,pages*4096,footer)),0,bytes,footer,8);
        int width=type==2?4:0;bytes[footer+8]=1;bytes[footer+11]=4;bytes[footer+12]=(byte)width;
        bytes[footer+13]=4;bytes[footer+14]=16;bytes[footer+15]=8;
        Format.le(bytes).putInt(footer+16,pages==0?0:(pages-1)*(4096/(20+width))+1);checksumFooter(bytes);return bytes;
    }
    void checksumFooter(byte[] bytes) {
        byte[] inner=Arrays.copyOfRange(bytes,bytes.length-20,bytes.length);Arrays.fill(inner,12,20,(byte)0);
        System.arraycopy(Format.hash("MD5",inner),0,bytes,bytes.length-8,8);
    }
    @Test void historyPreservesStrictUtf8AndRejectsInvalidGrammar() {
        String url="\ufeffhttps://example.test/é/\u0000";
        byte[] bytes=History.encode(4,"","us",Instant.EPOCH,url,new ObjectKey(5,new byte[16]));
        assertNull(Verification.CDN.check(key(6,bytes),bytes,bytes.length));
        assertEquals(url,History.parse(bytes,key(6,bytes)).url());
        byte[] bad=bytes.clone();bad[2]=1;refusal("OBJECT_HISTORY_INVALID",key(6,bad),bad);
        bad=bytes.clone();bad[bad.length-1]=(byte)0xc0;refusal("OBJECT_HISTORY_INVALID",key(6,bad),bad);
        refusal("OBJECT_HISTORY_INVALID",key(6,new byte[33]),new byte[33]);
        assertThrows(IllegalArgumentException.class,()->History.encode(4,"wow","us",Instant.EPOCH,"",new ObjectKey(5,new byte[16])));
        assertThrows(IllegalArgumentException.class,()->History.encode(1,"wow","us",Instant.EPOCH,"\ud800",new ObjectKey(5,new byte[16])));
    }
}
