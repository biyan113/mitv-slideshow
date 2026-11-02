package com.pan.tvgallery;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/**
 * 图片和视频保存在应用私有目录，文件名按上传时间生成。
 * 播放顺序和隐藏状态保存在 meta.json：没排过序的内容按上传时间排在后面。
 */
public final class ImageStore {

    public static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"));
    public static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mov", "webm", "mkv", "3gp"));

    private static final Pattern SAFE_NAME = Pattern.compile("^[0-9]{13}_[0-9]{4}\\.[a-z0-9]{3,4}$");

    private final File dir;
    private final File metaFile;
    private final Map<String, Long> durations = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private int counter;

    public ImageStore(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        metaFile = new File(dir.getParentFile(), "meta.json");
        loadMeta();
    }

    /** 全部内容（含隐藏的），按播放顺序。 */
    public synchronized List<File> list() {
        File[] files = dir.listFiles();
        List<File> result = new ArrayList<>();
        if (files == null) return result;
        for (File f : files) {
            if (f.isFile() && SAFE_NAME.matcher(f.getName()).matches()) result.add(f);
        }
        final Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < order.size(); i++) pos.put(order.get(i), i);
        Collections.sort(result, (a, b) -> {
            Integer pa = pos.get(a.getName());
            Integer pb = pos.get(b.getName());
            if (pa != null && pb != null) return pa - pb;
            if (pa != null) return -1; // 排过序的在前，新上传的追加在后
            if (pb != null) return 1;
            return a.getName().compareTo(b.getName());
        });
        return result;
    }

    /** 电视要播放的内容：去掉隐藏的。 */
    public synchronized List<File> visible() {
        List<File> result = list();
        for (int i = result.size() - 1; i >= 0; i--) {
            if (hidden.contains(result.get(i).getName())) result.remove(i);
        }
        return result;
    }

    public synchronized boolean isHidden(String name) {
        return hidden.contains(name);
    }

    public synchronized boolean setHidden(String name, boolean hide) {
        if (get(name) == null) return false;
        if (hide) hidden.add(name);
        else hidden.remove(name);
        saveMeta();
        return true;
    }

    /** 按给定名字设置播放顺序，未列出的内容保持原相对顺序排在后面。 */
    public synchronized void setOrder(List<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        for (String n : names) if (get(n) != null) seen.add(n);
        for (File f : list()) seen.add(f.getName());
        order.clear();
        order.addAll(seen);
        saveMeta();
    }

    private void loadMeta() {
        if (!metaFile.isFile()) return;
        try (InputStream in = new FileInputStream(metaFile)) {
            byte[] buf = new byte[(int) metaFile.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONObject json = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            JSONArray o = json.optJSONArray("order");
            if (o != null) for (int i = 0; i < o.length(); i++) order.add(o.getString(i));
            JSONArray h = json.optJSONArray("hidden");
            if (h != null) for (int i = 0; i < h.length(); i++) hidden.add(h.getString(i));
        } catch (Exception e) {
            Log.w("TvGallery", "读取 meta.json 失败", e);
        }
    }

    /** 先写临时文件再改名，断电也不会把 meta.json 写坏。 */
    private void saveMeta() {
        try {
            JSONObject json = new JSONObject()
                    .put("order", new JSONArray(order))
                    .put("hidden", new JSONArray(hidden));
            File tmp = new File(metaFile.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(metaFile)) Log.w("TvGallery", "保存 meta.json 失败");
        } catch (Exception e) {
            Log.w("TvGallery", "保存 meta.json 失败", e);
        }
    }

    /** 根据名字取文件，名字不合法或不存在时返回 null（防止路径穿越）。 */
    public File get(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    public static boolean isAllowed(String ext) {
        ext = ext.toLowerCase(Locale.ROOT);
        return IMAGE_EXT.contains(ext) || VIDEO_EXT.contains(ext);
    }

    public static boolean isVideo(String ext) {
        return VIDEO_EXT.contains(ext.toLowerCase(Locale.ROOT));
    }

    public static boolean isVideo(File f) {
        String name = f.getName();
        return isVideo(name.substring(name.lastIndexOf('.') + 1));
    }

    public long usableSpace() {
        return dir.getUsableSpace();
    }

    /** 视频时长（毫秒），读取一次后缓存；读不到返回 0。 */
    public long durationMs(File f) {
        Long cached = durations.get(f.getName());
        if (cached != null) return cached;
        long d = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (v != null) d = Long.parseLong(v);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        durations.put(f.getName(), d);
        return d;
    }

    /**
     * 先写入临时文件，校验能被解码后再改名，避免电视端读到半个文件。
     * 返回 null 表示文件不是可识别的图片或视频。
     */
    public File save(InputStream in, long length, String ext) throws IOException {
        ext = ext.toLowerCase(Locale.ROOT);
        if ("jpeg".equals(ext)) ext = "jpg";
        String name;
        synchronized (this) {
            counter = (counter + 1) % 10000;
            name = String.format(Locale.ROOT, "%013d_%04d.%s", System.currentTimeMillis(), counter, ext);
        }
        File tmp = new File(dir, name + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("上传中断");
                out.write(buf, 0, n);
                remaining -= n;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        if (!(isVideo(ext) ? isPlayableVideo(tmp) : isDecodableImage(tmp))) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        File dest = new File(dir, name);
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("保存失败");
        }
        return dest;
    }

    private static boolean isDecodableImage(File f) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), opts);
        return opts.outWidth > 0 && opts.outHeight > 0;
    }

    /** 能读出视频轨道信息即认为可播放（具体编码能否硬解由电视决定）。 */
    private static boolean isPlayableVideo(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            return "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    || r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) != null;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized boolean delete(String name) {
        File f = get(name);
        durations.remove(String.valueOf(name));
        if (f == null || !f.delete()) return false;
        if (order.remove(name) | hidden.remove(name)) saveMeta();
        return true;
    }

    public synchronized int clear() {
        int n = 0;
        for (File f : list()) {
            if (f.delete()) n++;
        }
        order.clear();
        hidden.clear();
        durations.clear();
        saveMeta();
        return n;
    }
}
