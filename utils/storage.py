import json
import os
import threading

DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")

_lock = threading.Lock()

os.makedirs(DATA_DIR, exist_ok=True)


def _path(guild_id: int) -> str:
    return os.path.join(DATA_DIR, f"{guild_id}.json")


def load_guild_data(guild_id: int) -> dict:
    path = _path(guild_id)
    if not os.path.exists(path):
        return {}
    with _lock:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)


def save_guild_data(guild_id: int, data: dict) -> None:
    path = _path(guild_id)
    with _lock:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
