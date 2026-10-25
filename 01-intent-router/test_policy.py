"""Behavioral regressions for task boundaries and payload/negation scope."""

import unittest

from router import route_from_scores
from intent_policy import structural_vision_task


class TaskPolicyTests(unittest.TestCase):
    def predict(self, text):
        # Intentionally misleading model scores test structural precedence.
        return route_from_scores(text, {"vision": .30, "phone_action": .20, "clarify": .95})["route"]

    def test_explicit_requests_do_not_require_high_semantic_scores(self):
        cases = {
            "镜头里的菜单都写了什么": "vision",
            "请读出手头小票的总金额": "vision",
            "摄像头对准通知，请念上面的内容": "vision",
            "把手机蓝牙关闭": "phone_action",
            "开启系统照相机": "phone_action",
            "打开摄像头识别眼前纸上的文字": "vision",
            "在备忘录里记下集合地点": "phone_action",
            "给联系人拨号": "phone_action",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(self.predict(text), expected)

    def test_rejects_negated_commands(self):
        for text in ("千万别打开微信", "先不要读纸上的字", "我不需要你点击确认", "停止打开手机应用"):
            with self.subTest(text=text):
                self.assertEqual(self.predict(text), "clarify")

    def test_negation_and_vision_inside_payload_do_not_change_phone_route(self):
        for text in ("给小何发微信说不要等我", "短信回复小何：先别出发", "在搜索框输入不要打开相机", "在微信发消息说帮我提取纸上的文字", "用手机浏览器搜索菜单上的文字怎么翻译"):
            with self.subTest(text=text):
                self.assertEqual(self.predict(text), "phone_action")

    def test_mixed_commands_are_not_dispatched(self):
        for text in ("读出纸上的号码然后拨号", "识别海报上的文字并输入备忘录", "先念出纸上数字然后输入计算器", "先读取菜单价格再用微信告诉姐姐", "先识别摄像头前书上的字，再用微信发给小何"):
            with self.subTest(text=text):
                self.assertEqual(self.predict(text), "clarify")

    def test_clicking_a_text_button_is_not_screen_ocr(self):
        self.assertEqual(self.predict("点击手机页面上写着取消的按钮"), "phone_action")
        self.assertEqual(self.predict("读一下手机屏幕上的文字"), "clarify")
        self.assertEqual(self.predict("告诉我手机搜索结果里那段话的内容"), "clarify")

    def test_screen_sources_are_clarified(self):
        for text in ("把视频播放器的字念出来", "把地图页面的字念出来", "念出应用通知页中可见的字"):
            with self.subTest(text=text):
                self.assertEqual(self.predict(text), "clarify")

    def test_visual_object_and_environment_phrasing(self):
        cases = {
            "这件电风扇属于什么物品": "recognize_object",
            "看清楚眼前的遥控器": "recognize_object",
            "先看看厨房再给小唐打电话": "clarify",
            "请在手机上运行天气应用": None,
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                if expected is None:
                    self.assertEqual(self.predict(text), "phone_action")
                else:
                    result = route_from_scores(text, {"vision": .95, "phone_action": .20, "clarify": .10})
                    self.assertEqual(result["vision_task"] if expected != "clarify" else result["route"], expected)

    def test_questions_and_missing_target(self):
        for text in ("解释一下手机蓝牙的原理", "手机怎么设置闹钟", "打开它"):
            with self.subTest(text=text):
                self.assertEqual(self.predict(text), "clarify")

    def test_vision_subtypes(self):
        cases = {
            "读出摄像头前这张纸上的字": "read_text",
            "识别镜头里的这是什么物体": "recognize_object",
            "看看周围环境有什么": "recognize_environment",
        }
        for text, expected in cases.items():
            self.assertEqual(structural_vision_task(text)[0], expected)
            result = route_from_scores(text, {"vision": .95, "phone_action": .20, "clarify": .10})
            self.assertEqual(result["vision_task"], expected)
            self.assertEqual(result["vision_task_source"], "structure")

    def test_nonvision_has_no_subtype(self):
        result = route_from_scores("打开微信", {"vision": .10, "phone_action": .95, "clarify": .20})
        self.assertIsNone(result["vision_task"])


if __name__ == "__main__":
    unittest.main()
