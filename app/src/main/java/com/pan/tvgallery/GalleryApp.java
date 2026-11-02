package com.pan.tvgallery;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程级单例：持有图片库、设置和 HTTP 服务，应用进程存活期间服务一直在线。 */
public final class GalleryApp extends Application {

    public static final int DEFAULT_PORT = 8080;
    private static final String TAG = "TvGallery";

    /** 网页端操作通知电视画面刷新，回调都在主线程执行。 */
    public interface Listener {
        void onImagesChanged();

        void onSettingsChanged();

        void onControl(String action);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private ImageStore store;
    private Settings settings;
    private HttpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new ImageStore(new File(getFilesDir(), "images"));
        settings = new Settings(this);
        server = new HttpServer(this, getAssets());
        try {
            server.start(DEFAULT_PORT);
        } catch (Exception e) {
            Log.e(TAG, "HTTP 服务启动失败", e);
        }
    }

    public ImageStore store() {
        return store;
    }

    public Settings settings() {
        return settings;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    void notifyImagesChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onImagesChanged();
        });
    }

    void notifySettingsChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onSettingsChanged();
        });
    }

    void notifyControl(String action) {
        main.post(() -> {
            for (Listener l : listeners) l.onControl(action);
        });
    }

    /** 返回局域网访问地址，例如 http://192.168.1.20:8080 ，没有网络时返回 null。 */
    public String serverUrl() {
        int port = server.port();
        if (port <= 0) return null;
        String ip = lanIp();
        return ip == null ? null : "http://" + ip + ":" + port;
    }

    private static String lanIp() {
        String fallback = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    // 优先有线 / Wi-Fi 网卡
                    String name = nif.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
