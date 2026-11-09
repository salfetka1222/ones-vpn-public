package app.onesvpn

import java.net.InetSocketAddress
import java.net.Socket
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*

private val dark = Color(0xFF080B15)
private val glass = Color(0xFF161B29)
private val accent = Color(0xFFC5B7FF)
private val glassEdge = Color(0xFF5D6483)
private val mutedText = Color(0xFF929CB1)
private val brightText = Color(0xFFE9ECFC)
private val cyanGlow = Color(0xFF87DFFF)
private val mintGlow = Color(0xFF99F1C0)

class MainActivity : ComponentActivity() {
    private var isScreenResumed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OnesScreen(isScreenResumed) }
    }

    override fun onResume() {
        super.onResume()
        isScreenResumed = true
        // A service already connected can revalidate after screen-off/Doze.
        // Never create a new VPN connection or request consent on resume.
        if (OnesVpnService.state.value.status == VpnStatus.CONNECTED) {
            runCatching {
                startService(Intent(this, OnesVpnService::class.java)
                    .setAction(OnesVpnService.ACTION_VERIFY_ON_RESUME))
            }
        }
    }

    override fun onPause() {
        isScreenResumed = false
        super.onPause()
    }
}

@Composable
fun OnesScreen(isScreenResumed: Boolean = true) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    val scope = rememberCoroutineScope()
    var checkingNative by remember { mutableStateOf(false) }
    var nativeResult by remember { mutableStateOf<String?>(null) }
    val initialProfiles = remember { runCatching { store.load() } }
    var profiles by remember { mutableStateOf(initialProfiles.getOrDefault(emptyList())) }
    var selectedId by remember { mutableStateOf(context.getSharedPreferences("ui", 0).getString("selected", null)) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    var tab by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    val serviceState by OnesVpnService.state.collectAsState()
    val trafficState by VpnTraffic.state.collectAsState()
    val routeAudit by VpnRouteAudit.state.collectAsState()
    val protectionSnapshot by SystemVpnProtection.state.collectAsState()
    // Private DNS is read-only and deliberately kept out of logs/backups.
    var privateDnsMode by remember { mutableStateOf(AndroidPrivateDnsReader.read(context)) }
    LaunchedEffect(isScreenResumed) {
        if (isScreenResumed) privateDnsMode = AndroidPrivateDnsReader.read(context)
    }
    // Await a new service-side result rather than claiming success on button tap.
    var protectionCheckBaseline by remember { mutableLongStateOf(-1L) }
    val externalIp by ExternalIpState.state.collectAsState()
    var showFullExternalIp by remember { mutableStateOf(false) }
    val diagnosticEvents by Diagnostics.events.collectAsState()
    var error by remember { mutableStateOf<String?>(if (initialProfiles.isFailure) "Не удалось прочитать профили. Данные сохранены; не переустанавливайте приложение." else null) }
    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var expandedSource by remember { mutableStateOf<String?>(null) }
    var refreshingSource by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var smartChecking by remember { mutableStateOf(false) }
    var smartJob by remember { mutableStateOf<Job?>(null) }
    var smartStatus by remember { mutableStateOf<String?>(null) }
    var pingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var serverSearch by rememberSaveable { mutableStateOf("") }
    var sortByLatency by rememberSaveable { mutableStateOf(false) }
    var serverFilter by rememberSaveable { mutableStateOf(0) }
    var autoRefresh by rememberSaveable { mutableStateOf(context.getSharedPreferences("ui", 0).getBoolean("auto_refresh", false)) }
    var autoRefreshRunning by remember { mutableStateOf(false) }
    val splitStore = remember { SplitTunnelStore(context) }
    var excludedApps by remember { mutableStateOf(splitStore.load()) }
    var splitExpanded by rememberSaveable { mutableStateOf(false) }
    var splitSearch by rememberSaveable { mutableStateOf("") }
    var compactSettings by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("ui", 0).getBoolean("compact_settings", false))
    }
    var backupBusy by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var importBackupDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupRepeatPassword by remember { mutableStateOf("") }
    var awaitingExportPassword by remember { mutableStateOf<String?>(null) }
    var awaitingImportPassword by remember { mutableStateOf<String?>(null) }
    var pendingBackupProfiles by remember { mutableStateOf<List<VpnProfile>?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = awaitingExportPassword
        awaitingExportPassword = null
        if (uri != null && pass != null) {
            backupBusy = true
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        val current = store.load()
                        val secret = pass.toCharArray()
                        val ciphertext = try {
                            EncryptedProfileBackup.encrypt(current, secret)
                        } finally {
                            secret.fill('\u0000')
                        }
                        val stream = context.contentResolver.openOutputStream(uri, "w")
                            ?: throw IllegalStateException("FILE_WRITE_FAILED")
                        try {
                            stream.use { it.write(ciphertext) }
                        } finally {
                            ciphertext.fill(0)
                        }
                        current.size
                    }
                    backupStatus = "Зашифрованная копия создана: $count серверов."
                } catch (_: Exception) {
                    backupStatus = "Не удалось сохранить копию. Существующие серверы не изменены."
                } finally {
                    backupBusy = false
                }
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val pass = awaitingImportPassword
        awaitingImportPassword = null
        if (uri != null && pass != null) {
            backupBusy = true
            scope.launch {
                try {
                    pendingBackupProfiles = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("FILE_READ_FAILED")
                        val ciphertext = stream.use(EncryptedProfileBackup::readBounded)
                        val secret = pass.toCharArray()
                        try {
                            EncryptedProfileBackup.decrypt(ciphertext, secret)
                        } finally {
                            secret.fill('\u0000')
                            ciphertext.fill(0)
                        }
                    }
                    backupStatus = "Копия проверена. Подтвердите восстановление."
                } catch (_: Exception) {
                    pendingBackupProfiles = null
                    backupStatus = "Копия не открыта: проверьте пароль, файл и его целостность."
                } finally {
                    backupBusy = false
                }
            }
        }
    }

    LaunchedEffect(autoRefresh) {
        if (autoRefresh && !autoRefreshRunning) {
            autoRefreshRunning = true
            val sources = profiles.map { it.source }.filter { it.isNotBlank() }.distinct()
            for (source in sources) {
                try {
                    val updated = withContext(Dispatchers.IO) { SubscriptionImport.load(source) }
                    val current = profiles.filter { it.source == source }.associateBy { it.uri }
                    val replacement = updated.map { it.copy(id = current[it.uri]?.id ?: it.id, source = source) }
                    val next = profiles.filterNot { it.source == source } + replacement
                    withContext(Dispatchers.IO) { store.save(next) }
                    profiles = next
                    if (selectedId != null && next.none { it.id == selectedId }) {
                        selectedId = replacement.firstOrNull()?.id
                        context.getSharedPreferences("ui", 0).edit().putString("selected", selectedId).apply()
                    }
                } catch (_: Exception) { /* Keep existing profiles on refresh failure. */ }
            }
            autoRefreshRunning = false
        }
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var installingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppUpdates.Release?>(null) }
    var updateStatus by remember { mutableStateOf("Проверка выполняется по запросу") }
    var pendingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    fun connect(id: String) {
        try {
            context.startForegroundService(Intent(context, OnesVpnService::class.java)
                .setAction(OnesVpnService.ACTION_CONNECT).putExtra(OnesVpnService.EXTRA_PROFILE_ID, id))
        } catch (_: Exception) { error = "Android не разрешил запуск VPN-сервиса" }
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending = pendingProfileId
        pendingProfileId = null
        if (VpnService.prepare(context) == null && pending != null) connect(pending)
        else error = "Разрешение VPN не предоставлено"
    }
    fun toggleVpn() {
                            when (serviceState.status) {
                                VpnStatus.CONNECTING, VpnStatus.CONNECTED -> {
                                    try { context.startService(Intent(context, OnesVpnService::class.java).setAction(OnesVpnService.ACTION_DISCONNECT)) }
                                    catch (_: Exception) { error = "Не удалось отправить команду отключения. Отключите VPN в настройках Android." }
                                }
                                VpnStatus.DISCONNECTING -> Unit
                                else -> {
                                    val profile = selected
                                    if (profile == null) error = "Сначала выберите сервер"
                                    else {
                                        val request = VpnService.prepare(context)
                                        if (request == null) connect(profile.id)
                                        else { pendingProfileId = profile.id; vpnPermissionLauncher.launch(request) }
                                    }
                                }
                            }
    }
    fun runSmartConnect() {
        if (smartChecking || serviceState.status !in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)) return
        val currentProfiles = profiles.toList()
        val originalSelection = selectedId
        if (currentProfiles.isEmpty()) {
            smartStatus = "Добавьте хотя бы один сервер."
            return
        }
        if (currentProfiles.size > SmartConnect.MAX_PROFILES) {
            smartStatus = "Превышен лимит " + SmartConnect.MAX_PROFILES + " серверов."
            return
        }
        smartChecking = true
        smartStatus = "Проверяем доступность и TCP-задержку серверов…"
        smartJob = scope.launch {
            try {
                val results = SmartConnect.probeAll(currentProfiles) { done, total ->
                    smartStatus = "Проверено $done из $total серверов…"
                }
                if (OnesVpnService.state.value.status !in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)
                    || pendingProfileId != null) {
                    smartStatus = "Проверка закончена: состояние VPN изменилось. Автоподключение отменено."
                    return@launch
                }
                pingResults = pingResults + results.associate { item ->
                    item.profileId to (item.latencyMs?.let { it.toString() + " мс" } ?: "Недоступен")
                }
                val best = SmartConnect.choose(currentProfiles, results)
                if (best == null) {
                    smartStatus = "Нет доступных серверов. Текущий выбор сохранён."
                    return@launch
                }
                if (profiles.none { it.id == best.id && it.uri == best.uri }) {
                    smartStatus = "Список серверов изменился. Повторите проверку."
                    return@launch
                }
                if (selectedId != originalSelection) {
                    smartStatus = "Сервер был выбран вручную. Автоподключение отменено."
                    return@launch
                }
                selectedId = best.id
                context.getSharedPreferences("ui", 0).edit().putString("selected", best.id).apply()
                smartStatus = "Выбран " + best.name + " по TCP-задержке. Проверяем VPN-подключение…"
                val request = VpnService.prepare(context)
                if (request == null) connect(best.id)
                else {
                    pendingProfileId = best.id
                    vpnPermissionLauncher.launch(request)
                }
            } catch (cancelled: CancellationException) {
                smartStatus = "Проверка отменена. Текущий выбор сохранён."
                throw cancelled
            } catch (_: Exception) {
                smartStatus = "Не удалось проверить серверы. Текущий выбор сохранён."
            } finally {
                smartChecking = false
                smartJob = null
            }
        }
    }
    val connected = serviceState.status == VpnStatus.CONNECTED
    // A non-verified, still-established tunnel must not be labelled "VPN off".
    val tunnelHeldUnverified = serviceState.detail in setOf(
        VpnLifecycle.TUN_HELD_UNVERIFIED, VpnLifecycle.NATIVE_STOPPED_TUN_HELD
    ) && serviceState.status in setOf(VpnStatus.CONNECTED, VpnStatus.CONNECTING)
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(serviceState.connectedAtNanos, isScreenResumed) {
        val started = serviceState.connectedAtNanos
        if (started == null) {
            elapsedSeconds = 0L
        } else if (isScreenResumed) {
            // A paused Activity must not wake every second. When it resumes,
            // calculate the full session duration from the original timestamp.
            while (true) {
                elapsedSeconds = ((System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000_000L)
                delay(1000L - ((System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L % 1000L))
            }
        }
    }
    val durationLabel = "%02d:%02d:%02d".format(
        elapsedSeconds / 3600, (elapsedSeconds / 60) % 60, elapsedSeconds % 60)
    // This ticker is only active while the Activity is resumed.
    // elapsedRealtime advances during deep sleep, so stale proof is rejected.
    val connectionDisplay = ConnectionDisplayPolicy.evaluate(
        serviceState, SystemClock.elapsedRealtime()
    )
    val verified = connectionDisplay.verified

    val heroGlow by animateFloatAsState(if (verified) 1f else 0.70f, label = "heroGlow")
    val powerSize by animateDpAsState(if (connected) 78.dp else 74.dp, label = "powerSize")
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = dark, surface = glass)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D1120), dark, Color(0xFF070812)))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(57.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF2A3555), Color(0xFF0B0F1A))))
                    .border(1.dp, glassEdge.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("◉", fontSize = 19.sp, color = accent)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("ONES", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = brightText, letterSpacing = 2.sp)
                    Text("PRIVATE NETWORK", color = mutedText, fontSize = 8.sp, letterSpacing = 1.5.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(13.dp))
                    .background(glass.copy(alpha = 0.82f))
                    .border(1.dp, glassEdge.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text("v" + BuildConfig.VERSION_NAME, color = mutedText, fontSize = 10.sp,
                        maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        val isBusy = serviceState.status == VpnStatus.DISCONNECTING ||
                            pendingProfileId != null || smartChecking
                        val connectionLabel = connectionDisplay.label
                        val connectionSubtitle = connectionDisplay.subtitle
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(4.dp))
                            SignalPill(verified,
                                serviceState.status == VpnStatus.CONNECTING || connectionDisplay.recovering)
                            Spacer(Modifier.height(10.dp))
                            Text(connectionLabel, color = brightText, fontSize = 35.sp,
                                letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                            Text(connectionSubtitle, color = mutedText, fontSize = 9.sp,
                                letterSpacing = 1.5.sp)
                            Box(Modifier.fillMaxWidth().height(252.dp),
                                contentAlignment = Alignment.Center) {
                                NeonGlobe(Modifier.size(252.dp), heroGlow)
                                Box(Modifier.size(powerSize)
                                    .shadow(if (connected) 26.dp else 16.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(
                                        Color(0xFF485579), Color(0xFF24283F), Color(0xFF111622))))
                                    .border(1.5.dp, Brush.linearGradient(listOf(
                                        Color(0xFFE9F7FF), Color(0xFF9188DC).copy(alpha = 0.5f))),
                                        CircleShape)
                                    .semantics { contentDescription =
                                        if (serviceState.status == VpnStatus.CONNECTING)
                                            "Отменить подключение VPN"
                                        else if (connected) "Отключить VPN"
                                        else "Подключить VPN" }
                                    .clickable(enabled = !isBusy) { toggleVpn() },
                                    contentAlignment = Alignment.Center) {
                                    PowerSymbol(Modifier.size(38.dp))
                                }
                            }
                            if (connected) {
                                Text("ВРЕМЯ ПОДКЛЮЧЕНИЯ", color = mutedText, fontSize = 9.sp,
                                    letterSpacing = 1.7.sp)
                                Text(durationLabel, color = brightText, fontSize = 23.sp,
                                    letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CompactGlassCard(Modifier.weight(1f), "ЛОКАЦИЯ",
                                selected?.name ?: "Не выбрана", "Активный профиль")
                            CompactGlassCard(Modifier.weight(1f), "ЗАДЕРЖКА TCP",
                                selected?.let { pingResults[it.id] } ?: "—",
                                "До сервера")
                        }
                        Spacer(Modifier.height(10.dp))
                        val freshTraffic = verified && trafficState.isFresh(
                            android.os.SystemClock.elapsedRealtime())
                        Text("СКОРОСТЬ ЧЕРЕЗ VPN", color = mutedText, fontSize = 10.sp,
                            letterSpacing = 1.3.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CompactGlassCard(Modifier.weight(1f), "↓ ЗАГРУЗКА",
                                if (freshTraffic) TrafficFormat.rate(trafficState.downloadBps) else "—",
                                "Измерение libbox")
                            CompactGlassCard(Modifier.weight(1f), "↑ ОТДАЧА",
                                if (freshTraffic) TrafficFormat.rate(trafficState.uploadBps) else "—",
                                "Измерение libbox")
                        }
                        Spacer(Modifier.height(9.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CompactGlassCard(Modifier.weight(1f), "↓ ЗА СЕАНС",
                                if (freshTraffic) TrafficFormat.bytes(trafficState.downloadedBytes) else "—",
                                "Трафик VPN")
                            CompactGlassCard(Modifier.weight(1f), "↑ ЗА СЕАНС",
                                if (freshTraffic) TrafficFormat.bytes(trafficState.uploadedBytes) else "—",
                                "Трафик VPN")
                        }
                        if (connected && !freshTraffic) {
                            Spacer(Modifier.height(6.dp))
                            Text("Ожидаем данные от VPN-движка…",
                                color = mutedText, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(serviceState.detail.ifBlank {
                            selected?.name ?: "Выберите сервер для подключения"
                        }, color = mutedText, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Button(shape = RoundedCornerShape(19.dp),
                            enabled = !isBusy, onClick = { toggleVpn() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFCDC5FF),
                                contentColor = Color(0xFF141628),
                                disabledContainerColor = glassEdge,
                                disabledContentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().height(53.dp)) {
                            Text(when (serviceState.status) {
                                VpnStatus.CONNECTING -> "Отменить подключение"
                                VpnStatus.CONNECTED -> "Отключиться"
                                VpnStatus.DISCONNECTING -> "Отключение…"
                                else -> "Подключиться"
                            }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            enabled = smartChecking || (!pinging && !importing &&
                                !autoRefreshRunning &&
                                serviceState.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)),
                            onClick = {
                                if (smartChecking) smartJob?.cancel() else runSmartConnect()
                            },
                            shape = RoundedCornerShape(17.dp),
                            border = BorderStroke(1.dp, glassEdge.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(if (smartChecking) "✕ Отменить проверку" else "✦ Smart Connect",
                                fontSize = 13.sp, color = accent)
                        }
                        smartStatus?.let { status ->
                            Spacer(Modifier.height(6.dp))
                            Text(status, color = mutedText, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CompactGlassCard(Modifier.weight(1f), "СОСТОЯНИЕ",
                                when {
                                    verified -> "АКТИВНО"
                                    connectionDisplay.recovering -> "ПРОВЕРКА"
                                    connected -> "ТУННЕЛЬ"
                                    else -> "ОЖИДАНИЕ"
                                },
                                when {
                                    verified -> "VPN проверен"
                                    connectionDisplay.recovering -> "Связь пока не подтверждена"
                                    connected -> "Туннель запущен"
                                    else -> "VPN не активен"
                                })
                            CompactGlassCard(Modifier.weight(1f), "СЕРВЕР",
                                selected?.name ?: "—",
                                if (verified) "Связь проверена" else if (connected)
                                    "Ожидаем проверку" else "Не подключён")
                        }
                        Spacer(Modifier.height(15.dp))
                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Серверы", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("✦", color = accent, fontSize = 22.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("${profiles.size} серверов доступно", color = Color(0xFFAAA1B9), fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF1D1929)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Все", "Подписки", "Ручные").forEachIndexed { index, label ->
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                    .background(if (serverFilter == index) Color(0xFF625B90) else Color.Transparent)
                                    .clickable { serverFilter = index }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (serverFilter == index) Color.White else Color(0xFFB5ADC5), fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { importText = ""; showImport = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("＋ Добавить сервер") }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = serverSearch, onValueChange = { serverSearch = it.take(80) },
                            label = { Text("Поиск сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sortByLatency, onCheckedChange = { sortByLatency = it })
                            Text("Сортировать по задержке", color = Color.LightGray, fontSize = 13.sp)
                        }
                        profiles.filter { serverFilter == 0 || (serverFilter == 1 && it.source.isNotBlank()) || (serverFilter == 2 && it.source.isBlank()) }
                            .groupBy { it.source }.forEach { (source, members) ->
                            val groupName = if (source.isBlank()) "Добавленные вручную" else runCatching { java.net.URI(source).host }.getOrNull() ?: "Подписка"
                            val expanded = expandedSource == source
                            Column(Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF20283B), Color(0xFF111622)))).border(1.dp, glassEdge.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("◈  $groupName", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("${members.size} серверов", color = Color.LightGray, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { expandedSource = if (expanded) null else source }) {
                                        Text(if (expanded) "Скрыть ▲" else "Показать ▼")
                                    }
                                }
                                if (expanded) {
                                    if (source.isNotBlank()) {
                                        TextButton(enabled = refreshingSource == null && !importing && !autoRefreshRunning, onClick = {
                                            refreshingSource = source
                                            scope.launch {
                                                try {
                                                    val updated = withContext(Dispatchers.IO) { SubscriptionImport.load(source) }
                                                    val existing = members.associateBy { it.uri }
                                                    val replacement = updated.map { item ->
                                                        item.copy(id = existing[item.uri]?.id ?: item.id, source = source)
                                                    }
                                                    val next = profiles.filterNot { it.source == source } + replacement
                                                    withContext(Dispatchers.IO) { store.save(next) }
                                                    profiles = next
                                                    if (selectedId != null && next.none { it.id == selectedId }) {
                                                        selectedId = replacement.firstOrNull()?.id
                                                        context.getSharedPreferences("ui", 0).edit().putString("selected", selectedId).apply()
                                                    }
                                                    refreshMessage = "Подписка обновлена: ${replacement.size} серверов"
                                                } catch (_: Exception) { refreshMessage = "Обновление не удалось. Старые серверы сохранены." }
                                                finally { refreshingSource = null }
                                            }
                                        }) { Text(if (refreshingSource == source) "Обновление…" else "↻ Обновить подписку") }
                                    }
                                    TextButton(enabled = !pinging, onClick = {
                                        pinging = true
                                        scope.launch {
                                            val measured = withContext(Dispatchers.IO) {
                                                members.map { profile ->
                                                    val result = runCatching {
                                                        val started = System.nanoTime()
                                                        Socket().use { it.connect(InetSocketAddress(profile.host, profile.port), 2500) }
                                                        "${(System.nanoTime() - started) / 1_000_000} мс"
                                                    }.getOrDefault("Недоступен")
                                                    profile.id to result
                                                }.toMap()
                                            }
                                            pingResults = pingResults + measured
                                            pinging = false
                                        }
                                    }) { Text(if (pinging) "Проверяем…" else "Проверить задержку TCP") }
                                    val visibleMembers = members.filter { serverSearch.isBlank() ||
                                        it.name.contains(serverSearch, ignoreCase = true) || it.host.contains(serverSearch, ignoreCase = true) }
                                        .let { matches -> if (sortByLatency) matches.sortedBy { profile ->
                                            pingResults[profile.id]?.substringBefore(" мс")?.toLongOrNull() ?: Long.MAX_VALUE
                                        } else matches }
                                    visibleMembers.forEach { profile ->
                                        Spacer(Modifier.height(8.dp))
                                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp))
                                            .background(if (selectedId == profile.id) Color(0xFF323E58) else Color(0xFF171D2A))
                                            .border(1.dp, if (selectedId == profile.id) Color(0xFFC4B4FF) else Color(0xFF343D52), RoundedCornerShape(19.dp))
                                            .padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("◈", color = accent, fontSize = 23.sp)
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(profile.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("${profile.host}:${profile.port}", color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Text(pingResults[profile.id] ?: "—", color = if (pingResults[profile.id]?.endsWith("мс") == true) Color(0xFF66E5A7) else Color.LightGray, fontSize = 12.sp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(if (selectedId == profile.id) "◉" else "○", color = accent, fontSize = 22.sp)
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(enabled = serviceState.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR), onClick = {
                                                selectedId = profile.id
                                                context.getSharedPreferences("ui", 0).edit().putString("selected", profile.id).apply()
                                            }) { Text(if (selectedId == profile.id) "✓ Выбран" else "Выбрать") }
                                            TextButton(enabled = !importing && refreshingSource == null && !autoRefreshRunning && serviceState.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR), onClick = {
                                                val next = profiles.filterNot { it.id == profile.id }
                                                try {
                                                    store.save(next)
                                                    profiles = next
                                                    if (selectedId == profile.id) {
                                                        selectedId = null
                                                        context.getSharedPreferences("ui", 0).edit().remove("selected").apply()
                                                    }
                                                } catch (_: Exception) { error = "Не удалось удалить профиль" }
                                            }) { Text("Удалить", color = Color(0xFFFF9A9A)) }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        refreshMessage?.let { Text(it, color = accent, fontSize = 12.sp) }
                        Text("Задержка TCP — время соединения с адресом сервера, не пинг через VPN.", color = Color.LightGray, fontSize = 11.sp)

                    }
                    2 -> {
                        Text("Сеть", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(18.dp))
                        NetworkTopology(verified)
                        Spacer(Modifier.height(20.dp))
                        Panel("Этот телефон • ${android.os.Build.MODEL}",
                            when {
                                tunnelHeldUnverified -> "VPN-туннель сохранён · связь не подтверждена"
                                verified -> "VPN-связь подтверждена"
                                connected -> "VPN активен · проверяем связь"
                                serviceState.status == VpnStatus.CONNECTING -> "Устанавливаем VPN"
                                else -> "VPN отключён"
                            })
                        Spacer(Modifier.height(12.dp))
                        Panel("VPN-сервер",
                            if (connected || tunnelHeldUnverified)
                                (selected?.let { "${it.name} • ${it.host}" } ?: "Проверяем сервер")
                            else "Нет подтверждённого соединения")
                        Spacer(Modifier.height(12.dp))
                        Text("Другие устройства не отображаются: приложение не сканирует локальную сеть.", color = Color.LightGray, fontSize = 12.sp)
                    }
                    else -> {
                        Text("Настройки", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(enabled = selected != null && !checkingNative && serviceState.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR),
                            modifier = Modifier.fillMaxWidth(), onClick = {
                                val profile = selected ?: return@OutlinedButton
                                checkingNative = true
                                scope.launch {
                                    val passed = withContext(Dispatchers.IO) { NativeEngine.validate(context, profile).isSuccess }
                                    nativeResult = if (passed) "Конфигурация принята sing-box; доступность сервера не проверялась." else "Конфигурация отклонена движком."
                                    checkingNative = false
                                }
                            }) { Text(if (checkingNative) "Проверяем…" else "Проверить конфигурацию") }
                        nativeResult?.let { Text(it, color = Color.LightGray, fontSize = 12.sp) }
                        Spacer(Modifier.height(14.dp))
                        SettingsHeading("НАСТРОЙКИ ИНТЕРФЕЙСА")
                        SettingsRow("Язык", "Русский", "Язык приложения", compactSettings)
                        SettingsRow("Тема оформления", "Тёмная · фиолетовая", "Оформление ones vpn", compactSettings)
                        SettingsRow("Интерфейс", "Стеклянные карточки", "Актуальный дизайн", compactSettings)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Компактные настройки", color = Color.White)
                                Text("Меньше отступы между пунктами", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Switch(checked = compactSettings, onCheckedChange = {
                                compactSettings = it
                                context.getSharedPreferences("ui", 0).edit().putBoolean("compact_settings", it).apply()
                            })
                        }
                        Spacer(Modifier.height(20.dp))
                        SettingsHeading("ПОДПИСКИ И СЕРВЕРЫ")
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(glass.copy(alpha = 0.65f)).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Обновлять при открытии", color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Без фоновой работы · сохраняет старые серверы при ошибке", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Switch(enabled = !importing && refreshingSource == null && !autoRefreshRunning, checked = autoRefresh, onCheckedChange = {
                                autoRefresh = it
                                context.getSharedPreferences("ui", 0).edit().putBoolean("auto_refresh", it).apply()
                            })
                        }
                        Spacer(Modifier.height(20.dp))
                        SettingsHeading("ПОДКЛЮЧЕНИЕ И БЕЗОПАСНОСТЬ")
                        SettingsRow("VPN-движок", "sing-box 1.14.4", "Движок подключения", compactSettings)
                        SettingsRow("Хранение профилей", "Зашифровано", "Защищённое хранилище Android", compactSettings)
                        val protectionLevel = if (serviceState.status == VpnStatus.CONNECTED)
                            protectionSnapshot.level else SystemProtectionLevel.UNKNOWN
                        val protectionTitle = when (protectionLevel) {
                            SystemProtectionLevel.LOCKDOWN -> "Kill Switch · системная блокировка включена"
                            SystemProtectionLevel.ALWAYS_ON -> "Kill Switch · Always-on без блокировки"
                            SystemProtectionLevel.VPN_ONLY -> "Kill Switch · системная блокировка выключена"
                            SystemProtectionLevel.UNKNOWN -> "Kill Switch · состояние не определено"
                        }
                        val protectionDetail = when (protectionLevel) {
                            SystemProtectionLevel.LOCKDOWN ->
                                "Android сообщает, что включены Always-on VPN и блокировка соединений вне VPN. " +
                                    "Реальную защиту при обрыве сети всё равно нужно проверить отдельно."
                            SystemProtectionLevel.ALWAYS_ON ->
                                "Android сообщает, что включена постоянная VPN, но трафик вне VPN НЕ блокируется. " +
                                    "При сбое обычный интернет может возобновиться."
                            SystemProtectionLevel.VPN_ONLY ->
                                "VPN подключён, но Always-on и системная блокировка трафика выключены. " +
                                    "При отключении VPN обычный интернет возобновится."
                            SystemProtectionLevel.UNKNOWN ->
                                "Не удалось получить актуальный статус Android. Подключите VPN и нажмите " +
                                    "«Проверить защиту». Неизвестно — не значит выключено."
                        }
                        Panel(protectionTitle, protectionDetail)
                        Spacer(Modifier.height(8.dp))
                        Panel("Восстановление Always-on",
                            "После ручного подключения сохраняется только идентификатор профиля, " +
                                "не его ключи. Если Android запускает VPN в режиме Always-on, " +
                                "приложение попробует восстановить последний подключавшийся профиль. " +
                                "Ручное отключение отменяет автозапуск. Для блокировки трафика при сбоях " +
                                "отдельно включите «Блокировать соединения без VPN» в настройках Android.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = serviceState.status == VpnStatus.CONNECTED,
                            onClick = {
                                protectionCheckBaseline = protectionSnapshot.manualCheckSequence
                                try {
                                    context.startService(Intent(context, OnesVpnService::class.java)
                                        .setAction(OnesVpnService.ACTION_CHECK_SYSTEM_PROTECTION))
                                } catch (_: Exception) {
                                    protectionCheckBaseline = -1L
                                    error = "Не удалось прочитать состояние защиты Android."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Проверить системную защиту") }
                        if (protectionCheckBaseline >= 0L && serviceState.status == VpnStatus.CONNECTED) {
                            val reviewMessage = if (protectionSnapshot.manualCheckSequence <= protectionCheckBaseline) {
                                "Запрашиваем состояние защиты Android…"
                            } else when (protectionSnapshot.level) {
                                SystemProtectionLevel.LOCKDOWN ->
                                    "Проверено: Android сообщает, что блокировка вне VPN включена. Проверка при обрыве ещё нужна."
                                SystemProtectionLevel.ALWAYS_ON ->
                                    "Проверено: Always-on включён, блокировка вне VPN выключена."
                                SystemProtectionLevel.VPN_ONLY ->
                                    "Проверено: Always-on и блокировка вне VPN выключены."
                                SystemProtectionLevel.UNKNOWN ->
                                    "Проверка завершена: Android не сообщил состояние защиты."
                            }
                            Text(reviewMessage, color = mutedText, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Открыть настройки VPN Android") }
                        SettingsRow("Маршрутизация", "VPN-туннель", "Исключения доступны ниже", compactSettings)
                        Spacer(Modifier.height(18.dp))
                        SettingsHeading("ЛОКАЛЬНЫЙ АУДИТ СЕТИ")
                        Panel("Маршруты IPv4, IPv6 и DNS",
                            "Проверяем только сведения Android о нашем VPN-туннеле. " +
                                "Никаких запросов к сторонним сайтам. " +
                                "Это предварительная диагностика, НЕ доказательство отсутствия " +
                                "DNS, IPv6 или WebRTC-утечек.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = verified && routeAudit.stage != AuditStage.RUNNING,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                try {
                                    context.startService(Intent(context, OnesVpnService::class.java)
                                        .setAction(OnesVpnService.ACTION_AUDIT_ROUTES))
                                } catch (_: Exception) {
                                    error = "Не удалось проверить маршруты VPN."
                                }
                            }
                        ) {
                            Text(if (routeAudit.stage == AuditStage.RUNNING)
                                "Проверяем маршруты…" else "Проверить маршруты VPN")
                        }
                        when (routeAudit.stage) {
                            AuditStage.COMPLETED -> routeAudit.result?.let { evidence ->
                                Spacer(Modifier.height(8.dp))
                                Panel("IPv4 и IPv6",
                                    "Полное покрытие IPv4: " +
                                        (if (evidence.fullIpv4Coverage) "да" else "нет") +
                                        ". Полное покрытие IPv6: " +
                                        (if (evidence.fullIpv6Coverage) "да" else "нет") +
                                        ". Контрольные адреса: IPv4 " + evidence.ipv4Examples +
                                        "/2, IPv6 " + evidence.ipv6Examples +
                                        "/2. Маршрутов передачи через VPN: " + evidence.totalRoutes +
                                        (if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU)
                                            ". Android ниже 13 не позволяет проверить типы маршрутов: " +
                                                "полное покрытие не подтверждено."
                                         else if (!evidence.fullIpv4Coverage || !evidence.fullIpv6Coverage)
                                            ". ВНИМАНИЕ: обнаружено неполное покрытие IP-маршрутов; " +
                                                "нужна дополнительная проверка возможного обхода VPN."
                                         else ". Полное покрытие в таблице Android не гарантирует отсутствие утечек.") )
                                Spacer(Modifier.height(8.dp))
                                Panel("DNS и исключения",
                                    "DNS-серверов, объявленных Android для VPN: " +
                                        evidence.vpnDnsServers + ". " +
                                        (if (evidence.dnsServersWithoutVpnRoute != null)
                                            "IPv4 DNS: ${evidence.dnsIpv4Servers}, " +
                                                "IPv6 DNS: ${evidence.dnsIpv6Servers}. " +
                                                "Без подтверждённого маршрута через VPN: " +
                                                "${evidence.dnsServersWithoutVpnRoute}. "
                                         else "Типы маршрутов DNS проверить на этой версии Android нельзя. ") +
                                        "Исключений по приложениям: " + evidence.appExclusions +
                                        ". Метаданные не доказывают отсутствие DNS-утечек.")
                                Spacer(Modifier.height(8.dp))
                                val findings = LocalLeakReviewPolicy.assess(
                                    evidence, protectionLevel, android.os.Build.VERSION.SDK_INT
                                )
                                Panel("Что ещё требует проверки",
                                    findings.joinToString("\n\n") { "• " + it.message })
                            }
                            AuditStage.NO_TUN -> {
                                Spacer(Modifier.height(8.dp))
                                Text("Не удалось найти настройки нашего туннеля. " +
                                    "Повторите после установления соединения.",
                                    color = mutedText, fontSize = 12.sp)
                            }
                            AuditStage.FAILED -> {
                                Spacer(Modifier.height(8.dp))
                                Text("Ошибка чтения сетевых маршрутов Android.",
                                    color = mutedText, fontSize = 12.sp)
                            }
                            else -> Unit
                        }
                        Spacer(Modifier.height(20.dp))
                        SettingsHeading("ВНЕШНИЙ IP ЧЕРЕЗ VPN")
                        Panel("IPv4 и IPv6 глазами сайта",
                            "По нажатию отправим два HTTPS-запроса сервису ipify строго " +
                                "через VPN-сеть Android. Сервис увидит IP-адрес запроса. " +
                                "Результаты останутся только в памяти приложения. " +
                                "Это не полная проверка DNS и WebRTC-утечек.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = verified && !externalIp.inProgress,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showFullExternalIp = false
                                try {
                                    context.startService(Intent(context, OnesVpnService::class.java)
                                        .setAction(OnesVpnService.ACTION_EXTERNAL_IP_CHECK))
                                } catch (_: Exception) {
                                    error = "Не удалось запустить проверку внешнего IP."
                                }
                            }
                        ) {
                            Text(if (externalIp.inProgress) "Проверяем внешний IP…"
                                else "Проверить внешний IPv4 и IPv6")
                        }
                        if (externalIp.noVpnNetwork) {
                            Spacer(Modifier.height(8.dp))
                            Panel("Нет VPN-сети",
                                "Android не предоставил нашу активную VPN-сеть. " +
                                    "Проверка остановлена без перехода на обычный интернет.")
                        } else if (externalIp.ipv4.status != ExternalIpStatus.NOT_CHECKED) {
                            Spacer(Modifier.height(8.dp))
                            val ipv4Status = when (externalIp.ipv4.status) {
                                ExternalIpStatus.MEASURED -> {
                                    val value = externalIp.ipv4.address.orEmpty()
                                    if (showFullExternalIp) value else ExternalIpFormat.masked(value)
                                }
                                ExternalIpStatus.CHECKING -> "Получаем ответ…"
                                ExternalIpStatus.UNAVAILABLE -> "Недоступен через VPN или ошибка сервиса"
                                ExternalIpStatus.INVALID_RESPONSE -> "Ответ сервиса не прошёл проверку"
                                else -> "Не проверялся"
                            }
                            val ipv6Status = when (externalIp.ipv6.status) {
                                ExternalIpStatus.MEASURED -> {
                                    val value = externalIp.ipv6.address.orEmpty()
                                    if (showFullExternalIp) value else ExternalIpFormat.masked(value)
                                }
                                ExternalIpStatus.CHECKING -> "Получаем ответ…"
                                ExternalIpStatus.UNAVAILABLE ->
                                    "Недоступен через VPN или ошибка сервиса; это не доказательство блокировки IPv6"
                                ExternalIpStatus.INVALID_RESPONSE -> "Ответ сервиса не прошёл проверку"
                                else -> "Не проверялся"
                            }
                            Panel("Внешний IPv4", ipv4Status)
                            Spacer(Modifier.height(7.dp))
                            Panel("Внешний IPv6", ipv6Status)
                            if (externalIp.ipv4.address != null || externalIp.ipv6.address != null) {
                                TextButton(onClick = { showFullExternalIp = !showFullExternalIp }) {
                                    Text(if (showFullExternalIp) "Скрыть полные IP"
                                        else "Показать полные IP на экране")
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsHeading("БРАУЗЕРНАЯ ПРОВЕРКА УТЕЧЕК")
                        Panel("Системный Private DNS Android",
                            PrivateDnsPolicy.explanation(privateDnsMode))
                        Spacer(Modifier.height(8.dp))
                        Panel("DNS и WebRTC",
                            "Дополнительные тесты запускаются отдельно в браузере. " +
                                "Сайты получат сетевые данные браузера. Сравните результаты " +
                                "с VPN включённым и выключенным; приложение не загружает их автоматически. " +
                                "Убедитесь, что браузер не исключён из VPN. " +
                                "Открытие тестов доступно только после новой успешной проверки " +
                                "соединения через VPN.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = verified,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://browserleaks.com/dns")))
                                } catch (_: Exception) {
                                    error = "Не удалось открыть DNS-тест в браузере."
                                }
                            }
                        ) { Text("Проверить DNS · BrowserLeaks") }
                        Spacer(Modifier.height(7.dp))
                        OutlinedButton(
                            enabled = verified,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://www.dnsleaktest.com/")))
                                } catch (_: Exception) {
                                    error = "Не удалось открыть резервный DNS-тест."
                                }
                            }
                        ) { Text("Резервный DNS-тест") }
                        Spacer(Modifier.height(7.dp))
                        OutlinedButton(
                            enabled = verified,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://browserleaks.com/webrtc")))
                                } catch (_: Exception) {
                                    error = "Не удалось открыть WebRTC-тест в браузере."
                                }
                            }
                        ) { Text("Проверить WebRTC · BrowserLeaks") }
                        Spacer(Modifier.height(12.dp))
                        SettingsHeading("SPLIT TUNNELING")
                        Panel("Исключения по приложениям",
                            if (excludedApps.isEmpty()) "Исключений нет. Приложения используют стандартную VPN-маршрутизацию Android."
                            else "Выбрано исключений: " + excludedApps.size +
                                ". Они используют обычную сеть вне VPN.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { splitExpanded = !splitExpanded },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (splitExpanded) "Скрыть список приложений" else "Выбрать приложения вне VPN")
                        }
                        if (splitExpanded) {
                            Spacer(Modifier.height(9.dp))
                            Text("ВНИМАНИЕ: исключённые приложения обходят VPN и могут раскрывать " +
                                "ваш настоящий IP-адрес и DNS. Настройка применяется при следующем " +
                                "подключении. Системный Kill Switch может блокировать их трафик.",
                                color = Color(0xFFFFD7AA), fontSize = 12.sp, lineHeight = 17.sp)
                            val splitEditable = serviceState.status in
                                setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR) && !smartChecking
                            if (!splitEditable) {
                                Spacer(Modifier.height(6.dp))
                                Text("Для изменения исключений отключите VPN.",
                                    color = mutedText, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = splitSearch,
                                onValueChange = { splitSearch = it.take(80) },
                                label = { Text("Поиск приложения") },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            val launcherApps = remember(splitExpanded) {
                                if (splitExpanded) loadLauncherApps(context) else emptyList()
                            }
                            val matchingApps = launcherApps.filter { app ->
                                splitSearch.isBlank() ||
                                    app.name.contains(splitSearch, ignoreCase = true) ||
                                    app.packageName.contains(splitSearch, ignoreCase = true)
                            }
                            matchingApps.take(40).forEach { app ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.name, color = brightText, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(app.packageName, color = mutedText, fontSize = 10.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Checkbox(
                                        enabled = splitEditable,
                                        checked = app.packageName in excludedApps,
                                        onCheckedChange = { checked ->
                                            val next = if (checked) excludedApps + app.packageName
                                                else excludedApps - app.packageName
                                            try {
                                                splitStore.save(next)
                                                excludedApps = next
                                            } catch (_: Exception) {
                                                error = "Не удалось сохранить исключения приложений."
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider(color = glassEdge.copy(alpha = 0.22f))
                            }
                            if (matchingApps.size > 40) {
                                Text("Показаны первые 40 приложений. Уточните поиск.",
                                    color = mutedText, fontSize = 11.sp)
                            }
                            if (launcherApps.isEmpty()) {
                                Text("Не удалось найти приложения с иконкой запуска.",
                                    color = mutedText, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(7.dp))
                            OutlinedButton(enabled = splitEditable && excludedApps.isNotEmpty(),
                                onClick = {
                                    try {
                                        splitStore.save(emptySet())
                                        excludedApps = emptySet()
                                    } catch (_: Exception) {
                                        error = "Не удалось сбросить исключения."
                                    }
                                }) {
                                Text("Сбросить все исключения")
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        SettingsHeading("РЕЗЕРВНОЕ КОПИРОВАНИЕ")
                        Panel("Защищённая копия профилей",
                            "Перенос серверов и ссылок подписок с паролем. Файл зашифрован AES-256-GCM. " +
                                "Пароль нигде не сохраняется; без него восстановление невозможно. " +
                                "Исключения приложений и настройки Android не экспортируются.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = !backupBusy && profiles.isNotEmpty() && !importing && !autoRefreshRunning,
                            onClick = {
                                backupPassword = ""
                                backupRepeatPassword = ""
                                backupStatus = null
                                exportDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Экспорт зашифрованной копии") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = !backupBusy && !importing && !autoRefreshRunning &&
                                !smartChecking && serviceState.status in
                                    setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR),
                            onClick = {
                                backupPassword = ""
                                backupStatus = null
                                importBackupDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Восстановить из копии") }
                        backupStatus?.let {
                            Spacer(Modifier.height(7.dp))
                            Text(it, color = mutedText, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                        if (backupBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(20.dp))
                        SettingsHeading("ОБНОВЛЕНИЯ И ПОДДЕРЖКА")
                        SettingsRow("Версия приложения", BuildConfig.VERSION_NAME, "ones vpn", compactSettings)
                        SettingsRow("Центр обновлений", updateStatus, "Проверка подписанного APK", compactSettings)
                        Spacer(Modifier.height(12.dp))
                        Button(enabled = !checkingUpdate && !installingUpdate, modifier = Modifier.fillMaxWidth(), onClick = {
                            checkingUpdate = true
                            scope.launch {
                                try {
                                    availableUpdate = withContext(Dispatchers.IO) {
                                        AppUpdates.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
                                    }
                                    updateStatus = availableUpdate?.let { "Доступна версия: ${it.title}" } ?: "Установлена актуальная версия"
                                } catch (failure: Exception) { updateStatus = (failure as? AppUpdates.UpdateException)?.message ?: "Не удалось проверить обновления. Попробуйте позже." }
                                finally { checkingUpdate = false }
                            }
                        }) { Text(if (checkingUpdate) "Проверяем…" else "Проверить обновления") }
                        availableUpdate?.let { release ->
                            Text(release.notes.ifBlank { "Новая версия ONES VPN" }, color = Color.LightGray, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(enabled = !installingUpdate, modifier = Modifier.fillMaxWidth(), onClick = {
                                installingUpdate = true
                                updateStatus = "Загрузка и проверка APK…"
                                scope.launch {
                                    try {
                                        val installerOpened = withContext(Dispatchers.IO) {
                                            AppUpdates.downloadAndInstall(context, release)
                                        }
                                        if (!installerOpened) {
                                            // Launch Android settings on the UI thread, not from IO.
                                            AppUpdates.openInstallPermissionSettings(context)
                                        }
                                        updateStatus = if (installerOpened)
                                            "Подтвердите установку в Android"
                                        else "Разрешите установку из этого приложения и нажмите «Скачать и установить» ещё раз"
                                    } catch (failure: Exception) { updateStatus = (failure as? AppUpdates.UpdateException)?.message ?: "Не удалось загрузить или проверить APK" }
                                    finally { installingUpdate = false }
                                }
                            }) { Text(if (installingUpdate) "Загружаем…" else "Скачать и установить") }
                        }
                        Spacer(Modifier.height(12.dp))
                        var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                            Text(if (diagnosticsExpanded) "Скрыть диагностику ▲" else "Диагностика ▼")
                        }
                        if (diagnosticsExpanded) Panel("Последние события",
                            diagnosticEvents.takeLast(12)
                                .joinToString("\n") { DiagnosticText.describe(it) }
                                .ifBlank { "Событий пока нет" })
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/onesvpn_official"))) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Telegram • Новости ONES VPN")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2030), Color(0xFF10131D))))
                .border(1.dp, glassEdge.copy(alpha = 0.43f), RoundedCornerShape(26.dp))
                .padding(horizontal = 5.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("⌂" to "Главная", "◈" to "Серверы",
                    "◎" to "Сеть", "⚙" to "Настройки").forEachIndexed { index, item ->
                    val active = tab == index
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .clickable { tab = index }
                        .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(37.dp).clip(CircleShape)
                            .background(if (active) Brush.radialGradient(listOf(
                                Color(0xFFDFD7FF), Color(0xFF8E7BC5)))
                                else Brush.radialGradient(listOf(
                                    Color(0xFF252A3B), Color(0xFF151924))))
                            .border(1.dp,
                                if (active) Color(0xFFEBE5FF) else glassEdge.copy(alpha = 0.2f),
                                CircleShape), contentAlignment = Alignment.Center) {
                            Text(item.first, color = if (active) Color(0xFF161727) else mutedText,
                                fontSize = 22.sp)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(item.second, color = if (active) brightText else mutedText,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
        }
        if (exportDialog) AlertDialog(
            onDismissRequest = {
                exportDialog = false
                backupPassword = ""
                backupRepeatPassword = ""
            },
            title = { Text("Зашифровать резервную копию") },
            text = {
                Column {
                    Text("В копию войдут адреса серверов, ключи VLESS и ссылки подписок. " +
                        "Придумайте отдельный пароль от 12 символов и сохраните его в надёжном месте. " +
                        "Без пароля восстановление невозможно.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it.take(128) },
                        label = { Text("Пароль копии") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backupRepeatPassword,
                        onValueChange = { backupRepeatPassword = it.take(128) },
                        label = { Text("Повторите пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy &&
                        EncryptedProfileBackup.validPassword(backupPassword.toCharArray()) &&
                        backupPassword == backupRepeatPassword,
                    onClick = {
                        awaitingExportPassword = backupPassword
                        backupPassword = ""
                        backupRepeatPassword = ""
                        exportDialog = false
                        exportPicker.launch("ones-vpn-profiles.onesvpn")
                    }
                ) { Text("Выбрать файл") }
            },
            dismissButton = {
                TextButton(onClick = {
                    exportDialog = false
                    backupPassword = ""
                    backupRepeatPassword = ""
                }) { Text("Отмена") }
            }
        )
        if (importBackupDialog) AlertDialog(
            onDismissRequest = {
                importBackupDialog = false
                backupPassword = ""
            },
            title = { Text("Открыть зашифрованную копию") },
            text = {
                Column {
                    Text("Файл будет проверен и расшифрован перед восстановлением. " +
                        "Существующие серверы не удаляются и не перезаписываются.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it.take(128) },
                        label = { Text("Пароль резервной копии") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy &&
                        EncryptedProfileBackup.validPassword(backupPassword.toCharArray()),
                    onClick = {
                        awaitingImportPassword = backupPassword
                        backupPassword = ""
                        importBackupDialog = false
                        importPicker.launch(arrayOf("*/*"))
                    }
                ) { Text("Выбрать копию") }
            },
            dismissButton = {
                TextButton(onClick = {
                    importBackupDialog = false
                    backupPassword = ""
                }) { Text("Отмена") }
            }
        )
        pendingBackupProfiles?.let { imported ->
            val alreadyThere = profiles.map { it.uri }.toSet()
            val newCount = imported.count { it.uri !in alreadyThere }
            AlertDialog(
                onDismissRequest = { if (!backupBusy) pendingBackupProfiles = null },
                title = { Text("Подтвердите восстановление") },
                text = {
                    Text("В копии: " + imported.size + " серверов. Новых: " + newCount +
                        ". Уже имеющихся: " + (imported.size - newCount) +
                        ". Существующие профили сохранятся, ничего не будет удалено.",
                        fontSize = 13.sp)
                },
                confirmButton = {
                    TextButton(
                        enabled = !backupBusy && !autoRefreshRunning &&
                            serviceState.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR),
                        onClick = {
                            backupBusy = true
                            scope.launch {
                                try {
                                    val (restored, added) = withContext(Dispatchers.IO) {
                                        // Reload immediately before merge so profiles changed since
                                        // preview cannot be overwritten with stale UI state.
                                        val existing = store.load()
                                        val merged = EncryptedProfileBackup.merge(existing, imported)
                                        if (merged.size > existing.size) store.save(merged)
                                        merged to (merged.size - existing.size)
                                    }
                                    profiles = restored
                                    backupStatus = "Восстановлено новых серверов: $added. " +
                                        "Существующие серверы сохранены."
                                } catch (_: Exception) {
                                    backupStatus = "Восстановление не удалось. " +
                                        "Существующие серверы не изменены."
                                } finally {
                                    pendingBackupProfiles = null
                                    backupBusy = false
                                }
                            }
                        }
                    ) { Text(if (backupBusy) "Восстанавливаем…" else "Добавить новые") }
                },
                dismissButton = {
                    TextButton(enabled = !backupBusy,
                        onClick = { pendingBackupProfiles = null }
                    ) { Text("Отмена") }
                }
            )
        }
        if (showImport) AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Импорт серверов") },
            text = {
                Column {
                    Text("Вставьте vless:// или HTTPS-ссылку на подписку. HTTPS-подписка сохранится в зашифрованном хранилище для ручного обновления.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = importText, onValueChange = { importText = it.take(8192) }, label = { Text("Ссылка на сервер или подписку") }, minLines = 3, maxLines = 6)
                }
            },
            confirmButton = {
                TextButton(enabled = !importing && refreshingSource == null && !autoRefreshRunning, onClick = {
                    val raw = importText.trim()
                    importing = true
                    scope.launch {
                        try {
                            val imported = withContext(Dispatchers.IO) {
                                if (raw.startsWith("https://", true)) SubscriptionImport.load(raw)
                                else listOf(parseProfile(raw).also { SingBoxConfig.outbound(it) })
                            }
                            val unique = imported.filter { item -> profiles.none { it.uri == item.uri } }.map { it.copy(source = if (raw.startsWith("https://", true)) raw else "") }
                            require(unique.isNotEmpty()) { "Все серверы уже добавлены" }
                            val next = profiles + unique
                            withContext(Dispatchers.IO) { store.save(next) }
                            profiles = next
                            if (selectedId == null) {
                                selectedId = unique.first().id
                                context.getSharedPreferences("ui", 0).edit().putString("selected", selectedId).apply()
                            }
                            importText = ""
                            showImport = false
                        } catch (e: Exception) {
                            error = "Не удалось импортировать серверы. Проверьте формат VLESS, поддерживаемый транспорт и доступность подписки."
                        } finally { importing = false }
                    }
                }) { Text(if (importing) "Загрузка…" else "Импортировать") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Отмена") } }
        )
        error?.let { message ->
            AlertDialog(onDismissRequest = { error = null }, title = { Text("Не удалось выполнить действие") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } })
        }
    }
}

private data class LauncherApp(val packageName: String, val name: String)

/** Only user-launchable installed apps; no QUERY_ALL_PACKAGES permission. */
private fun loadLauncherApps(context: Context): List<LauncherApp> {
    val manager = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        manager.queryIntentActivities(query, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val name = runCatching { info.loadLabel(manager).toString() }
                    .getOrDefault(packageName)
                LauncherApp(packageName, name)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }.getOrDefault(emptyList())
}

@Composable
private fun Panel(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF202637), Color(0xFF121723))))
        .border(1.dp, glassEdge.copy(alpha = 0.36f), RoundedCornerShape(23.dp))
        .padding(17.dp)) {
        Text(title, color = brightText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(7.dp))
        Text(detail, color = mutedText, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SignalPill(connected: Boolean, connecting: Boolean) {
    val signalColor = if (connected) mintGlow else if (connecting) cyanGlow else mutedText
    Row(Modifier.clip(RoundedCornerShape(50))
        .background(Color(0xFF192333))
        .border(1.dp, signalColor.copy(alpha = 0.30f), RoundedCornerShape(50))
        .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(signalColor))
        Spacer(Modifier.width(7.dp))
        Text(if (connected) "SIGNAL ACTIVE" else if (connecting) "CONNECTING" else "VPN STANDBY",
            fontSize = 9.sp, letterSpacing = 1.2.sp, color = signalColor)
    }
}

@Composable
private fun CompactGlassCard(
    modifier: Modifier = Modifier, title: String, value: String, caption: String
) {
    Column(modifier.clip(RoundedCornerShape(19.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF252A3C), Color(0xFF141824))))
        .border(1.dp, glassEdge.copy(alpha = 0.36f), RoundedCornerShape(19.dp))
        .padding(horizontal = 13.dp, vertical = 12.dp)) {
        Text(title, color = mutedText, fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Text(value, color = brightText, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(caption, color = mutedText, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsHeading(label: String) {
    Text(label, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(9.dp))
}

@Composable
private fun SettingsRow(title: String, value: String, description: String, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 7.dp else 13.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, color = Color(0xFFC5B3FF), fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))
        Text(description, color = Color(0xFFAAA5BB), fontSize = 11.sp)
    }
    HorizontalDivider(color = Color(0xFF534763).copy(alpha = 0.6f))
}

@Composable
private fun NeonGlobe(modifier: Modifier = Modifier, glow: Float = 1f) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.365f
        val highlight = Offset(c.x - radius * 0.36f, c.y - radius * 0.52f)
        // Soft atmospheric glow and thin orbital rings.
        drawCircle(brush = Brush.radialGradient(listOf(
            Color(0xFF8272DF).copy(alpha = 0.25f * glow),
            Color(0xFF3357A7).copy(alpha = 0.12f * glow),
            Color.Transparent), center = c, radius = radius * 1.50f),
            radius = radius * 1.50f, center = c)
        drawCircle(color = accent.copy(alpha = 0.15f * glow), radius = radius * 1.19f,
            center = c, style = Stroke(1.1f))
        drawCircle(color = cyanGlow.copy(alpha = 0.12f * glow), radius = radius * 1.34f,
            center = c, style = Stroke(1f))
        // 3D-like glass sphere; highlights are intentionally off-center.
        drawCircle(brush = Brush.radialGradient(listOf(
            Color(0xFFB8F3FF), Color(0xFF5EA8DE), Color(0xFF335D9E),
            Color(0xFF1A2E51), Color(0xFF11192D), Color(0xFF080B16)),
            center = highlight, radius = radius * 1.83f),
            radius = radius, center = c)
        // Very subtle globe grid under the transparent highlight.
        for (fraction in listOf(-0.60f, -0.28f, 0.28f, 0.60f)) {
            val line = Path().apply {
                moveTo(c.x, c.y - radius)
                cubicTo(c.x + fraction * radius * 1.22f, c.y - radius * 0.55f,
                    c.x + fraction * radius * 1.22f, c.y + radius * 0.55f,
                    c.x, c.y + radius)
            }
            drawPath(line, Color(0xFFB1DCFF).copy(alpha = 0.09f),
                style = Stroke(1.05f))
        }
        for (latitude in listOf(-52.0, -24.0, 12.0, 42.0)) {
            val angle = latitude * PI / 180.0
            val y = c.y + (sin(angle) * radius).toFloat()
            val halfWidth = radius * cos(angle).toFloat()
            val line = Path().apply {
                moveTo(c.x - halfWidth, y)
                cubicTo(c.x - halfWidth * 0.4f, y - radius * 0.08f,
                    c.x + halfWidth * 0.4f, y - radius * 0.08f,
                    c.x + halfWidth, y)
            }
            drawPath(line, Color(0xFFCEE9FF).copy(alpha = 0.11f),
                style = Stroke(1.05f))
        }
        drawCircle(brush = Brush.radialGradient(listOf(
            Color.White.copy(alpha = 0.48f * glow),
            cyanGlow.copy(alpha = 0.18f * glow), Color.Transparent),
            center = highlight, radius = radius * 0.75f),
            radius = radius * 0.75f, center = highlight)
        val lowerGlow = Offset(c.x + radius * 0.52f, c.y + radius * 0.57f)
        drawCircle(brush = Brush.radialGradient(listOf(
            Color(0xFFA48CFF).copy(alpha = 0.28f * glow), Color.Transparent),
            center = lowerGlow, radius = radius * 0.80f),
            radius = radius * 0.80f, center = lowerGlow)
        drawCircle(color = Color(0xFFB7ECFF).copy(alpha = 0.76f * glow),
            radius = radius, center = c, style = Stroke(2.4f))
        drawCircle(color = Color.White.copy(alpha = 0.16f * glow),
            radius = radius * 1.025f, center = c, style = Stroke(4.3f))
        for ((x, y) in listOf(-1.07f to -0.42f, 1.08f to -0.12f,
            -0.92f to 0.63f, 0.98f to 0.74f)) {
            drawCircle(color = cyanGlow.copy(alpha = 0.75f * glow),
                radius = 2.3f, center = Offset(c.x + x * radius, c.y + y * radius))
        }
    }
}

@Composable
private fun NetworkTopology(connected: Boolean) {
    val linkColor = if (connected) mintGlow else accent.copy(alpha = 0.48f)
    Box(Modifier.fillMaxWidth().height(258.dp)
        .clip(RoundedCornerShape(28.dp))
        .background(Brush.radialGradient(listOf(
            Color(0xFF252D46), Color(0xFF101522), Color(0xFF090D16))))
        .border(1.dp, glassEdge.copy(alpha = 0.42f), RoundedCornerShape(28.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.54f)
            val left = Offset(size.width * 0.22f, size.height * 0.71f)
            val right = Offset(size.width * 0.78f, size.height * 0.71f)
            drawLine(linkColor, left, center, strokeWidth = 2.2f)
            drawLine(linkColor, center, right, strokeWidth = 2.2f)
            drawCircle(brush = Brush.radialGradient(listOf(
                linkColor.copy(alpha = 0.21f), Color.Transparent),
                center, 64f), radius = 64f, center = center)
            drawCircle(Color(0xFF242F49), radius = 30f, center = center)
            drawCircle(linkColor, radius = 30f, center = center,
                style = Stroke(2.4f))
            drawCircle(linkColor, radius = 7f, center = left)
            drawCircle(linkColor, radius = 7f, center = right)
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NETWORK PATH", color = brightText, fontSize = 11.sp,
                letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (connected) "Защищённый маршрут активен" else "Маршрут не активен",
                color = mutedText, fontSize = 11.sp)
        }
        Text("◈", color = Color.White, fontSize = 27.sp,
            modifier = Modifier.align(Alignment.Center).offset(y = 10.dp))
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("УСТРОЙСТВО", color = mutedText, fontSize = 9.sp, letterSpacing = 1.sp)
            Text("VPN", color = mutedText, fontSize = 9.sp, letterSpacing = 1.sp)
            Text("СЕРВЕР", color = mutedText, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun PowerSymbol(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.32f
        val lineWidth = size.minDimension * 0.085f
        val path = Path()
        // A conventional power glyph: open at the TOP, with a continuous bottom arc.
        // Cartesian angles: -45° is upper-right, 225° is upper-left.
        for (step in 0..72) {
            val angle = Math.toRadians(-45.0 + step * 270.0 / 72.0)
            val point = Offset(
                center.x + radius * cos(angle).toFloat(),
                center.y + radius * sin(angle).toFloat()
            )
            if (step == 0) path.moveTo(point.x, point.y)
            else path.lineTo(point.x, point.y)
        }
        drawPath(path, Color.White, style = Stroke(width = lineWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(Color.White,
            Offset(center.x, center.y - radius * 1.35f),
            Offset(center.x, center.y - radius * 0.12f),
            strokeWidth = lineWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
