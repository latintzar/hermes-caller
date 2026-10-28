import os
import sys
from pathlib import Path
from unittest.mock import patch
import pytest
from fastapi.testclient import TestClient
from twilio.request_validator import RequestValidator

sys.path.insert(0, str(Path(__file__).parents[1] / "server"))
from app import app, download_recording

ACCOUNT = "AC" + "a" * 32
RECORDING = "RE" + "b" * 32
BASE = "https://caller.example"
KEY = "z" * 40

@pytest.fixture(autouse=True)
def config(monkeypatch, tmp_path):
    for k, v in {
        "PUBLIC_BASE_URL": BASE, "TWILIO_AUTH_TOKEN": "test-auth",
        "TWILIO_ACCOUNT_SID": ACCOUNT, "TWILIO_CALLER_ID": "+15551234567",
        "CALLER_ACCESS_KEY": KEY, "RECORDINGS_DIR": str(tmp_path),
        "TWILIO_API_KEY_SID": "SK" + "c" * 32, "TWILIO_API_KEY_SECRET": "test-secret",
        "TWILIO_TWIML_APP_SID": "AP" + "d" * 32,
    }.items():
        monkeypatch.setenv(k, v)
    monkeypatch.delenv("STORE_RECORDINGS", raising=False)

@pytest.fixture
def client():
    return TestClient(app)

def signed(path, data, token="test-auth"):
    return {"X-Twilio-Signature": RequestValidator(token).compute_signature(BASE + path, data)}

def test_token_requires_private_key(client):
    assert client.post("/api/caller/token").status_code == 401
    result = client.post("/api/caller/token", headers={"Authorization": "Bearer " + KEY})
    assert result.status_code == 200
    assert result.json()["token"].count(".") == 2

def test_twiml_rejects_forgery_and_returns_outbound_dial(client):
    path = "/api/caller/twiml"
    form = {"AccountSid": ACCOUNT, "To": "+15557654321"}
    assert client.post(path, data=form).status_code == 403
    assert client.post(path, data=form, headers=signed(path, form, "wrong")).status_code == 403
    response = client.post(path, data=form, headers=signed(path, form))
    assert response.status_code == 200
    assert "+15557654321" in response.text
    assert "record-from-answer-dual" in response.text
    invalid = {"AccountSid": ACCOUNT, "To": "<bad>"}
    assert client.post(path, data=invalid, headers=signed(path, invalid)).status_code == 400

def test_cross_account_and_untrusted_origin(client):
    path = "/api/caller/recording-complete"
    cross = {"AccountSid": "AC" + "e"*32, "RecordingSid": RECORDING, "RecordingStatus": "completed"}
    assert client.post(path, data=cross, headers=signed(path, cross)).status_code == 403
    good = dict(cross, AccountSid=ACCOUNT)
    forged_host = RequestValidator("test-auth").compute_signature("https://attacker.example" + path, good)
    assert client.post(path, data=good, headers={"X-Twilio-Signature": forged_host}).status_code == 403
    assert client.post(path + "?x=1", data=good, headers=signed(path, good)).status_code == 400

def test_callback_ignores_external_url_and_requires_opt_in(client, monkeypatch):
    path = "/api/caller/recording-complete"
    form = {"AccountSid": ACCOUNT, "RecordingSid": RECORDING, "RecordingStatus": "completed",
            "RecordingUrl": "https://attacker.example/steal"}
    with patch("app.download_recording") as fetch:
        assert client.post(path, data=form, headers=signed(path, form)).json()["stored"] is False
        fetch.assert_not_called()
        monkeypatch.setenv("STORE_RECORDINGS", "1")
        assert client.post(path, data=form, headers=signed(path, form)).json()["stored"] is True
        fetch.assert_called_once_with(ACCOUNT, RECORDING)
    bad = dict(form, RecordingSid="../../escape")
    assert client.post(path, data=bad, headers=signed(path, bad)).status_code == 400

def test_download_fixed_origin_no_redirect_and_private_permissions(tmp_path):
    captured = {}
    class Response:
        status_code = 200
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def raise_for_status(self): pass
        def iter_bytes(self): yield b"wav"
    class Client:
        def __init__(self, **kwargs): captured.update(kwargs)
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def stream(self, method, url, **kwargs):
            captured.update(method=method, url=url, auth=kwargs["auth"])
            return Response()
    with patch("app.httpx.Client", Client):
        download_recording(ACCOUNT, RECORDING)
    assert captured["follow_redirects"] is False
    assert captured["url"] == f"https://api.twilio.com/2010-04-01/Accounts/{ACCOUNT}/Recordings/{RECORDING}.wav"
    output = tmp_path / (RECORDING + ".wav")
    assert output.read_bytes() == b"wav"
    assert output.stat().st_mode & 0o777 == 0o600
