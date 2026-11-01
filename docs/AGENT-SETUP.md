# Set up Hermes Caller with your agent

Hermes Caller connects an Android Twilio dialler, private call recordings, Deepgram transcripts and an agent-led debrief. Use the [existing setup skill](../skills/hermes-caller/SKILL.md) with this entrypoint.

## Connect calling

Follow [server setup](../README.md#run-the-server) for the Python environment and the exact variable table. The server reads process environment values; your service manager or secret store supplies the private configuration.

Your Twilio account owns the caller ID, Voice API key and TwiML App. Your server owns `PUBLIC_BASE_URL` and the random `CALLER_ACCESS_KEY`; the Android app's **Setup** screen receives that origin and access key. Configure your TwiML App's POST callback as `https://YOUR-HOST/api/caller/twiml`.

Start the existing server from the repository root:

```sh
.venv/bin/uvicorn app:app --app-dir server --host 127.0.0.1 --port 8000
```

Use an HTTPS reverse proxy for Twilio callbacks. Its externally visible origin must match `PUBLIC_BASE_URL`, which the server uses for signature verification. [API reference](api.md) identifies the authenticated app and signed callback routes.

An agent can inspect configuration names/presence, run the existing tests and prepare your Android build before a live call. You choose the recipient and the recording arrangements. `STORE_RECORDINGS=1` enables the server's private recording download; `RECORDINGS_DIR` selects that storage. The current TwiML calling path requests dual-channel recording from Twilio.

## Transcribe and debrief

Use [the existing transcription guide](transcription.md) and your private `DEEPGRAM_API_KEY`. When you choose to process a recording:

```sh
.venv/bin/python server/transcribe.py /absolute/private/recording.wav \
  --dual-channel --output /absolute/private/transcript.json
```

Use `--dual-channel` for a genuine two-channel call recording. The resulting JSON carries timestamped utterances, channel/speaker labels and full text. Mixed recordings use the documented diarization path.

Give Hermes or your chosen agent access to the selected private transcript and [discovery-call-coach skill](../skills/discovery-call-coach/SKILL.md). Ask it to produce a debrief using the conversation evidence and your private discovery context. This is the published manual transcript-to-coach handoff. The operator configures that agent and its model account; transcription and coaching are separate actions from calling.

## Verify the result

Run [the offline test command](../README.md#tests) first. A successful HTTP health check verifies server reachability; authenticated token creation, a consented test call, recording download, transcription and the debrief each provide their own result. Keep recordings, tokens and customer context in operator-controlled storage.

[Architecture](architecture.md) · [Source server](../server/app.py) · [Transcription source](../server/transcribe.py) · [MIT licence](../LICENSE)
