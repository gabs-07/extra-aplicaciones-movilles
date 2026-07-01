package com.example.escomextra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
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

    private lateinit var tvConexion: TextView
    private lateinit var btnConectar: Button
    private lateinit var etBusqueda: EditText
    private lateinit var btnBuscar: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEstadoDescarga: TextView
    private lateinit var layoutReproductor: LinearLayout
    private lateinit var tvTituloActual: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnRetroceder: Button
    private lateinit var btnAdelantar: Button
    private lateinit var seekBar: SeekBar
    private lateinit var cbModoPrivado: CheckBox
    private lateinit var cbBajoConsumo: CheckBox
    private lateinit var btnHistorial: Button

    private var conectado = false
    private val historial = mutableListOf<String>()

    // ── Recepcion y reproduccion de video ──
    private var archivoTemp: File? = null
    private var outputArchivo: FileOutputStream? = null
    private var mediaPlayer: MediaPlayer? = null
    private var reproduciendo = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cliente)
        initVistas()
        configurarListeners()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
    }

    private fun initVistas() {
        tvConexion         = findViewById(R.id.tvConexion)
        btnConectar        = findViewById(R.id.btnConectar)
        etBusqueda         = findViewById(R.id.etBusqueda)
        btnBuscar          = findViewById(R.id.btnBuscar)
        progressBar        = findViewById(R.id.progressBar)
        tvEstadoDescarga   = findViewById(R.id.tvEstadoDescarga)
        layoutReproductor  = findViewById(R.id.layoutReproductor)
        tvTituloActual     = findViewById(R.id.tvTituloActual)
        btnPlayPause       = findViewById(R.id.btnPlayPause)
        btnRetroceder      = findViewById(R.id.btnRetroceder)
        btnAdelantar       = findViewById(R.id.btnAdelantar)
        seekBar            = findViewById(R.id.seekBar)
        cbModoPrivado      = findViewById(R.id.cbModoPrivado)
        cbBajoConsumo      = findViewById(R.id.cbBajoConsumo)
        btnHistorial       = findViewById(R.id.btnHistorial)
    }

    private fun configurarListeners() {
        btnConectar.setOnClickListener { mostrarSelectorDispositivo() }

        etBusqueda.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { buscar(); true } else false
        }
        btnBuscar.setOnClickListener { buscar() }

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnRetroceder.setOnClickListener { retroceder() }
        btnAdelantar.setOnClickListener { adelantar() }

        cbModoPrivado.setOnCheckedChangeListener { _, checked ->
            toast(if (checked) "Modo privado activado" else "Modo privado desactivado")
        }
        cbBajoConsumo.setOnCheckedChangeListener { _, checked ->
            toast(if (checked) "Bajo consumo activado" else "Calidad normal")
        }
        btnHistorial.setOnClickListener { mostrarHistorial() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── BLUETOOTH ─────────────────────────────────────────────────────────

    private fun mostrarSelectorDispositivo() {
        if (!tienePermiso()) { toast("Permiso Bluetooth necesario"); return }
        val pareados = bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
        if (pareados.isEmpty()) { toast("No hay dispositivos vinculados"); return }

        val nombres = pareados.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Servidor")
            .setItems(nombres) { _, i -> conectarCon(pareados[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun conectarCon(dispositivo: BluetoothDevice) {
        setConexion("Conectando con ${dispositivo.name}...")
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
                    setConexion("Conectado: ${dispositivo.name}")
                    tvConexion.setTextColor(android.graphics.Color.parseColor("#4ECDC4"))
                    btnConectar.text = "Reconectar"
                    toast("Conectado al servidor")
                }

                enviarPeticion(JSONObject().apply { put("tipo", "PING") })
                escucharMensajes()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    setConexion("Error de conexion: ${e.message}")
                    toast("No se pudo conectar")
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
                setConexion("Conexion perdida")
                tvConexion.setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
                toast("Se perdio la conexion Bluetooth")
            }
        }
    }

    private suspend fun procesarMensaje(msg: JSONObject) {
        when (msg.optString("tipo")) {

            "PONG" -> withContext(Dispatchers.Main) { toast("Servidor respondiendo correctamente") }

            "DESCARGANDO" -> withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                tvEstadoDescarga.visibility = View.VISIBLE
                tvEstadoDescarga.text = "El servidor esta descargando: ${msg.optString("titulo")}"
            }

            "STREAM_START" -> {
                // Preparar archivo local para ir guardando los bytes que lleguen
                archivoTemp = File(cacheDir, "video_recibido.mp4")
                outputArchivo = FileOutputStream(archivoTemp)
                withContext(Dispatchers.Main) {
                    tvEstadoDescarga.text = "Recibiendo: ${msg.optString("titulo")}"
                }
            }

            "CHUNK" -> {
                val datos = android.util.Base64.decode(msg.getString("d"), android.util.Base64.DEFAULT)
                outputArchivo?.write(datos)
            }

            "STREAM_END" -> {
                outputArchivo?.flush()
                outputArchivo?.close()
                outputArchivo = null
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvEstadoDescarga.visibility = View.GONE
                    tvTituloActual.text = msg.optString("titulo")
                    layoutReproductor.visibility = View.VISIBLE
                    reproducirArchivoLocal()

                    if (!cbModoPrivado.isChecked) {
                        val titulo = msg.optString("titulo")
                        if (!historial.contains(titulo)) historial.add(0, titulo)
                    }
                }
            }

            "ERROR" -> withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                tvEstadoDescarga.visibility = View.GONE
                toast("Error: ${msg.optString("mensaje")}")
            }
        }
    }

    private fun enviarPeticion(peticion: JSONObject) {
        scope.launch {
            try {
                output?.write((peticion.toString() + "\n").toByteArray(Charsets.UTF_8))
                output?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Error al enviar peticion") }
            }
        }
    }

    // ── BUSQUEDA ──────────────────────────────────────────────────────────

    private fun buscar() {
        val query = etBusqueda.text.toString().trim()
        if (query.isEmpty()) { toast("Escribe algo para buscar"); return }
        if (!conectado) { toast("Primero conecta con el servidor"); return }

        progressBar.visibility = View.VISIBLE
        tvEstadoDescarga.visibility = View.VISIBLE
        tvEstadoDescarga.text = "Solicitando video al servidor..."

        enviarPeticion(JSONObject().apply {
            put("tipo", "BUSQUEDA")
            put("query", query)
        })
    }

    // ── REPRODUCTOR LOCAL (MediaPlayer, sin internet) ──────────────────────

    private fun reproducirArchivoLocal() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(archivoTemp!!.absolutePath)
            setOnPreparedListener {
                seekBar.max = duration
                start()
                reproduciendo = true
                btnPlayPause.text = "||"
                actualizarSeekBar()
            }
            setOnCompletionListener {
                reproduciendo = false
                btnPlayPause.text = ">"
            }
            prepareAsync()
        }
    }

    private fun actualizarSeekBar() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        seekBar.progress = it.currentPosition
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }, 500)
    }

    private fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                btnPlayPause.text = ">"
            } else {
                it.start()
                btnPlayPause.text = "||"
                actualizarSeekBar()
            }
        }
    }

    private fun retroceder() {
        mediaPlayer?.let { it.seekTo(maxOf(0, it.currentPosition - 10000)) }
        toast("-10 seg")
    }

    private fun adelantar() {
        mediaPlayer?.let { it.seekTo(minOf(it.duration, it.currentPosition + 10000)) }
        toast("+10 seg")
    }

    private fun mostrarHistorial() {
        if (historial.isEmpty()) { toast("Historial vacio"); return }
        AlertDialog.Builder(this)
            .setTitle("Historial de videos")
            .setItems(historial.toTypedArray()) { _, i ->
                etBusqueda.setText(historial[i])
                buscar()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private fun setConexion(txt: String) { tvConexion.text = txt }
    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun tienePermiso() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    else true

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching {
            mediaPlayer?.release()
            socket?.close()
        }
    }
}
