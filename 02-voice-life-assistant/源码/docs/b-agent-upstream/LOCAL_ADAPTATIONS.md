# 主 APK 的本地适配

来源：用户提供的 b_agent_team_handoff.zip，提取于 b_agent_team_handoff_import/，主工程以源代码方式纳入 app/src/main/java/com/bagent 和 com/mobileagent。

- BAssist/AndroidAssistPort 增加宿主 guard，在截图前后、推理前及物理分发前检查授权、A 优先、锁屏、窗口与隐私过滤。
- 继续使用主工程 PageDwellDetector；未启用上游重复点击/页面往返的自动触发。主工程拥有 BLE、ASR、弹窗、超时及生命周期。
- 使用原始分辨率无障碍截图，保证坐标对应真实屏幕；页面像素指纹比较裁去顶部/底部系统区域。确认仍需相同窗口、控件树和内容指纹。
- 增加普通导航动作策略，阻止敏感与无标签目标；推理阶段和执行阶段都检查。
- OpenAiCompatibleClient 禁止重定向，HTTP 错误不向宿主输出服务响应正文；协程取消继续中断 OkHttp Call。
- 无障碍树遍历增加深度/数量限制及 finally 回收。
- 宿主配置和网络许可区分 debug/release。没有复制样例 APK、模型权重、Kotlin 旧标准库到应用运行时。
- 运行时使用原包附带的 coroutines 1.7.3、serialization 1.6.2、OkHttp 4.12.0、Okio 3.6.0 JAR。上游许可保留在本目录与 APK assets/licenses/b-agent 中。

上游各工具类中存在未由本应用 B 路径调用的能力，不代表主 APK 自动开放这些能力。主路径只接受一次 Click。
# 0.7.3 additions

`BModelProtocol` builds explicit non-streaming JPEG data-URL requests and rejects incomplete/refused/tool-calling responses. The Android B port requires exact ordered sections and a single unambiguous click with two integer coordinates. Reflection parsing now fails on malformed output rather than defaulting to C. `b_agent_proxy/` pins SiliconFlow and Qwen3-VL; its provider credential is server-only. User confirmation and A preemption remain enforced by the host state machine.

## 0.7.4 target selection

A live Qwen3-VL response identified the intended button but ignored the supplied center coordinates, and the probe rejected it. The B model now selects an allowed `target_id`; Android binds it to the observed control's actual center before asking for confirmation. Target IDs, duplicate/extra fields and stale pages are validated; model coordinates are rejected on the B suggestion path. The original coordinate parser remains for compatibility tests and internal canonical action objects. Provider balance exhaustion prevented live verification of the revised target prompt and two-image reflection.

