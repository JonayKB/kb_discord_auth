package es.jonaykb.kb_discord_auth.client;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.KbDiscordAuth;
import es.jonaykb.kb_discord_auth.avatar.AvatarMarker.Avatar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Downloads Discord avatars from the CDN, turns them into round 32x32 textures and caches them. */
public final class AvatarTextures {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Only for testing against a fake CDN: -Dkb_discord_auth.cdnBase=http://127.0.0.1:18999 */
    private static final String CDN = System.getProperty("kb_discord_auth.cdnBase", "https://cdn.discordapp.com");
    private static final int SIZE = 32;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "kb_discord_auth avatars");
        t.setDaemon(true);
        return t;
    });

    private enum State { LOADING, READY, FAILED }

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile ResourceLocation texture;
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static int generation;

    private AvatarTextures() {}

    /** The avatar's texture, or null while it is downloading or if it could not be loaded. */
    @Nullable
    public static ResourceLocation get(Avatar avatar) {
        Entry e = CACHE.computeIfAbsent(avatar.key(), k -> {
            Entry n = new Entry();
            int gen = generation;
            WORKER.execute(() -> load(avatar, n, gen));
            return n;
        });
        return e.state == State.READY ? e.texture : null;
    }

    private static void load(Avatar avatar, Entry entry, int gen) {
        NativeImage img = null;
        if (avatar.hash() != null) {
            img = download(CDN + "/avatars/" + avatar.discordId() + "/" + avatar.hash() + ".png?size=64");
        }
        if (img == null) {
            // Discord's default avatar for users without a picture (or if theirs failed): (id >> 22) % 6.
            long index = (Long.parseLong(avatar.discordId()) >>> 22) % 6;
            img = download(CDN + "/embed/avatars/" + index + ".png");
        }
        if (img == null) {
            entry.state = State.FAILED;
            return;
        }
        NativeImage round = roundAndScale(img);
        img.close();
        Minecraft.getInstance().execute(() -> {
            if (gen != generation) {
                round.close();
                return;
            }
            ResourceLocation rl = new ResourceLocation(KbDiscordAuth.MOD_ID, "avatars/" + avatar.key().replace('/', '_'));
            DynamicTexture texture = new DynamicTexture(round);
            texture.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(rl, texture);
            entry.texture = rl;
            entry.state = State.READY;
        });
    }

    @Nullable
    private static NativeImage download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "kb_discord_auth")
                    .GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200 || res.body().length > MAX_BYTES) return null;
            return NativeImage.read(new ByteArrayInputStream(res.body()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.debug("Could not load Discord avatar {}: {}", url, e.toString());
            return null;
        }
    }

    /** Box-filters the image down to SIZE x SIZE and cuts it into an anti-aliased circle, like Discord. */
    private static NativeImage roundAndScale(NativeImage src) {
        NativeImage out = new NativeImage(SIZE, SIZE, true);
        int w = src.getWidth();
        int h = src.getHeight();
        float c = SIZE / 2f;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int x0 = x * w / SIZE, x1 = Math.max(x0 + 1, (x + 1) * w / SIZE);
                int y0 = y * h / SIZE, y1 = Math.max(y0 + 1, (y + 1) * h / SIZE);
                long[] sum = new long[4];
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = src.getPixelRGBA(sx, sy);
                        for (int ch = 0; ch < 4; ch++) sum[ch] += (p >>> (ch * 8)) & 0xFF;
                        n++;
                    }
                }
                float dx = x + 0.5f - c, dy = y + 0.5f - c;
                float coverage = Math.max(0f, Math.min(1f, c - (float) Math.sqrt(dx * dx + dy * dy) + 0.5f));
                int pixel = 0;
                for (int ch = 0; ch < 4; ch++) {
                    long v = sum[ch] / n;
                    if (ch == 3) v = Math.round(v * coverage); // alpha is the top byte in NativeImage's ABGR ints
                    pixel |= (int) v << (ch * 8);
                }
                out.setPixelRGBA(x, y, pixel);
            }
        }
        return out;
    }

    /** Frees every avatar texture. Called when leaving a server. */
    public static void clear() {
        generation++;
        var textures = Minecraft.getInstance().getTextureManager();
        for (Entry e : new ArrayList<>(CACHE.values())) {
            if (e.texture != null) textures.release(e.texture);
        }
        CACHE.clear();
    }
}
