package grondag.canvas.varia;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import grondag.canvas.Canvas;
import grondag.canvas.apiimpl.QuadViewImpl;
import grondag.canvas.apiimpl.util.AoFaceData;
import net.minecraft.util.math.MathHelper;

public class LightmapHD {
    static final int TEX_SIZE = 512;
    static final int LIGHTMAP_SIZE = 6;
    static final int LIMIT_INCLUSIVE = LIGHTMAP_SIZE - 1;
    static final int RADIUS = LIGHTMAP_SIZE / 2;
    static final int LIGHTMAP_PIXELS = LIGHTMAP_SIZE * LIGHTMAP_SIZE;
    static final int IDX_SIZE = 512 / LIGHTMAP_SIZE;
    static final int MAX_COUNT = IDX_SIZE * IDX_SIZE;
    // UGLY - consider making this a full unsigned short
    // for initial pass didn't want to worry about signed value mistakes
    /** Scale of texture units sent to shader. Shader should divide by this. */
    static final int BUFFER_SCALE = 0x8000;
    static final int UNITS_PER_PIXEL = BUFFER_SCALE / TEX_SIZE;
    static final float TEXTURE_TO_BUFFER = (float) BUFFER_SCALE / TEX_SIZE;
    
    private static final LightmapHD[] maps = new LightmapHD[MAX_COUNT];
    
    private static final AtomicInteger nextIndex = new AtomicInteger();
    
    public static void forceReload() {
        nextIndex.set(0);
        MAP.clear();
    }
    
    public static void forEach(Consumer<LightmapHD> consumer) {
        final int limit = Math.min(MAX_COUNT, nextIndex.get());
        for(int i = 0; i < limit; i++) {
            consumer.accept(maps[i]);
        }
    }
    
    private static class Key {
        private int[] light = new int[LIGHTMAP_PIXELS];
        private int hashCode;
        
        Key() {
        }

        Key(int[] light) {
            System.arraycopy(light, 0, this.light, 0, LIGHTMAP_PIXELS);
            computeHash();
        }
        
        /**
         * Call after mutating {@link #light}
         */
        void computeHash() {
            this.hashCode = Arrays.hashCode(light);
        }
        
        @Override
        public boolean equals(Object other) {
            if(other == null || !(other instanceof Key)) {
                return false;
            }
            int[] otherLight = ((Key)other).light;
            return Arrays.equals(light, otherLight);
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
    }
    
    static final ThreadLocal<Key> TEMPLATES = ThreadLocal.withInitial(Key::new);
    
    static final ConcurrentHashMap<Key, LightmapHD> MAP = new ConcurrentHashMap<>();
    
    public static LightmapHD findBlock(AoFaceData faceData) {
        return find(faceData, LightmapHD::mapBlock);
    }
    
    public static LightmapHD findSky(AoFaceData faceData) {
        return find(faceData, LightmapHD::mapSky);
    }
    
    private static void mapBlock(AoFaceData faceData, int[] search) {
        /** v - 1 */
        final float top = input(faceData.light1);
        /** v + 1 */
        final float bottom = input(faceData.light0);
        /** u + 1 */
        final float right = input(faceData.light3);
        /** u - 1 */
        final float left = input(faceData.light2);
        
        final float topLeft = input(faceData.cLight2);
        final float topRight = input(faceData.cLight3);
        final float bottomRight = input(faceData.cLight1);
        final float bottomLeft = input(faceData.cLight0);
        
        final float center = input(faceData.lightCenter);

        compute(search, center, top, bottom, right, left, topLeft, topRight, bottomRight, bottomLeft);
    }
    
    private static LightmapHD find(AoFaceData faceData, BiConsumer<AoFaceData, int[]> mapper) {
        Key key = TEMPLATES.get();
        int[] search = key.light;
        
        mapper.accept(faceData, search);

        key.computeHash();
        
        LightmapHD result = MAP.get(key);
        
        if(result == null) {
            // create new key object to avoid putting threadlocal into map
            key = new Key(search);
            result = MAP.computeIfAbsent(key, k -> new LightmapHD(k.light));
        }
        
        return result;
    }
    
    private static float input(int b) {
        b &= 0xFF;
        if(b > 240) {
            b = 240;
        }
        return b / 16f;
    }
    
    private static void mapSky(AoFaceData faceData, int[] search) {
        /** v - 1 */
        final float top = input(faceData.light1 >>> 16);
        /** v + 1 */
        final float bottom = input(faceData.light0 >>> 16);
        /** u + 1 */
        final float right = input(faceData.light3 >>> 16);
        /** u - 1 */
        final float left = input(faceData.light2 >>> 16);
        
        final float topLeft = input(faceData.cLight2 >>> 16);
        final float topRight = input(faceData.cLight3 >>> 16);
        final float bottomRight = input(faceData.cLight1 >>> 16);
        final float bottomLeft = input(faceData.cLight0 >>> 16);
        
        final float center = input(faceData.lightCenter >>> 16);
        
        //TODO: remove
//      if(center == 15 && top == 15 && bottom == 15 && left == 15 && right == 15 && topLeft == 15 && topRight == 15 
//              && bottomLeft == 15 && bottomRight == 15) {
//          System.out.println("boop");
//      }
      
        compute(search, center, top, bottom, right, left, topLeft, topRight, bottomRight, bottomLeft);
    }
    
    public final int uMinImg;
    public final int vMinImg;
    public final int[] light;
    
    private LightmapHD(int[] light) {
        final int index = nextIndex.getAndIncrement();
        final int s = index % IDX_SIZE;
        final int t = index / IDX_SIZE;
        uMinImg = s * LIGHTMAP_SIZE;
        vMinImg = t * LIGHTMAP_SIZE;
        this.light = new int[LIGHTMAP_PIXELS];
        System.arraycopy(light, 0, this.light, 0, LIGHTMAP_PIXELS);
        
        if(index >= MAX_COUNT) {
            //TODO: put back
            //assert false : "Out of lightmap space.";
            Canvas.LOG.info("Out of lightmap space for index = " + index);
            return;
        }
        
        maps[index] = this;
        
        SmoothLightmapTexture.instance().setDirty();
    }
    
    private static void compute(int[] light, float center, 
            float top, float bottom, float right, float left,
            float topLeft, float topRight, float bottomRight, float bottomLeft) {

        // corners
        light[index(0, 0)] = output(corner(center, left, top, topLeft));
        light[index(LIMIT_INCLUSIVE, 0)] = output(corner(center, right, top, topRight));
        light[index(LIMIT_INCLUSIVE, LIMIT_INCLUSIVE)] = output(corner(center, right, bottom, bottomRight));
        light[index(0, LIMIT_INCLUSIVE)] = output(corner(center, left, bottom, bottomLeft));
        
        // edges
        for(int i = 0; i < RADIUS - 1; i++) {
            light[index(0, i + 1)] = output(side(center, left, top, topLeft, 0, i + 1));
            light[index(i + 1, 0)] = output(side(center, left, top, topLeft, i + 1, 0 ));
            
            light[index(RADIUS + i, 0)] = output(side(center, right, top, topRight, RADIUS + i, 0 ));
            light[index(LIMIT_INCLUSIVE, i + 1)] = output(side(center, right, top, topRight, LIMIT_INCLUSIVE, i + 1));
            
            light[index(LIMIT_INCLUSIVE, RADIUS + i)] = output(side(center, right, bottom, bottomRight, LIMIT_INCLUSIVE, RADIUS + i));
            light[index(RADIUS + i, LIMIT_INCLUSIVE)] = output(side(center, right, bottom, bottomRight, RADIUS + i, LIMIT_INCLUSIVE));
            
            light[index(i + 1, LIMIT_INCLUSIVE)] = output(side(center, left, bottom, bottomLeft, i + 1, LIMIT_INCLUSIVE));
            light[index(0, RADIUS + i)] = output(side(center, left, bottom, bottomLeft, 0, RADIUS + i));
            
        }
        
        // INTERIOR
        
        for(int i = 0; i < RADIUS - 1; i++) {
            for(int j = 0; j < RADIUS - 1; j++) {
                //PERF save calcs
                light[index(i + 1, j + 1)] = output(inside(center, left, top, topLeft, i + 1, j + 1));
                light[index(RADIUS + i, j + 1)] = output(inside(center, right, top, topRight, RADIUS + i, j + 1));
                light[index(RADIUS + i, RADIUS + j)] = output(inside(center, right, bottom, bottomRight, RADIUS + i, RADIUS + j));
                light[index(i + 1, RADIUS + j)] = output(inside(center, left, bottom, bottomLeft, i + 1, RADIUS + j));
            }
        }
        
        //TODO: remove
//      if(center == 0 && s0 == 0 && s1 == 0 && s2 == 0 && s3 == 0 && c0 == 0 && c1 == 0 && c2 == 0 && c3 == 0) {
//          for(int i : light) {
//              if(i != 8)
//              System.out.println("boop");
//          }
//      }
//        for(int i = 0; i < LIGHTMAP_PIXELS; i++) {
//            light[i] = 25;
//        }
    }
    
    private static float pclamp(float in) {
        return in < 0f ? 0f : in;
    }
    
    public static int index(int u, int v) {
        return v * LIGHTMAP_SIZE + u;
    }
    
    //FIX: is 1 right?
    private static int output(float in) {
        if(in < 1) {
            in = 1;
        } else if(in > 15) {
            in = 15;
        }
        int result = Math.round(in * 16f);
        
        return 8 + result;
    }
    
    public int coord(QuadViewImpl q, int i) {
        //PERF could compress coordinates sent to shader by 
        // sending lightmap/shademap index with same uv for each
        // would probably save 2 bytes - send 2 + 3 shorts instead of 6
        // or put each block/sky/ao combination in a texture and send 4 shorts...
        // 2 for uv and 2 to lookup the combinations
//        float u = uMinImg + 0.5f + q.u[i] * (LIGHTMAP_SIZE - 1);
//        float v = vMinImg + 0.5f + q.v[i] * (LIGHTMAP_SIZE - 1);
        int u = Math.round(uMinImg * TEXTURE_TO_BUFFER + 1 + q.u[i] * (LIGHTMAP_SIZE * TEXTURE_TO_BUFFER - 2));
        int v = Math.round(vMinImg * TEXTURE_TO_BUFFER + 1 + q.v[i] * (LIGHTMAP_SIZE * TEXTURE_TO_BUFFER - 2));
        return u | (v << 16);
    }
    
    private static float max(float a, float b, float c, float d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }
    
    private static float inside(float self, float uVal, float vVal, float cornerVal, int u, int v) {
        //UGLY: find symmetrical derivation
        if(self == uVal && self == vVal && self == cornerVal) {
            return cornerVal;
        }
        float selfFact = distUV(u, v);
        float uFact = distRadius(CELL_DISTANCE - pixelDist(u), pixelDist(v));
        float vFact = distRadius(pixelDist(u), CELL_DISTANCE - pixelDist(v));
        float cornerFact = distRadius(CELL_DISTANCE - pixelDist(u), CELL_DISTANCE - pixelDist(v));
        return max(self - selfFact, pclamp(uVal - uFact), pclamp(vVal - vFact), pclamp(cornerVal - cornerFact));
    }
    
    static final int CELL_DISTANCE = RADIUS * 2 - 1;
    static final float INVERSE_CELL_DISTANCE = 1f / CELL_DISTANCE;
    
    private static int pixelDist(int c) {
        return c >= RADIUS ? c - RADIUS : RADIUS - 1 - c;
    }
    
    private static float distUV(int u, int v) {
        return distRadius(pixelDist(u), pixelDist(v));
    }
    
    private static float distRadius(int uRadius, int vRadius) {
        return MathHelper.sqrt((uRadius * uRadius + vRadius * vRadius)) * INVERSE_CELL_DISTANCE;
    }
    
    static final float SELF_CORNER_LOSS = distUV(0, 0);
    static final float DIAG_CORNER_LOSS = distUV(-1, -1);
    static final float SIDE_CORNER_LOSS = distUV(-1, 0);
    
    private static float sideInner(float self, float uVal, float vVal, float cornerVal, int u, int v) {
        //UGLY: find symmetrical derivation
        if(self == uVal && self == vVal && self == cornerVal) {
            return self;
        }
        float selfFact = distUV(u, v);
        float uFact = distRadius(CELL_DISTANCE - pixelDist(u), pixelDist(v));
        float vFact = distRadius(pixelDist(u), CELL_DISTANCE - pixelDist(v));
        float cornerFact = distRadius(CELL_DISTANCE - pixelDist(u), CELL_DISTANCE - pixelDist(v));
        return max(pclamp(self - selfFact), pclamp(uVal - uFact), pclamp(vVal - vFact), pclamp(cornerVal - cornerFact));
    }
    
    private static float side(float self, float uVal, float vVal, float cornerVal, int u, int v) {
        //UGLY: find symmetrical derivation
        if(self == uVal && self == vVal && self == cornerVal) {
            return self;
        }
        float s = sideInner(self, uVal, vVal, cornerVal, u, v);
        final int uDist = pixelDist(u);
        final int vDist = pixelDist(v);
        
        assert (uDist == RADIUS - 1 && vDist != RADIUS -1) || (uDist != RADIUS - 1 && vDist == RADIUS -1);
        float t = uDist == RADIUS - 1 ? sideInner(uVal, self, cornerVal, vVal, u, v) : sideInner(vVal, cornerVal, self, uVal, u, v);
        return (s + t) * 0.5f;
    }
    
    private static float cornerInner(float self, float corner, float uVal, float vVal) {
        return max(pclamp(self - SELF_CORNER_LOSS), pclamp(uVal - SIDE_CORNER_LOSS), pclamp(vVal - SIDE_CORNER_LOSS), pclamp(corner - DIAG_CORNER_LOSS));
    }
    
    private static float corner(float self, float uVal, float vVal, float corner) {
        float a = cornerInner(self, corner, uVal, vVal);
        float b = cornerInner(corner, self, vVal, uVal);
        float c = cornerInner(uVal, vVal, self, corner);
        float d = cornerInner(vVal, uVal, corner, self);
        // don't return anything less than normal lerp
        return Math.max(mean(a, b, c, d), mean(self, uVal, vVal, corner));
    }
    
    private static float mean(float a, float b, float c, float d) {
        return (a + b + c + d) * 0.25f;
    }
}