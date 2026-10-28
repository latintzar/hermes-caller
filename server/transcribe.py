"""Opt-in transcription extracted from the original call-recorder pipeline."""
from __future__ import annotations
import json
import logging
import os
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any, Optional
import httpx

logger = logging.getLogger("caller.transcribe")
DEEPGRAM_URL = "https://api.deepgram.com/v1/listen"
DEEPGRAM_TIMEOUT = 180.0

def _convert_to_wav(src: Path, *, stereo: bool=False) -> bytes:
    if stereo:
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
             "stream=channels", "-of", "default=noprint_wrappers=1:nokey=1", str(src)],
            capture_output=True, text=True, timeout=30,
        )
        if probe.returncode != 0 or probe.stdout.strip() != "2":
            raise ValueError("dual-channel mode requires a two-channel recording")
    channels = '2' if stereo else '1'
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
        out = Path(tmp.name)
    try:
        result = subprocess.run(['ffmpeg', '-y', '-i', str(src), '-ar', '16000', '-ac', channels, str(out)], capture_output=True, timeout=300)
        if result.returncode != 0:
            raise RuntimeError('ffmpeg could not convert the recording')
        return out.read_bytes()
    finally:
        out.unlink(missing_ok=True)

def _deepgram(audio_wav: bytes, *, dual_channel: bool=False, keyterms: Optional[list[str]]=None) -> dict[str, Any]:
    key = (os.getenv('DEEPGRAM_API_KEY') or '').strip()
    if not key:
        raise RuntimeError('DEEPGRAM_API_KEY not set')
    lang = (os.getenv('CALL_RECORDER_TX_LANG') or 'multi').strip() or 'multi'
    params: dict[str, str] = {'model': 'nova-3', 'language': lang, 'punctuate': 'true', 'utterances': 'true', 'smart_format': 'true', 'paragraphs': 'true'}
    if dual_channel:
        params['multichannel'] = 'true'
    else:
        params['diarize'] = 'true'
    headers = {'Authorization': f'Token {key}', 'Content-Type': 'audio/wav'}
    keyterm_params = [('keyterm', k) for k in keyterms or [] if k.strip()]

    def _post(extra: list[tuple[str, str]]):
        with httpx.Client(timeout=DEEPGRAM_TIMEOUT) as client:
            resp = client.post(DEEPGRAM_URL, params=list(params.items()) + extra, headers=headers, content=audio_wav)
            resp.raise_for_status()
            return resp
    t0 = time.time()
    r = _post(keyterm_params)
    elapsed = time.time() - t0
    data = r.json()
    data['_elapsed'] = elapsed
    data['_dual_channel'] = dual_channel
    return data

def _parse_deepgram(data: dict) -> dict[str, Any]:
    dual_channel: bool = bool(data.get('_dual_channel'))
    results = (data or {}).get('results', {})
    metadata = (data or {}).get('metadata', {})
    model_info = metadata.get('model_info') or {}
    detected_lang = next(iter(model_info.values()), {}).get('language', '') if model_info else ''
    raw_utt = results.get('utterances') or []
    utterances: list[dict] = []
    speakers: set[int] = set()
    channels = results.get('channels') or []
    full_text_parts = []
    for ch in channels:
        alt_text = ((ch.get('alternatives') or [{}])[0].get('transcript') or '').strip()
        if alt_text:
            full_text_parts.append(alt_text)
    full_text = ' '.join(full_text_parts) if full_text_parts else ((channels[0].get('alternatives') or [{}])[0].get('transcript') or '').strip() if channels else ''
    for u in raw_utt:
        if dual_channel:
            speaker = int(u.get('channel', 0))
        else:
            speaker = int(u.get('speaker', 0))
        speakers.add(speaker)
        utterances.append({'speaker': speaker, 'text': (u.get('transcript') or '').strip(), 'start': float(u.get('start', 0.0)), 'end': float(u.get('end', 0.0))})
    utterances.sort(key=lambda u: u['start'])
    if not utterances and full_text:
        utterances = [{'speaker': 0, 'text': full_text, 'start': 0.0, 'end': 0.0}]
        speakers = {0}
    duration = float(metadata.get('duration', 0.0)) or max((u['end'] for u in utterances), default=0.0)
    return {'full_text': full_text, 'lang': detected_lang or '', 'utterances': utterances, 'num_speakers': len(speakers), 'duration_sec': duration, 'asr_seconds': float(data.get('_elapsed', 0.0)), 'dual_channel': dual_channel}

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Transcribe an operator-provided recording with Deepgram Nova-3")
    parser.add_argument("audio", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="new private JSON output file")
    parser.add_argument("--dual-channel", action="store_true", help="Twilio caller/callee channels")
    parser.add_argument("--keyterm", action="append", default=[])
    args = parser.parse_args()
    if not os.environ.get("DEEPGRAM_API_KEY", "").strip():
        parser.error("DEEPGRAM_API_KEY is required")
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            audio = _convert_to_wav(args.audio, stereo=args.dual_channel)
            parsed = _parse_deepgram(_deepgram(audio, dual_channel=args.dual_channel, keyterms=args.keyterm))
            json.dump(parsed, out, ensure_ascii=False, indent=2)
            out.write("\n")
    except BaseException:
        args.output.unlink(missing_ok=True)
        raise

if __name__ == "__main__":
    main()
