package com.pan.tvgallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/** 轮播设置，网页端修改后保存在 SharedPreferences。 */
public final class Settings {

    public static final List<String> EFFECTS =
            Arrays.asList("fade", "slide", "zoom", "kenburns", "flip", "random");

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** 每张图片的停留秒数。 */
    public int intervalSec() {
        return prefs.getInt("interval", 8);
    }

    /** 随机播放。 */
    public boolean shuffle() {
        return prefs.getBoolean("shuffle", false);
    }

    /** true 为铺满屏幕（会裁切），false 为完整显示（可能有黑边）。 */
    public boolean fill() {
        return prefs.getBoolean("fill", false);
    }

    /** 电视播放视频时是否有声音。 */
    public boolean videoSound() {
        return prefs.getBoolean("sound", true);
    }

    /** 电视右下角常驻显示上传二维码。 */
    public boolean showQr() {
        return prefs.getBoolean("qr", false);
    }

    /** 切换动效：fade / slide / zoom / kenburns / flip / random。 */
    public String effect() {
        return prefs.getString("effect", "kenburns");
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("interval", intervalSec())
                .put("shuffle", shuffle())
                .put("fill", fill())
                .put("effect", effect())
                .put("qr", showQr())
                .put("sound", videoSound());
    }

    public void update(JSONObject json) {
        SharedPreferences.Editor e = prefs.edit();
        if (json.has("interval")) {
            int v = json.optInt("interval", intervalSec());
            e.putInt("interval", Math.max(2, Math.min(3600, v)));
        }
        if (json.has("shuffle")) e.putBoolean("shuffle", json.optBoolean("shuffle"));
        if (json.has("fill")) e.putBoolean("fill", json.optBoolean("fill"));
        if (json.has("qr")) e.putBoolean("qr", json.optBoolean("qr"));
        if (json.has("sound")) e.putBoolean("sound", json.optBoolean("sound"));
        if (json.has("effect")) {
            String v = json.optString("effect");
            if (EFFECTS.contains(v)) e.putString("effect", v);
        }
        e.apply();
    }
}
