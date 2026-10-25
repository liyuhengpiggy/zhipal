"""Decision logic tests only; these do not measure model accuracy."""

import unittest

from router import decide


class DecisionTests(unittest.TestCase):
    def test_vision(self):
        self.assertEqual(decide({"vision": .94, "phone_action": .62, "clarify": .60})["route"], "vision")

    def test_phone(self):
        self.assertEqual(decide({"vision": .62, "phone_action": .94, "clarify": .60})["route"], "phone_action")

    def test_low_similarity(self):
        result = decide({"vision": .30, "phone_action": .20, "clarify": .10})
        self.assertEqual(result["route"], "clarify")
        self.assertEqual(result["reason"], "low_similarity")

    def test_ambiguous_and_tie(self):
        for phone_score in (.80, .82):
            result = decide({"vision": .82, "phone_action": phone_score, "clarify": .50})
            self.assertEqual(result["route"], "clarify")

    def test_clarification_example(self):
        self.assertEqual(decide({"vision": .70, "phone_action": .80, "clarify": .95})["route"], "clarify")

    def test_bad_scores(self):
        for score in (float("nan"), float("inf"), 2):
            with self.assertRaises(ValueError):
                decide({"vision": score, "phone_action": .80, "clarify": .60})


if __name__ == "__main__":
    unittest.main()
