# Phone Use 0.8.27

Publishes the shared plugin core and APK already installed for MiniMax Code and Claude Code.
Both host manifests and the APK report version 0.8.27. Host hooks adapt to each CLI; the browser
and Android implementation remain shared.

- Separate Chrome session management, actions/forms and accessibility fallback from the public
  `phone_browser` facade into `browser_session.py`, `browser_actions.py`, `browser_accessibility.py`
  and `browser_common.py`. CDP remains the browser transport.
- Keep coordinate taps at the requested point and report native dispatch coordinates. Preserve
  node identity, screen stability and occlusion checks.
- Verify text and form changes through read-back. A dispatch acknowledgement is not goal success;
  failed verification does not silently replay the write.
- Use one interaction policy across Python, JavaScript and generated Android code. Host permission
  context comes from CLI launch/hooks; model arguments cannot grant host permission.
- Keep read-only observations available during replanning and retain bounded action feedback.

The default-branch evaluation table and audit branch are preserved. This publication synchronizes
an existing release; it does not add AndroidControl results or claim a new evaluation.

Packaging validation covers behavior regressions, browser reference isolation, policy consistency,
source syntax, installed host core equality and the APK embedded version. No new live-device
regression or Android APK rebuild is performed by this publication.
