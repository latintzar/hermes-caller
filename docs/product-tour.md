# Hermes Caller product tour

Hermes Caller connects a Twilio phone call to a diarised transcript and a discovery debrief. Its public package makes each handoff explicit so the call, recording and agent stay under your control.

[Download Android preview](https://github.com/latintzar/hermes-caller/releases/download/v5.8-preview/hermes-caller-v5.8-debug.apk) · [Configure your server](getting-started.md) · [Website](https://atelierneyra.com/hermes-caller)

## A call through to reflection

Configure your HTTPS calling server and Twilio account using the [setup guide](getting-started.md). Install the Android preview or your own build. In **Setup**, save the server origin and private app access key.

Enter an E.164 phone number and tap **Call** for a consented test call. The app requests a short-lived token; Twilio’s Android SDK connects the call and obtains dial instructions from the server. During the call, mute, speaker and hang-up controls remain in the native app.

When recording storage is enabled, the signed completion callback identifies a recording. The server downloads the two-channel WAV to its private directory. Run the [transcription command](getting-started.md#transcribe-a-recording) when you want to send it to Deepgram. The result is a private JSON file containing `full_text`, `duration_sec` and timestamped `utterances`.

Give that file to Hermes or an agent using the [discovery-call-coach skill](../skills/discovery-call-coach/SKILL.md). It reflects on what the person actually said, identifies a concrete pain or objection and proposes one useful follow-up question. The result is an evidence-based debrief connected to the call’s exact utterances.

## Product areas and source

| Area | Action and result | Module |
| --- | --- | --- |
| Setup and dialler | Save the service address/key, enter a number and call | [MainActivity.kt](../android/app/src/main/kotlin/dev/latintzar/hermescaller/MainActivity.kt), [Api.kt](../android/app/src/main/kotlin/dev/latintzar/hermescaller/Api.kt) |
| In-call controls | Mute, switch speaker and hang up | [OutboundCall.kt](../android/app/src/main/kotlin/dev/latintzar/hermescaller/OutboundCall.kt), [foreground service](../android/app/src/main/kotlin/dev/latintzar/hermescaller/CallForegroundService.kt) |
| Calling server | Issue tokens, return TwiML and handle signed callbacks | [server/app.py](../server/app.py), [API guide](api.md) |
| Recording and transcript | Store the selected recording, convert audio and parse Nova-3 output | [server/app.py](../server/app.py), [server/transcribe.py](../server/transcribe.py) |
| Debrief | Review the supplied transcript with your configured agent | [coach skill](../skills/discovery-call-coach/SKILL.md), [handoff guide](transcription.md) |

## Speaker separation

For a genuine dual-channel Twilio recording, `--dual-channel` preserves channel 0 as caller and channel 1 as the other party. A mixed recording instead uses diarization: speaker numbers mark turns and need identity confirmation. The default transcription language is `multi`; optional keyterms help with known names. [Transcription](transcription.md) records the output fields and exact choices.

## Design decisions and reached stage

The phone receives short-lived Voice tokens while provider credentials stay on the server. Recording storage and transcription are separate choices; the recording callback and transcription command keep their own results. The server validates Twilio signatures and account identity, then constructs its recording-download URL from validated SIDs. [Architecture](architecture.md) explains the boundary.

The public beta exposes the dialler and setup experience, server, transcript command and generic coach. The original private prospect system and Hermes context remain with their owners. A complete public debrief uses the included handoff with the agent you configure. [Product record](PROJECT.md), [setup](getting-started.md) and [offline tests](getting-started.md#tests) describe the available release and the connected checks to make with your own accounts.
