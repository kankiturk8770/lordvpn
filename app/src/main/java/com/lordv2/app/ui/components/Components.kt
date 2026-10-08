package com.lordv2.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lordv2.app.data.Countries
import com.lordv2.app.data.PingTester
import com.lordv2.app.data.Profile
import com.lordv2.app.ui.theme.Lord

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    corner: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Lord.colors
    val shape = RoundedCornerShape(corner)
    val border = if (highlight) Brush.linearGradient(listOf(c.cyan.copy(alpha = 0.9f), c.primary.copy(alpha = 0.6f)))
    else Brush.linearGradient(listOf(c.stroke, c.stroke.copy(alpha = 0.35f)))
    val fill = if (c.isDark) Brush.verticalGradient(listOf(c.cardHi.copy(alpha = 0.92f), c.card.copy(alpha = 0.86f)))
    else Brush.verticalGradient(listOf(c.card, c.cardHi))
    Column(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Lord.colors
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text) }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = c.muted, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null, size: Dp = 42.dp) {
    val c = Lord.colors
    Box(
        Modifier.size(size).clip(CircleShape).background(c.card).border(1.dp, c.stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint ?: c.text, modifier = Modifier.size(size * 0.48f)) }
}

@Composable
fun pingColor(ms: Int): Color {
    val c = Lord.colors
    return when {
        ms == Profile.PING_UNTESTED -> c.muted
        ms < 0 -> c.danger
        ms < 100 -> c.success
        ms < 200 -> c.warning
        else -> c.danger
    }
}

@Composable
fun PingBadge(ms: Int, testing: Boolean = false, showLabel: Boolean = false) {
    val c = Lord.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (testing) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.cyan)
        } else {
            val col = pingColor(ms)
            Box(Modifier.size(7.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(6.dp))
            val txt = when {
                ms == Profile.PING_UNTESTED -> "— ms"
                ms < 0 -> "Timeout"
                else -> "$ms ms"
            }
            Text(txt, color = if (ms < 0 && ms != Profile.PING_UNTESTED) c.danger else c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (showLabel && ms > 0) {
                Text("  ${PingTester.quality(ms)}", color = col, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color? = null) {
    val c = Lord.colors
    val col = color ?: c.primary
    Text(
        text,
        color = col,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(col.copy(alpha = 0.13f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun FlagBubble(code: String, size: Dp = 44.dp) {
    val c = Lord.colors
    Box(
        Modifier.size(size).clip(CircleShape).background(c.bg.copy(alpha = 0.6f)).border(1.dp, c.stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(Countries.flag(code), fontSize = (size.value * 0.48f).sp) }
}

@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lord.colors
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        color = if (selected) (if (c.isDark) Color(0xFF00131C) else Color.White) else c.muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Brush.linearGradient(listOf(c.primary, c.cyan)) else Brush.linearGradient(listOf(c.card, c.card)))
            .border(1.dp, if (selected) Color.Transparent else c.stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> SelectChip(label, selected == value) { onSelect(value) } }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = Lord.colors
    Text(text.uppercase(), color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = modifier.padding(start = 4.dp, bottom = 8.dp, top = 18.dp))
}

@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Lord.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background((if (danger) c.danger else c.primary).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (danger) c.danger else c.cyan, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) c.danger else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = c.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (value != null) {
            Text(value, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            Spacer(Modifier.width(4.dp))
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.faint)
        }
    }
}

@Composable
fun SettingSwitch(icon: ImageVector, title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, title, subtitle, onClick = { onChange(!checked) }) { LordSwitch(checked, onChange) }
}

@Composable
fun LordSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lord.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.primary,
            checkedBorderColor = c.primary,
            uncheckedThumbColor = c.muted,
            uncheckedTrackColor = c.bg,
            uncheckedBorderColor = c.stroke,
        ),
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp), content = content)
}

@Composable
fun GroupDivider() {
    val c = Lord.colors
    HorizontalDivider(Modifier.padding(start = 64.dp), thickness = 1.dp, color = c.stroke.copy(alpha = 0.6f))
}

@Composable
fun lordFieldColors(): TextFieldColors {
    val c = Lord.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.primary,
        unfocusedBorderColor = c.stroke,
        focusedContainerColor = c.card,
        unfocusedContainerColor = c.card,
        cursorColor = c.cyan,
        focusedLabelColor = c.cyan,
        unfocusedLabelColor = c.muted,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        focusedPlaceholderColor = c.faint,
        unfocusedPlaceholderColor = c.faint,
    )
}

@Composable
fun LordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    number: Boolean = false,
    secret: Boolean = false,
    singleLine: Boolean = true,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) { { Text(placeholder) } } else null,
        singleLine = singleLine,
        shape = RoundedCornerShape(14.dp),
        colors = lordFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = when {
            number -> KeyboardType.Number
            secret -> KeyboardType.Password
            else -> KeyboardType.Text
        }),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show", tint = Lord.colors.muted)
                }
            }
        } else null,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lord.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) Brush.linearGradient(listOf(c.primary, c.cyan)) else Brush.linearGradient(listOf(c.stroke, c.stroke)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (c.isDark) Color(0xFF00131C) else Color.White
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.5.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color? = null, onClick: () -> Unit) {
    val c = Lord.colors
    val col = color ?: c.text
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(46.dp).clip(shape).background(c.card).border(1.dp, c.stroke, shape).clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = col, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = col, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Lord.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.cyan, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = c.muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, onClick = onAction)
        }
    }
}

@Composable
fun LordAlert(
    title: String,
    message: String,
    confirm: Pair<String, () -> Unit>,
    dismiss: Pair<String, () -> Unit>? = null,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = Lord.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(message, color = c.muted, fontSize = 14.sp)
                if (extra != null) { Spacer(Modifier.height(12.dp)); extra() }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm.second) { Text(confirm.first, color = if (danger) c.danger else c.cyan, fontWeight = FontWeight.Bold) }
        },
        dismissButton = if (dismiss != null) { { TextButton(onClick = dismiss.second) { Text(dismiss.first, color = c.muted) } } } else null,
    )
}

@Composable
fun OptionDialog(
    title: String,
    options: List<Triple<String, String, String?>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Lord.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { (value, label, desc) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(value); onDismiss() }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value); onDismiss() }, colors = RadioButtonDefaults.colors(selectedColor = c.cyan, unselectedColor = c.faint))
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (desc != null) Text(desc, color = c.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.muted) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "Save",
    placeholder: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val c = Lord.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = c.text, fontWeight = FontWeight.Bold) },
        text = { LordField(label, value, { value = it }, placeholder = placeholder) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(confirm, color = c.cyan, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}
