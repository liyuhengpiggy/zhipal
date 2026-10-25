# 上游来源与修改

主仓库：https://github.com/GiggleWang/MobileAgent-Android
Commit：31e3662d4bec9d8101997a2bd6f63a2c6d04e153
许可证：MIT，Copyright (c) 2026 Yixiao Wang；原文保存在 THIRD_PARTY_LICENSES/MobileAgent-Android-MIT.txt。

## 已实际检查并移植的源码

共16个文件整体移植，另1个文件从原无障碍服务抽取动作方法；当前12个文件字节一致，其他仅按本任务调整。upstream-files.json 为逐文件原路径、原SHA256、本地SHA256及一致性；upstream_patches/module-port.patch 为整体移植文件差异。这个数量不代表整个新模块的代码复用百分比。

| 原文件（app/src/main/java/com/mobileagent/app/下） | 复用与修改 |
|---|---|
| agent/BaseAgent.kt、AgentAction.kt、InfoPool.kt、PromptConstants.kt | 原接口、动作类型、状态数据、提示格式 |
| agent/Manager.kt | 原规划/解析；去掉上游强制Home和识别自身App的假设 |
| agent/Executor.kt | 原下一步提示和描述解析；去掉自身App假设；B桥附加一次Click及真实坐标约束 |
| agent/ActionReflector.kt | 原前后截图提示/ABC结果解析，不改为自写验证器 |
| api/ApiConfig.kt、VlmApiClient.kt、ImageEncoder.kt | 原配置、图片API接口和缩放JPEG/Base64编码 |
| api/OpenAiCompatibleClient.kt | 原OpenAI消息/图片请求和回复解析；阻塞请求改可取消enqueue、关闭Response、传播取消，重试10次减为2次 |
| controller/DeviceController.kt | 原控制接口 |
| controller/UiElementDetector.kt | 原树遍历、位置和标注；注入主服务并补viewId/enabled/password字段 |
| controller/ScreenCaptureManager.kt | 原MediaProjection截图保留可选；B默认注入主APK已有截图，不重复申请 |
| util/JsonActionParser.kt、CoordinateConverter.kt | 原动作解析、坐标工具；B不进行不可信坐标兜底 |
| service/AgentAccessibilityService.kt → controller/AccessibilityActions.kt | 提取原tap/swipe/type/system动作，注入服务；补dispatchGesture拒绝回调；Enter不再回退Back |

原 AgentLoop 的自动连续调度不搬入运行路径，由 BAssistEngine 的“建议→等确认→重读→原手势→原Reflector→停止”替代。任务取消从上游循环协程取消机制收窄为每轮Job取消、代际校验和可取消HTTP；新增绑定与隔离都是 B 缺失业务，不重写 Agent 控制器。

## 实际新增代码

integration：Contracts、DifficultyDetector、BAssistEngine（近期缓存/规则、编号确认、单步状态、A抢占、超时与生命周期）。
android_library/com/bagent/android：BAssist、AndroidAssistPort（主服务/截图/API桥、当前页面/单目标校验）。
sample_app：可编译现有服务接入示例、手动演示控件、自建真实Android联系人测试页面；不是正式独立产品。
测试：JUnit与Android instrumentation、证据采集/构建与打包脚本；真实模型测试调用原OpenAiCompatibleClient，不替换固定JSON。

## 原工程构建

先执行原工程 assembleDebug，遇到 release keystore空路径、当前上游native llama.cpp需要NDK30/CMake及子模块。做两处构建限定修复（cloud-only-build.patch）：未配置签名路径使用非空占位；只有不带-PcloudOnly时才启用外层CMake native编译。随后 :app:assembleDebug -PcloudOnly=true 成功。没有宣称未经修改的完整JNI/端侧模型构建通过。仍选第一主仓库，未整合Juyu或另一个完整Agent。

## 测试用公共资源

- Qwen官方GGUF：https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF；镜像hf-mirror仅用于下载同仓库文件。模型/投影文件及SHA256、上游revision见tests/evidence/model-provenance.json。
- 原项目ModelManifest.kt已列出该Qwen3VL-2B模型；不额外训练，不部署UI-TARS/OmniParser。
- llama.cpp：https://github.com/ggml-org/llama.cpp，测试运行时b11840 Windows Vulkan发行包；仅本机loopback。不是移植进Android的另一套Agent，不作为生产后端交付。
- android_world：https://github.com/google-research/android_world，参考checkout f8e78c0ab0bb8b5daa681afd0296ed0193a98f1f；只调查测试环境，没有搬入其代码/数据，也没有声称其基准通过。本次真实证据来自自建Android View页面，无需收集私人聊天/通讯录数据库。

大型模型/工具链留在E盘toolchain，不放入交付zip；下载地址与启动参数可复现。只有主仓库MIT代码进入本库，第三方依赖沿用各自许可证。
