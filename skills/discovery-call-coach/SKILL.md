---
name: discovery-call-coach
description: Review an operator-provided discovery call transcript and produce a concise evidence-based reflection.
---

# Discovery call coach

Read the operator's private context and the JSON transcript produced by `server/transcribe.py` when explicitly asked. Treat diarization labels as provisional; a genuine dual-channel recording maps the caller and recipient to their channels. Use the timestamped utterances to distinguish the person's actual words from interpretation.

Reflect on the specific evidence, a concrete pain or objection volunteered by the participant, and one useful follow-up question. Separate observation from inference and say when the transcript is insufficient. Avoid invented quotes and invented speaker identities.

This is a manual handoff. Keep context and transcripts private. Do not place calls, send messages, create calendar entries, or invoke a model provider without the operator's instruction and configured account.
