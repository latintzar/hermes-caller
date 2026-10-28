# Architecture

The Android app uses the Twilio Voice SDK and the original dialer, foreground service and calling-account code. Server connection settings are entered on the device and stored in private app preferences. No key is embedded in a shared build.

The server validates the app bearer key for API requests. Twilio callbacks are checked using the auth token and the configured public origin, then matched to the configured account SID. The callback's recording URL is ignored: the server builds an HTTPS URL at api.twilio.com from validated account and recording SIDs, and does not follow redirects. Downloads are disabled unless explicitly enabled. Stored files use restrictive permissions and a 50 MiB limit.

The original private app included prospect, discovery and transcription systems tied to its operating environment. This beta presents the dialer and setup screens; those systems are not part of the portable server. Transcription, summaries, and contact enrichment are not provided. Calls and recordings require a configured provider account; offline tests use mocks.
