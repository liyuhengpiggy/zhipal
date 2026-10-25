# AI 眼镜手机端（Android APK 工程）

目标：支持安装 Android APK 的 HarmonyOS 4.2 手机；不是 HarmonyOS NEXT 原生 HAP。
需提供手机型号，在实机上验证 APK 安装、网络及中文 TTS，不能仅凭系统版本保证兼容。

本工程是新增的云端联调客户端，不替换已有网站、不改固件、不修改 OCR fields。
仅使用 Android SDK / Java 标准 API，无额外业务依赖。工程尚未完成 Android 编译和真机验收。

## 本机构建

准备 JDK 17、Gradle 8.9、Android SDK Platform 35 和 Build Tools 35.0.0。
设置 `ANDROID_HOME` 指向 SDK，在 SDK Manager 接受所需许可并安装组件。
也可用 Android Studio 打开本目录，选择 JDK 17、Gradle 8.9，完成 Sync 后执行 Build APK。
本包不含 Gradle wrapper 二进制；请使用指定的本地 Gradle，勿直接运行不存在的 gradlew。

```powershell
cd mobile-android
gradle --no-daemon :app:assembleDebug
# 或：powershell -ExecutionPolicy Bypass -File .\BUILD_APK.ps1
```

输出：`app/build/outputs/apk/debug/app-debug.apk`，采用 Android 默认调试签名。
安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`，或传到手机后由用户确认安装。
若已装同包名但不同签名的版本，不能覆盖安装；先备份所需数据，再由用户决定处理方式。

正式发行使用独立保管的签名密钥，通过 Android Studio Generate Signed APK 构建。
不要把签名密码、证书私钥、云模型 Key 或服务令牌提交到仓库。

## 可选 CI 构建

本目录作为独立仓库根目录时，附带的 `.github/workflows/android-debug.yml` 可手动触发构建。
若放在现有后端仓库子目录，需把 workflow 放到仓库根 `.github/workflows/`，并为构建步骤设置
`working-directory: mobile-android`，将 artifact 路径改为 `mobile-android/app/build/outputs/apk/debug/app-debug.apk`。
本次没有上传手机工程、触发 CI、创建云账号或改变生产部署。

## 使用

1. 眼镜继续向现有 WSS 地址传图。手机与眼镜无需在同一 Wi-Fi，但都须能访问云端。
2. 打开 App，点“开始 / 停止原图预览”；这是约 1.5 秒间隔的缓存 JPEG 轮询，不是实时视频解码。
3. 填写后端 `VISION_API_TOKEN`；OCR 令牌可以单独填写，服务端配置了则可留空。
4. 选择模式，确认照片交给云服务处理，点击识别。
5. 中文 TTS 可用时，勾选播报；系统无中文引擎时只展示文字，不自动安装任何引擎。
6. OCR 未结束可以查询上次任务。停止等待不会取消服务器已创建的任务。

令牌仅在内存中使用，不保存到文件或 APK；App 重建或退出后需要重新填写。
OCR 任务号仅本会话保留，进程退出后不支持自动恢复；超时发生在任务号返回前可能无法恢复查询。
请求读取超时为 145 秒，OCR 轮询预算 180 秒，单次请求可能超出轮询预算；停止只阻止后续请求和结果显示，
已发出的网络请求可能继续到超时，不会在服务端自动撤销，也不会自动重发付费识别。

安全边界：固定 HTTPS 后端地址，禁止明文和自动重定向；无 JS bridge、无 WebView、无蓝牙/录音/无障碍权限。
令牌输入禁用状态保存和自动填充；窗口启用 FLAG_SECURE；不记录照片或令牌日志。
这不是生产身份系统：共用静态令牌和全局画面仍不适用于多用户正式发行。

详细架构、接口、云端配置与验收见交付包 `docs/技术交付与联调说明.md`。
