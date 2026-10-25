"""Generate a fresh, labeled two-stage routing fixture without model predictions."""
import hashlib, itertools, json, random, re, unicodedata
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEED = 2026102503
NOVEL_PREFIXES = ("语音指令：", "请按这条指令：", "我现在的要求是：", "当前要处理的是：", "麻烦执行：")

def key(text):
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", text).casefold())

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    excluded = set()
    for path in (ROOT / "evaluation").rglob("*.jsonl"):
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            try:
                row = json.loads(line)
                if isinstance(row.get("text"), str): excluded.add(key(row["text"]))
            except json.JSONDecodeError: pass
    for path in (ROOT / "routes.json", ROOT / "vision_routes.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        for items in data.values(): excluded.update(key(x) for x in items)
    rng = random.Random(SEED); used = set(excluded); rows = []

    def group(route, subtype, category, count, templates, **slots):
        candidates = {}
        names = list(slots)
        for values in itertools.product(*(slots[n] for n in names)):
            for template in templates:
                text = template.format(**dict(zip(names, values)))
                variants = (text,) + tuple(prefix + text for prefix in NOVEL_PREFIXES)
                for variant in variants:
                    if key(variant) not in used: candidates[key(variant)] = variant
        if len(candidates) < count: raise RuntimeError(f"{category}: {len(candidates)} < {count}")
        for text in rng.sample(sorted(candidates.values()), count):
            used.add(key(text)); rows.append({"text": text, "expected": route, "vision_task": subtype, "category": category, "synthetic": True})

    text_targets = ["展览入场券", "仓库货箱标签", "影院取票凭条", "设备检修单", "校园通知单", "药品外包装", "公交换乘牌", "餐厅桌面菜单", "会议签到卡", "旅店入住单", "停车缴费票", "快递收货单", "产品合格证", "登机行李条", "商场活动海报", "演出节目单", "电器说明页", "图书目录页", "施工安全告示", "窗口服务牌"]
    group("vision", "read_text", "text_reading", 170, ["请把镜头前{target}的字读出来", "摄像头对着{target}，帮我提取上面的文字", "我想知道{target}写了什么", "读取眼前{target}上的内容", "把{target}转成可以复制的文本", "念一下{target}上的那段话", "识别{target}印刷的文字", "请转录{target}里的可见文字", "将{target}上的内容朗读给我", "帮我看清{target}上面的字"], target=text_targets)
    group("vision", "read_text", "text_field", 80, ["从{target}读出{field}", "请告诉我{target}上的{field}", "识别{target}里的{field}文字", "帮我提取{target}的{field}"], target=text_targets, field=["日期", "编号", "金额", "地址", "姓名", "电话", "标题", "时间", "地点", "型号"])

    objects = ["折叠雨伞", "咖啡杯", "运动鞋", "仙人掌", "自行车", "电风扇", "背包", "钥匙串", "遥控器", "桌面台灯", "宠物猫", "盆栽植物", "手提箱", "水壶", "耳机盒", "螺丝刀", "玩具车", "安全帽", "充电器", "眼镜盒"]
    group("vision", "recognize_object", "object_identity", 150, ["镜头里的{object}是什么", "帮我识别眼前的{object}", "这件{object}属于什么物品", "请辨认画面中的{object}", "看看这是不是{object}", "告诉我面前这个东西是什么", "识别摄像头拍到的物体", "帮我认一认镜头前的{object}", "判断画面里这个{object}是什么", "看清楚眼前的{object}"], object=objects)
    group("vision", "recognize_object", "object_attribute", 50, ["判断眼前{object}的颜色", "看看镜头中的{object}是什么材质", "识别画面里的{object}类别", "帮我认出桌面上的{object}", "{object}是什么型号的设备"], object=objects)

    scenes = ["客厅", "厨房", "车站站台", "十字路口", "办公室", "商场入口", "公园小路", "医院大厅", "学校走廊", "停车场", "公交车内", "地铁站通道", "酒店前台", "餐馆门口", "小区楼道", "户外广场", "仓库通道", "展馆大厅", "机场候机区", "雨天街道"]
    group("vision", "recognize_environment", "environment_scene", 150, ["描述一下{scene}周围的环境", "看看镜头前{scene}的整体情况", "识别当前{scene}是什么场景", "告诉我{scene}附近有什么", "观察一下{scene}现场", "判断{scene}是室内还是室外", "看看{scene}周围是否有障碍物", "请说说{scene}的空间布局", "识别{scene}的现场状况"], scene=scenes)
    group("vision", "recognize_environment", "environment_context", 100, ["摄像头对着{scene}，请说明周围环境", "帮我看看{scene}附近的人和物", "描述{scene}当前画面的道路状况", "识别{scene}现场的光线和空间情况", "看看{scene}四周有什么需要注意的地方"], scene=scenes)

    apps = ["邮箱", "视频会议", "地图导航", "云盘", "外卖应用", "购物应用", "音乐播放器", "系统相册", "文件管理", "日历", "录音机", "计算器", "浏览器", "通讯录", "天气应用", "阅读软件", "翻译软件", "支付应用", "短信", "设置"]
    group("phone_action", None, "phone_open", 180, ["打开手机里的{app}", "启动{app}应用", "进入{app}主页", "把手机切换到{app}", "帮我找到并打开{app}", "请在手机上运行{app}", "调出{app}给我用", "从桌面点开{app}", "我要进入{app}操作"], app=apps)
    group("phone_action", None, "phone_control", 180, ["把手机{setting}打开", "关闭手机的{setting}", "调整手机{setting}", "进入系统设置的{setting}页面", "请操作手机的{setting}", "帮我启用{setting}", "将{setting}设为关闭", "把{setting}调到合适状态", "在设置里找到{setting}", "请帮我处理手机的{setting}"], setting=["蓝牙", "手电筒", "飞行模式", "屏幕亮度", "无线网络", "勿扰模式", "自动旋转", "移动数据", "定位服务", "个人热点", "铃声音量", "省电模式", "深色模式", "护眼模式", "屏幕锁定", "通知声音", "振动反馈", "字体大小", "导航手势", "自动同步"])
    group("phone_action", None, "phone_interaction", 160, ["点击手机页面的{button}", "在当前界面按下{button}", "把屏幕滑到{button}", "请找到{button}并点开", "轻触页面上的{button}", "选择界面里的{button}", "按一下标有{button}的选项", "把{button}按钮按下", "在输入框填入{value}", "请在输入框写上{value}", "把{value}输入当前文本框", "给{person}发消息说{message}", "通过微信告诉{person}{message}", "发短信给{person}，内容是{message}", "在聊天软件给{person}留言：{message}", "联系{person}说{message}", "给{person}拨打电话", "帮我电话联系{person}", "现在呼叫{person}"], button=["确定", "下一步", "保存", "返回", "搜索", "提交", "刷新", "分享", "取消", "继续", "登录", "完成", "退出"], value=["北京", "下午三点", "会议地址", "新的标题", "测试内容"], person=["阿楠", "小许", "陈老师", "外婆", "小唐"], message=["我晚点到", "请查收文件", "明天再联系", "不要着急", "会议改期了"])
    group("phone_action", None, "phone_schedule", 180, ["设置{time}的手机闹钟", "在日历添加{time}提醒", "创建{time}的会议事项", "设一个{time}的倒计时", "在备忘录记下{time}取快递", "请在手机中安排{time}的提醒", "把{time}加入日历", "手机定时在{time}提醒我", "新建一个{time}的待办", "为{time}设置叫醒时间", "在系统提醒里填写{time}", "安排手机在{time}通知我", "请新建{time}的日程", "帮我记住{time}这件事", "设置一个{time}的提醒事项", "手机到了{time}叫我", "在日历里登记{time}"], time=["明早六点", "明早七点半", "明天上午九点", "明天下午两点", "周六中午", "下周一上午十点", "下周三下午四点", "今晚八点", "后天早上八点", "周日晚上九点", "下周五中午十二点"])

    vague = ["这个东西", "刚才那件事", "眼前这一项", "那边的情况", "手头的问题", "我说的那个", "当前这个", "之前的事情", "这里的内容", "那个部分", "右边的东西", "刚出现的问题", "这件事", "面前这个", "上次提到的那个", "现在的情况", "没处理完的事", "这块内容", "那一项", "我指的这个"]
    group("clarify", None, "vague", 120, ["帮我处理一下{thing}", "你看看{thing}怎么办", "请帮我弄弄{thing}", "{thing}应该怎么解决", "能替我处理{thing}吗", "我想解决{thing}", "麻烦看看{thing}"], thing=vague)
    group("clarify", None, "negative", 100, ["不要{action}", "先别{action}", "我不需要你{action}", "暂时不用{action}", "取消{action}", "请停止{action}", "这次不必{action}", "别替我{action}", "不用执行{action}", "先不要帮我{action}"], action=["打开相册", "启动地图", "关闭蓝牙", "设置闹钟", "点击确认", "读出纸上的字", "识别眼前的物体", "看看周围环境", "发微信消息", "拨打电话", "打开摄像头", "读取手机屏幕"])
    screens = ["手机聊天窗口", "手机浏览器页面", "系统通知栏", "手机搜索结果", "文件列表页面", "支付页面", "相册截图", "设置界面", "短信窗口", "视频播放器", "手机桌面", "日历页面", "登录弹窗", "订单详情", "阅读软件页面", "手机屏幕", "地图页面", "通讯录页面", "邮件界面", "应用通知页"]
    group("clarify", None, "screen_reading", 140, ["读一下{screen}里的文字", "识别{screen}显示的内容", "把{screen}的字念出来", "告诉我{screen}那段话写了什么", "提取{screen}上的文本", "手机{screen}上的提示是什么意思", "把{screen}内容转成文字", "念出{screen}中可见的字"], screen=screens)
    group("clarify", None, "mixed", 140, ["读取{target}后发微信给{person}", "先识别{object}再输入手机搜索框", "把{target}文字读出后保存到备忘录", "先看看{scene}再给{person}打电话", "识别{object}并把结果发给{person}"], target=text_targets, object=objects, scene=scenes, person=["阿楠", "小许", "陈老师", "外婆", "小唐"])
    group("clarify", None, "unrelated", 100, ["给我解释{topic}", "我想聊聊{topic}", "介绍一下{topic}", "请讲讲{topic}的常识", "陪我讨论{topic}", "能不能说说{topic}"], topic=["桥梁结构", "四季变化", "历史故事", "电影情节", "学习方法", "书法风格", "天气原理", "咖啡制作", "音乐理论", "旅行规划", "数学知识", "建筑设计", "围棋规则", "摄影技巧", "节日习俗", "声音传播", "地图原理", "植物生长", "交通发展", "传统工艺"])

    rng.shuffle(rows)
    for i, row in enumerate(rows, 1): row["id"] = f"vision-subtype-{i:04d}"
    assert len(rows) == len({key(r["text"]) for r in rows}) == 2000
    assert Counter(r["expected"] for r in rows) == {"vision": 700, "phone_action": 700, "clarify": 600}
    assert Counter(r["vision_task"] for r in rows if r["expected"] == "vision") == {"read_text": 250, "recognize_object": 200, "recognize_environment": 250}
    assert not {key(r["text"]) for r in rows} & excluded
    folder = ROOT / "evaluation" / ("vision_subtype_2000_" + datetime.now().strftime("%Y%m%d_%H%M%S")); folder.mkdir()
    data = folder / "inputs_2000.jsonl"; data.write_text("".join(json.dumps(r, ensure_ascii=False)+"\n" for r in rows), encoding="utf-8")
    manifest = {"synthetic": True, "seed": SEED, "count": 2000, "dataset_sha256": sha(data), "class_counts": dict(Counter(r["expected"] for r in rows)), "vision_task_counts": dict(Counter(r["vision_task"] for r in rows if r["expected"] == "vision")), "prior_normalized_overlap": 0, "excluded_unique_texts": len(excluded), "label_policy": {"read_text": "实体文字读取", "recognize_object": "实体物体识别", "recognize_environment": "周围场景/环境识别"}, "limitations": "新模板合成数据，期望标签按任务定义预先生成，未使用预测结果；不能代表真实语音或生产准确率。视觉三类样本数量按可生成的去重组合分配。"}
    (folder / "dataset_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2)); print(folder)

if __name__ == "__main__": main()
