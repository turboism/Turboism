package dev.turboism.validation.modelimport3d.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlbExpandedBudgetTest {
    @Test void largerIndexBuffersAreDecodedWithTheNewFiniteLimit() {
        assertEquals(450000,GlbSurfaceReader.read(fixture(450000,false)).get(0).indices().size());
        assertEquals(600000,GlbSurfaceReader.read(fixture(600000,false)).get(0).indices().size());
    }
    @Test void overLimitAndExpandedInstancesStillFailBeforeImport() {
        var accessor=assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(fixture(600003,false)));
        assertEquals("accessor count budget",accessor.getMessage());
        var aggregate=assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(fixture(450000,true)));
        assertEquals("expanded instance index budget",aggregate.getMessage());
    }
    private static byte[] fixture(int count,boolean repeated) {
        int binaryLength=60+4*count;
        String nodes=repeated?"[{\"mesh\":0},{\"mesh\":0}]":"[{\"mesh\":0}]";
        String scene=repeated?"[0,1]":"[0]";
        String json="{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":"+scene+"}],\"nodes\":"+nodes
            +",\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"TEXCOORD_0\":1},\"indices\":2}]}],"
            +"\"buffers\":[{\"byteLength\":"+binaryLength+"}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
            +"{\"buffer\":0,\"byteOffset\":36,\"byteLength\":24},{\"buffer\":0,\"byteOffset\":60,\"byteLength\":"+(4*count)+"}],"
            +"\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]},"
            +"{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
            +"{\"bufferView\":2,\"componentType\":5125,\"count\":"+count+",\"type\":\"SCALAR\"}]}";
        byte[] text=json.getBytes(StandardCharsets.UTF_8);int padded=(text.length+3)&~3;
        var bytes=ByteBuffer.allocate(28+padded+binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);
        while(bytes.position()<20+padded)bytes.put((byte)' ');
        bytes.putInt(binaryLength).putInt(0x004e4942);
        for(float value:new float[]{0,0,0,1,0,0,0,1,0,0,0,1,0,0,1})bytes.putFloat(value);
        for(int i=0;i<count;i++)bytes.putInt(i%3);
        return bytes.array();
    }
}
