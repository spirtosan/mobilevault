package com.spirtosan.mobilevault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spirtosan.mobilevault.R
import com.spirtosan.mobilevault.security.PasswordStrength

/**
 * Standard page: top bar (optional back arrow + actions), scrollable column, snackbar, busy spinner.
 * With an empty [title] and no [onBack] there is no top bar (brand pages: Unlock, Onboarding).
 * [bottomBar] is drawn below the content (the list's status line).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)? = null,
    busy: Boolean = false,
    actions: @Composable () -> Unit = {},
    floating: @Composable () -> Unit = {},
    scroll: Boolean = true,
    titleColor: Color = Palette.fgPrimary,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            if (title.isNotEmpty() || onBack != null) TopAppBar(
                title = { Text(title, color = titleColor, fontWeight = FontWeight.Bold, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.fgSecondary)
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.bgToolbar),
            )
        },
        snackbarHost = { MessageHost(snackbar) },
        floatingActionButton = floating,
        bottomBar = bottomBar,
        containerColor = Palette.bgMain,
    ) { inner ->
        Box(Modifier.padding(inner).imePadding()) {
            val m = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            Column(
                if (scroll) m.verticalScroll(rememberScrollState()) else m,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.orange)
        }
    }
}

// ── messages ──────────────────────────────────────────────────────────────────
/** Snackbar text plus whether it reports a problem (red accent) or a success (green accent). */
class Message(override val message: String, val error: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Short
}

@Composable
private fun MessageHost(state: SnackbarHostState) = SnackbarHost(state) { data ->
    val error = (data.visuals as? Message)?.error ?: false
    Snackbar(
        containerColor = Palette.bgPanel, contentColor = Palette.fgPrimary,
        modifier = Modifier.padding(12.dp).testTag(if (error) "msgError" else "msgOk"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(20.dp).background(if (error) Palette.red else Palette.green, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(data.visuals.message)
        }
    }
}

// ── buttons (desktop colour meaning) ─────────────────────────────────────────
private val ButtonShape = RoundedCornerShape(10.dp)

/**
 * A filled, rounded button with a small leading icon, like the desktop toolbar. Colours by meaning:
 * orange main action, green add, blue edit, red delete/lock, teal/steel/purple copy, amber show, violet new/import.
 */
@Composable
fun ColorButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    textColor: Color = Color.White,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = textColor,
            disabledContainerColor = Palette.bgHover, disabledContentColor = Palette.fgMuted,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/**
 * Outlined variant: for actions that should be clearly available but less prominent (Skip), or
 * destructive actions that shouldn't shout (Remove recovery key). [color] is used for text and border.
 */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    borderColor: Color = color,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        border = BorderStroke(1.5.dp, if (enabled) borderColor else Palette.bgHover),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = Palette.fgMuted),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Unlock, Create vault, Save, Open: orange with dark bold text. */
@Composable
fun MainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) =
    ColorButton(text, onClick, Palette.orange, modifier, icon, Palette.onOrange, enabled)

/** Cancel, Skip and other secondary actions. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) =
    ColorButton(text, onClick, Palette.bgHover, modifier, icon, Palette.fgSecondary, enabled)

// ── layout pieces ────────────────────────────────────────────────────────────
/** Centred app icon and "MOBILE VAULT" in orange, like the desktop sidebar title. */
@Composable
fun BrandHeader(subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_launcher_art), null, Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)))
        Spacer(Modifier.height(14.dp))
        Text("MOBILE VAULT", color = Palette.orange, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 2.sp)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Palette.fgSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/** Rounded `bgPanel` card; with a [title] it gets an orange section header (Settings). */
@Composable
fun PanelCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.bgPanel), shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, color = Palette.orange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** Round coloured initial for a list row; the colour is stable per service name. */
@Composable
fun Initial(name: String) {
    val c = Palette.accents[Math.floorMod(name.trim().lowercase().hashCode(), Palette.accents.size)]
    Box(Modifier.size(40.dp).background(c, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

/** Dialog on `bgPanel`; the confirm button is coloured by meaning (red for destructive). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = Palette.orange,
    dismiss: String = "Cancel",
    confirmTag: String = "dialogConfirm",
    confirmEnabled: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) = AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Palette.bgPanel,
    title = { Text(title, color = Palette.fgPrimary, fontWeight = FontWeight.Bold) },
    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(text, color = Palette.fgSecondary); body?.invoke(this) } },
    confirmButton = {
        ColorButton(confirm, onConfirm, confirmColor, Modifier.testTag(confirmTag), enabled = confirmEnabled,
            textColor = if (confirmColor == Palette.orange) Palette.onOrange else Color.White)
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = Palette.fgSecondary) } },
)

// ── inputs ───────────────────────────────────────────────────────────────────
@Composable
fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Palette.bgInput, unfocusedContainerColor = Palette.bgInput, disabledContainerColor = Palette.bgInput,
    errorContainerColor = Palette.bgInput,
    focusedBorderColor = Palette.orange, unfocusedBorderColor = Palette.sep,
    focusedLabelColor = Palette.orange, unfocusedLabelColor = Palette.fgSecondary,
    cursorColor = Palette.orange,
)

/** Password input: no autocorrect, password keyboard, amber eye to show/hide. */
@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tag: String = label,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    isError: Boolean = false,
    mono: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Hide" else "Show", tint = Palette.amber)
            }
        },
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        colors = fieldColors(),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    tag: String = label,
    leading: ImageVector? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        leadingIcon = leading?.let { { Icon(it, null, tint = Palette.fgSecondary) } },
        colors = fieldColors(), shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * Recovery-key input: one line, ASCII keyboard with capitals, no autocorrect or suggestions.
 * The value is never rewritten while typing; line breaks from a pasted two-line key become spaces.
 */
@Composable
fun KeyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String = label,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.replace('\n', ' ').replace('\r', ' ')) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false, imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        colors = fieldColors(), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
fun StrengthMeter(password: String) {
    if (password.isEmpty()) return
    val chars = password.toCharArray()
    val s = PasswordStrength.score(chars)
    chars.fill('\u0000')
    val widths = listOf(0.14f, 0.34f, 0.55f, 0.77f, 1f)
    Column {
        Box(Modifier.fillMaxWidth().height(5.dp).background(Palette.bgInput, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(widths[s.level]).height(5.dp).background(Palette.strength[s.level], RoundedCornerShape(3.dp)))
        }
        Text("${s.label} (~${s.bits} bits)", color = Palette.strength[s.level], style = MaterialTheme.typography.labelSmall)
    }
}

/** Segmented choice; wraps to a second line at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) Text(label, color = Palette.fgPrimary, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                val sel = o == selected
                TextButton(
                    onClick = { onSelect(o) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.background(if (sel) Palette.selectBg else Palette.bgInput, RoundedCornerShape(8.dp)),
                ) { Text(text(o), color = if (sel) Color.White else Palette.fgSecondary, fontWeight = if (sel) FontWeight.Bold else null) }
            }
        }
    }
}

@Composable
fun Muted(text: String) = Text(text, color = Palette.fgSecondary, style = MaterialTheme.typography.bodySmall)
