# API reference

All app routes require `Authorization: Bearer <access key>`. Twilio webhooks require a valid `X-Twilio-Signature`, the configured account SID, and an exact configured HTTPS origin. Query strings are rejected.

| Method and path | Purpose |
| --- | --- |
| GET /healthz | Public process health |
| POST /api/caller/token | Issue a one-hour outgoing Voice token |
| POST /api/caller/twiml | Signed Twilio request returns outbound dial and dual-channel recording instructions |
| POST /api/caller/recording-complete | Signed callback optionally downloads a completed recording |
| POST /api/caller/call-status | Acknowledge signed call completion |

The callback handler uses the recording SID rather than the supplied recording URL. Storage is opt-in; files are named by SID.
