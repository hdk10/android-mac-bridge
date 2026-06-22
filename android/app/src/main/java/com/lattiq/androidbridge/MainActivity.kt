package com.lattiq.androidbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Brand = Color(0xFF169C76)
private val BrandLight = Color(0xFF1BB488)
private val BrandDark = Color(0xFF0F7E5E)
private val Amber = Color(0xFFE0A100)
private const val FEEDBACK_EMAIL = "hardikk.iitkgp@gmail.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Links.configure(Prefs.macs(this))
        if (Prefs.isPaired(this)) Discovery.start(applicationContext)
        setContent { MacBridgeTheme { HomeScreen() } }
    }
}

private data class MacRow(val mac: Mac, val lan: Boolean)
private data class UiState(val macs: List<MacRow>, val paused: Boolean, val notifAccess: Boolean) {
    val paired get() = macs.isNotEmpty()
}

private fun readUi(c: Context) = UiState(
    macs = Prefs.macs(c).map { MacRow(it, Links.isConnected(it.room)) },
    paused = Prefs.paused(c),
    notifAccess = notifAccessGranted(c),
)

private fun notifAccessGranted(c: Context): Boolean {
    val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(c.packageName)
}

private enum class Phase { IDLE, CONNECTING, ERROR }

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    var ui by remember { mutableStateOf(readUi(ctx)) }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var removing by remember { mutableStateOf<Mac?>(null) }
    fun refresh() { ui = readUi(ctx) }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(1500) } }

    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) phase = Phase.CONNECTING
    }
    LaunchedEffect(phase) {
        if (phase == Phase.CONNECTING) {
            refresh()
            val newest = Prefs.macs(ctx).lastOrNull()
            phase = if (newest != null && probeMac(ctx, newest)) Phase.IDLE else Phase.ERROR
            refresh()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (phase == Phase.CONNECTING) {
            ConnectingView()
        } else Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(ui)
            if (ui.paired) {
                MacsCard(ui, onRemove = { removing = it })
                ForwardToggle(ui.paused) { Prefs.setPaused(ctx, !ui.paused); refresh() }
            } else {
                PairHero { scan.launch(Intent(ctx, ScannerActivity::class.java)) }
            }
            if (!ui.notifAccess) {
                AccessCard { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            Spacer(Modifier.weight(1f))
            if (ui.paired) {
                OutlinedButton(
                    onClick = { scan.launch(Intent(ctx, ScannerActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Brand.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand)
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pair a new Mac", fontWeight = FontWeight.Medium)
                }
            }
            FooterBar(onFeedback = { sendFeedback(ctx) })
        }
    }

    if (phase == Phase.ERROR) {
        AlertDialog(
            onDismissRequest = { Prefs.macs(ctx).lastOrNull()?.let { Prefs.removeMac(ctx, it.id) }; phase = Phase.IDLE; refresh() },
            icon = { Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Couldn't reach that Mac") },
            text = { Text("Make sure Android Bridge is open on it, then scan the QR again.") },
            confirmButton = {
                TextButton(onClick = { Prefs.macs(ctx).lastOrNull()?.let { Prefs.removeMac(ctx, it.id) }; phase = Phase.IDLE; refresh() }) { Text("OK") }
            }
        )
    }

    removing?.let { mac ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unpair ${mac.name}?") },
            text = { Text("This phone stops sending to it. You can pair again by scanning its QR.") },
            confirmButton = {
                TextButton(onClick = {
                    sendBye(ctx, mac); Prefs.removeMac(ctx, mac.id); Links.configure(Prefs.macs(ctx))
                    removing = null; refresh()
                }) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

// ---- probe + control ----

private suspend fun probeMac(ctx: Context, mac: Mac): Boolean = withContext(Dispatchers.IO) {
    Links.ensure(mac)
    val ping = runCatching {
        Crypto.encrypt(mac.key, "{\"type\":\"ping\",\"did\":\"${Prefs.deviceId(ctx)}\"}")
    }.getOrNull()
    repeat(20) {
        if (Links.isConnected(mac.room)) return@withContext true
        if (ping != null && RelayClient.ping(mac.relay, mac.room, ping)) return@withContext true
        delay(500)
    }
    false
}

private fun sendBye(c: Context, mac: Mac) {
    val bye = org.json.JSONObject().apply { put("type", "bye"); put("did", Prefs.deviceId(c)) }
    runCatching { Crypto.encrypt(mac.key, bye.toString()) }.getOrNull()?.let { Sender.sendTo(mac, it) }
}

private fun sendFeedback(c: Context) {
    val body = "\n\n\n— — —\nApp ${BuildConfig.VERSION_NAME} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Mac Bridge feedback")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching { c.startActivity(Intent.createChooser(intent, "Send feedback")) }
}

// ---- composables ----

@Composable
private fun Header(ui: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.app_logo), null,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Mac Bridge", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Seamless Android <> Mac connectivity",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MacsCard(ui: UiState, onRemove: (Mac) -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            ui.macs.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                MacItem(row, onRemove)
            }
        }
    }
}

@Composable
private fun MacItem(row: MacRow, onRemove: (Mac) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulseDot(active = true, color = Brand)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.mac.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(if (row.lan) "Connected · LAN" else "Connected · Relay",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (row.lan) Icons.Filled.Wifi else Icons.Filled.CloudQueue,
            null, tint = Brand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = { onRemove(row.mac) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, "Unpair", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ForwardToggle(paused: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Forward notifications", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Switch(checked = !paused, onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Brand, checkedBorderColor = Brand))
        }
    }
}

@Composable
private fun PulseDot(active: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(1f, if (active) 1.7f else 1f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
        if (active) Box(Modifier.size((10 * scale).dp).clip(CircleShape).background(color.copy(alpha = 0.25f)))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun PairHero(onScan: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(38.dp), tint = Brand)
                Spacer(Modifier.height(10.dp))
                Text("Connect your Mac", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PairStep(1, "Open Android Bridge on your Mac")
                PairStep(2, "Click it, then \"Pair phone\"")
                PairStep(3, "Scan the QR below")
            }
            Button(
                onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Brand)
            ) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(10.dp))
                Text("Scan QR to pair", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun PairStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Brand.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AccessCard(onGrant: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.14f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, null, tint = Amber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Notification access needed", fontWeight = FontWeight.SemiBold)
                Text("Required to read and forward notifications", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onGrant) { Text("Grant", color = Brand, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun FooterBar(onFeedback: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("End-to-end encrypted", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onFeedback) {
            Text("Send feedback", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectingView() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Brand, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text("Connecting to your Mac…", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("Verifying the encrypted link", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MacBridgeTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Brand, secondary = BrandLight)
    else lightColorScheme(primary = Brand, secondary = BrandLight)
    MaterialTheme(colorScheme = scheme, content = content)
}
