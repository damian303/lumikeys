#!/usr/bin/env python3
"""Turns published notation of public-domain melodies into the app's song text.

Why this exists: the first eight built-in tunes were typed from memory. These were
not. Each melody below is copied from the notation in a Wikipedia article (LilyPond
or ABC source, fetched 26 Nov 2026), converted by this script rather than by hand,
and, where a second source exists, its pitches are compared with a letter-name
listing from another site (pianogrid.com/songs/easy, fetched the same day).

Run it to print the entries for Songs.kt and the checks:

    python3 tools/tunes_from_sources.py

What the script changes, and nothing else:
  - key: some tunes are moved to C so they use white keys only (noted per tune);
  - octave: placed so the tune starts near middle C (the app refits by octave anyway);
  - ornaments, slurs, dynamics, lyrics and other voices are dropped; grace notes too;
  - repeats are written out;
  - tempo is chosen for learners (the app has 50% and 75% speeds as well).
"""
import re
import sys

STEP_SEMITONES = [0, 2, 4, 5, 7, 9, 11]  # c d e f g a b
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


class Note:
    def __init__(self, pitch, beats):
        self.pitch = pitch  # MIDI note number, or None for a rest
        self.beats = beats  # in quarter notes

    def name(self):
        return "-" if self.pitch is None else NAMES[self.pitch % 12] + str(self.pitch // 12 - 1)


# ---- LilyPond (relative mode, one voice) ---------------------------------------------

NOTE_RE = re.compile(r"^([a-h])(isis|eses|is|es|s)?([',]*)(\d+)?(\.*)(~?)$")
REST_RE = re.compile(r"^[rs](\d+)?(\.*)$")
JUNK = [
    r"\\grace\s+\S+",                 # a grace note and its slur
    r"\^?\\markup\s*\{[^}]*\}", r"\^\"[^\"]*\"", r"\\bar\s*\"[^\"]*\"",
    r"\\(?:break|fermata|mordent|pp|p|autoBeamOff)\b", r"\\[()]", r"-[.>]", r"[\[\]()]",
]


def lily_reference(text):
    """`c''` and the like: the pitch that the first note is placed relative to."""
    m = re.match(r"^([a-g])([',]*)$", text)
    step = "cdefgab".index(m.group(1))
    octave = 3 + m.group(2).count("'") - m.group(2).count(",")
    return octave * 7 + step


def parse_lily(segments, order, relative, language="nederlands"):
    """
    segments: (name, text) pairs in the order they appear in the source, because in
    relative mode each note's octave depends on the note written before it. order:
    the names in the order they are played, which is how repeats are written out.
    """
    position = lily_reference(relative)  # staff steps: 7 per octave, c' (middle C) = 28
    duration = 1.0
    parsed = {}
    checks = []
    for name, text in segments:
        for pattern in JUNK:
            text = re.sub(pattern, " ", text)
        text = text.replace("|", " | ")  # bar lines are sometimes written hard against a note
        notes = []
        tie_open = False
        for token in text.split():
            if token == "|":
                checks.append((name, len(notes)))
                continue
            rest_after = token.endswith("\\rest")  # a rest written at a pitch
            token = token.replace("\\rest", "")
            m = REST_RE.match(token)
            if m and not rest_after:
                if m.group(1):
                    duration = 4.0 / int(m.group(1)) * (2 - 0.5 ** len(m.group(2)))
                notes.append(Note(None, duration))
                tie_open = False
                continue
            m = NOTE_RE.match(token)
            if not m:
                raise ValueError(f"{name}: cannot read '{token}'")
            letter, accidental, marks, number, dots, tie = m.groups()
            if language == "deutsch":
                step, alter = (6, 0) if letter == "h" else (6, -1) if letter == "b" else ("cdefgab".index(letter), 0)
            else:
                if letter == "h":
                    raise ValueError(f"{name}: 'h' outside German note names")
                step, alter = "cdefgab".index(letter), 0
            if accidental:
                if accidental == "s" and letter not in "ae":
                    raise ValueError(f"{name}: cannot read '{token}'")
                alter += {"is": 1, "isis": 2, "es": -1, "eses": -2, "s": -1}[accidental]
            # The octave nearest the previous note, counted in staff steps (a fourth or less).
            octave = position // 7
            candidates = [o * 7 + step for o in (octave - 1, octave, octave + 1)]
            position = min(candidates, key=lambda c: abs(c - position))
            position += 7 * (marks.count("'") - marks.count(","))
            if number:
                duration = 4.0 / int(number) * (2 - 0.5 ** len(dots))
            pitch = 12 * (position // 7 + 1) + STEP_SEMITONES[position % 7] + alter
            if rest_after:
                notes.append(Note(None, duration))
            elif tie_open and notes and notes[-1].pitch == pitch:
                notes[-1].beats += duration
            else:
                notes.append(Note(pitch, duration))
            tie_open = bool(tie)
        parsed[name] = notes
    return [Note(n.pitch, n.beats) for name in order for n in parsed[name]]


# ---- ABC (the small part of it these tunes use) ----------------------------------------

def parse_abc(text, unit_beats):
    notes = []
    for m in re.finditer(r"([A-Ga-gz])([,']*)(\d*)", re.sub(r"\|+", " ", text)):
        letter, marks, count = m.groups()
        beats = unit_beats * (int(count) if count else 1)
        if letter == "z":
            notes.append(Note(None, beats))
            continue
        step = "CDEFGAB".index(letter.upper())
        octave = (5 if letter.islower() else 4) + marks.count("'") - marks.count(",")
        notes.append(Note(12 * (octave + 1) + STEP_SEMITONES[step], beats))
    return notes


# ---- The tunes -------------------------------------------------------------------------

WIKI = "https://en.wikipedia.org/wiki/"
TUNES = []


def tune(**fields):
    TUNES.append(fields)


tune(
    id="au-clair-lune", title="Au Clair de la Lune", credit="Traditional", bpm=110, bar=4,
    source="https://fr.wikipedia.org/wiki/Au_clair_de_la_lune", shift=-12, change="down an octave",
    lily=dict(relative="c''", order=["a"], segments=[("a", """
        c4 c c d e2 d c4 e d d c1
        c4 c c d e2 d c4 e d d c1
        d4 d d d a2 a d4 c b a g1
        c4 c c d e2 d c4 e d d c1 """)]),
    second="C C C D E D  C E D D C  C C C D E D  C E D D C  D D D D A3 A3  D C B3 A3 G3  C C C D E D  C E D D C",
)

tune(
    id="london-bridge", title="London Bridge Is Falling Down", credit="Traditional", bpm=100, bar=2,
    source=WIKI + "London_Bridge_Is_Falling_Down", shift=-5, change="from F to C",
    lily=dict(relative="c''", order=["a", "first", "a", "second"], segments=[
        ("a", "c8. (d16 c8 bes8 | a8 bes8 c4) |"),
        ("first", "g8 (a8) bes4) | a8 (bes8 c4) |"),
        ("second", "g4 (c4 |a8 f4.)"),
    ]),
    second="G A G F E F G  D E F  E F G  G A G F E F G  D G E C",
)

tune(
    id="lightly-row", title="Lightly Row", credit="Traditional", bpm=100, bar=2,
    source=WIKI + "H%C3%A4nschen_klein", shift=-5, change="from F to C",
    lily=dict(relative="c''", language="deutsch", order=["a", "first", "a", "second", "b"], segments=[
        ("a", "c8 a a4 | b8 g g4 |"),
        ("first", "f8 g a b | c c c4 |"),
        ("second", "f,8 a c c | f,4 r |"),
        ("b", "g8 g g g | g a b4 | a8 a a a | a b c4 | c8 a a4 | b8 g g4 | f8 a c c | f,2"),
    ]),
    second="G E E F D D  C D E F G G G  G E E F D D  C E G G C  D D D D D E F  E E E E E F G  G E E F D D  C E G G C",
)

tune(
    id="row-boat", title="Row, Row, Row Your Boat", credit="Traditional", bpm=110, bar=3,
    source=WIKI + "Row,_Row,_Row_Your_Boat", shift=0, change=None,
    lily=dict(relative="c'", order=["a"], segments=[("a", """
        c4. c4. | c4 d8 e4. | e4 d8 e4 f8 | g2.
        c8 c c g8 g g e8 e e c8 c c |
        g'4 f8 e4 d8 c2. """)]),
    second="C C C D E  E D E F G  C5 C5 C5 G G G E E E C C C  G F E D C",
)

tune(
    id="when-saints", title="When the Saints Go Marching In", credit="Traditional", bpm=120, bar=2,
    source="https://de.wikipedia.org/wiki/When_the_Saints_Go_Marching_In", shift=0, change=None,
    abc=dict(unit_beats=0.5, text="""
        z CEF|G4|z CEF|G4|
        z CEF|G2E2|C2E2|D4|
        z EED|C4|E2G2|GF3|z2 EF|
        G2E2|C2 D2|C4||"""),
    # The second source has one more note here ("Oh Lord, I want to be..." against "I want to be...").
    second=None,
    second_note="pianogrid's version has an extra C in the third line (a different set of words); "
                "the other lines agree note for note",
    second_partial="C E F G  C E F G  C E F G E C E D",
)

tune(
    id="joy-to-world", title="Joy to the World", credit="Lowell Mason", bpm=90, bar=2,
    source=WIKI + "Joy_to_the_World", shift=-2, change="from D to C",
    lily=dict(relative="c''", order=["a", "b", "b", "c"], segments=[
        ("a", "d4 cis8. b16 | a4. g8 | fis4 e | d4. a'8 | b4. b8 | cis4. cis8 | d2~ | d4."),
        ("b", "d8 | d [cis] b [a] | a8. [g16 fis8]"),
        ("c", """fis8 | fis fis fis fis16 [g] | a4.
                 g16 [fis] | e8 e e e16 [fis] | g4.
                 fis16 [e] | fis8\\( d'4\\) b8 | a8. [g16] fis8 g | fis4 e | d2"""),
    ]),
    second=None,
)

tune(
    id="silent-night", title="Silent Night", credit="Franz Xaver Gruber", bpm=66, bar=3,
    source=WIKI + "Silent_Night", shift=0, change=None,
    lily=dict(relative="c''", order=["a"], segments=[("a", """
        g8. [(a16)] g8 e4. | g8. [a16] g8 e4. | d'4 d8 b4. | c4 c8 g4. | a4 a8 c8. [b16] a8 | g8. [a16] g8 e4. |
        a4 a8 c8. [b16] a8 | g8. [a16] g8 e4. | d'4 d8 f8.-> [d16] b8 | c4. (e4) r8 | c8. [(g16)] e8 g8. [f16] d8 | c4.~ c4 r8 """)]),
    second="G A G E  G A G E  D5 D5 B  C5 C5 G  A A C5 B A G A G E  A A C5 B A G A G E  D5 D5 F5 D5 B C5 E5  C5 G E G F D C",
)

tune(
    id="drunken-sailor", title="Drunken Sailor", credit="Traditional", bpm=90, bar=2,
    source="https://de.wikipedia.org/wiki/Drunken_Sailor", shift=0, change=None,
    lily=dict(relative="c''", order=["a"], segments=[("a", """
        a8 a16 a16 a8 a16 a16 | a8 d,8 f8 a8 | g8 g16 g16 g8 g16 g16 | g8 c,8 e8 g8
        a8 a16 a16 a8 a16 a16 | a8 b8 c8 d8  | c8 a8 g8 e8 | d4 d4 | a'4 a8. a16 | a8 d,8 f8 a8|
        g4 g8. g16 | g8 c,8 e8 g8 | a4 a8. a16 | a8 b8 c8 d8 | c8 a8 g8 e8 | d4 d4 """)]),
    second=None,
)

tune(
    id="korobeiniki", title="Korobeiniki (the Tetris tune)", credit="Russian folk song", bpm=120, bar=4,
    # The folk song as the article gives it. The game's music is an arrangement of this tune
    # and its first bar goes differently (E B C D C B A...); this follows the source.
    source=WIKI + "Korobeiniki", shift=0, change=None,
    lily=dict(relative="a'", order=["a", "b", "b"], segments=[
        ("a", "e4. gis8 b4 gis8 e8    a4. c8 e4 d8 c8    b4. c8 d4 e4    c4 a4 a2"),
        ("b", "f'4. g8 a4 g8 f8    e4. f8 e4 d8 c8   b4. c8 d4 e4    c4 a4 a2"),
    ]),
    second=None,
)

tune(
    id="minuet-g", title="Minuet in G (opening)", credit="Christian Petzold", bpm=110, bar=3,
    source=WIKI + "Minuets_in_G_major_and_G_minor", shift=0, change=None,
    lily=dict(relative="c''", order=["a"], segments=[("a", """
        d4 g,8 a b c | d4 g, g | e' c8\\mordent d e fis | g4 g, g | c d8\\mordent c b a | b4 c8 b a g | fis4 g8 a b g |
        \\grace b8( a2.) """)]),
    # The second source lists the grace note (a B) before the last note; it is left out here.
    second="D5 G A B C5 D5 G G  E5 C5 D5 E5 F#5 G5 G G  C5 D5 C5 B A B C5 B A G  F# G A B G A",
)

tune(
    id="mountain-king", title="In the Hall of the Mountain King", credit="Edvard Grieg", bpm=110, bar=4,
    source=WIKI + "In_the_Hall_of_the_Mountain_King", shift=12, change="up an octave; the four bars are written twice",
    # In the source the theme follows a held chord on F sharp (the one below middle C),
    # which is what the first note is placed relative to; only its line on the staff matters.
    lily=dict(relative="f", order=["a", "a"], segments=[("a", """
        b,8-. cis-. d-. e-. fis-.-> d-. fis4-. |
        eis8-.-> cis-. eis4-. e8-.-> c-. e4-. |
        b8-.cis-. d-. e-. fis-. d-. fis-. b-. |
        a-.-> fis-. d-. fis-. a4-.-> r4 | """)]),
    second=None,
)


# ---- Output and checks -----------------------------------------------------------------

def format_beats(beats):
    text = f"{beats:.4f}".rstrip("0").rstrip(".")
    return text


def song_text(notes, bar):
    """The app's text, with a bar line wherever a bar is complete."""
    words, lines, total = [], [], 0.0
    for note in notes:
        words.append(f"{note.name()}:{format_beats(note.beats)}")
        total += note.beats
        if abs(total / bar - round(total / bar)) < 1e-9:
            words.append("|")
    if words and words[-1] == "|":
        words.pop()
    line = ""
    for word in words:  # wrap at bar lines to keep lines readable
        if word == "|" and len(line) > 78:
            lines.append(line + " |")
            line = ""
        else:
            line = (line + " " + word).strip()
    if line:
        lines.append(line)
    return lines, total


def second_pitches(text):
    pitches = []
    for word in text.split():
        m = re.match(r"^([A-G])(#?)(\d?)$", word)
        octave = int(m.group(3)) if m.group(3) else 4
        pitches.append(12 * (octave + 1) + STEP_SEMITONES["CDEFGAB".index(m.group(1))] + (1 if m.group(2) else 0))
    return pitches


def main():
    failures = 0
    for t in TUNES:
        if "lily" in t:
            spec = t["lily"]
            notes = parse_lily(spec["segments"], spec["order"], spec["relative"], spec.get("language", "nederlands"))
        else:
            notes = parse_abc(t["abc"]["text"], t["abc"]["unit_beats"])
        for note in notes:
            if note.pitch is not None:
                note.pitch += t["shift"]
        while notes and notes[-1].pitch is None:  # a rest at the very end adds nothing
            notes.pop()
        lines, total = song_text(notes, t["bar"])
        played = [n.pitch for n in notes if n.pitch is not None]
        low, high = min(played), max(played)
        window = (low // 12) * 12  # the C at or below the lowest note
        fits = high <= window + 23
        whole_bars = abs(total / t["bar"] - round(total / t["bar"])) < 1e-9
        black = sorted({NAMES[p % 12] for p in played if "#" in NAMES[p % 12]})

        report = [f"{len(played)} notes", f"{format_beats(total)} beats",
                  f"{Note(low, 0).name()} to {Note(high, 0).name()}",
                  "white keys only" if not black else "black keys: " + " ".join(black)]
        if not fits:
            report.append("DOES NOT FIT ON 24 KEYS"); failures += 1
        if not whole_bars:
            report.append("(ends part-way through a bar)")
        if t.get("second"):
            want = second_pitches(t["second"])
            if want == played:
                report.append("pitches agree with the second source")
            else:
                at = next((i for i, (a, b) in enumerate(zip(want, played)) if a != b), min(len(want), len(played)))
                report.append(f"DIFFERS FROM THE SECOND SOURCE at note {at + 1} ({len(want)} there, {len(played)} here)")
                failures += 1
        elif t.get("second_partial"):
            want = second_pitches(t["second_partial"])
            ok = played[:len(want)] == want
            report.append(("first %d pitches agree with the second source; " % len(want) if ok else "DIFFERS; ") + t["second_note"])
            failures += 0 if ok else 1
        else:
            report.append("no second source compared")

        print(f"        // {t['source']}")
        if t["change"]:
            print(f"        // Changed from the source: {t['change']}.")
        print(f"        song(")
        title = t["title"].encode("ascii", "backslashreplace").decode()
        print(f"            \"{t['id']}\", \"{title}\", \"{t['credit']}\", {t['bpm']},")
        print('            """')
        for line in lines:
            print("            " + line)
        print('            """,')
        print("        ),")
        print("        //   check: " + "; ".join(report), file=sys.stderr)
    print(f"\n{len(TUNES)} tunes, {failures} failed checks", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
