import importlib.util, json, tempfile, unittest, os
from pathlib import Path
from unittest.mock import patch

SCRIPT=Path(__file__).resolve().parents[1]/'app/src/main/assets/rc1-migration.py'
spec=importlib.util.spec_from_file_location('rc1_migration',SCRIPT); mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)

class Migration(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='dsha-rc1-');self.root=Path(self.temp.name);self.dsh=self.root/'.dsh';self.dsh.mkdir();self.state=self.root/'host-state'
        self.runtime_version=json.loads((SCRIPT.parent/'runtime-patches.json').read_text())['dshVersion']
        (self.dsh/'runtime-patches.json').write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        (self.dsh/'settings.yaml').write_text('llm: {model: legacy}\n',encoding='utf8')
        (self.dsh/'sessions').mkdir();(self.dsh/'sessions/session.v3.jsonl').write_text('{"version":3}\n',encoding='utf8')
    def tearDown(self):self.temp.cleanup()
    def prepare(self):return mod.prepare(str(self.root),str(self.state),'boot')
    def finalize(self):return mod.finalize(str(self.root),str(self.state),'boot')
    def folder(self):return self.state/'generations'/json.loads((self.state/'current.json').read_text())['generation']
    def doc(self):return json.loads((self.folder()/'prepare.json').read_text())
    def result(self):return json.loads((self.folder()/'receipt.json').read_text())
    def verified(self, startup_id=None):
        doc=self.doc();current=json.loads((self.state/'current.json').read_text());source=next((row for row in doc.get('sources',[]) if row.get('kind')=='settings'),None)
        # 新写入者的账本自带期望分节集合；旧账本缺该字段时由 ledger_state 保守分类。
        (self.folder()/'settings-result.json').write_text(json.dumps({'version':2,'generation':doc['generation'],'sourceSha256':source['sha256'] if source else None,'startupId':startup_id or current.get('startupId'),'status':'verified','expectedSections':['first'],'sections':{'first':{'status':'verified'}}}))
    def capture(self,fn,*args):
        import contextlib,io
        buffer=io.StringIO()
        with contextlib.redirect_stdout(buffer):code=fn(*args)
        lines=[line for line in buffer.getvalue().splitlines() if line.startswith('DSHA_RC1_MIGRATION=')]
        self.assertEqual(len(lines),1,buffer.getvalue())
        return code,json.loads(lines[0][len('DSHA_RC1_MIGRATION='):])
    def test_new_records_and_host_checkpoint_use_deployed_runtime_version(self):
        self.assertEqual(self.prepare(),0);self.verified();self.assertEqual(self.finalize(),0)
        self.assertEqual(self.doc()['dshVersion'],self.runtime_version)
        self.assertEqual(self.result()['dshVersion'],self.runtime_version)
        self.assertEqual(self.result()['runtimeDshVersion'],self.runtime_version)
        self.assertEqual(json.loads((self.folder()/'reuse.json').read_text())['dshVersion'],self.runtime_version)
    def test_missing_or_wrong_deployed_registry_never_borrows_version_from_old_receipt(self):
        registry=self.dsh/'runtime-patches.json';registry.unlink()
        with self.assertRaisesRegex(ValueError,'REGISTRY_UNAVAILABLE'):self.prepare()
        self.assertFalse(self.state.exists());self.assertTrue((self.dsh/'settings.yaml').is_file())
        for value in ({'schema':1,'dshVersion':False},{'schema':2,'dshVersion':self.runtime_version},{'schema':1,'dshVersion':'unknown'}):
            registry.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError,'REGISTRY_VERSION'):self.prepare()
        self.assertFalse(self.state.exists())
    def test_old_committed_generation_does_not_become_new_runtime_host_cache(self):
        registry=self.dsh/'runtime-patches.json';registry.write_text(json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2'}))
        self.prepare();self.verified();self.finalize();old=self.folder()
        prepared=(old/'prepare.json').read_bytes();receipt=(old/'receipt.json').read_bytes()
        (old/'reuse.json').unlink()
        registry.write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('version change must not replay or rescan old business sources')):
            self.assertEqual(self.prepare(),0);self.assertEqual(self.finalize(),0)
        self.assertEqual(old,self.folder());self.assertEqual(prepared,(old/'prepare.json').read_bytes())
        self.assertEqual(receipt,(old/'receipt.json').read_bytes());self.assertFalse((old/'reuse.json').exists())
    def test_old_pending_finishes_same_generation_without_relabeling_old_proof(self):
        registry=self.dsh/'runtime-patches.json';registry.write_text(json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2'}))
        self.prepare();self.finalize();old=self.folder();self.assertEqual(self.result()['status'],'pending')
        registry.write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        self.prepare();self.verified();self.assertEqual(self.finalize(),0)
        self.assertEqual(old,self.folder());self.assertEqual(self.result()['status'],'committed')
        self.assertEqual(self.result()['dshVersion'],'0.2.0-rc.2')
        self.assertEqual(self.result()['runtimeDshVersion'],self.runtime_version)
        self.assertFalse((old/'reuse.json').exists())
    def test_runtime_registry_link_does_not_supply_version(self):
        registry=self.dsh/'runtime-patches.json';registry.unlink()
        external=self.root/'external-registry.json';external.write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        self.link(external,registry)
        with self.assertRaisesRegex(ValueError,'REGISTRY_TYPE'):self.prepare()
        self.assertFalse(self.state.exists())
    def test_real_pending_sequence_never_commits_without_settings_readback(self):
        self.assertEqual(self.prepare(),0);(self.dsh/'settings.yaml').rename(self.dsh/'settings.yaml.imported')
        self.assertEqual(self.finalize(),0);self.assertEqual(self.result()['status'],'pending')
        for _ in range(3):
            self.assertEqual(self.prepare(),0);self.assertFalse((self.dsh/'settings.yaml').exists());self.finalize();self.assertEqual(self.result()['status'],'pending')
        self.verified();self.finalize();self.assertEqual(self.result()['status'],'committed');self.assertEqual(self.result()['sessionsStatus'],'preserved-awaiting-runtime-open')
    def test_new_input_after_commit_has_new_generation(self):
        self.prepare();first=self.folder();self.verified();self.finalize();(self.dsh/'settings.yaml').write_text('llm: {model: other}\n')
        self.prepare();self.assertNotEqual(first,self.folder());self.assertTrue(first.is_dir())
    def test_both_settings_inputs_require_exact_match_and_valid_hashes(self):
        root={'path':'/root/.dsh','device':'1','inode':'2'}
        stamp=lambda inputs:dict(dataRoot=root,inputs=inputs)
        a='a'*64;b='b'*64
        self.assertTrue(mod.same_input(stamp({'settings.yaml':a}),stamp({'settings.yaml.imported':a})))
        self.assertFalse(mod.same_input(stamp({'settings.yaml':a}),stamp({'settings.yaml':a,'settings.yaml.imported':a})))
        self.assertFalse(mod.same_input(stamp({'settings.yaml':a,'settings.yaml.imported':b}),stamp({'settings.yaml':a,'settings.yaml.imported':a})))
        self.assertTrue(mod.same_input(stamp({'settings.yaml':a,'settings.yaml.imported':b}),stamp({'settings.yaml':a,'settings.yaml.imported':b})))
        self.assertFalse(mod.same_input(stamp({'settings.yaml':'invalid'}),stamp({'settings.yaml.imported':'invalid'})))
        self.assertFalse(mod.same_input(stamp({'unknown':a}),stamp({'unknown':a})))
    def test_restore_epoch_same_bytes_has_new_generation(self):
        self.prepare();first=self.folder();(self.dsh/'.dsha-rc1-restore-generation').write_text('restore-operation-2');self.prepare();self.assertNotEqual(first,self.folder())
    def test_completed_ordinary_prepare_does_not_scan_sessions(self):
        self.prepare();self.verified();self.finalize()
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('ordinary startup must not enumerate')):self.prepare()
    def test_reuse_checkpoint_only_follows_complete_same_generation_receipt(self):
        self.prepare();self.finalize();self.assertFalse((self.folder()/'reuse.json').exists())
        self.verified();self.finalize();checkpoint=json.loads((self.folder()/'reuse.json').read_text())
        self.assertEqual(checkpoint['inputs'],self.doc()['inputs']);self.assertEqual(checkpoint['generation'],self.result()['generation'])
        self.assertEqual(checkpoint['status'],'prepared');self.assertTrue(checkpoint['protectionComplete']);self.assertNotIn('sources',checkpoint)
        self.assertEqual(checkpoint['settingsSha256'],next(row for row in self.doc()['sources'] if row['kind']=='settings')['sha256'])
        self.assertLess((self.folder()/'reuse.json').stat().st_size,4096)
        (self.folder()/'reuse.json').unlink();self.finalize();self.assertTrue((self.folder()/'reuse.json').is_file())
        (self.folder()/'reuse.json').write_text('{broken')
        code,payload=self.capture(self.finalize);self.assertEqual(code,0);self.assertEqual(payload['status'],'historical-complete')
        self.assertEqual(json.loads((self.folder()/'reuse.json').read_text()),checkpoint)
        bad=dict(self.result(),sourcePreserved=False)
        (self.folder()/'receipt.json').write_text(json.dumps(bad));(self.folder()/'reuse.json').unlink()
        self.assertFalse(mod.publish_reuse(self.folder(),self.doc(),json.loads((self.state/'current.json').read_text()),bad,self.runtime_version))
        self.assertFalse((self.folder()/'reuse.json').exists())
    def test_partial_or_legacy_committed_receipt_is_rechecked_instead_of_upgraded(self):
        self.prepare();self.verified();self.finalize();(self.folder()/'reuse.json').unlink()
        receipt=self.result();receipt['version']=1;(self.folder()/'receipt.json').write_text(json.dumps(receipt))
        (self.folder()/self.doc()['sources'][0]['snapshot']).write_text('damaged')
        self.assertEqual(self.finalize(),1);self.assertFalse((self.folder()/'reuse.json').exists())
    def test_missing_source_and_changed_session_are_source_clues_not_copy_failure(self):
        self.prepare();(self.dsh/'settings.yaml').unlink();(self.dsh/'sessions/session.v3.jsonl').write_text('changed')
        code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertEqual(payload['contentCopyStatus'],'verified');self.assertEqual(payload['settingsImportStatus'],'unknown')
        self.assertTrue(any(x.startswith('SOURCE_MISSING_OR_CHANGED:') for x in payload['warnings']))
        self.assertEqual(self.result()['status'],'pending')
    def test_snapshot_modified_detected_even_source_unchanged(self):
        self.prepare();(self.folder()/self.doc()['sources'][0]['snapshot']).write_text('changed');self.assertEqual(self.finalize(),1)
    def test_source_replaced_with_same_bytes_keeps_content_copy_and_unknown_object_identity(self):
        self.prepare();self.verified();source=self.dsh/'settings.yaml';value=source.read_text();source.unlink();source.write_text(value)
        code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertEqual(payload['contentCopyStatus'],'verified');self.assertEqual(payload['sourceObjectIdentity'],'unknown')
        self.assertNotIn('sourcePreserved',payload);self.assertTrue(any(x.startswith('SOURCE_IDENTITY_CHANGED:') for x in payload['warnings']))
        self.assertEqual(self.result()['status'],'committed');self.assertEqual(self.result()['sourcePreserved'],True)
    def test_snapshot_io_error_blocks_prepared_and_never_creates_current(self):
        with patch.object(mod,'snapshot_row',side_effect=OSError('disk full')):self.assertEqual(self.prepare(),1)
        self.assertFalse((self.state/'current.json').exists());self.assertTrue((self.dsh/'settings.yaml').is_file())
    def test_low_space_preflight_does_not_copy_or_create_generation(self):
        with patch.object(mod.shutil,'disk_usage',return_value=type('Space',(),{'free':0})()),patch.object(mod,'snapshot_row',side_effect=AssertionError('must preflight before copy')):
            self.assertEqual(self.prepare(),1)
        self.assertFalse((self.state/'generations').exists());self.assertFalse((self.state/'current.json').exists())
    def test_same_complete_inventory_reuses_verified_failed_snapshots(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        generations=[];counts=[]
        with patch.object(mod.shutil,'copyfile',side_effect=OSError('candidate disk write unavailable')):
            for _ in range(3):
                self.assertEqual(self.prepare(),1)
                folder=next((self.state/'generations').iterdir());doc=json.loads((folder/'prepare.json').read_text());generations.append(doc['generation']);counts.append(len(list((folder/'snapshots').iterdir())))
        self.assertEqual(len(set(generations)),1);self.assertEqual(len(set(counts)),1)
        self.assertEqual(self.prepare(),0);self.assertEqual(self.doc()['generation'],generations[0])
    def test_failed_inventory_identity_changes_start_new_generation_without_deleting_old(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        with patch.object(mod.shutil,'copyfile',side_effect=OSError('candidate failure')):self.assertEqual(self.prepare(),1)
        old=next((self.state/'generations').iterdir());(self.dsh/'sessions/session.v3.jsonl').write_text('new contents')
        self.assertEqual(self.prepare(),0);self.assertNotEqual(old,self.folder());self.assertTrue(old.is_dir())
    def test_file_and_byte_budget_are_errors_not_partial_success(self):
        with patch.object(mod,'MAX_FILES',1):self.assertEqual(self.prepare(),1)
        with patch.object(mod,'MAX_BYTES',1):self.assertEqual(self.prepare(),1)
    def test_legacy_committed_receipt_is_not_authority(self):
        folder=self.dsh/'.dsha-rc1-migration';folder.mkdir();(folder/'receipt.json').write_text('{"version":1,"status":"committed","sourcePreserved":true}')
        self.prepare();self.assertEqual(self.doc()['version'],2);self.assertTrue((self.folder()/'legacy-receipt.json').exists())
    def test_preset_source_and_candidate_remain_independent(self):
        preset=self.dsh/'.agent-presets/crew';preset.mkdir(parents=True);(preset/'agent.cordis.yml').write_text('- id: old\n')
        self.prepare();candidate=self.dsh/self.doc()['presets'][0]['candidate'];self.assertFalse((candidate/'bundle').exists());(preset/'agent.cordis.yml').write_text('changed')
        self.assertIsNone(self.doc()['presets'][0]['bundle']);self.assertFalse(self.doc()['presets'][0]['activated'])
        self.assertEqual((candidate/'agent.cordis.yml').read_text(),'- id: old\n')
        code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertTrue(any(x.startswith('SOURCE_CHANGED:') for x in payload['warnings']));self.assertEqual(payload['contentCopyStatus'],'verified')
    def test_preset_yaml_remains_raw_for_explicit_native_ast_conversion(self):
        preset=self.dsh/'.agent-presets/crew';preset.mkdir(parents=True)
        original='---\n# preserved comment\n- id: old\n  name: plugin\n  config: &shared\n    prompt: |\n      user: prompt\n- id: next\n  name: plugin\n  config: *shared\n'
        (preset/'agent.cordis.yml').write_text(original,encoding='utf8');self.assertEqual(self.prepare(),0)
        candidate=self.dsh/self.doc()['presets'][0]['candidate']
        self.assertEqual((candidate/'agent.cordis.yml').read_text(encoding='utf8'),original)
        self.assertFalse((candidate/'bundle').exists());self.assertEqual((preset/'agent.cordis.yml').read_text(encoding='utf8'),original)
    def test_preset_root_metadata_is_snapshotted_without_becoming_directory_candidate(self):
        presets=self.dsh/'.agent-presets';presets.mkdir();(presets/'index.json').write_text('{"selected":"crew"}')
        (presets/'notes').write_text('keep this unknown root file')
        crew=presets/'crew';crew.mkdir();(crew/'agent.cordis.yml').write_text('- id: old\n')
        self.assertEqual(self.prepare(),0)
        doc=self.doc();self.assertEqual(len(doc['presets']),1);self.assertEqual(doc['presets'][0]['id'],'crew')
        rows={row['path']:row for row in doc['sources']}
        for name,value in [('index.json','{"selected":"crew"}'),('notes','keep this unknown root file')]:
            self.assertEqual((self.folder()/rows['.agent-presets/'+name]['snapshot']).read_text(),value)
            self.assertEqual((presets/name).read_text(),value)
            self.assertIn('PRESET_ROOT_FILE_PRESERVED:.agent-presets/'+name,doc['warnings'])
        self.assertEqual(len(list((self.folder()/'snapshots').iterdir())),len(doc['sources']))
    def test_post_snapshot_copy_failure_records_exact_operation_and_path(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        with patch.object(mod.shutil,'copyfile',side_effect=IsADirectoryError(21,'is a directory')):
            self.assertEqual(self.prepare(),1)
        folder=next((self.state/'generations').iterdir());doc=json.loads((folder/'prepare.json').read_text())
        self.assertEqual(doc['failure']['operation'],'preset-candidate-copy')
        self.assertEqual(doc['failure']['path'],'.agent-presets/crew/agent.cordis.yml')
        self.assertIn('IsADirectoryError',doc['failure']['traceback'])
        self.assertFalse(doc['protectionComplete']);self.assertFalse((self.state/'current.json').exists())
        self.assertEqual(len(list((folder/'snapshots').iterdir())),len(doc['sources']))
        self.assertEqual((crew/'agent.cordis.yml').read_text(),'- id: old\n')
    def test_directory_settings_has_typed_source_error(self):
        settings=self.dsh/'settings.yaml';settings.unlink();settings.mkdir();(settings/'keep').write_text('original')
        with self.assertRaises(mod.MigrationSourceError) as raised:self.prepare()
        self.assertEqual(str(raised.exception),'MIGRATION_SOURCE_NOT_REGULAR')
        self.assertEqual(raised.exception.operation,'settings-input')
        self.assertEqual(raised.exception.logical,str(settings))
        self.assertFalse((self.state/'current.json').exists());self.assertEqual((settings/'keep').read_text(),'original')
    def test_directory_never_passes_snapshot_source_guard(self):
        folder=self.root/'candidate';folder.mkdir();directory=self.dsh/'directory';directory.mkdir()
        with self.assertRaisesRegex(mod.MigrationSourceError,'SOURCE_NOT_REGULAR'):
            mod.snapshot_row(folder,self.dsh,directory,directory,[],'preset',0)
        self.assertFalse((folder/'snapshots/0').exists())
    def test_stale_finalize_cannot_commit_new_startup(self):
        self.prepare();self.assertEqual(mod.finalize(str(self.root),str(self.state),'old-boot'),1);self.assertFalse((self.folder()/'receipt.json').exists())
    def test_historical_prepare_is_explicit_scope_without_current_claims(self):
        self.prepare();self.verified();self.finalize()
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('ordinary startup must not enumerate')):
            code,payload=self.capture(self.prepare)
        self.assertEqual(code,0);self.assertEqual(payload['resultVersion'],3);self.assertEqual(payload['phase'],'prepare')
        self.assertEqual(payload['status'],'historical-complete');self.assertEqual(payload['verificationScope'],'historical')
        self.assertEqual(payload['contentCopyStatus'],'verified');self.assertEqual(payload['sourceObjectIdentity'],'unknown')
        self.assertEqual(payload['settingsImportStatus'],'verified-historical');self.assertTrue(payload['cached'])
        self.assertEqual(payload['executionState'],'NOT_SENT');self.assertNotIn('protectionComplete',payload)
        self.assertNotIn('sourcePreserved',payload)
    def test_historical_finalize_after_committed_receipt_reports_history_and_single_receipt(self):
        self.prepare();self.verified();self.finalize();(self.folder()/'reuse.json').write_text('{broken')
        code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'historical-complete');self.assertEqual(payload['phase'],'finalize')
        self.assertEqual(payload['verificationScope'],'historical');self.assertEqual(payload['settingsImportStatus'],'verified-historical')
        self.assertNotIn('protectionComplete',payload);self.assertNotIn('sourcePreserved',payload)
        self.assertTrue(any('REUSE_CHECKPOINT_FORMAT' in line for line in payload.get('warnings',[])))
    def test_missing_settings_ledger_cannot_be_historical_success(self):
        self.prepare();self.verified();self.finalize();(self.folder()/'settings-result.json').unlink()
        code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'historical-complete')
        self.assertEqual(payload['settingsImportStatus'],'unknown');self.assertTrue(any('SETTINGS_LEDGER_MISSING' in line for line in payload['warnings']))
    def test_writing_or_unknown_ledger_never_commits(self):
        self.prepare()
        doc=self.doc();current=json.loads((self.state/'current.json').read_text());source=next(row for row in doc['sources'] if row['kind']=='settings')
        ledger=dict(version=2,generation=doc['generation'],sourceSha256=source['sha256'],startupId=current['startupId'],status='pending',sections={'first':{'status':'writing'}})
        (self.folder()/'settings-result.json').write_text(json.dumps(ledger))
        code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertEqual(payload['status'],'pending');self.assertEqual(payload['settingsImportStatus'],'unknown')
        self.assertEqual(payload['verificationScope'],'current');self.assertEqual(self.result()['settingsStatus'],'unknown')
        self.assertNotEqual(self.result()['status'],'committed')
    def test_lost_current_index_with_completed_generation_is_unknown_not_new_migration(self):
        self.prepare();self.verified();self.finalize();generations=list((self.state/'generations').iterdir())
        (self.state/'current.json').unlink()
        code,payload=self.capture(self.prepare)
        self.assertEqual(code,1);self.assertEqual(payload['status'],'unknown');self.assertEqual(payload['reason'],'HOST_STATE_MISSING')
        self.assertEqual(generations,list((self.state/'generations').iterdir()));self.assertFalse((self.state/'current.json').exists())
    def test_incomplete_generation_retries_same_generation_without_relabeling(self):
        with patch.object(mod,'snapshot_row',side_effect=OSError('disk full')):self.assertEqual(self.prepare(),1)
        self.assertTrue((self.state/'pending.json').is_file());folder=next((self.state/'generations').iterdir())
        self.assertEqual(self.prepare(),0);self.assertEqual(folder,self.folder())
    def test_verify_existing_reads_copies_in_current_scope_without_writes(self):
        self.prepare();self.verified();self.finalize()
        before={path.name:path.read_bytes() for path in (self.folder()).iterdir() if path.is_file()}
        current_before=(self.state/'current.json').read_bytes()
        code,payload=self.capture(mod.verify_existing,str(self.root),str(self.state),'audit')
        self.assertEqual(code,0);self.assertEqual(payload['phase'],'verify-existing');self.assertEqual(payload['status'],'historical-complete')
        self.assertEqual(payload['verificationScope'],'current');self.assertEqual(payload['contentCopyStatus'],'verified')
        self.assertEqual(payload['sourceObjectIdentity'],'unknown');self.assertEqual(payload['settingsImportStatus'],'verified-historical')
        self.assertEqual(current_before,(self.state/'current.json').read_bytes())
        for name,value in before.items():self.assertEqual(value,(self.folder()/name).read_bytes())
        snapshot=next(row for row in self.doc()['sources'] if row['kind']=='settings')['snapshot']
        (self.folder()/snapshot).write_text('damaged')
        code,payload=self.capture(mod.verify_existing,str(self.root),str(self.state),'audit')
        self.assertEqual(code,1);self.assertEqual(payload['contentCopyStatus'],'failed');self.assertNotEqual(payload['status'],'historical-complete')
    def test_stale_startup_binding_is_unknown_and_no_receipt(self):
        self.prepare()
        code,payload=self.capture(mod.finalize,str(self.root),str(self.state),'old-boot')
        self.assertEqual(code,1);self.assertEqual(payload['status'],'unknown');self.assertEqual(payload['reason'],'STALE_STARTUP_BINDING')
        self.assertEqual(payload['executionState'],'COMPLETED');self.assertFalse((self.folder()/'receipt.json').exists())
    def test_no_second_protocol_receipt_on_any_path(self):
        self.prepare();self.verified();self.finalize()
        import contextlib,io
        for fn,args in [(self.prepare,()),(self.finalize,()),(mod.verify_existing,(str(self.root),str(self.state),'audit'))]:
            buffer=io.StringIO()
            with contextlib.redirect_stdout(buffer):fn(*args)
            lines=[line for line in buffer.getvalue().splitlines() if line.startswith('DSHA_RC1_MIGRATION=')]
            self.assertEqual(len(lines),1,buffer.getvalue())
    def test_historical_prepare_requires_committed_receipt(self):
        self.prepare()  # 首次 prepare 后、CJS/finalize 前崩溃：只有 prepared。
        code,payload=self.capture(self.prepare)
        self.assertEqual(code,0);self.assertNotEqual(payload['status'],'historical-complete')
        self.assertEqual(payload['status'],'pending');self.assertIn(payload['reason'],('LEGACY_COMPLETION_RECEIPT_MISSING','SETTINGS_LEDGER_MISSING'))
        self.assertFalse((self.folder()/'receipt.json').exists())
    def test_pending_receipt_is_not_historical_complete(self):
        self.prepare();self.finalize();self.assertEqual(self.result()['status'],'pending')
        code,payload=self.capture(self.prepare)
        self.assertEqual(code,0);self.assertNotEqual(payload['status'],'historical-complete')
        self.assertIn(payload['status'],('pending','unknown'))
    def test_committed_receipt_without_ledger_is_unknown_not_historical(self):
        self.prepare();self.verified();self.finalize();(self.folder()/'settings-result.json').unlink()
        code,payload=self.capture(self.prepare)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'unknown');self.assertEqual(payload['reason'],'SETTINGS_LEDGER_MISSING')
    def test_history_only_rewrites_crashed_write_allowed_context(self):
        self.prepare()
        current=json.loads((self.state/'current.json').read_text());self.assertEqual(current['startupContext']['mode'],'write-allowed')
        self.capture(self.prepare)
        current=json.loads((self.state/'current.json').read_text());self.assertEqual(current['startupContext']['mode'],'history-only')
    def test_warnings_are_strictly_bounded(self):
        self.assertEqual(len(mod.bounded_warnings([str(i) for i in range(32)])),32)
        bounded=mod.bounded_warnings([str(i) for i in range(40)])
        self.assertEqual(len(bounded),32);self.assertEqual(bounded[-1],'WARNINGS_TRUNCATED')
    def test_ledger_expected_sections_and_writing_are_unknown(self):
        folder=self.root/'ledger-check';folder.mkdir()
        sha='a'*64
        doc=dict(generation='g',sources=[{'kind':'settings','sha256':sha}])
        def write(ledger):(folder/'settings-result.json').write_text(json.dumps(ledger))
        write({'generation':'g','sourceSha256':sha,'status':'verified','expectedSections':['a','b'],'sections':{'a':{'status':'verified'}}})
        self.assertEqual(mod.ledger_state(folder,doc)[0],'unknown')
        write({'generation':'g','sourceSha256':sha,'status':'verified','expectedSections':['a'],'sections':{'a':{'status':'verified'}}})
        self.assertEqual(mod.ledger_state(folder,doc),('verified',None))
        write({'generation':'g','sourceSha256':sha,'status':'verified','sections':{'a':{'status':'verified'}}})
        self.assertEqual(mod.ledger_state(folder,doc),('unknown','SETTINGS_EXPECTED_SECTIONS_MISSING'))
        write({'generation':'g','sourceSha256':sha,'status':'writing','sections':{'a':{'status':'verified'}}})
        self.assertEqual(mod.ledger_state(folder,doc)[0],'unknown')
    def test_legacy_ledger_without_trusted_expected_sections_is_unknown(self):
        folder=self.root/'ledger-missing-expected';folder.mkdir();sha='b'*64
        doc=dict(generation='g',sources=[{'kind':'settings','sha256':sha}])
        def write(ledger):(folder/'settings-result.json').write_text(json.dumps(ledger))
        base=dict(generation='g',sourceSha256=sha,startupId='boot',status='verified')
        # 空/部分/全部 verified，但既无 expectedSections 也无 checkpoint：一律不能自证完成。
        for sections in ({},{'a':{'status':'verified'}},{'a':{'status':'verified'},'b':{'status':'verified'}}):
            write(dict(base,sections=sections))
            self.assertEqual(mod.ledger_state(folder,doc),('unknown','SETTINGS_EXPECTED_SECTIONS_MISSING'),sections)
        # 账本自带期望集合且一致 → verified；部分 → unknown；期望集合结构损坏 → unknown。
        write(dict(base,expectedSections=['a','b'],sections={'a':{'status':'verified'},'b':{'status':'verified'}}))
        self.assertEqual(mod.ledger_state(folder,doc),('verified',None))
        write(dict(base,expectedSections=['a','b'],sections={'a':{'status':'verified'}}))
        self.assertEqual(mod.ledger_state(folder,doc),('unknown','SETTINGS_SECTION_MISSING'))
        write(dict(base,expectedSections=['a','a'],sections={'a':{'status':'verified'}}))
        self.assertEqual(mod.ledger_state(folder,doc),('unknown','SETTINGS_LEDGER_SECTION'))
        # 无 settings 来源仍是 not-required，不受期望集合影响。
        self.assertEqual(mod.ledger_state(folder,dict(generation='g',sources=[])),('not-required',None))
    def test_legacy_ledger_only_uses_same_generation_checkpoint_expectation(self):
        folder=self.root/'ledger-checkpoint';folder.mkdir();sha='c'*64
        doc=dict(generation='g',sources=[{'kind':'settings','sha256':sha}])
        ledger=dict(generation='g',sourceSha256=sha,startupId='boot',status='verified',
                    sections={'a':{'status':'verified'},'b':{'status':'verified'}})
        (folder/'settings-result.json').write_text(json.dumps(ledger))
        def checkpoint(value):(folder/'reuse.json').write_text(json.dumps(value) if not isinstance(value,str) else value)
        checkpoint({'generation':'g','settingsSha256':sha,'settingsSections':['a','b']})
        self.assertEqual(mod.ledger_state(folder,doc,checkpoint=mod.reuse_checkpoint(folder)),('verified',None))
        # 期望集合只覆盖部分分节 → 不能判完成。
        checkpoint({'generation':'g','settingsSha256':sha,'settingsSections':['a']})
        self.assertEqual(mod.ledger_state(folder,doc,checkpoint=mod.reuse_checkpoint(folder)),('unknown','SETTINGS_SECTION_MISSING'))
        # 代次/摘要不符、结构非法、损坏的 checkpoint 都不能当独立期望集合。
        for value in ({'generation':'other','settingsSha256':sha,'settingsSections':['a','b']},
                      {'generation':'g','settingsSha256':'d'*64,'settingsSections':['a','b']},
                      {'generation':'g','settingsSha256':sha,'settingsSections':'a,b'},
                      {'generation':'g','settingsSha256':sha,'settingsSections':['a','a']},
                      '{broken'):
            checkpoint(value)
            self.assertEqual(mod.ledger_state(folder,doc,checkpoint=mod.reuse_checkpoint(folder)),('unknown','SETTINGS_EXPECTED_SECTIONS_MISSING'),value)
    def test_historical_prepare_without_trusted_expected_sections_is_unknown(self):
        self.prepare();self.verified();self.finalize()
        ledger=self.folder()/'settings-result.json'
        value=json.loads(ledger.read_text());value.pop('expectedSections',None);ledger.write_text(json.dumps(value))
        # 模拟更早版本的代次：既没有账本期望集合，也没有同代次 checkpoint 证据。
        (self.folder()/'reuse.json').unlink()
        code,payload=self.capture(self.prepare)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'unknown')
        self.assertEqual(payload['reason'],'SETTINGS_EXPECTED_SECTIONS_MISSING')
        self.assertEqual(payload['settingsImportStatus'],'unknown')
        self.assertNotIn('protectionComplete',payload)
    def link(self,target,link,directory=False):
        try:os.symlink(target,link,target_is_directory=directory)
        except OSError as error:self.skipTest('host cannot create symlink: '+str(error.winerror if hasattr(error,'winerror') else error.errno))
    def test_public_link_copies_bytes_and_rejects_escape(self):
        external=self.root/'approved';external.mkdir();source=external/'settings';source.write_text('llm: {model: linked}\n');(self.dsh/'settings.yaml').unlink();self.link(source,self.dsh/'settings.yaml')
        self.assertEqual(mod.prepare(str(self.root),str(self.state),'boot',[str(external)]),0);row=self.doc()['sources'][0];snapshot=self.folder()/row['snapshot'];self.assertFalse(snapshot.is_symlink());source.write_text('later');self.assertEqual(snapshot.read_text(),'llm: {model: linked}\n')
        with self.assertRaisesRegex(ValueError,'OUTSIDE'):mod.Resolver(self.dsh).resolve(self.dsh/'settings.yaml')
    def test_linked_session_root_is_traversed(self):
        external=self.root/'approved';external.mkdir();(external/'session.v2.jsonl').write_text('old');(self.dsh/'sessions/session.v3.jsonl').unlink();(self.dsh/'sessions').rmdir();self.link(external,self.dsh/'sessions',True)
        mod.prepare(str(self.root),str(self.state),'boot',[str(external)]);self.assertEqual(len(self.doc()['sessions']),1)

if __name__=='__main__':unittest.main()
