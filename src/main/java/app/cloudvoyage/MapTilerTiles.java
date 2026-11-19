package app.cloudvoyage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

final class MapTilerTiles {
    static final String USER_AGENT = "CloudVoyage-Android";
    private static final String BASE = "https://api.maptiler.com/tiles/satellite-v2/";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(3);
    private static final LruCache<Long,Tile> CACHE = new LruCache<Long,Tile>(16 * 1024 * 1024) {
        @Override protected int sizeOf(Long id, Tile tile) {
            return tile.pixels == null ? 128 : tile.pixels.length * 4;
        }
    };
    private static final Tile MISSING = new Tile(null,0,0);
    private static volatile long serviceResume;
    private static volatile String serviceProblem = "";
    private final View view;
    private final ArrayDeque<Long> queued = new ArrayDeque<>();
    private final Set<Long> loading = new HashSet<>();
    private final Runnable refreshBatch;
    private final Runnable retry;
    private String networkProblem = "";

    MapTilerTiles(View view, Runnable refresh) {
        this.view = view;
        refreshBatch = () -> {
            if (view.isAttachedToWindow()) refresh.run();
        };
        retry = () -> refreshBatch.run();
    }

    static boolean configured() { return !MapConfig.MAPTILER_KEY.isEmpty(); }

    String status() {
        long now = SystemClock.elapsedRealtime();
        if (serviceResume > now) return serviceProblem;
        return networkProblem.isEmpty() ? "离线航图" : networkProblem;
    }

    void load(Sampler viewport) {
        queued.clear();
        if (!view.isAttachedToWindow()) return;
        long now = SystemClock.elapsedRealtime();
        MAIN.removeCallbacks(retry);
        if (serviceResume > now) {
            scheduleRetry(serviceResume - now);
            return;
        }
        for (long id : viewport.missing) {
            if (!loading.contains(id)) queued.add(id);
        }
        if (viewport.retryAfter != Long.MAX_VALUE) scheduleRetry(viewport.retryAfter-now);
        pump();
    }

    private void scheduleRetry(long delay) {
        MAIN.removeCallbacks(retry);
        MAIN.postDelayed(retry,Math.max(500,delay));
    }

    private void pump() {
        if (!view.isAttachedToWindow() || serviceResume > SystemClock.elapsedRealtime()) return;
        while (loading.size() < 3 && !queued.isEmpty()) {
            long id = queued.removeFirst();
            if (cached(id) != null) continue;
            loading.add(id);
            NETWORK.execute(() -> {
                String problem = fetch(id);
                MAIN.post(() -> {
                    loading.remove(id);
                    if (!view.isAttachedToWindow()) return;
                    networkProblem = problem;
                    MAIN.removeCallbacks(refreshBatch);
                    MAIN.postDelayed(refreshBatch,80);
                    pump();
                });
            });
        }
    }

    private static Tile cached(long id) {
        Tile tile = CACHE.get(id);
        return tile != null && tile.until > SystemClock.elapsedRealtime() ? tile : null;
    }

    private static String fetch(long id) {
        HttpURLConnection link = null;
        long now = SystemClock.elapsedRealtime();
        try {
            if (serviceResume > now) return serviceProblem;
            int z = (int)(id >>> 34), x = (int)((id >>> 17) & 0x1FFFF), y = (int)(id & 0x1FFFF);
            link = (HttpURLConnection)new URL(BASE+z+"/"+x+"/"+y+".jpg?key="+MapConfig.MAPTILER_KEY).openConnection();
            link.setConnectTimeout(4500);
            link.setReadTimeout(4500);
            link.setInstanceFollowRedirects(false);
            link.setRequestProperty("User-Agent",USER_AGENT);
            int code = link.getResponseCode();
            if (code == 401 || code == 403 || code == 429) {
                String problem = code == 429 ? "离线 · 在线额度受限" : "离线 · 地图授权失败";
                serviceProblem = problem;
                serviceResume = now + 120_000;
                return problem;
            }
            if (code == 200) {
                try (InputStream data = link.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(data);
                    if (bitmap != null) {
                        int size = bitmap.getWidth();
                        if ((size == 256 || size == 512) && bitmap.getHeight() == size) {
                            int[] pixels = new int[size * size];
                            bitmap.getPixels(pixels,0,size,0,0,size,size);
                            bitmap.recycle();
                            long ttl = cacheLifetime(link.getHeaderField("Cache-Control"));
                            CACHE.put(id,new Tile(pixels,size,now + ttl));
                            return "";
                        }
                        bitmap.recycle();
                    }
                }
            }
            CACHE.put(id,new Tile(null,0,now + (code == 204 || code == 404 ? 120_000 : 20_000)));
            return code == 204 || code == 404 ? "" : "离线 · 地图服务未响应";
        } catch (IOException | RuntimeException e) {
            CACHE.put(id,new Tile(null,0,now + 20_000));
            return "离线 · 无法连接地图";
        } finally {
            if (link != null) link.disconnect();
        }
    }

    private static long cacheLifetime(String header) {
        if (header != null) {
            for (String directive : header.toLowerCase(Locale.ROOT).split(",")) {
                String value = directive.trim();
                if (value.equals("no-store") || value.equals("no-cache")) return 10_000;
                if (value.startsWith("max-age=")) {
                    try {
                        return Math.max(1000,Math.min(3_600_000,Long.parseLong(value.substring(8)) * 1000));
                    } catch (NumberFormatException ignored) {
                        break;
                    }
                }
            }
        }
        return 300_000;
    }

    static final class Sampler {
        final List<Long> missing = new ArrayList<>();
        final int zoom, side;
        private final Map<Long,Tile> seen = new HashMap<>();
        private long previous = -1;
        private Tile tile;
        boolean used;
        long retryAfter = Long.MAX_VALUE;

        void finish() {
            seen.clear();
            tile = null;
        }

        Sampler(int zoom) {
            this.zoom = zoom;
            side = 1 << zoom;
        }

        int sample(double u, double v) {
            double mercator = SatelliteProjection.tileY(v);
            if (mercator < 0) return 0;
            double tx = u * side, ty = mercator * side;
            int x = (int)tx, y = Math.min(side-1,(int)ty);
            long id = ((long)zoom << 34) | ((long)x << 17) | y;
            if (id != previous) {
                previous = id;
                tile = seen.get(id);
                if (tile == null) {
                    tile = cached(id);
                    if (tile == null) {
                        if (missing.size() < 30) missing.add(id);
                        tile = MISSING;
                    } else if (tile.pixels == null) {
                        retryAfter = Math.min(retryAfter,tile.until);
                    }
                    seen.put(id,tile);
                }
            }
            if (tile == null || tile.pixels == null) return 0;
            used = true;
            return GlobeTiles.sampleTexture(tile.pixels,tile.size,tile.size,
                    (tx-x)*tile.size-.5,(ty-y)*tile.size-.5,false);
        }
    }

    private static final class Tile {
        final int[] pixels;
        final int size;
        final long until;

        Tile(int[] pixels, int size, long until) {
            this.pixels = pixels;
            this.size = size;
            this.until = until;
        }
    }
}
