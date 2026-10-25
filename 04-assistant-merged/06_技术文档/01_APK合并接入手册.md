# 智能助手 APK 合并接入手册

版本：助手算法 1.3，模块化 SDK 1.3.0；文档日期：2026-10-25。

本文面向最终主 APK 开发者。目标是把助手能力接入同一个 APK，不是把两个 APK 文件拼接、反编译合并或同时安装。交付包含源码模块、AAR/JAR、带依赖元数据的 Maven 仓库、薄宿主示例和不同包名的二进制接入示例。最终主工程尚未提供，不能把本交付构建通过等同于最终主 APK 已通过真机验收。

## 1. 应当使用哪一套文件

| 文件/模块 | 用途 | 合并时的选择 |
|---|---|---|
| 原始 b_agent_team_handoff.zip | 用户提供的 Agent/模型原始代码、旧交接文档、旧构建物和历史证据 | 原样存档；不要混入新 SDK 的类或依赖 |
| model_client / model-client AAR | ApiConfig、VlmApiClient、OpenAI 兼容 HTTP 客户端、图片编码 | 新 SDK 的模型访问层；没有 Qwen 权重 |
| android_library / assistant-core AAR | 单次预测、控件选择、页面与目标复核、原生/手势点击、截图工具 | 新 SDK 的 Android 核心 |
| integration JAR | 原有核心协议和 BAssistEngine 等 | 自动作为 core 的依赖；旧语音流程不应与新浮层流程同时启动 |
| assistant_feature / assistant-feature AAR | 权限设置页面、无障碍服务、悬浮前台服务、确认面板、诊断及公开门面 | **推荐接入入口** |
| sample_app | 很薄的宿主，配置模型并打开 SDK 设置页 | 用作真实接入示例，不要连同自己的主 APK 一起复制 Application/Launcher |
| examples/binary_host | 不同 applicationId，仅依赖已发布 Maven 二进制，并启用 release 混淆 | 用于检查二进制接入与 Manifest 合并；未内置真实 Key |
| 原 1.3 性能优化 APK | 用户已有功能基线 | 可直接体验，但不是供主 APK implementation 的库 |

源码、Maven 二进制两种接入路径只能选一种。不要既 implementation AAR，又复制相同源码；不要把原始包旧 android_library-release.aar 与本次 assistant-core 同时引入。重复类错误不能靠 packagingOptions 解决。

## 2. 已验证的构建基线

| 项目 | 本交付基线 |
|---|---|
| JDK / Gradle | JDK 17 / Gradle 8.7 |
| Android Gradle Plugin / Kotlin | AGP 8.5.2 / Kotlin 1.9.22 |
| compileSdk / targetSdk | 34 / 34 |
| 主 APK minSdk | **至少 30**，因为推荐的完整 feature 要求 API 30 |
| model_client / core 自身 minSdk | 26；这不代表完整 feature 可以在 API 26 上运行 |
| 主要依赖 | coroutines 1.7.3、OkHttp 4.12.0、Okio 3.6.0、serialization 1.6.2 |
| 模型 | SiliconFlow，Qwen/Qwen3-VL-32B-Instruct |
| 代码语言 / 本地推理库 | Kotlin；不需要 NDK、llama.cpp 或模型权重作为本 APK 的运行依赖 |

宿主若使用不同 AGP/Kotlin、targetSdk 35/36、其他网络框架或更低 minSdk，应在该组合上重新构建和真机验收。不要用 tools:overrideLibrary 强行跳过最低 SDK 检查。不能把 SDK/JDK/插件已在本机缓存理解成新电脑完全离线即可构建。

## 3. 推荐路径 A：Maven 二进制接入

将交付目录 `03_Android功能SDK/maven/` 整个复制到宿主 `third_party/bagent-maven/`。保留 .pom、.module、sources JAR 和所有内部模块，不要只拿一个 AAR。

settings.gradle.kts：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(rootDir.resolve("third_party/bagent-maven")) }
        google()
        mavenCentral()
    }
}
```

主 app/build.gradle.kts：

```kotlin
android {
    compileSdk = 34
    defaultConfig { minSdk = 30; targetSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.bagent:assistant-feature:1.3.0")
}
```

这一个依赖经元数据带入 assistant-core、model-client、integration 以及它们的运行依赖。AAR 本身不是包含全部依赖的 fat-AAR。若公司仓库屏蔽外网，应把所列第三方依赖同步至公司 Maven 仓库，并配置仓库；不要遗漏 coroutines、serialization 或 OkHttp。

## 4. 路径 B：源码模块接入

将完整工程内的 model_client、integration、android_library、assistant_feature 放到宿主 vendor/bagent/，在 settings 中加入：

```kotlin
include(":model_client", ":integration", ":android_library", ":assistant_feature")
project(":model_client").projectDir = file("vendor/bagent/model_client")
project(":integration").projectDir = file("vendor/bagent/integration")
project(":android_library").projectDir = file("vendor/bagent/android_library")
project(":assistant_feature").projectDir = file("vendor/bagent/assistant_feature")
```

app 中使用 `implementation(project(":assistant_feature"))`。宿主根工程应声明 Kotlin Android、Kotlin JVM 和 serialization 插件版本，并与其现有插件管理合并；不要直接覆盖宿主 settings/build.gradle。交付根工程给出了可编译示例。

发布坐标与项目名不同：android_library 发布为 assistant-core，assistant_feature 发布为 assistant-feature，model_client 发布为 model-client。依赖关系应按源码或 Maven 元数据处理，不能凭目录名猜 Maven artifactId。

## 5. 最小接入代码

保留宿主原有 Application，不要注册第二个 Application。在宿主 Application.onCreate 的主进程、主线程进行配置：

```kotlin
import com.bagent.assistant.AssistantFeature

override fun onCreate() {
    super.onCreate()
    val key = /* 从你们主程序现有配置获取有效 API Key */
    AssistantFeature.configure(
        this,
        AssistantFeature.defaultCloudConfig(key)
    )
}
```

这个配置保存在当前进程内存，SDK 不把 Key 写入自己的 SharedPreferences。**每次进程启动都要配置**；不要只在某次按钮点击后配置。模块化示例通过本机 model.local.properties 生成 DemoApplication 的 BuildConfig，SDK AAR 和源码模板不包含实际 Key。最终宿主应接入你们自己的配置方式。

宿主功能按钮：

```kotlin
AssistantFeature.openSetup(this) // this 为当前前台 Activity
```

用户按设置页依次完成无障碍、录屏、悬浮窗和通知设置，再点启动图标。SDK 设置页有独立 Activity Result 回调，不要求宿主截获它的 requestCode。权限齐备后启动图标会关闭设置页，返回调用方或此前应用。

不要在 Application.onCreate、后台广播或无界面的定时任务里强行弹录屏授权或启动浮层服务。推荐从用户当前可见 Activity 的操作开始。Android 12 起存在后台启动前台服务的限制。[官方说明](https://developer.android.com/develop/background-work/services/fgs/launch)

## 6. Manifest 与组件合并

推荐依赖已经携带完整 feature Manifest。通常不需要主 APK 再抄一份。合并结果应包含下列完整类名：

- com.bagent.assistant.AssistantSetupActivity：设置页，exported=false。
- com.bagent.assistant.ConfirmDialogActivity / ErrorDialogActivity：兼容回退页面，exported=false。
- com.bagent.assistant.AssistantAccessService：由系统绑定的无障碍服务，exported=true，但受 BIND_ACCESSIBILITY_SERVICE 保护。
- com.bagent.assistant.FloatingService：浮层前台服务，exported=false。

feature 不声明宿主 Application、Launcher、应用主题或应用名。确认/错误回退 Activity 的 taskAffinity 使用 `${applicationId}.bagent.dialog`，不会固定为旧示例包名。资源名使用 `bagent_accessibility_service`；不要用宿主同名资源覆盖它。

无障碍声明核心如下；不要删掉权限、intent-filter 或 metadata：

```xml
<service
    android:name="com.bagent.assistant.AssistantAccessService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true"
    android:foregroundServiceType="specialUse|mediaProjection">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="user_initiated_accessibility_assistance" />
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/bagent_accessibility_service" />
</service>
```

无障碍 XML 的关键能力是 canRetrieveWindowContent、canPerformGestures，以及 flagReportViewIds、flagRetrieveInteractiveWindows。完整 feature 使用 MediaProjection，不依赖 canTakeScreenshot；本次模块化 XML 已移除该额外能力声明。读取窗口列表是为了避免把助手浮层、键盘或 Toast 误当成当前应用。[官方无障碍服务配置](https://developer.android.com/guide/topics/ui/accessibility/service)

最终应用建议核查 merged manifest，而不是只读 app/src/main/AndroidManifest.xml。不要用 tools:node="remove"/"replace" 无意删除这些服务或前台服务类型。

## 7. 权限逐项说明

| 权限/授权 | 如何获得 | 作用与容易出错的地方 |
|---|---|---|
| INTERNET | Manifest 普通权限 | 云模型请求，不是运行时弹窗 |
| SYSTEM_ALERT_WINDOW | Manifest + 系统“显示在其他应用上层”页面 | 必须用 Settings.canDrawOverlays 查询实际状态；不能用普通 requestPermissions 替代 |
| 无障碍服务授权 | 用户在系统无障碍设置手动启用对应服务 | 开关状态不等于服务实例已连接；必须等待 accessibilityConnected |
| BIND_ACCESSIBILITY_SERVICE | 写在 service 的 android:permission 属性 | 是保护系统绑定的签名权限，不是让用户通过 requestPermissions 授予的普通权限 |
| MediaProjection 同意 | 系统 createScreenCaptureIntent 回调 | 不是可永久保存的普通权限。停止会话、进程死亡等后通常需要重新授权 |
| FOREGROUND_SERVICE | Manifest 普通权限 | 前台服务基础声明，仍必须及时显示通知 |
| FOREGROUND_SERVICE_SPECIAL_USE | Manifest，API 34 服务类型相关 | 对应浮层/辅助服务；要保留 subtype property，不能随意改成 dataSync 或 shortService |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | Manifest，API 34 服务类型相关 | 仅在用户已同意本次录屏后提升服务类型，再取得投屏对象 |
| POST_NOTIFICATIONS | Android 13+ 运行时请求 | 影响通知显示；官方说明它不是启动前台服务的必要授权，但前台服务仍必须提供通知 |

本模块不要求 CAMERA、RECORD_AUDIO、READ_MEDIA_IMAGES、存储或蓝牙权限。主程序其他功能需要的权限由其单独管理，不应因为加入助手而一并申请无关权限。

Android 11 起悬浮权限 Intent 可能进入系统总设置页而不是直接定位到当前应用，OEM 页面也会不同；返回后务必重新查询状态，不能把 startActivity 成功当成已授权。[悬浮权限官方变化](https://developer.android.com/about/versions/11/privacy/permissions)、[通知权限官方说明](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

## 8. 录屏与前台服务必须遵守的顺序

默认由 SDK 管理截图时：

1. 宿主 Application 先配置模型。
2. 用户启用无障碍服务，等待系统回调连接；服务先以 specialUse 类型运行。
3. 前台设置 Activity 发起系统录屏同意。
4. 收到 RESULT_OK 和非空 data 后，将同意交给当前服务。
5. 服务提升到包含 mediaProjection 的前台服务类型，再调用 getMediaProjection。
6. 注册 MediaProjection.Callback 后创建一次 VirtualDisplay，持续读取最新帧。
7. 停止投屏时释放 VirtualDisplay、ImageReader/持有 Image 和 MediaProjection，并把就绪状态置为失效。

Android 14 每个会话的同意 token 和 MediaProjection 实例不可重复用于创建多个 VirtualDisplay。不能把 result data 存起来，在重启后或旋转后不经同意重新建投屏。当前 SDK 遇到分辨率/方向变化会拒绝旧坐标并要求重新授权，不会静默使用错误缩放继续点击。[官方 MediaProjection 文档](https://developer.android.com/media/grow/media-projection)

targetSdk 34 时，不能在尚未同意录屏的 onServiceConnected 中直接以 mediaProjection 类型启动；这正是先前容易崩溃的位置。宿主改动服务类型时要保留这个顺序。[前台服务类型要求](https://developer.android.com/about/versions/14/changes/fgs-types-required)

## 9. 主程序已经有截图/录屏：优先共享，不要开第二套

如果主程序 A 也在录屏，应统一由宿主持有投屏会话，让 B 使用同一来源。否则新投屏会话可能终止旧会话，表现为图标正常但截图失效。以下接口在主线程、助手空闲时安装：

```kotlin
AssistantFeature.installScreenshotProvider(
    provider = { hostScreenshots.borrowLatestBitmap() },
    isReady = { hostScreenshots.isReady() }
)
```

hostScreenshots 是你们已有截图对象，这两行方法名需对应替换。provider 为可挂起函数，不能阻塞主线程；应配合取消。SDK 会复制返回 Bitmap，宿主保留原图所有权，因此不要在返回后立即并发 recycle 原图。返回空表示当前无帧，必须如实处理。图像应是默认显示屏的完整、与无障碍坐标一一对应的当前帧，不能传裁剪图、压缩缩放图、旧缓存页或带错误方向的图。

安装共享来源后，SDK 的录屏按钮只提示去主程序授权，不再申请第二次投屏；运行时就绪状态读取 isReady。安装/卸载 provider 前会释放 SDK 自己的投屏，切换必须在空闲状态完成。传 null 可恢复 SDK 默认截图方式，但之后需要重新录屏授权。

共享 provider 是进程内注册，宿主进程重启后需重新安装。宿主仍须自己维护相应 mediaProjection 前台服务与用户同意；注册回调不授予任何系统权限。

## 10. A/B 任务互斥

不要让主程序 A 与 B 同时看屏幕、弹确认、输入或点击。A 开始前等待暂停完成，结束后恢复；恢复不会重放旧建议：

```kotlin
// 在主线程调度的 CoroutineScope 中启动。统一由主程序一个调度器管理，不要嵌套取得控制权。
scope.launch {
    AssistantFeature.suspendForHostTask()
    try {
        runHostTask() // 你们的可挂起主任务；不能阻塞主线程
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            AssistantFeature.resumeAfterHostTask()
        }
    }
}
```

suspendForHostTask 会设置互斥标记，取消并等待预测/执行协程，清除待确认和面板。它不能撤销已经交给系统的点击，也不自动把录屏所有权转交给 A。需要共享投屏时按上一节统一来源。若主任务被取消，finally 中也要恢复，否则助手会持续显示“主任务正在运行”。不要把原 BAssist 语音引擎与新版 feature 同时订阅同一事件并触发动作。

## 11. 同包、同进程与自身页面

完整 feature 的 Activity、Service 和宿主配置必须在同一个默认进程；不要加 android:process=":assistant"。配置、当前建议、Bitmap、服务 instance 和 callback 都是进程内对象，没有跨进程 IPC 实现。

默认不预测宿主自身页面，防止误操作设置页。若最终软件要在自己的业务页面上使用助手，显式配置：

```kotlin
AssistantFeature.configure(this, config, allowHostApplication = true)
```

SDK 自己设置页即使允许同包也仍然跳过。主程序应在自己的业务页面上真机验证读取、截图和点击行为。不同 applicationId 的静态构建验证不能替代这个运行验证。

通知渠道使用 bagent_assistant_access_channel、bagent_assistant_floating_channel，通知 ID 预留 0x424101、0x424102；宿主不要复用。回退对话框使用宿主后缀 taskAffinity；feature 不覆盖宿主主任务。SDK 包名是 com.bagent.assistant，不等于宿主 applicationId。

## 12. 模型替换与停止

普通 OpenAI 兼容服务仅需更换 ApiConfig 中 endpoint、model、apiKey。要替换模型客户端实现，可在空闲时、主线程注册：

```kotlin
AssistantFeature.installModelClientFactory { config -> YourVlmApiClient(config) }
```

实现 VlmApiClient 的挂起方法，遵守系统提示，返回 JSON `{"target_id":某个允许的ID}`；不能返回旧版 Thought/Action 格式。SDK 仍执行目标白名单检查。传 null 恢复默认 OpenAiCompatibleClient。自定义客户端没有默认客户端的详细 HTTP 指标时，应以总耗时和自身指标为准。

完整停止浮层与本地投屏：在协程中调用 `AssistantFeature.stop(context)`。它不会自动关闭用户的系统无障碍开关，也不会停止宿主共享的录屏。共享截图场景仍由宿主释放其录屏资源。不要直接 kill 进程或重用旧的待确认编号。

## 13. 状态、诊断、签名与混淆

`AssistantFeature.status(context)` 需要主线程，分别返回配置状态、系统开关、服务连接、悬浮授权、截图就绪、图标、忙碌、主任务和共享截图状态。不要用“曾连接过”作为可用依据。开发阶段保留耗时与执行诊断，区分模型等待和执行耗时；不要记录 Key 或真实截图到公共日志。

发布库带 consumer rules 保留关键入口类名；不同包名示例启用 R8 验证。宿主仍要在自己的 release 构建测试，不应关闭所有混淆来掩盖配置问题。升级前后服务组件名若变更，系统不会自动把旧服务授权迁移给新组件。

原 1.3 APK 的服务是 com.bagent.sample.SampleAccessService；模块化示例改为 com.bagent.assistant.AssistantAccessService，因此即使 applicationId 相同，也需重新启用新无障碍服务及授权录屏。原 APK 和模块化示例使用本机 debug 签名；最终主软件必须使用自己的统一签名。不同签名不能直接覆盖安装同 applicationId 的 APK。二进制宿主验证示例的 release 也只为验证而使用 debug 签名，不能当正式签名策略。

## 14. 最终必须验收什么

按配套《03_真机验收与排错清单》逐项执行。至少要验证：首次授权、拒绝再授权、服务被系统结束、停止录屏、屏幕旋转、网络失败、A 抢占 B、取消/过期不执行、正常确认只执行一次、release 混淆、进程重启和同包业务页。

本机静态和构建检查不能授权系统权限，也不能决定华为后台管理策略。最终主工程、真实手机和账号服务状态未验证前，不应承诺零错误、百分之百成功或稳定秒级。交付已把常见接入问题落实为可复用代码、元数据、示例和明确验收步骤；最终结果仍需在合并软件上完成最后一轮验证。
