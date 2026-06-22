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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Brand = Color(0xFF169C76)
private val BrandLight = Color(0xFF1BB488)
private val BrandDark = Color(0xFF0F7E5E)
private val Amber = Color(0xFFE0A100)
private const val FEEDBACK_EMAIL = "hardikk.iitkgp@gmail.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.ip(this).isNotEmpty()) BridgeClient.configure(Prefs.ip(this), Prefs.port(this))
        if (Prefs.isPaired(this)) Discovery.start(applicationContext)
        setContent { MacBridgeTheme { HomeScreen() } }
    }
}

// ---- state ----

private data class UiState(
    val paired: Boolean,
    val paused: Boolean,
    val channel: String,        // "LAN" | "Internet Relay"
    val notifAccess: Boolean,
)

private fun readUi(c: Context) = UiState(
    paired = Prefs.isPaired(c),
    paused = Prefs.paused(c),
    channel = Sender.currentChannel(),
    notifAccess = notifAccessGranted(c),
)

private fun notifAccessGranted(c: Context): Boolean {
    val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(c.packageName)
}

// ---- screen ----

private enum class Phase { IDLE, CONNECTING, ERROR }

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    var ui by remember { mutableStateOf(readUi(ctx)) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    fun refresh() { ui = readUi(ctx) }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(1500) } }

    // Launch the scanner and react to a successful scan by verifying the connection.
    val scan = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) phase = Phase.CONNECTING
    }
    LaunchedEffect(phase) {
        if (phase == Phase.CONNECTING) {
            refresh()
            phase = if (probeMac(ctx)) Phase.IDLE else Phase.ERROR
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
            Header()

            if (ui.paired) {
                StatusCard(ui) { Prefs.setPaused(ctx, !ui.paused); refresh() }
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brand.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand)
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pair a new Mac", fontWeight = FontWeight.Medium)
                }
            }
            FooterBar(
                paired = ui.paired,
                onFeedback = { sendFeedback(ctx) },
                onUnpair = { confirmUnpair = true },
            )
        }
    }

    if (phase == Phase.ERROR) {
        AlertDialog(
            onDismissRequest = { Prefs.clear(ctx); Discovery.stop(); phase = Phase.IDLE; refresh() },
            icon = { Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Couldn't reach your Mac") },
            text = { Text("Make sure Android Bridge is open on your Mac, then scan the QR again.") },
            confirmButton = {
                TextButton(onClick = { Prefs.clear(ctx); Discovery.stop(); phase = Phase.IDLE; refresh() }) {
                    Text("OK")
                }
            }
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this Mac?") },
            text = { Text("Notifications stop and the key is erased. You'll scan the QR again to reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    sendBye(ctx)                       // tell the Mac we're leaving
                    Prefs.clear(ctx); Discovery.stop(); confirmUnpair = false; refresh()
                }) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } }
        )
    }
}

/** Verify the freshly-paired Mac is reachable: LAN socket up, or relay delivers a ping. */
private suspend fun probeMac(ctx: Context): Boolean = withContext(Dispatchers.IO) {
    val key = Prefs.key(ctx)
    if (key.isEmpty()) return@withContext false
    BridgeClient.configure(Prefs.ip(ctx), Prefs.port(ctx))   // (re)open LAN attempt
    val ping = runCatching { Crypto.encrypt(key, "{\"type\":\"ping\"}") }.getOrNull()
    repeat(20) {                                              // ~10s
        if (BridgeClient.isConnected) return@withContext true
        if (ping != null && RelayClient.ping(Prefs.relay(ctx), Prefs.room(ctx), ping)) {
            return@withContext true
        }
        delay(500)
    }
    false
}

@Composable
private fun ConnectingView() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Brand, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text("Connecting to your Mac…", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("Verifying the encrypted link", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- header ----

@Composable
private fun Header() {
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

// ---- paired: status hero ----

@Composable
private fun StatusCard(ui: UiState, onTogglePause: () -> Unit) {
    val live = !ui.paused
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (live) Brand.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(active = live, color = if (live) Brand else Amber)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (live) "Connected" else "Paused",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = if (live) Brand else Amber
                    )
                    Text(
                        if (live) "Receiving on your Mac" else "Notifications aren't being sent",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (live) ChannelChip(ui.channel)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Forward notifications", Modifier.weight(1f),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Switch(
                    checked = live, onCheckedChange = { onTogglePause() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Brand,
                        checkedBorderColor = Brand,
                    )
                )
            }
        }
    }
}

@Composable
private fun ChannelChip(channel: String) {
    val lan = channel == "LAN"
    Row(
        Modifier.clip(CircleShape).background(Brand.copy(0.16f)).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (lan) Icons.Filled.Wifi else Icons.Filled.CloudQueue, null, tint = Brand, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(if (lan) "LAN" else "Relay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand)
    }
}

@Composable
private fun PulseDot(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        1f, if (active) 1.7f else 1f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
        if (active) Box(Modifier.size((11 * scale).dp).clip(CircleShape).background(color.copy(alpha = 0.25f)))
        Box(Modifier.size(11.dp).clip(CircleShape).background(color))
    }
}

// ---- unpaired: pair hero ----

@Composable
private fun PairHero(onScan: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand)
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
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Brand.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp))
    }
}

// ---- access nudge (only when not granted) ----

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
                Text("Required to read and forward notifications",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onGrant) { Text("Grant", color = Brand, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ---- footer ----

@Composable
private fun FooterBar(paired: Boolean, onFeedback: () -> Unit, onUnpair: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("End-to-end encrypted", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onFeedback) {
                Text("Send feedback", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (paired) {
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onUnpair) {
                    Text("Unpair", fontSize = 13.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                }
            }
        }
    }
}

private fun sendBye(c: Context) {
    val key = Prefs.key(c)
    if (key.isEmpty()) return
    runCatching { Crypto.encrypt(key, "{\"type\":\"bye\"}") }.getOrNull()
        ?.let { Sender.send(c, it) }
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

// ---- theme ----

@Composable
private fun MacBridgeTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Brand, secondary = BrandLight)
    else
        lightColorScheme(primary = Brand, secondary = BrandLight)
    MaterialTheme(colorScheme = scheme, content = content)
}
