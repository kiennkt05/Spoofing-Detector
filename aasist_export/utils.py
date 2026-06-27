"""
Minimal utils module — only what models/AASIST.py needs for ONNX export.
Matches the standard AASIST repo's str_to_bool implementation.
"""


def str_to_bool(value: str) -> bool:
    if isinstance(value, bool):
        return value
    if value.lower() in ("yes", "true", "t", "1"):
        return True
    if value.lower() in ("no", "false", "f", "0"):
        return False
    raise ValueError(f"Cannot convert '{value}' to bool")
