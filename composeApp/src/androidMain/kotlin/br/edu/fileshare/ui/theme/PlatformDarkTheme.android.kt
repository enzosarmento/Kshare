package br.edu.fileshare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
actual fun rememberPlatformDarkTheme(): Boolean = isSystemInDarkTheme()
