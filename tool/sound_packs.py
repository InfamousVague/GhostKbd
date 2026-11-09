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
    'mechanical': 'Cherry MX Brown tactile mechanical keyboard with thick PBT keycaps, warm and full, not tinny',
    'thock': 'deep thock of a heavily lubed linear switch bottoming out in a foam-dampened aluminium case with thick PBT keycaps',
    'typewriter': 'vintage manual typewriter, warm wooden desk, soft',
    'bubble': 'soft cartoon bubble',
    'arcade': '8-bit retro video game console',
    # 2026-11-08, Matt: "generate about 20-30 more sound packs so I can refine the good ones".
    'holy-panda': 'Holy Panda tactile mechanical keyboard switch, a rounded poppy bump',
    'topre': 'Topre electrocapacitive keyboard, a soft cushioned rounded thock',
    'buckling-spring': '1980s buckling spring keyboard, a loud spring click with a little ping',
    'alps': 'vintage Alps white mechanical switch, a crisp scratchy click',
    'box-jade': 'thick clicky box mechanical switch, a sharp heavy click bar',
    'creamy': 'creamy lubed linear switch with POM keycaps, a smooth soft tok',
    'marble': 'marbly thock keyboard, like a glass marble tapping stone, deep and glassy',
    'laptop': 'thin laptop chiclet keyboard, a quiet soft tap',
    'silent': 'silent dampened linear switch, a very soft muffled tap',
    'wood': 'wooden keycaps on a wooden keyboard case, a warm hollow knock',
    'ceramic': 'ceramic keycaps on a mechanical keyboard, a clacky glassy tick',
    'terminal': 'vintage 1980s computer terminal keyboard, a solid mechanical clack',
    'red-linear': 'light smooth red linear mechanical switch, a gentle clack',
    'ink': 'smooth heavy black linear mechanical switch, a deep clean clack',
}

# Packs that are a sound rather than a keyboard: one description, varied for each key the same way.
SOUND_ONLY = {
    'piano': 'soft felt piano hammer tapping a muted low string, a short dull note',
    'raindrop': 'water drop plip into a still pool',
    'woodblock': 'small woodblock tok struck with a soft mallet',
    'kalimba': 'soft kalimba tine pluck, gentle and warm',
    'glass': 'light fingertip tap on a wine glass, a short soft ting',
    'scifi': 'futuristic soft user interface tap, a subtle digital blip',
    'pencil': 'pencil tip tapping paper on a desk',
    'cardboard': 'fingertip tap on an empty cardboard box',
    'pebble': 'small pebble tapped on a smooth stone',
    'bubble-wrap': 'single bubble wrap bubble popping',
    'phone-tick': 'smartphone touchscreen keyboard tick, soft and subtle',
}
VARIANTS = {
    'key1': 'one {s}', 'key2': 'a single, very slightly different {s}', 'key3': 'one quick {s}',
    'space': 'one deeper, bigger {s}', 'delete': 'one softer, lighter {s}', 'enter': 'one fuller, more satisfying {s}',
}

# Every pack's name in Settings, in its order; written to assets/sounds/packs.json for the app.
NAMES = {
    'mechanical': 'Clicky mechanical', 'thock': 'Deep thock', 'typewriter': 'Typewriter', 'bubble': 'Bubble pop',
    'arcade': '8-bit arcade', 'holy-panda': 'Tactile pop', 'topre': 'Cushioned thock', 'buckling-spring': 'Buckling spring',
    'alps': 'Vintage click', 'box-jade': 'Heavy click', 'creamy': 'Creamy', 'marble': 'Marbly', 'laptop': 'Laptop',
    'silent': 'Silent', 'wood': 'Wooden', 'ceramic': 'Ceramic', 'terminal': 'Terminal clack', 'red-linear': 'Light linear',
    'ink': 'Deep linear', 'piano': 'Muted piano', 'raindrop': 'Raindrops', 'woodblock': 'Woodblock', 'kalimba': 'Kalimba',
    'glass': 'Glass', 'scifi': 'Sci-fi', 'pencil': 'Pencil', 'cardboard': 'Cardboard', 'pebble': 'Pebbles',
    'bubble-wrap': 'Bubble wrap', 'phone-tick': 'Phone tick',
}
SOUNDS = {
    'key1': 'a single {p} key press, one short crisp click, close mic, dry, no reverb, isolated',
    'key2': 'one {p} key tap, a slightly different single short click, close mic, dry, isolated',
    'key3': 'a single quick {p} keystroke, short click, close mic, dry, isolated',
    'space': 'a single {p} space bar press, a deeper wider single thud, close mic, dry, isolated',
    'delete': 'a single {p} backspace key press, short soft click, close mic, dry, isolated',
    'enter': 'a single {p} enter key press, a firm satisfying single clack, close mic, dry, isolated',
}
# The arcade and bubble packs are sounds rather than keys; the thock is asked for by its sound, not a click.
SPECIAL = {
    'thock': {
        'key1': 'one deep muffled thock, a low pitched round knock like a marble dropped on a wooden desk, short, no click, no rattle, close mic, dry, isolated',
        'key2': 'a single low soft thock of a heavily lubed linear mechanical keyboard key bottoming out, deep and muted, no clack, dry, isolated',
        'key3': 'one short deep creamy thock keystroke, low pitched, muted, no high frequencies, dry, isolated',
        'space': 'one big deep hollow thock of a dampened keyboard space bar, very low and round, short, no rattle, dry, isolated',
        'delete': 'one soft low thock, slightly lighter, muted, short, dry, isolated',
        'enter': 'one deep heavy satisfying thock, low pitched and round, short, no click, dry, isolated',
    },
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


# Warmer and quieter than ElevenLabs gives them: the rumble below 35 Hz and the fizz above 6.5 kHz gone, the body
# around 140 Hz lifted, the harsh 3.5 kHz presence and the top end eased, then squeezed and held well under full
# scale (peaks about -11 dBFS) so a run of keys is never piercing. The app's volume setting does the rest.
WARM = ('highpass=f=35,lowpass=f=6500,bass=g=7:f=140:w=0.8,equalizer=f=3500:t=q:w=1.2:g=-6,treble=g=-4:f=6000,'
        'acompressor=threshold=-20dB:ratio=3:attack=1:release=60,alimiter=limit=0.28:level=false')


def refine() -> None:
    """--refine: the packs already made, put through WARM again (for packs made before it existed)."""
    for f in sorted(OUT.glob('*/*.ogg')):
        tmp = f.with_suffix('.tmp.ogg')
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', str(f), '-af', WARM, '-ac', '1', '-ar', '44100',
                        '-c:a', 'libvorbis', '-q:a', '5', str(tmp)], check=True)
        tmp.replace(f)
        print(f'refined {f.relative_to(OUT)}')


# What a pack needs beyond WARM: the thock loses its click and rattle (everything over 2.4 kHz, a dip at 1.6 kHz)
# and gains weight around 160 Hz, dropped a little in pitch.
PACK_FILTERS = {
    'thock': 'lowpass=f=2400,lowpass=f=2400,equalizer=f=1600:t=q:w=1:g=-7,bass=g=6:f=160:w=0.7,asetrate=44100*0.92,aresample=44100,volume=8dB,',
}

# A pack is made again when its number here is higher than the one it was made at (its folder's .version).
PACK_VERSIONS = {'thock': 2}


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


def trim(mp3: bytes, dest: pathlib.Path, pack: str = '') -> None:
    with tempfile.NamedTemporaryFile(suffix='.mp3') as src:
        src.write(mp3)
        src.flush()
        # Start at the sound (no lead-in silence), keep 250 ms, fade the tail, mono 44.1 kHz.
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src.name, '-af',
                        'silenceremove=start_periods=1:start_threshold=-42dB,atrim=0:0.25,afade=t=out:st=0.17:d=0.08,' + PACK_FILTERS.get(pack, '') + WARM,
                        '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '4', str(dest)], check=True)


def main() -> None:
    if '--refine' in sys.argv:
        refine()
        return
    again = '--again' in sys.argv
    api_key = key()
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'packs.json').write_text(json.dumps([{'id': k, 'name': v} for k, v in NAMES.items()], indent=1) + '\n')
    for pack, thing in list(PACKS.items()) + [(k, None) for k in SOUND_ONLY]:
        folder = OUT / pack
        stamp = folder / '.version'
        made_at = int(stamp.read_text().strip()) if stamp.exists() else 1
        if folder.exists() and len(list(folder.glob('*.ogg'))) == len(SOUNDS) and not again and made_at >= PACK_VERSIONS.get(pack, 1):
            print(f'{pack}: already made')
            continue
        folder.mkdir(parents=True, exist_ok=True)
        for name, prompt in SOUNDS.items():
            if pack in SOUND_ONLY:
                text = VARIANTS[name].format(s=SOUND_ONLY[pack]) + ', very short, close mic, dry, no reverb, isolated'
            else:
                text = SPECIAL.get(pack, {}).get(name) or prompt.format(p=thing)
            trim(generate(api_key, text), folder / f'{name}.ogg', pack)
            print(f'{pack}/{name}.ogg')
        stamp.write_text(str(PACK_VERSIONS.get(pack, 1)))


if __name__ == '__main__':
    main()
