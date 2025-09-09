package org.iligm.ginspeedwarning

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.SensorManager
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
    private lateinit var sensorManager: SensorManager

    private var limitKmh = 25.0
    private var lastSpeed = 0.0
    private var lastBeepAt = 0L
    private var gpsSearchStartTime = 0L
    private var isGpsEnabled = false
    private var lastLocationTime = 0L
    private var satellitesCount = 0
    private var statusUpdateHandler: android.os.Handler? = null
    private var statusUpdateRunnable: Runnable? = null

    // Фильтр Калмана и сенсоры
    private lateinit var speedKF: SpeedKF
    private lateinit var sensorProcessor: SensorProcessor
    private lateinit var zuptDetector: ZuptDetector
    private var lastGpsSpeed = 0.0
    private var lastGpsAccuracy = 0f
    private var lastGpsTime = 0L

    private val hysteresis = 1.0
    private val minBeepIntervalMs = 4000L

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastLocationTime = System.currentTimeMillis()
            val vRaw = (loc.speed * 3.6).coerceAtLeast(0.0) // м/с → км/ч
            val acc = loc.accuracy
            val provider = loc.provider
            
            Log.d("SpeedService", "Получено местоположение от $provider: точность ${acc}м, скорость ${vRaw}км/ч")
            
            // Проверяем, что это GPS и точность достаточна
            if (provider != LocationManager.GPS_PROVIDER) {
                Log.w("SpeedService", "Игнорируем местоположение от $provider - используем только GPS")
                return
            }
            
            // Минимальная точность для отображения скорости - 20 метров
            val minAccuracy = 20f
            if (acc > minAccuracy) {
                Log.d("SpeedService", "Точность GPS недостаточна: ${acc}м > ${minAccuracy}м, продолжаем поиск")
                sendGpsStatusUpdate("Поиск точного GPS... (±${acc.toInt()}м)", false)
                return
            }
            
            // Обновляем фильтр Калмана с GPS данными
            val gpsSpeedMs = loc.speed.toDouble().coerceAtLeast(0.0) // скорость в м/с
            val speedVariance = calculateSpeedVariance(acc, loc.time - lastGpsTime)
            
            speedKF.updateWithGps(gpsSpeedMs, speedVariance)
            
            // Обновляем курс для процессора сенсоров
            if (loc.hasBearing()) {
                sensorProcessor.updateBearingFromGps(loc.bearing.toDouble())
            }
            
            // Обновляем скорость для детектора остановки
            sensorProcessor.updateSpeed(gpsSpeedMs)
            
            // Сохраняем GPS данные
            lastGpsSpeed = vRaw
            lastGpsAccuracy = acc
            lastGpsTime = loc.time
            
            // Получаем скорость от фильтра Калмана
            val currentSpeed = speedKF.getSpeedKmh()
            lastSpeed = currentSpeed

            // Обновляем статические переменные
            SpeedService.lastSpeed = currentSpeed
            SpeedService.lastAccuracy = acc

            // Отправляем обновление скорости
            sendSpeedUpdate(currentSpeed, acc, satellitesCount)
            
            Log.d("SpeedService", "GPS update: raw=${vRaw}km/h, filtered=${currentSpeed}km/h, accuracy=${acc}m")
        }

        private fun calculateSpeedVariance(accuracy: Float, timeInterval: Long): Double {
            // Базовая дисперсия на основе точности GPS
            val baseVariance = (accuracy / 3.0) * (accuracy / 3.0) // примерная оценка
            
            // Увеличиваем дисперсию при больших интервалах между обновлениями
            val timeFactor = if (timeInterval > 0) {
                kotlin.math.min(timeInterval / 1000.0, 5.0) // максимум 5x
            } else {
                1.0
            }
            
            return baseVariance * timeFactor
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
        sensorManager = getSystemService(SensorManager::class.java)

        val filter = IntentFilter(ACTION_SET_LIMIT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ — обязателен флаг, если таргетишь 34+
            registerReceiver(limitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(limitReceiver, filter)
        }

        // Инициализируем фильтр Калмана и сенсоры
        initializeKalmanFilter()
        
        Log.d("SpeedService", "Запуск foreground сервиса")
        startForeground(NOTIF_ID, buildNotification("GPS готов"))
        gpsSearchStartTime = System.currentTimeMillis()
        
        // Отправляем начальный статус сразу после создания сервиса
        sendGpsStatusUpdate("GPS сервис запущен", false)
        
        // Запускаем периодическую отправку статуса
        startPeriodicStatusUpdates()
        
        requestLocation()
    }

    private fun initializeKalmanFilter() {
        Log.d("SpeedService", "Инициализация фильтра Калмана")
        
        // Создаем фильтр Калмана
        speedKF = SpeedKF()
        
        // Создаем детектор остановки
        zuptDetector = ZuptDetector()
        
        // Создаем процессор сенсоров
        sensorProcessor = SensorProcessor(sensorManager) { acceleration, dt ->
            onAccelerationUpdate(acceleration, dt)
        }
        
        // Запускаем сенсоры
        sensorProcessor.start()
        
        Log.d("SpeedService", "Фильтр Калмана инициализирован")
    }

    private fun onAccelerationUpdate(acceleration: Double, dt: Double) {
        if (!isRunning) return
        
        // Обновляем фильтр Калмана
        speedKF.predict(acceleration, dt)
        
        // Получаем текущую скорость от фильтра
        val currentSpeed = speedKF.getSpeedKmh()
        
        // Обновляем детектор остановки
        val isStopped = zuptDetector.update(acceleration, currentSpeed / 3.6) // конвертируем в м/с
        
        // Если обнаружена остановка, применяем ZUPT
        if (isStopped) {
            speedKF.updateZeroVelocity()
        }
        
        // Обновляем скорость в статических переменных
        SpeedService.lastSpeed = currentSpeed
        
        // Отправляем обновление скорости
        sendSpeedUpdate(currentSpeed, lastGpsAccuracy, satellitesCount)
        
        Log.d("SpeedService", "Acceleration update: ${acceleration}m/s², Speed: ${currentSpeed}km/h, Stopped: $isStopped")
    }

    private fun sendSpeedUpdate(speed: Double, accuracy: Float, satellites: Int) {
        // Обновляем уведомление
        updateNotification("Скорость: %.1f км/ч (GPS ±${accuracy.toInt()}м)".format(Locale.getDefault(), speed))
        maybeAlert(speed)
        
        // Отправляем broadcast с обновленной скоростью
        val intent = Intent(ACTION_SPEED_UPDATE).apply {
            putExtra("speed", speed)
            putExtra("accuracy", accuracy)
            putExtra("satellites", satellites)
            putExtra("searchTime", System.currentTimeMillis() - gpsSearchStartTime)
            putExtra("provider", "gps")
            flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            setPackage(packageName)
        }
        sendBroadcast(intent)
        
        // Отправляем статус GPS
        sendGpsStatusUpdate("GPS активен (±${accuracy.toInt()}м)", true)
    }

    private fun requestLocation() {
        Log.d("SpeedService", "requestLocation() вызван")
        val fineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            PackageManager.PERMISSION_GRANTED
        }
        
        Log.d("SpeedService", "Разрешения - FINE: $fineLocation, BACKGROUND: $backgroundLocation")
        
        if (fineLocation == PackageManager.PERMISSION_GRANTED) {
            Log.d("SpeedService", "Разрешение на GPS есть")
            
            // Сначала пробуем получить последнее известное местоположение только от GPS
            tryGetLastKnownLocation()
            
            // Проверяем доступность только GPS провайдера
            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            
            Log.d("SpeedService", "GPS провайдер: $gpsEnabled")
            
            if (gpsEnabled) {
                Log.d("SpeedService", "Запрашиваем обновления только от GPS")
                sendGpsStatusUpdate("Поиск GPS сигнала...", false)
                
                try {
                    // Запрашиваем обновления только от GPS
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,   // 1 секунда
                        1f,      // 1 метр
                        locListener,
                        Looper.getMainLooper()
                    )
                    Log.d("SpeedService", "GPS обновления запрошены")
                    
                } catch (e: SecurityException) {
                    Log.e("SpeedService", "Ошибка безопасности при запросе GPS", e)
                    sendGpsStatusUpdate("Ошибка доступа к GPS - проверьте разрешения", false)
                } catch (e: Exception) {
                    Log.e("SpeedService", "Ошибка при запросе GPS", e)
                    sendGpsStatusUpdate("Ошибка GPS - проверьте настройки", false)
                }
            } else {
                Log.w("SpeedService", "GPS провайдер отключен")
                sendGpsStatusUpdate("GPS отключен - включите GPS в настройках", false)
            }
        } else {
            Log.w("SpeedService", "Нет разрешения на GPS")
            sendGpsStatusUpdate("Нет разрешения на GPS - предоставьте доступ", false)
        }
    }
    
    private fun tryGetLastKnownLocation() {
        Log.d("SpeedService", "Пробуем получить последнее известное местоположение от GPS")
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                val lastLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastLocation != null) {
                    Log.d("SpeedService", "Найдено последнее GPS местоположение: ${lastLocation.latitude}, ${lastLocation.longitude}")
                    // Используем последнее GPS местоположение для быстрого старта
                    locListener.onLocationChanged(lastLocation)
                    return
                }
            }
            Log.d("SpeedService", "Последнее GPS местоположение не найдено")
        } catch (e: SecurityException) {
            Log.w("SpeedService", "Нет разрешения на получение последнего GPS местоположения", e)
        } catch (e: Exception) {
            Log.w("SpeedService", "Ошибка при получении последнего GPS местоположения", e)
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
                    val searchTime = (System.currentTimeMillis() - gpsSearchStartTime) / 1000
                    val currentStatus = if (lastLocationTime > 0) {
                        val kfSpeed = if (::speedKF.isInitialized) speedKF.getSpeedKmh() else 0.0
                        val kfBias = if (::speedKF.isInitialized) speedKF.getBias() else 0.0
                        "GPS+KF: ${String.format("%.1f", kfSpeed)}км/ч (bias: ${String.format("%.2f", kfBias)})"
                    } else {
                        "Поиск GPS сигнала... (${searchTime}с)"
                    }
                    sendGpsStatusUpdate(currentStatus, lastLocationTime > 0)
                    
                    // Если долго не получаем GPS, пробуем принудительно обновить
                    if (lastLocationTime == 0L && searchTime > 10) {
                        Log.d("SpeedService", "Долго нет GPS, пробуем принудительное обновление")
                        tryGetLastKnownLocation()
                    }
                    
                    // Логируем статистику фильтра Калмана
                    if (::speedKF.isInitialized) {
                        Log.d("SpeedService", "KF Stats: ${speedKF.getStats()}")
                        Log.d("SpeedService", "ZUPT Stats: ${zuptDetector.getStats()}")
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
        
        // Останавливаем сенсоры
        try {
            sensorProcessor.stop()
        } catch (e: Exception) {
            Log.e("SpeedService", "Ошибка остановки сенсоров", e)
        }
        
        try {
            unregisterReceiver(limitReceiver)
        } catch (_: Exception) { }
        lm.removeUpdates(locListener)
    }

    override fun onBind(i: Intent?): IBinder? = null
}
