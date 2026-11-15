package br.edu.fileshare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.ui.format.ByteFormatter

/**
 * Conjunto leve de icones vetoriais nativos em Compose Multiplatform (`commonMain`),
 * sem dependencias extras alem de `compose.ui`.
 */
object KShareIcons {
    val Send: ImageVector by lazy {
        materialVector(
            name = "KShareSend",
            pathData = "M9,16h6v-6h4l-7,-7 -7,7h4v6zM5,18h14v2H5v-2z",
        )
    }

    val Received: ImageVector by lazy {
        materialVector(
            name = "KShareReceived",
            pathData = "M5,20h14v-2H5v2zM19,9h-4V3H9v6H5l7,7 7,-7z",
        )
    }

    val Transfers: ImageVector by lazy {
        materialVector(
            name = "KShareTransfers",
            pathData = "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z",
        )
    }

    val Settings: ImageVector by lazy {
        materialVector(
            name = "KShareSettings",
            pathData = "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
        )
    }

    val Desktop: ImageVector by lazy {
        materialVector(
            name = "KShareDesktop",
            pathData = "M21,2H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM21,16H3V4h18v12z",
        )
    }

    val Mobile: ImageVector by lazy {
        materialVector(
            name = "KShareMobile",
            pathData = "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z",
        )
    }

    val Tablet: ImageVector by lazy {
        materialVector(
            name = "KShareTablet",
            pathData = "M21,4H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 1.99,-0.9 1.99,-2L23,6c0,-1.1 -0.9,-2 -2,-2zM19,18H5V6h14v12z",
        )
    }

    val Server: ImageVector by lazy {
        materialVector(
            name = "KShareServer",
            pathData = "M20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1zM8,8H6V6h2v2zM20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1zM8,18H6v-2h2v2z",
        )
    }

    val Plus: ImageVector by lazy {
        materialVector(
            name = "KSharePlus",
            pathData = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        )
    }

    val Close: ImageVector by lazy {
        materialVector(
            name = "KShareClose",
            pathData = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        )
    }

    val Refresh: ImageVector by lazy {
        materialVector(
            name = "KShareRefresh",
            pathData = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        )
    }

    val Check: ImageVector by lazy {
        materialVector(
            name = "KShareCheck",
            pathData = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
        )
    }

    val Warning: ImageVector by lazy {
        materialVector(
            name = "KShareWarning",
            pathData = "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z",
        )
    }

    val Shield: ImageVector by lazy {
        materialVector(
            name = "KShareShield",
            pathData = "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z",
        )
    }

    val Folder: ImageVector by lazy {
        materialVector(
            name = "KShareFolder",
            pathData = "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
        )
    }

    val Radar: ImageVector by lazy {
        materialVector(
            name = "KShareRadar",
            pathData = "M12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,13c0,-3.31 -2.69,-6 -6,-6s-6,2.69 -6,6c0,2.22 1.21,4.15 3,5.19l1,-1.74c-1.19,-0.7 -2,-1.97 -2,-3.45 0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.48 -0.81,2.75 -2,3.45l1,1.74c1.79,-1.04 3,-2.97 3,-5.19zM12,3C6.48,3 2,7.48 2,13c0,3.7 2.01,6.92 4.99,8.65l1,-1.73C5.61,18.53 4,15.96 4,13c0,-4.42 3.58,-8 8,-8s8,3.58 8,8c0,2.96 -1.61,5.53 -4,6.92l1,1.73c2.99,-1.73 5,-4.95 5,-8.65 0,-5.52 -4.48,-10 -10,-10z",
        )
    }

    fun forDeviceType(deviceType: DeviceType): ImageVector = when (deviceType) {
        DeviceType.DESKTOP -> Desktop
        DeviceType.MOBILE -> Mobile
        DeviceType.TABLET -> Tablet
        DeviceType.SERVER -> Server
    }

    private fun materialVector(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()
}

@Composable
fun SemanticBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    dotColor: Color? = null,
    monospace: Boolean = false,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DeviceAvatar(
    deviceType: DeviceType,
    isSelected: Boolean = false,
    isOnline: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fgColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = modifier.size(44.dp)) {
        Surface(
            color = bgColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = KShareIcons.forDeviceType(deviceType),
                    contentDescription = ByteFormatter.formatDeviceType(deviceType),
                    tint = fgColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun FileItemCard(
    fileName: String,
    subtitle: String,
    monospaceSubtitle: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ext = ByteFormatter.formatFileExtension(fileName)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = ext,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!monospaceSubtitle.isNullOrBlank()) {
                        Text(
                            text = monospaceSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingContent()
            }
        }
    }
}
