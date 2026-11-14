package com.pcv2.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.Level
import com.pcv2.app.core.fmtDate
import com.pcv2.app.core.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ====================================================================== text
/** Renders the small bits of markup the dashboard strings use: <b>, <mono>, <span class="mono">. */
@Composable
fun rich(text: String, boldColor: Color = Color.Unspecified): AnnotatedString {
    val p = pal
    return remember(text, boldColor, p) {
        buildAnnotatedString {
            val re = Regex("<b>(.*?)</b>|<mono>(.*?)</mono>|<span class=\"mono\">(.*?)</span>")
            var last = 0
            for (m in re.findAll(text)) {
                append(text.substring(last, m.range.first))
                when {
                    m.groups[1] != null -> pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor))
                    else -> pushStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.92.em()))
                }
                append(m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: "")
                pop()
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
}

private fun Double.em(): TextUnit = TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = pal.text,
    size: TextUnit = 15.sp,
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    mono: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text, modifier = modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, textAlign = align, fontFamily = if (mono) MonoFamily else null,
        lineHeight = lineHeight, letterSpacing = letterSpacing,
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(rich(text, pal.text2), modifier = modifier, color = pal.text3, fontSize = 13.sp, lineHeight = 18.sp, textAlign = align)
}

// ====================================================================== surfaces
@Composable
fun PCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    background: Color = pal.surface,
    alpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .alpha(alpha)
            .shadow(if (pal.dark) 0.dp else 1.5.dp, shape, ambientColor = Color(0x22141828), spotColor = Color(0x22141828))
            .clip(shape)
            .background(background)
            .border(1.dp, pal.border, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Txt(
        text.uppercase(I18nLocale()), modifier.padding(bottom = 10.dp), color = pal.text3, size = 12.sp,
        weight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
    )
}

@Composable
private fun I18nLocale() = com.pcv2.app.core.I18n.locale

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(text, Modifier.weight(1f), size = 16.sp, weight = FontWeight.SemiBold)
        trailing()
    }
}

@Composable
fun PageHead(title: String, subtitle: String? = null, action: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Txt(title, size = 20.sp, weight = FontWeight.Bold, letterSpacing = (-0.1).sp)
        if (subtitle != null) Text(rich(subtitle), color = pal.text2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
        if (action != null) Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = action)
    }
}

@Composable
fun EmptyCard(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    PCard(padding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(title, color = pal.text2, size = 16.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(rich(text, pal.text2), color = pal.text3, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (action != null) { Spacer(Modifier.height(12.dp)); action() }
        }
    }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "sk")
    val x by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "skx")
    val p = pal
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(
            Brush.linearGradient(
                listOf(p.track, p.surface2, p.track),
                start = Offset(-600f + 1200f * x, 0f), end = Offset(600f * x + 0f, 0f),
            ),
        ),
    )
}

// ====================================================================== chips
enum class Tone { NEUTRAL, OK, WARN, DANGER, INFO, ACCENT }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.NEUTRAL -> pal.surface2 to pal.text2
    Tone.OK -> pal.okSoft to pal.ok
    Tone.WARN -> pal.warnSoft to pal.warn
    Tone.DANGER -> pal.dangerSoft to pal.danger
    Tone.INFO -> pal.infoSoft to pal.info
    Tone.ACCENT -> pal.accentSoft to pal.accent
}

@Composable
fun Chip(text: String, tone: Tone = Tone.NEUTRAL, live: Boolean = false, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (tone == Tone.NEUTRAL) Modifier.border(1.dp, pal.border, CircleShape) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (live) LiveDot()
        Text(rich(text), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LiveDot(color: Color = pal.ok, size: Dp = 7.dp) {
    val tr = rememberInfiniteTransition(label = "pulse")
    val a by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pa")
    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size + 8.dp)) {
            val r = size.toPx() / 2
            if (a < 0.7f) drawCircle(color.copy(alpha = 0.6f * (1 - a / 0.7f)), radius = r + (a / 0.7f) * 3.5.dp.toPx())
            drawCircle(color, radius = r)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

// ====================================================================== buttons
enum class BtnKind { DEFAULT, PRIMARY, DANGER, GHOST, SOFT }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.DEFAULT,
    small: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    hPad: Dp? = null,
) {
    val p = pal
    val (bg, fg, border) = when (kind) {
        BtnKind.DEFAULT -> Triple(p.surface, p.text, p.border)
        BtnKind.PRIMARY -> Triple(p.accent, p.accentText, p.accent)
        BtnKind.DANGER -> Triple(p.danger, Color.White, p.danger)
        BtnKind.GHOST -> Triple(Color.Transparent, p.text2, Color.Transparent)
        BtnKind.SOFT -> Triple(p.accentSoft, p.accent, Color.Transparent)
    }
    val shape = RoundedCornerShape(if (small) 8.dp else 9.dp)
    Row(
        modifier
            .heightIn(min = if (small) 36.dp else 42.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = hPad ?: if (small) 12.dp else 14.dp, vertical = if (small) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, color = fg, fontSize = if (small) 13.5.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    spinning: Boolean = false,
    text: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(40.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(pal.surface)
                .border(1.dp, pal.border, shape)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (text != null) {
                Txt(text, color = pal.text2, size = 12.5.sp, weight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
            } else {
                val angle = if (spinning) {
                    val tr = rememberInfiniteTransition(label = "spin")
                    tr.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spina").value
                } else 0f
                Icon(icon, description, tint = pal.text2, modifier = Modifier.size(18.dp).rotate(angle))
            }
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).heightIn(min = 17.dp).widthIn(min = 17.dp)
                    .clip(CircleShape).background(pal.warn).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(badge.toString(), color = Color.White, size = 10.5.sp, weight = FontWeight.Bold) }
        }
    }
}

// ====================================================================== progress
@Composable
fun levelColor(level: Level, default: Color = pal.accent): Color = when (level) {
    Level.WARN -> pal.warn
    Level.DANGER -> pal.danger
    Level.OK -> pal.ok
    Level.NONE -> default
}

@Composable
fun Bar(fraction: Float, level: Level = Level.NONE, modifier: Modifier = Modifier, color: Color? = null) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "bar")
    val c = color ?: levelColor(level)
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(5.dp)).background(pal.track)) {
        Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(5.dp)).background(c))
    }
}

@Composable
fun Ring(fraction: Float, level: Level, size: Dp, center: @Composable BoxScope.() -> Unit) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "ring")
    val track = pal.track
    val c = levelColor(level)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.minDimension * 11f / 120f
            val d = this.size.minDimension * 104f / 120f
            val tl = Offset((this.size.width - d) / 2, (this.size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
            if (f > 0f) drawArc(c, -90f, 360f * f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
        }
        center()
    }
}

/** A segment of a 24h timeline: from/to as fractions of the day. */
data class Seg(val from: Float, val to: Float, val kind: Int) // 0 = on, 1 = blocked, 2 = whitelist

@Composable
fun Timeline(segs: List<Seg>, nowFrac: Float?, modifier: Modifier = Modifier) {
    val p = pal
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(7.dp)).background(p.track)) {
            for (s in segs) {
                val color = when (s.kind) { 1 -> p.danger.copy(alpha = 0.55f); else -> p.ok.copy(alpha = 0.85f) }
                val x = s.from * size.width
                val w = ((s.to - s.from) * size.width).coerceAtLeast(1.5f)
                drawRect(color, Offset(x, 0f), Size(w, size.height))
            }
            if (nowFrac != null) drawRect(p.text, Offset(nowFrac * size.width - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { Txt(it, color = p.text3, size = 11.sp) }
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Txt(text, color = pal.text2, size = 12.5.sp)
    }
}

// ====================================================================== key/value + stats
@Composable
fun Kv(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt(k, color = pal.text2, size = 14.5.sp)
                Txt(v, Modifier.weight(1f), size = 14.5.sp, weight = FontWeight.SemiBold, align = TextAlign.End)
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    PCard(modifier, padding = 12.dp) {
        Txt(label, color = pal.text3, size = 12.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
        Txt(value, size = 19.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

// ====================================================================== banner
@Composable
fun Banner(tone: Tone, title: String, text: String) {
    val (bg, fg) = toneColors(tone)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(9.dp)).background(bg).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Ic.warning, null, tint = fg, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, color = fg, size = 14.5.sp, weight = FontWeight.SemiBold)
            Txt(text, color = pal.text2, size = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ====================================================================== form controls
@Composable
fun Field(label: String, modifier: Modifier = Modifier, hint: String? = null, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Txt(label, color = pal.text2, size = 13.sp, weight = FontWeight.SemiBold)
        content()
        if (hint != null) Hint(hint)
    }
}

@Composable
fun Input(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    center: Boolean = false,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Default,
    onIme: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = 44.dp,
) {
    val p = pal
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        singleLine = true,
        interactionSource = interaction,
        textStyle = TextStyle(
            color = if (readOnly) p.text2 else p.text, fontSize = 16.sp,
            fontFamily = if (mono) MonoFamily else null, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        ),
        cursorBrush = SolidColor(p.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType, imeAction = imeAction,
            autoCorrectEnabled = !(mono || password || keyboardType != KeyboardType.Text),
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
        ),
        keyboardActions = KeyboardActions(onAny = { onIme?.invoke() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .then(if (focused) Modifier.border(3.dp, p.accentSoft, RoundedCornerShape(11.dp)).padding(2.dp) else Modifier.padding(2.dp))
                    .heightIn(min = minHeight)
                    .clip(shape)
                    .background(if (readOnly) p.surface2 else p.surface)
                    .border(1.dp, if (focused) p.accent else p.border, shape)
                    .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, color = p.text3, size = 16.sp, mono = mono, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/** A field that looks like an input and opens something when tapped (date, time, select). */
@Composable
fun PickerField(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, mono: Boolean = false, center: Boolean = false) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier
            .padding(2.dp)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(pal.surface)
            .border(1.dp, pal.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
    ) {
        Txt(text, if (center) Modifier else Modifier.weight(1f), size = 16.sp, mono = mono, maxLines = 1)
        if (icon != null) { Spacer(Modifier.width(8.dp)); Icon(icon, null, tint = pal.text3, modifier = Modifier.size(18.dp)) }
    }
}

/** Hours + minutes inputs ("1 h 30 m"). */
@Composable
fun HmInput(h: String, m: String, onH: (String) -> Unit, onM: (String) -> Unit, modifier: Modifier = Modifier, hLabel: String = t("h"), mLabel: String = t("m"), enabled: Boolean = true) {
    Row(modifier.alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Input(h, { v -> if (enabled) onH(v.filter(Char::isDigit).take(2)) }, Modifier.weight(1f), center = true, keyboardType = KeyboardType.Number, readOnly = !enabled)
        Txt(hLabel, color = pal.text3, size = 13.sp)
        Input(m, { v -> if (enabled) onM(v.filter(Char::isDigit).take(2)) }, Modifier.weight(1f), center = true, keyboardType = KeyboardType.Number, readOnly = !enabled)
        Txt(mLabel, color = pal.text3, size = 13.sp)
    }
}

@Composable
fun SegCtl(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = pal
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface2).border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 38.dp)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(7.dp)) else Modifier)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) p.surface else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(label, color = if (on) p.text else p.text2, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1, align = TextAlign.Center) }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = pal.accent, checkedThumbColor = Color.White, checkedBorderColor = pal.accent,
                uncheckedTrackColor = pal.track, uncheckedThumbColor = Color.White, uncheckedBorderColor = pal.track,
            ),
        )
        Txt(label, weight = FontWeight.Medium)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { onChange(!checked) }.padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = pal.accent, uncheckedColor = pal.text3, checkmarkColor = pal.accentText))
        Txt(label, size = 14.5.sp)
    }
}

@Composable
fun Dropdown(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        PickerField(options.firstOrNull { it.first == selected }?.second ?: selected, Ic.chevronDown, { open = true }, Modifier.fillMaxWidth())
        DropdownMenu(open, { open = false }, containerColor = pal.surface) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Txt(label, weight = if (value == selected) FontWeight.SemiBold else null, color = if (value == selected) pal.accent else pal.text) },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: String, onDate: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    PickerField(fmtDate(date), Ic.calendar, { open = true }, modifier)
    if (open) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Txt(t("OK"), color = pal.accent, weight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton({ open = false }) { Txt(t("Cancel"), color = pal.text2) } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, label: String) {
    var open by remember { mutableStateOf(false) }
    PickerField(value.ifEmpty { "--:--" }, null, { open = true }, modifier.semanticsLabel(label), center = true)
    if (open) {
        val parts = value.split(":")
        val state = rememberTimePickerState(parts.getOrNull(0)?.toIntOrNull() ?: 22, parts.getOrNull(1)?.toIntOrNull() ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ onValue("%02d:%02d".format(state.hour, state.minute)); open = false }) { Txt(t("OK"), color = pal.accent, weight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton({ open = false }) { Txt(t("Cancel"), color = pal.text2) } },
            title = { Txt(label, weight = FontWeight.SemiBold) },
            text = { TimePicker(state) },
            containerColor = pal.surface,
        )
    }
}

fun Modifier.semanticsLabel(label: String): Modifier = this.semantics { contentDescription = label }

@Composable
fun Spinner(size: Dp = 18.dp, color: Color = pal.accent) {
    val tr = rememberInfiniteTransition(label = "spinner")
    val a by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "sa")
    Icon(Ic.refresh, null, tint = color, modifier = Modifier.size(size).rotate(a))
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp)
