package com.blacktea.everyshare

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.blacktea.everyshare.core.StatusListener
import com.blacktea.everyshare.core.TcpPunchTransfer
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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

enum class ActiveTab { TRANSFER, SETTINGS }
enum class SessionState { IDLE, CONNECTING, ACTIVE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppTheme { LAVENDER, SAGE, SLATE, OAT }

class MainActivity : ComponentActivity() {
    private val TAG = "EveryShare"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        setContent {
            val context = LocalContext.current
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

            val systemInDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> systemInDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LaunchedEffect(isDark) {
                windowInsetsController.isAppearanceLightStatusBars = !isDark
            }

            val rawColorScheme = when {
                useDynamicColor && isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicDarkColorScheme(context)
                useDynamicColor && !isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
                isDark -> getDarkPresetScheme(appTheme)
                else -> getLightPresetScheme(appTheme)
            }

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

    private fun decodeQrFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val reader = MultiFormatReader()
                val result = reader.decode(binaryBitmap)
                result.text
            }
        } catch (e: Exception) {
            Log.e(TAG, "从相册解析二维码失败", e)
            null
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

        val showQrCode = rememberSaveable { mutableStateOf(false) }
        val showLogs = remember { mutableStateOf(false) }
        val logList = remember { mutableStateListOf<String>() }
        val logListState = rememberLazyListState()

        val activeSocket = remember { mutableStateOf<Socket?>(null) }
        val activeTransferThread = remember { mutableStateOf<Thread?>(null) }
        var resetIpJob by remember { mutableStateOf<Job?>(null) }

        var connectionProgress by remember { mutableStateOf(-1.0f) }
        var showExitDialog by remember { mutableStateOf(false) }
        var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

        val selectedFileUri = remember { mutableStateOf<Uri?>(null) }
        val selectedFileName = remember { mutableStateOf("") }
        val selectedFileSize = remember { mutableStateOf(0L) }
        val isCachingFile = remember { mutableStateOf(false) }

        val hasIpv6 = myIpv6Text.value != "连接中..." && myIpv6Text.value != "未连接"
        val cardBg = if (isDark) Color(0xFF242424) else Color.White
        val cardTextColor = if (isDark) Color.White else Color.Black
        val activeCardBg = if (isDark) Color(0xFF203726) else Color(0xFFE3F9E4)
        val activeCheckColor = if (isDark) Color(0xFF6CCC73) else Color(0xFF6ACD73)

        // 💡 状态提升：将传输策略的折叠控制变量放到此处，实现主页卡片的跨页面联动展开
        var showStrategyDetail by remember { mutableStateOf(false) }

        var logoFlipped by rememberSaveable { mutableStateOf(false) }
        val logoCardRotationY by animateFloatAsState(
            targetValue = if (logoFlipped) 180f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            label = "LogoCard3dFlip"
        )
        val isBackShowing = logoCardRotationY > 90f
        val logoIndexFront = rememberSaveable { mutableStateOf(0) }
        val logoIndexBack = rememberSaveable { mutableStateOf(1) }

        val totalLogos = remember {
            var count = 0
            while (context.resources.getIdentifier("logo_$count", "drawable", context.packageName) != 0) {
                count++
            }
            if (count == 0) 1 else count
        }

        fun getLogoResId(index: Int): Int {
            val resId = context.resources.getIdentifier("logo_$index", "drawable", context.packageName)
            return if (resId != 0) resId else R.drawable.avatar
        }

        val scanLauncher = rememberLauncherForActivityResult(
            contract = ScanContract()
        ) { result ->
            if (result.contents != null) {
                remoteCode.value = result.contents
                statusText.value = "已扫描输入连接码"
            }
        }

        LaunchedEffect(myCode.value) {
            if (myCode.value.startsWith("everyshare://")) {
                withContext(Dispatchers.IO) {
                    val bitmap = this@MainActivity.generateQrCode(myCode.value, 400)
                    withContext(Dispatchers.Main) {
                        qrBitmap = bitmap
                    }
                }
            }
        }

        // 💡 反应式互传码生成器：当角色切换、网卡变化或端口变换时，自动反应并重写正确的 1 字节角色标识 [1]
        LaunchedEffect(isSenderRole.value, myIpv6Text.value, myPort.value) {
            if (myIpv6Text.value != "连接中..." && myIpv6Text.value != "未连接") {
                val rawIp = myIpv6Text.value.split("\n")[0]
                val roleInt = if (isSenderRole.value) 0 else 1
                myCode.value = ConnectionCodeUtil.generateCode(rawIp, myPort.value, roleInt)
            }
        }

        val galleryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val parsed = this@MainActivity.decodeQrFromUri(context, uri)
                        if (parsed != null && parsed.startsWith("everyshare://")) {
                            withContext(Dispatchers.Main) {
                                remoteCode.value = parsed
                                AppLogger.info("Success: QR decoded from photo gallery.")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "未能在图片中找到有效的 EveryShare 二维码", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "相册解析出错: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

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
                        val generatedCode = ConnectionCodeUtil.generateCode(myIpv6.hostAddress, randomPort, if (isSenderRole.value) 0 else 1)

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
                    remoteCode.value = ""
                }

                try { Thread.sleep(500) } catch (ignored: Exception) {}
                resetMyConnectionCode()
            }
        }

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

        BackHandler(enabled = sessionState.value != SessionState.IDLE) {
            if (sessionState.value == SessionState.ACTIVE) {
                cancelActiveTransfer()
            } else {
                cancelActiveTransfer()
            }
        }

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

        DisposableEffect(Unit) {
            System.setProperty("java.net.preferIPv6Addresses", "true")
            System.setProperty("java.net.preferIPv4Stack", "false")

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
            onDispose {
                AppLogger.onLogAdded = null
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp)
                    .padding(top = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                                        Text(
                                            text = "EveryShare",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                            textAlign = TextAlign.Start
                                        )

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

                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(180.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            val isConnecting = myIpv6Text.value == "连接中..."
                                            val hasIpv6Status = !isConnecting && myIpv6Text.value != "未连接"

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
                                                        Text(text = "本地随机端口", fontSize = 13.sp, color = Color.Gray)
                                                        Text(text = myPort.value.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cardTextColor)
                                                    }
                                                }
                                                // 💡 联动机制实现：点击主页“传输策略”卡片，自动平滑跳转至设置界面并展开
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .bounceClick {
                                                            currentTab.value = ActiveTab.SETTINGS
                                                            showStrategyDetail = true
                                                        },
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
                                                            statusText.value = "等待对方连接"
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
                                                            statusText.value = "等待对方连接"
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
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isSenderRole.value) "发送" else "接收",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                IconButton(
                                                    onClick = {
                                                        val options = ScanOptions().apply {
                                                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                                            setPrompt("将二维码放入框内即可自动扫描")
                                                            setBeepEnabled(false)
                                                            setBarcodeImageEnabled(true)
                                                            setOrientationLocked(true)
                                                        }
                                                        scanLauncher.launch(options)
                                                    },
                                                    enabled = !isTransferring.value // 💡 开始连接后禁用
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.QrCodeScanner,
                                                        contentDescription = "Camera Scan",
                                                        tint = if (isTransferring.value) cardTextColor.copy(alpha = 0.4f) else cardTextColor
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        galleryLauncher.launch("image/*")
                                                    },
                                                    enabled = !isTransferring.value // 💡 开始连接后禁用
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PhotoLibrary,
                                                        contentDescription = "Gallery Scan",
                                                        tint = if (isTransferring.value) cardTextColor.copy(alpha = 0.4f) else cardTextColor
                                                    )
                                                }
                                            }
                                        }

                                        val rotationY by animateFloatAsState(
                                            targetValue = if (showQrCode.value) 180f else 0f,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                            label = "3dFlip"
                                        )

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))
                                                .graphicsLayer {
                                                    this.rotationY = rotationY
                                                    cameraDistance = size.minDimension * 4.5f
                                                }
                                                .bounceClick {
                                                    if (hasIpv6) {
                                                        showQrCode.value = !showQrCode.value
                                                    } else {
                                                        android.widget.Toast.makeText(context, "请等待公网 IP 定位成功再试", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                            shape = RoundedCornerShape(24.dp),
                                            colors = CardDefaults.cardColors(containerColor = cardBg),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                        ) {
                                            if (rotationY <= 90f) {
                                                Column(modifier = Modifier.padding(16.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(text = "我的互传码", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(CircleShape)
                                                                    .clickable {
                                                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                                        val clipData = ClipData.newPlainText("EveryShare", myCode.value)
                                                                        clipboardManager.setPrimaryClip(clipData)
                                                                        android.widget.Toast.makeText(context, "连接码已复制到剪贴板！", android.widget.Toast.LENGTH_SHORT).show()
                                                                    },
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Canvas(modifier = Modifier.size(18.dp)) {
                                                                    val strokeWidth = 1.5.dp.toPx()
                                                                    val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                                                    drawRoundRect(
                                                                        color = cardTextColor.copy(alpha = 0.8f),
                                                                        topLeft = Offset(4.dp.toPx(), 0.dp.toPx()),
                                                                        size = Size(10.dp.toPx(), 13.dp.toPx()),
                                                                        cornerRadius = cornerRadius,
                                                                        style = Stroke(width = strokeWidth)
                                                                    )
                                                                    drawRoundRect(
                                                                        color = cardTextColor.copy(alpha = 0.8f),
                                                                        topLeft = Offset(0.dp.toPx(), 4.dp.toPx()),
                                                                        size = Size(10.dp.toPx(), 13.dp.toPx()),
                                                                        cornerRadius = cornerRadius,
                                                                        style = Stroke(width = strokeWidth)
                                                                    )
                                                                }
                                                            }

                                                            Icon(
                                                                imageVector = Icons.Default.QrCodeScanner,
                                                                contentDescription = "Flip to QR",
                                                                tint = cardTextColor.copy(alpha = 0.6f),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(text = myCode.value, fontSize = 14.sp, color = cardTextColor)
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .graphicsLayer { scaleX = -1f }
                                                        .padding(16.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(text = "扫码快速连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        qrBitmap?.let {
                                                            Image(
                                                                bitmap = it.asImageBitmap(),
                                                                contentDescription = "QR Code",
                                                                modifier = Modifier.size(140.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 连接配对 Bento 一体化卡片
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(24.dp),
                                            colors = CardDefaults.cardColors(containerColor = cardBg),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "连接到对方设备",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )

                                                    // 纯圆形一键粘贴按键，Canvas 手绘
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .clickable {
                                                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                                val clipData = clipboardManager.primaryClip
                                                                if (clipData != null && clipData.itemCount > 0) {
                                                                    val text = clipData.getItemAt(0).text
                                                                    if (text != null) {
                                                                        remoteCode.value = text.toString().trim()
                                                                        statusText.value = "已粘贴连接码"
                                                                    }
                                                                }
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Canvas(modifier = Modifier.size(18.dp)) {
                                                            val strokeWidth = 1.5.dp.toPx()
                                                            val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                                            drawRoundRect(
                                                                color = cardTextColor.copy(alpha = 0.8f),
                                                                topLeft = Offset(2.dp.toPx(), 4.dp.toPx()),
                                                                size = Size(14.dp.toPx(), 14.dp.toPx()),
                                                                cornerRadius = cornerRadius,
                                                                style = Stroke(width = strokeWidth)
                                                            )
                                                            drawRoundRect(
                                                                color = cardTextColor.copy(alpha = 0.8f),
                                                                topLeft = Offset(5.dp.toPx(), 1.dp.toPx()),
                                                                size = Size(8.dp.toPx(), 4.dp.toPx()),
                                                                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                                                                style = Stroke(width = strokeWidth)
                                                            )
                                                        }
                                                    }
                                                }

                                                OutlinedTextField(
                                                    value = remoteCode.value,
                                                    onValueChange = { remoteCode.value = it },
                                                    label = { Text("请输入或扫描对方的连接码") },
                                                    shape = CircleShape,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                        unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f)
                                                    ),
                                                    enabled = !isTransferring.value // 开始连接后禁用输入框
                                                )

                                                // 💡 一键变形药丸按钮：未连接显示“开始连接”，连接中变为“取消连接”
                                                Button(
                                                    onClick = {
                                                        if (isTransferring.value) {
                                                            // 💡 状态重载：如果在连接等待中被点击，执行一键安全返回并关闭 Socket 进程！
                                                            cancelActiveTransfer()
                                                        } else {
                                                            if (remoteCode.value.isBlank()) return@Button

                                                            // 💡 物理防御：自己不能连接自己 (使用 Toast 直接静默拦截，不触发不和谐错误状态卡) [1]
                                                            if (remoteCode.value.trim() == myCode.value.trim()) {
                                                                android.widget.Toast.makeText(context, "不能连接到自己", android.widget.Toast.LENGTH_SHORT).show()
                                                                return@Button
                                                            }

                                                            // 💡 物理防御：接收解析 19 字节角色并核对是否冲突 [1]
                                                            try {
                                                                val parsedInfo = ConnectionCodeUtil.parseCode(remoteCode.value)
                                                                val myRoleInt = if (isSenderRole.value) 0 else 1
                                                                if (parsedInfo.role == myRoleInt) {
                                                                    statusText.value = "连接失败：双方角色冲突" // 💡 双方角色冲突：写入状态卡展示，不弹 Toast！ [5]
                                                                    isTransferring.value = false
                                                                    return@Button
                                                                }
                                                            } catch (e: Exception) {
                                                                statusText.value = "连接失败：互传码无效"
                                                                isTransferring.value = false
                                                                return@Button
                                                            }

                                                            statusText.value = "等待对方连接"
                                                            isTransferring.value = true
                                                            connectionProgress = -1.0f

                                                            val statusListener = StatusListener { status ->
                                                                this@MainActivity.runOnUiThread {
                                                                    statusText.value = status
                                                                }
                                                            }

                                                            val t = thread {
                                                                try {
                                                                    val remoteInfo = ConnectionCodeUtil.parseCode(remoteCode.value)
                                                                    val puncher = TcpPunchTransfer()

                                                                    val listener = ProgressListener { name, read, total, speed ->
                                                                        progressPercent.value = ((read.toDouble() / total) * 100).toInt()
                                                                        currentSpeed.value = speed
                                                                        statusText.value = if (isSenderRole.value) "正在发送: $name" else "正在接收: $name"
                                                                    }

                                                                    val actualFakeHttpHost = if (fakeHttpOption.value == "自定义" && customFakeHttpHost.value.isNotBlank()) {
                                                                        customFakeHttpHost.value
                                                                    } else {
                                                                        "speedtest.cn"
                                                                    }

                                                                    val socket = puncher.connectByPunch(remoteInfo.ip, remoteInfo.port, myPort.value, !isSenderRole.value, useUdpSync.value, actualFakeHttpHost, statusListener)
                                                                    if (socket != null) {
                                                                        activeSocket.value = socket

                                                                        this@MainActivity.runOnUiThread {
                                                                            connectionProgress = 1.0f
                                                                            statusText.value = "穿透成功！建立长周期会话。"
                                                                        }

                                                                        try { Thread.sleep(1500) } catch (ignored: Exception) {}

                                                                        sessionState.value = SessionState.ACTIVE

                                                                        if (!isSenderRole.value) {
                                                                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                                                                                android.os.Environment.DIRECTORY_DOWNLOADS
                                                                            )
                                                                            val everyShareDir = File(downloadDir, "EveryShare")
                                                                            if (!everyShareDir.exists()) everyShareDir.mkdirs()
                                                                            val saveDir = if (everyShareDir.exists()) everyShareDir.absolutePath else downloadDir.absolutePath

                                                                            while (activeSocket.value != null && !activeSocket.value!!.isClosed) {
                                                                                puncher.receiveFile(socket, saveDir, listener)
                                                                                this@MainActivity.runOnUiThread { statusText.value = "🎉 接收成功！已存入公共下载目录" }
                                                                            }
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
                                                                    this@MainActivity.runOnUiThread {
                                                                        isTransferring.value = false
                                                                        progressPercent.value = 0
                                                                        currentSpeed.value = 0.0
                                                                    }
                                                                    activeTransferThread.value = null
                                                                }
                                                            }
                                                            activeTransferThread.value = t
                                                        }
                                                    },
                                                    shape = CircleShape,
                                                    modifier = Modifier.width(160.dp).height(44.dp),
                                                    enabled = remoteCode.value.isNotBlank(), // 💡 发送期间不用置灰，因为它身兼“取消连接”的功能！ [1]
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isTransferring.value) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                                    )
                                                ) {
                                                    Text(
                                                        text = if (isTransferring.value) "取消连接" else "开始连接",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        // 💡 智能显隐状态卡：当处于传输中，或者状态栏包含“连接失败”报错时，卡片必须平滑在眼前拉开！
                                        val isErrorState = statusText.value.startsWith("连接失败")
                                        AnimatedVisibility(
                                            visible = isTransferring.value || isErrorState,
                                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                        ) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                                ) {
                                                    // 💡 修复后（使用 Material 官方原生错误图标，并绑定 M3 主题错误红）：
                                                    if (isErrorState) {
                                                        Icon(
                                                            imageVector = Icons.Default.Cancel, // 👈 官方标准的圆圈交叉图标（或换成 Icons.Default.Error 变感叹号）
                                                            contentDescription = "Error Connection",
                                                            tint = MaterialTheme.colorScheme.error, // 👈 优雅地绑定当前主题的警告/错误红色
                                                            modifier = Modifier.size(54.dp) // 保持 54.dp 的完美比例
                                                        )
                                                    } else if (connectionProgress < 0f) {
                                                        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                                                        LoadingIndicator(modifier = Modifier.size(54.dp))
                                                    } else {
                                                        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                                                        LoadingIndicator(
                                                            progress = { connectionProgress },
                                                            modifier = Modifier.size(54.dp)
                                                        )
                                                    }

                                                    Box(
                                                        modifier = Modifier
                                                            .width(1.dp)
                                                            .height(36.dp)
                                                            .background(Color.LightGray.copy(alpha = 0.4f))
                                                    )

                                                    Box(modifier = Modifier.weight(1f)) {
                                                        AnimatedContent(
                                                            targetState = statusText.value,
                                                            transitionSpec = {
                                                                (slideInVertically { height -> height } + fadeIn(animationSpec = tween(300)))
                                                                    .togetherWith(slideOutVertically { height -> -height } + fadeOut(animationSpec = tween(300)))
                                                            },
                                                            label = "StatusText"
                                                        ) { targetText ->
                                                            Text(
                                                                text = targetText,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = cardTextColor
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (state == SessionState.ACTIVE) {
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

                                            Spacer(modifier = Modifier.height(8.dp))

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
                                            onClick = { showExitDialog = true },
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

                            // 💡 1. 3D 翻转多彩 Logo 品牌卡片（Settings 最顶端）[1, 1.1.2]
                            val logoCardRotationY by animateFloatAsState(
                                targetValue = if (logoFlipped) 180f else 0f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                label = "LogoCard3dFlip"
                            )

                            val isBackShowing = logoCardRotationY > 90f
                            val displayLogoIndex = if (isBackShowing) logoIndexBack.value else logoIndexFront.value

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .graphicsLayer {
                                        this.rotationY = logoCardRotationY
                                        cameraDistance = size.minDimension * 4.5f
                                    }
                                    .bounceClick {
                                        if (!logoFlipped) {
                                            logoIndexBack.value = (logoIndexFront.value + 1) % totalLogos
                                            logoFlipped = true
                                        } else {
                                            logoIndexFront.value = (logoIndexBack.value + 1) % totalLogos
                                            logoFlipped = false
                                        }
                                    },
                                shape = RoundedCornerShape(24.dp),
                                // 💡 视觉完全相容：深色模式直接采用 cardBg，浅色完全透明
                                colors = CardDefaults.cardColors(containerColor = if (isDark) cardBg else Color.Transparent),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.graphicsLayer {
                                            if (isBackShowing) scaleX = -1f
                                        }
                                    ) {
                                        Image(
                                            painter = painterResource(id = getLogoResId(displayLogoIndex)),
                                            contentDescription = "EveryShare Brand Logo",
                                            modifier = Modifier.size(100.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "EveryShare", // 💡 正反面都是 EveryShare
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Black,
                                            color = cardTextColor
                                        )
                                        Text(
                                            text = "v0.1.0-alpha", // 💡 统一版本号为 v0.1.0-alpha [3]
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }

                            // 💡 2. 「初次使用」说明卡片放在 Logo 卡片下方
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
                                        text = "依赖公网 IPv6 实现点对点传输，通过 TCP Simultaneous Open 打开防火墙，DPI 流量伪装绕过上行限速。\n仅在本地测试有效。还需其他地区，其他运营商，不同环境下测试。",
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = if (isDark) Color.LightGray else Color.Gray
                                    )
                                }
                            }

                            var showAppearanceDetail by remember { mutableStateOf(false) }
                            val appearanceArrowRotation by animateFloatAsState(
                                targetValue = if (showAppearanceDetail) 180f else 0f,
                                animationSpec = tween(300),
                                label = "AppearanceArrow"
                            )

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
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Toggle Chevron",
                                            tint = Color.Gray,
                                            modifier = Modifier.graphicsLayer { rotationZ = appearanceArrowRotation }
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = showAppearanceDetail,
                                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = "动态颜色", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)
                                                    Text(
                                                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "跟随系统壁纸取色 (Android 12+)" else "仅支持 Android 12+ 设备",
                                                        fontSize = 11.sp,
                                                        color = Color.Gray
                                                    )
                                                }
                                                Switch(
                                                    checked = useDynamicColor.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                                    onCheckedChange = { onUseDynamicColorChange(it) },
                                                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                                )
                                            }

                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Text(text = "配色主题", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardTextColor)
                                                Text(
                                                    text = if (useDynamicColor.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "关闭动态颜色后生效" else "挑选你喜欢的个性化色调",
                                                    fontSize = 11.sp,
                                                    color = Color.Gray
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceAround,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val isSelectionEnabled = !(useDynamicColor.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

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

                            val strategyArrowRotation by animateFloatAsState(
                                targetValue = if (showStrategyDetail) 180f else 0f,
                                animationSpec = tween(300),
                                label = "StrategyArrow"
                            )

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
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Toggle Chevron",
                                            tint = Color.Gray,
                                            modifier = Modifier.graphicsLayer { rotationZ = strategyArrowRotation }
                                        )
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

                                            BoxWithConstraints(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(44.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (isDark) Color(0xFF1E1E1E) else Color.LightGray.copy(alpha = 0.2f))
                                                    .padding(2.dp)
                                            ) {
                                                val innerWidth = maxWidth - 4.dp
                                                val pillWidth = innerWidth / 2
                                                val selectedIndex = if (useUdpSync.value) 0 else 1
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
                                                        text = "UDP + TCP (推荐)",
                                                        isSelected = useUdpSync.value,
                                                        onClick = { onUseUdpSyncChange(true) },
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    ThemeModePill(
                                                        text = "TCP",
                                                        isSelected = !useUdpSync.value,
                                                        onClick = { onUseUdpSyncChange(false) },
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 💡 DPI 流量伪装域名设置
                            var showFakeHttpDetail by remember { mutableStateOf(false) }
                            val fakeHttpArrowRotation by animateFloatAsState(
                                targetValue = if (showFakeHttpDetail) 180f else 0f,
                                animationSpec = tween(300),
                                label = "FakeHttpArrow"
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showFakeHttpDetail = !showFakeHttpDetail },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardColors(containerColor = cardBg, contentColor = cardTextColor, disabledContainerColor = cardBg, disabledContentColor = cardTextColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "DPI 伪装域名", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cardTextColor)
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Toggle Chevron",
                                            tint = Color.Gray,
                                            modifier = Modifier.graphicsLayer { rotationZ = fakeHttpArrowRotation }
                                        )
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

                                            var hHeaderExpanded by remember { mutableStateOf(false) }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { hHeaderExpanded = true }
                                            ) {
                                                OutlinedTextField(
                                                    value = fakeHttpOption.value,
                                                    onValueChange = {},
                                                    readOnly = true,
                                                    enabled = false,
                                                    label = { Text("DPI 伪装域名") },
                                                    trailingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowDropDown,
                                                            contentDescription = null,
                                                            tint = cardTextColor
                                                        )
                                                    },
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        disabledTextColor = cardTextColor,
                                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        // 💡 修复后：补全了中间的 .colorScheme 属性调用
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
                            val aboutArrowRotation by animateFloatAsState(
                                targetValue = if (showAboutDetail) 180f else 0f,
                                animationSpec = tween(300),
                                label = "AboutArrow"
                            )

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
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Toggle Chevron",
                                            tint = Color.Gray,
                                            modifier = Modifier.graphicsLayer { rotationZ = aboutArrowRotation }
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = showAboutDetail,
                                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Divider(color = Color.LightGray.copy(alpha = 0.2f))
                                            Spacer(modifier = Modifier.height(14.dp))

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
                    Text(text = "运行日志", fontSize = 13.sp, color = Color.Gray)
                    TextButton(onClick = { showLogs.value = !showLogs.value }) {
                        Text(text = if (showLogs.value) "隐藏日志" else "展开日志", fontSize = 12.sp)
                    }
                }

                AnimatedVisibility(visible = showLogs.value) {
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

            val selectedIndex = if (currentTab.value == ActiveTab.TRANSFER) 0 else 1
            val indicatorOffset by animateDpAsState(targetValue = if (selectedIndex == 0) 0.dp else 86.dp)

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .width(180.dp)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF242424).copy(alpha = 0.95f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                    .padding(4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(86.dp)
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
                            .weight(1f)
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
                            .weight(1f)
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

            if (showExitDialog) {
                val backgroundAlpha by animateFloatAsState(
                    targetValue = 0.5f,
                    animationSpec = tween(150),
                    label = "dim"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = backgroundAlpha))
                        .pointerInput(Unit) { detectTapGestures { /* 阻断背景点击 */ } },
                    contentAlignment = Alignment.Center
                ) {
                    var dialogVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        dialogVisible = true
                    }

                    AnimatedVisibility(
                        visible = dialogVisible,
                        enter = fadeIn(animationSpec = tween(150)),
                        exit = fadeOut(animationSpec = tween(150))
                    ) {
                        Card(
                            modifier = Modifier
                                .width(280.dp)
                                .padding(16.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "会话正在进行中",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = cardTextColor
                                )
                                Text(
                                    text = "你确定要退出会话吗？",
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "取消",
                                        color = Color.Gray,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                scope.launch {
                                                    dialogVisible = false
                                                    delay(150)
                                                    showExitDialog = false
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                dialogVisible = false
                                                delay(150)
                                                showExitDialog = false
                                                cancelActiveTransfer()
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text(text = "确定", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
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

fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    var rotationX by remember { mutableStateOf(0f) }
    var rotationY by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }
    var alpha by remember { mutableStateOf(1f) }

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

                rotationY = deltaX * 24f
                rotationX = -deltaY * 24f
                scale = 0.94f
                alpha = 0.85f

                try {
                    tryAwaitRelease()
                } catch (ignored: Exception) {}

                rotationX = 0f
                rotationY = 0f
                scale = 1f
                alpha = 1f
            },
            onTap = { currentOnClick.value() }
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