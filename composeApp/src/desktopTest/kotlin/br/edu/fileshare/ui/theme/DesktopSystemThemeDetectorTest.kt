package br.edu.fileshare.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSystemThemeDetectorTest {

    @Test
    fun `detecta modo escuro no Cinnamon via portal color-scheme prefer-dark`() {
        val dconf = """
            [org/cinnamon/desktop/interface]
            gtk-theme='Mint-Y-Dark'

            [org/x/apps/portal]
            color-scheme='prefer-dark'
        """.trimIndent()

        assertEquals(
            true,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = dconf,
                currentDesktop = "X-Cinnamon",
            ),
        )
    }

    @Test
    fun `detecta modo claro no Cinnamon via portal color-scheme prefer-light`() {
        val dconf = """
            [org/cinnamon/desktop/interface]
            gtk-theme='Mint-Y'

            [org/x/apps/portal]
            color-scheme='prefer-light'
        """.trimIndent()

        assertEquals(
            false,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = dconf,
                currentDesktop = "X-Cinnamon",
            ),
        )
    }

    @Test
    fun `detecta modo misto e customizado no Cinnamon usando gtk-theme quando color-scheme for default`() {
        val mixedDconf = """
            [org/cinnamon/desktop/interface]
            gtk-theme='Mint-Y'

            [org/cinnamon/theme]
            name='Mint-Y-Dark'

            [org/x/apps/portal]
            color-scheme='default'
        """.trimIndent()

        assertEquals(
            false,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = mixedDconf,
                currentDesktop = "X-Cinnamon",
            ),
        )

        val customDarkDconf = """
            [org/cinnamon/desktop/interface]
            gtk-theme='Mint-Y-Dark-Aqua'

            [org/gnome/desktop/interface]
            gtk-theme='Mint-Y'

            [org/x/apps/portal]
            color-scheme='default'
        """.trimIndent()

        assertEquals(
            true,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = customDarkDconf,
                currentDesktop = "X-Cinnamon",
            ),
        )
    }

    @Test
    fun `detecta preferencias do GNOME via color-scheme e gtk-theme`() {
        val gnomePreferDark = """
            [org/gnome/desktop/interface]
            color-scheme='prefer-dark'
            gtk-theme='Yaru'
        """.trimIndent()
        assertEquals(
            true,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = gnomePreferDark,
                currentDesktop = "ubuntu:GNOME",
            ),
        )

        val gnomePreferLight = """
            [org/gnome/desktop/interface]
            color-scheme='prefer-light'
            gtk-theme='Yaru-dark'
        """.trimIndent()
        assertEquals(
            false,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = gnomePreferLight,
                currentDesktop = "GNOME",
            ),
        )

        val gnomeLegacyDark = """
            [org/gnome/desktop/interface]
            color-scheme='default'
            gtk-theme='Adwaita-dark'
        """.trimIndent()
        assertEquals(
            true,
            DesktopSystemThemeDetector.parseDconfDumpForDarkTheme(
                dconfOutput = gnomeLegacyDark,
                currentDesktop = "GNOME",
            ),
        )
    }

    @Test
    fun `interpreta fallback GTK settings ini e nomes de tema`() {
        assertNull(DesktopSystemThemeDetector.parseDconfDumpForDarkTheme("", "X-Cinnamon"))
        assertTrue(DesktopSystemThemeDetector.isDarkThemeName("Mint-Y-Dark-Blue"))
        assertTrue(DesktopSystemThemeDetector.isDarkThemeName("Arc-Darker"))
        assertFalse(DesktopSystemThemeDetector.isDarkThemeName("Mint-Y-Aqua"))

        assertEquals(
            true,
            DesktopSystemThemeDetector.parseGtkSettingsIni(
                """
                [Settings]
                gtk-application-prefer-dark-theme=1
                gtk-theme-name=Adwaita
                """.trimIndent(),
            ),
        )
        assertEquals(
            true,
            DesktopSystemThemeDetector.parseGtkSettingsIni(
                """
                [Settings]
                gtk-theme-name=Mint-Y-Dark
                """.trimIndent(),
            ),
        )
    }
}
