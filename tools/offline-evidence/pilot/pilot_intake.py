#!/usr/bin/env python3
"""Local, advisory intake; never authenticates a person, authorizes contact, or runs a phone."""
import argparse
import collections
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import re
import sys

# Public role assertion only; equality does not authenticate a person or approval.
OWNER = 'offerfilter-release-maintainer'
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
FROZEN = dict(version_name='0.5.9', version_code=89,
              source_commit='fc7c55bbb0e38603584f96df603e30eb4149abb2',
              source_tree='61a2cf564ee15899501f82ab30983ed4ac7e6468',
              apk_sha256='3211477cd2f8096577b73aa24b5807c750897a5aa201d0507801064cec344b08',
              certificate_sha256=SIGNER)
PUBLIC = dict(version_name='0.5.8', version_code=88,
              source_commit='a778c418d17e0babcf94972d0c72071e2315efe6',
              apk_sha256='459c4ae82b7538f81a9e55103557bdf243f3eaed55414e48707dbf89d1cd5f40',
              certificate_sha256=SIGNER)
CASE_IDS = [f'C{i:02}' for i in range(1,15)] + ['S01','S02']
STATES = {'PASS','FAIL','NOT_OBSERVED','BLOCKED','NOT_RUN'}
GATES = ['canonical_acceptance','publication','production_transport','full_phone_acceptance',
         'regression_resolution','site_acceptance','film_acceptance','private_triage',
         'privacy_contact','owner_support','recruitment_clearance']
BUILD_GATES = set(GATES[:5])
KINDS = {'phone_observation','artifact_verification','owner_review','participant_checkin',
         'consent','study_record','source_test'}
EVENTS = {'notification_observed','launch_requested','dasher_visible','readable_offer',
          'recognized_waiting','action_requested','observed_outcome','return_requested',
          'actual_route_visible','user_touch','lock','app_switch','other_fixed_category'}
DIRECT = {'dasher_visible','readable_offer','recognized_waiting','observed_outcome',
          'actual_route_visible','user_touch','lock','app_switch','other_fixed_category'}
PLACEHOLDERS = {'unknown','todo','tbd','none','null','pass','n/a','placeholder','example','test'}

class Invalid(ValueError):
    pass

def need(value, message):
    if not value:
        raise Invalid(message)

def text(value, label):
    need(isinstance(value,str) and value.strip() and value.strip().lower() not in PLACEHOLDERS,
         f'{label}: non-placeholder text required')
    return value

def timestamp(value, label, now=None):
    text(value,label)
    try:
        parsed = dt.datetime.fromisoformat(value.replace('Z','+00:00'))
    except ValueError as exc:
        raise Invalid(f'{label}: ISO timestamp required') from exc
    need(parsed.tzinfo is not None, f'{label}: timezone offset required')
    if now is not None:
        need(parsed <= now, f'{label}: future evidence is not admissible')
    return parsed

def integer(value,label):
    need(type(value) is int, f'{label}: integer required (boolean is not an integer)')
    return value

def no_duplicates(pairs):
    result = {}
    for key,value in pairs:
        need(key not in result, f'duplicate JSON key: {key}')
        result[key] = value
    return result

def read_json(path):
    need(path.stat().st_size <= 2_000_000, 'JSON exceeds 2 MB intake bound')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=no_duplicates,
                      parse_constant=lambda v: (_ for _ in ()).throw(Invalid(f'nonfinite JSON {v}')))

def local_path(root, relative):
    text(relative,'path')
    p = Path(relative)
    need(not p.is_absolute() and '..' not in p.parts, 'path must stay inside intake directory')
    current = root
    for part in p.parts:
        current = current / part
        need(not current.is_symlink(), 'symlink evidence is not accepted')
    need(current.is_file(), f'missing evidence file: {relative}')
    need(current.resolve().is_relative_to(root.resolve()), 'evidence escapes intake directory')
    return current

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def binding(actual, expected, label):
    need(isinstance(actual,dict), f'{label}: artifact object required')
    integer(actual.get('version_code'),f'{label}.version_code')
    for key,value in expected.items():
        need(actual.get(key) == value, f'{label}: {key} does not match pinned artifact')

def artifact_class(actual):
    if actual.get('apk_sha256') == FROZEN['apk_sha256']:
        binding(actual,FROZEN,'frozen installed build')
        need(not actual.get('includes_successor_commits'), 'frozen APK cannot contain successor fixes')
        return 'frozen_candidate'
    if actual.get('apk_sha256') == PUBLIC['apk_sha256']:
        binding(actual,PUBLIC,'public installed build')
        need(not actual.get('includes_successor_commits'), 'public APK cannot contain successor fixes')
        return 'public_baseline'
    raise Invalid('unregistered artifact: this policy accepts frozen candidate and public baseline only; successor requires a separate owner-reviewed policy')

class Intake:
    def __init__(self, root, data, now):
        self.root,self.data,self.now = root,data,now
        self.synthetic = data.get('evidence_mode') == 'synthetic_fixture'
        self.evidence = {}
        self.gates = {}
        self.runs = []
        self.critical = False
        self.blocking = False
        self.hashes = {}
        self.failures = []

    def refs(self, refs, kinds, label):
        need(isinstance(refs,list) and refs and len(refs)==len(set(refs)), f'{label}: unique evidence IDs required')
        found = []
        for ref in refs:
            need(ref in self.evidence, f'{label}: unregistered evidence {ref}')
            evidence = self.evidence[ref]
            need(evidence['kind'] in kinds, f'{label}: wrong evidence kind {evidence["kind"]}')
            found.append(evidence)
        return found

    def load_evidence(self):
        for entry in self.data['evidence']:
            eid = text(entry['id'],'evidence.id')
            need(eid not in self.evidence,'duplicate evidence ID')
            need(entry['kind'] in KINDS,'unknown evidence kind')
            need(type(entry['synthetic']) is bool,'evidence.synthetic must be boolean')
            need(entry['synthetic'] == self.synthetic,'synthetic/live evidence mismatch')
            p = local_path(self.root,entry['path'])
            b = p.read_bytes()
            need(0 < len(b) <= 2_000_000 and b.strip() not in (b'',b'{}',b'[]',b'null'), 'empty or oversized evidence rejected')
            need(len(b.strip()) >= 32,'evidence must contain a substantive bounded receipt')
            need(entry['sha256'] == hashlib.sha256(b).hexdigest(),'evidence SHA-256 mismatch')
            if not self.synthetic:
                need('fixtures' not in p.parts and 'tests' not in p.parts and
                     b'SYNTHETIC_FIXTURE' not in b.upper(), 'fixture evidence cannot enter live intake')
            self.hashes[entry['path']] = entry['sha256']
            self.evidence[eid] = entry

    def gate(self, name, item):
        need(item['status'] in {'PENDING','RECORDED'}, f'{name}: invalid gate status')
        if item['status'] == 'PENDING':
            return None
        need(item['owner'] == OWNER, f'{name}: canonical owner must record gate')
        at = timestamp(item['recorded_at'],name,self.now)
        self.refs(item['evidence_refs'],{'owner_review'},name)
        if name in BUILD_GATES:
            binding(item['artifact'],FROZEN,name)
        if name in {'full_phone_acceptance','regression_resolution'}:
            self.refs(item['phone_evidence_refs'],{'phone_observation'},name)
        return at

    def load_runs(self):
        seen = set()
        for entry in self.data['phone_runs']:
            p = local_path(self.root,entry['path'])
            receipt = read_json(p)
            self.hashes[entry['path']] = sha(p)
            need(receipt['schema_version'] == 1,'phone receipt schema must be 1')
            cases = receipt['case_results']
            need(len(cases)==16 and {c['id'] for c in cases} == set(CASE_IDS),'exactly C01–C14 and S01–S02 required')
            need(all(c['status'] in STATES for c in cases),'invalid phone status')
            need(all(c['status']=='BLOCKED' for c in cases if c['id'].startswith('S')),
                 'successor cases stay BLOCKED in this frozen-only intake policy')
            observed = receipt['observed_run']
            attempted = any(c['status'] in {'PASS','FAIL','NOT_OBSERVED'} for c in cases)
            counts = dict(collections.Counter(c['status'] for c in cases))
            for c in cases:
                need(c.get('incident_severity') in {None,'none','minor','blocking','critical'}, 'invalid incident severity')
                if c['status'] in {'NOT_RUN','BLOCKED'}:
                    need(not c.get('actual_result') and not c.get('timeline'), 'unrun case has result evidence')
            if not attempted and observed['run_id'] is None:
                need(all(v is None or v == [] for v in observed['installed_offerfilter'].values()),
                     'blank run contains claimed installed identity')
                self.runs.append(dict(run_id=None,artifact='unobserved',counts=counts,submitted_candidate_passes=[]))
                continue
            need(receipt['document_type']=='phone_receipt','completed observation must be explicitly labeled phone_receipt')
            rid = text(observed['run_id'],'run_id')
            need(rid not in seen,'duplicate run ID')
            seen.add(rid)
            start = timestamp(observed['started_at'],'run.started_at',self.now)
            end = timestamp(observed['ended_at'],'run.ended_at',self.now)
            need(start <= end,'run ends before it starts')
            need(observed['stationary_and_consented'] is True,'consented stationary observation required')
            text(observed['operator_local_nickname'],'operator nickname')
            text(observed['timezone'],'run timezone')
            text(observed['dasher_version'],'Dasher version')
            for key in ('model','android_version','android_build'):
                text(observed['device'][key],f'device.{key}')
            build = observed['installed_offerfilter']
            art = artifact_class(build)
            text(build['verification_method'],'installed verification method')
            text(build['receipt_reference'],'installed receipt reference')
            self.refs([entry['artifact_evidence']],{'artifact_verification'},'installed artifact')
            need(build['receipt_reference']==entry['artifact_evidence'],'observed receipt_reference must name registered artifact evidence')
            stage = entry['stage']
            need(stage in {'owner_repair','later_enabled','initial_pilot'},'unknown run stage')
            settings = observed['settings']
            for key in ('filtering','autopilot','peek','auto_accept'):
                text(settings[key],f'settings.{key}')
            if stage=='initial_pilot':
                need(all(settings[k]=='OFF' for k in ('autopilot','peek','auto_accept')),'initial cohort automation must stay OFF')
            candidate_passes = []
            for case in cases:
                cid,state=case['id'],case['status']
                if state in {'NOT_RUN','BLOCKED'}:
                    need(not case.get('actual_result') and not case.get('timeline'), f'{cid}: unrun case has result evidence')
                    continue
                cs=timestamp(case['started_at'],f'{cid}.started_at',self.now)
                ce=timestamp(case['ended_at'],f'{cid}.ended_at',self.now)
                cr=timestamp(case['reviewed_at'],f'{cid}.reviewed_at',self.now)
                need(start <= cs <= ce <= end and cr >= ce,f'{cid}: invalid chronology')
                need(case['observed_build_receipt']==entry['artifact_evidence'], f'{cid}: case/run artifact mismatch')
                for key in ('procedure_observed','expected_result','actual_result','reviewer'):
                    text(case[key],f'{cid}.{key}')
                self.refs(entry['case_evidence'][cid],{'phone_observation'},cid)
                events=case['timeline']
                need(isinstance(events,list) and events, f'{cid}: observation timeline required')
                last=-1
                for event in events:
                    num=event['relative_ms']
                    need(type(num) in (int,float) and math.isfinite(num) and num>=last and num>=0,
                         f'{cid}: finite ordered event timing required')
                    need(num <= (ce-cs).total_seconds()*1000,f'{cid}: timeline exceeds case duration')
                    need(event['event'] in EVENTS and event['source'] in {'direct_observation','bounded_diagnostic','self_report','unknown'},f'{cid}: unknown event/source')
                    text(event['detail'],f'{cid}.event.detail')
                    last=num
                if state=='PASS':
                    need(any(e['source']=='direct_observation' and e['event'] in DIRECT for e in events),
                         f'{cid}: request logs or silence cannot establish phone PASS')
                    need(case['settings_restored'] is True,f'{cid}: restore user settings before PASS')
                    if cid in {'C01','C03','C09','C14'}:
                        need(stage=='later_enabled',f'{cid}: full case includes a later-enabled subcase; partial evidence cannot be full PASS')
                    if cid=='C09':
                        need(settings['peek']=='ON', 'C09 full case requires observed Peek stage')
                    if cid=='C13':
                        need(set(entry['filtering_states_observed'])=={'ON','PAUSED'},'C13 needs both observed filtering states')
                    if cid=='C10':
                        self.refs(entry['study_build_confirmation'],{'owner_review'},'study-build inclusion')
                    if cid in {'C11','C12'}:
                        need(art=='frozen_candidate' and all(self.gates[g] for g in GATES[:3]),f'{cid}: accepted published candidate/transport required')
                    if cid=='C11':
                        update=entry['update_observation']
                        binding(update['before'],PUBLIC,'C11 before')
                        binding(update['after'],FROZEN,'C11 after')
                        need(update['same_update_channel'] is True and update['uninstalled'] is False and
                             update['settings_history_preserved'] is True and update['actual_launch_observed'] is True,
                             'C11 must observe ordinary upgrade, preservation and launch')
                        self.refs(update['evidence_refs'],{'phone_observation','artifact_verification'},'C11 update')
                    if art=='frozen_candidate':
                        candidate_passes.append(cid)
                if state=='FAIL' and art=='frozen_candidate':
                    self.failures.append(dict(run_id=rid,case_id=cid,reviewed_at=case['reviewed_at'],observed_ended_at=case['ended_at']))
                if case.get('incident_severity')=='blocking':
                    self.blocking=True
                if case.get('incident_severity')=='critical':
                    self.critical=True
            self.runs.append(dict(run_id=rid,artifact=art,counts=counts,case_status={c['id']:c['status'] for c in cases},case_reviewed_at={c['id']:c['reviewed_at'] for c in cases},case_started_at={c['id']:c['started_at'] for c in cases},submitted_candidate_passes=candidate_passes))

    def participants(self):
        data=self.data
        people=data['participants']
        need(isinstance(people,list) and len(people)<=15,'at most 15 cumulative enrollments')
        codes=[p['code'] for p in people]
        need(len(codes)==len(set(codes)) and all(re.fullmatch(r'P(0[1-9]|1[0-5])',c) for c in codes),'unique P01–P15 codes required; never reuse')
        schedule=data['schedule']
        if people:
            start=timestamp(schedule['starts_at'],'pilot start',self.now)
            end=timestamp(schedule['ends_at'],'pilot end')
            need(end-start==dt.timedelta(days=7),'pilot schedule must cover seven days')
        else:
            start=end=None
            need(schedule['starts_at'] is None and schedule['ends_at'] is None,'no launch dates in empty live ledger')
        expansion=data['first_three_review']
        reviewed=None
        selected=[]
        if expansion['status']=='RECORDED':
            need(expansion['owner']==OWNER,'canonical owner reviews expansion')
            reviewed=timestamp(expansion['reviewed_at'],'first-three review',self.now)
            self.refs(expansion['evidence_refs'],{'owner_review'},'first-three review')
            selected=expansion['participant_codes']
            need(len(selected)==3 and len(set(selected))==3 and all(c in codes for c in selected),'three distinct enrolled people required')
        else:
            need(expansion['status']=='PENDING','invalid expansion status')
        events=[]
        qualified={}
        usable=set()
        report_refs=set()
        installed_count=0
        seats=0
        primary=0
        replacements=0
        for p in people:
            code=p['code']
            admitted=timestamp(p['enrolled_at'],f'{code}.enrolled_at',self.now)
            need(start <= admitted < end,'enrollment outside seven-day schedule')
            need(all(self.gates.values()) and max(self.gates.values())<=admitted, 'admission requires all recorded owner gates beforehand')
            need(p['adult_confirmed'] is True and integer(p['android_major'],'android_major')>=11 and p['apk_comfort_confirmed'] is True,'pilot eligibility incomplete')
            self.refs(p['agreement_evidence'],{'consent'},'pilot and private-contact agreement')
            need(p['same_app_update_channel'] is True,'one shared app/update channel required')
            need(p['status'] in {'enrolled','active','withdrawn','inactive_unknown'},'invalid participant state')
            if p['replacement_for'] is None:
                primary+=1
            else:
                replacements+=1
                need(p['replacement_for'] in codes and p['replacement_for']!=code,'replacement code missing')
            ended=timestamp(p['seat_ended_at'],f'{code}.seat_ended_at',self.now) if p['seat_ended_at'] else None
            if p['status'] in {'withdrawn','inactive_unknown'}:
                need(ended is not None,'inactive/withdrawn seats require recorded closure')
            else:
                need(ended is None,'occupied participant cannot have ended seat')
            if ended:
                need(ended>=admitted,'seat ends before enrollment')
                events.append((ended,0,code))
            else:
                seats+=1
            events.append((admitted,1,code))
            if p['installation']:
                binding(p['installation']['artifact'],FROZEN,'pilot installation')
                self.refs(p['installation']['evidence_refs'],{'participant_checkin','artifact_verification'},'self-reported installation')
                installed_count+=1
            station=p['stationary_check']
            stationary_at=None
            if station:
                stationary_at=timestamp(station['completed_at'],'stationary check',self.now)
                need(stationary_at>=admitted,'check precedes enrollment')
                need(station['passed'] is True and station['filtering']=='PAUSED' and all(station[k]=='OFF' for k in ('autopilot','auto_accept','peek')),'stationary setup must pass with filtering paused and automation off')
                self.refs(station['evidence_refs'],{'phone_observation','participant_checkin'},'stationary evidence')
            sessions=p['ordinary_sessions']
            need(len({s['id'] for s in sessions})==len(sessions),'duplicate session ID within participant')
            qualifying=[]
            for s in sessions:
                ss=timestamp(s['started_at'],'session start',self.now)
                se=timestamp(s['ended_at'],'session end',self.now)
                sr=timestamp(s['reviewed_at'],'session review',self.now)
                need(stationary_at is not None and stationary_at<=ss<se<=sr and start<=ss<end and se<=end,'session chronology or stationary prerequisite invalid')
                need(not ended or se<=ended,'session after seat withdrawal/closure')
                need(s['voluntary'] is True and s['safety_checkin_complete'] is True and s['private_feedback_reviewed'] is True and s['incident_status_reviewed'] is True,'ordinary session requires explicit safety/private feedback/incident review')
                need(all(s[k]=='OFF' for k in ('autopilot','auto_accept','peek')),'initial cohort automation must remain off')
                if s['filtering']=='ON':
                    self.refs(s['filtering_choice_evidence'],{'consent'},'voluntary active filtering')
                else:
                    need(s['filtering']=='PAUSED','unknown filtering state')
                need(p['installation'] is not None,'qualified session needs confirmed installation')
                binding(s['artifact'],FROZEN,'pilot session')
                self.refs(s['evidence_refs'],{'phone_observation','participant_checkin'},'ordinary session')
                qualifying.append(sr)
            if p['status']=='active':
                need(qualifying and p['installation'],'active tester requires installation and qualified session')
            if qualifying:
                qualified[code]=min(qualifying)
            choice=p['study_choice']
            withdrawal=timestamp(choice['withdrawn_at'],'study withdrawal',self.now) if choice['withdrawn_at'] else None
            saved=timestamp(choice['saved_at'],'study saved choice',self.now) if choice['saved_at'] else None
            need(type(choice['value']) is bool or choice['value'] is None,'study value must be boolean or null')
            if choice['value'] is True or saved is not None:
                need(choice['affirmative_save'] is True and saved is not None,'checked-default is not affirmative study consent')
                self.refs(choice['evidence_refs'],{'consent'},'study affirmative choice')
            else:
                need(not p['study_records'],'study records require historical affirmative consent')
            if withdrawal:
                need(saved is not None and withdrawal>=saved and choice['value'] is False,'withdrawal must follow a saved choice and leave current study choice off')
            for record in p['study_records']:
                need(saved is not None and choice['affirmative_save'] is True and (choice['value'] is True or withdrawal is not None),'study record without separate affirmative consent interval')
                rs=timestamp(record['recorded_at'],'study recorded',self.now)
                record_end=timestamp(record['outcome_or_censor_at'],'study end',self.now)
                submitted=timestamp(record['submitted_at'],'study submitted',self.now)
                rr=timestamp(record['received_at'],'study received',self.now)
                review=timestamp(record['reviewed_at'],'study reviewed',self.now)
                need(saved<=rs<record_end<=submitted<=rr<=review and (withdrawal is None or submitted<=withdrawal),'study record before consent/after withdrawal/invalid chronology')
                if withdrawal and rr>withdrawal:
                    need(record['in_flight_at_withdrawal'] is True,'later receipt needs explicit in-flight evidence')
                session=next((s for s in sessions if s['id']==record['session_id']),None)
                need(session is not None,'study record must link a qualified session')
                need(session['filtering']=='ON' and timestamp(session['started_at'],'study session')<=rs<record_end<=timestamp(session['ended_at'],'study session'),'study coverage must be inside an active-filtering session')
                binding(record['artifact'],session['artifact'],'study/session artifact')
                need(record['method_version']==session['estimate_method_version'] and record['policy_epoch']==session['policy_epoch'],'study/session method or policy mismatch')
                coverage=record['eligible_coverage_seconds']
                need(type(coverage) in (int,float) and math.isfinite(coverage) and 0<coverage<=(record_end-rs).total_seconds(),'positive measured eligible coverage required')
                need(record['usable'] is True and record['prospective'] is True and record['coverage_checked'] is True,'only prospective reviewed coverage-checked records count')
                need(record['end_kind'] in {'observed_qualifying_offer','right_censored'},'unknown/unseen outcomes cannot be treated as absence')
                text(record['method_version'],'estimate method version')
                need(record['method_version']!='model=2','rules model=2 is not prediction method')
                text(record['policy_epoch'],'policy epoch')
                artifact_class(record['artifact'])
                if record['send_mode']=='automatic':
                    reporting=p['automatic_reporting_choice']
                    need(reporting['affirmative_save'] is True and type(reporting['value']) is bool and (reporting['value'] is True or reporting['withdrawn_at'] is not None),'separate affirmative automatic-reporting choice required')
                    need(timestamp(reporting['saved_at'],'automatic-report choice',self.now)<=submitted,'automatic report precedes reporting choice')
                    self.refs(reporting['evidence_refs'],{'consent'},'separate automatic reporting choice')
                    need(set(reporting['evidence_refs']).isdisjoint(choice['evidence_refs']),'reporting and study choices need separate evidence')
                    if reporting['withdrawn_at']:
                        need(submitted<=timestamp(reporting['withdrawn_at'],'reporting withdrawal',self.now),'automatic submission after reporting withdrawal')
                else:
                    need(record['send_mode']=='manual','unknown send mode')
                    self.refs(record['send_evidence'],{'consent','participant_checkin'},'manual preview and Send')
                ref=text(record['report_reference'],'report reference')
                need(ref not in report_refs,'duplicate received report reference/retry')
                report_refs.add(ref)
                self.refs(record['evidence_refs'],{'study_record'},'received study record')
                usable.add(code)
        need(primary<=10 and replacements<=5,'at most ten primary and five replacement enrollments')
        bycode={p['code']:p for p in people}
        for p in people:
            if p['replacement_for']:
                former=bycode[p['replacement_for']]
                need(former['seat_ended_at'] is not None and timestamp(former['seat_ended_at'],'replaced seat')<=timestamp(p['enrolled_at'],'replacement admission'),'replacement requires previously closed seat')
        if reviewed:
            need(all(c in qualified and qualified[c]<=reviewed for c in selected),'expansion review must follow all three completed/reviewed sessions')
        occupied=set()
        for when,kind,code in sorted(events):
            if kind==0:
                occupied.discard(code)
            else:
                occupied.add(code)
                cap=10 if reviewed and when>=reviewed else 3
                need(len(occupied)<=cap,'fourth seat before first-three review or more than ten occupied seats')
        for incident in data['incidents']:
            need(incident['severity'] in {'critical','blocking','minor'},'unknown incident severity')
            at=timestamp(incident['observed_at'],'incident',self.now)
            self.refs(incident['evidence_refs'],{'phone_observation','participant_checkin','owner_review'},'incident')
            resolved=timestamp(incident['resolved_at'],'incident resolution',self.now) if incident['resolved_at'] else None
            if resolved:
                need(resolved>=at,'resolution predates incident')
                self.refs(incident['resolution_evidence'],{'owner_review'},'incident resolution')
            if incident['severity']=='blocking' and not resolved:
                self.blocking=True
            if incident['severity']=='critical':
                if not resolved:
                    self.critical=True
                for p in people:
                    admitted=timestamp(p['enrolled_at'],'admission')
                    need(not(at<=admitted and (resolved is None or admitted<resolved)),'admission during critical incident hold')
                    for s in p['ordinary_sessions']:
                        ss=timestamp(s['started_at'],'session')
                        se=timestamp(s['ended_at'],'session end')
                        need(not(se>at and (resolved is None or ss<resolved)),'active testing during critical incident hold')
        return dict(enrolled=len(people),occupied_seats=seats,self_reported_or_verified_installed=installed_count,
                    active_phone_testers=sum(p['status']=='active' for p in people),
                    completed_reviewed_sessions=sum(len(p['ordinary_sessions']) for p in people),
                    usable_study_participants=len(usable),prediction_coverage='INSUFFICIENT' if len(usable)<5 else 'EXPLORATORY_REVIEW_ONLY',
                    first_three_review_recorded=reviewed is not None,remaining_seat_capacity=max(0,(10 if reviewed else 3)-seats),
                    pilot_window_closed=bool(end and self.now>=end))

    def evaluate(self):
        need(self.data['schema_version']==1 and type(self.data['schema_version']) is int,'intake schema must be 1')
        need(self.data['document_type']=='offerfilter_pilot_intake','wrong intake document type')
        need(self.data['evidence_mode'] in {'live','synthetic_fixture'},'explicit evidence mode required')
        binding(self.data['target_artifact'],FROZEN,'target')
        need(set(self.data['gates'])==set(GATES),'all fixed readiness gates required')
        self.load_evidence()
        self.gates={name:self.gate(name,self.data['gates'][name]) for name in GATES}
        self.load_runs()
        cohort=self.participants()
        pending=[g for g,v in self.gates.items() if v is None]
        unresolved_failures=list(self.failures)
        disposition_keys=set()
        for disposition in self.data.get('case_dispositions',[]):
            key=(disposition['run_id'],disposition['case_id'])
            need(key not in disposition_keys,'duplicate failure disposition')
            disposition_keys.add(key)
            original=next((f for f in self.failures if (f['run_id'],f['case_id'])==key),None)
            need(original is not None,'disposition must identify an observed candidate failure')
            need(disposition['owner']==OWNER and disposition['resolution']=='OWNER_ACCEPTED_RETEST','explicit canonical retest acceptance required')
            at=timestamp(disposition['recorded_at'],'failure disposition',self.now)
            retest=next((r for r in self.runs if r['run_id']==disposition['retest_run_id'] and r['artifact']=='frozen_candidate'),None)
            need(retest is not None and disposition['case_id'] in retest['submitted_candidate_passes'],'disposition requires a version-bound passing retest')
            retest_at=timestamp(retest['case_reviewed_at'][disposition['case_id']],'retest review',self.now)
            need(timestamp(original['reviewed_at'],'failure review')<retest_at<=at,'owner disposition must follow failure and retest')
            need(timestamp(original['observed_ended_at'],'failure observation end')<=timestamp(retest['case_started_at'][disposition['case_id']],'retest observation start'),'retest observation must follow the failed observation')
            self.refs(disposition['evidence_refs'],{'owner_review'},'failure disposition')
            unresolved_failures.remove(original)
        failed={f['case_id'] for f in unresolved_failures}
        claims=sorted({c for r in self.runs for c in r['submitted_candidate_passes']} - failed)
        unresolved=[c for c in CASE_IDS[:14] if c not in claims]
        reasons=list(pending)
        if unresolved_failures: reasons.append('observed_candidate_failure_requires_owner_disposition')
        if self.critical: reasons.append('critical_incident_stop')
        if self.blocking: reasons.append('blocking_install_consent_reporting_hold')
        if cohort['pilot_window_closed']: reasons.append('seven_day_closeout_due')
        if self.synthetic: reasons.append('synthetic_fixture_not_live_evidence')
        # Readiness is an advisory summary of recorded owner gates, not a new owner decision.
        return dict(schema_valid=True,evidence_mode=self.data['evidence_mode'],
                    evaluation_at=self.now.isoformat(),target_artifact=FROZEN,
                    recruitment='HELD' if reasons else 'RECORDED_OWNER_CLEARANCE_REQUIRES_HUMAN_EXECUTION',
                    active_testing='STOP' if self.critical else 'HELD_AFFECTED_BUILD_STUDY' if self.blocking else 'SUBJECT_TO_OWNER_GATES',
                    blockers=reasons,phone_runs=self.runs,
                    submitted_candidate_pass_cases=claims,observed_candidate_failures=self.failures,unresolved_candidate_failures=unresolved_failures,owner_accepted_phone_passes='NOT_INFERRED',
                    unresolved_candidate_cases=unresolved,successor_cases={'S01':'BLOCKED','S02':'BLOCKED'},
                    cohort=cohort,gate_status={g:'RECORDED' if v else 'PENDING' for g,v in self.gates.items()},
                    input_sha256=self.hashes,
                    limits=['Hash checks establish file identity, not truth or observer identity.',
                            'Submitted phone PASS remains owner-reviewed evidence; this does not replace the full phone gate.',
                            'No contact, release acceptance, publication, enrollment or automated phone actions are performed.',
                            'Successor artifacts require a separately reviewed policy; public baseline never credits frozen acceptance.',
                            'No prediction performance, causal, dispatch or earnings conclusions are computed.'])

def evaluate_file(path, now=None):
    clock='explicit_replay_timestamp' if now else 'current_utc_evaluation'
    now=now or dt.datetime.now(dt.timezone.utc)
    data=read_json(path)
    intake=Intake(path.parent,data,now)
    result=intake.evaluate()
    result['input_sha256'][path.name]=sha(path)
    result['evaluation_clock']=clock
    result['report_generated_at']=dt.datetime.now(dt.timezone.utc).isoformat()
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('intake',type=Path)
    parser.add_argument('--as-of',help='advisory replay at explicit timezone-bearing timestamp; does not freshly observe any gate' )
    parser.add_argument('--output',type=Path,help='write JSON report; otherwise stdout')
    args=parser.parse_args()
    try:
        now=timestamp(args.as_of,'as-of') if args.as_of else None
        result=evaluate_file(args.intake,now)
        code=0
    except (Invalid,ValueError,KeyError,TypeError,OSError,AttributeError) as exc:
        result=dict(schema_valid=False,recruitment='HELD',active_testing='HELD',error=str(exc),
                    submitted_candidate_pass_cases=[],owner_accepted_phone_passes='NOT_INFERRED')
        code=2
    content=json.dumps(result,indent=2,sort_keys=True,allow_nan=False)+'\n'
    if args.output:
        try:
            with args.output.open('x',encoding='utf-8') as out:
                out.write(content)
        except OSError as exc:
            print(json.dumps(dict(schema_valid=False,recruitment='HELD',error=f'output not written: {exc}')),file=sys.stderr)
            return 2
    else:
        print(content,end='')
    return code

if __name__=='__main__':
    sys.exit(main())
