"""Create new synthetic fixtures without consulting model predictions."""

import hashlib
import itertools
import json
import random
import re
import unicodedata
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEED = 2026102502


def canonical(text):
    value = unicodedata.normalize("NFKC", text).casefold()
    return re.sub(r"[\W_]+", "", value)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate():
    folder = ROOT / "evaluation" / ("fresh_2000_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
    folder.mkdir(parents=True, exist_ok=False)
    excluded = set()
    sources = []
    # Include every earlier input/result fixture, plus route prototypes and snapshots.
    for path in sorted((ROOT / "evaluation").rglob("*.jsonl")):
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            row = json.loads(line)
            if isinstance(row.get("text"), str):
                excluded.add(canonical(row["text"]))
        sources.append({"path": str(path.relative_to(ROOT)), "sha256": digest(path)})
    for path in [ROOT / "routes.json", ROOT / "evaluation" / "baseline_v1" / "routes.json"]:
        for texts in json.loads(path.read_text(encoding="utf-8")).values():
            excluded.update(canonical(text) for text in texts)
        sources.append({"path": str(path.relative_to(ROOT)), "sha256": digest(path)})
    rng = random.Random(SEED)
    rows, used = [], set(excluded)

    def group(label, category, count, templates, **slots):
        candidates = {}
        keys = list(slots)
        for values in itertools.product(*(slots[key] for key in keys)):
            for template in templates:
                text = template.format(**dict(zip(keys, values)))
                key = canonical(text)
                if key not in used:
                    candidates.setdefault(key, text)
        if len(candidates) < count:
            raise ValueError(f"Insufficient unique candidates: {category}: {len(candidates)}")
        for text in rng.sample(sorted(candidates.values()), count):
            used.add(canonical(text))
            rows.append(dict(text=text, expected=label, category=category, synthetic=True))

    objects = ["展馆入口的参观须知", "手中的停车缴费单", "桌边的折叠餐牌", "面前的维修工单", "墙上的疏散指示牌", "手里的储物柜凭条", "眼前的纸质值班表", "柜台旁的营业公告", "手中的图书借阅条", "桌上的会议议程纸", "面前的寄存行李标签", "手里的纸质预约单", "镜头中的公交线路图", "眼前的纸质优惠券", "桌上的手写留言卡", "手里的纸质电影票", "门边的展厅导览牌", "镜头前的电器保修卡", "桌上的运动鞋吊牌", "手边的茶叶盒标签", "镜头中的设备操作说明", "手里的拼装玩具说明书", "眼前的纸质行程单", "手上的演出节目单", "面前的停车区域牌"]
    group("vision", "fresh_visual_transcription", 250,
          ["请将{obj}的字逐行转写出来", "我想听清{obj}写的内容，帮我念一遍", "只读取{obj}上印刷的文字", "请对{obj}做文字提取", "看一下{obj}，把读到的字告诉我", "能不能用摄像头认出{obj}的文本", "帮我辨认{obj}那一段小字", "请把{obj}上的手写文字读出来", "把{obj}里的文字照原样念给我", "给我转录一下{obj}，保留上面的文字顺序"], obj=objects)
    fields = [("手里的纸质电影票", "影厅和座位号"), ("眼前的纸质行程单", "集合时间"), ("手中的停车缴费单", "应付金额"), ("镜头前的电器保修卡", "购买日期"), ("桌上的运动鞋吊牌", "鞋码"), ("手边的茶叶盒标签", "净含量"), ("手里的图书借阅条", "归还日期"), ("眼前的纸质优惠券", "截止日期"), ("柜台旁的营业公告", "营业时段"), ("手里的演出节目单", "节目名称"), ("面前的维修工单", "工单编号"), ("手里的纸质预约单", "预约时间"), ("眼前的纸质值班表", "值班人员姓名"), ("面前的寄存行李标签", "柜号"), ("桌上的会议议程纸", "会议地点")]
    # Each group uses a valid object/field pair, not arbitrary cross-products.
    for obj, field in fields:
        group("vision", "fresh_visual_fields", 10,
              ["{obj}印的{field}具体是什么", "帮我从{obj}读出{field}这一项", "请用镜头识别{obj}的{field}", "我只想知道{obj}里的{field}", "{obj}的{field}看不清，帮我认一下", "{obj}写的{field}是多少，念给我", "能告诉我{obj}上标注的{field}吗", "请提取{obj}中{field}对应的文字", "照着{obj}，给我读读{field}", "找到{obj}上的{field}并读出来"], obj=[obj], field=[field])
    group("vision", "fresh_visual_spoken", 150,
          ["呃，{obj}那块字太小，给我念念呗", "劳驾呀，帮忙瞅瞅{obj}写啥了", "{obj}那几行字我瞧不清，念来听听", "那个，{obj}上的内容读一下好吗", "能帮忙认认{obj}上头的字不", "我眼前的{obj}上写啥，你给读读"], obj=objects)
    group("vision", "fresh_visual_context", 150,
          ["摄像头已经就位了，目标是{obj}，请输出识别文字", "我正拿镜头对着{obj}，请读出文字部分", "相机拍到的是{obj}，这次只提取字", "你看当前画面里的{obj}，能给我念出文本吗", "把摄像头当作读字工具，识别{obj}", "镜头里现在放着{obj}，请将内容转成文字"], obj=objects)

    apps = ["微信", "系统文件管理器", "腾讯会议", "飞书", "携程旅行", "铁路12306", "豆瓣", "小红书", "百度网盘", "腾讯视频", "酷我音乐", "大众点评", "系统指南针", "系统天气", "电子邮件", "系统下载管理", "系统便签", "系统时钟", "系统设置", "系统相机", "系统计算器", "系统相册", "支付宝", "钉钉", "百度地图"]
    group("phone_action", "fresh_phone_apps", 150,
          ["麻烦将手机里的{app}运行起来", "把手机前台切到{app}", "我要进{app}，帮我点开应用", "请在桌面找到{app}并启动", "帮忙调出手机的{app}应用", "现在进入手机上的{app}主页"], app=apps)
    group("phone_action", "fresh_phone_settings", 100,
          ["请把手机{setting}切换为开启状态", "麻烦把手机{setting}切换为关闭状态", "在手机系统设置中进入{setting}的选项页", "帮我操作手机，将{setting}打开", "帮我操作手机，将{setting}关掉"], setting=["蓝牙", "无线网络", "飞行模式", "屏幕自动旋转", "个人热点", "移动数据", "护眼模式", "深色模式", "省电模式", "勿扰模式", "屏幕亮度自动调节", "定位服务", "自动同步", "通知声音", "触摸振动", "锁屏通知", "导航手势", "悬浮球", "系统大字体", "屏幕放大功能"])
    group("phone_action", "fresh_phone_ui", 150,
          ["把手机当前页面的{button}点一下", "手机屏幕上有个{button}，请按它", "替我轻触当前应用里的{button}选项", "当前界面找到{button}后点击", "选择手机页面的{button}按钮", "请点击屏幕上写着“{button}”的那一项"], button=["确定", "取消操作", "暂不更新", "不再提醒", "稍后再说", "同意并继续", "退出登录", "返回首页", "查看详情", "选择文件", "添加附件", "放弃编辑", "恢复默认", "完成设置", "立即刷新", "关闭窗口", "打开文件夹", "继续播放", "保存草稿", "下一首", "上一页", "确认选择", "结束录音", "跳过引导", "展开全部"])
    names = ["阿哲", "阿蓉", "小夏", "小袁", "小顾", "外婆", "叔叔", "陈老师", "小唐", "阿峰"]
    group("phone_action", "fresh_phone_payload", 100,
          ["通过微信给{name}发送一条消息：{body}", "在短信应用给{name}留言：{body}", "请给{name}发微信，内容是{body}", "用QQ给{name}发消息说{body}"], name=names, body=["别等我，先吃饭", "不要忘记带钥匙", "请把纸上的字读给我听", "相机里的菜单看不清", "明天十点在车站碰面", "路牌上的内容晚点再看", "不要打开那个文件", "会议延后半小时", "请取消明天的预约", "把书上的地址告诉我"])
    group("phone_action", "fresh_phone_tools", 100,
          ["请在手机闹钟中新增{time}的起床提醒", "帮我把手机闹钟时间改成{time}", "在手机日历里添加{time}的读书提醒", "请用系统提醒设置{time}取快递", "在手机备忘录输入{time}集合"], time=["明早五点二十分", "明早六点十分", "明早七点四十五分", "明早八点二十五分", "明早九点十分", "后天上午十点", "后天上午十一点半", "本周六早上六点", "本周日早上八点", "下周二上午九点", "下周三上午十点", "下周四下午两点", "下周五下午三点半", "下周六下午四点", "下周日下午五点", "今天晚上七点", "明天晚上八点半", "后天晚上九点", "周一中午十二点", "周五中午十二点半"])
    group("phone_action", "fresh_phone_media", 100,
          ["请在{app}里暂停正在播放的声音", "将{app}当前播放内容暂停一下", "帮我把{app}切到下一首", "把{app}的播放音量调低", "在{app}里播放我的收藏列表"], app=["网易云音乐", "QQ音乐", "酷狗音乐", "酷我音乐", "喜马拉雅", "汽水音乐", "手机音乐播放器", "系统音乐应用", "千千音乐", "咪咕音乐", "波点音乐", "荔枝", "蜻蜓FM", "懒人听书", "番茄畅听", "腾讯视频", "哔哩哔哩", "优酷", "爱奇艺", "手机浏览器"])

    group("clarify", "fresh_vague", 100,
          ["劳驾处理下{thing}吧", "我想让你帮忙弄好{thing}", "{thing}接下来咋整", "你能替我解决{thing}吗", "麻烦帮我弄弄{thing}"], thing=["这桩事情", "刚才说的那件事", "眼前这一处", "那边的情况", "这里的问题", "手头这件事情", "那个情况", "刚出现的这个", "我指的这个", "上次提的那个", "面前这一块", "右边那一项", "我刚说的东西", "这个部分", "之前没弄完的事", "咱们刚聊的那个", "今天这个问题", "那件没做完的事", "当前这一项", "左边那个东西"])
    group("clarify", "fresh_cancelled", 100,
          ["请不要{action}，我暂时不需要", "先别{action}，等我想好再说", "我改变主意了，不用{action}", "暂时不要{action}，这次取消", "现在不需要{action}，请停下"], action=["打开手机相册", "启动腾讯会议", "进入系统时钟", "关闭无线网络", "开启手机热点", "调整手机亮度", "点击确认按钮", "启动手机指南针", "给阿哲发送消息", "设置明天的闹钟", "读出摄像头里的车票", "念眼前节目单的字", "提取手中停车单的文本", "识别桌上的保修卡", "读取镜头里的告示", "打开文件管理器", "播放收藏音乐", "切换手机输入法", "拨打联系人电话", "扫描面前的纸质预约单"])
    group("clarify", "fresh_cross_module", 150,
          ["读取{obj}上的文字，随后用微信发给{name}", "先打开手机备忘录，再把{obj}上的字识别进去", "把{obj}的字读出来，然后通过QQ告诉{name}", "先给{name}拨电话，再识别{obj}上的文字", "提取{obj}的文本后，在手机浏览器搜索这些文字", "识别{obj}上的内容，然后输入手机的搜索框"], obj=objects, name=names)
    group("clarify", "fresh_screen_ocr", 150,
          ["请把{screen}显示的文字读给我", "能将{screen}中的段落提取为文本吗", "{screen}上的提示文字写的什么", "帮我念念{screen}的文本内容", "我想知道{screen}那行字的内容", "请识别{screen}上全部可见文字"], screen=["手机正在显示的邮箱界面", "手机浏览器的搜索结果", "当前手机的锁屏页面", "手机天气页面", "手机文件管理页面", "手机播放器页面", "当前应用的登录弹窗", "手机相册里打开的截图", "微信的聊天记录页面", "QQ消息窗口", "手机设置界面", "当前手机应用的通知页", "手机订单详情界面", "手机备忘录界面", "当前手机屏幕", "手机日历页面", "手机导航页面", "手机状态栏", "手机桌面的小组件", "屏幕上的系统提示框", "正在使用的应用界面", "手机通讯录页面", "短信聊天窗口", "手机阅读软件页面", "手机应用里的搜索结果页"])
    group("clarify", "fresh_unrelated", 100,
          ["能给我讲讲{topic}吗", "我想听你介绍{topic}", "请解释一下{topic}，不用操作手机", "陪我聊聊{topic}好不好", "给我说说关于{topic}的常识"], topic=["四季变化", "桥梁结构", "火车发展史", "成语出处", "太阳系", "月相变化", "中国古代建筑", "节约用电", "陶瓷制作", "风筝文化", "邮票收藏", "传统灯笼", "指南针的原理", "地图比例尺", "声音传播", "光的反射", "数独规则", "国际象棋规则", "标点符号用法", "书法字体"])

    rng.shuffle(rows)
    for index, row in enumerate(rows, 1):
        row["id"] = f"fresh-{index:04d}"
    assert len(rows) == len({canonical(row["text"]) for row in rows}) == 2000
    assert Counter(row["expected"] for row in rows) == {"vision": 700, "phone_action": 700, "clarify": 600}
    assert not {canonical(row["text"]) for row in rows} & excluded
    assert all(len(row["text"]) <= 200 for row in rows)
    dataset = folder / "inputs_2000.jsonl"
    dataset.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    manifest = dict(synthetic=True, seed=SEED, created_at=datetime.now().astimezone().isoformat(), count=len(rows),
                    class_counts=dict(Counter(row["expected"] for row in rows)),
                    category_counts=dict(Counter(row["category"] for row in rows)),
                    dataset_sha256=digest(dataset), prior_normalized_overlap=0,
                    deduplication="NFKC + casefold + remove punctuation/whitespace; excludes earlier fixtures and route prototypes",
                    excluded_unique_texts=len(excluded), excluded_sources=sources,
                    frozen_classifier_hashes={name: digest(ROOT / name) for name in ("router.py", "intent_policy.py", "routes.json", "router_config.json", "app.py", "server.py")},
                    label_policy={"vision": "摄像头前实体文本读取", "phone_action": "手机应用/界面操作，消息正文不改变外层意图", "clarify": "取消、含糊、跨模块、屏幕读字、无关对话"},
                    limitations="新模板合成，期望先按任务定义生成，未使用预测定标签；本轮不据此调参。去重不代表语义独立，也不代表真实ASR或生产准确率。")
    (folder / "dataset_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ("count", "class_counts", "category_counts", "dataset_sha256", "prior_normalized_overlap", "excluded_unique_texts")}, ensure_ascii=False, indent=2))
    print(str(folder))


if __name__ == "__main__":
    generate()
