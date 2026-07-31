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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
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
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileInputStream
import java.net.Socket
import java.util.Objects
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

enum class ActiveTab { TRANSFER, SETTINGS }
enum class SessionState { IDLE, CONNECTING, ACTIVE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppTheme { LAVENDER, SAGE, SLATE, OAT }
enum class PillState { HIDDEN, POP_CIRCLE, EXTEND_PILL }

class MainActivity : ComponentActivity() {
    private val TAG = "EveryShare"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 💡 动态申请 Android 13+ 必配的发送通知权限，否则前台服务会在启动时崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

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

        // 💡 状态：智能药丸（Smart Pill）状态机及显示文本
        var pillState by remember { mutableStateOf(PillState.HIDDEN) }
        var pillText by remember { mutableStateOf("") }

        // 💡 智能药丸自动化生命周期监听一：网络定位阶段
        LaunchedEffect(myIpv6Text.value) {
            if (myIpv6Text.value == "连接中...") {
                pillText = "正在定位公网 IP"
                pillState = PillState.POP_CIRCLE
                delay(1000) // 💡 完美停顿：等待 1 秒，等小圆果冻回弹完全静止后再平滑延伸
                pillState = PillState.EXTEND_PILL
            } else if (myIpv6Text.value != "连接中..." && myIpv6Text.value != "未连接") {
                pillText = "公网 IP 定位成功"
                pillState = PillState.EXTEND_PILL
                delay(2500) // 显示 2.5 秒后自动缩回并隐藏
                pillState = PillState.POP_CIRCLE
                delay(600) // 💡 物理平滑等待：等 600ms 正圆收缩动画彻底放完，再触发 Hidden 退出淡出！
                pillState = PillState.HIDDEN
            } else if (myIpv6Text.value == "未连接") {
                pillText = "公网 IP 定位失败"
                pillState = PillState.EXTEND_PILL
                delay(2500)
                pillState = PillState.POP_CIRCLE
                delay(600)
                pillState = PillState.HIDDEN
            }
        }

        // 💡 智能药丸自动化生命周期监听二：传输连接阶段
        // 💡 智能药丸自动化生命周期监听二：传输连接阶段
        LaunchedEffect(isTransferring.value, statusText.value) {
            if (isTransferring.value) {
                // 💡 按照约定，只对这三个关键状态进行极致精简翻译，其他保持原样
                pillText = when {
                    statusText.value.startsWith("正在发送:") -> "发送中..."
                    statusText.value.startsWith("正在接收:") -> "接收中..."
                    statusText.value == "穿透成功！建立长周期会话。" -> "通道已打通"
                    else -> statusText.value
                }

                if (pillState == PillState.HIDDEN) {
                    pillState = PillState.POP_CIRCLE
                    delay(1000) // 弹起圆点，静止 1 秒
                }
                pillState = PillState.EXTEND_PILL
            } else {
                if (pillState == PillState.EXTEND_PILL) {
                    pillState = PillState.POP_CIRCLE
                    delay(600) // 等待缩圆
                    pillState = PillState.HIDDEN
                }
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

        // 💡 反应式互传码生成器
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
            resetIpJob?.cancel() // 💡 杀掉前一个，实现防抖
            myIpv6Text.value = "连接中..."
            myCode.value = "正在定位公网 IP..."

            resetIpJob = scope.launch(Dispatchers.IO) {
                // 💡 1. 黄金防抖时间：给网卡、基站和 DNS 1.2 秒的物理就绪和稳定时间
                delay(1200)
                try {
                    // 💡 修改你的 MainActivity.kt 里的 apis 列表：
                    val apis = listOf(
                        "https://ipv6.icanhazip.com",    // 2. 微软/Cloudflare 顶级 CDN，全球最稳
                        "https://v6.ident.me",      // 3. 经典备用
                        "https://api6.ipify.org"     // 4. 经典备用
                    )

                    // 第一次尝试（因为有了 1.2s 的防抖，这一次的成功率将极高）
                    var myIpv6 = withTimeoutOrNull(2500) {
                        raceFetchIpv6(apis)
                    }

                    // 第二次容错重试（只有在极端差网、或者 1.2s 仍未就绪时才触发）
                    if (myIpv6 == null) {
                        AppLogger.info("[IP] 首次定位失败，网络可能尚未完全就绪。正在等待 1.5 秒后进行第二次尝试...")
                        delay(1500)
                        myIpv6 = withTimeoutOrNull(2500) {
                            raceFetchIpv6(apis)
                        }
                    }

                    if (myIpv6 != null) {
                        val randomPort = 50000 + (Math.random() * 10000).toInt()
                        myPort.value = randomPort
                        val generatedCode = ConnectionCodeUtil.generateCode(
                            myIpv6.hostAddress,
                            randomPort,
                            if (isSenderRole.value) 0 else 1
                        )

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
                    AppLogger.error("并行定位公网 IP 异常", e)
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

                try {
                    context.stopService(Intent(context, EveryShareService::class.java))
                } catch (ignored: Exception) {}

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

                // 💡 0毫秒就绪：不再进行任何后台 Files.copy 复制！
                statusText.value = "文件就绪: $name"
                isCachingFile.value = false
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
                                        // 💡 主页纯净：右上角只留灵动药丸 (SmartPill)，完全解耦
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animateContentSize(
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                ),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "EveryShare",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            )

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                SmartPillShell(state = pillState, text = pillText)
                                                Spacer(modifier = Modifier.width(6.dp)) // 💡 物理安全边距，彻底防右边缘裁切
                                            }
                                        }

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
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animateContentSize(
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                ),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isSenderRole.value) "发送" else "接收",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
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
                                                    enabled = !isTransferring.value
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
                                                    enabled = !isTransferring.value
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PhotoLibrary,
                                                        contentDescription = "Gallery Scan",
                                                        tint = if (isTransferring.value) cardTextColor.copy(alpha = 0.4f) else cardTextColor
                                                    )
                                                }

                                                // 💡 配对页右上角：药丸放置在扫码和相册的右侧
                                                SmartPillShell(state = pillState, text = pillText)
                                                Spacer(modifier = Modifier.width(6.dp)) // 💡 物理安全边距，防止回弹时右侧切边
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

                                        // 连接配对 Bento 卡片
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

                                                    // 一键粘贴圆形按键
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
                                                    enabled = !isTransferring.value
                                                )

                                                // 💡 一键变形药丸按钮
                                                Button(
                                                    onClick = {
                                                        if (isTransferring.value) {
                                                            cancelActiveTransfer()
                                                        } else {
                                                            if (remoteCode.value.isBlank()) return@Button
                                                            if (remoteCode.value.trim() == myCode.value.trim()) {
                                                                android.widget.Toast.makeText(context, "不能连接到自己", android.widget.Toast.LENGTH_SHORT).show()
                                                                return@Button
                                                            }

                                                            try {
                                                                val parsedInfo = ConnectionCodeUtil.parseCode(remoteCode.value)
                                                                val myRoleInt = if (isSenderRole.value) 0 else 1
                                                                if (parsedInfo.role == myRoleInt) {
                                                                    statusText.value = "连接失败：双方角色冲突"
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

                                                                        this@MainActivity.runOnUiThread {
                                                                            isTransferring.value = false // 💡 穿透建立会话后，先恢复为 false，使其显示 LoadingIndicator
                                                                            statusText.value = if (isSenderRole.value) "等待选择文件..." else "等待对方发送文件..."
                                                                        }

                                                                        // 💡 启动前台服务：向 Android 宣誓主权，要求其在选文件时保持 CPU 和网卡绝对活跃！ [2]
                                                                        try {
                                                                            val serviceIntent = Intent(this@MainActivity, EveryShareService::class.java)
                                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                                startForegroundService(serviceIntent)
                                                                            } else {
                                                                                startService(serviceIntent)
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            AppLogger.error("启动前台守护服务失败", e)
                                                                        }


                                                                        // 💡 核心修复：在这里启动一个不受 Android UI 限制的纯 Java 后台守护线程发送心跳 [1, 2]
                                                                        if (isSenderRole.value) {
                                                                            thread(isDaemon = true) {
                                                                                val socket = activeSocket.value
                                                                                while (socket != null && !socket.isClosed) {
                                                                                    try {
                                                                                        Thread.sleep(15000) // 15秒一跳 [1]
                                                                                        val os = socket.getOutputStream()
                                                                                        os.write("HEARTBEAT\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                                                                        os.flush()
                                                                                        Log.d("EveryShare", "发送端后台心跳成功...")
                                                                                    } catch (e: Exception) {
                                                                                        AppLogger.error("后台心跳失败，连接已断开", e)
                                                                                        this@MainActivity.runOnUiThread {
                                                                                            statusText.value = "连接已断开"
                                                                                            isTransferring.value = false
                                                                                        }
                                                                                        try { Thread.sleep(2000) } catch (ignored: Exception) {}
                                                                                        cancelActiveTransfer()
                                                                                        break
                                                                                    }
                                                                                }
                                                                            }
                                                                        }

                                                                        sessionState.value = SessionState.ACTIVE

                                                                        if (!isSenderRole.value) {
                                                                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                                                                                android.os.Environment.DIRECTORY_DOWNLOADS
                                                                            )
                                                                            val everyShareDir = File(downloadDir, "EveryShare")
                                                                            if (!everyShareDir.exists()) everyShareDir.mkdirs()
                                                                            val saveDir = if (everyShareDir.exists()) everyShareDir.absolutePath else downloadDir.absolutePath

                                                                            // 重新定义一个接收端的专用 listener，在收到数据的第一个字节时，自动激活波浪圆圈
                                                                            val rxListener = ProgressListener { name, read, total, speed ->
                                                                                this@MainActivity.runOnUiThread {
                                                                                    isTransferring.value = true // 💡 只要开始读数据，左侧立刻无缝切换到波浪圆圈
                                                                                    progressPercent.value = ((read.toDouble() / total) * 100).toInt()
                                                                                    currentSpeed.value = speed
                                                                                    statusText.value = "正在接收: $name"
                                                                                }
                                                                            }

                                                                            while (activeSocket.value != null && !activeSocket.value!!.isClosed) {
                                                                                // 💡 2. 只有在真正接收到文件（返回 true）时，才显示接收成功
                                                                                val receivedSuccess = puncher.receiveFile(socket, saveDir, rxListener)
                                                                                if (receivedSuccess) {
                                                                                    this@MainActivity.runOnUiThread {
                                                                                        isTransferring.value = false
                                                                                        statusText.value = "接收成功，已存入Download/EveryShare"
                                                                                        progressPercent.value = 0
                                                                                        currentSpeed.value = 0.0
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    } else {
                                                                        AppLogger.info("❌ 穿透失败，请确认双方同时开启")
                                                                        this@MainActivity.runOnUiThread {
                                                                            isTransferring.value = false
                                                                        }
                                                                    }
                                                                } catch (e: Exception) {
                                                                    AppLogger.error("接收端会话异常", e)
                                                                    // 💡 异常发生（说明对方主动关闭或网络断开），在 UI 上提示 [2.1.2]
                                                                    this@MainActivity.runOnUiThread {
                                                                        statusText.value = "连接已断开"
                                                                        isTransferring.value = false
                                                                    }
                                                                    // 停顿 2 秒让用户看清提示，然后干净地退回主界面
                                                                    try { Thread.sleep(2000) } catch (ignored: Exception) {}
                                                                    cancelActiveTransfer()
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
                                                    enabled = remoteCode.value.isNotBlank(),
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
                                                    if (isErrorState) {
                                                        // 💡 替换为 Material3 官方原生组件和主题错误红
                                                        Icon(
                                                            imageVector = Icons.Default.Cancel,
                                                            contentDescription = "Error Connection",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(54.dp)
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
                                        //               「会话传输中」界面 (M3 Expressive)
                                        // ==========================================
                                        // 💡 1. 核心重组：大标题与药丸合并至同一行，删去原先的多余副标题行 [2.1.2]
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "传输会话",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )

                                            SmartPillShell(state = pillState, text = pillText)
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // 💡 2. 传输状态大卡片 (Status Card)
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(24.dp)), // 物理遮罩
                                            colors = CardDefaults.cardColors(containerColor = cardBg),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                        ) {
                                            // 保持高度 140.dp，确保背景波浪流动空间
                                            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {

                                                // 【图层 1：底层】大河漂流传送带 [1]
                                                DriftingBackgroundWaves(
                                                    progress = progressPercent.value / 100f,
                                                    isDark = isDark
                                                )

                                                // 【图层 2：前台】信息内容展示
                                                Row(
                                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                                ) {
                                                    // 💡 左侧圆形：只有真正传输时才转为 CircularWavyProgressIndicator，平时保持为 LoadingIndicator [2]
                                                    Box(
                                                        modifier = Modifier.size(54.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                                                        if (isTransferring.value) {
                                                            CircularWavyProgressIndicator(
                                                                progress = { progressPercent.value / 100f },
                                                                color = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(36.dp)
                                                            )
                                                        } else {
                                                            @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                                                            LoadingIndicator(
                                                                modifier = Modifier.size(24.dp),
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }

                                                    // 垂直分割线
                                                    Box(
                                                        modifier = Modifier
                                                            .width(1.dp)
                                                            .height(48.dp)
                                                            .background(Color.LightGray.copy(alpha = 0.4f))
                                                    )

                                                    // 状态文字与速度
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = statusText.value,
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = cardTextColor
                                                        )
                                                        if (isTransferring.value) {
                                                            Text(
                                                                text = String.format("速度: %.2f MB/s | 进度: %d%%", currentSpeed.value, progressPercent.value),
                                                                fontSize = 12.sp,
                                                                color = Color.Gray
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // 【居中】选择文件卡片（去掉了文件夹 Emoji）
                                        if (isSenderRole.value) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().bounceClick {
                                                    if (!isCachingFile.value && !isTransferring.value) filePickerLauncher.launch("*/*")
                                                },
                                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(16.dp)) {
                                                    Text(
                                                        text = "选择要发送的文件 (点击浏览):",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = if (selectedFileName.value.isEmpty()) "点击选择手机上的任意文件" else "${selectedFileName.value} (${selectedFileSize.value / 1024 / 1024} MB)",
                                                        fontSize = 14.sp,
                                                        color = cardTextColor
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.weight(1f))

                                        // 【居底】双排小尺寸并排按钮
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isSenderRole.value) {
                                                Button(
                                                    onClick = {
                                                        if (activeSocket.value == null || selectedFileUri.value == null) return@Button
                                                        isTransferring.value = true
                                                        statusText.value = "正在上传文件..."

                                                        val t = thread {
                                                            try {
                                                                val puncher = TcpPunchTransfer()
                                                                val listener = ProgressListener { name, read, total, speed ->
                                                                    progressPercent.value = ((read.toDouble() / total) * 100).toInt()
                                                                    currentSpeed.value = speed
                                                                    statusText.value = "正在发送: $name"
                                                                }

                                                                // 💡 核心优化：直接从 Android 系统获取该 URI 的输入流，直接喂给 Java 引擎！ [1, 2]
                                                                context.contentResolver.openInputStream(selectedFileUri.value!!).use { fileInputStream ->
                                                                    if (fileInputStream != null) {
                                                                        puncher.sendFile(activeSocket.value!!, fileInputStream, selectedFileName.value, selectedFileSize.value, listener)
                                                                    } else {
                                                                        throw java.io.IOException("无法打开文件输入流")
                                                                    }
                                                                }

                                                                AppLogger.info("🎉 文件发送完成！")
                                                                this@MainActivity.runOnUiThread {
                                                                    statusText.value = "等待选择文件..."
                                                                }
                                                            } catch (e: Exception) {
                                                                AppLogger.error("发送出错", e)
                                                                this@MainActivity.runOnUiThread {
                                                                    statusText.value = "连接已断开"
                                                                    isTransferring.value = false
                                                                }
                                                                try { Thread.sleep(2000) } catch (ignored: Exception) {}
                                                                cancelActiveTransfer()
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
                                                    shape = CircleShape,
                                                    modifier = Modifier.weight(1f).height(44.dp), // 缩小的开始按钮
                                                    enabled = !isTransferring.value && !isCachingFile.value && selectedFileUri.value != null
                                                ) {
                                                    Text("发送选中的文件", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            Button(
                                                onClick = { cancelActiveTransfer() },
                                                shape = CircleShape,
                                                modifier = Modifier.weight(1f).height(44.dp), // 缩小的断开按钮
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("断开连接", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
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

                            // 💡 1. 3D 翻转多彩 Logo 品牌卡片移至 Settings 最顶端
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
                                            text = "EveryShare",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Black,
                                            color = cardTextColor
                                        )
                                        Text(
                                            text = "v0.1.0-alpha",
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
                                                        Row(verticalAlignment = Objects.requireNonNull(Alignment.CenterVertically)) {
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

                // 💡 优化：将日志标题、一键复制、展开折叠整齐排布
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "运行日志", fontSize = 13.sp, color = Color.Gray)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 💡 新增：一键复制日志按钮 [1]
                        TextButton(
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val allLogs = logList.joinToString("\n")
                                val clipData = ClipData.newPlainText("EveryShare_Logs", allLogs)
                                clipboardManager.setPrimaryClip(clipData)
                                android.widget.Toast.makeText(context, "日志已一键复制到剪贴板！", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            enabled = logList.isNotEmpty()
                        ) {
                            Text(text = "一键复制", fontSize = 12.sp)
                        }

                        TextButton(onClick = { showLogs.value = !showLogs.value }) {
                            Text(text = if (showLogs.value) "隐藏日志" else "展开日志", fontSize = 12.sp)
                        }
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
                        // 💡 彻底删去了外层的 SelectionContainer，完美解决快速刷屏时的闪退 Bug
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

// 💡 定义每一条漂流波浪的数据结构 [1]
data class WaveTrack(
    val id: Int,
    val yOffset: Dp,
    val width: Dp,
    val durationMillis: Int,
    val initialDelayMillis: Int
)

/**
 * 💡 核心动效：背景“传送带”漂流波浪组件 [1]
 * 6 条官方波浪轨道在后台交错运动，其自身的 progress 实时绑定文件传输的实际进度 [1, 2]
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DriftingBackgroundWaves(progress: Float, isDark: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "BackgroundWaves")

    // 💡 物理轨迹库：不同高度、不同宽度（长短不一）、不同速度（快慢交错），营造极佳的水流层次感 [1]
    val tracks = remember {
        listOf(
            WaveTrack(1, 10.dp, 120.dp, 8000, 0),
            WaveTrack(2, 28.dp, 80.dp, 6000, 1500),
            WaveTrack(3, 46.dp, 140.dp, 10000, 500),
            WaveTrack(4, 64.dp, 95.dp, 7000, 2000),
            WaveTrack(5, 82.dp, 115.dp, 9000, 1000),
            WaveTrack(6, 100.dp, 75.dp, 5500, 2500)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        tracks.forEach { track ->
            // 💡 利用无限循环过渡，让每一条波浪的 X 轴偏移量平滑向右递增 [1]
            val progressFloat by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = track.durationMillis,
                        delayMillis = track.initialDelayMillis,
                        easing = LinearEasing // 线性平滑滑行，绝不卡顿
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "WaveTranslation_${track.id}"
            )

            // 💡 传送带物理位移计算：从左侧 -150.dp（隐藏出生）平滑滑行至右侧 400.dp（隐藏消逝） [1]
            val startX = -150.dp
            val endX = 400.dp
            val currentX = startX + (endX - startX) * progressFloat

            // 💡 调用官方原生波浪组件 [1, 2]
            LinearWavyProgressIndicator(
                progress = { progress }, // 实时绑定传输进度！ [2]
                modifier = Modifier
                    .offset(x = currentX, y = track.yOffset)
                    .width(track.width) // 随机的长短不一
                    .graphicsLayer {
                        // 💡 极致的半透明：既有隐约的流体光泽，又绝对不干扰前台文字阅读 [1]
                        alpha = if (isDark) 0.05f else 0.08f
                    },
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent // 隐藏灰色背景轨道，让视觉极其干净
            )
        }
    }
}

// 💡 智能药丸（SmartPill）动画外壳包装器
@Composable
fun SmartPillShell(state: PillState, text: String) {
    AnimatedVisibility(
        visible = state != PillState.HIDDEN,
        enter = fadeIn(animationSpec = tween(100)) + scaleIn(
            animationSpec = spring(
                // 💡 核心优化：将 MediumBouncy 改为 LowBouncy（低弹性）
                // 这样药丸会有一个非常高级、克制的微弱回弹，既保持了Q弹感，又绝对不会超出边界 [2.1.2]
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            initialScale = 0f
        ),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(
            animationSpec = tween(300),
            targetScale = 0f
        ) + shrinkHorizontally(
            animationSpec = tween(300)
        )
    ) {
        SmartPill(state = state, text = text)
    }
}

// 💡 智能药丸（SmartPill）内部实现组件
@Composable
fun SmartPill(state: PillState, text: String) {
    val pillWidthModifier = when (state) {
        PillState.HIDDEN -> Modifier.width(36.dp)
        PillState.POP_CIRCLE -> Modifier.width(36.dp)
        PillState.EXTEND_PILL -> Modifier.wrapContentWidth()
    }

    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = 450,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
            .then(pillWidthModifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart // 左侧物理锚点固定
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.fillMaxHeight()
        ) {
            // 左侧加载圆圈
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                LoadingIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White
                )
            }

            if (state == PillState.EXTEND_PILL) {
                Spacer(modifier = Modifier.width(6.dp))
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (slideInVertically { height -> height } + fadeIn(animationSpec = tween(150)))
                            .togetherWith(slideOutVertically { height -> -height } + fadeOut(animationSpec = tween(150)))
                    },
                    label = "PillText"
                ) { targetText ->
                    Text(
                        text = targetText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.clip(CircleShape)
                    )
                }
                // 💡 核心微调：仅在展开状态下，在文字右侧增加 4.dp 的物理占位 [2.1.2]
                // 这样右侧总间距变为（4.dp占位 + 8.dp外边距 = 12.dp），完美与左侧视觉重心实现对称！ [2.1.2]
                Spacer(modifier = Modifier.width(4.dp))
            }
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

/**
 * 💡 终极修复版：并行网络竞争（真正抢答、0毫秒等待、不等待慢速清理）
 */
private suspend fun raceFetchIpv6(apis: List<String>): InetAddress? {
    // 创建一个完全独立的临时协程作用域，脱离原先 coroutineScope 的强绑定限制
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val deferredResult = CompletableDeferred<InetAddress?>()
    val totalTasks = apis.size
    val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

    apis.forEach { api ->
        scope.launch {
            try {
                val ip = TcpPunchTransfer.getPublicIpv6FromApi(api)
                if (ip != null) {
                    deferredResult.complete(ip) // 🏆 胜者瞬间抢答！
                } else {
                    if (failedCount.incrementAndGet() >= totalTasks) {
                        deferredResult.complete(null)
                    }
                }
            } catch (e: Exception) {
                if (failedCount.incrementAndGet() >= totalTasks) {
                    deferredResult.complete(null)
                }
            }
        }
    }

    return try {
        // 💡 关键：只等抢答结果。一旦拿到，立刻通过 finally 返回，绝不拖泥带水
        deferredResult.await()
    } catch (e: Exception) {
        null
    } finally {
        // 💡 瞬间杀死并销毁整个临时作用域，慢速任务的清理工作在后台慢慢进行，绝不卡死主进程
        scope.cancel()
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

                // 💡 按压物理角度恢复：完全还原为用户首选、符合直觉的最早版本
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
        // 💡 物理深度透视：恢复最舒适平稳的 16f 视差 [1]
        this.cameraDistance = 16f * density
    }
}