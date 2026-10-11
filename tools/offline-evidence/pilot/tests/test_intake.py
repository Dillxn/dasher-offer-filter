"""Synthetic fixtures only. These tests do not constitute a phone run or gate receipt."""
import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import pilot_intake as p

ROOT=Path(__file__).resolve().parents[1]
NOW=dt.datetime.fromisoformat('2026-10-11T20:00:00+00:00')
def when(hour=9,minute=0):
    return f'2026-10-11T{hour:02}:{minute:02}:00+00:00'

class IntakeTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.data=json.loads((ROOT/'live/pilot-state.json').read_text())
        self.receipt=json.loads((ROOT/'live/phone-receipt.json').read_text())
        self.data['evidence_mode']='synthetic_fixture'
        self.data['phone_runs']=[]
        for kind in p.KINDS:
            self.evidence(kind,kind)
    def evidence(self,eid,kind,body=None):
        body=body or f'SYNTHETIC_FIXTURE: {eid}; generated only for local validator verification. No actual human observation.\n'
        path=f'{eid}.txt'
        (self.root/path).write_text(body)
        self.data['evidence'].append(dict(id=eid,path=path,kind=kind,synthetic=True,sha256=hashlib.sha256(body.encode()).hexdigest()))
        return eid
    def run_data(self):
        (self.root/'phone-receipt.json').write_text(json.dumps(self.receipt))
        (self.root/'state.json').write_text(json.dumps(self.data))
        return p.evaluate_file(self.root/'state.json',NOW)
    def rejected(self,message):
        with self.assertRaisesRegex((p.Invalid,ValueError,KeyError,TypeError),message):
            self.run_data()
    def gates(self):
        for name in p.GATES:
            self.data['gates'][name]=dict(status='RECORDED',owner=p.OWNER,recorded_at=when(8),evidence_refs=['owner_review'],artifact=copy.deepcopy(p.FROZEN),phone_evidence_refs=['phone_observation'])
    def case(self,cid='C02',artifact=None):
        artifact=artifact or p.FROZEN
        r=self.receipt
        r['document_type']='phone_receipt'
        r['observed_run'].update(run_id='SYNTHETIC-RUN',operator_local_nickname='fixture-operator',started_at=when(),ended_at=when(9,10),timezone='UTC',stationary_and_consented=True,dasher_version='fixture-only-version')
        r['observed_run']['device'].update(model='fixture device',android_version='16',android_build='fixture build')
        r['observed_run']['installed_offerfilter']={**artifact,'verification_method':'fixture bytes checked, never a live result','receipt_reference':'artifact_verification','includes_successor_commits':[]}
        r['observed_run']['settings'].update(filtering='PAUSED',autopilot='OFF',peek='OFF',auto_accept='OFF')
        c=next(c for c in r['case_results'] if c['id']==cid)
        c.update(status='PASS',observed_build_receipt='artifact_verification',started_at=when(),ended_at=when(9,5),procedure_observed='SYNTHETIC fixture procedure',expected_result='SYNTHETIC expected state',actual_result='SYNTHETIC observed state',reviewer='fixture reviewer',reviewed_at=when(9,11),settings_restored=True,timeline=[dict(relative_ms=100,event='observed_outcome',source='direct_observation',detail='SYNTHETIC fixed category')])
        entry=dict(path='phone-receipt.json',stage='owner_repair',artifact_evidence='artifact_verification',case_evidence={cid:['phone_observation']})
        self.data['phone_runs']=[entry]
        return c,entry
    def participant(self,index,hour=9,minute=0,complete=False):
        self.data['schedule']=dict(starts_at=when(8),ends_at='2026-10-18T08:00:00+00:00')
        person=dict(code=f'P{index:02}',enrolled_at=when(hour,minute),adult_confirmed=True,android_major=16,apk_comfort_confirmed=True,agreement_evidence=['consent'],same_app_update_channel=True,status='enrolled',replacement_for=None,seat_ended_at=None,installation=None,stationary_check=None,ordinary_sessions=[],study_choice=dict(value=None,affirmative_save=False,saved_at=None,withdrawn_at=None,evidence_refs=[]),study_records=[],automatic_reporting_choice=dict(value=False,affirmative_save=False,saved_at=None,withdrawn_at=None,evidence_refs=[]))
        if complete:
            person.update(status='active',installation=dict(artifact=copy.deepcopy(p.FROZEN),evidence_refs=['participant_checkin']),stationary_check=dict(completed_at=when(10),passed=True,filtering='PAUSED',autopilot='OFF',auto_accept='OFF',peek='OFF',evidence_refs=['participant_checkin']))
            person['ordinary_sessions']=[dict(id=f'session-{index}',started_at=when(11),ended_at=when(12),reviewed_at=when(13),voluntary=True,safety_checkin_complete=True,private_feedback_reviewed=True,incident_status_reviewed=True,autopilot='OFF',auto_accept='OFF',peek='OFF',filtering='PAUSED',artifact=copy.deepcopy(p.FROZEN),evidence_refs=['participant_checkin'])]
        self.data['participants'].append(person)
        return person
    def expansion(self):
        self.data['first_three_review']=dict(status='RECORDED',owner=p.OWNER,reviewed_at=when(14),participant_codes=['P01','P02','P03'],evidence_refs=['owner_review'])
    def study(self,person):
        person['study_choice']=dict(value=True,affirmative_save=True,saved_at=when(9,30),withdrawn_at=None,evidence_refs=['consent'])
        if person['ordinary_sessions']:
            person['ordinary_sessions'][0].update(filtering='ON',filtering_choice_evidence=['consent'],estimate_method_version='fixture-prospective-v1',policy_epoch='fixture-policy-1')
        record=dict(session_id=f'session-{person["code"][-2:].lstrip("0")}',submitted_at=when(11,15),in_flight_at_withdrawal=False,eligible_coverage_seconds=600,recorded_at=when(11),outcome_or_censor_at=when(11,10),received_at=when(12),reviewed_at=when(13),usable=True,prospective=True,coverage_checked=True,end_kind='right_censored',method_version='fixture-prospective-v1',policy_epoch='fixture-policy-1',artifact=copy.deepcopy(p.FROZEN),send_mode='manual',send_evidence=['participant_checkin'],report_reference=f'fixture-report-{person["code"]}',evidence_refs=['study_record'])
        person['study_records'].append(record)
        return record
    def test_live_blank_preserves_all_unknown_results(self):
        result=p.evaluate_file(ROOT/'live/pilot-state.json',NOW)
        self.assertTrue(result['schema_valid'])
        self.assertEqual(result['phone_runs'][0]['counts'],{'NOT_RUN':14,'BLOCKED':2})
        self.assertEqual(result['submitted_candidate_pass_cases'],[])
        self.assertEqual(result['recruitment'],'HELD')
        self.assertEqual(result['cohort']['enrolled'],0)
    def test_submitted_phone_evidence_is_never_owner_acceptance(self):
        self.case()
        result=self.run_data()
        self.assertEqual(result['submitted_candidate_pass_cases'],['C02'])
        self.assertEqual(result['owner_accepted_phone_passes'],'NOT_INFERRED')
        self.assertEqual(result['recruitment'],'HELD')
    def test_source_tests_cannot_be_phone_evidence(self):
        c,e=self.case(); e['case_evidence']['C02']=['source_test']
        self.rejected('wrong evidence kind')
    def test_expected_reference_cannot_change_observed_binding(self):
        self.case(); self.receipt['expected_artifacts']['frozen_candidate']['version_code']=90
        self.receipt['observed_run']['installed_offerfilter']['version_code']=90
        self.rejected('version_code does not match')
    def test_copied_expected_identity_without_receipt_rejected(self):
        self.case(); self.receipt['observed_run']['installed_offerfilter']['verification_method']=None
        self.rejected('verification method')
    def test_public_baseline_never_credits_frozen(self):
        self.case(artifact=p.PUBLIC)
        result=self.run_data()
        self.assertEqual(result['phone_runs'][0]['artifact'],'public_baseline')
        self.assertEqual(result['submitted_candidate_pass_cases'],[])
    def test_frozen_cannot_claim_successor(self):
        self.case(); self.receipt['observed_run']['installed_offerfilter']['includes_successor_commits']=['77cd7fcf7751ebc4d51f851b05c577ac99d32396']
        self.rejected('cannot contain successor')
    def test_successor_status_locked(self):
        self.case(); self.receipt['case_results'][-1]['status']='PASS'
        self.rejected('successor cases stay BLOCKED')
    def test_duplicate_case_ids(self):
        self.case(); self.receipt['case_results'][1]['id']='C01'
        self.rejected('exactly C01')
    def test_unknown_status(self):
        c,e=self.case(); c['status']='LIKELY_PASS'; self.rejected('invalid phone status')
    def test_boolean_version_code(self):
        self.case(); self.receipt['observed_run']['installed_offerfilter']['version_code']=True
        self.rejected('boolean')
    def test_nan_event_rejected(self):
        c,e=self.case(); c['timeline'][0]['relative_ms']=float('nan'); self.rejected('nonfinite JSON')
    def test_reversed_case_time(self):
        c,e=self.case(); c['ended_at']=when(8); self.rejected('chronology')
    def test_request_only_is_not_pass(self):
        c,e=self.case(); c['timeline'][0]['event']='action_requested'; self.rejected('request logs')
    def test_case_run_mismatch(self):
        c,e=self.case(); c['observed_build_receipt']='different'; self.rejected('case/run artifact mismatch')
    def test_empty_evidence(self):
        self.case(); ev=self.data['evidence'][0]; (self.root/ev['path']).write_text(''); ev['sha256']=hashlib.sha256(b'').hexdigest(); self.rejected('empty')
    def test_tampered_evidence(self):
        self.case(); (self.root/'phone_observation.txt').write_text('altered content that differs from the recorded attachment hash'); self.rejected('SHA-256 mismatch')
    def test_fixture_cannot_be_relabelled_live(self):
        self.case(); self.data['evidence_mode']='live'
        for e in self.data['evidence']: e['synthetic']=False
        self.rejected('fixture evidence')
    def test_parent_path_rejected(self):
        self.data['evidence'][0]['path']='../other.txt'; self.rejected('inside intake')
    def test_initial_stage_cannot_validate_peek_case(self):
        c,e=self.case('C09'); e['stage']='initial_pilot'; self.rejected('later-enabled')
    def test_paused_only_cannot_validate_read_cost(self):
        c,e=self.case('C13'); e['filtering_states_observed']=['PAUSED']; self.rejected('both observed filtering states')
    def test_study_case_needs_inclusion_evidence(self):
        c,e=self.case('C10'); e['study_build_confirmation']=[]; self.rejected('evidence IDs')
    def test_update_has_explicit_before_and_after(self):
        self.gates(); c,e=self.case('C11')
        e['update_observation']=dict(before=p.PUBLIC,after=p.FROZEN,same_update_channel=True,uninstalled=False,settings_history_preserved=True,actual_launch_observed=True,evidence_refs=['phone_observation','artifact_verification'])
        self.assertEqual(self.run_data()['submitted_candidate_pass_cases'],['C11'])
        e['update_observation']['uninstalled']=True; self.rejected('ordinary upgrade')
    def test_pending_release_blocks_update_pass(self):
        self.case('C11'); self.rejected('accepted published candidate')
    def test_enrollment_before_clearance_rejected(self):
        self.participant(1); self.rejected('owner gates beforehand')
    def test_three_are_advisory_only(self):
        self.gates()
        for i in range(1,4): self.participant(i)
        result=self.run_data()
        self.assertEqual(result['cohort']['remaining_seat_capacity'],0)
        self.assertEqual(result['recruitment'],'HELD')
    def test_fourth_without_expansion_rejected(self):
        self.gates()
        for i in range(1,5): self.participant(i)
        self.rejected('fourth seat')
    def test_three_downloads_do_not_clear_expansion(self):
        self.gates()
        for i in range(1,4): self.participant(i)
        self.expansion(); self.rejected('completed/reviewed sessions')
    def test_review_before_session_rejected(self):
        self.gates()
        for i in range(1,4): self.participant(i,complete=True)
        self.expansion(); self.data['first_three_review']['reviewed_at']=when(12)
        self.rejected('completed/reviewed sessions')
    def test_then_seven_ten_occupied(self):
        self.gates()
        for i in range(1,4): self.participant(i,complete=True)
        self.expansion()
        for i in range(4,11): self.participant(i,hour=15)
        r=self.run_data(); self.assertEqual(r['cohort']['occupied_seats'],10)
        self.assertEqual(r['cohort']['active_phone_testers'],3)
        self.participant(11,hour=16); self.rejected('ten primary')
    def test_sixteenth_enrollment_rejected(self):
        self.gates()
        for i in range(1,17): self.participant(i)
        self.rejected('15 cumulative')
    def test_reused_participant_code(self):
        self.gates(); self.participant(1); self.participant(1); self.rejected('unique P01')
    def test_replacement_requires_closed_seat(self):
        self.gates(); self.participant(1); person=self.participant(2); person['replacement_for']='P01'; self.rejected('previously closed seat')
    def test_seven_days_fixed(self):
        self.gates(); self.participant(1); self.data['schedule']['ends_at']='2026-10-19T08:00:00+00:00'; self.rejected('seven days')
    def test_prechecked_choice_not_consent(self):
        self.gates(); person=self.participant(1); self.study(person); person['study_choice']['affirmative_save']=False; self.rejected('checked-default')
    def test_study_optout_can_remain_usability_tester(self):
        self.gates(); person=self.participant(1,complete=True); person['study_choice']['value']=False
        r=self.run_data(); self.assertEqual(r['cohort']['active_phone_testers'],1); self.assertEqual(r['cohort']['usable_study_participants'],0)
    def test_study_record_after_withdrawal(self):
        self.gates(); person=self.participant(1,complete=True); self.study(person); person['study_choice']['withdrawn_at']=when(10); person['study_choice']['value']=False; self.rejected('after withdrawal')
    def test_separate_auto_reporting_choice(self):
        self.gates(); person=self.participant(1,complete=True); record=self.study(person); record['send_mode']='automatic'; record['reporting_choice_evidence']=[]; self.rejected('separate affirmative automatic-reporting')
    def test_received_record_counts_not_consent(self):
        self.gates(); person=self.participant(1,complete=True); self.study(person)
        r=self.run_data(); self.assertEqual(r['cohort']['usable_study_participants'],1); self.assertEqual(r['cohort']['prediction_coverage'],'INSUFFICIENT')
    def test_duplicate_report_retry_not_extra_data(self):
        self.gates(); person=self.participant(1,complete=True); record=self.study(person); person['study_records'].append(copy.deepcopy(record)); self.rejected('duplicate received report')
    def test_critical_stops_testing_and_admission(self):
        self.gates(); person=self.participant(1)
        self.data['incidents']=[dict(severity='critical',observed_at=when(10),resolved_at=None,evidence_refs=['participant_checkin'])]
        r=self.run_data(); self.assertEqual(r['active_testing'],'STOP'); self.assertIn('critical_incident_stop',r['blockers'])
        self.participant(2,hour=11); self.rejected('critical incident hold')
    def test_blocking_issue_holds_study(self):
        self.data['incidents']=[dict(severity='blocking',observed_at=when(10),resolved_at=None,evidence_refs=['participant_checkin'])]
        r=self.run_data(); self.assertIn('blocking_install_consent_reporting_hold',r['blockers'])
    def test_duplicate_json_key_rejected(self):
        (self.root/'bad.json').write_text('{"schema_version":1,"schema_version":1}')
        with self.assertRaisesRegex(p.Invalid,'duplicate JSON key'): p.evaluate_file(self.root/'bad.json',NOW)

    def test_newer_fail_is_not_hidden_by_older_pass(self):
        self.gates(); c,e=self.case()
        (self.root/'old-pass.json').write_text(json.dumps(self.receipt))
        older=copy.deepcopy(e); older['path']='old-pass.json'
        c['status']='FAIL'; self.receipt['observed_run']['run_id']='SYNTHETIC-FAIL'
        self.data['phone_runs'].insert(0,older)
        r=self.run_data()
        self.assertEqual(r['submitted_candidate_pass_cases'],[])
        self.assertEqual(r['unresolved_candidate_failures'][0]['case_id'],'C02')
        self.assertIn('observed_candidate_failure_requires_owner_disposition',r['blockers'])
    def test_failure_needs_later_owner_reviewed_retest(self):
        self.gates(); c,e=self.case(); c['status']='FAIL'
        (self.root/'failed.json').write_text(json.dumps(self.receipt))
        failed=copy.deepcopy(e); failed['path']='failed.json'
        c['status']='PASS'; c['started_at']=when(9,6); c['ended_at']=when(9,7); c['reviewed_at']=when(9,12)
        self.receipt['observed_run']['run_id']='SYNTHETIC-RETEST'
        self.data['phone_runs'].insert(0,failed)
        self.data['case_dispositions']=[dict(run_id='SYNTHETIC-RUN',case_id='C02',owner=p.OWNER,resolution='OWNER_ACCEPTED_RETEST',recorded_at=when(10),retest_run_id='SYNTHETIC-RETEST',evidence_refs=['owner_review'])]
        r=self.run_data(); self.assertEqual(r['unresolved_candidate_failures'],[])
        self.assertEqual(len(r['observed_candidate_failures']),1)
        c['started_at']=when(); c['ended_at']=when(9,5)
        self.rejected('retest observation must follow')
    def test_baseline_cannot_qualify_candidate_cohort(self):
        self.gates(); person=self.participant(1,complete=True); person['ordinary_sessions'][0]['artifact']=p.PUBLIC
        self.rejected('pilot session')
    def test_session_requires_installation_even_when_enrolled(self):
        self.gates(); person=self.participant(1,complete=True); person['status']='enrolled'; person['installation']=None
        self.rejected('confirmed installation')
    def test_zero_length_session_rejected(self):
        self.gates(); person=self.participant(1,complete=True); person['ordinary_sessions'][0]['ended_at']=when(11)
        self.rejected('session chronology')
    def test_unbound_study_record_rejected(self):
        self.gates(); person=self.participant(1); self.study(person)
        self.rejected('link a qualified session')
    def test_paused_study_coverage_rejected(self):
        self.gates(); person=self.participant(1,complete=True); self.study(person); person['ordinary_sessions'][0]['filtering']='PAUSED'
        self.rejected('active-filtering session')
    def test_historical_sent_record_survives_optout(self):
        self.gates(); person=self.participant(1,complete=True); self.study(person)
        person['study_choice'].update(value=False,withdrawn_at=when(13))
        self.assertEqual(self.run_data()['cohort']['usable_study_participants'],1)
    def test_withdrawal_does_not_allow_unsent_queued_report(self):
        self.gates(); person=self.participant(1,complete=True); record=self.study(person)
        person['study_choice'].update(value=False,withdrawn_at=when(11,20)); record['submitted_at']=when(11,30)
        self.rejected('after withdrawal')
    def test_automatic_reporting_cannot_reuse_study_consent(self):
        self.gates(); person=self.participant(1,complete=True); record=self.study(person); record['send_mode']='automatic'
        person['automatic_reporting_choice']=dict(value=True,affirmative_save=True,saved_at=when(10),withdrawn_at=None,evidence_refs=['consent'])
        self.rejected('separate evidence')
    def test_exact_incident_time_holds_admission(self):
        self.gates(); self.participant(1,hour=10)
        self.data['incidents']=[dict(severity='critical',observed_at=when(10),resolved_at=None,evidence_refs=['participant_checkin'])]
        self.rejected('critical incident hold')
    def test_session_overlapping_critical_incident_rejected(self):
        self.gates(); self.participant(1,complete=True)
        self.data['incidents']=[dict(severity='critical',observed_at=when(11,30),resolved_at=None,evidence_refs=['participant_checkin'])]
        self.rejected('active testing during critical')
    def test_invalid_severity_rejected(self):
        c,e=self.case(); c['incident_severity']='CRITICAL'; self.rejected('invalid incident severity')
    def test_blank_cannot_hide_result(self):
        self.receipt['case_results'][0]['actual_result']='claimed outcome without run'
        self.data['phone_runs']=[dict(path='phone-receipt.json')]
        self.rejected('unrun case has result evidence')
    def test_output_will_not_overwrite_intake(self):
        import subprocess
        self.run_data(); path=self.root/'state.json'; before=path.read_bytes()
        result=subprocess.run([sys.executable,str(ROOT/'pilot_intake.py'),str(path),'--output',str(path)],capture_output=True)
        self.assertEqual(result.returncode,2); self.assertEqual(path.read_bytes(),before)
    def test_initial_optout_is_not_historical_optin(self):
        self.gates(); person=self.participant(1,complete=True); self.study(person)
        person['study_choice']['value']=False
        self.rejected('affirmative consent interval')
    def test_replay_clock_is_explicit(self):
        self.assertEqual(self.run_data()['evaluation_clock'],'explicit_replay_timestamp')

if __name__=='__main__': unittest.main(verbosity=2)
