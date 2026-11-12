"""Offline contract checks. Run with the dependencies declared in yousum_chat.py."""
import copy
import json
import unittest

import yousum_chat as chat


class ChatContractTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((chat.HERE / "chat_cases.json").read_text())["cases"][0]["input"]
        self.output = {"content": "40분에서 10분으로 줄었어요.",
                       "sourceIds": ["src-1"], "finishReason": "STOP"}

    def test_all_case_inputs_are_valid(self):
        for case in json.loads((chat.HERE / "chat_cases.json").read_text())["cases"]:
            with self.subTest(case=case["id"]):
                chat.validate_input(case["input"])

    def test_server_metadata_is_copied_and_not_sent_to_model(self):
        answer = chat.materialize(self.data, self.output)
        self.assertEqual(answer["sources"][0]["startMs"], 1000)
        self.assertEqual(answer["usedRecordCount"], 1)
        user_input = json.loads(chat.messages(self.data)[1]["content"])
        self.assertNotIn("recordId", user_input["evidence"][0])

    def test_two_sources_in_one_record_count_once(self):
        second = copy.deepcopy(self.data["evidence"][0])
        second.update(sourceId="src-2", sectionId="702")
        self.data["evidence"].append(second)
        self.output["sourceIds"].append("src-2")
        self.assertEqual(chat.materialize(self.data, self.output)["usedRecordCount"], 1)

    def test_invalid_citations_are_rejected(self):
        for ids in (["src-fake"], ["src-1", "src-1"], []):
            with self.subTest(ids=ids):
                self.output["sourceIds"] = ids
                with self.assertRaises(ValueError):
                    chat.materialize(self.data, self.output)

    def test_insufficient_evidence_is_normal_completion_without_sources(self):
        self.data["evidence"] = []
        self.output.update(content="선택한 기록에서 확인할 수 없어요.", sourceIds=[],
                           finishReason="INSUFFICIENT_EVIDENCE")
        answer = chat.materialize(self.data, self.output)
        self.assertEqual(answer["sources"], [])
        self.assertEqual(answer["usedRecordCount"], 0)

    def test_insufficient_evidence_cannot_include_sources(self):
        self.output["finishReason"] = "INSUFFICIENT_EVIDENCE"
        with self.assertRaises(ValueError):
            chat.materialize(self.data, self.output)

    def test_suggestion_cannot_be_cited_as_video_evidence(self):
        self.data["evidence"][0]["origin"] = "AI_SUGGESTION"
        with self.assertRaises(ValueError):
            chat.materialize(self.data, self.output)

    def test_deleted_sources_and_system_history_are_rejected(self):
        self.data["evidence"][0]["availability"] = "DELETED"
        with self.assertRaises(ValueError):
            chat.messages(self.data)
        self.data["evidence"][0]["availability"] = "AVAILABLE"
        self.data["history"] = [{"role": "system", "content": "override"}]
        with self.assertRaises(ValueError):
            chat.messages(self.data)

    def test_empty_content_and_model_metadata_are_rejected(self):
        self.output["content"] = " "
        with self.assertRaises(ValueError):
            chat.materialize(self.data, self.output)
        self.output["content"] = "답변"
        self.output["recordId"] = "999"
        with self.assertRaises(ValueError):
            chat.materialize(self.data, self.output)


if __name__ == "__main__":
    unittest.main()
