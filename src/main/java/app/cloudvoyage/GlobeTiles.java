package app.cloudvoyage;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GlobeTiles {
    private static final int MAX_FRAME_PIXELS = 1_100_000;
    private static final int TILE_SIZE = 600, COLS = 36, ROWS = 18;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final LruCache<Integer, int[]> TILES = new LruCache<Integer, int[]>(34 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, int[] pixels) { return pixels.length * 4; }
    };
    private static final int[] UNAVAILABLE = new int[0];
    private static Texture overview;

    private final View view;
    private final AssetManager assets;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Frame displayed, pending, inFlight;
    private final MapTilerTiles online;
    private boolean needsRefresh;

    GlobeTiles(View view) {
        this.view = view;
        assets = view.getContext().getAssets();
        online = MapTilerTiles.configured() ? new MapTilerTiles(view,() -> {
            needsRefresh = true;
            view.invalidate();
        }) : null;
    }

    GlobeCamera draw(Canvas canvas, GlobeCamera camera, float cx, float cy, float radius, int width, int height) {
        if (needsRefresh || displayed == null || !displayed.matches(camera, width, height)
                || (inFlight != null && !inFlight.matches(camera, width, height))) {
            if (inFlight != null && inFlight.matches(camera, width, height)) {
                pending = null;
            } else if (pending == null || !pending.matches(camera, width, height)) {
                pending = new Frame(camera, cx, cy, radius, width, height);
            }
            submit();
        } else {
            pending = null;
        }
        if (displayed == null || displayed.width != width || displayed.height != height) return null;
        canvas.drawBitmap(displayed.bitmap, null, new RectF(0,0,width,height), paint);
        return displayed.camera;
    }

    private void submit() {
        if (inFlight != null || pending == null || !view.isAttachedToWindow()) return;
        Frame frame = pending;
        needsRefresh = false;
        inFlight = frame;
        pending = null;
        LOADER.execute(() -> {
            try {
                frame.bitmap = render(frame);
            } catch (RuntimeException e) {
                android.util.Log.e("CloudVoyage", "Unable to render map frame", e);
            }
            MAIN.post(() -> {
                if (frame.bitmap != null) displayed = frame;
                inFlight = null;
                if (!view.isAttachedToWindow()) return;
                if (online != null && frame.satellite != null && pending == null) online.load(frame.satellite);
                if (frame.bitmap != null) view.invalidate();
                submit();
            });
        });
    }

    String status() {
        if (displaysOnline()) return "在线卫星";
        return online == null ? "离线航图" : online.status();
    }

    boolean displaysOnline() {
        return displayed != null && displayed.satellite != null && displayed.satellite.used;
    }

    private Bitmap render(Frame frame) {
        if (overview == null) {
            overview = decode("earth-preview.jpg",3600,1800);
        }
        double scale = Math.min(1, Math.sqrt(MAX_FRAME_PIXELS / ((double)frame.width * frame.height)));
        int width = Math.max(1, (int)(frame.width * scale)), height = Math.max(1, (int)(frame.height * scale));
        int[] pixels = new int[width * height];
        float stepX = frame.width / (width * frame.radius), stepY = frame.height / (height * frame.radius);
        boolean close = frame.camera.zoom >= 3 && frame.radius > 460;
        int[][] visibleTiles = close ? new int[COLS * ROWS][] : null;
        double[] uv = new double[2];
        int zoom = SatelliteProjection.zoom(frame.radius * (float)scale,frame.camera.latitude());
        if (frame.camera.zoom < 2) zoom = Math.min(1,zoom);
        MapTilerTiles.Sampler satellite = online == null ? null : new MapTilerTiles.Sampler(zoom);
        for (int y = 0; y < height; y++) {
            float north = frame.cy / frame.radius - (y + .5f) * stepY;
            float rowDepthSquared = 1 - north * north;
            if (rowDepthSquared <= 0) continue;
            for (int x = 0; x < width; x++) {
                float east = (x + .5f) * stepX - frame.cx / frame.radius;
                float depthSquared = rowDepthSquared - east * east;
                if (depthSquared <= 0) continue;
                float front = (float)Math.sqrt(depthSquared);
                frame.camera.textureCoordinates(east, north, front, uv);
                int color = satellite == null ? 0 : satellite.sample(uv[0],uv[1]);
                if (color == 0 && close) color = detail(visibleTiles,uv[0],uv[1]);
                if (color == 0) color = overview == null ? 0xFF28485B : overview.sample(uv[0], uv[1]);
                pixels[y * width + x] = color;
            }
        }
        if (satellite != null) satellite.finish();
        frame.satellite = satellite;
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int detail(int[][] visible, double u, double v) {
        double x = u * COLS, y = Math.min(Math.nextDown((double)ROWS), v * ROWS);
        int col = (int)x, row = (int)y, index = row * COLS + col;
        int[] tile = visible[index];
        if (tile == null) {
            tile = TILES.get(index);
            if (tile == null) {
                Texture decoded = decode("earth-detail/" + col + "-" + row + ".jpg", TILE_SIZE, TILE_SIZE);
                tile = decoded == null ? UNAVAILABLE : decoded.pixels;
                TILES.put(index, tile);
            }
            visible[index] = tile;
        }
        return tile == UNAVAILABLE ? 0 : Texture.sample(tile, TILE_SIZE, TILE_SIZE,
                (x - col) * TILE_SIZE - .5, (y - row) * TILE_SIZE - .5, false);
    }

    private Texture decode(String asset, int maxWidth, int maxHeight) {
        try (InputStream input = assets.open(asset)) {
            Bitmap image = BitmapFactory.decodeStream(input);
            if (image == null) return null;
            int width = image.getWidth(), height = image.getHeight();
            if (width != maxWidth || height != maxHeight) {
                image.recycle();
                return null;
            }
            int[] pixels = new int[width * height];
            image.getPixels(pixels, 0, width, 0, 0, width, height);
            image.recycle();
            return new Texture(width,height,pixels);
        } catch (IOException e) {
            android.util.Log.w("CloudVoyage", "Unable to load map texture " + asset, e);
            return null;
        }
    }

    static int sampleTexture(int[] pixels, int width, int height, double x, double y, boolean wrap) {
        return Texture.sample(pixels,width,height,x,y,wrap);
    }

    private static final class Texture {
        final int width, height;
        final int[] pixels;

        Texture(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        int sample(double u, double v) {
            return sample(pixels,width,height,u * width - .5,v * height - .5,true);
        }

        static int sample(int[] pixels, int width, int height, double x, double y, boolean wrap) {
            y = Math.max(0, Math.min(height - 1, y));
            if (!wrap) x = Math.max(0,Math.min(width - 1,x));
            int left = (int)Math.floor(x), top = (int)y;
            int fx = (int)((x - left) * 256), fy = (int)((y - top) * 256);
            int right = left + 1;
            if (right == width) right = wrap ? 0 : left;
            if (left < 0) left = width - 1;
            int bottom = Math.min(height - 1, top + 1);
            int a = mix(pixels[top * width + left], pixels[top * width + right], fx);
            int b = mix(pixels[bottom * width + left], pixels[bottom * width + right], fx);
            return 0xFF000000 | mix(a, b, fy);
        }

        private static int mix(int a, int b, int phase) {
            int inverse = 256 - phase;
            int rb = (((a & 0xFF00FF) * inverse + (b & 0xFF00FF) * phase) >>> 8) & 0xFF00FF;
            int g = (((a & 0x00FF00) * inverse + (b & 0x00FF00) * phase) >>> 8) & 0x00FF00;
            return rb | g;
        }
    }

    private static final class Frame {
        final GlobeCamera camera;
        final int width, height;
        final float cx, cy, radius;
        Bitmap bitmap;
        MapTilerTiles.Sampler satellite;

        Frame(GlobeCamera camera, float cx, float cy, float radius, int width, int height) {
            this.camera = new GlobeCamera(camera);
            this.width = width;
            this.height = height;
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
        }

        boolean matches(GlobeCamera candidate, int width, int height) {
            return this.width == width && this.height == height && camera.sameView(candidate);
        }
    }
}
