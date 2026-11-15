package br.edu.fileshare.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

/**
 * Detector multiplataforma de tema escuro no Desktop, com suporte completo a ambientes Linux
 * (Cinnamon/Linux Mint, GNOME, MATE, XFCE, KDE e portais XDG) e fallback para Windows/macOS.
 */
object DesktopSystemThemeDetector {

    private val DARK_NAME_TOKENS = listOf("dark", "inverse", "night", "black")
    private const val COMMAND_TIMEOUT_MS = 500L

    fun isDarkThemeName(themeName: String?): Boolean {
        if (themeName.isNullOrBlank()) return false
        val normalized = themeName.lowercase()
        return DARK_NAME_TOKENS.any { token -> normalized.contains(token) }
    }

    fun parseColorSchemePreference(rawValue: String?): Boolean? =
        when (stripQuotes(rawValue)?.lowercase()) {
            "prefer-dark" -> true
            "prefer-light" -> false
            else -> null
        }

    /**
     * Interpreta a saida de `dconf dump /` priorizando preferencias explicitas de color-scheme
     * (`org.x.apps.portal` e `org.gnome.desktop.interface`) e, quando em `default`, o `gtk-theme` ativo.
     */
    fun parseDconfDumpForDarkTheme(
        dconfOutput: String,
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
    ): Boolean? {
        if (dconfOutput.isBlank()) return null

        var currentSection = ""
        val valuesBySection = mutableMapOf<String, MutableMap<String, String>>()

        for (rawLine in dconfOutput.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                currentSection = line.substring(1, line.length - 1).trim()
                continue
            }
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0 || currentSection.isEmpty()) continue
            val key = line.substring(0, separatorIndex).trim()
            val value = stripQuotes(line.substring(separatorIndex + 1)) ?: continue
            valuesBySection.getOrPut(currentSection) { mutableMapOf() }[key] = value
        }

        val portalColorScheme = valuesBySection["org/x/apps/portal"]?.get("color-scheme")
        parseColorSchemePreference(portalColorScheme)?.let { return it }

        val gnomeColorScheme = valuesBySection["org/gnome/desktop/interface"]?.get("color-scheme")
        parseColorSchemePreference(gnomeColorScheme)?.let { return it }

        val cinnamonGtkTheme = valuesBySection["org/cinnamon/desktop/interface"]?.get("gtk-theme")
        val gnomeGtkTheme = valuesBySection["org/gnome/desktop/interface"]?.get("gtk-theme")
        val mateGtkTheme = valuesBySection["org/mate/desktop/interface"]?.get("gtk-theme")

        val activeGtkTheme = selectGtkThemeForDesktop(
            currentDesktop = currentDesktop,
            cinnamonGtkTheme = cinnamonGtkTheme,
            gnomeGtkTheme = gnomeGtkTheme,
            mateGtkTheme = mateGtkTheme,
        )
        if (!activeGtkTheme.isNullOrBlank()) {
            return isDarkThemeName(activeGtkTheme)
        }
        return null
    }

    internal fun parseGtkSettingsIni(iniContent: String): Boolean? {
        if (iniContent.isBlank()) return null
        var themeName: String? = null
        for (rawLine in iniContent.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
            val sep = line.indexOf('=')
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim().lowercase()
            val value = stripQuotes(line.substring(sep + 1)) ?: continue
            when (key) {
                "gtk-application-prefer-dark-theme" -> {
                    when (value.lowercase()) {
                        "true", "1", "yes" -> return true
                        "false", "0", "no" -> return false
                    }
                }
                "gtk-theme-name" -> if (value.isNotBlank()) themeName = value
            }
        }
        return themeName?.let(::isDarkThemeName)
    }

    internal fun parseKdeGlobals(content: String): Boolean? {
        if (content.isBlank()) return null
        var colorScheme: String? = null
        var lookAndFeel: String? = null
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
            val sep = line.indexOf('=')
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim()
            val value = stripQuotes(line.substring(sep + 1)) ?: continue
            when (key) {
                "ColorScheme" -> if (value.isNotBlank()) colorScheme = value
                "LookAndFeelPackage" -> if (value.isNotBlank()) lookAndFeel = value
            }
        }
        val candidate = colorScheme ?: lookAndFeel
        return candidate?.let(::isDarkThemeName)
    }

    fun detectIsDarkTheme(
        osName: String = System.getProperty("os.name").orEmpty(),
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
    ): Boolean {
        if (osName.contains("Linux", ignoreCase = true)) {
            detectLinuxDarkTheme(currentDesktop = currentDesktop)?.let { return it }
        }
        return currentSystemTheme == SystemTheme.DARK
    }

    private fun detectLinuxDarkTheme(currentDesktop: String): Boolean? {
        runCommandCapture("dconf", "dump", "/")?.let { dump ->
            parseDconfDumpForDarkTheme(dump, currentDesktop)?.let { return it }
        }

        queryGsettings("org.x.apps.portal", "color-scheme")
            ?.let(::parseColorSchemePreference)
            ?.let { return it }

        queryGsettings("org.gnome.desktop.interface", "color-scheme")
            ?.let(::parseColorSchemePreference)
            ?.let { return it }

        val gtkSchemas = if (currentDesktop.contains("cinnamon", ignoreCase = true)) {
            listOf("org.cinnamon.desktop.interface", "org.gnome.desktop.interface")
        } else {
            listOf("org.gnome.desktop.interface", "org.cinnamon.desktop.interface")
        }
        for (schema in gtkSchemas) {
            val themeName = queryGsettings(schema, "gtk-theme")
            if (!themeName.isNullOrBlank()) {
                return isDarkThemeName(themeName)
            }
        }

        System.getenv("GTK_THEME")?.trim()?.takeIf { it.isNotEmpty() }?.let { gtkThemeEnv ->
            return isDarkThemeName(gtkThemeEnv)
        }

        val configHome = resolveXdgConfigHome()
        listOf("gtk-4.0/settings.ini", "gtk-3.0/settings.ini").forEach { relativePath ->
            val file = File(configHome, relativePath)
            if (file.isFile) {
                runCatching { file.readText() }.getOrNull()
                    ?.let(::parseGtkSettingsIni)
                    ?.let { return it }
            }
        }

        val kdeGlobals = File(configHome, "kdeglobals")
        if (kdeGlobals.isFile) {
            runCatching { kdeGlobals.readText() }.getOrNull()
                ?.let(::parseKdeGlobals)
                ?.let { return it }
        }

        return null
    }

    private fun selectGtkThemeForDesktop(
        currentDesktop: String,
        cinnamonGtkTheme: String?,
        gnomeGtkTheme: String?,
        mateGtkTheme: String?,
    ): String? = when {
        currentDesktop.contains("cinnamon", ignoreCase = true) ->
            cinnamonGtkTheme ?: gnomeGtkTheme ?: mateGtkTheme
        currentDesktop.contains("mate", ignoreCase = true) ->
            mateGtkTheme ?: gnomeGtkTheme ?: cinnamonGtkTheme
        else ->
            gnomeGtkTheme ?: cinnamonGtkTheme ?: mateGtkTheme
    }

    private fun queryGsettings(schema: String, key: String): String? {
        val raw = runCommandCapture("gsettings", "get", schema, key) ?: return null
        val cleaned = stripQuotes(raw) ?: return null
        if (cleaned.startsWith("No such", ignoreCase = true)) return null
        return cleaned
    }

    private fun stripQuotes(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.length >= 2 &&
            ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
                (trimmed.startsWith("\"") && trimmed.endsWith("\"")))
        ) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    private fun runCommandCapture(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() == 0) output else null
    }.getOrNull()

    internal fun watchedConfigFiles(): List<File> {
        val configHome = resolveXdgConfigHome()
        return listOf(
            File(configHome, "dconf/user"),
            File(configHome, "gtk-4.0/settings.ini"),
            File(configHome, "gtk-3.0/settings.ini"),
            File(configHome, "kdeglobals"),
        )
    }

    private fun resolveXdgConfigHome(): File {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        if (xdg != null) return File(xdg)
        return File(System.getProperty("user.home"), ".config")
    }
}

private fun currentConfigSignature(files: List<File>): List<Pair<Long, Long>> =
    files.map { file ->
        if (file.exists()) file.lastModified() to file.length() else -1L to -1L
    }

@Composable
actual fun rememberPlatformDarkTheme(): Boolean {
    val initial = remember { DesktopSystemThemeDetector.detectIsDarkTheme() }
    val darkTheme by produceState(initialValue = initial) {
        val watchedFiles = DesktopSystemThemeDetector.watchedConfigFiles()
        var lastSignature = withContext(Dispatchers.IO) { currentConfigSignature(watchedFiles) }
        var tick = 0
        while (isActive) {
            delay(750)
            tick++
            val nextSignature = withContext(Dispatchers.IO) { currentConfigSignature(watchedFiles) }
            if (nextSignature != lastSignature || tick % 4 == 0) {
                lastSignature = nextSignature
                value = withContext(Dispatchers.IO) {
                    DesktopSystemThemeDetector.detectIsDarkTheme()
                }
            }
        }
    }
    return darkTheme
}
