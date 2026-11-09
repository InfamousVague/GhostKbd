#!/usr/bin/env python3
"""The sounds plugin's packs, made with ElevenLabs' sound effects (POST /v1/sound-generation) and written as Ogg Vorbis
to android/app/src/main/assets/sounds/<pack>/ (key1..3, space, delete, enter), with every pack's name in
assets/sounds/packs.json. SoundPacks.kt plays them.

How (v3, 2026-11-08): ElevenLabs is far better at a recording of someone typing than at one isolated click, which
came out exaggerated and alike from board to board. So for each pack it is asked for a few seconds of slow typing on
that board (and of its space bar, enter key and backspace), described the way keyboard people describe switches:
what the switch is, the keycaps and case, and the sound they make. The keystrokes are then cut out of each recording
by their onsets, the most typical ones kept (not the loudest), each trimmed to its stroke, faded, gently cleaned up
and brought to the same modest level. Packs that are a sound rather than a board (raindrops, kalimba) are asked for a
slow series of that sound and cut the same way.

The key comes from ELEVENLABS_API_KEY or ~/.elevenlabs-key, never printed. A pack is made again when its number in
VERSIONS is higher than the one in its folder's .version; --again remakes every pack. Run by .github/workflows/sounds.yml.
"""
import json, os, pathlib, struct, subprocess, sys, tempfile, urllib.request, wave

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'android/app/src/main/assets/sounds'

# The boards: what they are and how they sound, in the words switch reviews use.
# The boards (keyboard recordings) were retired on 2026-11-09: "far too keyboard like and not subtle enough" (Matt).
# The machinery for them stays for another try: a pack here is asked for typing on that board.
BOARDS: dict = {}

# Packs that are a sound rather than a board.
SOUNDS = {
    # The stock sounds (Settings > Sounds), kept from the first sets.
    'bamboo': 'knocks on a hollow bamboo stalk',
    'geiger': 'a Geiger counter clicking',
    'ping-pong': 'a ping pong ball bouncing on a table',
    # 2026-11-09, Matt: "a fresh list of 30 new distinct and subtle sounds". Small, soft, short things.
    'droplet': 'tiny soft water droplets',
    'felt': 'soft felt mallet taps on a wooden tabletop',
    'paper': 'fingertip taps on a sheet of thick paper',
    'soft-pop': 'tiny soft mouth pops',
    'marimba': 'very short muted marimba taps, each damped at once',
    'sand': 'small pebbles dropped onto damp sand',
    'linen': 'tiny soft fingertip taps on linen fabric',
    'corkboard': 'fingertip taps on a cork board',
    'eraser': 'a soft rubber eraser tapped on a desk',
    'seed': 'single seeds dropped into a ceramic bowl',
    'leaf': 'a single small leaf flicked with a finger',
    'blip': 'tiny underwater bubble blips',
    'latch': 'a small plastic latch closing gently',
    'balsa': 'fingertip knocks on a thin balsa wood panel',
    'bead': 'small glass beads dropped on a felt mat',
    'frost': 'very soft crisp ticks, like frost cracking',
    'card': 'a playing card flicked once',
    'page': 'a single quick soft flick of a book page',
    'stone': 'a smooth river stone set gently on another',
    'velvet': 'fingertip taps on a velvet cushion, soft thuds',
    'clay': 'fingertip taps on an unglazed clay pot',
    'shell': 'a small seashell tapped against another',
    'block': 'soft wooden tocks of a tiny toy block',
    'rain-leaf': 'raindrops landing on a broad leaf',
    'pluck': 'very short muted guitar string plucks, damped instantly',
    'heartbeat': 'soft low single thumps like a muffled heartbeat',
    'sparkle': 'very short tiny high sparkle ticks, no ringing',
    'twig': 'a thin dry twig snapping softly',
    'zipper': 'a single soft click of a zipper tooth',
    'button': 'a soft cloth-covered button pressed',
}

# Every pack's name in Settings, in its order.
NAMES = {
    'bamboo': 'Thock', 'geiger': 'Geiger counter', 'ping-pong': 'Ping pong',
    'droplet': 'Droplet', 'felt': 'Felt mallet', 'paper': 'Paper', 'soft-pop': 'Soft pop', 'marimba': 'Muted marimba',
    'sand': 'Sand', 'linen': 'Linen', 'corkboard': 'Corkboard', 'eraser': 'Eraser', 'seed': 'Seed', 'leaf': 'Leaf',
    'blip': 'Bubble blip', 'latch': 'Latch', 'balsa': 'Balsa', 'bead': 'Glass bead', 'frost': 'Frost', 'card': 'Card flick',
    'page': 'Page', 'stone': 'River stone', 'velvet': 'Velvet', 'clay': 'Clay', 'shell': 'Seashell', 'block': 'Toy block',
    'rain-leaf': 'Rain on a leaf', 'pluck': 'Muted pluck', 'heartbeat': 'Heartbeat', 'sparkle': 'Sparkle', 'twig': 'Twig',
    'zipper': 'Zipper', 'button': 'Button',
}

# Sounds every keyboard has, without the plugin (Settings > Key press > Sound): Bamboo, as "Thock" (2026-11-08).
STOCK = {'bamboo': 'Thock', 'geiger': 'Geiger counter', 'ping-pong': 'Ping pong'}

# v3 for every pack but cardboard, which Matt liked as it was (2026-11-08) and which keeps its v1 sounds.
VERSIONS = {pack: 3 for pack in NAMES}

QUIET = ('Close-up recording in a quiet room, very soft, gentle and subtle, natural and realistic, not exaggerated, '
         'no music, no voices, no reverb.')


def prompts(pack: str) -> dict:
    """What each recording asks for: the keys, the space bar, enter and backspace."""
    if pack in BOARDS:
        board = BOARDS[pack]
        return {
            'keys': f'Slow, relaxed typing on {board}. Single keystrokes with clear pauses between them. {QUIET}',
            'space': f'The space bar of {board}, pressed slowly a few times with pauses: a slightly deeper, longer stroke. {QUIET}',
            'enter': f'The enter key of {board}, pressed firmly a few times with pauses. {QUIET}',
            'delete': f'The backspace key of {board}, pressed a few times with pauses. {QUIET}',
        }
    sound = SOUNDS[pack]
    return {
        'keys': f'A slow series of {sound}, one at a time with clear pauses between them, each short. {QUIET}',
        'space': f'A slow series of slightly deeper, fuller {sound}, one at a time with pauses. {QUIET}',
        'enter': f'A slow series of the most satisfying, rounded {sound}, one at a time with pauses. {QUIET}',
        'delete': f'A slow series of softer, lighter {sound}, one at a time with pauses. {QUIET}',
    }


def key() -> str:
    k = os.environ.get('ELEVENLABS_API_KEY', '').strip()
    if not k:
        f = pathlib.Path.home() / '.elevenlabs-key'
        k = f.read_text().strip() if f.exists() else ''
    if not k:
        sys.exit('No ElevenLabs key: set ELEVENLABS_API_KEY or ~/.elevenlabs-key')
    return k


def generate(api_key: str, text: str, seconds: float) -> bytes:
    body = json.dumps({'text': text, 'duration_seconds': seconds, 'prompt_influence': 0.55}).encode()
    req = urllib.request.Request('https://api.elevenlabs.io/v1/sound-generation?output_format=mp3_44100_128', data=body,
                                 headers={'xi-api-key': api_key, 'Content-Type': 'application/json', 'Accept': 'audio/mpeg'})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def pcm(mp3: bytes) -> list:
    """The recording as mono floats at 44.1 kHz."""
    with tempfile.TemporaryDirectory() as d:
        src, dst = pathlib.Path(d, 'in.mp3'), pathlib.Path(d, 'out.wav')
        src.write_bytes(mp3)
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', str(src), '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', str(dst)], check=True)
        with wave.open(str(dst)) as w:
            raw = w.readframes(w.getnframes())
    return [s / 32768 for s in struct.unpack(f'<{len(raw) // 2}h', raw)]


RATE = 44100


def strokes(x: list) -> list:
    """The keystrokes in a recording: (start, end) sample spans, found on a 5 ms envelope. A keystroke is often two
    sounds (the press and, 50-80 ms on, the bottom-out), so a stroke only ends after 45 ms of quiet, and a new one
    needs 60 ms of quiet before it; each runs at most 300 ms and keeps 30 ms of tail."""
    hop = int(RATE * 0.005)
    env = [max(abs(v) for v in x[i:i + hop]) for i in range(0, len(x) - hop, hop)]
    if not env:
        return []
    peak = max(env)
    floor = sorted(env)[len(env) // 5]  # the room between strokes
    on = max(peak * 0.15, floor * 4)
    off = max(on * 0.15, floor * 2)
    spans, i = [], 0
    while i < len(env):
        # A rise, with the 60 ms before it (but its own first 10 ms of attack) quiet.
        before = env[max(0, i - 14):max(0, i - 2)]
        if env[i] >= on and (not before or max(before) < on * 0.5):
            start = max(0, i - 2)
            last_loud, j = i, i + 1
            while j < len(env) and j - start < 60 and j - last_loud < 9:
                if env[j] >= off:
                    last_loud = j
                j += 1
            spans.append((start * hop, min(len(x), (last_loud + 6) * hop)))
            i = j
            continue
        i += 1
    return spans


def typical(x: list, spans: list, n: int) -> list:
    """The n strokes nearest the median loudness: the ordinary presses, not the slams or the grazes."""
    if not spans:
        return []
    level = lambda s: max(abs(v) for v in x[s[0]:s[1]])
    ranked = sorted(spans, key=level)
    mid = len(ranked) // 2
    picked = sorted(ranked, key=lambda s: abs(ranked.index(s) - mid))[:n]
    return sorted(picked)


def write(samples: list, dest: pathlib.Path) -> None:
    """A stroke as a pack's file: faded in over 2 ms and out over its last 40%, cleaned of rumble and fizz, at a
    soft, even level (peaks at -18 dBFS) so no pack is loud next to another."""
    n = len(samples)
    fade_in, fade_out = int(RATE * 0.002), max(1, int(n * 0.4))
    shaped = []
    for i, v in enumerate(samples):
        g = min(1.0, i / fade_in) if fade_in else 1.0
        if i > n - fade_out:
            g *= ((n - i) / fade_out) ** 2
        shaped.append(v * g)
    peak = max(1e-6, max(abs(v) for v in shaped))
    target = 10 ** (-18 / 20)
    shaped = [v * target / peak for v in shaped]
    with tempfile.TemporaryDirectory() as d:
        wav = pathlib.Path(d, 'x.wav')
        with wave.open(str(wav), 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(struct.pack(f'<{len(shaped)}h', *[int(max(-1, min(1, v)) * 32767) for v in shaped]))
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', str(wav), '-af', 'highpass=f=40,lowpass=f=11000',
                        '-c:a', 'libvorbis', '-q:a', '5', str(dest)], check=True)


def make(api_key: str, pack: str) -> None:
    folder = OUT / pack
    folder.mkdir(parents=True, exist_ok=True)
    asks = prompts(pack)
    for take, seconds in (('keys', 5.0), ('space', 3.5), ('enter', 3.5), ('delete', 3.5)):
        x = pcm(generate(api_key, asks[take], seconds))
        found = strokes(x)
        want = 3 if take == 'keys' else 1
        picked = typical(x, found, want)
        if len(picked) < want:
            raise RuntimeError(f'{pack}/{take}: found {len(found)} strokes, wanted {want}')
        names = ['key1', 'key2', 'key3'] if take == 'keys' else [take]
        for name, (a, b) in zip(names, picked):
            write(x[a:b], folder / f'{name}.ogg')
            print(f'{pack}/{name}.ogg  ({(b - a) * 1000 // RATE} ms of {len(found)} strokes)')
    (folder / '.version').write_text(str(VERSIONS[pack]))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    # The plugin's list leaves out the stock sounds, which Key press offers without it (SoundPacks.STOCK).
    (OUT / 'packs.json').write_text(json.dumps([{'id': k, 'name': v} for k, v in NAMES.items() if k not in STOCK], indent=1) + '\n')
    again = '--again' in sys.argv
    api_key = key()
    failed = []
    for pack in NAMES:
        stamp = OUT / pack / '.version'
        made_at = int(stamp.read_text().strip()) if stamp.exists() else 1
        complete = len(list((OUT / pack).glob('*.ogg'))) == 6
        if complete and not again and made_at >= VERSIONS[pack]:
            print(f'{pack}: already made (v{made_at})')
            continue
        for attempt in range(3):  # a recording with too few clear strokes is asked for again
            try:
                make(api_key, pack)
                break
            except RuntimeError as e:
                print(f'retry: {e}')
        else:
            failed.append(pack)
    if failed:
        print('Could not make: ' + ', '.join(failed))


if __name__ == '__main__':
    main()
