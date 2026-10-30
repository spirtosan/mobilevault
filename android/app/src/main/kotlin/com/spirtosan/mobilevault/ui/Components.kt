package com.spirtosan.mobilevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.spirtosan.mobilevault.security.PasswordStrength

/** Standard page: top bar (optional back arrow + actions), scrollable column, snackbar, busy spinner. */
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
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.bgToolbar),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = floating,
    ) { inner ->
        Box(Modifier.padding(inner).imePadding()) {
            val m = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            Column(
                if (scroll) m.verticalScroll(rememberScrollState()) else m,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** Password input: no autocorrect, password keyboard, show/hide toggle. */
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
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
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
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth().testTag(tag),
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
        Box(Modifier.fillMaxWidth().height(5.dp).background(Palette.bgInput)) {
            Box(Modifier.fillMaxWidth(widths[s.level]).height(5.dp).background(Palette.strength[s.level]))
        }
        Text("${s.label} (~${s.bits} bits)", color = Palette.strength[s.level], style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun <T> ChoiceRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o ->
                val sel = o == selected
                TextButton(
                    onClick = { onSelect(o) },
                    modifier = if (sel) Modifier.background(Palette.selectBg, MaterialTheme.shapes.small) else Modifier,
                ) { Text(text(o), color = if (sel) Palette.fgPrimary else Palette.fgSecondary) }
            }
        }
    }
}

@Composable
fun Muted(text: String) = Text(text, color = Palette.fgSecondary, style = MaterialTheme.typography.bodySmall)
