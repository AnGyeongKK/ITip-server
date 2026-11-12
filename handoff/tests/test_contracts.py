import copy
import json
import sys
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from preprocessing.normalize import normalize, expand_output
from validation.contracts import validate_chat, provider_status, ContractError, validate_analysis
from invocation.run import prepare_chat, request
from invocation.answer_stream import AnswerDecoder


class Contracts(unittest.TestCase):
    def setUp(self):
        self.chat=json.loads((ROOT/'examples/chat.supported.input.json').read_text())
        self.output={'answer':'40분에서 10분으로 줄었어요.','citations':[{'contextId':'C1','sectionId':self.chat['contexts'][0]['sectionIds'][0]}],'evidenceStatus':'SUPPORTED'}

    def test_valid_chat(self):validate_chat(self.output,self.chat)

    def test_foreign_context_and_section_rejected(self):
        for key,value in [('contextId','C999'),('sectionId','77777777-7777-4777-8777-777777777777')]:
            bad=copy.deepcopy(self.output);bad['citations'][0][key]=value
            with self.assertRaises(ContractError):validate_chat(bad,self.chat)

    def test_insufficient_is_not_provider_failure(self):
        out={'answer':'근거가 부족해요.','citations':[],'evidenceStatus':'INSUFFICIENT_EVIDENCE'}
        validate_chat(out,self.chat);provider_status('stop')
        for reason in ('length',None,'content_filter'):
            with self.assertRaises(ContractError):provider_status(reason)

    def test_status_citation_consistency(self):
        self.output['evidenceStatus']='INSUFFICIENT_EVIDENCE'
        with self.assertRaises(ContractError):validate_chat(self.output,self.chat)

    def test_budget_drops_history_before_context(self):
        data=copy.deepcopy(self.chat)
        data['history']=[{'role':'USER','content':'a '*2000},{'role':'ASSISTANT','content':'b '*2000}]
        kept,meta=prepare_chat(data,'system',{},budget=1000)
        self.assertEqual(kept['history'],[]);self.assertEqual(len(kept['contexts']),1)

    def test_question_is_never_truncated(self):
        data=copy.deepcopy(self.chat);data['question']='a '*10000
        with self.assertRaises(ValueError):prepare_chat(data,'system',{},budget=10)

    def test_analysis_mapping_preserves_original_ids_and_overlap_end(self):
        doc={'videoId':'test','language':'ko','segments':[
            {'segmentId':'s000001','startMs':0,'endMs':18000,'text':'첫 내용'},
            {'segmentId':'s000002','startMs':10000,'endMs':15000,'text':'마지막.'}]}
        prepared=normalize(doc,duration_ms=20000)
        self.assertEqual(prepared['groups'][0]['originalSegmentIds'],['s000001','s000002'])
        self.assertEqual(prepared['groups'][0]['endMs'],18000)
        self.assertEqual(prepared['originalDocument'],doc)

    def test_single_long_caption_and_last_short_group(self):
        doc={'videoId':'test','language':'ko','segments':[
            {'segmentId':'s000001','startMs':0,'endMs':30000,'text':'긴 자막.'},
            {'segmentId':'s000002','startMs':30000,'endMs':31000,'text':'끝'}]}
        p=normalize(doc,duration_ms=31000)
        self.assertEqual(len(p['groups']),2);self.assertEqual(p['groups'][0]['endMs'],30000)

    def test_no_meaningful_caption_is_not_fake_success(self):
        doc={'videoId':'test','language':'ko','segments':[{'segmentId':'s000001','startMs':0,'endMs':10000,'text':'[음악]'}]}
        with self.assertRaises(ValueError):normalize(doc,duration_ms=10000)

    def test_answer_stream_every_character_and_surrogate_pair(self):
        raw=json.dumps({'answer':'한글 "인용"\n😀','citations':[],'evidenceStatus':'INSUFFICIENT_EVIDENCE'})
        d=AnswerDecoder();body=''.join(d.feed(c) for c in raw)
        self.assertEqual(body,d.finish()['answer']);self.assertNotIn('citations',body)

    def test_duplicate_json_and_truncation_rejected(self):
        for raw in ('{"answer":"x","answer":"y"}','{"answer":"unfinished'):
            d=AnswerDecoder();d.feed(raw)
            with self.assertRaises(ValueError):d.finish()

    def test_request_strict_schema_and_model(self):
        body,_,_=request('chat',self.chat)
        self.assertTrue(body['response_format']['json_schema']['strict'])
        self.assertEqual(body['model'],'gpt-6-luna');self.assertEqual(body['reasoning_effort'],'low')

    def analysis_fixture(self):
        result=json.loads((ROOT/'examples/analysis.actual.json').read_text())
        return result['springOutput'],result['preprocessing']['originalDocument']

    def test_actual_full_analysis_matches_original_id_contract(self):
        output,document=self.analysis_fixture()
        validate_analysis(output,document,expanded=True)

    def test_invalid_analysis_boundary_and_foreign_id(self):
        for field,value in [('startSegmentId','s999999'),('endSegmentId','g000001')]:
            output,document=self.analysis_fixture();output['sections'][0][field]=value
            with self.assertRaises(ValueError):validate_analysis(output,document,expanded=True)

    def test_detail_outside_section_is_error_under_current_spring(self):
        output,document=self.analysis_fixture()
        output['sections'][0]['detail']['examples'][0]['sourceSegmentIds']=[document['segments'][-1]['segmentId']]
        with self.assertRaises(ValueError):validate_analysis(output,document,expanded=True)

    def test_empty_mindmap_is_not_success(self):
        output,document=self.analysis_fixture();output['mindmap']['sectionKeys']=[]
        with self.assertRaises(ValueError):validate_analysis(output,document,expanded=True)


if __name__=='__main__':unittest.main()
