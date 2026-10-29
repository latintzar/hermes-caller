# An Android Twilio dialer with call recording and transcription

Hermes Caller is an Android dialer for Twilio Voice. It grew from a founder’s customer-discovery calling tool, with dual-channel recording and an opt-in Deepgram Nova-3 workflow for multilingual transcripts and speaker separation.

Calling, recording and turning conversations into useful discovery notes involves several tools. A dedicated dialer can keep the call and its recording connected to a controlled transcription workflow.

| Feature | What it does |
| --- | --- |
| Native Android calling | Use Twilio’s Voice SDK with a short-lived token from your own server. |
| Private server setup | Configure the app’s HTTPS server and access key on the device. |
| Dual-channel recordings | Retain each call’s two channels when recording is enabled. |
| Deepgram transcripts | Run the opt-in Nova-3 multilingual and speaker parsing workflow. |
| Coaching handoff | Pass transcript files to a separately configured coaching workflow. |

## Workflow

Android dialer → Own Twilio service → Optional call recording → Opt-in transcription → Discovery notes or coaching

## Design decisions

- The Android app requests short-lived call tokens; provider credentials stay on the server.
- Recording and transcription are explicit operational choices with their own configuration.

## Status and next steps

The app and Python server source are published, with a downloadable debug-signed Android preview. It starts with an empty server configuration. Real calls use your account, caller identity and consent arrangements; the release documents its tested scope. Configure an owned HTTPS server and Twilio account, then validate a consented test call and optional transcription.

Source: [docs/architecture.md](../docs/architecture.md).
