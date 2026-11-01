#!/usr/bin/env python3
"""Turn the nanoMuse Android tree in android/ into Nano Agent.

Nano Agent is a fork of nanoMuse (https://github.com/nano-muse/nanoMuse), which is itself
based on OpenMinis 1.13. Both are GPL-3.0; so is this. See NOTICE.

The script is idempotent: run it again after pulling a newer nanoMuse into android/ and it
re-applies every change below, then reports anything it expected to find and did not.

What it changes:

- Identity: applicationId, version (from CI), the system URL scheme, the launcher label and
  every user-visible "nanoMuse" in strings.xml, Kotlin string literals and the bundled JSON.
  Identifiers, comments, paths and the relay's User-Agent are left alone.
- Language: English first. The Simplified / Traditional Chinese translations, the Chinese
  Ideas feed and the Chinese language picks are removed; other locales stay.
- Region: American / global defaults. Providers, voice vendors, sandbox mirrors and
  connectors that only serve mainland China are dropped; region detection always answers
  "not mainland"; Azure speech defaults to East US.
- Links: updates, issues, privacy and licence point at this repository.
- Look: the Nano Agent palette (violet / cyan) replaces nanoMuse's blue. Icons come from
  scripts/gen-icons.py.

    python3 scripts/brand.py            # apply
    python3 scripts/brand.py --check    # report only
"""

from __future__ import annotations

import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "src" / "android"
APP = ANDROID / "app"
SRC = APP / "src"
MAIN = SRC / "main"
JAVA = MAIN / "java"
OM = JAVA / "com" / "openminis" / "app"
NM = JAVA / "io" / "github" / "nanomuse"
RES = MAIN / "res"
ASSETS = MAIN / "assets" / "nanomuse"

NAME = "Nano Agent"
AGENT_NAME = "Nano"
APP_ID = "io.github.deezi74.nanoagent"
SCHEME = "nanoagent"
REPO = "deezi74/Nano-agent-"
REPO_URL = f"https://github.com/{REPO}"
UPSTREAM_URL = "https://github.com/nano-muse/nanoMuse"

CHECK = "--check" in sys.argv
changed: list[str] = []
missing: list[str] = []


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT))


def write(path: Path, old: str, new: str) -> None:
    if new != old:
        if not CHECK:
            path.write_text(new, encoding="utf-8")
        changed.append(rel(path))


def edit(path: Path, subs: list[tuple[str, str]], *, literal: bool = False, expect: bool = True) -> None:
    if not path.is_file():
        missing.append(f"{rel(path)} (file)")
        return
    text = path.read_text(encoding="utf-8")
    new = text
    for pattern, repl in subs:
        if literal:
            if pattern not in new and repl not in new and expect:
                missing.append(f"{rel(path)}: {pattern[:60]!r}")
            new = new.replace(pattern, repl)
        else:
            done = re.sub(r"\\(\d|g<\d+>)", "", repl).replace("\\n", "\n")  # minus backreferences
            if expect and not re.search(pattern, new, flags=re.M) and done.strip() and done not in new \
                    and not any(part and part in new for part in done.split("\n") if len(part) > 12):
                missing.append(f"{rel(path)}: /{pattern[:60]}/")
            new = re.sub(pattern, repl, new, flags=re.M)
    write(path, text, new)


# --- product name -------------------------------------------------------------------------

# Specific phrases first, then the bare word. The bare word is only replaced when it stands
# alone: not inside a URL or path (nano-muse/nanoMuse, nanoMuse/avatar) and not glued to a
# dash (the relay's "nanoMuse-Android/1.0" User-Agent, which identifies the client honestly).
PHRASES = [
    ("nanoMuse Cloud", "Community Relay"),
    ("nanoMuse Desktop", "nanoMuse Desktop"),
    ("nanoMuse Skills", f"{NAME} Skills"),
    ("Not affiliated with Meta", "Built on nanoMuse (GPL-3.0). Not affiliated with Meta or nanoMuse"),
    ("3D toy (Muse)", "3D toy"),
    ("your Muse", "your agent"),
    ("Your Muse", "Your agent"),
    ("Muse shows only the name", f"{NAME} shows only the name"),
]
WORD = re.compile(r"(?<![/\w-])nanoMuse(?![\w/-])")


def rename(text: str) -> str:
    # Phrases go through placeholders so the bare-word pass below cannot touch what they
    # put back (several keep "nanoMuse" on purpose, as credit).
    held = []
    for a, b in PHRASES:
        if b in text and "nanoMuse" in b:  # already applied: keep its credit safe
            text = text.replace(b, f"\x00{len(held)}\x00")
            held.append(b)
        elif a in text:
            text = text.replace(a, f"\x00{len(held)}\x00")
            held.append(b)
    text = WORD.sub(NAME, text)
    for i, b in enumerate(held):
        text = text.replace(f"\x00{i}\x00", b)
    return text


KT_LITERAL = re.compile(r'"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"')


def product_name() -> None:
    for path in sorted(RES.glob("values*/*.xml")):
        text = path.read_text(encoding="utf-8")
        if "nanoMuse" not in text and "Muse" not in text:
            continue
        # Only element text, never names / comments: comments keep the upstream markers.
        new = re.sub(r"(<(string|item)\b[^>]*>)([\s\S]*?)(</\2>)",
                     lambda m: m.group(1) + rename(m.group(3)) + m.group(4), text)
        if new != text:
            ET.fromstring(new.encode("utf-8"))
        write(path, text, new)
    for path in sorted(SRC.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        if "nanoMuse" not in text and "Muse" not in text:
            continue
        new = KT_LITERAL.sub(lambda m: rename(m.group(0)), text)
        write(path, text, new)
    for path in sorted(ASSETS.glob("*.json")):
        text = path.read_text(encoding="utf-8")
        write(path, text, rename(text))


def identity() -> None:
    edit(APP / "build.gradle.kts", [
        (r'applicationId = "[^"]+"', f'applicationId = "{APP_ID}"'),
        (r"versionCode = \d+",
         'versionCode = (project.findProperty("na.versionCode") as String?)?.toInt() ?: 1'),
        (r'versionName = "[^"]+"',
         'versionName = (project.findProperty("na.versionName") as String?) ?: "1.0.0"'),
    ])
    edit(RES / "xml" / "shortcuts.xml", [(r'android:targetPackage="[^"]+"', f'android:targetPackage="{APP_ID}"')])
    edit(MAIN / "AndroidManifest.xml", [(r'<data android:scheme="nanomuse" />', f'<data android:scheme="{SCHEME}" />')])
    # The agent's own default name (the Soul); the user renames it in Settings.
    edit(OM / "agent" / "SoulStore.kt", [(r'(val DEFAULT = SoulMetadata\(\s*name = )"[^"]*"', rf'\1"{AGENT_NAME}"')])


# --- language: English first ----------------------------------------------------------------

def english() -> None:
    for d in ("values-zh", "values-zh-rTW"):
        p = RES / d
        if p.is_dir():
            if not CHECK:
                shutil.rmtree(p)
            changed.append(rel(p) + " (removed)")
    p = ASSETS / "ideas.zh.json"
    if p.is_file():
        if not CHECK:
            p.unlink()
        changed.append(rel(p) + " (removed)")
    edit(NM / "ideas" / "Ideas.kt", [
        (r'val name = if \(lang == "zh"\) "nanomuse/ideas\.zh\.json" else "nanomuse/ideas\.en\.json"',
         'val name = "nanomuse/ideas.en.json" // Nano Agent: English only'),
    ])
    edit(RES / "xml" / "locales_config.xml", [
        (r'\s*<locale android:name="zh"/>', ""),
        (r'\s*<!-- zh-Hant carries[\s\S]*?-->\s*<locale android:name="zh-Hant"/>', ""),
    ])
    edit(OM / "ui" / "settings" / "AppearanceScreen.kt", [
        (r'\n\s*LanguageOption\("zh", "[^"]*", "[^"]*"\),', ""),
        (r'\n\s*LanguageOption\("zh-Hant", "[^"]*", "[^"]*"\),', ""),
    ])
    edit(OM / "ui" / "settings" / "SoulSettingsScreen.kt", [
        (r'\n\s*"zh" to stringResource\(R\.string\.soul_lang_zh\),', ""),
    ])
    # Bundled catalogues: the English text is always the one shown.
    edit(NM / "cloud" / "ProviderCatalogue.kt", [
        (r'(fun chinese\(context: Context\): Boolean =)\s*\n?\s*\(context\.resources\.configuration\.locales\[0\] \?: Locale\.getDefault\(\)\)\.language == "zh"',
         r'\1 false // Nano Agent: English names and notes'),
    ])
    edit(NM / "connectors" / "ConnectorsCatalogue.kt", [
        (r'if \(locale\.language == "zh" && aboutZh\.isNotBlank\(\)\) aboutZh else aboutEn', 'aboutEn // Nano Agent: English'),
    ])


# --- region: American / global defaults ------------------------------------------------------

CN_PROVIDERS = {"bailian", "deepseek", "moonshot", "zhipu", "siliconflow", "volcengine", "minimax"}
CN_CONNECTORS = {"amap", "zhipu-search"}
CJK = re.compile(r"[㐀-鿿豈-﫿]")


def strip_zh(o):
    if isinstance(o, dict):
        return {k: strip_zh(v) for k, v in o.items() if not (k.endswith("_zh") or k == "zh")}
    if isinstance(o, list):
        return [strip_zh(v) for v in o]
    return o


def dump(path: Path, data) -> None:
    text = path.read_text(encoding="utf-8")
    new = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    write(path, text, new)


def region() -> None:
    p = ASSETS / "providers.json"
    data = json.loads(p.read_text(encoding="utf-8"))
    data["providers"] = [x for x in data["providers"] if x["id"] not in CN_PROVIDERS]
    for x in data["providers"]:
        if "regions" in x:
            x["regions"] = [r for r in x["regions"] if r != "cn"] or ["global"]
    dump(p, strip_zh(data))

    p = ASSETS / "connectors.json"
    data = json.loads(p.read_text(encoding="utf-8"))
    data["connectors"] = [x for x in data["connectors"] if x["id"] not in CN_CONNECTORS]
    data = strip_zh(data)
    dump(p, data)

    # "Mainland China" is never the answer: OpenRouter and the US providers come first.
    edit(NM / "cloud" / "Region.kt", [
        (r"fun mainland\(context: Context\): Boolean \{\n(?!\s*if \(true\))",
         "fun mainland(context: Context): Boolean {\n        if (true) return false // Nano Agent: US / global defaults\n"),
    ])

    # Sandbox package mirrors: drop the China-only ones, add a US one.
    edit(OM / "ui" / "sandbox" / "MirrorSettingsScreen.kt", [
        (r'\n\s*(alpine|pip|npm)\("[^"]+", "[^"]+", "[^"]+", "China"\),', ""),
        (r'(alpine\("official", "Official CDN", "https://dl-cdn\.alpinelinux\.org/alpine/", "Global", official = true\),)(?!\n\s*alpine\("ocf")',
         r'\1\n        alpine("ocf", "UC Berkeley OCF", "https://mirrors.ocf.berkeley.edu/alpine/", "North America"),'),
    ])

    # Voice vendors: keep the US / global ones, Azure in East US with English voices first.
    vt = OM / "data" / "model" / "VoiceProviderTemplate.kt"
    text = vt.read_text(encoding="utf-8")
    new = re.sub(r'\n\s*// Chinese \((Mandarin|Cantonese|Taiwanese)\)', "", text)
    new = re.sub(r'\n\s*tts\("zh-[A-Z]{2}-\w+", "[^"]*", "azure-tts"\),', "", new)
    new = new.replace("eastasia.tts.speech.microsoft.com", "eastus.tts.speech.microsoft.com")
    start = new.find('            VoiceProviderTemplate(\n                id = "minimax"')
    if start != -1:
        end = new.rfind("        )\n    }\n}")
        new = new[:start] + new[end:]
    write(vt, text, new)
    edit(OM / "provider" / "voice" / "VoiceProviderFactory.kt", [
        ("https://eastasia.tts.speech.microsoft.com", "https://eastus.tts.speech.microsoft.com"),
    ], literal=True)


# --- links -----------------------------------------------------------------------------------

def links() -> None:
    edit(OM / "data" / "UpdateChecker.kt", [
        (r'private const val OWNER = "[^"]+"', 'private const val OWNER = "deezi74"'),
        (r'private const val REPO = "[^"]+"', 'private const val REPO = "Nano-agent-"'),
        (r'const val RELEASES_URL: String = "[^"]+"', f'const val RELEASES_URL: String = "{REPO_URL}/releases"'),
    ])
    edit(NM / "community" / "UpdateCheck.kt", [
        (r'const val DOWNLOAD_URL = "[^"]+"', f'const val DOWNLOAD_URL = "{REPO_URL}/releases/latest"'),
        (r'const val INDEX_URL = "[^"]+"', 'const val INDEX_URL = ""'),
        (r'const val GITHUB_LATEST_URL = "[^"]+"', f'const val GITHUB_LATEST_URL = "https://api.github.com/repos/{REPO}/releases/latest"'),
        (r"get\(INDEX_URL\)\?\.let", "INDEX_URL.takeIf { it.isNotEmpty() }?.let { get(it) }?.let"),
    ])
    # Every other link to the nanoMuse site or repo, in Kotlin literals.
    url_map = [
        ("https://github.com/nano-muse/nanoMuse/releases", f"{REPO_URL}/releases"),
        ("https://github.com/nano-muse/nanoMuse/issues/new/choose", f"{REPO_URL}/issues/new"),
        ("https://github.com/nano-muse/nanoMuse/issues/new", f"{REPO_URL}/issues/new"),
        ("https://github.com/nano-muse/nanoMuse/blob/main/LICENSE", f"{REPO_URL}/blob/main/LICENSE"),
        ("https://nanomuse.cn/own-key", f"{REPO_URL}#use-your-own-key"),
        ("https://nanomuse.cn/#download", f"{UPSTREAM_URL}/releases/latest"),
        ("https://nanomuse.cn/#open-source", f"{REPO_URL}/blob/main/NOTICE"),
        ("https://nanomuse.cn/privacy/", f"{REPO_URL}/blob/main/PRIVACY.md"),
    ]
    for path in sorted(JAVA.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        new = text
        for a, b in url_map:
            new = new.replace(f'"{a}"', f'"{b}"').replace(f"({a})", f"({b})")
        new = new.replace('const val CLIENT_URI = "https://nanomuse.cn"', f'const val CLIENT_URI = "{REPO_URL}"')
        write(path, text, new)


# --- look ------------------------------------------------------------------------------------

# nanoMuse blue -> Nano Agent violet; its gradient end -> cyan. Any alpha prefix is kept.
PALETTE = [
    ("015CFB", "6C3CF0"),  # primary (light)
    ("0186FB", "17C3E6"),  # mark gradient end
    ("58A6FF", "B49CFF"),  # primary (dark)
    ("E5F0FF", "EEE8FF"),  # primaryContainer
    ("012F80", "250B6E"),  # onPrimaryContainer
    ("00224D", "1C0752"),  # dark onPrimary
    ("1A2B4A", "2D1F5C"),  # dark primaryContainer
    ("0A66E4", "5B2BE0"),  # deep accent
    ("0F1B33", "120B24"),  # forced-dark icon tile
]


def colours() -> None:
    pats = []
    for a, b in PALETTE:
        pats.append((re.compile(rf"0x([0-9A-Fa-f]{{2}}){a}\b", re.I), rf"0x\g<1>{b}"))
        pats.append((re.compile(rf"#([0-9A-Fa-f]{{2}})?{a}\b", re.I), lambda m, b=b: "#" + (m.group(1) or "") + b))
    for path in sorted(JAVA.rglob("*.kt")) + sorted(RES.rglob("*.xml")):
        text = path.read_text(encoding="utf-8")
        new = text
        for rx, repl in pats:
            new = rx.sub(repl, new)
        write(path, text, new)


# --- Nano Agent touches (carried over from TruAgent) -------------------------------------------

FREELLMAPI = {
    "id": "freellmapi",
    "name": "FreeLLMAPI",
    "protocol": "openai",
    "base_url": "http://localhost:3001/v1",
    "key_url": "http://localhost:3001",
    "key_hint": "your FreeLLMAPI key",
    "auth": ["key", "none"],
    "regions": ["global"],
    "capabilities": ["chat"],
    "user_capabilities": True,
    "defaults": {"chat": "auto"},
    "note": "Your own free-model router. Start it in Termux (cd ~/freellmapi && termux-wake-lock && npm run dev), keep the address http://localhost:3001/v1 and the model auto; it picks a free model for each request.",
    "verified": "2026-11",
}

# American defaults where nanoMuse picked Chinese models (the user can choose any model).
DEFAULT_MODELS = {
    "openrouter": {"chat": "openai/gpt-5.4-mini", "hands": "openai/gpt-5.4"},
    "ollama": {"chat": "gpt-oss:20b"},
}
DROP_DEFAULTS = {"groq": ["hands"]}

SOUL_BODY = """**Don't perform — help.** Skip the "Sure!" and "Happy to assist!" — just do the work.

**Bring the wit.** Quick, bold and a little funny, like a sharp friend who happens to know everything. Never let a joke get in the way of being right.

**Have a stance.** It's fine to disagree, prefer one thing over another, find some things interesting and others dull.

**Act first, ask second.** If you can look it up, look it up. Come back with answers, not questions.

**Keep it American-plain.** Everyday English, US units, dates and dollars unless asked otherwise.
"""


def touches() -> None:
    p = ASSETS / "providers.json"
    data = json.loads(p.read_text(encoding="utf-8"))
    provs = data["providers"]
    provs[:] = [x for x in provs if x["id"] != "freellmapi"]
    at = next((i for i, x in enumerate(provs) if x["id"] == "ollama"), len(provs))
    provs.insert(at, FREELLMAPI)
    for x in provs:
        x.setdefault("defaults", {}).update(DEFAULT_MODELS.get(x["id"], {}))
        for k in DROP_DEFAULTS.get(x["id"], []):
            x["defaults"].pop(k, None)
    dump(p, data)

    soul = OM / "agent" / "SoulStore.kt"
    text = soul.read_text(encoding="utf-8")
    new = re.sub(r'(val DEFAULT_CONTENT: String = """---\nname: )"[^"]*"', rf'\1"{AGENT_NAME}"', text)
    new = re.sub(r'(val DEFAULT_CONTENT: String = """---\n[\s\S]*?\n---\n\n)[\s\S]*?(""")',
                 lambda m: m.group(1) + SOUL_BODY + m.group(2), new)
    new = new.replace('const val DISPLAY_EMOJI = "🐾"', 'const val DISPLAY_EMOJI = "⚡"')
    write(soul, text, new)

    # Dark by default, like TruAgent (Settings > Appearance still offers System / Light).
    for path in (OM / "ui" / "settings" / "AppearanceScreen.kt", OM / "MainActivity.kt"):
        edit(path, [("getInt(KEY_THEME_MODE, 0)", "getInt(KEY_THEME_MODE, 2)")], literal=True)
    edit(OM / "config" / "ConfigBuiltins.kt", [
        (r'(key = "theme_mode",\n\s*cases = listOf\("system", "light", "dark"\),\n\s*defaultIndex = )0', r"\g<1>2"),
    ])


def report_cjk() -> None:
    """Chinese left in user-facing places (strings, catalogues), for a human to look at."""
    left = []
    for path in sorted(RES.glob("values/*.xml")) + sorted(ASSETS.glob("*.json")):
        n = len(CJK.findall(path.read_text(encoding="utf-8")))
        if n:
            left.append(f"{rel(path)}: {n} CJK chars")
    if left:
        print("CJK still in user-facing files:")
        for line in left:
            print("  " + line)


def main() -> int:
    product_name()
    identity()
    english()
    region()
    links()
    colours()
    touches()
    report_cjk()
    verb = "would change" if CHECK else "changed"
    print(f"{verb} {len(set(changed))} files")
    if missing:
        print("expected but not found (upstream moved?):")
        for m in missing:
            print("  " + m)
    return 1 if missing and CHECK else 0


if __name__ == "__main__":
    sys.exit(main())
