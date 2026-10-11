"""Behavior regressions for the shared browser execution and host permission policy."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'mcp'))
from phone_browser import PhoneBrowser
from browser_common import BrowserError
import host_policy

class Regression(unittest.TestCase):
    def browser(self):
        b=PhoneBrowser(Mock(),Mock(),Mock(),Mock())
        b.call_pu=Mock()
        b.touch=Mock()
        b.js=Mock()
        b.tab=Mock(return_value=Mock())
        return b
    def test_covered_click_never_dispatches_dom_or_touch(self):
        b=self.browser();b.call_pu.return_value={'ok':True,'hit':False,'w':30,'h':20,'covered_by':'overlay'}
        with self.assertRaises(BrowserError) as e:b.press(12)
        self.assertEqual(e.exception.code,'TARGET_OCCLUDED')
        b.touch.assert_not_called();b.js.assert_not_called()
    def test_checkbox_is_toggled_once_and_requires_readback(self):
        b=self.browser();b.press=Mock(return_value='touch');b.call_pu.return_value=False
        result=b.fill_field({'type':'checkbox','ref':'e12','checked':False},True)
        self.assertEqual(result['status'],'rejected');b.press.assert_called_once_with(12);b.js.assert_not_called()
    def test_text_mismatch_is_not_replayed_or_reported_success(self):
        b=self.browser();b.field_meta=Mock(return_value='normal')
        b.call_pu.side_effect=lambda fn,*args: {'sensitive':False,'box':{'ok':True,'hit':True},'focusAndSelect':{'ok':True,'active':True},'fireChange':True,'valueOf':'short'}[fn]
        result=b.type_into(12,'exact trailing space ')
        self.assertFalse(result['ok']);self.assertEqual(result['code'],'TEXT_NOT_APPLIED')
        self.assertEqual(b.tab.return_value.call.call_count,1)
        self.assertNotIn('setValue',[c.args[0] for c in b.call_pu.call_args_list])
    def test_exact_trailing_space_is_preserved(self):
        b=self.browser();b.field_meta=Mock(return_value='normal');value='copy exactly '
        b.call_pu.side_effect=lambda fn,*args: {'sensitive':False,'box':{'ok':True,'hit':True},'focusAndSelect':{'ok':True,'active':True},'fireChange':True,'valueOf':value}[fn]
        self.assertTrue(b.type_into(1,value)['text_verified'])
        self.assertEqual(b.tab.return_value.call.call_args.args[1]['text'],value)
    def test_fallback_uses_one_shared_set(self):
        b=self.browser();b.text_call=Mock(return_value={'ok':True,'text_verified':True});b.display_call=Mock()
        self.assertTrue(b.a11y_fill('#3','exact ')['ok'])
        b.text_call.assert_called_once_with({'action':'set','query':'#3','text':'exact '});b.display_call.assert_not_called()
    def test_drop_session_clears_network_and_dialog(self):
        b=self.browser();b.requests={'old':{}};b.inflight={'old'};b.dialog={'x':1};b.drop_session()
        self.assertEqual(b.requests,{});self.assertEqual(b.inflight,set());self.assertIsNone(b.dialog)
    def test_snapshot_and_extract_dispatch_reference(self):
        b=self.browser()
        b.call_pu.return_value={'ok':True,'text':'visible','url':'https://example.test','interactive':1}
        self.assertIn('visible',b.do_snapshot({'ref':'e12'})['page'])
        self.assertEqual(b.call_pu.call_args.args[1]['root'],12)
        b.call_pu.return_value={'ok':True,'content':'visible','url':'https://example.test'}
        self.assertIn('visible',b.do_extract({'ref':'e13'})['content'])
        self.assertEqual(b.call_pu.call_args.args[2],13)
    def test_wait_dispatches_reference(self):
        b=self.browser();b.call_pu.side_effect=[True,{'url':'https://example.test'}]
        self.assertTrue(b.do_wait_for({'ref':'e14'})['ok'])
        self.assertEqual(b.call_pu.call_args_list[0].args,('waitCheck',{'ref':14}))
    def test_policy_modes_and_refresh(self):
        with patch.object(host_policy,'host_identity',return_value=None):
            for mode,allowed in [('full',True),('auto',False),('default',False),('deny',False),('',False)]:
                with self.subTest(mode=mode),patch.dict(os.environ,{'PHONE_USE_PERMISSION_MODE':mode}):
                    self.assertEqual(host_policy.require_sensitive() is None,allowed)
        with tempfile.TemporaryDirectory() as d,patch.object(host_policy,'host_identity',return_value='session'),patch.object(host_policy,'policy_path',return_value=Path(d)/'policy.json'):
            host_policy.record_permission({'permission_mode':'bypassPermissions'})
            self.assertEqual(host_policy.permission_mode(),'full')
            host_policy.record_permission({'permission_mode':'auto'})
            self.assertEqual(host_policy.permission_mode(),'auto')
    def test_sensitive_model_flag_cannot_grant_permission(self):
        b=self.browser();b.field_meta=Mock(return_value='password');b.call_pu.return_value=True
        with patch('host_policy.permission_mode',return_value='auto'):
            self.assertEqual(b.type_into(1,'secret')['code'],'HOST_APPROVAL_REQUIRED')
            b.tab.assert_not_called()

if __name__=='__main__':unittest.main()
