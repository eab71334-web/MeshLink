package com.meshlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================= Theme =======================
private val Sky = Color(0xFF0EA5E9)
private val Indigo = Color(0xFF6366F1)
val Brand: Brush = Brush.linearGradient(listOf(Sky, Indigo))

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8), onPrimary = Color(0xFF00263A),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF121B2E), onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1B2740), onSurfaceVariant = Color(0xFF9FB0C8),
    outline = Color(0xFF2B3A57)
)
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B7FD1), onPrimary = Color.White,
    background = Color(0xFFF3F6FB), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6ECF5), onSurfaceVariant = Color(0xFF5B6B84),
    outline = Color(0xFFD3DCEA)
)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}

private fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 1440 -> "${m / 60} h ago"
        else -> "${m / 1440} d ago"
    }
}

// ======================= Components =======================
@Composable
fun MeshLogo(dp: Dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.width
        drawRoundRect(brush = Brand, cornerRadius = CornerRadius(w * 0.24f))
        val a = Offset(w * 0.5f, w * 0.30f)
        val b = Offset(w * 0.30f, w * 0.68f)
        val c = Offset(w * 0.70f, w * 0.68f)
        val sw = w * 0.045f
        drawLine(Color.White, a, b, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, a, c, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, b, c, strokeWidth = sw, cap = StrokeCap.Round)
        listOf(a, b, c).forEach { drawCircle(Color.White, radius = w * 0.085f, center = it) }
    }
}

@Composable
fun Avatar(name: String, seed: String, dp: Dp = 46.dp, online: Boolean = false) {
    val palette = listOf(
        Color(0xFF0EA5E9), Color(0xFF8B5CF6), Color(0xFF10B981),
        Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF14B8A6)
    )
    val c = palette[(seed.hashCode() and 0x7fffffff) % palette.size]
    Box {
        Box(Modifier.size(dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = (dp.value * 0.4f).sp
            )
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp)
                    .clip(CircleShape).background(Color(0xFF22C55E))
            )
        }
    }
}

@Composable
fun StatusPill(links: Int) {
    val on = links > 0
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Color(0xFF22C55E) else Color(0xFFF59E0B)))
        Spacer(Modifier.width(6.dp))
        Text(
            if (on) "$links linked" else "Searching…",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ConvoRow(
    title: String, subtitle: String, unread: Int,
    onClick: () -> Unit, avatar: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        avatar()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = TextStyle(textDirection = TextDirection.Content)
            )
        }
        if (unread > 0) {
            Box(Modifier.clip(CircleShape).background(Brand).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text("$unread", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ======================= Screens =======================
@Composable
fun App(mesh: MeshManager) {
    var stage by remember { mutableStateOf(0) }
    when (stage) {
        0 -> SplashScreen { stage = if (mesh.onboarded) 2 else 1 }
        1 -> OnboardingScreen(mesh) { mesh.onboarded = true; stage = 2 }
        2 -> PermissionGate { stage = 3 }
        else -> MainFlow(mesh)
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(700))
        delay(900)
        onDone()
    }
    val inf = rememberInfiniteTransition(label = "pulse")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t")
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF16254A)))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(300.dp)) {
            for (k in 0..1) {
                val tt = (t + k * 0.5f) % 1f
                drawCircle(
                    Color(0xFF38BDF8).copy(alpha = (1f - tt) * 0.35f),
                    radius = size.minDimension / 2f * (0.35f + tt * 0.65f),
                    style = Stroke(width = 3f)
                )
            }
        }
        Column(Modifier.alpha(appear.value), horizontalAlignment = Alignment.CenterHorizontally) {
            MeshLogo(112.dp)
            Spacer(Modifier.height(20.dp))
            Text("MeshLink", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Chat without internet", color = Color(0xFF9FB0C8), fontSize = 15.sp)
        }
    }
}

@Composable
private fun FeatureRow(emoji: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 20.sp) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun OnboardingScreen(mesh: MeshManager, onDone: () -> Unit) {
    var name by remember { mutableStateOf(mesh.nickname.value) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        MeshLogo(88.dp)
        Spacer(Modifier.height(16.dp))
        Text("Welcome to MeshLink", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Message people nearby, even when there is no signal.",
            fontSize = 14.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(24.dp))
        FeatureRow("📡", "Works without internet", "Bluetooth only. No SIM, no Wi‑Fi.")
        FeatureRow("🔁", "Messages hop between phones", "Every phone relays for the others.")
        FeatureRow("🔒", "Private chats are encrypted", "End-to-end. Relays can't read them.")
        FeatureRow("📦", "Store and forward", "Messages wait until someone is in range.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= 16) name = it },
            label = { Text("Choose your name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { mesh.setNickname(name); onDone() },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Get started", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
    }
}

@Composable
fun PermissionGate(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    val required = remember {
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val all = remember {
        if (Build.VERSION.SDK_INT >= 33) required + Manifest.permission.POST_NOTIFICATIONS else required
    }
    fun ok() = required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ok()) onGranted()
    }
    LaunchedEffect(Unit) { if (ok()) onGranted() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        MeshLogo(80.dp)
        Spacer(Modifier.height(20.dp))
        Text("Allow Bluetooth & notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            "MeshLink needs Bluetooth to find nearby phones and notifications to tell you about new messages.",
            fontSize = 14.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { launcher.launch(all) },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("Allow", fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            )
        }) { Text("Open app settings") }
    }
}

@Composable
fun MainFlow(mesh: MeshManager) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java))
    }
    var convo by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(convo) {
        mesh.currentConvo = convo
        convo?.let { mesh.markRead(it) }
    }
    BackHandler(enabled = convo != null) { convo = null }
    val c = convo
    if (c == null) {
        HomeScreen(mesh, onOpen = { convo = it }, onSettings = { settings = true })
    } else {
        ChatScreen(mesh, c) { convo = null }
    }
    if (settings) SettingsDialog(mesh) { settings = false }
}

private fun lastPreview(list: List<ChatMsg>, convo: String): String? =
    list.lastOrNull { it.convo == convo }?.let { (if (it.mine) "You: " else "") + it.text }

@Composable
fun HomeScreen(mesh: MeshManager, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val messages by mesh.messages.collectAsState()
    val peers by mesh.peers.collectAsState()
    val links by mesh.links.collectAsState()
    val unread by mesh.unread.collectAsState()
    val nick by mesh.nickname.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(10_000); now = System.currentTimeMillis() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MeshLogo(40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("MeshLink", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Hi, $nick", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(links)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ConvoRow(
                    "Public channel",
                    lastPreview(messages, PUBLIC) ?: "Everyone nearby · not encrypted",
                    unread[PUBLIC] ?: 0, { onOpen(PUBLIC) }
                ) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                        Text("#", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Text(
                    "PEOPLE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp)
                )
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        "No one found yet. Ask a friend to open MeshLink nearby.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
            items(peers, key = { it.id }) { p ->
                val online = now - p.lastSeen < 90_000
                ConvoRow(
                    p.nick,
                    lastPreview(messages, p.id) ?: (if (online) "Nearby" else "Seen " + ago(p.lastSeen)),
                    unread[p.id] ?: 0, { onOpen(p.id) }
                ) { Avatar(p.nick, p.id, online = online) }
            }
        }
    }
}

@Composable
fun ChatScreen(mesh: MeshManager, convo: String, onBack: () -> Unit) {
    val all by mesh.messages.collectAsState()
    val peers by mesh.peers.collectAsState()
    val msgs = remember(all, convo) { all.filter { it.convo == convo } }
    val peer = peers.firstOrNull { it.id == convo }
    val isPublic = convo == PUBLIC
    val title = if (isPublic) "Public channel" else (peer?.nick ?: msgs.lastOrNull { !it.mine }?.nick ?: convo.take(6))
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
        mesh.markRead(convo)
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            if (isPublic) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                    Text("#", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else Avatar(title, convo, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isPublic) {
                        Icon(
                            Icons.Default.Lock, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (isPublic) "Visible to everyone nearby · not encrypted" else "End-to-end encrypted",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(msgs, key = { it.id }) { Bubble(it, isPublic) }
        }
        InputBar(enabled = isPublic || peer != null, maxBytes = 100) { text ->
            if (isPublic) mesh.sendPublic(text) else mesh.sendPrivate(convo, text)
        }
    }
}

@Composable
fun Bubble(m: ChatMsg, isPublic: Boolean) {
    val mine = m.mine
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (mine) Brand else SolidColor(MaterialTheme.colorScheme.surface))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            if (!mine && isPublic) {
                Text(m.nick, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                m.text, fontSize = 16.sp,
                color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                style = TextStyle(textDirection = TextDirection.Content)
            )
            Row(Modifier.align(Alignment.End)) {
                val sub = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(fmtTime(m.time), fontSize = 11.sp, color = sub)
                if (mine && !isPublic) Text(if (m.delivered) "  ✓✓" else "  ✓", fontSize = 11.sp, color = sub)
            }
        }
    }
}

@Composable
fun InputBar(enabled: Boolean, maxBytes: Int, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank() && enabled
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    if (enabled) "Message" else "Waiting to discover this person…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= maxBytes) text = it },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp,
                    textDirection = TextDirection.Content
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 4, enabled = enabled, modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (canSend) Brand else SolidColor(MaterialTheme.colorScheme.outline))
                .clickable(enabled = canSend) { onSend(text); text = "" },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
    }
}

@Composable
fun SettingsDialog(mesh: MeshManager, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(mesh.nickname.value) }
    val status by mesh.status.collectAsState()
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 16) name = it },
                    label = { Text("Display name") }, singleLine = true
                )
                Text("Your ID: " + mesh.myIdHex.chunked(4).joinToString(" "), fontSize = 12.sp, color = sub)
                Text("Status: $status", fontSize = 12.sp, color = sub)
                Text(
                    "Tip: keep Bluetooth on and turn off battery optimization for MeshLink so it keeps relaying in the background.",
                    fontSize = 12.sp, color = sub
                )
            }
        },
        confirmButton = { TextButton(onClick = { mesh.setNickname(name); onClose() }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = {
                ctx.stopService(Intent(ctx, MeshService::class.java))
                (ctx as? Activity)?.finishAffinity()
            }) { Text("Stop & quit") }
        }
    )
}
