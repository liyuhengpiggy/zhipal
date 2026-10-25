# 在现有 Render 后端接入双 Agent

本地已经接入 `POST /api/v1/object-recognition` 和 `POST /v1/chat/completions`。
新接口使用独立的 Bearer Token 鉴权，未配置令牌时返回503，令牌错误返回401。
原实时照片、OCR 和其他接口不受新鉴权影响。新接口不加载本地模型、不创建数据库。

## 1. 更新 Render 绑定的仓库

先确认 Render → 当前 Web Service → Settings 中的 Repository、Branch、Root Directory。
将本项目的 `app/agent_routes.py` 添加到对应后端目录，并合并 `app/main.py` 的三处改动：

```python
from app.agent_routes import router as agent_router
```

在原来的 `app = FastAPI(...)` 后加入：

```python
app.include_router(agent_router)
```

如果项目已有 CORS 配置，给 allow_headers 添加 `Authorization`，保留原有其他配置。
本地 main.py 已完成以上修改，不要重复添加。

若线上仓库版本包含本地没有的腾讯云 OCR 路由，请只合并上述改动，保留线上其他代码，不要整文件覆盖。
依赖 httpx、FastAPI 和 Pydantic 已在当前 requirements.txt 中，无需增加模型依赖。

## 2. 设置环境变量

在 Render → Environment 添加以下变量，保留已有腾讯云密钥与其他变量：

| 名称 | 值 |
| --- | --- |
| VISION_API_TOKEN | 自行生成的随机长令牌，不要使用示例或空值 |
| VISION_QWEN_BASE_URL | https://api.siliconflow.cn/v1 |
| VISION_QWEN_API_KEY | 你自己的硅基流动密钥 |
| VISION_QWEN_MODEL | Qwen/Qwen3-VL-8B-Instruct（需确认账号有权限） |
| VISION_QWEN_TIMEOUT | 120 |

这些变量与原有 VISION_API_KEY、VISION_MODE 等不同，不要替换腾讯 OCR 或旧视觉线配置。
不要把真实密钥提交到仓库。本服务令牌用于联调或可信后端间调用；公开发布的 APP 应接用户登录鉴权，不能依赖 APK 中的固定共享令牌。

## 3. 部署

保持当前能工作的 Build Command、Start Command、PORT 配置。
本项目的启动命令为 `python -m app`；不要改为精简包的 serve_vision.py，也不要新建另一个监听端口。
推送代码后，在 Render 执行 Manual Deploy → Deploy latest commit（或等待自动部署）。

## 4. 验证

打开 `https://你的服务.onrender.com/docs`，确认两个新接口出现。
点击 Authorize，填入 VISION_API_TOKEN 的值（HTTPBearer 弹窗只填令牌本身）。
调用物体识别：

```json
{"question":"图片里是什么？","images":["data:image/jpeg;base64,真实图片Base64"]}
```

调用 MobileAgentB 时使用原来的 OpenAI 兼容 messages 格式，把截图放入 image_url，设置 stream=false。
两条接口在实际 HTTP 请求中都要求 `Authorization: Bearer <VISION_API_TOKEN>`。
物体识别读取响应 answer；MobileAgentB 读取 choices[0].message.content。
它只返回模型分析，手机点击/滑动仍需 APP 执行器。

验收：不带令牌返回401；带令牌及真实图片能返回答案；实时照片和腾讯 OCR 仍然正常。
`/health` 沿用原状态接口，不代表已验证 Qwen 密钥或余额。

免费 Render 实例休眠会增加首次访问等待；连续照片预览继续走原接口，不要把每一帧都送入收费模型。

## 本地回归

```powershell
.\.venv\Scripts\python.exe -m pytest tests -q
```
