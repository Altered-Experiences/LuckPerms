/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.webeditor.local;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalWebEditorServer {

    private static final String EDITOR_PREFIX = "/editor";

    private final OkHttpClient httpClient;
    private final PluginLogger logger;
    private final String bindAddress;
    private final int port;
    private final String upstreamUrl;
    private final String publicUrl;

    private HttpServer server;
    private ExecutorService executor;

    public LocalWebEditorServer(LuckPermsPlugin plugin, OkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.logger = plugin.getLogger();
        this.bindAddress = plugin.getConfiguration().get(ConfigKeys.LOCAL_WEB_EDITOR_BIND_ADDRESS);
        this.port = plugin.getConfiguration().get(ConfigKeys.LOCAL_WEB_EDITOR_PORT);
        this.upstreamUrl = normalizeTrailingSlash(plugin.getConfiguration().get(ConfigKeys.LOCAL_WEB_EDITOR_UPSTREAM_URL));
        this.publicUrl = normalizeTrailingSlash(resolvePublicUrl(plugin));
    }

    public void start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(this.bindAddress, this.port);
        this.server = HttpServer.create(address, 0);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "luckperms-local-editor");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(this.executor);
        this.server.createContext("/", this::handleRoot);
        this.server.createContext(EDITOR_PREFIX, this::handleEditorProxy);
        this.server.start();
        this.logger.info("Local web editor proxy started at " + this.publicUrl);
    }

    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
    }

    public String getPublicUrl() {
        return this.publicUrl;
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Location", this.publicUrl);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleEditorProxy(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String targetUrl = buildUpstreamUrl(exchange.getRequestURI());
        Request.Builder requestBuilder = new Request.Builder().url(targetUrl).method(method, null);
        requestBuilder.header("User-Agent", "luckperms/local-web-editor");

        Call call = this.httpClient.newCall(requestBuilder.build());
        try (Response response = call.execute()) {
            Headers responseHeaders = exchange.getResponseHeaders();
            for (String name : response.headers().names()) {
                responseHeaders.add(name, Objects.requireNonNull(response.header(name)));
            }

            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(response.code(), -1);
                exchange.close();
                return;
            }

            ResponseBody body = response.body();
            if (body == null) {
                exchange.sendResponseHeaders(response.code(), -1);
                exchange.close();
                return;
            }

            byte[] bytes = body.bytes();
            exchange.sendResponseHeaders(response.code(), bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        } catch (IOException ex) {
            this.logger.warn("Failed to proxy web editor request to " + targetUrl, ex);
            byte[] message = "Local web editor proxy failed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, message.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(message);
            }
        } finally {
            exchange.close();
        }
    }

    private String buildUpstreamUrl(URI requestUri) {
        String path = requestUri.getPath();
        String suffix = path.substring(EDITOR_PREFIX.length());
        if (suffix.isEmpty()) {
            suffix = "/";
        }

        StringBuilder url = new StringBuilder(this.upstreamUrl);
        if (suffix.startsWith("/")) {
            url.append(suffix.substring(1));
        } else {
            url.append(suffix);
        }

        if (requestUri.getRawQuery() != null) {
            url.append('?').append(requestUri.getRawQuery());
        }
        return url.toString();
    }

    public static String resolvePublicUrl(LuckPermsPlugin plugin) {
        String configured = plugin.getConfiguration().get(ConfigKeys.LOCAL_WEB_EDITOR_PUBLIC_URL);
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }

        String bind = plugin.getConfiguration().get(ConfigKeys.LOCAL_WEB_EDITOR_BIND_ADDRESS);
        int port = plugin.getConfiguration().get(ConfigKeys.LOCAL_WEB_EDITOR_PORT);
        String host = bind;
        if ("0.0.0.0".equals(bind) || "::".equals(bind)) {
            host = "127.0.0.1";
        }

        return "http://" + host + ":" + port + "/editor/";
    }

    private static String normalizeTrailingSlash(String value) {
        String trimmed = Objects.requireNonNull(value).trim();
        if (trimmed.endsWith("/")) {
            return trimmed;
        }
        return trimmed + "/";
    }
}
