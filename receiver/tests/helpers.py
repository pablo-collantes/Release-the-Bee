"""Shared bits for the receiver tests. Standard library only: no ffmpeg, whisper,
sherpa-onnx or LLM is needed (processing is switched off or faked)."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def adts_frame(payload=b"\x00" * 20, sr_index=8):
    """One AAC-LC ADTS frame (16 kHz mono by default) carrying `payload`."""
    n = 7 + len(payload)
    head = bytes([
        0xFF, 0xF1,
        (1 << 6) | (sr_index << 2),               # profile LC, sample rate, channel high bit 0
        (1 << 6) | ((n >> 11) & 3),               # mono
        (n >> 3) & 0xFF,
        ((n & 7) << 5) | 0x1F,
        0xFC,                                     # one raw data block
    ])
    return head + payload


def adts(frames):
    """`frames` ADTS frames: frames * 64 ms of "audio" at 16 kHz."""
    return b"".join(adts_frame() for _ in range(frames))


class Args:
    """Stand-in for the receiver's argparse namespace."""
    token = None
    whisper_url = whisper_cli = whisper_model = llm_url = sherpa_dir = None
    ffmpeg = "ffmpeg-not-installed-for-tests"
    language = "auto"
    speaker_model = "x.onnx"
    speakers = 0
    speaker_threshold = 0.5
    llm_model = "test"
    llm_key = None
    llm_context = 32768
    llm_chunk = 0
    finish_after = 600
    on_session_end = None
    no_process = True

    def __init__(self, **kw):
        self.__dict__.update(kw)
