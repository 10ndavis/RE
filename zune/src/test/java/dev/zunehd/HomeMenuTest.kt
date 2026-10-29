package dev.zunehd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeMenuTest {
    @Test fun defaultsWhenNothingSaved() {
        assertEquals(HomeMenu.DEFAULT_IDS, HomeMenu.decode(null).map { it.id })
        assertEquals(HomeMenu.DEFAULT_IDS, HomeMenu.decode("").map { it.id })
    }

    @Test fun roundTrips() {
        val menu = HomeMenu.defaults().toMutableList()
        menu[1] = menu[1].copy(label = "movies", pkg = "com.netflix.mediaclient", visible = false)
        menu += MenuItem("app:com.spotify.music", "spotify", "com.spotify.music")
        assertEquals(menu, HomeMenu.decode(HomeMenu.encode(menu)))
    }

    @Test fun keepsLabelsOnOneField() {
        val menu = listOf(MenuItem("music", "my\tmu\nsic"))
        assertEquals("my mu sic", HomeMenu.decode(HomeMenu.encode(menu))[0].label)
    }

    @Test fun addsBuiltInsMissingFromAnOlderSave() {
        val saved = HomeMenu.encode(listOf(MenuItem("apps", "apps"), MenuItem("music", "music")))
        val menu = HomeMenu.decode(saved)
        assertEquals(listOf("apps", "music"), menu.take(2).map { it.id })
        assertEquals(HomeMenu.DEFAULT_IDS.size, menu.size)
    }

    @Test fun dropsJunk() {
        val menu = HomeMenu.decode("bogus\tx\t\t1\nmusic\tmusic\t\t0\nmusic\tdup\t\t1\nshort")
        assertEquals("music", menu[0].id)
        assertFalse(menu[0].visible)
        assertEquals(1, menu.count { it.id == "music" })
    }

    @Test fun moves() {
        val m = HomeMenu.defaults()
        assertEquals(listOf("videos", "music"), HomeMenu.move(m, 0, 1).take(2).map { it.id })
        assertEquals(m, HomeMenu.move(m, 0, -1))
        assertEquals(m, HomeMenu.move(m, m.size - 1, 1))
    }

    @Test fun lettersForLabels() {
        assertEquals("s", Letters.of("Spotify"))
        assertEquals("#", Letters.of("7-zip"))
        assertEquals("#", Letters.of("éclair"))
        assertEquals("#", Letters.of(""))
    }

    @Test fun stripPicksNearestLetterWithApps() {
        val present = setOf("#", "c", "m", "z")
        assertEquals("#", Letters.pick(0f, present))
        assertEquals("z", Letters.pick(0.999f, present))
        assertEquals("m", Letters.pick(13.5f / 27, present)) // "m"
        assertEquals("m", Letters.pick(14.5f / 27, present)) // "n" is empty: nearest is "m"
        assertEquals("c", Letters.pick(-1f, setOf("c")))
        assertNull(Letters.pick(0.5f, emptySet()))
    }
}
