"""Generate a deterministic, explicitly synthetic test fixture, not user records."""

import hashlib
import itertools
import json
import random
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RNG = random.Random(20261025)


def generate():
    examples = json.loads((ROOT / "routes.json").read_text(encoding="utf-8"))
    used = {text for items in examples.values() for text in items}
    rows = []

    def group(label, category, count, templates, **slots):
        keys = list(slots)
        combinations = itertools.product(*(slots[key] for key in keys))
        candidates = set()
        for values in combinations:
            for template in templates:
                text = template.format(**dict(zip(keys, values)))
                if text not in used:
                    candidates.add(text)
        if len(candidates) < count:
            raise RuntimeError(f"Not enough distinct samples for {category}: {len(candidates)}")
        for text in RNG.sample(sorted(candidates), count):
            used.add(text)
            rows.append({"text": text, "expected": label, "category": category, "synthetic": True})

    objects = ["纸质通知", "餐馆菜单", "公交站牌", "商店招牌", "商品标签", "快递面单", "停车告示", "纸质发票", "展览海报", "活动传单", "书本封面", "纸质车票", "购物小票", "门口公告", "食品包装", "纸质名片", "机器铭牌", "桌上的信封", "手里的贺卡", "楼层指示牌", "纸质合同", "打印表格", "洗衣标签", "安全提示牌", "纸质说明书", "旅行手册", "纪念明信片", "墙上的课程表", "纸质邀请函", "商品价签"]
    positions = ["镜头里这份", "我手里这份", "摄像头前这份", "面前这份", "眼前这份"]
    group("vision", "visual_read", 300,
          ["帮我读出{position}{obj}上的文字", "识别一下{position}{obj}上写了什么", "请提取{position}{obj}里的文字内容", "把{position}{obj}上印的字念给我听", "我看不清{position}{obj}上的字，替我读出来", "扫描{position}{obj}并输出它的文本"], position=positions, obj=objects)
    fields = {"商品标签": ["生产日期", "保质期限", "产品名称", "规格型号", "制造商名称"], "快递面单": ["收件地址", "收件人姓名", "快递单号", "寄件地址", "邮政编码"], "纸质名片": ["公司名称", "联系人姓名", "联系电话", "办公地址", "电子邮箱"], "购物小票": ["合计金额", "购买日期", "商品名称", "商店名称", "订单编号"], "纸质车票": ["出发车站", "到达车站", "出发时间", "座位号码", "车次编号"]}
    field_templates = ["从{position}{obj}上读出{field}", "识别{position}{obj}上印的{field}", "提取{position}{obj}中的{field}给我", "{position}{obj}上写的{field}是什么"]
    for obj, values in fields.items():
        group("vision", "visual_field", 40, field_templates, position=positions, obj=[obj], field=values)
    group("vision", "visual_camera_context", 100,
          ["摄像头已经对准{obj}了，把上面的字念一下", "我把{obj}举到镜头前了，请识别里面的文字", "现在拍到的是{obj}，帮忙提取文本", "请直接读取当前摄像头画面中{obj}的文字"], obj=objects)
    group("vision", "visual_colloquial", 100,
          ["那个{obj}我看不清，你给念念", "瞅瞅面前{obj}上头写的啥", "麻烦把眼前{obj}那几个字读给我", "我拿着的{obj}上写啥呢", "帮俺认认镜头里{obj}上的字呗"], obj=objects)

    apps = ["支付宝", "高德地图", "百度地图", "QQ", "钉钉", "淘宝", "京东", "美团", "饿了么", "抖音", "哔哩哔哩", "网易云音乐", "QQ音乐", "手机相机", "系统相册", "日历", "计算器", "文件管理器", "备忘录", "浏览器", "短信", "通讯录", "录音机", "时钟", "应用商店"]
    group("phone_action", "phone_launch", 140,
          ["帮我启动{app}", "把{app}这个应用打开", "切换到{app}的主界面", "我要用{app}，替我打开它", "在手机上进入{app}", "请打开手机里的{app}"], app=apps)
    settings = ["蓝牙", "无线网络", "飞行模式", "勿扰模式", "自动旋转", "护眼模式", "深色模式", "移动数据", "手电筒", "省电模式", "位置服务", "个人热点"]
    group("phone_action", "phone_settings", 140,
          ["帮我开启手机的{setting}", "把手机{setting}关闭", "进入系统设置的{setting}页面", "请在手机里打开{setting}设置", "把{setting}功能关掉", "将{setting}开关打开", "替我打开手机设置，找到{setting}", "我想调整{setting}，进入对应设置", "现在启用{setting}", "把当前手机的{setting}停用", "请关闭系统的{setting}", "我要设置{setting}，帮我进入那一页"], setting=settings)
    names = ["小王", "小李", "小陈", "小赵", "妈妈", "爸爸", "小林", "小刘", "小周", "小吴"]
    messages = ["我已经到家了", "晚点给你回复", "今天下午见", "记得带雨伞", "周末一起吃饭", "我马上就到", "路上注意安全", "文件已收到"]
    group("phone_action", "phone_message", 140,
          ["用微信给{name}发消息说{message}", "打开短信，给{name}发送{message}", "在QQ里给{name}留言：{message}"], name=names, message=messages)
    buttons = ["取消", "下一步", "完成", "返回", "保存", "搜索", "关闭", "继续", "允许", "刷新", "登录", "提交", "更多", "分享", "编辑", "复制", "粘贴", "全选", "退出", "设置"]
    group("phone_action", "phone_ui", 140,
          ["点一下当前手机页面的{button}按钮", "帮我按屏幕里的{button}", "在当前界面点击{button}", "请选中手机页面上写着{button}的按钮", "把手机页面滑到{button}按钮的位置", "点击手机屏幕上的{button}选项", "替我找到并点开当前页面的{button}"], button=buttons)
    hours = ["六点", "六点半", "七点半", "八点", "八点半", "九点", "九点半", "十点", "十点半", "十一点"]
    group("phone_action", "phone_tools", 140,
          ["在手机上设置{day}早上{hour}的闹钟", "添加一个{day}上午{hour}的系统提醒", "打开日历，在{day}上午{hour}添加会议提醒", "把手机闹钟设置成{day}早上{hour}", "请在备忘录里记下{day}上午{hour}开会"], day=["明天", "后天", "下周一"], hour=hours)

    vague = ["这个", "那个", "这里", "那里", "眼前这个", "刚才那个", "这边", "那边", "这个东西", "刚才的东西", "现在这个", "之前那个", "这件事", "那个东西", "这一项", "那一项", "刚才的事", "面前这个东西", "你看到的这个", "我说的那个"]
    group("clarify", "clarify_ambiguous", 120,
          ["帮忙处理一下{thing}", "{thing}怎么搞啊", "你看看{thing}吧", "帮我弄一下{thing}", "能帮忙看看{thing}吗", "{thing}应该怎么办"], thing=vague)
    group("clarify", "clarify_mixed", 120,
          ["先识别摄像头前{obj}上的字，再用微信发给{name}", "把眼前{obj}的文字提取出来，然后打开备忘录保存", "先读出镜头里{obj}上的地址，再打开地图导航过去", "识别面前{obj}里的文字并通过短信转发给{name}"], obj=objects, name=names)
    negative_actions = ["启动支付宝", "打开高德地图", "关闭蓝牙", "开启飞行模式", "发送微信消息", "设置闹钟", "点击提交按钮", "返回桌面", "打开相机应用", "识别摄像头里的菜单", "读取眼前纸上的字", "提取包装上的文字", "念出路牌上的内容", "扫描手里的名片", "识别面前的快递单", "读取当前摄像头画面", "打开手电筒", "进入系统设置", "滑动手机屏幕", "打开浏览器"]
    group("clarify", "clarify_negation", 120,
          ["不要{action}", "先别{action}", "暂时不用{action}", "我不需要你{action}", "别替我{action}", "这次不必{action}"], action=negative_actions)
    topics = ["数学题", "英语语法", "古诗含义", "历史故事", "旅行计划", "电影情节", "天气原因", "地理知识", "写作技巧", "绘画方法", "音乐风格", "咖啡口味", "家具搭配", "时间管理", "学习方法", "节日习俗", "摄影构图", "建筑风格", "围棋规则", "成语意思"]
    group("clarify", "clarify_out_of_scope", 120,
          ["给我解释一下{topic}", "我想聊聊{topic}", "教我一些{topic}的知识", "你了解{topic}吗", "我对{topic}很感兴趣", "介绍一下{topic}吧"], topic=topics)
    screen_objects = ["当前聊天窗口", "手机通知栏", "当前手机屏幕", "手机网页", "手机应用界面", "系统弹窗", "手机设置页面", "短信页面", "手机相册截图", "手机桌面", "当前软件窗口", "手机搜索结果", "手机订单页面", "手机支付页面", "手机登录页面", "手机通讯录页面", "手机日历页面", "手机文件列表", "手机评论区", "手机音乐播放器"]
    group("clarify", "clarify_screen_reading", 120,
          ["读一下{screen}里的文字", "识别{screen}上写了什么", "把{screen}里的字念出来", "提取{screen}上的所有文本", "告诉我{screen}里那段话的内容", "把{screen}里的文字转成可复制文本"], screen=screen_objects)

    RNG.shuffle(rows)
    for index, row in enumerate(rows, 1):
        row["id"] = f"synthetic-{index:04d}"
    assert len(rows) == len({row["text"] for row in rows}) == 2000
    assert Counter(row["expected"] for row in rows) == {"vision": 700, "phone_action": 700, "clarify": 600}
    data = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
    target = ROOT / "evaluation"
    target.mkdir(exist_ok=True)
    (target / "test_inputs_2000.jsonl").write_text(data, encoding="utf-8")
    manifest = {
        "synthetic": True, "seed": 20261025, "count": len(rows),
        "class_counts": dict(Counter(row["expected"] for row in rows)),
        "category_counts": dict(Counter(row["category"] for row in rows)),
        "dataset_sha256": hashlib.sha256((target / "test_inputs_2000.jsonl").read_bytes()).hexdigest(),
        "prototype_overlap_exact": 0,
        "label_policy": {"vision": "摄像头前实物的文字读取", "phone_action": "单一手机应用或界面操作", "clarify": "含糊、否定、多步混合、无关输入、当前未支持的手机屏幕读字"},
        "limitations": "固定模板组合的合成测试；不是实际用户记录；语义与示例可能相近；不能代表真实语音转写准确率。测试标签由任务定义生成，未使用模型预测。"
    }
    (target / "dataset_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    generate()
