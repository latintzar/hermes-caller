from unittest.mock import patch
import pytest
from test_caller import ACCOUNT, RECORDING
from app import download_recording

def test_download_refuses_redirect(monkeypatch, tmp_path):
    monkeypatch.setenv("TWILIO_AUTH_TOKEN", "test-auth")
    monkeypatch.setenv("RECORDINGS_DIR", str(tmp_path))
    requests = []
    class Response:
        status_code = 302
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def raise_for_status(self): pass
    class Client:
        def __init__(self, **kwargs):
            assert kwargs["follow_redirects"] is False
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def stream(self, method, url, **kwargs):
            requests.append(url)
            return Response()
    with patch("app.httpx.Client", Client), pytest.raises(ValueError):
        download_recording(ACCOUNT, RECORDING)
    assert len(requests) == 1
    assert list(tmp_path.iterdir()) == []
