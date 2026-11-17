package com.pcv2.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The dashboard's dialogs, which open as bottom sheets on phones:
 * grip, icon + title + subtitle, scrollable body and a footer with buttons.
 * [actions] receive `close(after)`, which slides the sheet away and then runs `after`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PSheet(
    onDismiss: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: Tone = Tone.ACCENT,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable RowScope.(close: (after: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { after ->
        scope.launch { state.hide() }.invokeOnCompletion { after(); onDismiss() }
    }
    val p = pal
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = p.surface,
        contentColor = p.text,
        scrimColor = Color(0x800A0C14),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(Modifier.padding(top = 8.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(p.border))
        },
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (bg, fg) = toneColors(tone)
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Txt(title, size = 17.5.sp, weight = FontWeight.Bold)
                    if (!subtitle.isNullOrEmpty()) androidx.compose.material3.Text(rich(subtitle), color = p.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (body != null) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                    content = body,
                )
            } else {
                Spacer(Modifier.height(14.dp))
            }
            Row(
                Modifier.fillMaxWidth().background(p.surface2).border(0.5.dp, p.border).navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions(close) }
        }
    }
}

/** Promise-style confirm dialog driven by [AppViewModel.confirm]. */
@Composable
fun ConfirmSheet(vm: AppViewModel) {
    val req = vm.confirmReq ?: return
    key(req) {
        PSheet(
            onDismiss = { vm.answerConfirm(false) },
            icon = when (req.icon) {
                DlgIcon.QUESTION -> Ic.question
                DlgIcon.DANGER -> Ic.trash
                DlgIcon.WARN -> Ic.warning
                DlgIcon.CLOCK -> Ic.clock
                DlgIcon.EDIT -> Ic.edit
                DlgIcon.GIFT -> Ic.gift
            },
            tone = if (req.danger) Tone.DANGER else if (req.icon == DlgIcon.WARN) Tone.WARN else Tone.ACCENT,
            title = req.title,
            subtitle = req.message,
        ) { close ->
            Btn(t("Cancel"), { close { vm.answerConfirm(false) } }, Modifier.weight(1f).height(46.dp), kind = BtnKind.GHOST)
            Btn(req.okLabel, { close { vm.answerConfirm(true) } }, Modifier.weight(1f).height(46.dp), kind = if (req.danger) BtnKind.DANGER else BtnKind.PRIMARY)
        }
    }
}

// ====================================================================== toasts
@Composable
fun ToastHost(vm: AppViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (toast in vm.toasts.reversed()) {
            key(toast.id) { ToastView(toast) { vm.dismissToast(toast.id) } }
        }
    }
}

@Composable
private fun ToastView(toast: Toast, onDismiss: () -> Unit) {
    val p = pal
    val color = when (toast.type) {
        ToastType.SUCCESS -> p.ok
        ToastType.ERROR -> p.danger
        ToastType.WARNING -> p.warn
        ToastType.INFO -> p.info
    }
    val icon = when (toast.type) {
        ToastType.SUCCESS -> Ic.success
        ToastType.ERROR -> Ic.error
        ToastType.WARNING -> Ic.warning
        ToastType.INFO -> Ic.info
    }
    val progress = remember { Animatable(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dx by remember { mutableFloatStateOf(0f) }
    val dismissPx = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(toast.id, dragging) {
        if (dragging) { progress.stop(); return@LaunchedEffect }
        val remaining = (toast.durationMs * progress.value).toLong()
        progress.animateTo(0f, tween(remaining.toInt().coerceAtLeast(1), easing = LinearEasing))
        delay(10)
        onDismiss()
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(dx.roundToInt(), 0) }
            .alpha((1f - abs(dx) / (dismissPx * 2.5f)).coerceIn(0.2f, 1f))
            .shadow(10.dp, shape, ambientColor = Color(0x38141828), spotColor = Color(0x38141828))
            .clip(shape)
            .background(p.surface)
            .drawBehind { drawRect(color, size = Size(4.dp.toPx(), size.height)) }
            .border(1.dp, p.border, shape)
            .draggable(
                rememberDraggableState { dx += it },
                Orientation.Horizontal,
                onDragStarted = { dragging = true },
                onDragStopped = {
                    if (abs(dx) > dismissPx) onDismiss() else { dx = 0f; dragging = false }
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp).padding(top = 1.dp))
            Column(Modifier.weight(1f)) {
                Txt(toast.title, size = 14.5.sp, weight = FontWeight.SemiBold)
                if (toast.message.isNotEmpty()) Txt(toast.message, color = p.text2, size = 13.5.sp, modifier = Modifier.padding(top = 1.dp))
            }
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = t("Dismiss"), onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Ic.close, t("Dismiss"), tint = p.text3, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.value).height(3.dp).background(color.copy(alpha = 0.25f)))
    }
}

