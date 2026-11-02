package com.pan.tvgallery;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全屏轮播图片和视频。遥控器：左/右 切换，OK 暂停/继续，上/菜单 显示上传地址。
 * 图片按设置的秒数停留，视频完整播放完再切到下一项。
 */
public final class MainActivity extends Activity implements GalleryApp.Listener {

    private static final long TRANSITION_MS = 1200;
    private static final String[] RANDOM_EFFECTS = {"fade", "slide", "zoom", "kenburns", "flip"};
    private static final long INFO_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private GalleryApp app;
    private ImageView[] views;
    private int front; // 当前显示在上层的 ImageView 下标
    private View emptyView;
    private TextView emptyUrl;
    private TextView emptyTitle;
    private TextView infoBar;
    private View qrCard;
    private ImageView emptyQr;
    private ImageView cardQr;
    private String qrUrl; // 当前二维码对应的地址，地址变了才重新生成

    private TextureView videoView;
    private Surface videoSurface;
    private MediaPlayer player;       // 正在播放的视频
    private MediaPlayer fadingPlayer; // 被图片盖住、等淡出结束再释放的视频
    private int videoW;
    private int videoH;

    private List<File> playlist = new ArrayList<>();
    private int index = -1;
    private File current;
    private String newestName; // 用于识别「新上传」的图片并立即展示
    private boolean paused;
    private int loadToken;
    private int screenW;
    private int screenH;
    private final Random random = new Random();

    private final Runnable advance = () -> show(index + 1, 1);
    private final Runnable releaseFading = () -> {
        releaseFadingPlayer();
        if (player == null) videoView.setAlpha(0f);
    };
    private final Runnable hideInfo = () -> {
        infoBar.setVisibility(View.GONE);
        if (!app.settings().showQr()) qrCard.setVisibility(View.GONE);
    };

    /** 开机时 Wi-Fi 可能还没连上，地址拿不到就每 3 秒重试一次。 */
    private final Runnable refreshUrl = new Runnable() {
        @Override
        public void run() {
            String url = app.serverUrl();
            updateQr(url);
            emptyUrl.setText(url != null ? url : "正在等待网络连接…");
            if (app.settings().showQr()) {
                qrCard.setVisibility(url != null && !playlist.isEmpty() ? View.VISIBLE : View.GONE);
            }
            if (url == null) handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        app = (GalleryApp) getApplication();
        views = new ImageView[]{findViewById(R.id.image_a), findViewById(R.id.image_b)};
        emptyView = findViewById(R.id.empty_view);
        emptyUrl = findViewById(R.id.empty_url);
        emptyTitle = findViewById(R.id.empty_title);
        infoBar = findViewById(R.id.info_bar);
        qrCard = findViewById(R.id.qr_card);
        emptyQr = findViewById(R.id.empty_qr);
        cardQr = findViewById(R.id.card_qr);
        videoView = findViewById(R.id.video_view);
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                videoSurface = new Surface(st);
                // 视图还没准备好时就轮到了视频：现在补播
                if (current != null && ImageStore.isVideo(current) && player == null) show(index, 1);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyVideoTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                releaseFadingPlayer();
                if (videoSurface != null) videoSurface.release();
                videoSurface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        app.addListener(this);
        applyScaleType();
        reloadPlaylist(false);
        if (!playlist.isEmpty() && (current == null || (ImageStore.isVideo(current) && player == null))) {
            show(Math.max(index, 0), 1);
        } else {
            scheduleNext();
        }
        refreshUrl.run();
        showInfo(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.removeListener(this);
        handler.removeCallbacks(advance);
        handler.removeCallbacks(refreshUrl);
        // 离开应用时停掉视频，回来后从头播放当前视频
        releasePlayer();
        releaseFadingPlayer();
        videoView.setAlpha(0f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------------------------------------------------------------- 网页端回调

    @Override
    public void onImagesChanged() {
        String previousNewest = newestName;
        int previousIndex = index;
        reloadPlaylist(true);
        // 图片从无到有（或被清空）时同步角落二维码：没图片时引导页已有大二维码
        if (app.settings().showQr() || playlist.isEmpty()) {
            qrCard.setVisibility(qrUrl != null && !playlist.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (playlist.isEmpty()) return;
        // 有新图片上传：立即切到最新的一张
        if (newestName != null && (previousNewest == null || newestName.compareTo(previousNewest) > 0)) {
            for (int i = 0; i < playlist.size(); i++) {
                if (playlist.get(i).getName().equals(newestName)) {
                    show(i, 1);
                    return;
                }
            }
        }
        // 当前内容被删除或隐藏了：顺延到原位置的下一项
        if (current == null || !playlist.contains(current)) show(Math.max(previousIndex, 0), 1);
    }

    @Override
    public void onSettingsChanged() {
        applyScaleType();
        applyVideoTransform();
        if (player != null) setVolume(player);
        if (!app.settings().showQr()) qrCard.setVisibility(View.GONE);
        reloadPlaylist(false);
        scheduleNext();
        showInfo("设置已更新：图片每张 " + app.settings().intervalSec() + " 秒"
                + (app.settings().shuffle() ? "，随机" : "，顺序")
                + (app.settings().fill() ? "，铺满" : "，完整显示")
                + "，动效：" + effectLabel(app.settings().effect())
                + (app.settings().videoSound() ? "，视频有声" : "，视频静音"));
    }

    @Override
    public void onControl(String action) {
        switch (action) {
            case "prev": show(index - 1, -1); break;
            case "next": show(index + 1, 1); break;
            case "toggle": togglePause(); break;
        }
    }

    // ---------------------------------------------------------------- 遥控器

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                show(index - 1, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                show(index + 1, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                showInfo(null);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private static String effectLabel(String effect) {
        switch (effect) {
            case "fade": return "淡入淡出";
            case "slide": return "滑动";
            case "zoom": return "缩放";
            case "kenburns": return "缓慢推镜";
            case "flip": return "3D 翻转";
            case "random": return "随机";
            default: return effect;
        }
    }

    private void togglePause() {
        paused = !paused;
        if (paused) {
            handler.removeCallbacks(advance);
            if (player != null && player.isPlaying()) player.pause();
        } else {
            if (player != null) player.start();
            scheduleNext();
        }
        showInfo(paused ? "已暂停（按 OK 继续）" : "继续播放");
    }

    // ---------------------------------------------------------------- 播放逻辑

    private void reloadPlaylist(boolean keepOrder) {
        List<File> files = app.store().visible();
        // 文件名以上传时间开头，名字最大的就是最新上传的
        newestName = null;
        for (File f : files) {
            if (newestName == null || f.getName().compareTo(newestName) > 0) newestName = f.getName();
        }
        if (keepOrder && app.settings().shuffle() && !playlist.isEmpty()) {
            // 随机模式下保留已有顺序，新图片追加在后面
            List<File> merged = new ArrayList<>();
            for (File f : playlist) if (files.contains(f)) merged.add(f);
            for (File f : files) if (!merged.contains(f)) merged.add(f);
            files = merged;
        } else if (app.settings().shuffle()) {
            Collections.shuffle(files);
        }
        playlist = files;
        index = current == null ? -1 : playlist.indexOf(current);
        // 只有一个视频时循环播放；之后又上传了别的内容就改为播完切下一项
        if (player != null) player.setLooping(playlist.size() == 1);
        boolean empty = playlist.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyTitle.setText(empty && !app.store().list().isEmpty() ? "内容都被隐藏了" : "还没有图片或视频");
        if (empty) {
            current = null;
            index = -1;
            loadToken++;
            for (ImageView v : views) v.setImageDrawable(null);
            releasePlayer();
            releaseFadingPlayer();
            videoView.setAlpha(0f);
            handler.removeCallbacks(advance);
            handler.removeCallbacks(refreshUrl);
            refreshUrl.run();
        }
    }

    /** direction：1 为下一张，-1 为上一张（决定滑动 / 翻转方向）。 */
    private void show(int i, int direction) {
        if (playlist.isEmpty()) return;
        int n = playlist.size();
        final int target = ((i % n) + n) % n;
        final File file = playlist.get(target);
        final int token = ++loadToken;
        handler.removeCallbacks(advance);
        if (ImageStore.isVideo(file)) {
            index = target;
            current = file;
            playVideo(file, token);
            return;
        }
        loader.execute(() -> {
            Bitmap bmp = decode(file, screenW, screenH);
            handler.post(() -> {
                if (token != loadToken || isFinishing()) return;
                index = target;
                current = file;
                if (bmp != null) {
                    transition(bmp, direction);
                    coverVideo();
                }
                scheduleNext();
            });
        });
    }

    private void transition(Bitmap bmp, int direction) {
        ImageView in = views[1 - front];
        ImageView out = views[front];
        resetView(in);
        out.animate().cancel();
        in.setImageBitmap(bmp);
        in.bringToFront();
        infoBar.bringToFront(); // 信息条和二维码保持在最上层
        qrCard.bringToFront();
        front = 1 - front;

        String effect = app.settings().effect();
        if ("random".equals(effect)) effect = RANDOM_EFFECTS[random.nextInt(RANDOM_EFFECTS.length)];
        boolean first = out.getDrawable() == null;
        if (first && !"kenburns".equals(effect)) effect = "fade";
        if ("slide".equals(effect) || "zoom".equals(effect)) {
            // 这两种会改旧图的位移 / 缩放，先停掉旧图上的 Ken Burns 动画
            Object tag = out.getTag();
            if (tag instanceof Animator) ((Animator) tag).cancel();
            out.setTag(null);
        }

        Interpolator ease = new DecelerateInterpolator(1.6f);
        float w = in.getWidth() > 0 ? in.getWidth() : screenW;
        switch (effect) {
            case "slide":
                // 推入：新图从一侧滑入，旧图向另一侧滑出
                in.setTranslationX(w * direction);
                in.animate().translationX(0).setDuration(TRANSITION_MS).setInterpolator(ease).start();
                out.animate().translationX(-w * direction * 0.35f).alpha(0f)
                        .setDuration(TRANSITION_MS).setInterpolator(ease).withEndAction(() -> clear(out)).start();
                break;
            case "zoom":
                // 新图由放大状态收拢淡入，旧图轻微缩小淡出
                in.setAlpha(0f);
                in.setScaleX(1.25f);
                in.setScaleY(1.25f);
                in.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(TRANSITION_MS).setInterpolator(ease).start();
                out.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                        .setDuration(TRANSITION_MS).withEndAction(() -> clear(out)).start();
                break;
            case "flip": {
                // 3D 翻转：旧图转走一半，新图从另一面转出来
                float camera = getResources().getDisplayMetrics().density * 8000;
                in.setCameraDistance(camera);
                out.setCameraDistance(camera);
                long half = TRANSITION_MS / 2;
                in.setAlpha(0f);
                in.setRotationY(-90f * direction);
                out.animate().rotationY(90f * direction).setDuration(half)
                        .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                            clear(out);
                            in.setAlpha(1f);
                            in.animate().rotationY(0f).setDuration(half).setInterpolator(ease).start();
                        }).start();
                break;
            }
            case "kenburns":
                // 交叉淡入后，在停留期间缓慢推近并平移（肯·伯恩斯效果）
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(TRANSITION_MS).start();
                out.animate().alpha(0f).setDuration(TRANSITION_MS).withEndAction(() -> clear(out)).start();
                kenBurns(in);
                break;
            case "fade":
            default:
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(TRANSITION_MS).start();
                out.animate().alpha(0f).setDuration(TRANSITION_MS).withEndAction(() -> clear(out)).start();
                break;
        }
    }

    /** 在整个停留时间内做缓慢的缩放 + 平移，方向随机。 */
    private void kenBurns(ImageView v) {
        long duration = app.settings().intervalSec() * 1000L + TRANSITION_MS;
        boolean zoomIn = random.nextBoolean();
        float from = zoomIn ? 1.0f : 1.15f;
        float to = zoomIn ? 1.15f : 1.0f;
        float dx = (random.nextFloat() - 0.5f) * screenW * 0.06f;
        float dy = (random.nextFloat() - 0.5f) * screenH * 0.06f;
        v.setScaleX(from);
        v.setScaleY(from);
        // 用 ObjectAnimator 单独驱动缩放平移，不与透明度动画互相取消
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, from, to),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, from, to),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dx),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, dy));
        set.setDuration(duration);
        set.setInterpolator(new LinearInterpolator());
        v.setTag(set);
        set.start();
    }

    private static void resetView(ImageView v) {
        v.animate().cancel();
        Object tag = v.getTag();
        if (tag instanceof Animator) ((Animator) tag).cancel();
        v.setTag(null);
        v.setAlpha(1f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setRotationY(0f);
    }

    /** 旧图动画结束后释放位图，但保留 Ken Burns 的进行状态不受影响。 */
    private void clear(ImageView v) {
        if (v == views[front]) return; // 已经又被选作上层
        Object tag = v.getTag();
        if (tag instanceof Animator) ((Animator) tag).cancel();
        v.setTag(null);
        v.setImageDrawable(null);
        v.setAlpha(0f);
    }

    private void scheduleNext() {
        handler.removeCallbacks(advance);
        // 视频由播放完成回调驱动切换
        if (paused || playlist.size() < 2 || (current != null && ImageStore.isVideo(current))) return;
        handler.postDelayed(advance, app.settings().intervalSec() * 1000L);
    }

    private void applyScaleType() {
        ImageView.ScaleType type = app.settings().fill()
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER;
        for (ImageView v : views) v.setScaleType(type);
    }

    private void showInfo(String message) {
        String url = app.serverUrl();
        StringBuilder sb = new StringBuilder();
        if (message != null) sb.append(message).append('\n');
        sb.append("上传地址：").append(url != null ? url : "未连接网络");
        if (!playlist.isEmpty()) {
            sb.append("    第 ").append(index + 1).append(" / ").append(playlist.size()).append(" 项");
            if (current != null && ImageStore.isVideo(current)) sb.append("（视频）");
            if (paused) sb.append("（已暂停）");
        }
        infoBar.setText(sb);
        infoBar.setVisibility(View.VISIBLE);
        infoBar.bringToFront();
        updateQr(url);
        // 没有图片时引导页已经有大二维码，就不再显示角落的小卡片
        qrCard.setVisibility(url != null && !playlist.isEmpty() ? View.VISIBLE : View.GONE);
        qrCard.bringToFront();
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, INFO_MS);
    }

    private void updateQr(String url) {
        if (url == null) {
            qrUrl = null;
            emptyQr.setVisibility(View.INVISIBLE);
            cardQr.setImageDrawable(null);
            return;
        }
        if (url.equals(qrUrl)) return;
        Bitmap qr = QrCode.encode(url, 600);
        if (qr == null) return;
        qrUrl = url;
        emptyQr.setImageBitmap(qr);
        emptyQr.setVisibility(View.VISIBLE);
        cardQr.setImageBitmap(qr);
    }

    // ---------------------------------------------------------------- 视频

    private void playVideo(File file, int token) {
        if (videoSurface == null) return; // 等 onSurfaceTextureAvailable 再播
        releasePlayer();
        releaseFadingPlayer();
        handler.removeCallbacks(releaseFading);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getPath());
            mp.setSurface(videoSurface);
            mp.setLooping(playlist.size() == 1);
            setVolume(mp);
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (p != player || w <= 0 || h <= 0) return;
                videoW = w;
                videoH = h;
                applyVideoTransform();
            });
            mp.setOnPreparedListener(p -> {
                if (p != player || token != loadToken) return;
                if (paused) {
                    revealVideo(token);
                } else {
                    p.start();
                }
            });
            // 等新视频第一帧真正画出来再淡入，避免闪出上一次播放残留的画面
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && p == player && token == loadToken) {
                    revealVideo(token);
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (p == player && token == loadToken && !paused) show(index + 1, 1);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player && token == loadToken) {
                    showInfo("这个视频无法播放（可能是电视不支持的编码），已跳过");
                    releasePlayer();
                    if (playlist.size() > 1) handler.postDelayed(advance, 2000);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            showInfo("视频打开失败，已跳过");
            if (playlist.size() > 1) handler.postDelayed(advance, 2000);
        }
    }

    /** 视频首帧就绪后淡入，盖住下面的图片，淡入结束再释放图片内存。 */
    private void revealVideo(int token) {
        videoView.bringToFront();
        infoBar.bringToFront();
        qrCard.bringToFront();
        videoView.animate().cancel();
        if (videoView.getAlpha() >= 1f) return; // 视频接视频，直接换内容
        videoView.animate().alpha(1f).setDuration(TRANSITION_MS).withEndAction(() -> {
            if (token != loadToken) return;
            for (ImageView v : views) {
                resetView(v);
                v.setImageDrawable(null);
                v.setAlpha(0f);
            }
        }).start();
    }

    /** 新图片已经淡入盖在视频上面，等过渡结束再停掉视频。 */
    private void coverVideo() {
        if (player == null) return;
        releaseFadingPlayer();
        fadingPlayer = player;
        player = null;
        try {
            fadingPlayer.setVolume(0f, 0f);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(releaseFading);
        handler.postDelayed(releaseFading, TRANSITION_MS);
    }

    private void setVolume(MediaPlayer mp) {
        float v = app.settings().videoSound() ? 1f : 0f;
        try {
            mp.setVolume(v, v);
        } catch (Exception ignored) {
        }
    }

    /** 按「完整显示 / 铺满」缩放视频，保持原始比例。 */
    private void applyVideoTransform() {
        int vw = videoView.getWidth();
        int vh = videoView.getHeight();
        if (vw == 0 || vh == 0 || videoW == 0 || videoH == 0) return;
        float fit = Math.min((float) vw / videoW, (float) vh / videoH);
        float fill = Math.max((float) vw / videoW, (float) vh / videoH);
        float scale = app.settings().fill() ? fill : fit;
        Matrix m = new Matrix();
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f);
        videoView.setTransform(m);
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    private void releaseFadingPlayer() {
        if (fadingPlayer == null) return;
        try {
            fadingPlayer.release();
        } catch (Exception ignored) {
        }
        fadingPlayer = null;
    }

    // ---------------------------------------------------------------- 解码

    /** 按屏幕尺寸采样解码，并根据 EXIF 方向旋转，避免大图撑爆电视内存。 */
    private static Bitmap decode(File file, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;

        int rotation = exifRotation(file);
        int w = o.outWidth;
        int h = o.outHeight;
        if (rotation == 90 || rotation == 270) {
            int t = w;
            w = h;
            h = t;
        }
        int sample = 1;
        while (w / (sample * 2) >= reqW || h / (sample * 2) >= reqH) sample *= 2;

        Bitmap bmp = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try {
                bmp = BitmapFactory.decodeFile(file.getPath(), d);
                break;
            } catch (OutOfMemoryError e) {
                // 内存不足时降低分辨率重试
                sample *= 2;
            }
        }
        if (bmp == null || rotation == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(rotation);
        try {
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (OutOfMemoryError e) {
            return bmp;
        }
    }

    private static int exifRotation(File file) {
        String name = file.getName();
        if (!name.endsWith(".jpg") && !name.endsWith(".webp") && !name.endsWith(".heic")
                && !name.endsWith(".heif")) {
            return 0;
        }
        try {
            ExifInterface exif = new ExifInterface(file.getPath());
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
