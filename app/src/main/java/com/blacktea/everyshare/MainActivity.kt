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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
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
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

// 选项卡：传输、设置
enum class ActiveTab { TRANSFER, SETTINGS }

// 会话状态：空闲、连接中、会话中
enum class SessionState { IDLE, CONNECTING, ACTIVE }

class MainActivity : ComponentActivity() {
    private val TAG = "EveryShare"
    private val PORT = 50002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 状态栏边缘避让与深色文字图标
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = true

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EveryShareScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EveryShareScreen() {
        val context = LocalContext.current
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        // 状态管理 (.value 稳健语法)
        val currentTab = remember { mutableStateOf(ActiveTab.TRANSFER) }
        val sessionState = remember { mutableStateOf(SessionState.IDLE) }
        val isSenderRole = remember { mutableStateOf(true) }

        val myCode = remember { mutableStateOf("正在定位公网 IP...") }
        val myIpv6Text = remember { mutableStateOf("连接中...") }
        val myPort = remember { mutableStateOf(50002) }
        val remoteCode = remember { mutableStateOf("") }
        val useUdpSync = remember { mutableStateOf(true) }

        val statusText = remember { mutableStateOf("等待指令") }
        val progressPercent = remember { mutableStateOf(0) }
        val currentSpeed = remember { mutableStateOf(0.0) }
        val isTransferring = remember { mutableStateOf(false) }

        val showQrCode = remember { mutableStateOf(false) }
        val showLogs = remember { mutableStateOf(false) }
        val logList = remember { mutableStateListOf<String>() }
        val logListState = rememberLazyListState()

        val activeSocket = remember { mutableStateOf<Socket?>(null) }

        // 文件选择相关状态
        val selectedFileUri = remember { mutableStateOf<Uri?>(null) }
        val selectedFileName = remember { mutableStateOf("") }
        val selectedFileSize = remember { mutableStateOf(0L) }
        val isCachingFile = remember { mutableStateOf(false) }

        // 扫码器
        val scanLauncher = rememberLauncherForActivityResult(
            contract = ScanContract()
        ) { result ->
            if (result.contents != null) {
                remoteCode.value = result.contents
                statusText.value = "已扫描输入连接码"
            }
        }

        // 重置连接码
        fun resetMyConnectionCode() {
            this@MainActivity.runOnUiThread {
                myIpv6Text.value = "连接中..."
                myCode.value = "正在定位公网 IP..."
            }
            thread {
                try {
                    val myIpv6 = TcpPunchTransfer.getActivePublicIpv6()
                    if (myIpv6 != null) {
                        val randomPort = 50000 + (Math.random() * 10000).toInt()
                        myPort.value = randomPort
                        myCode.value = ConnectionCodeUtil.generateCode(myIpv6.hostAddress, randomPort)

                        // 自动进行 NAT6 探测检测
                        val localIps = TcpPunchTransfer.getLocalIPv6List()
                        val isNat6 = !localIps.contains(myIpv6.hostAddress.lowercase())

                        this@MainActivity.runOnUiThread {
                            myIpv6Text.value = if (isNat6) "${myIpv6.hostAddress}\n<NAT6 转换>" else myIpv6.hostAddress
                        }
                    } else {
                        this@MainActivity.runOnUiThread {
                            myIpv6Text.value = "未连接"
                            myCode.value = "定位失败"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重置连接码失败", e)
                    this@MainActivity.runOnUiThread {
                        myIpv6Text.value = "未连接"
                        myCode.value = "定位失败"
                    }
                }
            }
        }

        // 自动网络环境监听 [2]
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
            thread {
                try {
                    activeSocket.value?.close()
                    activeSocket.value = null
                } catch (ignored: Exception) {}
                this@MainActivity.runOnUiThread {
                    isTransferring.value = false
                    sessionState.value = SessionState.IDLE
                    statusText.value = "已返回首页"
                }
                resetMyConnectionCode()
            }
        }

        // 文件选择器
        val filePickerLauncher = rememberLauncherForActivityResult(
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
                        Log.e(TAG, "缓存文件失败", e)
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
                // 💡 页面级左右滑动过渡动画，标题与卡片整体划过
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
                                        // 标题左对齐 (与 KernelSU 一致)
                                        Text(
                                            text = "EveryShare",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                            textAlign = TextAlign.Start
                                        )

                                        // 说明文案卡片化 (💡 去掉投影效果，采用极轻扁平微细边框设计，高级极简)
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp) // 💡 彻底去掉阴影
                                        ) {
                                            Text(
                                                text = "仅支持公网 IPv6 地址互传，当前版本为 AI 测试版。\n有 BUG 可以提 Issue，但是不一定能改好。",
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(14.dp),
                                                textAlign = TextAlign.Start
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        // Bento Box 仪表盘 (1:1 绝对对称，长度 180.dp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(180.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            val isConnecting = myIpv6Text.value == "连接中..."
                                            val hasIpv6 = !isConnecting && myIpv6Text.value != "未连接"

                                            // 左侧大卡片 (KernelSU 描边对勾右下角对齐与裁剪)
                                            Card(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(24.dp))
                                                    // 💡 3D 物理下沉变暗 ＋ 倾斜动效，点击自动触发连接重置
                                                    .bounceClick { resetMyConnectionCode() },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (hasIpv6) Color(0xFFE3F9E4) else if (isConnecting) Color(0xFFFFF3E0) else Color(0xFFFFEBEE)
                                                )
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    // 💡 极致视觉：圆圈再度变大至 140.dp，描边加粗至 14.dp，移到【右下角】并进行边缘裁剪，对勾右腿略微加长
                                                    if (hasIpv6) {
                                                        Canvas(
                                                            modifier = Modifier
                                                                .size(140.dp) // 💡 大圆圈
                                                                .align(Alignment.BottomEnd) // 💡 精确对齐右下角
                                                                .offset(x = 35.dp, y = 35.dp) // 💡 右下角裁剪偏移
                                                        ) {
                                                            val strokeWidth = 14.dp.toPx() // 💡 极粗描边 14.dp
                                                            val circleColor = Color(0xFF6ACD73)

                                                            drawCircle(
                                                                color = circleColor,
                                                                radius = size.minDimension / 2 - strokeWidth,
                                                                style = Stroke(width = strokeWidth)
                                                            )
                                                            val path = Path().apply {
                                                                // 💡 完美收拢在 140.dp 内部，且右腿稍微拉长
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
                                                            text = if (hasIpv6) "服务中" else if (isConnecting) "连接中..." else "未连接",
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontSize = 20.sp,
                                                            color = Color.Black
                                                        )
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        Text(
                                                            text = myIpv6Text.value,
                                                            fontSize = 9.sp,
                                                            lineHeight = 13.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = Color.DarkGray
                                                        )
                                                    }
                                                }
                                            }

                                            // 右侧两个小卡片，底色改纯白，无阴影，使用内置 border 对齐，排版上下均匀分布
                                            Column(
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .bounceClick { resetMyConnectionCode() }, // 💡 点击快捷重置
                                                    shape = RoundedCornerShape(16.dp),
                                                    border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.2f)), // 💡 使用内置 border 彻底消除漏边
                                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(14.dp).fillMaxHeight(),
                                                        verticalArrangement = Arrangement.SpaceBetween // 💡 空间均匀分布
                                                    ) {
                                                        Text(text = "本地随机端口", fontSize = 13.sp, color = Color.Gray) // 💡 灰色字体加大到 13.sp
                                                        Text(text = myPort.value.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                                    }
                                                }
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .bounceClick { resetMyConnectionCode() }, // 💡 点击快捷重置
                                                    shape = RoundedCornerShape(16.dp),
                                                    border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.2f)),
                                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(14.dp).fillMaxHeight(),
                                                        verticalArrangement = Arrangement.SpaceBetween // 💡 空间均匀分布
                                                    ) {
                                                        Text(text = "传输同步策略", fontSize = 13.sp, color = Color.Gray) // 💡 灰色字体加大到 13.sp
                                                        Text(
                                                            text = if (useUdpSync.value) "UDP + TCP" else "纯 TCP",
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Black
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // 发送/接收选择卡片（集成 3D 物理下沉变暗 Bounce 动效）
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    // 💡 零延迟响应：3D 物理下沉阻尼弹簧动效
                                                    .bounceClick {
                                                        isSenderRole.value = true
                                                        sessionState.value = SessionState.CONNECTING
                                                        statusText.value = "请在下方进行连接配对"
                                                    },
                                                shape = RoundedCornerShape(20.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
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
                                                    // 💡 零延迟响应：3D 物理下沉阻尼弹簧动效
                                                    .bounceClick {
                                                        isSenderRole.value = false
                                                        sessionState.value = SessionState.CONNECTING
                                                        statusText.value = "请在下方进行连接配对"
                                                    },
                                                shape = RoundedCornerShape(20.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
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

                                        Card(
                                            modifier = Modifier.fillMaxWidth().border(1.dp, Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)).bounceClick {
                                                if (myCode.value.startsWith("everyshare://")) {
                                                    scope.launch {
                                                        val clipData = ClipData.newPlainText("EveryShare", myCode.value)
                                                        clipboard.setClipEntry(clipData.toClipEntry())
                                                        android.widget.Toast.makeText(context, "连接码已复制到剪贴板！", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(text = "📱 我的本端互传码 (点击自动复制):", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(text = myCode.value, fontSize = 14.sp)
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
                                                    }
                                                    scanLauncher.launch(options)
                                                },
                                                modifier = Modifier.height(56.dp)
                                            ) {
                                                Text("扫码")
                                            }
                                        }

                                        if (isSenderRole.value) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().border(1.dp, Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)).bounceClick {
                                                    if (!isCachingFile.value) filePickerLauncher.launch("*/*")
                                                },
                                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(text = "📂 选择要发送的文件 (点击浏览):", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (selectedFileName.value.isEmpty()) "点击选择手机上的任意文件" else "${selectedFileName.value} (${selectedFileSize.value / 1024 / 1024} MB)",
                                                        fontSize = 14.sp
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

                                                thread {
                                                    try {
                                                        val remoteInfo = ConnectionCodeUtil.parseCode(remoteCode.value)
                                                        val puncher = TcpPunchTransfer()

                                                        val listener = ProgressListener { name, read, total, speed ->
                                                            progressPercent.value = ((read.toDouble() / total) * 100).toInt()
                                                            currentSpeed.value = speed
                                                            statusText.value = if (isSenderRole.value) "正在发送: $name" else "正在接收: $name"
                                                        }

                                                        val socket = puncher.connectByPunch(remoteInfo.ip, remoteInfo.port, myPort.value, !isSenderRole.value, useUdpSync.value)
                                                        if (socket != null) {
                                                            activeSocket.value = socket
                                                            sessionState.value = SessionState.ACTIVE
                                                            this@MainActivity.runOnUiThread { statusText.value = "穿透成功！建立长生命周期会话。" }

                                                            if (isSenderRole.value) {
                                                                val tempCacheFile = File(context.cacheDir, "temp_upload.dat")
                                                                FileInputStream(tempCacheFile).use { fileInputStream ->
                                                                    puncher.sendFile(socket, fileInputStream, selectedFileName.value, selectedFileSize.value, listener)
                                                                }
                                                                this@MainActivity.runOnUiThread { statusText.value = "文件发送完成！" }
                                                            } else {
                                                                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                                                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                                                )
                                                                val everyShareDir = File(downloadDir, "EveryShare")
                                                                if (!everyShareDir.exists()) everyShareDir.mkdirs()
                                                                val saveDir = if (everyShareDir.exists()) everyShareDir.absolutePath else downloadDir.absolutePath

                                                                puncher.receiveFile(socket, saveDir, listener)
                                                                this@MainActivity.runOnUiThread { statusText.value = "🎉 接收成功！已存入公共下载目录" }
                                                            }
                                                        } else {
                                                            this@MainActivity.runOnUiThread {
                                                                statusText.value = "❌ 穿透失败，请确认双方同时开启"
                                                                isTransferring.value = false
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "会话执行失败", e)
                                                        this@MainActivity.runOnUiThread {
                                                            statusText.value = "异常中断: ${e.message}"
                                                            isTransferring.value = false
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().height(50.dp),
                                            enabled = remoteCode.value.isNotBlank() && (!isSenderRole.value || (selectedFileUri.value != null && !isCachingFile.value))
                                        ) {
                                            Text(text = "开始连接并进入会话", fontSize = 15.sp)
                                        }

                                        TextButton(onClick = { sessionState.value = SessionState.IDLE }) {
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
                                                }
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(text = "📂 选择另一个要发送的文件 (点击浏览):", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (selectedFileName.value.isEmpty()) "点击浏览" else "${selectedFileName.value} (${selectedFileSize.value / 1024 / 1024} MB)",
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    if (activeSocket.value == null || selectedFileUri.value == null || isCachingFile.value) return@Button
                                                    isTransferring.value = true
                                                    statusText.value = "正在通过已建立通道上传..."

                                                    thread {
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
                                                            this@MainActivity.runOnUiThread { statusText.value = "🎉 文件发送完成！通道继续保持。" }
                                                        } catch (e: Exception) {
                                                            this@MainActivity.runOnUiThread { statusText.value = "发送出错: ${e.message}" }
                                                        } finally {
                                                            this@MainActivity.runOnUiThread {
                                                                isTransferring.value = false
                                                                selectedFileUri.value = null
                                                                selectedFileName.value = ""
                                                            }
                                                        }
                                                    }
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
                                            onClick = {
                                                thread {
                                                    try {
                                                        activeSocket.value?.close()
                                                        activeSocket.value = null
                                                        this@MainActivity.runOnUiThread {
                                                            statusText.value = "已主动断开会话"
                                                            isTransferring.value = false
                                                            sessionState.value = SessionState.IDLE
                                                        }
                                                        resetMyConnectionCode()
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "断开连接失败", e)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("断开连接并结束会话")
                                        }
                                    }
                                }
                            } // 💡 这是关闭 if (targetTab == ActiveTab.TRANSFER) 的大括号
                        } else { // 💡 这是对应的 else (也就是 ActiveTab.SETTINGS)
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

                            Card(
                                modifier = Modifier.fillMaxWidth().border(1.dp, Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(text = "初次使用？", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = "这是一套去中心化的 P2P 文件传输工具，无任何流量中继开销，数据完全点对点直连传输。", fontSize = 12.sp, color = Color.Gray)
                                }
                            }

                            var showAboutDetail by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth().border(1.dp, Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)).clickable { showAboutDetail = !showAboutDetail },
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "ℹ️", fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = "关于 EveryShare", fontWeight = FontWeight.Bold)
                                        }
                                        Text(text = if (showAboutDetail) "收起" else "展开", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    if (showAboutDetail) {
                                        Column(modifier = Modifier.padding(top = 10.dp)) {
                                            Divider()
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(text = "版本：v0.3.0-alpha (Beta 1)", fontSize = 12.sp)
                                            Text(text = "开源协议：MIT License", fontSize = 12.sp)
                                            Text(text = "说明：本工具纯属学术与极客研究项目，不对使用过程中产生的流量和兼容性问题负责。", fontSize = 11.sp, color = Color.Gray)
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Button(
                                                onClick = {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/"))
                                                    context.startActivity(intent)
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("访问 GitHub 源码仓库")
                                            }
                                        }
                                    }
                                }
                            }
                        } // 💡 这是关闭 else (Settings) 的大括号
                    } // 💡 这是关闭 Column (C_SUB) 的大括号
                } // 💡 这是关闭 AnimatedContent 的大括号

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
                    // 💡 去掉内阴影，白底黑字扁平极细灰色边框，并使用 SelectionContainer 支持复制
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(135.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.dp, Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(8.dp)
                    ) {
                        SelectionContainer { // 💡 支持文本光标选择复制！
                            LazyColumn(
                                state = logListState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(logList) { logLine ->
                                    Text(
                                        text = logLine,
                                        color = if (logLine.contains("[ERROR]")) Color.Red else Color.Black,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            } // 💡 这是关闭主布局 Column (C_MAIN) 的大括号

            // 悬浮药丸底部导航栏
            val selectedIndex = if (currentTab.value == ActiveTab.TRANSFER) 0 else 1
            val indicatorOffset by animateDpAsState(targetValue = if (selectedIndex == 0) 4.dp else 92.dp)

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .width(180.dp)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                    .padding(4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // 背景滑动小药丸
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(84.dp)
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
                            .width(80.dp)
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
                            .width(80.dp)
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
            } // 💡 这是关闭底部导航栏 Box 的大括号
        } // 💡 这是关闭整个 EveryShareScreen 顶层 Box (B_ROOT) 的大括号
    } // 💡 这是关闭 EveryShareScreen 方法本身的大括号

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
                    // 💡 显式指定包名，彻底解决类名冲突 [1]
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

// 💡 极致 3D 物理下沉变暗动效 (3D Parallax/Tilt Card Effect)
// 手指按在哪个角，卡片就朝哪个方向发生 3D 偏转，松手时在弹簧阻尼下优雅、Q弹回弹
// 💡 核心优化：采用全新 getGestureState 手势机制，解决 Scroll 容器下的 Tap 冲突，确保按压 100% 极速响应！
inline fun Modifier.bounceClick(crossinline onClick: () -> Unit): Modifier = composed {
    var rotationX by remember { mutableStateOf(0f) }
    var rotationY by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }
    var alpha by remember { mutableStateOf(1f) }

    // 💡 物理阻尼弹簧效果
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
        // 💡 1. 核心修复：在进入 detectTapGestures 之前，先把 PointerInputScope 的 size 保存为局部变量
        val localSize = this.size

        detectTapGestures(
            onPress = { offset ->
                val centerX = localSize.component1() / 2f  // 💡 2. 使用 localSize.component1()
                val centerY = localSize.component2() / 2f  // 💡 3. 使用 localSize.component2()

                val deltaX = (offset.x - centerX) / centerX
                val deltaY = (offset.y - centerY) / centerY

                // 3D 偏转算法
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
            onTap = { onClick() }
        )
    }.graphicsLayer {
        this.rotationX = animRotationX
        this.rotationY = animRotationY
        this.scaleX = animScale
        this.scaleY = animScale
        this.alpha = animAlpha
        this.cameraDistance = 16f * density // 💡 3D 深度透视
    }
}