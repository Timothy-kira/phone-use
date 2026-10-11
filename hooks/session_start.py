"""SessionStart hook: if Phone Use is not ready, have the agent offer to set it up.

The same pattern as EverMe's onboarding step: look at the state, and when something is missing,
put an offer in front of the user before any phone work starts - never install on our own. Writes
nothing when Phone Use is ready or when the user already said "don't ask again".

Output is the hookSpecificOutput.additionalContext shape that both Claude Code and MiniMax Code
accept for SessionStart. Any failure exits 0 silently: a hook must never break a session.
"""
from __future__ import annotations

import json
import os
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "mcp"))

BUDGET_SECONDS = 8  # MiniMax caps a handler at 10s

WHAT_SETUP_DOES = {
    "adb": "install Android platform-tools (adb) on this computer",
    "usb_access": "let this Linux computer open the phone over USB (Android udev rules, plugdev group)",
    "device": "help connect the phone and authorize this computer for USB / wireless debugging",

}


def main() -> int:
    try:
        from host_policy import record_permission
        record_permission(json.load(sys.stdin))
    except (OSError, ValueError):
        pass
    from phone_mcp import PhoneLink
    from phone_setup import Setup, load_state

    if "--compact" in sys.argv:
        return compact_recap(PhoneLink)
    if load_state().get("declined"):
        return 0

    holder: dict = {}

    def probe() -> None:
        try:
            holder["result"] = Setup(PhoneLink()).check()
        except Exception as exc:  # noqa: BLE001
            holder["error"] = str(exc)

    worker = threading.Thread(target=probe, daemon=True)
    worker.start()
    worker.join(BUDGET_SECONDS)
    result = holder.get("result")
    if result and result.get("ready"):
        return 0

    if result:
        failed = next((s for s in result["steps"] if not s["ok"]), None)
        todo = ([result["next"]] if result.get("next") else []) + list(result.get("not_checked_yet") or [])
        state_line = (f"Missing: {failed['step']} - {failed['detail'].rstrip('.')}." if failed else "Setup is incomplete.")
        done = [s["step"] for s in result["steps"] if s["ok"]]
        if done:
            state_line += " Already fine: " + ", ".join(done) + "."
        plan = "; ".join(WHAT_SETUP_DOES[s] for s in todo if s in WHAT_SETUP_DOES)
    else:
        state_line = "The setup check did not finish in time; call phone_setup action=check to see what is missing."
        plan = "check what is missing, then install it step by step"

    context = (
        "[phone-use plugin] Phone Use (driving the user's Android phone from this computer over adb) is installed "
        f"as a plugin but is not ready on this machine. {state_line}\n"
        "In your first reply of this session, before any other phone work, ask the user once whether they "
        "want you to set Phone Use up now - in the user's language, with your host's question tool if it has "
        "one (options: set it up now / not now / don't ask again), otherwise in one short question. Say in a "
        f"few words what setup would do: {plan}. Then answer whatever else they asked.\n"
        "- Yes: follow the 'Setup' section of the phone-use skill. For each missing step call phone_setup "
        "action=install step=<step> without confirm to get the exact command, then ask the user with options "
        "(install it for me / I'll do it myself / skip); on 'for me' repeat with confirm=true (admin steps show "
        "the system's own password dialog to the user), on 'myself' give them the command. Steps that need the user's hands (turning on USB debugging, plugging in the phone, "
        "tapping Allow) are theirs - guide them and re-run phone_setup action=check.\n"
        "- Don't ask again: call phone_setup action=decline.\n"
        "- Not now: do nothing; it is offered again next session.\n"
        "Never install anything without the user's explicit yes."
    )
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": context}},
                     ensure_ascii=False))
    return 0


def compact_recap(link_class) -> int:
    """After the host compacted the conversation: put the phone work's state back in front of the agent.

    References (@rN, @pN, @sN, @aN), notes and app maps live on the phone and this computer, not in
    the context, so they survive; the agent only needs reminding of them. Passive contact: a server
    that is not running (or not ours) is left alone, and the agent gets a one-line pointer instead.
    """
    holder: dict = {}

    def fetch() -> None:
        try:
            reply = link_class().rpc("aether-agent-display", "tools/call",
                                     {"name": "agent_display", "arguments": {"action": "recap"}}, timeout=6, passive=True)
            holder["text"] = (((reply.get("result") or {}).get("content") or [{}])[0]).get("text", "")
        except Exception:  # noqa: BLE001
            pass

    worker = threading.Thread(target=fetch, daemon=True)
    worker.start()
    worker.join(BUDGET_SECONDS)
    recap = holder.get("text", "").strip()
    context = (
        "[phone-use plugin] The conversation was compacted. Phone references and notes are kept outside it and "
        "are still valid - keep using the same @rN / @pN / @sN, goto {to:@sN} to return to a screen, refs for details."
        + (f"\nWhere the phone work stands (agent_display recap):\n{recap[:4000]}" if recap else
           "\nCall agent_display {action:\"recap\"} before continuing phone work.")
    )
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": context}},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001
        raise SystemExit(0)
