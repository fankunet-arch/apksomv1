package net.sushisom.sushivip.network

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 应用内置的极简 CONNECT 代理，等效于一份随 APK 分发的 hosts 文件。
 *
 * 【为什么要绕这一圈】
 * Android 没有向 WebView 暴露任何覆盖 DNS 的公开 API。Chromium 内核确实有
 * --host-resolver-rules 开关，但它只能通过命令行文件注入，应用无法给自己设置。
 *
 * 突破口在代理协议：HTTPS 走代理时，客户端发的是 `CONNECT 域名:443`，
 * **域名解析由代理端负责**。把代理放进应用自己的进程，这一步就归我们管了。
 *
 * 【相比直接把地址改成 IP 的好处】
 *   - TLS 的 SNI 与 HTTP 的 Host 头仍然是域名，服务端虚拟主机、Cookie 作用域、
 *     重定向全都不受影响 —— 这才是真正的 hosts 语义
 *   - 证书依旧按域名校验，不要求证书里包含 IP
 *   - 隧道内是端到端密文，本代理只做字节转发，既不参与也无法解密 TLS
 *
 * 【安全】只绑定回环地址，不对外暴露；未命中映射的域名原样交给系统解析。
 */
class HostsProxyServer(private val hosts: Map<String, String>) {

    companion object {
        private const val TAG = "HostsProxy"
        private const val CONNECT_TIMEOUT_MS = 8000
        /** 映射地址的连接超时取短一些，连不上要尽快退回 DNS 解析 */
        private const val MAPPED_CONNECT_TIMEOUT_MS = 3000
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_LINE = 8192
        private const val DEFAULT_PORT = 443
    }

    private var serverSocket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    /** 启动并返回实际监听的端口；启动失败返回 -1 */
    fun start(): Int = try {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serverSocket = socket
        running = true
        pool.execute { acceptLoop(socket) }
        Log.i(TAG, "本地代理已启动 port=${socket.localPort} 映射=$hosts")
        socket.localPort
    } catch (e: IOException) {
        Log.e(TAG, "本地代理启动失败", e)
        -1
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept 失败", e)
                return
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            // 丢弃剩余请求头，直到空行
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                // 站点全程 HTTPS，明文请求不应出现在这里
                Log.w(TAG, "非 CONNECT 请求，拒绝: $requestLine")
                writeStatus(client.getOutputStream(), "501 Not Implemented")
                return
            }

            val (host, port) = parseHostPort(parts[1])
            upstream = connect(host, port)
            writeStatus(client.getOutputStream(), "200 Connection Established")

            // 双向盲转发。上行放到线程池，下行占用当前线程直到连接结束。
            val up = upstream
            pool.execute { pipe(client.getInputStream(), up.getOutputStream()) }
            pipe(up.getInputStream(), client.getOutputStream())
        } catch (e: IOException) {
            Log.w(TAG, "转发失败: ${e.message}")
            runCatching { writeStatus(client.getOutputStream(), "502 Bad Gateway") }
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    /**
     * 优先连映射的 IP；连不上再退回系统解析。
     *
     * 这一层回退很重要：映射是编译期写死的，服务器一旦换了 IP，映射就成了
     * 错误答案。有了回退，只要设备 DNS 正常，过时的映射也不会把应用堵死。
     */
    private fun connect(host: String, port: Int): Socket {
        val mapped = hosts[host.lowercase()]
        if (mapped != null) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(mapped, port), MAPPED_CONNECT_TIMEOUT_MS)
                Log.i(TAG, "隧道建立(内置映射) $host:$port -> $mapped:$port")
                return socket
            } catch (e: IOException) {
                Log.w(TAG, "映射地址 $mapped:$port 连不上，退回系统解析: ${e.message}")
            }
        }
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        Log.i(TAG, "隧道建立(系统解析) $host:$port")
        return socket
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val idx = raw.lastIndexOf(':')
        return if (idx > 0) {
            raw.substring(0, idx) to (raw.substring(idx + 1).toIntOrNull() ?: DEFAULT_PORT)
        } else {
            raw to DEFAULT_PORT
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null   // 防御畸形请求
        }
    }

    private fun writeStatus(out: OutputStream, status: String) {
        out.write("HTTP/1.1 $status\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (e: IOException) {
            // 任意一端关闭都会走到这里，属正常结束
        }
    }
}
