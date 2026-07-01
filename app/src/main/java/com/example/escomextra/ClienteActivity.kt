package com.example.escomextra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*

class ClienteActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var reader: BufferedReader? = null
    private var dispositivoActual: BluetoothDevice? = null

    private lateinit var tvConexion: TextView
    private lateinit var btnConectar: Button
    private lateinit var etBusqueda: EditText
    private lateinit var btnBuscar: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutReproductor: LinearLayout
    private lateinit var webViewVideo: WebView
    private lateinit var tvTituloActual: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnRetroceder: Button
    private lateinit var btnAdelantar: Button
    private lateinit var btnFavorito: Button
    private lateinit var seekBar: SeekBar
    private lateinit var cbModoPrivado: CheckBox
    private lateinit var cbBajoConsumo: CheckBox
    private lateinit var btnHistorial: Button

    private var conectado = false
    private var reproduciendo = false
    private val historial = mutableListOf<String>()
    private val favoritos = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cliente)
        initVistas()
        configurarWebView()
        configurarListeners()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
    }

    private fun initVistas() {
        tvConexion        = findViewById(R.id.tvConexion)
        btnConectar       = findViewById(R.id.btnConectar)
        etBusqueda        = findViewById(R.id.etBusqueda)
        btnBuscar         = findViewById(R.id.btnBuscar)
        progressBar       = findViewById(R.id.progressBar)
        layoutReproductor = findViewById(R.id.layoutReproductor)
        webViewVideo      = findViewById(R.id.webViewVideo)
        tvTituloActual    = findViewById(R.id.tvTituloActual)
        btnPlayPause      = findViewById(R.id.btnPlayPause)
        btnRetroceder     = findViewById(R.id.btnRetroceder)
        btnAdelantar      = findViewById(R.id.btnAdelantar)
        btnFavorito       = findViewById(R.id.btnFavorito)
        seekBar           = findViewById(R.id.seekBar)
        cbModoPrivado     = findViewById(R.id.cbModoPrivado)
        cbBajoConsumo     = findViewById(R.id.cbBajoConsumo)
        btnHistorial      = findViewById(R.id.btnHistorial)
    }

    private fun configurarWebView() {
        webViewVideo.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webViewVideo.webChromeClient = WebChromeClient()
        webViewVideo.webViewClient = WebViewClient()
    }

    private fun normalizarUrl(url: String): String {
        val regex = Regex("(?:v=|youtu\\.be/|embed/)([a-zA-Z0-9_-]{11})")
        val videoId = regex.find(url)?.groupValues?.get(1)
        return if (videoId != null)
            "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1"
        else url
    }

    private fun esFuenteSegura(url: String): Boolean {
        return url.contains("youtube.com") || url.contains("youtu.be")
    }

    private fun configurarListeners() {
        btnConectar.setOnClickListener { mostrarSelectorDispositivo() }

        etBusqueda.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { buscar(); true } else false
        }
        btnBuscar.setOnClickListener { buscar() }

        // Controles del reproductor via JavaScript sobre WebView
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnRetroceder.setOnClickListener { retroceder() }
        btnAdelantar.setOnClickListener { adelantar() }
        btnFavorito.setOnClickListener { toggleFavorito() }

        // Actualizar seekBar simulado cada segundo
        handler.post(object : Runnable {
            override fun run() {
                if (reproduciendo) {
                    webViewVideo.evaluateJavascript(
                        "(function(){ var v=document.querySelector('video'); " +
                        "return v ? JSON.stringify({cur:v.currentTime,dur:v.duration}) : '{}'; })()"
                    ) { result ->
                        try {
                            val json = JSONObject(result.trim('"').replace("\\\"", "\""))
                            val cur = json.optDouble("cur", 0.0)
                            val dur = json.optDouble("dur", 0.0)
                            if (dur > 0) {
                                seekBar.max = dur.toInt()
                                seekBar.progress = cur.toInt()
                            }
                        } catch (_: Exception) {}
                    }
                }
                handler.postDelayed(this, 1000)
            }
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    webViewVideo.evaluateJavascript(
                        "document.querySelector('video')?.seekTo($progress)", null)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        cbModoPrivado.setOnCheckedChangeListener { _, checked ->
            toast(if (checked) "🔒 Modo privado activado — no se guardará en historial"
                  else "🔓 Modo privado desactivado")
        }
        cbBajoConsumo.setOnCheckedChangeListener { _, checked ->
            toast(if (checked) "🔋 Bajo consumo: reproducción en calidad reducida"
                  else "⚡ Calidad normal restaurada")
        }
        btnHistorial.setOnClickListener { mostrarHistorial() }
    }

    // ── CONTROLES REPRODUCTOR ─────────────────────────────────────────────

    private fun togglePlayPause() {
        if (reproduciendo) {
            webViewVideo.evaluateJavascript("document.querySelector('video')?.pause()", null)
            btnPlayPause.text = "▶"
        } else {
            webViewVideo.evaluateJavascript("document.querySelector('video')?.play()", null)
            btnPlayPause.text = "▐▐"
        }
        reproduciendo = !reproduciendo
    }

    private fun retroceder() {
        webViewVideo.evaluateJavascript(
            "var v=document.querySelector('video'); if(v) v.currentTime=Math.max(0,v.currentTime-10)", null)
        toast("-10 seg")
    }

    private fun adelantar() {
        webViewVideo.evaluateJavascript(
            "var v=document.querySelector('video'); if(v) v.currentTime=Math.min(v.duration,v.currentTime+10)", null)
        toast("+10 seg")
    }

    private fun toggleFavorito() {
        val url = webViewVideo.url ?: return
        if (favoritos.contains(url)) {
            favoritos.remove(url)
            btnFavorito.text = "⭐ Fav"
            toast("Eliminado de favoritos")
        } else {
            favoritos.add(url)
            btnFavorito.text = "★ Guardado"
            toast("Agregado a favoritos")
        }
    }

    // ── BLUETOOTH ─────────────────────────────────────────────────────────

    private fun mostrarSelectorDispositivo() {
        if (!tienePermiso()) { toast("Permiso Bluetooth necesario"); return }
        val pareados = bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
        if (pareados.isEmpty()) {
            toast("No hay dispositivos vinculados. Ve a Ajustes > Bluetooth")
            return
        }
        val nombres = pareados.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Servidor")
            .setItems(nombres) { _, i -> conectarCon(pareados[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun conectarCon(dispositivo: BluetoothDevice) {
        dispositivoActual = dispositivo
        setConexion("Conectando con ${dispositivo.name}...", "#FFC107")
        progressBar.visibility = View.VISIBLE

        scope.launch {
            try {
                socket = if (tienePermiso())
                    dispositivo.createRfcommSocketToServiceRecord(BluetoothService.UUID_APP)
                else return@launch

                socket?.connect()
                output = socket?.outputStream
                reader = BufferedReader(InputStreamReader(socket?.inputStream))
                conectado = true

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    setConexion("✅ Conectado: ${dispositivo.name}", "#4CAF50")
                    btnConectar.text = "Reconectar"
                    toast("Conectado al servidor")
                }

                enviarPeticion(JSONObject().apply { put("tipo", "PING") })
                escucharMensajes()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    setConexion("❌ Error: ${e.message}", "#FF5252")
                    toast("No se pudo conectar — reintentando...")
                }
                // Reconexión automática tras 3 segundos
                delay(3000)
                if (!conectado && dispositivoActual != null) {
                    conectarCon(dispositivoActual!!)
                }
            }
        }
    }

    private suspend fun escucharMensajes() {
        try {
            while (conectado) {
                val linea = reader?.readLine() ?: break
                procesarMensaje(JSONObject(linea))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                conectado = false
                setConexion("🔴 Conexión perdida — reconectando...", "#FF5252")
                toast("Conexión Bluetooth perdida")
            }
            // Reconexión automática
            delay(3000)
            dispositivoActual?.let { conectarCon(it) }
        }
    }

    private suspend fun procesarMensaje(msg: JSONObject) {
        when (msg.optString("tipo")) {
            "PONG" -> withContext(Dispatchers.Main) {
                setConexion("✅ Servidor respondiendo", "#4CAF50")
            }
            "STREAM_URL" -> {
                val urlOriginal = msg.getString("url")

                // Req 4: Advertencia de fuente no segura
                if (!esFuenteSegura(urlOriginal)) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@ClienteActivity)
                            .setTitle("⚠️ Fuente no verificada")
                            .setMessage("El video proviene de una fuente no segura:\n$urlOriginal\n\n¿Deseas reproducirlo de todos modos?")
                            .setPositiveButton("Reproducir") { _, _ ->
                                cargarVideo(urlOriginal)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    return
                }

                withContext(Dispatchers.Main) { cargarVideo(urlOriginal) }
            }
        }
    }

    private fun cargarVideo(urlOriginal: String) {
        val url = normalizarUrl(urlOriginal)
        tvTituloActual.text = "Reproduciendo video recibido por Bluetooth"
        webViewVideo.loadUrl(url)
        layoutReproductor.visibility = View.VISIBLE
        reproduciendo = true
        btnPlayPause.text = "▐▐"

        // Guardar en historial solo si no es modo privado
        if (!cbModoPrivado.isChecked) {
            if (!historial.contains(urlOriginal)) historial.add(0, urlOriginal)
            if (historial.size > 50) historial.removeAt(historial.size - 1)
        }
        toast("Video recibido por Bluetooth")
    }

    private fun enviarPeticion(peticion: JSONObject) {
        scope.launch {
            try {
                output?.write((peticion.toString() + "\n").toByteArray(Charsets.UTF_8))
                output?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Error al enviar") }
            }
        }
    }

    // ── BUSQUEDA ──────────────────────────────────────────────────────────

    private fun buscar() {
        val query = etBusqueda.text.toString().trim()
        if (query.isEmpty()) { toast("Escribe algo para buscar"); return }
        if (!conectado) { toast("Primero conecta con el servidor"); return }

        enviarPeticion(JSONObject().apply {
            put("tipo", "BUSQUEDA")
            put("query", query)
        })
        toast("Búsqueda enviada al servidor")
    }

    private fun mostrarHistorial() {
        val items = (if (favoritos.isNotEmpty())
            listOf("⭐ FAVORITOS:") + favoritos + listOf("📋 HISTORIAL:") + historial
        else listOf("📋 HISTORIAL:") + historial).toTypedArray()

        if (historial.isEmpty() && favoritos.isEmpty()) {
            toast("No hay historial ni favoritos"); return
        }
        AlertDialog.Builder(this)
            .setTitle("Historial y Favoritos")
            .setItems(items) { _, i ->
                val url = items[i]
                if (!url.startsWith("⭐") && !url.startsWith("📋")) {
                    cargarVideo(url)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private fun setConexion(txt: String, color: String) {
        tvConexion.text = txt
        tvConexion.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun tienePermiso() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    else true

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { socket?.close() }
    }
}
