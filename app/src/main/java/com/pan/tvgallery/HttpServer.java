package com.pan.tvgallery;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP/1.1 服务（每个请求一个连接，Connection: close），不依赖第三方库。
 *
 * <pre>
 * GET  /                     上传管理页面
 * GET  /api/state            图片列表 + 设置
 * POST /api/upload?ext=jpg   请求体为图片 / 视频原始字节
 * POST /api/delete?name=...  删除一张
 * POST /api/clear            清空
 * POST /api/order            JSON: {names: [...]} 播放顺序
 * POST /api/hide?name=&hidden=1|0  隐藏 / 取消隐藏（电视不播放隐藏的内容）
 * POST /api/settings         JSON: {interval, shuffle, fill}
 * POST /api/control?action=  prev | next | toggle
 * GET  /img/{name}           图片 / 视频文件（支持 Range，手机浏览器才能播放视频）
 * </pre>
 */
final class HttpServer {

    private static final String TAG = "TvGallery";
    private static final long MAX_IMAGE = 64L * 1024 * 1024;
    private static final long MAX_VIDEO = 4L * 1024 * 1024 * 1024;
    private static final long SPACE_RESERVE = 200L * 1024 * 1024; // 给系统留的空间
    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_JSON = 16 * 1024;
    private static final int MAX_ORDER_JSON = 1024 * 1024; // 几千个文件名也够用

    private final GalleryApp app;
    private final AssetManager assets;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile ServerSocket serverSocket;

    HttpServer(GalleryApp app, AssetManager assets) {
        this.app = app;
        this.assets = assets;
    }

    /** 优先使用指定端口，被占用时依次尝试后面 10 个端口。 */
    void start(int preferredPort) throws IOException {
        IOException last = null;
        for (int port = preferredPort; port < preferredPort + 10; port++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                serverSocket = ss;
                Thread t = new Thread(this::acceptLoop, "http-accept");
                t.setDaemon(true);
                t.start();
                Log.i(TAG, "HTTP 服务已启动，端口 " + port);
                return;
            } catch (BindException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("没有可用端口");
    }

    int port() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    private void acceptLoop() {
        ServerSocket ss = serverSocket;
        while (ss != null && !ss.isClosed()) {
            try {
                Socket s = ss.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                Log.w(TAG, "accept 失败", e);
            }
        }
    }

    // ---------------------------------------------------------------- 请求解析

    private static final class Request {
        String method;
        String path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        InputStream body;
        long contentLength = -1;
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            Request req = parse(in);
            if (req == null) return;
            if ("100-continue".equalsIgnoreCase(req.headers.get("expect"))) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            try {
                route(req, out);
            } catch (HttpError e) {
                sendText(out, e.status, e.getMessage());
            } catch (SocketException e) {
                // 客户端断开，忽略
            } catch (Exception e) {
                Log.e(TAG, "处理请求失败 " + req.method + " " + req.path, e);
                sendText(out, 500, "服务器错误: " + e.getMessage());
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static Request parse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0; // 已匹配的 \r\n\r\n 字节数
        while (state < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEAD) return null;
            if ((state % 2 == 0 && b == '\r') || (state % 2 == 1 && b == '\n')) {
                state++;
            } else {
                state = b == '\r' ? 1 : 0;
            }
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;

        Request req = new Request();
        req.method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        int q = target.indexOf('?');
        req.path = decode(q >= 0 ? target.substring(0, q) : target);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                if (eq < 0) req.query.put(decode(pair), "");
                else req.query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                req.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(c + 1).trim());
            }
        }
        String cl = req.headers.get("content-length");
        if (cl != null) {
            try {
                req.contentLength = Long.parseLong(cl);
            } catch (NumberFormatException ignored) {
            }
        }
        req.body = in;
        return req;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- 路由

    private static final class HttpError extends Exception {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void route(Request req, OutputStream out) throws Exception {
        String p = req.path;
        boolean get = "GET".equals(req.method) || "HEAD".equals(req.method);
        boolean post = "POST".equals(req.method);

        if (get && ("/".equals(p) || "/index.html".equals(p))) {
            sendAsset(out, "web/index.html", "text/html; charset=utf-8");
        } else if (get && "/api/state".equals(p)) {
            sendJson(out, state());
        } else if (get && p.startsWith("/img/")) {
            File f = app.store().get(p.substring("/img/".length()));
            if (f == null) throw new HttpError(404, "图片不存在");
            sendFile(out, f, "HEAD".equals(req.method), req.headers.get("range"));
        } else if (post && "/api/upload".equals(p)) {
            upload(req, out);
        } else if (post && "/api/delete".equals(p)) {
            if (!app.store().delete(req.query.get("name"))) throw new HttpError(404, "图片不存在");
            app.notifyImagesChanged();
            sendJson(out, state());
        } else if (post && "/api/order".equals(p)) {
            JSONArray arr = new JSONObject(readBodyString(req, MAX_ORDER_JSON)).getJSONArray("names");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) names.add(arr.getString(i));
            app.store().setOrder(names);
            app.notifyImagesChanged();
            sendJson(out, state());
        } else if (post && "/api/hide".equals(p)) {
            boolean hide = !"0".equals(req.query.get("hidden")) && !"false".equals(req.query.get("hidden"));
            if (!app.store().setHidden(req.query.get("name"), hide)) throw new HttpError(404, "内容不存在");
            app.notifyImagesChanged();
            sendJson(out, state());
        } else if (post && "/api/clear".equals(p)) {
            app.store().clear();
            app.notifyImagesChanged();
            sendJson(out, state());
        } else if (post && "/api/settings".equals(p)) {
            app.settings().update(new JSONObject(readBodyString(req, MAX_JSON)));
            app.notifySettingsChanged();
            sendJson(out, state());
        } else if (post && "/api/control".equals(p)) {
            String action = req.query.get("action");
            if (!"prev".equals(action) && !"next".equals(action) && !"toggle".equals(action)) {
                throw new HttpError(400, "未知操作");
            }
            app.notifyControl(action);
            sendJson(out, new JSONObject().put("ok", true));
        } else if (get && "/favicon.ico".equals(p)) {
            send(out, 204, "text/plain", new byte[0]);
        } else {
            throw new HttpError(404, "Not Found");
        }
    }

    private void upload(Request req, OutputStream out) throws Exception {
        String ext = req.query.get("ext");
        if (ext == null || !ImageStore.isAllowed(ext)) throw new HttpError(415, "不支持的文件格式");
        boolean video = ImageStore.isVideo(ext);
        if (req.contentLength <= 0) throw new HttpError(411, "缺少 Content-Length");
        if (req.contentLength > (video ? MAX_VIDEO : MAX_IMAGE)) {
            throw new HttpError(413, video ? "视频太大（最大 4GB）" : "图片太大（最大 64MB）");
        }
        if (app.store().usableSpace() - req.contentLength < SPACE_RESERVE) {
            throw new HttpError(507, "电视存储空间不足");
        }
        File saved = app.store().save(req.body, req.contentLength, ext);
        if (saved == null) throw new HttpError(415, video ? "无法识别的视频文件" : "无法识别的图片文件");
        app.notifyImagesChanged();
        sendJson(out, new JSONObject().put("ok", true).put("name", saved.getName()));
    }

    private JSONObject state() throws Exception {
        JSONArray images = new JSONArray();
        List<File> files = app.store().list();
        for (File f : files) {
            JSONObject item = new JSONObject().put("name", f.getName()).put("size", f.length())
                    .put("hidden", app.store().isHidden(f.getName()));
            if (ImageStore.isVideo(f)) {
                item.put("type", "video").put("duration", app.store().durationMs(f));
            } else {
                item.put("type", "image");
            }
            images.put(item);
        }
        return new JSONObject()
                .put("images", images)
                .put("settings", app.settings().toJson())
                .put("url", app.serverUrl());
    }

    private static String readBodyString(Request req, int max) throws IOException, HttpError {
        if (req.contentLength < 0 || req.contentLength > max) throw new HttpError(400, "请求体无效");
        byte[] buf = new byte[(int) req.contentLength];
        int off = 0;
        while (off < buf.length) {
            int n = req.body.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 响应

    private void sendAsset(OutputStream out, String name, String type) throws IOException {
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            send(out, 200, type, buf.toByteArray());
        }
    }

    private static void sendFile(OutputStream out, File f, boolean headOnly, String range) throws IOException {
        long total = f.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        // 只支持单段 Range：bytes=start-end / bytes=start- / bytes=-suffix
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, total - suffix);
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                }
                partial = true;
            } catch (NumberFormatException ignored) {
            }
            if (partial && (start > end || start >= total)) {
                String head = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                return;
            }
        }
        long length = end - start + 1;
        String head = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
                + "Content-Type: " + mimeOf(f.getName()) + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n" : "")
                + "Accept-Ranges: bytes\r\n"
                + "Cache-Control: public, max-age=31536000, immutable\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (headOnly) return;
        try (InputStream in = new FileInputStream(f)) {
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) throw new IOException("skip 失败");
                skipped += n;
            }
            byte[] b = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(b, 0, (int) Math.min(b.length, remaining));
                if (n < 0) break;
                out.write(b, 0, n);
                remaining -= n;
            }
        }
    }

    private static void sendJson(OutputStream out, JSONObject json) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendText(OutputStream out, int status, String text) throws IOException {
        send(out, status, "text/plain; charset=utf-8", String.valueOf(text).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int status, String type, byte[] body) throws IOException {
        writeHead(out, status, type, body.length, "no-store");
        out.write(body);
    }

    private static void writeHead(OutputStream out, int status, String type, long length, String cache)
            throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    private static String mimeOf(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        switch (ext) {
            case "jpg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "mp4": return "video/mp4";
            case "m4v": return "video/x-m4v";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "3gp": return "video/3gpp";
            default: return "application/octet-stream";
        }
    }
}
