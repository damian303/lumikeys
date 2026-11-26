package com.dhobson.lumi.learn

/**
 * The built-in tunes, roughly from easiest to hardest. All are traditional melodies or
 * by composers long out of copyright, written out here as single-line melodies that fit
 * on two octaves.
 *
 * The tunes with a web address above them were converted from published notation by
 * `tools/tunes_from_sources.py`, which also records what was changed (key, octave,
 * repeats) and checks most of them against a second source. Change those there and
 * paste the result here, rather than editing the notes by hand. The others were written
 * from memory and have been checked by ear.
 */
object Songs {
    private fun song(id: String, title: String, credit: String, bpm: Int, text: String): Song {
        val notes = checkNotNull(SongText.parse(text)) { "song '$id' does not parse" }
        return Song(id, title, credit, bpm, notes)
    }

    val all: List<Song> = listOf(
        song(
            "hot-cross-buns", "Hot Cross Buns", "Traditional", 100,
            """
            E4:1 D4:1 C4:2 | E4:1 D4:1 C4:2 |
            C4:0.5 C4:0.5 C4:0.5 C4:0.5 D4:0.5 D4:0.5 D4:0.5 D4:0.5 | E4:1 D4:1 C4:2
            """,
        ),
        song(
            "mary-lamb", "Mary Had a Little Lamb", "Traditional", 110,
            """
            E4:1 D4:1 C4:1 D4:1 | E4:1 E4:1 E4:2 | D4:1 D4:1 D4:2 | E4:1 G4:1 G4:2 |
            E4:1 D4:1 C4:1 D4:1 | E4:1 E4:1 E4:1 E4:1 | D4:1 D4:1 E4:1 D4:1 | C4:4
            """,
        ),
        song(
            "twinkle", "Twinkle, Twinkle, Little Star", "Traditional", 100,
            """
            C4:1 C4:1 G4:1 G4:1 | A4:1 A4:1 G4:2 | F4:1 F4:1 E4:1 E4:1 | D4:1 D4:1 C4:2 |
            G4:1 G4:1 F4:1 F4:1 | E4:1 E4:1 D4:2 | G4:1 G4:1 F4:1 F4:1 | E4:1 E4:1 D4:2 |
            C4:1 C4:1 G4:1 G4:1 | A4:1 A4:1 G4:2 | F4:1 F4:1 E4:1 E4:1 | D4:1 D4:1 C4:2
            """,
        ),
        song(
            "frere-jacques", "Fr\u00e8re Jacques", "Traditional", 110,
            """
            C4:1 D4:1 E4:1 C4:1 | C4:1 D4:1 E4:1 C4:1 | E4:1 F4:1 G4:2 | E4:1 F4:1 G4:2 |
            G4:0.5 A4:0.5 G4:0.5 F4:0.5 E4:1 C4:1 | G4:0.5 A4:0.5 G4:0.5 F4:0.5 E4:1 C4:1 |
            C4:1 G3:1 C4:2 | C4:1 G3:1 C4:2
            """,
        ),
        // https://fr.wikipedia.org/wiki/Au_clair_de_la_lune
        // Changed from the source: down an octave.
        song(
            "au-clair-lune", "Au Clair de la Lune", "Traditional", 110,
            """
            C4:1 C4:1 C4:1 D4:1 | E4:2 D4:2 | C4:1 E4:1 D4:1 D4:1 | C4:4 | C4:1 C4:1 C4:1 D4:1 |
            E4:2 D4:2 | C4:1 E4:1 D4:1 D4:1 | C4:4 | D4:1 D4:1 D4:1 D4:1 | A3:2 A3:2 | D4:1 C4:1 B3:1 A3:1 |
            G3:4 | C4:1 C4:1 C4:1 D4:1 | E4:2 D4:2 | C4:1 E4:1 D4:1 D4:1 | C4:4
            """,
        ),
        // https://en.wikipedia.org/wiki/London_Bridge_Is_Falling_Down
        // Changed from the source: from F to C.
        song(
            "london-bridge", "London Bridge Is Falling Down", "Traditional", 100,
            """
            G4:0.75 A4:0.25 G4:0.5 F4:0.5 | E4:0.5 F4:0.5 G4:1 | D4:0.5 E4:0.5 F4:1 | E4:0.5 F4:0.5 G4:1 |
            G4:0.75 A4:0.25 G4:0.5 F4:0.5 | E4:0.5 F4:0.5 G4:1 | D4:1 G4:1 | E4:0.5 C4:1.5
            """,
        ),
        // https://en.wikipedia.org/wiki/H%C3%A4nschen_klein
        // Changed from the source: from F to C.
        song(
            "lightly-row", "Lightly Row", "Traditional", 100,
            """
            G4:0.5 E4:0.5 E4:1 | F4:0.5 D4:0.5 D4:1 | C4:0.5 D4:0.5 E4:0.5 F4:0.5 | G4:0.5 G4:0.5 G4:1 |
            G4:0.5 E4:0.5 E4:1 | F4:0.5 D4:0.5 D4:1 | C4:0.5 E4:0.5 G4:0.5 G4:0.5 | C4:1 -:1 |
            D4:0.5 D4:0.5 D4:0.5 D4:0.5 | D4:0.5 E4:0.5 F4:1 | E4:0.5 E4:0.5 E4:0.5 E4:0.5 | E4:0.5 F4:0.5 G4:1 |
            G4:0.5 E4:0.5 E4:1 | F4:0.5 D4:0.5 D4:1 | C4:0.5 E4:0.5 G4:0.5 G4:0.5 | C4:2
            """,
        ),
        // https://en.wikipedia.org/wiki/Row,_Row,_Row_Your_Boat
        song(
            "row-boat", "Row, Row, Row Your Boat", "Traditional", 110,
            """
            C4:1.5 C4:1.5 | C4:1 D4:0.5 E4:1.5 | E4:1 D4:0.5 E4:1 F4:0.5 | G4:3 | C5:0.5 C5:0.5 C5:0.5 G4:0.5 G4:0.5 G4:0.5 |
            E4:0.5 E4:0.5 E4:0.5 C4:0.5 C4:0.5 C4:0.5 | G4:1 F4:0.5 E4:1 D4:0.5 | C4:3
            """,
        ),
        // https://de.wikipedia.org/wiki/When_the_Saints_Go_Marching_In
        song(
            "when-saints", "When the Saints Go Marching In", "Traditional", 120,
            """
            -:0.5 C4:0.5 E4:0.5 F4:0.5 | G4:2 | -:0.5 C4:0.5 E4:0.5 F4:0.5 | G4:2 | -:0.5 C4:0.5 E4:0.5 F4:0.5 |
            G4:1 E4:1 | C4:1 E4:1 | D4:2 | -:0.5 E4:0.5 E4:0.5 D4:0.5 | C4:2 | E4:1 G4:1 | G4:0.5 F4:1.5 |
            -:1 E4:0.5 F4:0.5 | G4:1 E4:1 | C4:1 D4:1 | C4:2
            """,
        ),
        song(
            "ode-to-joy", "Ode to Joy", "Ludwig van Beethoven", 110,
            """
            E4:1 E4:1 F4:1 G4:1 | G4:1 F4:1 E4:1 D4:1 | C4:1 C4:1 D4:1 E4:1 | E4:1.5 D4:0.5 D4:2 |
            E4:1 E4:1 F4:1 G4:1 | G4:1 F4:1 E4:1 D4:1 | C4:1 C4:1 D4:1 E4:1 | D4:1.5 C4:0.5 C4:2
            """,
        ),
        song(
            "jingle-bells", "Jingle Bells", "James Pierpont", 120,
            """
            E4:1 E4:1 E4:2 | E4:1 E4:1 E4:2 | E4:1 G4:1 C4:1.5 D4:0.5 | E4:4 |
            F4:1 F4:1 F4:1.5 F4:0.5 | F4:1 E4:1 E4:1 E4:0.5 E4:0.5 | E4:1 D4:1 D4:1 E4:1 | D4:2 G4:2
            """,
        ),
        // https://en.wikipedia.org/wiki/Joy_to_the_World
        // Changed from the source: from D to C.
        song(
            "joy-to-world", "Joy to the World", "Lowell Mason", 90,
            """
            C5:1 B4:0.75 A4:0.25 | G4:1.5 F4:0.5 | E4:1 D4:1 | C4:1.5 G4:0.5 | A4:1.5 A4:0.5 |
            B4:1.5 B4:0.5 | C5:3.5 C5:0.5 | C5:0.5 B4:0.5 A4:0.5 G4:0.5 | G4:0.75 F4:0.25 E4:0.5 C5:0.5 |
            C5:0.5 B4:0.5 A4:0.5 G4:0.5 | G4:0.75 F4:0.25 E4:0.5 E4:0.5 | E4:0.5 E4:0.5 E4:0.5 E4:0.25 F4:0.25 |
            G4:1.5 F4:0.25 E4:0.25 | D4:0.5 D4:0.5 D4:0.5 D4:0.25 E4:0.25 | F4:1.5 E4:0.25 D4:0.25 |
            E4:0.5 C5:1 A4:0.5 | G4:0.75 F4:0.25 E4:0.5 F4:0.5 | E4:1 D4:1 | C4:2
            """,
        ),
        song(
            "happy-birthday", "Happy Birthday", "Patty and Mildred Hill", 100,
            """
            G4:0.75 G4:0.25 | A4:1 G4:1 C5:1 | B4:2 G4:0.75 G4:0.25 | A4:1 G4:1 D5:1 | C5:2 G4:0.75 G4:0.25 |
            G5:1 E5:1 C5:1 | B4:1 A4:1 F5:0.75 F5:0.25 | E5:1 C5:1 D5:1 | C5:2
            """,
        ),
        // https://en.wikipedia.org/wiki/Silent_Night
        song(
            "silent-night", "Silent Night", "Franz Xaver Gruber", 66,
            """
            G4:0.75 A4:0.25 G4:0.5 E4:1.5 | G4:0.75 A4:0.25 G4:0.5 E4:1.5 | D5:1 D5:0.5 B4:1.5 |
            C5:1 C5:0.5 G4:1.5 | A4:1 A4:0.5 C5:0.75 B4:0.25 A4:0.5 | G4:0.75 A4:0.25 G4:0.5 E4:1.5 |
            A4:1 A4:0.5 C5:0.75 B4:0.25 A4:0.5 | G4:0.75 A4:0.25 G4:0.5 E4:1.5 | D5:1 D5:0.5 F5:0.75 D5:0.25 B4:0.5 |
            C5:1.5 E5:1 -:0.5 | C5:0.75 G4:0.25 E4:0.5 G4:0.75 F4:0.25 D4:0.5 | C4:2.5
            """,
        ),
        // https://de.wikipedia.org/wiki/Drunken_Sailor
        song(
            "drunken-sailor", "Drunken Sailor", "Traditional", 90,
            """
            A4:0.5 A4:0.25 A4:0.25 A4:0.5 A4:0.25 A4:0.25 | A4:0.5 D4:0.5 F4:0.5 A4:0.5 | G4:0.5 G4:0.25 G4:0.25 G4:0.5 G4:0.25 G4:0.25 |
            G4:0.5 C4:0.5 E4:0.5 G4:0.5 | A4:0.5 A4:0.25 A4:0.25 A4:0.5 A4:0.25 A4:0.25 | A4:0.5 B4:0.5 C5:0.5 D5:0.5 |
            C5:0.5 A4:0.5 G4:0.5 E4:0.5 | D4:1 D4:1 | A4:1 A4:0.75 A4:0.25 | A4:0.5 D4:0.5 F4:0.5 A4:0.5 |
            G4:1 G4:0.75 G4:0.25 | G4:0.5 C4:0.5 E4:0.5 G4:0.5 | A4:1 A4:0.75 A4:0.25 | A4:0.5 B4:0.5 C5:0.5 D5:0.5 |
            C5:0.5 A4:0.5 G4:0.5 E4:0.5 | D4:1 D4:1
            """,
        ),
        // https://en.wikipedia.org/wiki/Korobeiniki
        song(
            "korobeiniki", "Korobeiniki (the Tetris tune)", "Russian folk song", 120,
            """
            E4:1.5 G#4:0.5 B4:1 G#4:0.5 E4:0.5 | A4:1.5 C5:0.5 E5:1 D5:0.5 C5:0.5 | B4:1.5 C5:0.5 D5:1 E5:1 |
            C5:1 A4:1 A4:2 | F5:1.5 G5:0.5 A5:1 G5:0.5 F5:0.5 | E5:1.5 F5:0.5 E5:1 D5:0.5 C5:0.5 |
            B4:1.5 C5:0.5 D5:1 E5:1 | C5:1 A4:1 A4:2 | F5:1.5 G5:0.5 A5:1 G5:0.5 F5:0.5 | E5:1.5 F5:0.5 E5:1 D5:0.5 C5:0.5 |
            B4:1.5 C5:0.5 D5:1 E5:1 | C5:1 A4:1 A4:2
            """,
        ),
        // https://en.wikipedia.org/wiki/Minuets_in_G_major_and_G_minor
        song(
            "minuet-g", "Minuet in G (opening)", "Christian Petzold", 110,
            """
            D5:1 G4:0.5 A4:0.5 B4:0.5 C5:0.5 | D5:1 G4:1 G4:1 | E5:1 C5:0.5 D5:0.5 E5:0.5 F#5:0.5 |
            G5:1 G4:1 G4:1 | C5:1 D5:0.5 C5:0.5 B4:0.5 A4:0.5 | B4:1 C5:0.5 B4:0.5 A4:0.5 G4:0.5 |
            F#4:1 G4:0.5 A4:0.5 B4:0.5 G4:0.5 | A4:3
            """,
        ),
        // https://en.wikipedia.org/wiki/In_the_Hall_of_the_Mountain_King
        // Changed from the source: up an octave; the four bars are written twice.
        song(
            "mountain-king", "In the Hall of the Mountain King", "Edvard Grieg", 110,
            """
            B3:0.5 C#4:0.5 D4:0.5 E4:0.5 F#4:0.5 D4:0.5 F#4:1 | F4:0.5 C#4:0.5 F4:1 E4:0.5 C4:0.5 E4:1 |
            B3:0.5 C#4:0.5 D4:0.5 E4:0.5 F#4:0.5 D4:0.5 F#4:0.5 B4:0.5 | A4:0.5 F#4:0.5 D4:0.5 F#4:0.5 A4:1 -:1 |
            B3:0.5 C#4:0.5 D4:0.5 E4:0.5 F#4:0.5 D4:0.5 F#4:1 | F4:0.5 C#4:0.5 F4:1 E4:0.5 C4:0.5 E4:1 |
            B3:0.5 C#4:0.5 D4:0.5 E4:0.5 F#4:0.5 D4:0.5 F#4:0.5 B4:0.5 | A4:0.5 F#4:0.5 D4:0.5 F#4:0.5 A4:1
            """,
        ),
        song(
            "fur-elise", "F\u00fcr Elise (opening)", "Ludwig van Beethoven", 120,
            """
            E5:0.5 D#5:0.5 | E5:0.5 D#5:0.5 E5:0.5 B4:0.5 D5:0.5 C5:0.5 | A4:1 -:0.5 C4:0.5 E4:0.5 A4:0.5 |
            B4:1 -:0.5 E4:0.5 G#4:0.5 B4:0.5 | C5:1 -:0.5 E4:0.5 E5:0.5 D#5:0.5 |
            E5:0.5 D#5:0.5 E5:0.5 B4:0.5 D5:0.5 C5:0.5 | A4:1 -:0.5 C4:0.5 E4:0.5 A4:0.5 |
            B4:1 -:0.5 E4:0.5 C5:0.5 B4:0.5 | A4:2
            """,
        ),
    )

    fun byId(id: String): Song? = all.firstOrNull { it.id == id }
}
