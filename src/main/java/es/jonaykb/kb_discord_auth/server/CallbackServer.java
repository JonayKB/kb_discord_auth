package es.jonaykb.kb_discord_auth.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.server.AuthManager.CallbackResult;

/** Small HTTP server (JDK built-in) that receives Discord's OAuth2 redirect. */
final class CallbackServer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final AuthManager auth;
    private HttpServer http;
    private ExecutorService pool;

    CallbackServer(AuthManager auth) {
        this.auth = auth;
    }

    void start() throws IOException {
        String path = URI.create(AuthConfig.REDIRECT_URI.get()).getPath();
        if (path == null || path.isEmpty()) path = "/";
        String callbackPath = path;

        http = HttpServer.create(new InetSocketAddress(AuthConfig.HTTP_BIND_ADDRESS.get(), AuthConfig.HTTP_PORT.get()), 0);
        http.createContext("/", exchange -> {
            try (exchange) {
                String reqPath = exchange.getRequestURI().getPath();
                WebAuth web = auth.web();
                if (reqPath.startsWith("/web/") && web != null) {
                    web.handle(exchange);
                } else if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                } else if (!reqPath.equals(callbackPath)) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    handleCallback(exchange);
                }
            } catch (Exception e) {
                LOGGER.warn("Error handling HTTP request {}", exchange.getRequestURI().getPath(), e);
            }
        });
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "kb_discord_auth-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.start();
        LOGGER.info("Discord callback listening on {}:{}{}", AuthConfig.HTTP_BIND_ADDRESS.get(), AuthConfig.HTTP_PORT.get(), callbackPath);
    }

    void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        WebAuth web = auth.web();
        if (web != null && web.ownsState(q.get("state"))) {
            web.handleCallback(exchange, q);
            return;
        }
        CallbackResult result;
        if (q.containsKey("error")) {
            // The player pressed "Cancel" on Discord. Their link in Minecraft is still valid.
            result = new CallbackResult(false, "Inicio de sesión cancelado",
                    "Has cancelado el inicio de sesión. Vuelve a Minecraft y pulsa otra vez el botón para entrar.");
        } else {
            result = auth.handleCallback(q.get("code"), q.get("state"));
        }
        byte[] body = page(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // malformed %-escape: skip the pair
            }
        }
        return out;
    }

    private static String page(CallbackResult r) {
        String accent = r.success() ? "#3ba55d" : "#ed4245";
        String icon = r.success() ? "&#10003;" : "&#10007;";
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                  :root { color-scheme: dark; }
                  body { margin:0; min-height:100vh; display:grid; place-items:center; background:#1e1f22;
                         font-family: system-ui, -apple-system, "Segoe UI", sans-serif; color:#dbdee1; }
                  main { max-width:420px; margin:16px; padding:32px; background:#2b2d31; border-radius:12px; text-align:center; }
                  .icon { width:56px; height:56px; margin:0 auto 16px; border-radius:50%%; display:grid; place-items:center;
                          font-size:28px; color:#fff; background:%s; }
                  h1 { font-size:20px; margin:0 0 8px; color:#f2f3f5; }
                  p { margin:0; line-height:1.5; }
                </style></head>
                <body><main><div class="icon">%s</div><h1>%s</h1><p>%s</p></main></body></html>
                """.formatted(escape(r.title()), accent, icon, escape(r.title()), escape(r.message()));
    }

    private static String escape(String s) {
        // Messages come from the config and may carry Minecraft color codes.
        return s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
