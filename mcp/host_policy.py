"""Trusted host policy supplied by launch environment or host hooks, never tool arguments.

Hook records are bound to the CLI process's PID and creation time. Permission is read
on every call so a host mode change does not leave a cached full-access grant.
"""
from __future__ import annotations
import ctypes
import json
import os
from pathlib import Path

MODES = {'full': 'full', 'bypassPermissions': 'full', 'auto': 'auto', 'smart': 'auto',
         'default': 'ask', 'ask': 'ask', 'deny': 'deny', 'plan': 'deny', 'acceptEdits': 'ask'}

def normalize(value):
    return MODES.get(str(value or ''), 'unknown')

def host_identity():
    """Nearest supported CLI ancestor; no cwd-based or cross-session grants."""
    try:
        pid = os.getppid()
        if os.name == 'nt':
            from ctypes import wintypes as w
            class Entry(ctypes.Structure):
                _fields_ = [('size',w.DWORD),('usage',w.DWORD),('pid',w.DWORD),('heap',ctypes.c_size_t),
                            ('module',w.DWORD),('threads',w.DWORD),('parent',w.DWORD),('priority',w.LONG),
                            ('flags',w.DWORD),('exe',w.WCHAR*260)]
            k = ctypes.WinDLL('kernel32', use_last_error=True)
            k.CreateToolhelp32Snapshot.restype = w.HANDLE
            k.OpenProcess.restype = w.HANDLE
            k.CloseHandle.argtypes = [w.HANDLE]
            k.Process32FirstW.argtypes = [w.HANDLE,ctypes.POINTER(Entry)]
            k.Process32NextW.argtypes = [w.HANDLE,ctypes.POINTER(Entry)]
            k.GetProcessTimes.argtypes = [w.HANDLE] + [ctypes.POINTER(w.FILETIME)]*4
            snapshot = k.CreateToolhelp32Snapshot(2,0)
            entry = Entry(); entry.size = ctypes.sizeof(Entry)
            parents = {}
            try:
                more = k.Process32FirstW(snapshot,ctypes.byref(entry))
                while more:
                    parents[entry.pid] = (entry.parent,entry.exe.lower())
                    more = k.Process32NextW(snapshot,ctypes.byref(entry))
            finally:
                k.CloseHandle(snapshot)
            for _ in range(20):
                parent, name = parents.get(pid,(0,''))
                if name in ('node.exe','mcode.exe','claude.exe','codex.exe'):
                    handle = k.OpenProcess(0x1000,False,pid)
                    if not handle: return None
                    times = [w.FILETIME() for _ in range(4)]
                    try:
                        if not k.GetProcessTimes(handle,*[ctypes.byref(t) for t in times]): return None
                        return f'{pid}-{times[0].dwHighDateTime:x}{times[0].dwLowDateTime:08x}'
                    finally: k.CloseHandle(handle)
                if not parent or parent == pid: break
                pid = parent
        else:
            for _ in range(20):
                stat = Path(f'/proc/{pid}/stat').read_text()
                fields = stat[stat.rfind(')')+2:].split()
                name = Path(f'/proc/{pid}/comm').read_text().strip()
                if name in ('node','mcode','claude','codex'):
                    return f'{pid}-{fields[19]}'
                pid = int(fields[1])
                if pid < 2: break
    except (OSError,ValueError,AttributeError):
        pass
    return None

def policy_path(identity):
    return Path.home() / '.phone-use/host-policy' / (identity + '.json')

def record_permission(payload):
    identity = host_identity()
    if identity and isinstance(payload,dict) and 'permission_mode' in payload:
        p = policy_path(identity); p.parent.mkdir(parents=True,exist_ok=True)
        temp = p.with_suffix(f'.{os.getpid()}.tmp')
        temp.write_text(json.dumps({'mode':normalize(payload['permission_mode']),
                                    'session':payload.get('session_id','')}),encoding='utf-8')
        os.replace(temp,p)

def permission_mode():
    identity = host_identity()
    if identity:
        try:
            return normalize(json.loads(policy_path(identity).read_text(encoding='utf-8'))['mode'])
        except (OSError,ValueError,KeyError): pass
    return normalize(os.environ.get('PHONE_USE_PERMISSION_MODE'))

def require_sensitive():
    mode = permission_mode()
    return None if mode == 'full' else {'ok':False,'dispatched':False,
        'code':'HOST_PERMISSION_DENIED' if mode == 'deny' else 'HOST_APPROVAL_REQUIRED',
        'host_permission':mode,'errmsg':'Host approval is required for this sensitive action. Model flags cannot grant permission.'}
