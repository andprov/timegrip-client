package ru.timegrip.app.ui.common

import android.util.Patterns
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.timegrip.app.AppContainer
import ru.timegrip.app.R
import ru.timegrip.app.domain.SyncMark
import java.time.Instant

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer is not provided") }

/** A ViewModel built from the app's dependencies, scoped to the current destination. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel { create(container) }
}

fun parseColor(hex: String): Color =
    runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)

@Composable
fun ColorDot(color: String, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(parseColor(color)),
    )
}

/** The current time, ticking every second while [enabled]. */
@Composable
fun rememberNow(enabled: Boolean = true): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(enabled) {
        while (enabled) {
            now = Instant.now()
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    return now
}

@Composable
fun MessageCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

enum class BannerKind { ERROR, INFO, SUCCESS }

/** Web: components/ui/Alert.tsx. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.ERROR) {
    val (container, content) = when (kind) {
        BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BannerKind.INFO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        BannerKind.SUCCESS -> Color(0xFFDCFCE7) to Color(0xFF166534)
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kind == BannerKind.ERROR) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ErrorBanner(message: UiMessage?, modifier: Modifier = Modifier) {
    if (message != null) Banner(message.text(), modifier)
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    supportingText: String? = null,
    isError: Boolean = false,
    /** Shown in place of [supportingText], in red; see [requiredError]. */
    errorText: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError || errorText != null,
        supportingText = (errorText ?: supportingText)?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The error under a required field left empty: shown once the form was
 * submitted ([submitted]) and gone as soon as something is typed in.
 */
@Composable
fun requiredError(submitted: Boolean, value: String): String? =
    if (submitted && value.isBlank()) stringResource(R.string.field_required) else null

/** Roughly what the server accepts; it still has the last word. */
fun isValidEmail(value: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()

/** [requiredError], or a malformed address once the form was submitted. */
@Composable
fun emailError(submitted: Boolean, value: String): String? =
    requiredError(submitted, value)
        ?: if (submitted && !isValidEmail(value)) stringResource(R.string.error_validation_email) else null

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    confirming: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !confirming) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Material full-screen dialog for forms. */
@Composable
fun FullScreenDialog(
    title: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FullScreenDialogFrame(
        title = title,
        // Deleting is the one thing that does not confirm the form, so it stays in the top bar,
        // away from the button that saves it.
        actionLabel = if (onDelete == null) null else stringResource(R.string.delete),
        onDismiss = onDismiss,
        onAction = onDelete,
        actionEnabled = deleteEnabled,
        actionDestructive = true,
    ) { padding ->
        ConfirmButtonLayout(
            padding,
            button = { ConfirmButton(actionLabel, enabled = actionEnabled, onClick = onAction) },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * The body of a [FullScreenDialogFrame] above its confirm [button]. The keyboard
 * covers the button rather than pushing it up: the button keeps its place at the
 * bottom and only the body shrinks, down to the keyboard's top edge, so the
 * field being typed in can still be scrolled into view.
 */
@Composable
fun ConfirmButtonLayout(
    padding: PaddingValues,
    button: (@Composable () -> Unit)?,
    body: @Composable ColumnScope.() -> Unit,
) {
    var buttonHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // What already lies between the body and the bottom edge: the system bar and the button.
    // The keyboard rises from that edge, so the body gives way only to the part above them.
    val below = PaddingValues(bottom = padding.calculateBottomPadding() + with(density) { buttonHeight.toDp() })
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(
            Modifier
                .weight(1f)
                .consumeWindowInsets(below)
                .imePadding(),
            content = body,
        )
        if (button != null) {
            Box(Modifier.onSizeChanged { buttonHeight = it.height }) { button() }
        }
    }
}

/**
 * A full-screen dialog with a close button and, when [actionLabel] is given,
 * an action in the top bar; [content] lays itself out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenDialogFrame(
    title: String,
    actionLabel: String?,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)?,
    actionEnabled: Boolean = true,
    actionDestructive: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog has its own window: match its status bar icons to the theme, and make it
        // resize for the keyboard itself (it doesn't inherit the host activity's soft input
        // mode), otherwise the OS pans the whole window, confirm button and all.
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        if (actionLabel != null && onAction != null) {
                            TextButton(onClick = onAction, enabled = actionEnabled) {
                                Text(
                                    actionLabel,
                                    color = if (actionDestructive) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Color.Unspecified
                                    },
                                )
                            }
                        }
                    },
                )
            },
            // Without the keyboard: the content makes room for it itself, see ConfirmButtonLayout.
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
            content = content,
        )
    }
}

/**
 * The confirm button of a full-screen window: full width, at the bottom, under
 * whatever the window shows. Windows where a choice has to be confirmed all use
 * this one, so the button reads and sits the same everywhere.
 */
@Composable
fun ConfirmButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) { Text(label) }
}

/**
 * Wraps a screen's floating button: it is there at the top of [listState] and
 * on the way back up, and fades out while the list is scrolled down, where it
 * would only cover the rows the reader is moving towards.
 */
@Composable
fun FabOnList(listState: LazyListState, content: @Composable () -> Unit) {
    val visible by remember(listState) {
        derivedStateOf { !listState.canScrollBackward || listState.lastScrolledBackward }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), content = { content() })
}

/** Marks a record whose changes are not on the server yet. */
@Composable
fun SyncMarkIcon(mark: SyncMark, modifier: Modifier = Modifier) {
    when (mark) {
        SyncMark.SYNCED -> Unit
        SyncMark.PENDING -> Icon(
            Icons.Outlined.CloudUpload,
            contentDescription = stringResource(R.string.sync_mark_pending),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(16.dp),
        )
        SyncMark.FAILED -> Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.sync_mark_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(16.dp),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A card on the screen background, like the web `Card`. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = CardDefaults.outlinedCardBorder()
    if (onClick == null) {
        Card(modifier.fillMaxWidth(), colors = colors, border = border) {
            Box(Modifier.padding(contentPadding)) { content() }
        }
    } else {
        Card(onClick, modifier.fillMaxWidth(), colors = colors, border = border) {
            Box(Modifier.padding(contentPadding)) { content() }
        }
    }
}
