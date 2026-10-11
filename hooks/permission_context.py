"""PreToolUse: refresh the current trusted host permission without approving tools."""
import json
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1] / 'mcp'))
from host_policy import record_permission
try:
    record_permission(json.load(sys.stdin))
except (OSError,ValueError,TypeError):
    pass
