import base64
import unittest

from pydantic import ValidationError

from app.schemas.observation import MAX_JPEG_BYTES, ObservationRequest
from app.services.response_validator import ResponseValidator
from fixtures import observation, payload, prediction


class ObservationTests(unittest.TestCase):
    def assert_invalid(self, value):
        with self.assertRaises(ValidationError):
            ObservationRequest.model_validate(value)

    def test_valid_round_trip(self):
        self.assertEqual(ObservationRequest.model_validate_json(observation().model_dump_json()), observation())

    def test_strict_scalar_types(self):
        for path, wrong in [("schema_version", True), ("schema_version", "1"), ("window_id", "5"),
                            ("captured_at_ms", 2.0), ("request_id", 13)]:
            with self.subTest(path=path, value=wrong):
                value = payload()
                value[path] = wrong
                self.assert_invalid(value)

    def test_mixed_ids_and_unknown_fields(self):
        value = payload()
        value["request_id"] = value["capture_id"]
        self.assert_invalid(value)
        value = payload()
        value["tool"] = "tap"
        self.assert_invalid(value)

    def test_empty_or_oversized_collections(self):
        for field, count in [("ui_nodes", 0), ("ui_nodes", 161), ("history", 21), ("candidates", 41)]:
            value = payload()
            value[field] = value[field][:1] * count
            self.assert_invalid(value)

    def test_candidate_must_match_available_node(self):
        for mutation in ("absent", "hidden", "disabled", "not_clickable", "bounds", "class", "duplicate"):
            value = payload()
            if mutation == "absent": value["candidates"][0]["node_key"] = "missing"
            elif mutation == "hidden": value["ui_nodes"][0]["visible"] = False
            elif mutation == "disabled": value["ui_nodes"][0]["enabled"] = False
            elif mutation == "not_clickable": value["ui_nodes"][0]["clickable"] = False
            elif mutation == "bounds": value["candidates"][0]["bounds"]["left"] = 1
            elif mutation == "class": value["candidates"][0]["class_name"] = "wrong"
            else: value["candidates"][0]["candidate_id"] = "none-1"
            with self.subTest(mutation=mutation): self.assert_invalid(value)

    def test_noop_must_exist_once_and_have_no_target(self):
        value = payload()
        value["candidates"].pop()
        self.assert_invalid(value)

    def test_hidden_empty_nodes_are_data_but_never_action_targets(self):
        value = payload()
        hidden = dict(value["ui_nodes"][0], node_key="hidden", visible=False,
                      bounds={"left": -16, "top": 0, "right": 0, "bottom": 0})
        value["ui_nodes"].append(hidden)
        self.assertEqual(len(ObservationRequest.model_validate(value).ui_nodes), 2)
        value["ui_nodes"][0]["bounds"] = {"left": 0, "top": 0, "right": 0, "bottom": 0}
        self.assert_invalid(value)
        value = payload()
        value["candidates"][-1]["node_key"] = "node-1"
        self.assert_invalid(value)

    def test_invalid_jpeg_and_dimensions(self):
        for mutation in ("base64", "mime", "dimension", "truncated", "too_large"):
            value = payload()
            shot = value["screenshot"]
            if mutation == "base64": shot["jpeg_base64"] = "!" * 20
            elif mutation == "mime": shot["mime_type"] = "image/png"
            elif mutation == "dimension": shot["height"] = 15
            elif mutation == "truncated": shot["jpeg_base64"] = base64.b64encode(b"\xff\xd8broken\xff\xd9").decode()
            else: shot["jpeg_base64"] = base64.b64encode(b"x" * (MAX_JPEG_BYTES + 1)).decode()
            with self.subTest(mutation=mutation): self.assert_invalid(value)

    def test_untrusted_label_length_limited(self):
        value = payload()
        value["ui_nodes"][0]["label"] = "secret" * 200
        self.assert_invalid(value)


class ResponseTests(unittest.TestCase):
    def test_valid_choice(self):
        result = ResponseValidator().validate(prediction(), observation())
        self.assertEqual(result.decision.candidate_id, "tap-1")

    def test_unknown_candidate_rejected(self):
        with self.assertRaises(ValueError):
            ResponseValidator().validate(prediction("made-up"), observation())

    def test_action_must_match_candidate(self):
        with self.assertRaises(ValueError):
            ResponseValidator().validate(prediction("tap-1", "BACK"), observation())

    def test_bad_ids_types_confidence_or_extra_fields(self):
        for mutation in ("request_id", "capture_id", "str_float", "bool_float", "nan", "unknown_action", "extra", "long_reason"):
            value = prediction()
            if mutation in {"request_id", "capture_id"}: value[mutation] = "00000000-0000-0000-0000-000000000000"
            elif mutation == "str_float": value["decision"]["confidence"] = "0.9"
            elif mutation == "bool_float": value["decision"]["confidence"] = True
            elif mutation == "nan": value["decision"]["confidence"] = float("nan")
            elif mutation == "unknown_action": value["decision"]["action"] = "INPUT_TEXT"
            elif mutation == "extra": value["decision"]["x"] = 100
            else: value["decision"]["reason"] = "x" * 301
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                ResponseValidator().validate(value, observation())

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):
            ResponseValidator().validate('{"schema_version":1,"schema_version":1}', observation())
