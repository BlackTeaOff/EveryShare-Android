package com.blacktea.everyshare

import android.content.ClipData
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
// 💡 这两行是解决 “by” 代理报错（getValue/setValue）的核心导入！
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacktea.everyshare.core.ConnectionCodeUtil
import com.blacktea.everyshare.core.ProgressListener
import com.blacktea.everyshare.core.TcpPunchTransfer
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val TAG = "EveryShare"
    private val PORT = 50002 // 双方约定的碰撞端口

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
// 💡 获取协程作用域，用于在 onClick 里启动异步任务
        val scope = rememberCoroutineScope()

        // 💡 1. 声明状态变量 (有了上面的 getValue/setValue 导入，这里绝不会再报错)
        var myCode by remember { mutableStateOf("正在获取 IPv6 中...") }
        var remoteCode by remember { mutableStateOf("") }
        var statusText by remember { mutableStateOf("等待指令") }
        var progressPercent by remember { mutableStateOf(0) }
        var currentSpeed by remember { mutableStateOf(0.0) }
        var isTransferring by remember { mutableStateOf(false) }

        // 💡 2. 初始化：获取本机 IPv6 并生成 20MB 的虚拟测试文件
        LaunchedEffect(Unit) {
            thread {
                try {
                    val myIpv6: InetAddress? = TcpPunchTransfer.getActivePublicIpv6()
                    if (myIpv6 != null) {
                        myCode = ConnectionCodeUtil.generateCode(myIpv6.hostAddress,
                            50002
                        )
                        Log.i(TAG, "成功生成本端互传码: $myCode")
                    } else {
                        myCode = "未找到公网 IPv6，请检查 5G/Wi-Fi 网络"
                    }

                    // 自动生成测试文件，免去 Android 系统文件权限申请
                    val testFile = File(context.cacheDir, "everyshare_20MB_test.tmp")
                    if (!testFile.exists()) {
                        testFile.writeBytes(ByteArray(100 * 1024 * 1024))
                        Log.i(TAG, "已自动生成 500MB 测试文件")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "初始化失败", e)
                }
            }
        }

        // 💡 3. 界面布局 (纯代码声明式 UI)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "EveryShare 远程穿透测试", fontSize = 22.sp, style = MaterialTheme.typography.headlineMedium)

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (myCode.startsWith("everyshare://")) {
                        // 💡 在协程中异步执行复制动作，符合现代 Android 规范
                        scope.launch {
                            val clipData = ClipData.newPlainText("EveryShare Link", myCode)
                            clipboard.setClipEntry(clipData.toClipEntry())
                            android.widget.Toast.makeText(context, "互传码已复制到剪贴板！", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "📱 本机远程互传码 (点按可复制):", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = myCode, fontSize = 15.sp, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // 对方连接码输入
            OutlinedTextField(
                value = remoteCode,
                onValueChange = { remoteCode = it },
                label = { Text("请输入对方的远程互传码") },
                modifier = Modifier.fillMaxWidth()
            )

            // 状态显示
            Text(text = "当前状态: $statusText", fontSize = 16.sp)

            // 进度条与速度
            if (isTransferring) {
                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = String.format("速度: %.2f MB/s | 进度: %d%%", currentSpeed, progressPercent),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 💡 4. 发送按钮
            Button(
                onClick = {
                    if (remoteCode.isBlank()) return@Button
                    isTransferring = true
                    statusText = "正在进行 TCP 碰撞打洞..."

                    thread {
                        try {
                            val remoteInfo = ConnectionCodeUtil.parseCode(remoteCode)
                            val puncher = TcpPunchTransfer()

                            val listener = ProgressListener { name, read, total, speed ->
                                val percent = (read.toDouble() / total) * 100
                                progressPercent = percent.toInt()
                                currentSpeed = speed
                                statusText = "🚀 正在发送文件: $name"
                            }

                            // 💡 使用 this@MainActivity 明确指定 Activity 上下文，彻底解决作用域报错
                            val socket = puncher.connectByPunch(remoteInfo.ip, PORT, PORT)
                            if (socket != null) {
                                this@MainActivity.runOnUiThread { statusText = "✅ 通道打通！开始极速传输..." }

                                val fileToSend = File(context.cacheDir, "everyshare_20MB_test.tmp")
                                puncher.sendFile(socket, fileToSend, listener)

                                this@MainActivity.runOnUiThread { statusText = "🎉 文件发送成功！" }
                            } else {
                                this@MainActivity.runOnUiThread {
                                    statusText = "❌ 碰撞失败，请确认双方是否在 5秒内 同时点击"
                                    isTransferring = false
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "发送失败", e)
                            this@MainActivity.runOnUiThread {
                                statusText = "发送异常: ${e.message}"
                                isTransferring = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTransferring && remoteCode.isNotBlank()
            ) {
                Text("发送文件 (向导)")
            }

            // 💡 5. 接收按钮
            Button(
                onClick = {
                    if (remoteCode.isBlank()) return@Button
                    isTransferring = true
                    statusText = "正在进行 TCP 碰撞打洞..."

                    thread {
                        try {
                            val remoteInfo = ConnectionCodeUtil.parseCode(remoteCode)
                            val puncher = TcpPunchTransfer()

                            val listener = ProgressListener { name, read, total, speed ->
                                val percent = (read.toDouble() / total) * 100
                                progressPercent = percent.toInt()
                                currentSpeed = speed
                                statusText = "📥 正在接收文件: $name"
                            }

                            val socket = puncher.connectByPunch(remoteInfo.ip,
                                remoteInfo.port.toInt(), PORT)
                            if (socket != null) {
                                this@MainActivity.runOnUiThread { statusText = "✅ 通道打通！等待接收数据..." }

                                val saveDir = context.getExternalFilesDir(null)?.absolutePath ?: context.cacheDir.absolutePath
                                puncher.receiveFile(socket, saveDir, listener)

                                this@MainActivity.runOnUiThread { statusText = "🎉 文件接收成功！" }
                            } else {
                                this@MainActivity.runOnUiThread {
                                    statusText = "❌ 碰撞失败，请确认双方是否在 5秒内 同时点击"
                                    isTransferring = false
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "接收失败", e)
                            this@MainActivity.runOnUiThread {
                                statusText = "接收异常: ${e.message}"
                                isTransferring = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                enabled = !isTransferring && remoteCode.isNotBlank()
            ) {
                Text("接收文件 (向导)")
            }
        }
    }
}