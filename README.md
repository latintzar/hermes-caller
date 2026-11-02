# Hermes Caller

A Twilio dialler with diarised transcripts and Hermes debriefs

Hermes Caller is an Android dialler that connects a customer-discovery call to its recording, transcript and debrief. Twilio places the call, Deepgram Nova-3 produces a multilingual speaker-separated transcript, and Hermes uses that transcript for reflection and follow-up questions.

[Website](https://atelierneyra.com/hermes-caller) · [Download Android preview](https://github.com/latintzar/hermes-caller/releases/download/v5.8-preview/hermes-caller-v5.8-debug.apk) · [Product tour](docs/product-tour.md) · [Documentation](https://docs.atelierneyra.com/hermes-caller/overview/)

![Hermes Caller](https://atelierneyra.com/assets/product-motion-v5/hermes-caller-2f04d2b4f42a.jpg)

[Phone artwork credit](docs/assets/handset-CREDITS.md)

## Call to debrief

1. Enter your HTTPS server and access key in the Android app’s **Setup** screen.
2. Dial an international number and use the in-call mute, speaker and hang-up controls.
3. With recording enabled, your server stores Twilio’s dual-channel recording privately.
4. Run the opt-in Nova-3 transcription command to produce timestamped utterances.
5. Give that JSON to Hermes or an agent using the included discovery-call-coach skill.

The [product tour](docs/product-tour.md) follows that handoff and shows the source behind each stage.

## Product areas

| Area | Result |
| --- | --- |
| Android dialler | Twilio Voice calling with short-lived tokens and native call controls |
| Private service | App authentication, signed TwiML/callbacks and optional recording storage |
| Transcription | Nova-3 multilingual utterances with timestamps and channel/speaker labels |
| Debrief | A transcript handoff and generic discovery-call-coach skill |

The source release includes the Android app, Python server, transcription command and generic coach skill. The debug-signed preview starts with empty server settings. The original private Hermes coach and account-specific discovery systems are separate from this portable package; the public debrief uses your configured agent and the transcript file.

## Run the server

The [setup guide](docs/getting-started.md#run-the-server) lists the exact Twilio fields, HTTPS setup, Android build and optional recording configuration. Calling uses your Twilio account; transcription uses your Deepgram account. Recording and provider requests are explicit operations.

[Set up with your agent](docs/AGENT-SETUP.md) · [Transcription and coach handoff](docs/transcription.md)

## Source

| Module | Source |
| --- | --- |
| Android screens and calls | [MainActivity.kt](android/app/src/main/kotlin/dev/latintzar/hermescaller/MainActivity.kt), [OutboundCall.kt](android/app/src/main/kotlin/dev/latintzar/hermescaller/OutboundCall.kt) |
| Tokens, TwiML and recording callbacks | [server/app.py](server/app.py) |
| Audio conversion and speaker-separated transcript | [server/transcribe.py](server/transcribe.py) |
| Debrief workflow | [discovery-call-coach](skills/discovery-call-coach/SKILL.md) |

[Architecture](docs/architecture.md) · [API](docs/api.md) · [Product record](docs/PROJECT.md)

## Tests

The [test guide](docs/getting-started.md#tests) covers local authentication, signed callbacks, account isolation and transcript parsing. Its provider responses are mocked; live calls and transcription use your configured accounts.

Original project code is [MIT licensed](LICENSE); dependencies retain their [own terms](THIRD_PARTY.md).

[made by atelier neyra](https://atelierneyra.com)
