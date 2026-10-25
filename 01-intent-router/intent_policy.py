"""Task structure rules; model similarities remain the fallback for paraphrases.

These rules classify commands; they neither authorize nor execute phone actions.
Only text before a message/search/input payload participates in command rules.
"""

import re
import unicodedata

POLICY_VERSION = "hybrid-v2.3"
VISION_TASKS = ("read_text", "recognize_object", "recognize_environment")


def normalize(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text)).lower()


PAYLOAD = re.compile(
    r"发(?:送)?(?:一条)?(?:消息|短信|微信)(?:给[^,，。:：]{1,12})?(?:[,，]?说|内容[是为]?|[:：])?"
    r"|留言[:：]?|回复[^:：,，]{0,12}[:：]|(?:搜索(?!框|结果)|输入(?!框)|填入|写下|记下|搜一下|查一下)"
)
NEGATION = re.compile(
    r"(?:^|[,，。;；])(?:请|麻烦|帮我|你|我|现在|这次|暂时|先|千万|还是|先暂时)*"
    r"(?:不要|别|不用|不必|不需要|不想让你|无需|停止|取消)(?!紧|忘了|忘记)"
    r"|我不需要你|先别|别替我|暂时不用|这次不必"
)
EDUCATIONAL = re.compile(
    r"^(?:请|麻烦|能不能|能|帮我|给我|你能|我想|我想要)*"
    r"(?:解释|介绍|讲个|讲一|教我|学一下|学学|聊|推荐|翻译|写.*(?:文案|作文|故事))"
    r"|(?:手机|蓝牙|无线网络|wifi).*(?:怎么|如何|怎样)"
)
READ = re.compile(r"读|念|识别|认(?:一认|下|一下|认)|提取|扫描|文字|文本|写(?:了|的|着|啥|什么)|看不清|内容|落款")
SCREEN = re.compile(
    r"手机屏幕|当前.*屏幕|手机.*(?:页面|网页|界面|截图|文件列表|评论区|搜索结果|播放器)|"
    r"(?:视频播放器|地图页面|应用通知页|通知栏|聊天(?:窗口|记录)|短信页面|手机相册截图|手机桌面)|"
    r"(?:当前|这个|打开的)?(?:网页|页面|界面|窗口|弹窗|文件列表|评论区|搜索结果|播放器)"
)
SCREEN_READ = re.compile(r"读|念|识别|提取|翻译|意思|转.*(?:文本|文字)|告诉我.*(?:写|内容|意思)")
PHYSICAL = re.compile(
    r"纸|菜单|餐单|路牌|招牌|包装|标签|书|收据|小票|发票|车票|名片|公告|海报|快递|说明书|表格|合同|信封|贺卡|课程表|传单|字条|门牌|信件|卡片|票据|铭牌|价签|告示|站牌|便条|清单|公示栏|邀请卡|回执|明信片|牌匾|登机牌|桌牌|邀请函|通知|册子|凭证|封信|外盒|瓶身"
)
CAMERA = re.compile(r"摄像头|镜头|眼前|面前|手中|手里|手上|拿着|拍到|拍摄|画面|举着")
TEXT_FIELD = re.compile(r"字|名称|姓名|公司|厂家|制造商|生产商|净含量|配料|日期|电话|地址|金额|总价|地点|时间|几路|哪一站|车次|哪趟车|登机口|税号|型号|落款|店名|标题|价")
PHONE_TARGET = re.compile(
    r"手机|手机桌面|屏幕|界面|窗口|页面|主页|邮箱|按钮|按键|输入框|搜索框|应用|软件|app|闹钟|闹铃|提醒|计时|倒计时|备忘录|短信|消息|微信|qq|支付宝|钉钉|淘宝|京东|美团|饿了么|抖音|哔哩哔哩|高德|百度地图|地图|浏览器|日历|计算器|通讯录|相册|录音机|时钟|应用商店|设置|蓝牙|无线网络|wifi|网络|音量|声音|铃声|亮度|飞行模式|勿扰|自动旋转|护眼|深色模式|移动数据|手电筒|省电模式|位置服务|个人热点|音乐|网易云|酷狗|喜马拉雅|有道|图标|联系人|电话|拨号"
)
PHONE_VERB = re.compile(
    r"打开|启动|运行|进入|进一下|切换|调出|关掉|开启|关闭|关了|开关|停用|启用|设置|设个|设一个|调整|调大|调小|调低|调高|调到|改为|改成|点击|点开|点一下|按一下|按屏幕|按键|轻点|长按|双击|滑|划|翻|返回|退回|回到|复制|粘贴|全选|搜索|搜一下|查一下|导航|拨|打.*电话|播放|暂停|截.*图|截屏|录屏|录音|记下|写下|添加|选中|发送|发消息|发短信|发微信|留言|回复|转发|填入|填到|输入|退出|保存|存到|创建|开始"
)
CAMERA_LAUNCH = re.compile(r"(?:打开|开启|启动|进入|调出|点开|关闭|关掉).*(?:相机|摄像头|照相机)|(?:相机|摄像头|照相机).*(?:打开|关闭)")
VISION_TEXT_TASK = re.compile(r"读|念|识别.*(?:文字|文本|字|内容)|提取.*(?:文字|文本|字)|扫描.*(?:文字|文本)|转成.*(?:文字|文本)|写了什么|写的什么|上面.*(?:字|内容)")
VISION_OBJECT_TASK = re.compile(r"识别|认出|认一下|认一认|辨认|判断|分辨|是不是|这是什么|叫什么|是哪种|什么物体|什么东西")
VISION_ENV_TASK = re.compile(r"周围|四周|附近|环境|场景|现场|室内|室外|房间|厨房|客厅|大厅|走廊|道路|路况|光线|方向|在哪里|有什么|整体情况|空间布局|全景")
OBJECT_CONTEXT = re.compile(r"摄像头|镜头|眼前|面前|画面|拍到|拍摄|这个(?:东西|物体)|这件|手里|手上|拿着")
PHONE_APP_CONTEXT = re.compile(r"(?:手机|应用|软件|app|微信|地图|天气|浏览器|相册|设置|播放器|页面|界面|窗口)")


def command_scope(text):
    normalized = normalize(text)
    payload = PAYLOAD.search(normalized)
    if payload:
        # Keep the command verb, but exclude the content being sent/searched/typed.
        return normalized[:payload.end()]
    return normalized


def structural_route(text):
    """Return (route, reason) only for a recognized command structure."""
    scope = command_scope(text)
    # Negation inside a button name/search query/message body is not a prohibition.
    if NEGATION.search(scope):
        return "clarify", "negated_or_cancelled_command"
    if EDUCATIONAL.search(scope):
        return "clarify", "outside_supported_tasks"
    reading = bool(READ.search(scope))
    if SCREEN_READ.search(scope) and SCREEN.search(scope):
        return "clarify", "screen_reading_not_supported"
    physical = bool(PHYSICAL.search(scope))
    camera = bool(CAMERA.search(scope))
    # Explicit object/environment recognition is also a camera task even when
    # the speaker omits the word "camera".
    subtype = structural_vision_task(scope) if "structural_vision_task" in globals() else None
    vision = ((physical or camera) and (reading or bool(TEXT_FIELD.search(scope)))) or bool(subtype)
    phone = bool(PHONE_TARGET.search(scope) and PHONE_VERB.search(scope))
    phone = phone or bool(re.search(r"输入|填入|双击|长按|截屏|拨号|(?:微信|qq|短信|钉钉).*(?:告诉|发给)", scope))
    # Launching a camera to read text belongs to the vision module.
    camera_launch = bool(CAMERA_LAUNCH.search(scope))
    if vision and camera_launch and not PHONE_TARGET.search(re.sub(r"(?:手机)?(?:照相机|相机|摄像头)(?:应用)?", "", scope)):
        phone = False
    if vision and phone:
        return "clarify", "mixed_module_request"
    if vision:
        return "vision", "explicit_visual_text_task"
    if phone or camera_launch:
        return "phone_action", "explicit_phone_task"
    if re.search(r"(?:点|时|分钟|小时).*(?:叫我|提醒我|闹钟)|(?:叫我|提醒我).*(?:点|时)", scope):
        return "phone_action", "explicit_reminder_task"
    if re.fullmatch(r"(?:请|帮我|麻烦|给我|你)?(?:打开|关闭|处理|弄|看看|看一下|操作)(?:一下)?(?:它|这个|那个|这里|那里|这件事|那个东西)[吧啊呀呗]*", scope):
        return "clarify", "missing_task_target"
    return None


def structural_vision_task(text):
    """Return a visual task only when the requested subtype is explicit."""
    scope = command_scope(text)
    # Phone UI/app requests must not be promoted to camera environment tasks
    # merely because they contain words such as "天气" or "页面".
    phone_context = bool(PHONE_APP_CONTEXT.search(scope))
    visual_context = bool(CAMERA.search(scope) or PHYSICAL.search(scope) or OBJECT_CONTEXT.search(scope))
    if VISION_TEXT_TASK.search(scope):
        return "read_text", "explicit_text_reading"
    if PHYSICAL.search(scope) and TEXT_FIELD.search(scope) and not phone_context:
        return "read_text", "explicit_text_field_reading"
    if VISION_ENV_TASK.search(scope) and (visual_context or not phone_context):
        return "recognize_environment", "explicit_environment_recognition"
    if VISION_OBJECT_TASK.search(scope) and (visual_context or bool(re.search(r"属于什么物品|什么东西|哪种物品", scope))):
        return "recognize_object", "explicit_object_recognition"
    if OBJECT_CONTEXT.search(scope) and bool(re.search(r"看清|辨认|认出|识别|判断|分辨|是什么|哪种|什么物品", scope)):
        return "recognize_object", "explicit_object_recognition"
    return None
