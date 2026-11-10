"""Recheck saved actual outputs offline; no new provider calls."""
import json
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from validation.contracts import validate_chat,validate_analysis,ContractError
from invocation.run import consume_stream, dump
import hashlib
import time


def check(condition, message):
    # Keep verification active under python -O too.
    if not condition:
        raise AssertionError(message)


def verify_record(record, kind):
    request = record['request']
    check(json.loads(request['messages'][1]['content']) == record['transmittedInput'], 'transmitted input mismatch')
    digest = hashlib.sha256(dump(request['response_format']['json_schema']['schema']).encode()).hexdigest()
    check(digest == record['metadata']['schemaSha256'], 'request schema checksum mismatch')
    check(record['providerFinishReason'] == 'stop', 'provider did not complete normally')
    check(request['response_format']['json_schema']['strict'] is True, 'strict Schema missing')
    if kind == 'chat':
        from openai.types.chat import ChatCompletionChunk
        replay = {'providerChunks': [], 'answerDeltas': [], 'providerFinishReason': None,
                  'metadata': {'firstDeltaSeconds': None, 'usage': None}}
        consume_stream((ChatCompletionChunk.model_validate(c) for c in record['providerChunks']), replay, began=time.monotonic())
        check(replay['response'] == record['response'], 'saved output differs from provider chunks')
        check(''.join(replay['answerDeltas']) == record['response']['answer'], 'replayed body differs')
        check(''.join(record['answerDeltas']) == record['response']['answer'], 'saved deltas differ')
        validate_chat(record['response'], record['transmittedInput'])
    else:
        groups = {'segments': [{'segmentId': g['groupId'], 'startMs': g['startMs'], 'endMs': g['endMs']}
                               for g in record['preprocessing']['groups']]}
        profile = 'recommended-v1' if request['response_format']['json_schema']['name'] == 'analysis_recommended-v1' else 'spring-v1'
        validate_analysis(record['response'], groups, profile=profile, require_full_coverage=True)
        validate_analysis(record['springOutput'], record['preprocessing']['originalDocument'], expanded=True, profile=profile)


def verify():
    example=ROOT/'examples';rows=[]
    for case in json.loads((example/'evaluation.json').read_text()):
        path=example/case['input'].replace('.input.json','.actual.json')
        record=json.loads(path.read_text());output=record['response']
        verify_record(record, 'chat')
        check(output['evidenceStatus']==case['expectedStatus'],str(path))
        check({c['contextId'] for c in output['citations']}==set(case['expectedContextIds']),str(path))
        check(all(text not in output['answer'] for text in case.get('forbidden',[])),str(path))
        rows.append({'file':path.name,'passed':True,'seconds':record['metadata']['seconds']})
    for filename in ('analysis.actual.json','analysis.recommended.actual.json'):
        record=json.loads((example/filename).read_text())
        verify_record(record, 'analysis')
        rows.append({'file':filename,'passed':True,'seconds':record['metadata']['seconds']})
    failed=json.loads((example/'analysis.final-schema-r2.actual.json').read_text())
    try:
        groups={'segments':[{'segmentId':g['groupId'],'startMs':g['startMs'],'endMs':g['endMs']}
                            for g in failed['preprocessing']['groups']]}
        validate_analysis(failed['response'],groups)
    except ValueError:
        rows.append({'file':'analysis.final-schema-r2.actual.json','expectedRejection':True,
                     'reason':failed['validation']['detail']})
    else:raise AssertionError('invalid actual response was accepted')
    for filename, kind, expected in (
        ('review.analysis.actual.json', 'analysis', None),
        ('review.chat.supported.actual.json', 'chat', 'SUPPORTED'),
        ('review.chat.insufficient.actual.json', 'chat', 'INSUFFICIENT_EVIDENCE'),
    ):
        record = json.loads((example / filename).read_text())
        check(record.get('validation', {}).get('passed') is True, filename + ': validation failed')
        verify_record(record, kind)
        if expected:
            check(record['response']['evidenceStatus'] == expected, filename + ': unexpected status')
        rows.append({'file': filename, 'passed': True, 'seconds': record['metadata']['seconds']})
    return rows


if __name__=='__main__':
    print(json.dumps(verify(),ensure_ascii=False,indent=2))
