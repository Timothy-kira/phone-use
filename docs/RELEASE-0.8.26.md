# Phone Use 0.8.26

MiniMax Code and Claude Code share the same MCP core, Android APK and version. Host hook manifests remain separate adapters.

- Browser connection/session, actions/forms, and accessibility fallback moved out of the public phone_browser facade. CDP remains the sole browser transport.
- Coordinate taps retain the requested point, report actual native pixels, and keep screen stability, occlusion, protected-window and cancellation checks. Node clicks retain identity checks.
- Read-only observations remain available during replanning. Capability errors do not create app obstacles. Repeated ineffective actions and distinct unverified actions remain bounded; pixels alone never prove goal progress.
- Host permission context comes from launch environment or hooks tied to the CLI process identity. Full access avoids extra plugin questions. Auto/ask/unknown require host approval or user takeover; model flags cannot grant permission. Explicit deny refuses calls. Host-local transport and policy records assume a trusted same-user host; they do not defend against an OS process already able to rewrite the plugin or its environment.
- Sensitive text is redacted and not saved for undo. One policy JSON drives Python/JS and generated Android patterns; run `python audit/sync_interaction_policy.py --check` before packaging.
- Covered elements cannot trigger implicit DOM clicks. Field writes and toggles execute once and report read-back mismatches. Browser refs have document namespaces; session changes clear network/dialog state.

MiniWoB follow-up uses a separate harness: official task/score source unchanged, decimal reward compatibility preserves the official error, strict success remains reward==1. Timestamped visible initialization frames and limited terminal reads are explicitly additional observations. Baseline results are not overwritten. Every follow-up task runs once with a fresh instance; no seed-matched causal claim is warranted.
