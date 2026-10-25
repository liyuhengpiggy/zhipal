"""Real-model HTTP lifecycle smoke tests (no mocked predictions)."""

import unittest

from fastapi.testclient import TestClient

from app import app


class RealModelAPITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)
        cls.client.__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.client.__exit__(None, None, None)

    def test_health(self):
        self.assertEqual(self.client.get("/health").json()["status"], "ready")
        health = self.client.get("/health").json()
        self.assertEqual(health["policy_version"], "hybrid-v2.3")
        self.assertEqual(health["vision_tasks"], ["read_text", "recognize_object", "recognize_environment"])

    def test_valid_output(self):
        response = self.client.post("/route", json={"text": "读出摄像头前这张名片上的公司名字"})
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn(data["route"], ("vision", "phone_action", "clarify"))
        self.assertEqual(data["needs_clarification"], data["route"] == "clarify")
        self.assertEqual(set(data["scores"]), {"vision", "phone_action", "clarify"})
        self.assertEqual(data["vision_task"], "read_text")

    def test_invalid_inputs(self):
        for payload in ({}, {"text": ""}, {"text": "  "}, {"text": "字" * 201}, {"text": None}):
            self.assertEqual(self.client.post("/route", json=payload).status_code, 422)


if __name__ == "__main__":
    unittest.main()
