package org.iligm.ginspeedwarning

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class SpeedService : Service() {

    companion object {
        const val ACTION_SET_LIMIT = "org.iligm.ginspeedwarning.SET_LIMIT"
        const val ACTION_SPEED_UPDATE = "org.iligm.ginspeedwarning.SPEED_UPDATE"
        const val ACTION_GPS_STATUS = "org.iligm.ginspeedwarning.GPS_STATUS"
        const val NOTIF_ID = 1
        @Volatile var isRunning = false
        @Volatile var lastGpsStatus = "Не запущен"
        @Volatile var lastSpeed = 0.0
        @Volatile var lastAccuracy = 0f
        @Volatile var lastSearchTime = 0L
    }

    private lateinit var nm: NotificationManager
    private lateinit var lm: LocationManager

    private var limitKmh = 25.0
    private var lastSpeed = 0.0
    private var lastBeepAt = 0L
    private var gpsSearchStartTime = 0L
    private var isGpsEnabled = false
    private var lastLocationTime = 0L
    private var satellitesCount = 0
    private var statusUpdateHandler: android.os.Handler? = null
    private var statusUpdateRunnable: Runnable? = null

    private val hysteresis = 1.0
    private val minBeepIntervalMs = 4000L

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastLocationTime = System.currentTimeMillis()
            val vRaw = (loc.speed * 3.6).coerceAtLeast(0.0) // м/с → км/ч
            val acc = loc.accuracy
            val provider = loc.provider
            
            Log.d("SpeedService", "Получено местоположение от $provider: точность ${acc}м, скорость ${vRaw}км/ч")
            
            // Более агрессивная фильтрация для быстрого отклика
            val alpha = when {
                acc <= 3   -> 0.9  // Отличная точность - почти полностью доверяем
                acc <= 8   -> 0.8  // Хорошая точность
                acc <= 20  -> 0.6  // Средняя точность
                acc <= 50  -> 0.4  // Низкая точность
                else       -> 0.2  // Очень низкая точность
            }
            
            val v = alpha * vRaw + (1 - alpha) * lastSpeed
            lastSpeed = v

            // Обновляем статические переменные
            SpeedService.lastSpeed = v
            SpeedService.lastAccuracy = acc

            // Обновляем уведомление с информацией о провайдере
            val providerText = when (provider) {
                LocationManager.GPS_PROVIDER -> "GPS"
                LocationManager.NETWORK_PROVIDER -> "Сеть"
                LocationManager.PASSIVE_PROVIDER -> "Пассивный"
                else -> provider
            }
            updateNotification("Скорость: %.1f км/ч ($providerText)".format(Locale.getDefault(), v))
            maybeAlert(v)
            
            // Отправляем broadcast с обновленной скоростью
            val intent = Intent(ACTION_SPEED_UPDATE).apply {
                putExtra("speed", v)
                putExtra("accuracy", acc)
                putExtra("satellites", satellitesCount)
                putExtra("searchTime", System.currentTimeMillis() - gpsSearchStartTime)
                putExtra("provider", provider)
                flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                setPackage(packageName)
            }
            sendBroadcast(intent)
            
            // Отправляем статус GPS с информацией о провайдере
            sendGpsStatusUpdate("GPS активен ($providerText)", true)
        }

        @Deprecated("Deprecated in Android 30+, но нужен для совместимости с minSdk 24")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            val statusText = when (status) {
                android.location.LocationProvider.AVAILABLE -> "Доступен"
                android.location.LocationProvider.OUT_OF_SERVICE -> "Недоступен"
                android.location.LocationProvider.TEMPORARILY_UNAVAILABLE -> "Временно недоступен"
                else -> "Неизвестно"
            }
            sendGpsStatusUpdate("GPS: $statusText", false)
        }
        
        override fun onProviderEnabled(provider: String) {
            isGpsEnabled = true
            sendGpsStatusUpdate("GPS включен", false)
        }
        
        override fun onProviderDisabled(provider: String) {
            isGpsEnabled = false
            sendGpsStatusUpdate("GPS отключен", false)
        }
    }

    private val limitReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_SET_LIMIT) {
                limitKmh = i.getIntExtra("limit", limitKmh.toInt()).toDouble()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d("SpeedService", "onCreate() вызван")
        isRunning = true
        nm = getSystemService(NotificationManager::class.java)
        lm = getSystemService(LocationManager::class.java)

        val filter = IntentFilter(ACTION_SET_LIMIT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ — обязателен флаг, если таргетишь 34+
            registerReceiver(limitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(limitReceiver, filter)
        }

        Log.d("SpeedService", "Запуск foreground сервиса")
        startForeground(NOTIF_ID, buildNotification("Готов"))
        gpsSearchStartTime = System.currentTimeMillis()
        
        // Отправляем начальный статус сразу после создания сервиса
        sendGpsStatusUpdate("Сервис запущен", false)
        
        // Запускаем периодическую отправку статуса
        startPeriodicStatusUpdates()
        
        requestLocation()
    }

    private fun requestLocation() {
        Log.d("SpeedService", "requestLocation() вызван")
        val fineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            PackageManager.PERMISSION_GRANTED
        }
        
        Log.d("SpeedService", "Разрешения - FINE: $fineLocation, COARSE: $coarseLocation, BACKGROUND: $backgroundLocation")
        
        if (fineLocation == PackageManager.PERMISSION_GRANTED || coarseLocation == PackageManager.PERMISSION_GRANTED) {
            Log.d("SpeedService", "Разрешение на GPS есть")
            
            // Сначала пробуем получить последнее известное местоположение
            tryGetLastKnownLocation()
            
            // Проверяем доступность провайдеров
            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val passiveEnabled = lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
            
            Log.d("SpeedService", "Провайдеры - GPS: $gpsEnabled, Network: $networkEnabled, Passive: $passiveEnabled")
            
            if (gpsEnabled || networkEnabled) {
                Log.d("SpeedService", "Запрашиваем обновления от всех доступных провайдеров")
                sendGpsStatusUpdate("Поиск GPS...", false)
                
                try {
                    // Запрашиваем обновления от GPS (приоритетный)
                    if (gpsEnabled) {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000L,   // 1 секунда
                            1f,      // 1 метр
                            locListener,
                            Looper.getMainLooper()
                        )
                        Log.d("SpeedService", "GPS обновления запрошены")
                    }
                    
                    // Запрашиваем обновления от Network (быстрее, но менее точно)
                    if (networkEnabled) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            2000L,   // 2 секунды
                            10f,     // 10 метров
                            locListener,
                            Looper.getMainLooper()
                        )
                        Log.d("SpeedService", "Network обновления запрошены")
                    }
                    
                    // Запрашиваем обновления от Passive (если доступен)
                    if (passiveEnabled) {
                        lm.requestLocationUpdates(
                            LocationManager.PASSIVE_PROVIDER,
                            5000L,   // 5 секунд
                            50f,     // 50 метров
                            locListener,
                            Looper.getMainLooper()
                        )
                        Log.d("SpeedService", "Passive обновления запрошены")
                    }
                    
                    Log.d("SpeedService", "Все обновления запрошены успешно")
                } catch (e: SecurityException) {
                    Log.e("SpeedService", "Ошибка безопасности при запросе GPS", e)
                    sendGpsStatusUpdate("Ошибка доступа к GPS", false)
                } catch (e: Exception) {
                    Log.e("SpeedService", "Ошибка при запросе GPS", e)
                    sendGpsStatusUpdate("Ошибка GPS", false)
                }
            } else {
                Log.w("SpeedService", "Все провайдеры отключены")
                sendGpsStatusUpdate("GPS отключен", false)
            }
        } else {
            Log.w("SpeedService", "Нет разрешения на GPS")
            sendGpsStatusUpdate("Нет разрешения на GPS", false)
        }
    }
    
    private fun tryGetLastKnownLocation() {
        Log.d("SpeedService", "Пробуем получить последнее известное местоположение")
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            
            for (provider in providers) {
                if (lm.isProviderEnabled(provider)) {
                    val lastLocation = lm.getLastKnownLocation(provider)
                    if (lastLocation != null) {
                        Log.d("SpeedService", "Найдено последнее местоположение от $provider: ${lastLocation.latitude}, ${lastLocation.longitude}")
                        // Используем последнее местоположение для быстрого старта
                        locListener.onLocationChanged(lastLocation)
                        return
                    }
                }
            }
            Log.d("SpeedService", "Последнее местоположение не найдено")
        } catch (e: SecurityException) {
            Log.w("SpeedService", "Нет разрешения на получение последнего местоположения", e)
        } catch (e: Exception) {
            Log.w("SpeedService", "Ошибка при получении последнего местоположения", e)
        }
    }

    private fun sendGpsStatusUpdate(status: String, isActive: Boolean) {
        Log.d("SpeedService", "Отправляем GPS статус: $status, активен: $isActive")
        
        // Обновляем статические переменные для прямого доступа
        lastGpsStatus = status
        lastSearchTime = System.currentTimeMillis() - gpsSearchStartTime
        
        val intent = Intent(ACTION_GPS_STATUS).apply {
            putExtra("status", status)
            putExtra("isActive", isActive)
            putExtra("searchTime", lastSearchTime)
            putExtra("lastLocationTime", lastLocationTime)
            // Добавляем флаги для лучшей доставки
            flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            // Явно указываем компонент для доставки
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d("SpeedService", "Broadcast отправлен: $ACTION_GPS_STATUS")
    }

    private fun startPeriodicStatusUpdates() {
        statusUpdateHandler = android.os.Handler(Looper.getMainLooper())
        statusUpdateRunnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    val currentStatus = if (lastLocationTime > 0) {
                        "GPS активен (${(System.currentTimeMillis() - lastLocationTime) / 1000}с назад)"
                    } else {
                        "Поиск GPS... (${(System.currentTimeMillis() - gpsSearchStartTime) / 1000}с)"
                    }
                    sendGpsStatusUpdate(currentStatus, lastLocationTime > 0)
                    
                    // Если долго не получаем GPS, пробуем принудительно обновить
                    if (lastLocationTime == 0L && (System.currentTimeMillis() - gpsSearchStartTime) > 10000) {
                        Log.d("SpeedService", "Долго нет GPS, пробуем принудительное обновление")
                        tryGetLastKnownLocation()
                    }
                    
                    statusUpdateHandler?.postDelayed(this, 2000) // Каждые 2 секунды
                }
            }
        }
        statusUpdateHandler?.post(statusUpdateRunnable!!)
    }

    private fun stopPeriodicStatusUpdates() {
        statusUpdateRunnable?.let { statusUpdateHandler?.removeCallbacks(it) }
        statusUpdateHandler = null
        statusUpdateRunnable = null
    }

    private fun maybeAlert(v: Double) {
        val now = System.currentTimeMillis()
        if (v > (limitKmh + hysteresis) && now - lastBeepAt > minBeepIntervalMs) {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            lastBeepAt = now
        }
    }

    private fun buildNotification(text: String): Notification {
        val chId = "speed_ch"
        // Канал нужен только на 26+
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(chId) == null) {
            val channel = NotificationChannel(
                chId,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        Log.d("SpeedService", "onStartCommand() вызван")
        limitKmh = i?.getIntExtra("limit", limitKmh.toInt())?.toDouble() ?: limitKmh
        Log.d("SpeedService", "Лимит установлен: $limitKmh")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SpeedService", "onDestroy() вызван")
        isRunning = false
        stopPeriodicStatusUpdates()
        try {
            unregisterReceiver(limitReceiver)
        } catch (_: Exception) { }
        lm.removeUpdates(locListener)
    }

    override fun onBind(i: Intent?): IBinder? = null
}
