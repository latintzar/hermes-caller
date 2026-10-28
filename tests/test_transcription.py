import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "server"))
import transcribe

def test_nova3_multichannel_and_chronological_parse(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")
    monkeypatch.delenv("CALL_RECORDER_TX_LANG", raising=False)
    captured = {}
    class Response:
        def raise_for_status(self): pass
        def json(self):
            return {"metadata": {"duration": 4}, "results": {
                "channels": [{"alternatives": [{"transcript": "synthetic caller"}]},
                             {"alternatives": [{"transcript": "synthetic recipient"}]}],
                "utterances": [
                    {"channel": 1, "transcript": "second synthetic line", "start": 2, "end": 3},
                    {"channel": 0, "transcript": "first synthetic line", "start": 0, "end": 1}]}}
    class Client:
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def post(self, url, **kwargs):
            captured.update(url=url, **kwargs)
            return Response()
    with patch("transcribe.httpx.Client", Client):
        response = transcribe._deepgram(b"synthetic wav", dual_channel=True, keyterms=["fictional"])
    params = captured["params"]
    assert ("model", "nova-3") in params
    assert ("language", "multi") in params
    assert ("multichannel", "true") in params
    assert ("keyterm", "fictional") in params
    parsed = transcribe._parse_deepgram(response)
    assert [u["speaker"] for u in parsed["utterances"]] == [0, 1]
    assert [u["start"] for u in parsed["utterances"]] == [0, 2]
    assert parsed["dual_channel"] is True

def test_mono_diarization_and_empty_transcript(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")
    captured = {}
    class Response:
        def raise_for_status(self): pass
        def json(self): return {"results": {}, "metadata": {}}
    class Client:
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def post(self, url, **kwargs):
            captured.update(kwargs)
            return Response()
    with patch("transcribe.httpx.Client", Client):
        data = transcribe._deepgram(b"synthetic wav")
    assert ("diarize", "true") in captured["params"]
    assert transcribe._parse_deepgram(data)["utterances"] == []



def test_dual_channel_rejects_mono_before_transcription(monkeypatch, tmp_path):
    import pytest
    class Probe:
        returncode = 0
        stdout = "1"
    monkeypatch.setattr(transcribe.subprocess, "run", lambda *a, **k: Probe())
    with pytest.raises(ValueError, match="two-channel"):
        transcribe._convert_to_wav(tmp_path / "mono.wav", stereo=True)
