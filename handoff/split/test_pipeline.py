import copy
from pathlib import Path
import sys
import unittest

from jsonschema import ValidationError

HANDOFF = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(HANDOFF))
from validation.contracts import validate_analysis, load_json
import pipeline as p
def read_json(path): return load_json(Path(path).read_text())


class SplitRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parent
        cls.message = read_json(root/'examples/example.input.json')
        cls.output = read_json(root/'examples/example.output.json')
        cls.base = read_json(HANDOFF/'schemas/analysis.spring-v1.json')

    def plan(self):
        first,last = self.output['sections']
        return {'earlierSections':[{'title':first['title'],'endSegmentId':first['endSegmentId']}],
                'lastSectionTitle':last['title']}

    def test_plan_assigns_contiguous_boundaries_and_server_keys(self):
        fixed = p.freeze_sections(self.message,self.plan())
        expected = [{k:s[k] for k in ('sectionKey','title','startSegmentId','endSegmentId')} for s in self.output['sections']]
        self.assertEqual(fixed,expected)
        self.assertEqual([g['groupId'] for s in fixed for g in p.section_groups(self.message,s)],p.identifiers(self.message))

    def test_duplicate_or_reordered_boundaries_are_rejected_without_sorting(self):
        planned = self.plan()
        planned['earlierSections'] *= 2
        with self.assertRaises(ValueError):p.freeze_sections(self.message,planned)

    def test_last_boundary_cannot_be_claimed_early(self):
        planned = self.plan()
        planned['earlierSections'][0]['endSegmentId'] = p.identifiers(self.message)[-1]
        with self.assertRaises(ValidationError):p.freeze_sections(self.message,planned)

    def test_one_section_is_allowed_and_covers_the_entire_input(self):
        fixed = p.freeze_sections(self.message,{'earlierSections':[],'lastSectionTitle':'전체 주제'})
        self.assertEqual(len(fixed),1)
        self.assertEqual(p.section_groups(self.message,fixed[0]),self.message['groups'])

    def test_detail_uses_only_its_original_captions(self):
        section = p.freeze_sections(self.message,self.plan())[0]
        data = p.detail_message(self.message,section)
        self.assertEqual(data['groups'],p.section_groups(self.message,section))
        self.assertLess(len(data['groups']),len(self.message['groups']))

    def test_out_of_section_and_empty_suggestion_sources_are_rejected(self):
        section = self.output['sections'][0]
        stage = {k:copy.deepcopy(section[k]) for k in ('sourceSegmentIds','summary','subtopics','keywords','detail')}
        stage['mentionedTools'] = []
        ids = [g['groupId'] for g in p.section_groups(self.message,section)]
        schema = p.detail_schema(self.base,ids)
        p.validate_stage(stage,schema)
        for invalid in ('',p.identifiers(self.message)[-1]):
            bad = copy.deepcopy(stage)
            bad['detail']['applicationTips'] = [{'text':'맥락 기반 제안입니다.','origin':'AI_SUGGESTION','sourceSegmentIds':[invalid]}]
            with self.assertRaises(ValidationError):p.validate_stage(bad,schema)

    def test_summary_references_only_existing_sections_and_sent_originals(self):
        stage = {'summary':copy.deepcopy(self.output['summary']),'rootTitle':self.output['mindmap']['rootTitle']}
        ids = p.identifiers(self.message)
        keys = [s['sectionKey'] for s in self.output['sections']]
        schema = p.summary_schema(self.base,ids,keys)
        p.validate_stage(stage,schema)
        stage['summary']['highlightSectionKeys'] = ['section-99']
        with self.assertRaises(ValidationError):p.validate_stage(stage,schema)

    def test_numeric_original_evidence_is_preserved_beside_generated_details(self):
        message = copy.deepcopy(self.message)
        message['groups'].append({'groupId':'EX4','text':'추가 수치는 73%입니다.'})
        data = p.summary_message(message,self.output['sections'],[{'mentionedTools':[]}])
        self.assertIn(message['groups'][-1],data['evidence'])
        self.assertNotIn('groups',data)

    def test_assembly_matches_full_original_contract_and_does_not_mutate(self):
        before = copy.deepcopy(self.output)
        stage = {'summary':self.output['summary'],'rootTitle':self.output['mindmap']['rootTitle']}
        result = p.assemble(self.output['sections'],stage)
        document = {'segments':[{'segmentId':g['groupId'],'startMs':i*20000,'endMs':(i+1)*20000} for i,g in enumerate(self.message['groups'])]}
        validate_analysis(result,document,profile='spring-v1',require_full_coverage=True)
        self.assertEqual(result,before)
        self.assertEqual(self.output,before)

    def test_base_schema_not_mutated_by_stage_adapters(self):
        original = copy.deepcopy(self.base)
        p.detail_schema(self.base,p.identifiers(self.message))
        p.summary_schema(self.base,p.identifiers(self.message),['section-01'])
        self.assertEqual(self.base,original)

    def test_parallel_detail_caps_reserve_summary_and_do_not_overspend(self):
        for used in (0,1024,8000,12925):
            caps = p.detail_caps(used,3)
            self.assertLessEqual(sum(caps)+used+p.CAPS['summary'],p.TOTAL_COMPLETION_BUDGET)
        with self.assertRaises(ValueError):p.detail_caps(16000,3)

    def test_input_budget_is_checked_before_any_api_call(self):
        with self.assertRaisesRegex(ValueError,'SPLIT_INPUT_TOO_LARGE'):
            p.request('plan',self.message,p.plan_schema(self.message),p.CAPS['plan'],lambda *args:30001)


if __name__ == '__main__':unittest.main()
