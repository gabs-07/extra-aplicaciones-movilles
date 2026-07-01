package com.example.escomextra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class ServidorActivity : AppCompatActivity() {

    private val TAG = "BlueTube_Servidor"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var serverSocket: BluetoothServerSocket? = null
    private var outputStream: OutputStream? = null
    private var servidorCorriendo = false

    private lateinit var tvEstado: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnVisible: Button
    private lateinit var btnIniciar: Button
    private lateinit var etBuscar: EditText
    private lateinit var btnBuscar: Button
    private lateinit var progressBar: ProgressBar

    // Cache: si ya se descargo un video, no se vuelve a descargar
    private val cache = mutableMapOf<String, File>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Instancias de PipedAPI publicas actualizadas
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi-libre.kavin.rocks",
        "https://pipedapi.leptons.xyz",
        "https://piped-api.privacy.com.de",
        "https://pipedapi.adminforge.de",
        "https://api.piped.yt",
        "https://pipedapi.drgns.space",
        "https://pipedapi.owo.si",
        "https://pipedapi.darkness.services"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_servidor)

        tvEstado    = findViewById(R.id.tvEstado)
        tvLog       = findViewById(R.id.tvLog)
        scrollLog   = findViewById(R.id.scrollLog)
        btnVisible  = findViewById(R.id.btnVisible)
        btnIniciar  = findViewById(R.id.btnIniciar)
        etBuscar    = findViewById(R.id.etBuscar)
        btnBuscar   = findViewById(R.id.btnBuscar)
        progressBar = findViewById(R.id.progressBar)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        btnVisible.setOnClickListener { hacerseVisible() }
        btnIniciar.setOnClickListener { iniciarServidor() }
        btnBuscar.setOnClickListener { buscarYEnviar() }
        etBuscar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { buscarYEnviar(); true } else false
        }
    }

    // ── BLUETOOTH ─────────────────────────────────────────────────────────

    private fun hacerseVisible() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        if (tienePermiso()) startActivity(intent)
    }

    private fun iniciarServidor() {
        if (servidorCorriendo) {
            Toast.makeText(this, "Ya esta corriendo", Toast.LENGTH_SHORT).show()
            return
        }
        servidorCorriendo = true
        btnIniciar.isEnabled = false
        setEstado("Iniciando servidor...")

        scope.launch {
            try {
                serverSocket = if (tienePermiso())
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        BluetoothService.NOMBRE_SERVIDOR, BluetoothService.UUID_APP)
                else { servidorCorriendo = false; return@launch }

                agregarLog("Escuchando conexiones...")
                setEstado("Esperando cliente...")

                while (servidorCorriendo) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) { break } ?: break

                    val nombre = if (tienePermiso()) socket.remoteDevice?.name ?: "?" else "?"
                    outputStream = socket.outputStream
                    agregarLog("Cliente conectado: $nombre")
                    setEstado("Cliente conectado: $nombre")
                    escucharCliente(socket)
                }
            } catch (e: Exception) {
                agregarLog("Error: ${e.message}")
                setEstado("Error")
            } finally {
                servidorCorriendo = false
                withContext(Dispatchers.Main) { btnIniciar.isEnabled = true }
            }
        }
    }

    private suspend fun escucharCliente(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            while (true) {
                val linea = reader.readLine() ?: break
                val msg = JSONObject(linea)
                when (msg.optString("tipo")) {
                    "PING" -> {
                        outputStream?.write((JSONObject().apply {
                            put("tipo", "PONG")
                        }.toString() + "\n").toByteArray())
                        outputStream?.flush()
                    }
                    "BUSQUEDA" -> {
                        val query = msg.getString("query")
                        withContext(Dispatchers.Main) { etBuscar.setText(query) }
                        descargarYEnviar(query)
                    }
                }
            }
        } catch (e: Exception) {
            agregarLog("Cliente desconectado: ${e.message}")
        } finally {
            runCatching { socket.close() }
            outputStream = null
            setEstado("Cliente desconectado — esperando...")
        }
    }

    // ── BUSQUEDA Y DESCARGA ───────────────────────────────────────────────

    private fun buscarYEnviar() {
        val query = etBuscar.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Escribe algo para buscar", Toast.LENGTH_SHORT).show()
            return
        }
        if (outputStream == null) {
            Toast.makeText(this, "No hay cliente conectado", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch { descargarYEnviar(query) }
    }

    private suspend fun descargarYEnviar(query: String) {
        withContext(Dispatchers.Main) { progressBar.visibility = View.VISIBLE }
        agregarLog("Buscando: $query")

        try {
            val cacheKey = query.lowercase().trim()

            val archivoLocal = if (cache.containsKey(cacheKey)) {
                agregarLog("Usando cache para: $query")
                cache[cacheKey]!!
            } else {
                // Paso 1: Buscar el videoId en YouTube
                val videoId = buscarVideoId(query)
                if (videoId == null) {
                    agregarLog("No se encontraron resultados")
                    enviarControl("ERROR", "No se encontraron resultados")
                    return
                }
                agregarLog("VideoId: $videoId")

                // Paso 2: Obtener URL directa del stream via PipedAPI
                val streamUrl = obtenerStreamUrl(videoId)
                if (streamUrl == null) {
                    agregarLog("No se pudo obtener stream del video")
                    enviarControl("ERROR", "No se pudo obtener el stream")
                    return
                }
                agregarLog("Stream URL obtenida, descargando...")
                enviarControl("DESCARGANDO", query)

                // Paso 3: Descargar el archivo
                val archivo = File(cacheDir, "video_${cacheKey.hashCode()}.mp4")
                descargarArchivo(streamUrl, archivo)
                cache[cacheKey] = archivo
                agregarLog("Descarga completa: ${archivo.length() / 1024} KB")
                archivo
            }

            // Paso 4: Enviar por Bluetooth
            enviarArchivoPorBluetooth(archivoLocal, query)

        } catch (e: Exception) {
            agregarLog("Error: ${e.message}")
            enviarControl("ERROR", "Error: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        }
    }

    // ── YOUTUBE SEARCH (sin API key, scraping simple) ─────────────────────

    private fun buscarVideoId(query: String): String? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$q"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val html = conn.inputStream.bufferedReader().readText()
            // Extraer el primer videoId del HTML de resultados
            val regex = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
            val match = regex.find(html)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            agregarLog("Error buscando videoId: ${e.message}")
            null
        }
    }

    private fun obtenerStreamUrl(videoId: String): String? {
        for (instancia in pipedInstances) {
            try {
                agregarLog("Probando: $instancia")
                val url = "$instancia/streams/$videoId"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    // Headers que imitan un navegador real para evitar bloqueos
                    setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                    setRequestProperty("Origin", instancia.replace("api.", "").replace("pipedapi", "piped"))
                    setRequestProperty("Referer", "$instancia/")
                    instanceFollowRedirects = true
                }

                val code = conn.responseCode
                if (code != 200) {
                    agregarLog("HTTP $code en $instancia, probando siguiente...")
                    continue
                }

                val body = conn.inputStream.bufferedReader().readText()

                // Verificar que la respuesta es JSON valido antes de parsear
                if (!body.trimStart().startsWith("{")) {
                    agregarLog("Respuesta no es JSON en $instancia: ${body.take(50)}")
                    continue
                }

                val json = JSONObject(body)

                // Intentar primero audioStreams (solo audio, mas liviano para Bluetooth)
                // Si el modo bajo consumo estuviera activo usariamos solo audio
                // Por defecto usamos videoStreams de menor resolucion
                val videoStreams = json.optJSONArray("videoStreams")
                if (videoStreams != null && videoStreams.length() > 0) {
                    var mejorUrl: String? = null
                    var menorResolucion = Int.MAX_VALUE

                    for (i in 0 until videoStreams.length()) {
                        val stream = videoStreams.getJSONObject(i)
                        val quality = stream.optString("quality", "9999p")
                        val res = quality.replace("p", "").trim().toIntOrNull() ?: 9999
                        val streamUrl = stream.optString("url", "")
                        // Evitar streams video-only (sin audio)
                        val videoOnly = stream.optBoolean("videoOnly", false)
                        if (streamUrl.isNotEmpty() && !videoOnly && res < menorResolucion) {
                            menorResolucion = res
                            mejorUrl = streamUrl
                        }
                    }

                    if (mejorUrl != null) {
                        agregarLog("Stream encontrado: ${menorResolucion}p en $instancia")
                        return mejorUrl
                    }
                }

                // Si no hay videoStreams con audio, usar audioStreams
                val audioStreams = json.optJSONArray("audioStreams")
                if (audioStreams != null && audioStreams.length() > 0) {
                    val audioUrl = audioStreams.getJSONObject(0).optString("url", "")
                    if (audioUrl.isNotEmpty()) {
                        agregarLog("Usando audio stream de $instancia")
                        return audioUrl
                    }
                }

                agregarLog("Sin streams disponibles en $instancia")

            } catch (e: Exception) {
                agregarLog("Fallo $instancia: ${e.message?.take(60)}")
                continue
            }
        }
        return null
    }

    // ── DESCARGA Y ENVIO ──────────────────────────────────────────────────

    private fun descargarArchivo(url: String, destino: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 20000
        }
        conn.inputStream.use { input ->
            FileOutputStream(destino).use { output ->
                input.copyTo(output, bufferSize = 16384)
            }
        }
    }

    private suspend fun enviarArchivoPorBluetooth(archivo: File, titulo: String) {
        val tamano = archivo.length()
        agregarLog("Enviando $titulo (${tamano / 1024} KB) por Bluetooth...")
        enviarControl("STREAM_START", titulo)

        val buffer = ByteArray(8192)
        var enviados = 0L
        var ultimoPorcentaje = 0

        FileInputStream(archivo).use { fis ->
            while (true) {
                val leidos = fis.read(buffer)
                if (leidos == -1) break

                val chunk = JSONObject().apply {
                    put("tipo", "CHUNK")
                    put("d", android.util.Base64.encodeToString(
                        buffer.copyOf(leidos), android.util.Base64.NO_WRAP))
                }
                outputStream?.write((chunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                enviados += leidos

                val porcentaje = (enviados * 100 / tamano).toInt()
                if (porcentaje / 10 > ultimoPorcentaje / 10) {
                    ultimoPorcentaje = porcentaje
                    agregarLog("Enviando: $porcentaje%")
                }
                delay(5)
            }
        }

        val fin = JSONObject().apply {
            put("tipo", "STREAM_END")
            put("titulo", titulo)
        }
        outputStream?.write((fin.toString() + "\n").toByteArray(Charsets.UTF_8))
        outputStream?.flush()
        agregarLog("Envio completado")
        setEstado("Video enviado — listo para el siguiente")
    }

    private fun enviarControl(tipo: String, titulo: String) {
        try {
            val msg = JSONObject().apply {
                put("tipo", tipo)
                put("titulo", titulo)
            }
            outputStream?.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) { Log.e(TAG, "control: ${e.message}") }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private fun setEstado(txt: String) = runOnUiThread { tvEstado.text = "Estado: $txt" }

    private fun agregarLog(msg: String) {
        val hora = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            tvLog.append("[$hora] $msg\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        Log.d(TAG, msg)
    }

    private fun tienePermiso() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    else true

    override fun onDestroy() {
        super.onDestroy()
        servidorCorriendo = false
        scope.cancel()
        runCatching { serverSocket?.close() }
    }
}