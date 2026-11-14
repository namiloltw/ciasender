package com.nord.ciasender

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import java.io.BufferedOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SendService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var items: List<Item> = emptyList()
        @Volatile var listener: (() -> Unit)? = null
        val logText = StringBuilder()
        private val main = Handler(Looper.getMainLooper())
        fun log(s: String) {
            synchronized(logText) { logText.append(s).append('\n') }
            main.post { listener?.invoke() }
        }
    }

    private var server: ServerSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var stopped = false
    @Volatile private var lastActivity = 0L
    private val active = AtomicInteger(0)
    private val done = ConcurrentHashMap.newKeySet<Int>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { log("Cancelado"); shutdown(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val ip = intent?.getStringExtra("ip")
        if (ip == null) { stopSelf(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        running = true
        stopped = false
        done.clear()
        synchronized(logText) { logText.setLength(0) }
        val list = items
        thread { work(ip, list) }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("send", "Envío", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "send")
        } else Notification.Builder(this)
        return b.setContentTitle("Enviando a la 3DS")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true).build()
    }

    private fun work(ip: String, list: List<Item>) {
        try {
            val local = localIp()
            if (local == null) { log("No encuentro la IP WiFi del móvil. ¿Estás conectado al WiFi?"); return }
            acquireLocks()
            val ss = ServerSocket(0)
            server = ss
            lastActivity = System.currentTimeMillis()
            thread { acceptLoop(ss, list) }

            val urls = list.mapIndexed { i, it -> "http://$local:${ss.localPort}/$i/${Uri.encode(it.name)}" }
                .joinToString("\n")
            log("Conectando con $ip:5000…")
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 5000), 5000)
                val data = urls.toByteArray()
                val out = s.getOutputStream()
                out.write(ByteBuffer.allocate(4).putInt(data.size).array())
                out.write(data)
                out.flush()
                s.soTimeout = 5000
                try { s.getInputStream().read() } catch (_: Exception) {}
            }
            log("Lista enviada (${list.size}). La 3DS está descargando…")

            while (!stopped && done.size < list.size) {
                Thread.sleep(500)
                if (active.get() == 0 && System.currentTimeMillis() - lastActivity > 180_000) {
                    log("Sin actividad, parando"); break
                }
            }
            if (done.size >= list.size) log("Terminado ✔")
        } catch (e: Exception) {
            log("Error: ${e.message}. Comprueba que FBI está en Remote Install > Receive URLs.")
        } finally {
            shutdown()
        }
    }

    private fun acceptLoop(ss: ServerSocket, list: List<Item>) {
        try {
            while (!stopped) {
                val c = ss.accept()
                thread { handle(c, list) }
            }
        } catch (_: Exception) {}
    }

    private fun handle(c: Socket, list: List<Item>) {
        active.incrementAndGet()
        lastActivity = System.currentTimeMillis()
        try {
            c.use {
                val reader = c.getInputStream().bufferedReader()
                val req = reader.readLine() ?: return
                while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
                val parts = req.split(" ")
                val idx = parts.getOrNull(1)?.split("/")?.getOrNull(1)?.toIntOrNull()
                val item = idx?.let { list.getOrNull(it) }
                val out = BufferedOutputStream(c.getOutputStream(), 65536)
                if (item == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val len = if (item.size > 0) "Content-Length: ${item.size}\r\n" else ""
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n${len}Connection: close\r\n\r\n".toByteArray())
                if (parts[0] == "HEAD") { out.flush(); return }

                log("Enviando ${item.name}")
                var sent = 0L
                var lastPct = 0
                contentResolver.openInputStream(item.uri)?.use { ins ->
                    val buf = ByteArray(65536)
                    while (!stopped) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        lastActivity = System.currentTimeMillis()
                        if (item.size > 0) {
                            val p = (sent * 100 / item.size).toInt()
                            if (p >= lastPct + 10) { lastPct = p - p % 10; log("  ${item.name}: $p%") }
                        }
                    }
                    out.flush()
                }
                if (item.size <= 0 || sent >= item.size) { done.add(idx!!); log("✔ ${item.name}") }
            }
        } catch (e: Exception) {
            log("Conexión interrumpida: ${e.message}")
        } finally {
            active.decrementAndGet()
            lastActivity = System.currentTimeMillis()
        }
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ciasender:send").apply { acquire(3 * 60 * 60 * 1000L) }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "ciasender:wifi").apply { acquire() }
    }

    private fun shutdown() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        running = false
        main.post { listener?.invoke() }
        @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }
}
