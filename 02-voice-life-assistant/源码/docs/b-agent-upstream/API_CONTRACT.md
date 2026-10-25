# API 契约

公开类：com.bagent.android.BAssist。业务状态机：com.bagent.integration.BAssistEngine。调用与回调均在 Android Main，状态内存保存。

| 入口 | 作用 |
|---|---|
| startObserving() | 接受事件/主动 B 请求；不自动执行动作 |
| stopObserving() | 停止监听，取消任务/网络，清空规则缓存与建议 |
| onAccessibilityEvent(event) | 主服务转发点击/窗口/内容事件；没有目标时不做模型猜测 |
| onBHelpRequested(taskId) | 新建目标询问，替换旧 B；taskId 空白/A运行/未监听时忽略 |
| onBSpeechResult(taskId,promptId,sourceButton,text) | 精确绑定当前问题；sourceButton 必须等于 B |
| onATaskStarted() | 标记 A 活跃，立即取消 B，旧 prompt 永久失效 |
| onATaskFinished() | 解除 A 屏蔽；不恢复旧目标/建议/动作 |
| cancelAssist() | 废弃任务工作及问题，停止本轮；观察开关仍保留 |
| close() | 生命周期终止，停观察并取消作用域；不要再复用该实例 |
| currentPrompt | 当前有效提示，或 null；持有引用不是执行权限 |

## 提示与转写

AssistPrompt：taskId、promptId（每次新建 UUID）、kind=GOAL/ACTION、text、expiresAtMs。expiresAtMs 是 SystemClock.elapsedRealtime 单调毫秒，不能当 Unix 时间。默认提示有效45秒；后台模型分析不是已确认动作，不在此超时里自动放行。超时不会点击。

- GOAL 先问需要什么帮助，不把“疑似困难”当目标。“对”不能确认未知目标。
- “我想/我要/帮我/请/打开/返回/找到/查看/点击/输入/怎么/如何”开头且3–500字符视为当前 B 目标，重新读屏分析。原实现的最小文本路由有意保守；其他表达不执行，可由主 APK 提示用户重说。
- ACTION 只有规范化后的“对”确认；先消费编号再等待截图，重复转写不能排队二次动作。
- “不用了/取消/停止/不用/不需要/不要/不”取消；“不是这个”废弃旧建议，询问具体目标，**不会执行旧建议**。
- 不理解、旧 task、旧 prompt、非 B 来源、超时输入均不执行；未知语音保留当前有效问题直到超时。
- A 返回后如需 B，发新的 onBHelpRequested 和 task_id。

## 回调

AssistListener.onPrompt(prompt)：主 APK 显示/朗读 text，并绑定 taskId/promptId。GOAL 不是动作许可；ACTION 必须等待 B 确认。

AssistListener.onEvent(AssistEvent(type,taskId,promptId,detail))：

| type | 含义 |
|---|---|
| observing | 观察开始/解除A暂停，不表示旧任务继续 |
| difficulty_suspected | 规则疑似困难，detail给出规则原因，随后产生GOAL |
| speech_handled | 已处理拒绝或“不是这个” |
| speech_ignored | 编号/来源/超时/未知话语/无目标确认被拒绝 |
| analysis_failed | 截图/模型/单动作解析/唯一目标验证失败，后随stopped |
| action_dispatched | Android 接收一次手势请求的调用已开始，不等于业务成功 |
| execution_result | 原Reflector的A/B/C结果或 not_dispatched:/dispatched_unverified:错误 |
| paused | A活跃，B不得启动新动作 |
| stopped | 本轮结束；清除主APK录音/UI旧问题；single_step_complete不等于用户总目标完成 |

Reflector A=本次动作成功或部分成功，B=到了错误页面，C=没有期望变化（按原项目定义）。上游解析器缺标题时默认C；主 APK 不得把 stopped 或 gesture 成功误报成模型验证成功。测试会额外核对真实页面/点击计数。

## 单步与资源

候选绑定窗口ID、包名、控件签名、32×32画面指纹和唯一目标。确认时重截图对比，最后同一Main轮次重读树，随后调用移植的 performTap 一次。反思只观察/模型请求，不规划或点击下一步。

SingleStepPort 是测试/Android适配接口，不是让调用方实现新动作引擎的要求。dispatchOnce 非挂起，必须同步开始恰好一次动作并返回Deferred；release 必须幂等。Android实现通过token移除/回收Bitmap；建议失效、超时、分析取消、确认前取消和执行后均释放。

