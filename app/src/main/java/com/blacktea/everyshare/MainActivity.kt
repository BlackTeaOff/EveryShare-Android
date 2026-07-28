package com.blacktea.everyshare

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blacktea.everyshare.core.AppLogger
import com.blacktea.everyshare.core.ConnectionCodeUtil
import com.blacktea.everyshare.core.ProgressListener
import com.blacktea.everyshare.core.TcpPunchTransfer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.io.FileInputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// 选项卡：传输、设置
enum class ActiveTab { TRANSFER, SETTINGS }

// 会话状态：空闲、连接中、会话中
enum class SessionState { IDLE, CONNECTING, ACTIVE }

// 视觉配置：深色/浅色模式
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// 视觉配置：预设配色主题
enum class AppTheme { LAVENDER, SAGE, SLATE, OAT }

class MainActivity : ComponentActivity() {
    private val TAG = "EveryShare"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 状态栏边缘避让与深色文字图标
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = true

        setContent {
            val context = LocalContext.current

            // 💡 1. 引入 SharedPreferences 存储，防止设置在 App 退出后重置 [1]
            val prefs = remember { context.getSharedPreferences("everyshare_prefs", Context.MODE_PRIVATE) }

            var useDynamicColor by rememberSaveable {
                mutableStateOf(prefs.getBoolean("use_dynamic_color", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S))
            }
            var themeMode by rememberSaveable {
                mutableStateOf(ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name))
            }
            var appTheme by rememberSaveable {
                mutableStateOf(AppTheme.valueOf(prefs.getString("app_theme", AppTheme.SLATE.name) ?: AppTheme.SLATE.name))
            }
            var useUdpSync by rememberSaveable {
                mutableStateOf(prefs.getBoolean("use_udp_sync", true))
            }
            var fakeHttpOption by rememberSaveable {
                mutableStateOf(prefs.getString("fake_http_option", "speedtest.cn") ?: "speedtest.cn")
            }
            var customFakeHttpHost by rememberSaveable {
                mutableStateOf(prefs.getString("custom_fake_http_host", "") ?: "")
            }

            // 💡 2. 动态计算当前是否应该使用深色主题
            val systemInDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> systemInDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // 💡 3. 构建当前的主题色色板
            val rawColorScheme = when {
                useDynamicColor && isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicDarkColorScheme(context)
                useDynamicColor && !isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
                isDark -> getDarkPresetScheme(appTheme)
                else -> getLightPresetScheme(appTheme)
            }

            // 主题切换时享受渐变动画过渡
            val animatedColorScheme = animateColorScheme(rawColorScheme)

            MaterialTheme(colorScheme = animatedColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EveryShareScreen(
                        useDynamicColor = remember { mutableStateOf(useDynamicColor) }.apply { value = useDynamicColor },
                        themeMode = remember { mutableStateOf(themeMode) }.apply { value = themeMode },
                        appTheme = remember { mutableStateOf(appTheme) }.apply { value = appTheme },
                        useUdpSync = remember { mutableStateOf(useUdpSync) }.apply { value = useUdpSync },
                        fakeHttpOption = remember { mutableStateOf(fakeHttpOption) }.apply { value = fakeHttpOption },
                        customFakeHttpHost = remember { mutableStateOf(customFakeHttpHost) }.apply { value = customFakeHttpHost },
                        onUseDynamicColorChange = {
                            useDynamicColor = it
                            prefs.edit().putBoolean("use_dynamic_color", it).apply()
                        },
                        onThemeModeChange = {
                            themeMode = it
                            prefs.edit().putString("theme_mode", it.name).apply()
                        },
                        onAppThemeChange = {
                            appTheme = it
                            prefs.edit().putString("app_theme", it.name).apply()
                        },
                        onUseUdpSyncChange = {
                            useUdpSync = it
                            prefs.edit().putBoolean("use_udp_sync", it).apply()
                        },
                        onFakeHttpOptionChange = {
                            fakeHttpOption = it
                            prefs.edit().putString("fake_http_option", it).apply()
                        },
                        onCustomFakeHttpHostChange = {
                            customFakeHttpHost = it
                            prefs.edit().putString("custom_fake_http_host", it).apply()
                        },
                        isDark = isDark
                    )
                }
            }
        }
    }

    @Composable
    private fun animateColorScheme(target: ColorScheme): ColorScheme {
        return ColorScheme(
            primary = animateColorAsState(target.primary, animationSpec = tween(500)).value,
            onPrimary = animateColorAsState(target.onPrimary, animationSpec = tween(500)).value,
            primaryContainer = animateColorAsState(target.primaryContainer, animationSpec = tween(500)).value,
            onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, animationSpec = tween(500)).value,
            inversePrimary = animateColorAsState(target.inversePrimary, animationSpec = tween(500)).value,
            secondary = animateColorAsState(target.secondary, animationSpec = tween(500)).value,
            onSecondary = animateColorAsState(target.onSecondary, animationSpec = tween(500)).value,
            secondaryContainer = animateColorAsState(target.secondaryContainer, animationSpec = tween(500)).value,
            onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, animationSpec = tween(500)).value,
            tertiary = animateColorAsState(target.tertiary, animationSpec = tween(500)).value,
            onTertiary = animateColorAsState(target.onTertiary, animationSpec = tween(500)).value,
            tertiaryContainer = animateColorAsState(target.tertiaryContainer, animationSpec = tween(500)).value,
            onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, animationSpec = tween(500)).value,
            background = animateColorAsState(target.background, animationSpec = tween(500)).value,
            onBackground = animateColorAsState(target.onBackground, animationSpec = tween(500)).value,
            surface = animateColorAsState(target.surface, animationSpec = tween(500)).value,
            onSurface = animateColorAsState(target.onSurface, animationSpec = tween(500)).value,
            surfaceVariant = animateColorAsState(target.surfaceVariant, animationSpec = tween(500)).value,
            onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, animationSpec = tween(500)).value,
            surfaceTint = animateColorAsState(target.surfaceTint, animationSpec = tween(500)).value,
            inverseSurface = animateColorAsState(target.inverseSurface, animationSpec = tween(500)).value,
            inverseOnSurface = animateColorAsState(target.inverseOnSurface, animationSpec = tween(500)).value,
            error = animateColorAsState(target.error, animationSpec = tween(500)).value,
            onError = animateColorAsState(target.onError, animationSpec = tween(500)).value,
            errorContainer = animateColorAsState(target.errorContainer, animationSpec = tween(500)).value,
            onErrorContainer = animateColorAsState(target.onErrorContainer, animationSpec = tween(500)).value,
            outline = animateColorAsState(target.outline, animationSpec = tween(500)).value,
            outlineVariant = animateColorAsState(target.outlineVariant, animationSpec = tween(500)).value,
            scrim = animateColorAsState(target.scrim, animationSpec = tween(500)).value,
        )
    }

    private fun getLightPresetScheme(theme: AppTheme): ColorScheme {
        return when (theme) {
            AppTheme.LAVENDER -> lightColorScheme(primary = Color(0xFF8F5F6F), primaryContainer = Color(0xFFECE2E5), background = Color(0xFFF7F2F4), surface = Color.White)
            AppTheme.SAGE -> lightColorScheme(primary = Color(0xFF5F8F6F), primaryContainer = Color(0xFFE2ECE9), background = Color(0xFFF2F7F4), surface = Color.White)
            AppTheme.SLATE -> lightColorScheme(primary = Color(0xFF4F6F8F), primaryContainer = Color(0xFFD9E2EC), background = Color(0xFFF0F4F8), surface = Color.White)
            AppTheme.OAT -> lightColorScheme(primary = Color(0xFF8F8A5F), primaryContainer = Color(0xFFECEBE2), background = Color(0xFFF7F7F2), surface = Color.White)
        }
    }

    private fun getDarkPresetScheme(theme: AppTheme): ColorScheme {
        return when (theme) {
            AppTheme.LAVENDER -> darkColorScheme(primary = Color(0xFFF9D1DC), primaryContainer = Color(0xFF4F2A38), background = Color(0xFF1C1B1D), surface = Color(0xFF242424))
            AppTheme.SAGE -> darkColorScheme(primary = Color(0xFFD1F9D4), primaryContainer = Color(0xFF2A4F32), background = Color(0xFF1C1B1D), surface = Color(0xFF242424))
            AppTheme.SLATE -> darkColorScheme(primary = Color(0xFFD1E8F9), primaryContainer = Color(0xFF2A3F4F), background = Color(0xFF1C1B1D), surface = Color(0xFF242424))
            AppTheme.OAT -> darkColorScheme(primary = Color(0xFFF9F7D1), primaryContainer = Color(0xFF4F4B2A), background = Color(0xFF1C1B1D), surface = Color(0xFF242424))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EveryShareScreen(
        useDynamicColor: MutableState<Boolean>,
        themeMode: MutableState<ThemeMode>,
        appTheme: MutableState<AppTheme>,
        useUdpSync: MutableState<Boolean>,
        fakeHttpOption: MutableState<String>,
        customFakeHttpHost: MutableState<String>,
        onUseDynamicColorChange: (Boolean) -> Unit,
        onThemeModeChange: (ThemeMode) -> Unit,
        onAppThemeChange: (AppTheme) -> Unit,
        onUseUdpSyncChange: (Boolean) -> Unit,
        onFakeHttpOptionChange: (String) -> Unit,
        onCustomFakeHttpHostChange: (String) -> Unit,
        isDark: Boolean
    ) {
        val context = LocalContext.current
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        // 状态管理 (使用 rememberSaveable 确保旋转不丢失)
        val currentTab = rememberSaveable { mutableStateOf(ActiveTab.TRANSFER) }
        val sessionState = rememberSaveable { mutableStateOf(SessionState.IDLE) }
        val isSenderRole = rememberSaveable { mutableStateOf(true) }

        val myCode = remember { mutableStateOf("正在定位公网 IP...") }
        val myIpv6Text = remember { mutableStateOf("连接中...") }
        val myPort = rememberSaveable { mutableStateOf(50002) }
        val remoteCode = rememberSaveable { mutableStateOf("") }

        val statusText = remember { mutableStateOf("等待指令") }
        val progressPercent = remember { mutableStateOf(0) }
        val currentSpeed = remember { mutableStateOf(0.0) }
        val isTransferring = remember { mutableStateOf(false) }

        val showQrCode = remember { mutableStateOf(false) }
        val showLogs = remember { mutableStateOf(false) }
        val logList = remember { mutableStateListOf<String>() }
        val logListState = rememberLazyListState()

        val activeSocket = remember { mutableStateOf<Socket?>(null) }
        val activeTransferThread = remember { mutableStateOf<Thread?>(null) }
        var resetIpJob by remember { mutableStateOf<Job?>(null) }

        // 文件选择相关状态
        val selectedFileUri = remember { mutableStateOf<Uri?>(null) }
        val selectedFileName = remember { mutableStateOf("") }
        val selectedFileSize = remember { mutableStateOf(0L) }
        val isCachingFile = remember { mutableStateOf(false) }

        val hasIpv6 = myIpv6Text.value != "连接中..." && myIpv6Text.value != "未连接"
        val cardBg = if (isDark) Color(0xFF242424) else Color.White
        val cardTextColor = if (isDark) Color.White else Color.Black
        val activeCardBg = if (isDark) Color(0xFF203726) else Color(0xFFE3F9E4)
        val activeCheckColor = if (isDark) Color(0xFF6CCC73) else Color(0xFF6ACD73)

        // 扫码器
        val scanLauncher = rememberLauncherForActivityResult(
            contract = ScanContract()
        ) { result ->
            if (result.contents != null) {
                remoteCode.value = result.contents
                statusText.value = "已扫描输入连接码"
            }
        }

        // 💡 重置连接码 (加上协程 delay，防止冷启动和界面返回时抢占 CPU 造成 UI 卡顿)
        fun resetMyConnectionCode() {
            resetIpJob?.cancel()
            myIpv6Text.value = "连接中..."
            myCode.value = "正在定位公网 IP..."

            resetIpJob = scope.launch(Dispatchers.IO) {
                delay(400)
                try {
                    val myIpv6 = withTimeoutOrNull(4000) {
                        TcpPunchTransfer.getActivePublicIpv6()
                    }

                    if (myIpv6 != null) {
                        val randomPort = 50000 + (Math.random() * 10000).toInt()
                        myPort.value = randomPort
                        val generatedCode = ConnectionCodeUtil.generateCode(myIpv6.hostAddress, randomPort)

                        val localIps = TcpPunchTransfer.getLocalIPv6List()
                        val isNat6 = !localIps.contains(myIpv6.hostAddress.lowercase())

                        withContext(Dispatchers.Main) {
                            myCode.value = generatedCode
                            myIpv6Text.value = if (isNat6) "${myIpv6.hostAddress}\n<NAT6 转换>" else myIpv6.hostAddress
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            myIpv6Text.value = "未连接"
                            myCode.value = "定位失败"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        myIpv6Text.value = "未连接"
                        myCode.value = "定位失败"
                    }
                }
            }
        }

        // 退出连接并中止后台线程 (💡 增加 500ms 延迟重置 IP，让前台返回过渡动画完全放完后再检测网络，防止卡顿)
        fun cancelActiveTransfer() {
            thread {
                try {
                    activeTransferThread.value?.interrupt()
                    activeTransferThread.value = null
                    activeSocket.value?.close()
                    activeSocket.value = null
                } catch (ignored: Exception) {}

                this@MainActivity.runOnUiThread {
                    isTransferring.value = false
                    sessionState.value = SessionState.IDLE
                    statusText.value = "已取消连接并返回"
                }

                try { Thread.sleep(500) } catch (ignored: Exception) {}
                resetMyConnectionCode()
            }
        }

        // 自动网络环境监听
        DisposableEffect(Unit) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    resetMyConnectionCode()
                }
                override fun onLost(network: Network) {
                    resetMyConnectionCode()
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            onDispose {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            }
        }

        // 拦截系统返回键
        BackHandler(enabled = sessionState.value != SessionState.IDLE) {
            cancelActiveTransfer()
        }

        // 文件选择器
        val filePickerLauncher = rememberLauncherForActivityResult<String, Uri?>(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                selectedFileUri.value = uri
                val (name, size) = getFileMetadata(context, uri)
                selectedFileName.value = name
                selectedFileSize.value = size

                isCachingFile.value = true
                statusText.value = "正在后台缓存文件，请稍候..."
                thread {
                    try {
                        val tempFile = File(context.cacheDir, "temp_upload.dat")
                        context.contentResolver.openInputStream(uri).use { input ->
                            if (input != null) {
                                java.nio.file.Files.copy(input, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                        this@MainActivity.runOnUiThread {
                            statusText.value = "文件就绪: $name"
                            isCachingFile.value = false
                        }
                    } catch (e: Exception) {
                        AppLogger.error("缓存文件失败", e)
                        this@MainActivity.runOnUiThread {
                            statusText.value = "缓存失败"
                            isCachingFile.value = false
                            selectedFileUri.value = null
                        }
                    }
                }
            }
        }

        // 初始化
        LaunchedEffect(Unit) {
            System.setProperty("java.net.preferIPv6Addresses", "true")
            System.setProperty("java.net.preferIPv4Stack", "false")

            // 💡 启动时立即同步已经积攒的全局历史日志，统一前台后台控制台
            logList.clear()
            logList.addAll(AppLogger.logs)

            AppLogger.onLogAdded = Runnable {
                this@MainActivity.runOnUiThread {
                    logList.clear()
                    logList.addAll(AppLogger.logs)
                    scope.launch {
                        if (logList.isNotEmpty()) {
                            logListState.animateScrollToItem(logList.size - 1)
                        }
                    }
                }
            }
            resetMyConnectionCode()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp)
                    .padding(top = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 页面级左右滑动过渡动画
                AnimatedContent(
                    targetState = currentTab.value,
                    transitionSpec = {
                        if (targetState == ActiveTab.SETTINGS) {
                            slideInHorizontally(animationSpec = tween(350)) { width -> width } + fadeIn() togetherWith
                                    slideOutHorizontally(animationSpec = tween(350)) { width -> -width } + fadeOut()
                        } else {
                            slideInHorizontally(animationSpec = tween(350)) { width -> -width } + fadeIn() togetherWith
                                    slideOutHorizontally(animationSpec = tween(350)) { width -> width } + fadeOut()
                        }
                    }
                ) { targetTab ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (targetTab == ActiveTab.TRANSFER) {
                            // ==========================================
                            //               「传输」主界面
                            // ==========================================
                            AnimatedContent(
                                targetState = sessionState.value,
                                transitionSpec = {
                                    if (targetState == SessionState.CONNECTING || targetState == SessionState.ACTIVE) {
                                        slideInHorizontally(animationSpec = tween(350)) { width -> width } + fadeIn() togetherWith
                                                slideOutHorizontally(animationSpec = tween(350)) { width -> -width } + fadeOut()
                                    } else {
                                        slideInHorizontally(animationSpec = tween(350)) { width -> -width } + fadeIn() togetherWith
                                                slideOutHorizontally(animationSpec = tween(350)) { width -> width } + fadeOut()
                                    }
                                }
                            ) { state ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (state == SessionState.IDLE) {
                                        // 标题左对齐
                                        Text(
                                            text = "EveryShare",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                            textAlign = TextAlign.Start
                                        )

                                        // 说明文案卡片
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = cardBg),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                        ) {
                                            Text(
                                                text = "仅支持公网 IPv6 地址互传，当前版本为 AI 测试版。\n有 BUG 可以提 Issue，但是不一定能改好。",
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp,
                                                color = if (isDark) Color.LightGray else Color.Gray,
                                                modifier = Modifier.padding(14.dp),
                                                textAlign = TextAlign.Start
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        // Bento Box 仪表盘 (1:1 绝对对称)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(180.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            val isConnecting = myIpv6Text.value == "连接中..."
                                            val hasIpv6Status = !isConnecting && myIpv6Text.value != "未连接"

                                            // 左侧大卡片 (3D 物理下沉变暗 ＋ 倾斜动效)
                                            Card(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .bounceClick { resetMyConnectionCode() },
                                                shape = RoundedCornerShape(24.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (hasIpv6Status) activeCardBg else if (isConnecting) (if (isDark) Color(0xFF4F3A1A) else Color(0xFFFFF3E0)) else (if (isDark) Color(0xFF4F1A1E) else Color(0xFFFFEBEE))
                                                )
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    // 描边大圆圈
                                                    if (hasIpv6Status) {
                                                        Canvas(
                                                            modifier = Modifier
                                                                .size(140.dp)
                                                                .align(Alignment.BottomEnd)
                                                                .offset(x = 35.dp, y = 35.dp)
                                                        ) {
                                                            val strokeWidth = 14.dp.toPx()
                                                            val circleColor = activeCheckColor

                                                            drawCircle(
                                                                color = circleColor,
                                                                radius = size.minDimension / 2 - strokeWidth,
                                                                style = Stroke(width = strokeWidth)
                                                            )
                                                            val path = Path().apply {
                                                                moveTo(size.width * 0.38f, size.height * 0.52f)
                                                                lineTo(size.width * 0.48f, size.height * 0.64f)
                                                                lineTo(size.width * 0.68f, size.height * 0.40f)
                                                            }
                                                            drawPath(
                                                                path = path,
                                                                color = circleColor,
                                                                style = Stroke(
                                                                    width = strokeWidth,
                                                                    cap = StrokeCap.Round,
                                                                    join = StrokeJoin.Round
                                                                )
                                                            )
                                                        }
                                                    }

                                                    Column(modifier = Modifier.padding(16.dp)) {
                                                        Text(
                                                            text = if (hasIpv6Status) "服务中" else if (isConnecting) "连接中..." else "未连接",
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontSize = 20.sp,
                                                            color = cardTextColor
                                                        )
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        Text(
                                                            text = myIpv6Text.value,
                                                            fontSize = 9.sp,
                                                            lineHeight = 13.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = if (isDark) Color.LightGray else Color.DarkGray
                                                        )
                                                    }
                                                }
                                            }

                                            // 右侧两个小卡片
                                            Column(
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .bounceClick { resetMyConnectionCode() },
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = CardDefaults.cardColors(containerColor = cardBg),
                                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(14.dp).fillMaxHeight(),
                                                        verticalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        // 💡 视觉统一：修改为“传输策略”
                                                        Text(text = "传输策略", fontSize = 13.sp, color = Color.Gray)
                                                        Text(text = myPort.value.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cardTextColor)
                                                    }
                                                }
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .bounceClick { resetMyConnectionCode() },
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = CardDefaults.cardColors(containerColor = cardBg),
                                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(14.dp).fillMaxHeight(),
                                                        verticalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(text = "传输策略", fontSize = 13.sp, color = Color.Gray)
                                                        Text(
                                                            // 💡 视觉统一：修改“纯TCP”为“TCP”
                                                            text = if (useUdpSync.value) "UDP + TCP" else "TCP",
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = cardTextColor
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // 发送/接收选择卡片
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .graphicsLayer {
                                                        alpha = if (hasIpv6) 1.0f else 0.4f
                                                    }
                                                    .bounceClick {
                                                        if (hasIpv6) {
                                                            isSenderRole.value = true
                                                            sessionState.value = SessionState.CONNECTING
                                                            statusText.value = "请在下方进行连接配对"
                                                        } else {
                                                            // 弹出阻断提醒
                                                            android.widget.Toast.makeText(context, "请等待公网 IP 定位成功再试", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                shape = RoundedCornerShape(20.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (hasIpv6) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                )
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(28.dp))
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(text = "发送文件", fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            Card(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .graphicsLayer {
                                                        alpha = if (hasIpv6) 1.0f else 0.4f
                                                    }
                                                    .bounceClick {
                                                        if (hasIpv6) {
                                                            isSenderRole.value = false
                                                            sessionState.value = SessionState.CONNECTING
                                                            statusText.value = "请在下方进行连接配对"
                                                        } else {
                                                            android.widget.Toast.makeText(context, "请等待公网 IP 定位成功再试", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                shape = RoundedCornerShape(20.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (hasIpv6) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                )
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(28.dp))
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(text = "接收文件", fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }

                                    } else if (state == SessionState.CONNECTING) {
                                        // ==========================================
                                        //               「连接配对」界面
                                        // ==========================================
                                        Text(
                                            text = if (isSenderRole.value) "发送" else "接收",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                            textAlign = TextAlign.Start
                                        )

                                        // 我的本端互传码卡片
                                        Card(
                                            modifier = Modifier.fillMaxWidth().bounceClick {
                                                if (myCode.value.startsWith("everyshare://")) {
                                                    scope.launch {
                                                        val clipData = ClipData.newPlainText("EveryShare", myCode.value)
                                                        clipboard.setClipEntry(clipData.toClipEntry())
                                                        android.widget.Toast.makeText(context, "连接码已复制到剪贴板！", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = cardBg),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(text = "📱 我的本端互传码 (点击自动复制):", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(text = myCode.value, fontSize = 14.sp, color = cardTextColor)
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = "提供二维码供对方扫码", fontSize = 13.sp, color = Color.Gray)
                                            Button(
                                                onClick = { showQrCode.value = !showQrCode.value },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                            ) {
                                                Text(text = if (showQrCode.value) "隐藏二维码" else "显示二维码", fontSize = 11.sp)
                                            }
                                        }

                                        AnimatedVisibility(visible = showQrCode.value && myCode.value.startsWith("everyshare://")) {
                                            val qrBitmap = remember(myCode.value) { generateQrCode(myCode.value, 400) }
                                            qrBitmap?.let {
                                                Image(
                                                    bitmap = it.asImageBitmap(),
                                                    contentDescription = "QR Code",
                                                    modifier = Modifier.size(150.dp)
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = remoteCode.value,
                                                onValueChange = { remoteCode.value = it },
                                                label = { Text("请输入或扫描对方的连接码") },
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    val options = ScanOptions().apply {
                                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                                        setPrompt("将二维码放入框内即可自动扫描")
                                                        setBeepEnabled(true)
                                                        setBarcodeImageEnabled(true)
                                                        setOrientationLocked(true) // 💡 锁定竖屏！
                                                    }
                                                    scanLauncher.launch(options)
                                                },
                                                modifier = Modifier.height(56.dp)
                                            ) {
                                                Text("扫码")
                                            }
                                        }

                                        if (isSenderRole.value) {
                                            // 选择要发送的文件卡片
                                            Card(
                                                modifier = Modifier.fillMaxWidth().bounceClick {
                                                    if (!isCachingFile.value) filePickerLauncher.launch("*/*")
                                                },
                                                shape = RoundedCornerShape(16.dp),
                                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(text = "📂 选择要发送的文件 (点击浏览):", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (selectedFileName.value.isEmpty()) "点击选择手机上的任意文件" else "${selectedFileName.value} (${selectedFileSize.value / 1024 / 1024} MB)",
                                                        fontSize = 14.sp,
                                                        color = cardTextColor
                                                    )
                                                }
                                            }
                                        }

                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = useUdpSync.value, onCheckedChange = { useUdpSync.value = it })
                                            Text(text = "使用高精度 UDP 引导同步 (推荐)", fontSize = 13.sp)
                                        }

                                        Text(text = "当前状态: ${statusText.value}", fontSize = 14.sp)

                                        if (isTransferring.value) {
                                            LinearProgressIndicator(progress = { progressPercent.value / 100f }, modifier = Modifier.fillMaxWidth())
                                            Text(text = String.format("速度: %.2f MB/s | 进度: %d%%", currentSpeed.value, progressPercent.value), fontSize = 13.sp)
                                        }

                                        Button(
                                            onClick = {
                                                if (remoteCode.value.isBlank()) return@Button
                                                statusText.value = if (useUdpSync.value) "正在进行 UDP 时延校准..." else "正在进行 TCP 碰撞打洞..."
                                                isTransferring.value = true

                                                // 💡 保存当前的传输线程引用，用于支持用户随时中断
                                                val t = thread {
                                                    try {
                                                        val remoteInfo = ConnectionCodeUtil.parseCode(remoteCode.value)
                                                        val puncher = TcpPunchTransfer()

                                                        val listener = ProgressListener { name, read, total, speed ->
                                                            progressPercent.value = ((read.toDouble() / total) * 100).toInt()
                                                            currentSpeed.value = speed
                                                            statusText.value = if (isSenderRole.value) "正在发送: $name" else "正在接收: $name"
                                                        }

                                                        val socket = puncher.connectByPunch(remoteInfo.ip, remoteInfo.port, myPort.value, !isSenderRole.value, useUdpSync.value, "speedtest.cn")
                                                        if (socket != null) {
                                                            activeSocket.value = socket
                                                            sessionState.value = SessionState.ACTIVE
                                                            AppLogger.info("穿透成功！建立长生命周期会话。")

                                                            if (isSenderRole.value) {
                                                                val tempCacheFile = File(context.cacheDir, "temp_upload.dat")
                                                                FileInputStream(tempCacheFile).use { fileInputStream ->
                                                                    puncher.sendFile(socket, fileInputStream, selectedFileName.value, selectedFileSize.value, listener)
                                                                }
                                                                AppLogger.info("文件发送完成！")
                                                            } else {
                                                                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                                                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                                                )
                                                                val everyShareDir = File(downloadDir, "EveryShare")
                                                                if (!everyShareDir.exists()) everyShareDir.mkdirs()
                                                                val saveDir = if (everyShareDir.exists()) everyShareDir.absolutePath else downloadDir.absolutePath

                                                                puncher.receiveFile(socket, saveDir, listener)
                                                                AppLogger.info("🎉 接收成功！已存入公共下载目录")
                                                            }
                                                        } else {
                                                            AppLogger.info("❌ 穿透失败，请确认双方同时开启")
                                                            this@MainActivity.runOnUiThread {
                                                                isTransferring.value = false
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        AppLogger.error("会话执行失败", e)
                                                        this@MainActivity.runOnUiThread {
                                                            isTransferring.value = false
                                                        }
                                                    } finally {
                                                        activeTransferThread.value = null
                                                    }
                                                }
                                                activeTransferThread.value = t
                                            },
                                            modifier = Modifier.fillMaxWidth().height(50.dp),
                                            enabled = remoteCode.value.isNotBlank() && (!isSenderRole.value || (selectedFileUri.value != null && !isCachingFile.value))
                                        ) {
                                            Text(text = "开始连接并进入会话", fontSize = 15.sp)
                                        }

                                        TextButton(onClick = { cancelActiveTransfer() }) {
                                            Text("返回首页")
                                        }

                                    } else if (sessionState.value == SessionState.ACTIVE) {
                                        // ==========================================
                                        //               「会话传输中」界面
                                        // ==========================================
                                        Text(
                                            text = "传输会话",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                            textAlign = TextAlign.Start
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        if (isSenderRole.value) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().bounceClick {
                                                    if (!isCachingFile.value && !isTransferring.value) filePickerLauncher.launch("*/*")
                                                },
                                                colors = CardDefaults.cardColors(containerColor = cardBg)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(text = "📂 选择另一个要发送的文件 (点击浏览):", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (selectedFileName.value.isEmpty()) "点击浏览" else "${selectedFileName.value} (${selectedFileSize.value / 1024 / 1024} MB)",
                                                        fontSize = 14.sp,
                                                        color = cardTextColor
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    if (activeSocket.value == null || selectedFileUri.value == null || isCachingFile.value) return@Button
                                                    isTransferring.value = true
                                                    statusText.value = "正在通过已建立通道上传..."

                                                    val t = thread {
                                                        try {
                                                            val puncher = TcpPunchTransfer()
                                                            val listener = ProgressListener { name, read, total, speed ->
                                                                progressPercent.value = ((read.toDouble() / total) * 100).toInt()
                                                                currentSpeed.value = speed
                                                                statusText.value = "🚀 正在发送: $name"
                                                            }
                                                            val tempCacheFile = File(context.cacheDir, "temp_upload.dat")
                                                            FileInputStream(tempCacheFile).use { fileInputStream ->
                                                                puncher.sendFile(activeSocket.value!!, fileInputStream, selectedFileName.value, selectedFileSize.value, listener)
                                                            }
                                                            AppLogger.info("🎉 文件发送完成！通道继续保持。")
                                                        } catch (e: Exception) {
                                                            AppLogger.error("发送出错", e)
                                                        } finally {
                                                            this@MainActivity.runOnUiThread {
                                                                isTransferring.value = false
                                                                selectedFileUri.value = null
                                                                selectedFileName.value = ""
                                                            }
                                                            activeTransferThread.value = null
                                                        }
                                                    }
                                                    activeTransferThread.value = t
                                                },
                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                enabled = !isTransferring.value && !isCachingFile.value && selectedFileUri.value != null
                                            ) {
                                                Text("直接发送选中的文件")
                                            }
                                        }

                                        Text(text = "传输状态: ${statusText.value}", fontSize = 14.sp)

                                        if (isTransferring.value) {
                                            LinearProgressIndicator(progress = { progressPercent.value / 100f }, modifier = Modifier.fillMaxWidth())
                                            Text(text = String.format("速度: %.2f MB/s | 进度: %d%%", currentSpeed.value, progressPercent.value), fontSize = 13.sp)
                                        }

                                        Spacer(modifier = Modifier.weight(1f))

                                        Button(
                                            onClick = { cancelActiveTransfer() },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("断开连接并结束会话")
                                        }
                                    }
                                }
                            }
                        } else {
                            // ==========================================
                            //               「设置」界面
                            // ==========================================
                            Text(
                                text = "Settings",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                textAlign = TextAlign.Start
                            )

                            // 设置说明卡片 (去掉 border，更新为硬核点对点科普说明)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(text = "初次使用？", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "依赖公网 IPv6 实现点对点传输，通过 TCP Simultaneous Open 打开防火墙，FAKE HTTP HEADER 绕过上行限速。\n仅在本地测试有效。还需其他地区，其他运营商，不同环境下测试。",
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        color = if (isDark) Color.LightGray else Color.Gray
                                    )
                                }
                            }

                            // 声明折叠状态：默认收起（false）
                            var showAppearanceDetail by remember { mutableStateOf(false) }

                            // 外观卡片 (Settings > Appearance) (已去掉 border)
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showAppearanceDetail = !showAppearanceDetail },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "外观", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cardTextColor)
                                        }
                                        Text(text = if (showAppearanceDetail) "收起" else "展开", fontSize = 11.sp, color = Color.Gray)
                                    }

                                    // 💡 彻底修复割裂感：移除父级 spacedBy，所有分割线和间距完全收纳进 AnimatedVisibility
                                    AnimatedVisibility(
                                        visible = showAppearanceDetail,
                                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))

                                            // 1. 动态颜色开关 (Android 12+)
                                            val isAtLeastS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = "动态颜色", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)
                                                    Text(
                                                        text = if (isAtLeastS) "跟随系统壁纸取色 (Android 12+)" else "仅支持 Android 12+ 设备",
                                                        fontSize = 11.sp,
                                                        color = Color.Gray
                                                    )
                                                }
                                                Switch(
                                                    checked = useDynamicColor.value && isAtLeastS,
                                                    onCheckedChange = { onUseDynamicColorChange(it) },
                                                    enabled = isAtLeastS
                                                )
                                            }

                                            // 2. 预设主题色切换
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Text(text = "配色主题", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)
                                                Text(
                                                    text = if (useDynamicColor.value && isAtLeastS) "关闭动态颜色后生效" else "挑选你喜欢的个性化色调",
                                                    fontSize = 11.sp,
                                                    color = Color.Gray
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceAround,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val isSelectionEnabled = !(useDynamicColor.value && isAtLeastS)

                                                    ThemeIcon(
                                                        points = 12,
                                                        innerRatio = 0.84f,
                                                        isSelected = appTheme.value == AppTheme.LAVENDER && isSelectionEnabled,
                                                        color = Color(0xFF8F5F6F),
                                                        enabled = isSelectionEnabled,
                                                        onClick = { onAppThemeChange(AppTheme.LAVENDER) }
                                                    )

                                                    ThemeIcon(
                                                        points = 4,
                                                        innerRatio = 0.65f,
                                                        isSelected = appTheme.value == AppTheme.SAGE && isSelectionEnabled,
                                                        color = Color(0xFF5F8F6F),
                                                        enabled = isSelectionEnabled,
                                                        onClick = { onAppThemeChange(AppTheme.SAGE) }
                                                    )

                                                    ThemeIcon(
                                                        points = 8,
                                                        innerRatio = 0.74f,
                                                        isSelected = appTheme.value == AppTheme.SLATE && isSelectionEnabled,
                                                        color = Color(0xFF4F6F8F),
                                                        enabled = isSelectionEnabled,
                                                        onClick = { onAppThemeChange(AppTheme.SLATE) }
                                                    )

                                                    ThemeIcon(
                                                        points = 10,
                                                        innerRatio = 0.88f,
                                                        isSelected = appTheme.value == AppTheme.OAT && isSelectionEnabled,
                                                        color = Color(0xFF8F8A5F),
                                                        enabled = isSelectionEnabled,
                                                        onClick = { onAppThemeChange(AppTheme.OAT) }
                                                    )
                                                }
                                            }

                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))

                                            // 3. 深色模式三档切换：采用高精度滑动动画滑块设计 [2]
                                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(text = "深色模式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)

                                                BoxWithConstraints(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(44.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(if (isDark) Color(0xFF1E1E1E) else Color.LightGray.copy(alpha = 0.2f))
                                                        .padding(2.dp)
                                                ) {
                                                    val innerWidth = maxWidth - 4.dp
                                                    val pillWidth = innerWidth / 3
                                                    val selectedIndex = when (themeMode.value) {
                                                        ThemeMode.SYSTEM -> 0
                                                        ThemeMode.LIGHT -> 1
                                                        ThemeMode.DARK -> 2
                                                    }
                                                    val indicatorOffset by animateDpAsState(targetValue = pillWidth * selectedIndex)

                                                    Box(
                                                        modifier = Modifier
                                                            .offset(x = indicatorOffset)
                                                            .width(pillWidth)
                                                            .fillMaxHeight()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )

                                                    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                                        ThemeModePill(
                                                            text = "跟随系统",
                                                            isSelected = themeMode.value == ThemeMode.SYSTEM,
                                                            onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        ThemeModePill(
                                                            text = "浅色",
                                                            isSelected = themeMode.value == ThemeMode.LIGHT,
                                                            onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        ThemeModePill(
                                                            text = "深色",
                                                            isSelected = themeMode.value == ThemeMode.DARK,
                                                            onClick = { onThemeModeChange(ThemeMode.DARK) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 💡 传输策略设置卡片 (M3 标准下拉源选择器风格) (已去掉所有边框与 spacedBy 割裂感) [2]
                            var showStrategyDetail by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showStrategyDetail = !showStrategyDetail },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardColors(containerColor = cardBg, contentColor = cardTextColor, disabledContainerColor = cardBg, disabledContentColor = cardTextColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "传输策略", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cardTextColor)
                                        }
                                        Text(text = if (showStrategyDetail) "收起" else "展开", fontSize = 11.sp, color = Color.Gray)
                                    }

                                    AnimatedVisibility(
                                        visible = showStrategyDetail,
                                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))

                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(text = "设置传输策略，默认为 UDP + TCP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)
                                                Text(text = "UDP + TCP：先打通 UDP 防火墙，两端通过 UDP 校准时间，同时向对方发 TCP 包", fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
                                                Text(text = "TCP：尝试直接向对方发 TCP 包打通两端 TCP 防火墙", fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
                                            }

                                            // 💡 彻底修复：点击输入框整行任意位置自动触发下拉，而不需要点右侧小箭头 [2]
                                            var dropdownExpanded by remember { mutableStateOf(false) }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { dropdownExpanded = true } // 💡 整行点击！
                                            ) {
                                                OutlinedTextField(
                                                    value = if (useUdpSync.value) "UDP + TCP (推荐)" else "TCP",
                                                    onValueChange = {},
                                                    readOnly = true,
                                                    enabled = false, // 💡 关闭本身交互，把点击权完全让渡给外层 Box
                                                    label = { Text("传输策略") },
                                                    trailingIcon = {
                                                        Icon(
                                                            imageVector = if (dropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                            contentDescription = null,
                                                            tint = cardTextColor
                                                        )
                                                    },
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        disabledTextColor = cardTextColor,
                                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                                                        disabledContainerColor = Color.Transparent
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                DropdownMenu(
                                                    expanded = dropdownExpanded,
                                                    onDismissRequest = { dropdownExpanded = false },
                                                    modifier = Modifier.fillMaxWidth(0.9f).background(cardBg)
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("UDP + TCP (推荐)", color = cardTextColor) },
                                                        onClick = {
                                                            onUseUdpSyncChange(true)
                                                            dropdownExpanded = false
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("TCP", color = cardTextColor) },
                                                        onClick = {
                                                            onUseUdpSyncChange(false)
                                                            dropdownExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 💡 FAKE HTTP HEADER 混淆设置卡片 [2.1.2]
                            var showFakeHttpDetail by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showFakeHttpDetail = !showFakeHttpDetail },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardColors(containerColor = cardBg, contentColor = cardTextColor, disabledContainerColor = cardBg, disabledContentColor = cardTextColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "FAKE HTTP HEADER", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cardTextColor)
                                        }
                                        Text(text = if (showFakeHttpDetail) "收起" else "展开", fontSize = 11.sp, color = Color.Gray)
                                    }

                                    AnimatedVisibility(
                                        visible = showFakeHttpDetail,
                                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))

                                            Text(text = "用于解开运营商限速的域名，默认为 speedtest.cn", fontSize = 11.sp, color = Color.Gray)

                                            // 💡 彻底修复：点击输入框整行任意位置自动触发下拉 [2]
                                            var hHeaderExpanded by remember { mutableStateOf(false) }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { hHeaderExpanded = true } // 💡 整行点击！
                                            ) {
                                                OutlinedTextField(
                                                    value = fakeHttpOption.value,
                                                    onValueChange = {},
                                                    readOnly = true,
                                                    enabled = false, // 💡 关闭本身交互，把点击权完全让渡给外层 Box
                                                    label = { Text("DPI 伪装域名") },
                                                    trailingIcon = {
                                                        Icon(
                                                            imageVector = if (hHeaderExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                            contentDescription = null,
                                                            tint = cardTextColor
                                                        )
                                                    },
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        disabledTextColor = cardTextColor,
                                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                                                        disabledContainerColor = Color.Transparent
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                DropdownMenu(
                                                    expanded = hHeaderExpanded,
                                                    onDismissRequest = { hHeaderExpanded = false },
                                                    modifier = Modifier.fillMaxWidth(0.9f).background(cardBg)
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("speedtest.cn", color = cardTextColor) },
                                                        onClick = {
                                                            onFakeHttpOptionChange("speedtest.cn")
                                                            hHeaderExpanded = false
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("自定义", color = cardTextColor) },
                                                        onClick = {
                                                            onFakeHttpOptionChange("自定义")
                                                            hHeaderExpanded = false
                                                        }
                                                    )
                                                }
                                            }

                                            // 💡 只有当用户选择“自定义”时，才优雅渐显出自定义输入框
                                            AnimatedVisibility(
                                                visible = fakeHttpOption.value == "自定义",
                                                enter = expandVertically(animationSpec = tween(250)) + fadeIn(),
                                                exit = shrinkVertically(animationSpec = tween(250)) + fadeOut()
                                            ) {
                                                OutlinedTextField(
                                                    value = customFakeHttpHost.value,
                                                    onValueChange = { onCustomFakeHttpHostChange(it) },
                                                    label = { Text("自定义混淆 Host (例: update.microsoft.com)") },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            var showAboutDetail by remember { mutableStateOf(false) }
                            // 关于 EveryShare 卡片：完全按你的最新需求与截图重构 (已去掉所有边框与 spacedBy 割裂感)
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showAboutDetail = !showAboutDetail },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "关于 EveryShare", fontWeight = FontWeight.Bold, color = cardTextColor)
                                        }
                                        Text(text = if (showAboutDetail) "收起" else "展开", fontSize = 11.sp, color = Color.Gray)
                                    }

                                    // 打开关闭加入优雅平滑伸缩折叠动画，拒绝生硬卡顿！
                                    AnimatedVisibility(
                                        visible = showAboutDetail,
                                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))
                                            Spacer(modifier = Modifier.height(14.dp))

                                            // 100% 还原关于盒子布局
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .background(if (isDark) Color(0xFF1E1E1E) else Color.LightGray.copy(alpha = 0.15f))
                                                    .padding(16.dp)
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                    Text(text = "关于", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = cardTextColor)

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            // 💡 硬件级物理裁剪的圆形本地头像（自适应读取 app/src/main/res/drawable/avatar.png） [1]
                                                            Image(
                                                                painter = painterResource(id = R.drawable.avatar),
                                                                contentDescription = "Avatar",
                                                                modifier = Modifier
                                                                    .size(48.dp)
                                                                    .clip(CircleShape)
                                                            )

                                                            Spacer(modifier = Modifier.width(12.dp))

                                                            Column {
                                                                Text(text = "BlackTea", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                                                Text(text = "开发者", fontSize = 12.sp, color = Color.Gray)
                                                            }
                                                        }

                                                        // 右侧外链分享图标 [1]
                                                        IconButton(onClick = {
                                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BlackTeaOff/EveryShare-Android"))
                                                            context.startActivity(intent)
                                                        }) {
                                                            Icon(
                                                                imageVector = Icons.Default.Share,
                                                                contentDescription = "GitHub Repo",
                                                                tint = cardTextColor
                                                            )
                                                        }
                                                    }

                                                    // 💡 底部署名与版本 (版本对齐为 v0.1.0-alpha，作者变更为 Powered By Gemini) [3]
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(text = "版本：v0.1.0-alpha", fontSize = 12.sp, color = cardTextColor)
                                                        Text(text = "Powered By Gemini", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "运行日志控制台", fontSize = 13.sp, color = Color.Gray)
                    TextButton(onClick = { showLogs.value = !showLogs.value }) {
                        Text(text = if (showLogs.value) "隐藏日志" else "展开日志", fontSize = 12.sp)
                    }
                }

                AnimatedVisibility(visible = showLogs.value) {
                    // 运行日志控制台也深度适配深浅色模式：深色模式自动变为护眼深黑控制台 #151515
                    val consoleBg = if (isDark) Color(0xFF151515) else Color(0xFFF9F9F9)
                    val consoleBorder = if (isDark) Color.DarkGray.copy(alpha = 0.4f) else Color.LightGray.copy(alpha = 0.4f)
                    val consoleTextColor = if (isDark) Color.LightGray else Color.Black

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(135.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(consoleBg)
                            .border(1.dp, consoleBorder, RoundedCornerShape(14.dp))
                            .padding(8.dp)
                    ) {
                        SelectionContainer {
                            LazyColumn(
                                state = logListState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(logList) { logLine ->
                                    Text(
                                        text = logLine,
                                        color = if (logLine.contains("[ERROR]")) Color.Red else consoleTextColor,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 悬浮药丸底部导航栏
            val selectedIndex = if (currentTab.value == ActiveTab.TRANSFER) 0 else 1
            // 数学对称性优化：180.dp 大药丸除去左右 4.dp padding 剩下 172.dp 内部区域
            // 每个 Tab 点击区和背景滑动滑块宽度精确设置为：172 / 2 = 86.dp
            // 左边时偏移 0.dp，右边时精准偏移 86.dp，彻底根治滑块紧贴右边缘的问题 [2]
            val indicatorOffset by animateDpAsState(targetValue = if (selectedIndex == 0) 0.dp else 86.dp)

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .width(180.dp)
                    .height(52.dp)
                    .clip(CircleShape)
                    // 悬浮药丸背景自适应：深色背景为 #242424，浅色为 standard 磨砂玻璃色
                    .background(if (isDark) Color(0xFF242424).copy(alpha = 0.95f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                    .padding(4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(86.dp) // 84.dp 改为 86.dp 精确对接
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isTabTrans = currentTab.value == ActiveTab.TRANSFER
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f) // width(80.dp) 改为 weight(1f) 保证宽度为精确的 86.dp
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { currentTab.value = ActiveTab.TRANSFER },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "传输",
                            color = if (isTabTrans) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    val isTabSettings = currentTab.value == ActiveTab.SETTINGS
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f) // width(80.dp) 改为 weight(1f) 保证宽度为精确的 86.dp
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { currentTab.value = ActiveTab.SETTINGS },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "设置",
                            color = if (isTabSettings) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    private fun getFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_file"
        var size = 0L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) name = it.getString(nameIndex)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) size = it.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }

    private fun generateQrCode(text: String, size: Int): Bitmap? {
        return try {
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

// 💡 异形主题配色图标手绘 Canvas 组件 (支持无极缩放与原生 M3 变色)
@Composable
fun ThemeIcon(
    points: Int,
    innerRatio: Float,
    isSelected: Boolean,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .graphicsLayer { alpha = if (enabled) 1.0f else 0.4f }
            .bounceClick { if (enabled) onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(40.dp)) {
            val path = Path()
            val center = Offset(size.width / 2, size.height / 2)
            val outerRadius = size.minDimension / 2
            val innerRadius = outerRadius * innerRatio
            val angleStep = Math.PI / points

            for (i in 0 until 2 * points) {
                val r = if (i % 2 == 0) outerRadius else innerRadius
                val angle = i * angleStep
                val x = (center.x + r * Math.cos(angle)).toFloat()
                val y = (center.y + r * Math.sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path = path, color = color)

            // 如果被选中，在中心绘制一个精致的白色对勾
            if (isSelected) {
                val strokeWidth = 3.dp.toPx()
                val checkPath = Path().apply {
                    moveTo(size.width * 0.35f, size.height * 0.5f)
                    lineTo(size.width * 0.47f, size.height * 0.62f)
                    lineTo(size.width * 0.68f, size.height * 0.38f)
                }
                drawPath(
                    path = checkPath,
                    color = Color.White,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

// 💡 扁平分栏滑动小药丸组件 (Settings > Dark Mode)
@Composable
fun ThemeModePill(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}


// 极致 3D 物理下沉变暗动效 (已彻底修复 `pointerInput(Unit)` 闭包捕获历史旧状态的经典 Bug)
fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    var rotationX by remember { mutableStateOf(0f) }
    var rotationY by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }
    var alpha by remember { mutableStateOf(1f) }

    // 💡 解决由 pointerInput(Unit) 导致 lambda 闭包捕获历史旧状态的经典 Bug
    val currentOnClick = rememberUpdatedState(onClick)

    val animRotationX by animateFloatAsState(
        targetValue = rotationX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    val animRotationY by animateFloatAsState(
        targetValue = rotationY,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    val animScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    val animAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    this.pointerInput(Unit) {
        val localSize = this.size

        detectTapGestures(
            onPress = { offset ->
                val centerX = localSize.component1() / 2f
                val centerY = localSize.component2() / 2f

                val deltaX = (offset.x - centerX) / centerX
                val deltaY = (offset.y - centerY) / centerY

                rotationY = deltaX * 12f
                rotationX = -deltaY * 12f
                scale = 0.95f
                alpha = 0.85f

                try {
                    tryAwaitRelease()
                } catch (ignored: Exception) {}

                rotationX = 0f
                rotationY = 0f
                scale = 1f
                alpha = 1f
            },
            onTap = { currentOnClick.value() } // 💡 调用最新状态的 Lambda
        )
    }.graphicsLayer {
        this.rotationX = animRotationX
        this.rotationY = animRotationY
        this.scaleX = animScale
        this.scaleY = animScale
        this.alpha = animAlpha
        this.cameraDistance = 16f * density
    }
}