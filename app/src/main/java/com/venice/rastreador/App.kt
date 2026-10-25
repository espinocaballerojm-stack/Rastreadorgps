package com.venice.rastreador

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Config {
    // CAMBIA esto por algo unico tuyo (solo letras/numeros/guiones)
    const val SECRETO = "cambia-esto-1234-xyz"
    const val BROKER = "tcp://broker.hivemq.com:1883"
}

class MainActivity : AppCompatActivity() {

    private lateinit var mapa: MapView
    private lateinit var info: TextView
    private val marcadores = HashMap<String, Marker>()
    private var client: MqttClient? = null
    private var primera = true

    private val lanzadorPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            lanzadorFondo.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private val lanzadorFondo = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        info = TextView(this).apply {
            setPadding(28, 28, 28, 28)
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF101820.toInt())
            text = "Conectando..."
        }

        val btn = Button(this).apply {
            text = "ACTIVAR RASTREO EN ESTE MOVIL"
        }
        btn.setOnClickListener { activar() }

        mapa = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }

        raiz.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        raiz.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        raiz.addView(mapa, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)

        conectarMqtt()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            lanzadorPermisos.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun conectarMqtt() {
        Thread {
            try {
                val c = MqttClient(
                    Config.BROKER, "vis-" + System.currentTimeMillis(), MemoryPersistence()
                )
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        runOnUiThread { info.text = "Reconectando..." }
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val id = topic.split("/").getOrNull(3) ?: return
                        val obj = JSONObject(String(message.payload))
                        runOnUiThread { pintar(id, obj) }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val o = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                }
                c.connect(o)
                c.subscribe("venice/tracker/${Config.SECRETO}/+/loc", 1)
                client = c
                runOnUiThread { info.text = "Conectado. Esperando moviles..." }
            } catch (e: Exception) {
                runOnUiThread { info.text = "Error de conexion: ${e.message}" }
            }
        }.start()
    }

    private fun pintar(id: String, obj: JSONObject) {
        val lat = obj.optDouble("lat", 0.0)
        val lon = obj.optDouble("lon", 0.0)
        val punto = GeoPoint(lat, lon)

        val m = marcadores.getOrPut(id) {
            Marker(mapa).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapa.overlays.add(this)
            }
        }
        m.title = obj.optString("modelo", id)
        m.position = punto
        m.snippet = "Bateria ${obj.optInt("bat")}% - ${hora(obj.optLong("ts", 0L))}"
        m.showInfoWindow()

        mapa.controller.animateTo(punto)
        if (primera) { mapa.controller.setZoom(17.0); primera = false }
        mapa.invalidate()

        info.text = "Moviles detectados: ${marcadores.size}\n" +
                "${m.title}\n" +
                "Lat $lat   Lon $lon\n" +
                "Bateria: ${obj.optInt("bat")}%\n" +
                "Actualizado: ${hora(obj.optLong("ts", 0L))}"
    }

    private fun hora(ts: Long): String =
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun activar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            lanzadorPermisos.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {}
        }
        ContextCompat.startForegroundService(this, Intent(this, TrackerService::class.java))
        Toast.makeText(this, "Rastreo ACTIVADO en este movil", Toast.LENGTH_LONG).show()
    }

    override fun onResume() { super.onResume(); mapa.onResume() }
    override fun onPause() { super.onPause(); mapa.onPause() }
    override fun onDestroy() {
        try { client?.disconnect() } catch (_: Exception) {}
        super.onDestroy()
    }
}

class TrackerService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private var client: MqttClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "sinid"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        crearCanal()
        arrancarForeground()
        conectarMqtt()
        empezarUbicacion()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CANAL, "Rastreo", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun notificacion(t: String): Notification =
        NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Ubicacion")
            .setContentText(t)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

    private fun arrancarForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notificacion("Activo"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notificacion("Activo"))
        }
    }

    private fun conectarMqtt() {
        scope.launch {
            while (isActive) {
                try {
                    val c = client
                    if (c == null || !c.isConnected) {
                        client = MqttClient(
                            Config.BROKER, "trk-$deviceId", MemoryPersistence()
                        ).apply {
                            val o = MqttConnectOptions().apply {
                                isAutomaticReconnect = true
                                isCleanSession = true
                                keepAliveInterval = 30
                                connectionTimeout = 10
                            }
                            connect(o)
                        }
                    }
                } catch (_: Exception) {}
                delay(10_000)
            }
        }
    }

    private fun empezarUbicacion() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L
        )
            .setMinUpdateIntervalMillis(30_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { subir(it) }
            }
        }

        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {}

        try {
            fused.lastLocation.addOnSuccessListener { it?.let { l -> subir(l) } }
        } catch (_: SecurityException) {}
    }

    private fun subir(loc: Location) {
        val json = JSONObject().apply {
            put("id", deviceId)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("prec", loc.accuracy.toDouble())
            put("bat", bateria())
            put("ts", System.currentTimeMillis())
            put("modelo", Build.MODEL)
        }.toString()

        scope.launch {
            try {
                val c = client
                if (c != null && c.isConnected) {
                    c.publish(
                        "venice/tracker/${Config.SECRETO}/$deviceId/loc",
                        MqttMessage(json.toByteArray()).apply {
                            qos = 1
                            isRetained = true
                        }
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun bateria(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        try { client?.disconnect() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CANAL = "rastreo_canal"
        const val NOTIF_ID = 4711
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ContextCompat.startForegroundService(
                    context, Intent(context, TrackerService::class.java)
                )
            }
        }
    }
}

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
