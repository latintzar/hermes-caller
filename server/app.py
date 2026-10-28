"""Portable calling service derived from the original Hermes caller."""
import hmac
import os
import re
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from twilio.jwt.access_token import AccessToken
from twilio.jwt.access_token.grants import VoiceGrant
from twilio.request_validator import RequestValidator
from twilio.twiml.voice_response import VoiceResponse

app = FastAPI()
SID = re.compile(r"^[A-Za-z]{2}[0-9a-fA-F]{32}$")
E164 = re.compile(r"^\+[1-9][0-9]{7,14}$")
WEBHOOKS = {"/api/caller/twiml", "/api/caller/recording-complete", "/api/caller/call-status"}

def setting(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing {name}")
    return value

def public_base():
    base = setting("PUBLIC_BASE_URL").rstrip("/")
    u = urlsplit(base)
    if u.scheme != "https" or not u.hostname or u.username or u.password or u.query or u.fragment or u.path:
        raise RuntimeError("PUBLIC_BASE_URL must be an HTTPS origin")
    return base

def valid_sid(value, prefix):
    return bool(SID.fullmatch(value)) and value.startswith(prefix)

@app.middleware("http")
async def protect(request, call_next):
    if request.url.path == "/healthz":
        return await call_next(request)
    if request.url.path in WEBHOOKS:
        # A fixed configured origin prevents Host / forwarded-header poisoning.
        if request.method != "POST" or request.url.query:
            return JSONResponse({"error": "invalid request"}, 400)
        try:
            await request.body()
            form = await request.form()
            signature = request.headers.get("X-Twilio-Signature", "")
            validator = RequestValidator(setting("TWILIO_AUTH_TOKEN"))
            ok = validator.validate(public_base() + request.url.path, form, signature)
            account_ok = hmac.compare_digest(str(form.get("AccountSid", "")), setting("TWILIO_ACCOUNT_SID"))
            if not ok or not account_ok:
                return JSONResponse({"error": "unauthorized"}, 403)
        except (RuntimeError, ValueError):
            return JSONResponse({"error": "configuration error"}, 503)
        return await call_next(request)
    try:
        expected = setting("CALLER_ACCESS_KEY")
    except RuntimeError:
        return JSONResponse({"error": "configuration error"}, 503)
    if len(expected) < 32:
        return JSONResponse({"error": "configuration error"}, 503)
    supplied = request.headers.get("Authorization", "")
    if not hmac.compare_digest(supplied.encode(), ("Bearer " + expected).encode()):
        return JSONResponse({"error": "unauthorized"}, 401)
    return await call_next(request)

@app.get("/healthz")
async def health():
    return {"ok": True}

@app.post("/api/caller/token")
async def token():
    try:
        token = AccessToken(setting("TWILIO_ACCOUNT_SID"), setting("TWILIO_API_KEY_SID"),
                            setting("TWILIO_API_KEY_SECRET"), identity="caller", ttl=3600)
        token.add_grant(VoiceGrant(outgoing_application_sid=setting("TWILIO_TWIML_APP_SID"), incoming_allow=False))
        return {"token": token.to_jwt(), "identity": "caller"}
    except RuntimeError:
        return JSONResponse({"error": "configuration error"}, 503)

@app.post("/api/caller/twiml")
async def twiml(request: Request):
    form = await request.form()
    to = str(form.get("To", "")).strip()
    caller_id = setting("TWILIO_CALLER_ID")
    if not E164.fullmatch(to) or not E164.fullmatch(caller_id):
        return JSONResponse({"error": "invalid number"}, 400)
    base = public_base()
    response = VoiceResponse()
    dial = response.dial(caller_id=caller_id, record="record-from-answer-dual",
        recording_status_callback=base + "/api/caller/recording-complete",
        recording_status_callback_method="POST",
        action=base + "/api/caller/call-status", method="POST", timeout=30)
    dial.number(to)
    return Response(str(response), media_type="application/xml")

def download_recording(account, recording):
    # Never use RecordingUrl supplied by the webhook. Do not forward auth on redirects.
    url = f"https://api.twilio.com/2010-04-01/Accounts/{account}/Recordings/{recording}.wav"
    directory = Path(setting("RECORDINGS_DIR")).expanduser()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    target = directory / (recording + ".wav")
    if target.exists():
        return
    with httpx.Client(follow_redirects=False, timeout=60) as client:
        with client.stream("GET", url, auth=(account, setting("TWILIO_AUTH_TOKEN"))) as response:
            response.raise_for_status()
            if response.status_code != 200:
                raise ValueError("unexpected recording response")
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                size = 0
                with os.fdopen(fd, "wb") as output:
                    for chunk in response.iter_bytes():
                        size += len(chunk)
                        if size > 50 * 1024 * 1024:
                            raise ValueError("recording exceeds size limit")
                        output.write(chunk)
            except BaseException:
                target.unlink(missing_ok=True)
                raise

@app.post("/api/caller/recording-complete")
async def recording_complete(request: Request):
    form = await request.form()
    account = str(form.get("AccountSid", ""))
    recording = str(form.get("RecordingSid", ""))
    if not valid_sid(account, "AC") or not valid_sid(recording, "RE"):
        return JSONResponse({"error": "invalid SID"}, 400)
    if str(form.get("RecordingStatus", "")) != "completed":
        return {"ok": True, "stored": False}
    # Explicit opt-in avoids unexpected downloads or provider usage.
    if os.environ.get("STORE_RECORDINGS") != "1":
        return {"ok": True, "stored": False}
    from starlette.concurrency import run_in_threadpool
    await run_in_threadpool(download_recording, account, recording)
    return {"ok": True, "stored": True}

@app.post("/api/caller/call-status")
async def call_status():
    return {"ok": True}
