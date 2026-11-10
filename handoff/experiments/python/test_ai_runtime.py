import copy
import json
import unittest
from unittest.mock import patch

from ai_runtime import (AIError, check_language, gate_action, load_json, prepare_captions,
                        validate_analysis_input, validate_analysis_output, check_token_budget)


def fixture():
    data = {"videoId": "fixture0001", "title": "테스트 영상", "durationMs": 40000, "language": "ko",
            "segments": [{"segmentId": f"s{i:04}", "startMs": (i-1)*10000,
                          "endMs": i*10000, "text": f"내용 {i}"} for i in range(1, 5)]}
    sections = []
    for number, start, end in ((1, "s0001", "s0002"), (2, "s0003", "s0004")):
        sections.append({"sectionKey": f"section-{number:02}", "title": "주제", "startSegmentId": start,
                         "endSegmentId": end, "sourceSegmentIds": [start], "summary": "요약",
                         "subtopics": ["개념"], "keywords": ["기술"], "detail": {
                             "takeaway": "결론", "explanation": "설명", "applicationTips": [],
                             "recommendedTools": [], "usageKeywords": [], "examples": [
                                 {"text": "사례", "origin": "SOURCE", "sourceSegmentIds": [start]}]}})
    output = {"schemaVersion": "1.0", "contentStatus": "ANALYZABLE", "sections": sections,
              "summary": {"oneLine": "영상 요약", "keyPoints": ["핵심"], "practicalPoints": [],
                          "mentionedTools": [], "keywords": ["기술"], "highlightSectionKeys": ["section-01"]},
              "mindmap": {"rootTitle": "테스트", "sectionKeys": [s["sectionKey"] for s in sections]}}
    return data, output


class AnalysisRuntimeTests(unittest.TestCase):
    def test_normal_input_and_timestamp_mapping(self):
        data, output = fixture()
        errors, _, mapped = validate_analysis_output(data, output)
        self.assertEqual(errors, [])
        self.assertEqual([(s["startMs"], s["endMs"]) for s in mapped], [(0, 20000), (20000, 40000)])

    def test_gaps_at_start_middle_and_end_rejected(self):
        for section, field, sid in ((0, "startSegmentId", "s0002"),
                                    (1, "startSegmentId", "s0004"), (1, "endSegmentId", "s0003")):
            data, output = fixture()
            output["sections"][section][field] = sid
            with self.subTest(field=field, sid=sid):
                self.assertTrue(validate_analysis_output(data, output)[0])

    def test_detail_cross_section_evidence_is_preserved_with_warning(self):
        data, output = fixture()
        output["sections"][0]["detail"]["examples"][0]["sourceSegmentIds"] = ["s0004"]
        errors, warnings, _ = validate_analysis_output(data, output)
        self.assertEqual(errors, [])
        self.assertTrue(warnings)

    def test_representative_source_must_be_inside_section(self):
        data, output = fixture()
        output["sections"][0]["sourceSegmentIds"] = ["s0004"]
        self.assertTrue(validate_analysis_output(data, output)[0])

    def test_mindmap_must_match_sections_in_order(self):
        for keys in (["section-01"], ["section-02", "section-01"], ["section-01", "section-01"]):
            data, output = fixture()
            output["mindmap"]["sectionKeys"] = keys
            self.assertTrue(validate_analysis_output(data, output)[0])

    def test_not_analyzable_must_be_empty(self):
        data, output = fixture()
        output.update(contentStatus="NOT_ANALYZABLE", sections=[])
        self.assertTrue(validate_analysis_output(data, output)[0])
        for key, value in output["summary"].items():
            output["summary"][key] = "" if isinstance(value, str) else []
        output["mindmap"] = {"rootTitle": "", "sectionKeys": []}
        self.assertEqual(validate_analysis_output(data, output)[0], [])

    def test_invalid_caption_times_ids_languages_and_durations(self):
        data, _ = fixture()
        variants = []
        for key, value in (("language", "ko-en"), ("isTranslated", True), ("durationMs", 3600001),
                           ("durationMs", True), ("segments", [])):
            variants.append({**data, key: value})
        duplicate = copy.deepcopy(data)
        duplicate["segments"][1]["segmentId"] = "s0001"
        variants.append(duplicate)
        overlap = copy.deepcopy(data)
        overlap["segments"][1]["startMs"] = 1
        variants.append(overlap)
        for item in variants:
            with self.subTest(item=item), self.assertRaises(AIError):
                validate_analysis_input(item)

    def test_exactly_60_minutes_is_supported(self):
        data, _ = fixture()
        data["durationMs"] = 3600000
        validate_analysis_input(data)

    def test_prepare_upload_clips_rolling_overlap_and_assigns_ids(self):
        metadata = {"videoId": "fixture0001", "title": "영상", "durationMs": 30000}
        upload = {"videoId": "fixture0001", "language": "ko-orig", "segments": [
            {"startMs": 0, "endMs": 15000, "text": "첫 내용"},
            {"startMs": 10000, "endMs": 20000, "text": "둘째 내용."},
            {"startMs": 21000, "endMs": 22000, "text": "[음악]"},
            {"startMs": 22000, "endMs": 30000, "text": "마지막"}]}
        result = prepare_captions(upload, metadata)
        self.assertEqual(result["segments"][0]["text"], "첫 내용 둘째 내용.")
        self.assertEqual(result["segments"][-1]["segmentId"], "s0002")
        self.assertEqual(result["segments"][-1]["endMs"], 30000)
        upload["videoId"] = "other"
        with self.assertRaises(AIError):
            prepare_captions(upload, metadata)

    def test_gate_policy_cannot_override_not_it(self):
        self.assertEqual(gate_action("NOT_IT", True), "REJECT")
        self.assertEqual(gate_action("UNSURE"), "CONFIRM_REQUIRED")
        self.assertEqual(gate_action("UNSURE", True), "ANALYZE")
        self.assertEqual(gate_action("IT"), "ANALYZE")
        with self.assertRaises(AIError):
            gate_action("UNSURE", "true")

    def test_prepare_clips_caption_tail_to_video_end(self):
        metadata = {"videoId": "fixture0001", "title": "영상", "durationMs": 10000}
        upload = {"videoId": "fixture0001", "language": "ko", "segments": [
            {"startMs": 0, "endMs": 10800, "text": "마지막 자막"}]}
        data = prepare_captions(upload, metadata)
        self.assertEqual(data["segments"][0]["endMs"], 10000)
        self.assertEqual(data["normalization"]["clippedVideoEndCount"], 1)
        upload["segments"][0]["startMs"] = 10000
        with self.assertRaises(AIError):
            prepare_captions(upload, metadata)

    def test_json_duplicate_and_nonfinite_numbers_rejected(self):
        for text in ('{"sourceIds":[],"sourceIds":["fake"]}', '{"x":NaN}', '{"x":Infinity}'):
            with self.subTest(text=text), self.assertRaises(AIError):
                load_json(text)

    def test_token_budget_rejects_instead_of_truncating_evidence(self):
        with self.assertRaises(AIError):
            check_token_budget([{"role": "user", "content": "내용" * 100}], {}, 1)

    def test_analysis_attempt_limit_is_total_three(self):
        from yousum_runtime import analyze
        data, output = fixture()
        output["sections"][0]["sourceSegmentIds"] = ["s9999"]
        with patch("yousum_runtime.complete", return_value=(output, {})) as call:
            result = analyze(data)
        self.assertEqual(call.call_count, 3)
        self.assertEqual(result["error"]["code"], "ANALYSIS_ATTEMPTS_EXHAUSTED")

    def test_valid_analysis_stops_after_first_attempt(self):
        from yousum_runtime import analyze
        data, output = fixture()
        with patch("yousum_runtime.complete", return_value=(output, {})) as call:
            result = analyze(data)
        self.assertEqual(call.call_count, 1)
        self.assertNotIn("error", result)

    def test_permanent_model_error_does_not_retry(self):
        from yousum_runtime import analyze
        data, _ = fixture()
        with patch("yousum_runtime.complete", side_effect=AIError("MODEL_REFUSAL")) as call:
            with self.assertRaises(AIError):
                analyze(data)
        self.assertEqual(call.call_count, 1)


if __name__ == "__main__":
    unittest.main()
