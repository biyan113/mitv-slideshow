# 电视相册 (TvGallery)

小米电视 / Android TV 幻灯片应用。电视端启动后内置 HTTP 服务并显示二维码，手机扫码即可上传照片和视频，电视端自动轮播。

## 功能

- 局域网内手机扫码上传图片 / 视频，无需登录、无需云端
- 超过 4K 的照片上传前自动压缩并矫正方向，省流量也省电视内存
- 多种切换动画：滑动、推入、3D 翻转、交叉淡入 + Ken Burns 缓慢推近
- 视频播放，单个视频时自动循环
- 遥控器左右键切换、菜单键呼出二维码
- 网页端可设置：切换间隔（3–60 秒）、随机顺序、铺满屏幕（裁切边缘）、视频声音、角落常驻二维码
- 有新图片上传时立即切到最新一张

## 安装

从 [Releases](../../releases) 下载 APK，通过 U 盘或 adb 侧载：

```bash
adb install TvGallery-*.apk
```

支持 Android 5.0+（minSdk 21），覆盖小米电视绝大多数机型。

## 构建

```bash
./gradlew assembleRelease
```

需要 Android SDK（compileSdk 35）。release 包直接用 debug 证书签名，方便侧载安装。

## 技术说明

- 纯 Java 实现，无 AndroidX 依赖，仅依赖 `com.google.zxing:core`（二维码）
- `HttpServer.java` 内置 HTTP 服务：上传（`POST /api/upload`）、排序、文件下发（支持 Range 请求）
- 上传的文件名以上传时间开头，天然按时间排序；网页端与电视端通过同一接口同步顺序
