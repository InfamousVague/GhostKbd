#!/usr/bin/env python3
"""The sounds plugin's packs: each key sound made with ElevenLabs' sound effects (POST /v1/sound-generation), then
trimmed to its first quarter second with ffmpeg so it starts the instant it plays, and kept as Ogg Vorbis in
android/app/src/main/assets/sounds/<pack>/ (key1..3, space, delete, enter). SoundPacks.kt plays them.

The key comes from ELEVENLABS_API_KEY or ~/.elevenlabs-key, never printed. A pack already made is left alone; pass
--again to make every pack anew. Run by .github/workflows/sounds.yml.

Re-run: the ElevenLabs key is now in the Prod environment (2026-11-08).
"""
import json, os, pathlib, subprocess, sys, tempfile, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'android/app/src/main/assets/sounds'

# What each pack's keys sound like: one recording each, close-miked and dry, nothing else in it.
PACKS = {
    'mechanical': 'Cherry MX Blue mechanical keyboard',
    'thock': 'deep thocky lubed linear mechanical keyboard with heavy keycaps',
    'typewriter': 'vintage manual typewriter',
    'bubble': 'soft cartoon bubble',
    'arcade': '8-bit retro video game console',
}
SOUNDS = {
    'key1': 'a single {p} key press, one short crisp click, close mic, dry, no reverb, isolated',
    'key2': 'one {p} key tap, a slightly different single short click, close mic, dry, isolated',
    'key3': 'a single quick {p} keystroke, short click, close mic, dry, isolated',
    'space': 'a single {p} space bar press, a deeper wider single thud, close mic, dry, isolated',
    'delete': 'a single {p} backspace key press, short soft click, close mic, dry, isolated',
    'enter': 'a single {p} enter key press, a firm satisfying single clack, close mic, dry, isolated',
}
# The arcade and bubble packs are sounds rather than keys.
SPECIAL = {
    'bubble': {
        'key1': 'one tiny soft bubble pop, very short, dry, isolated', 'key2': 'a single small water bubble pop, short, dry, isolated',
        'key3': 'one little bubble pop blip, short, dry, isolated', 'space': 'one bigger soft bubble pop, short, dry, isolated',
        'delete': 'a short reverse bubble suck sound, dry, isolated', 'enter': 'a cheerful bright bubble pop with a tiny sparkle, short, dry, isolated',
    },
    'arcade': {
        'key1': 'one short 8-bit chiptune blip, square wave, dry, isolated', 'key2': 'a single short 8-bit tick sound, dry, isolated',
        'key3': 'one short retro video game select blip, dry, isolated', 'space': 'a single short low 8-bit bloop, dry, isolated',
        'delete': 'a short descending 8-bit blip, dry, isolated', 'enter': 'a short bright 8-bit confirm chime, dry, isolated',
    },
}


def key() -> str:
    k = os.environ.get('ELEVENLABS_API_KEY', '').strip()
    if not k:
        f = pathlib.Path.home() / '.elevenlabs-key'
        k = f.read_text().strip() if f.exists() else ''
    if not k:
        sys.exit('No ElevenLabs key: set ELEVENLABS_API_KEY or ~/.elevenlabs-key')
    return k


def generate(api_key: str, text: str) -> bytes:
    body = json.dumps({'text': text, 'duration_seconds': 0.5, 'prompt_influence': 0.7}).encode()
    req = urllib.request.Request('https://api.elevenlabs.io/v1/sound-generation?output_format=mp3_44100_128', data=body,
                                 headers={'xi-api-key': api_key, 'Content-Type': 'application/json', 'Accept': 'audio/mpeg'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def trim(mp3: bytes, dest: pathlib.Path) -> None:
    with tempfile.NamedTemporaryFile(suffix='.mp3') as src:
        src.write(mp3)
        src.flush()
        # Start at the sound (no lead-in silence), keep 250 ms, fade the tail, mono 44.1 kHz.
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src.name, '-af',
                        'silenceremove=start_periods=1:start_threshold=-42dB,atrim=0:0.25,afade=t=out:st=0.17:d=0.08,loudnorm=I=-18:TP=-2',
                        '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', str(dest)], check=True)


def main() -> None:
    again = '--again' in sys.argv
    api_key = key()
    for pack, thing in PACKS.items():
        folder = OUT / pack
        if folder.exists() and len(list(folder.glob('*.ogg'))) == len(SOUNDS) and not again:
            print(f'{pack}: already made')
            continue
        folder.mkdir(parents=True, exist_ok=True)
        for name, prompt in SOUNDS.items():
            text = SPECIAL.get(pack, {}).get(name) or prompt.format(p=thing)
            trim(generate(api_key, text), folder / f'{name}.ogg')
            print(f'{pack}/{name}.ogg')


if __name__ == '__main__':
    main()
