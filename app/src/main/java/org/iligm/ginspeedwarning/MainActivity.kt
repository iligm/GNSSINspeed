package org.iligm.ginspeedwarning

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.iligm.ginspeedwarning.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private var limit = 25
    private var serviceStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var searchTimeRunnable: Runnable? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* показать подсказку при отказе, если нужно */ }

    private val speedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Получен broadcast: ${intent?.action}")
            when (intent?.action) {
                SpeedService.ACTION_SPEED_UPDATE -> {
                    val speed = intent.getDoubleExtra("speed", 0.0)
                    val accuracy = intent.getFloatExtra("accuracy", 0f)
                    val satellites = intent.getIntExtra("satellites", 0)
                    val searchTime = intent.getLongExtra("searchTime", 0L)
                    val provider = intent.getStringExtra("provider") ?: "Неизвестно"
                    Log.d("MainActivity", "Обновление скорости: $speed км/ч от $provider")
                    updateSpeedDisplay(speed, accuracy, satellites, searchTime, provider)
                }
                SpeedService.ACTION_GPS_STATUS -> {
                    val status = intent.getStringExtra("status") ?: "Неизвестно"
                    val isActive = intent.getBooleanExtra("isActive", false)
                    val searchTime = intent.getLongExtra("searchTime", 0L)
                    val lastLocationTime = intent.getLongExtra("lastLocationTime", 0L)
                    Log.d("MainActivity", "Обновление GPS статуса: $status")
                    updateGpsStatus(status, isActive, searchTime, lastLocationTime)
                }
            }
        }
    }

    private fun hasLocationPerms(): Boolean {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        vb.limitSeek.max = 60
        vb.limitSeek.progress = limit
        vb.tvLimit.text = "Лимит: $limit км/ч"

        vb.limitSeek.setOnSeekBarChangeListener(SimpleSeekBar { p ->
            limit = p
            vb.tvLimit.text = "Лимит: $limit км/ч"
            sendBroadcast(Intent(SpeedService.ACTION_SET_LIMIT).putExtra("limit", limit))
        })

        vb.btnToggle.setOnClickListener {
            if (SpeedService.isRunning) {
                stopService(Intent(this, SpeedService::class.java))
                vb.btnToggle.text = "Старт"
                vb.tvSpeed.text = "0.0 км/ч"
                vb.tvGpsStatus.text = "GPS: Не активен"
                vb.tvGpsStatus.setTextColor(0xFF666666.toInt())
                vb.tvSearchTime.text = "Время поиска: --"
                vb.tvAccuracy.text = "Точность: --"
                vb.tvSatellites.text = "Спутники: --"
                
                // Останавливаем таймер
                stopSearchTimeTimer()
            } else {
                if (!hasLocationPerms()) {
                    // если ещё не дали разрешение → запросим и выйдем
                    permLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                    return@setOnClickListener
                }

                Log.d("MainActivity", "Запускаем сервис с лимитом: $limit")
                val intent = Intent(this, SpeedService::class.java).putExtra("limit", limit)
                if (Build.VERSION.SDK_INT >= 26) {
                    Log.d("MainActivity", "Запуск foreground сервиса")
                    startForegroundService(intent)
                } else {
                    Log.d("MainActivity", "Запуск обычного сервиса")
                    @Suppress("DEPRECATION")
                    startService(intent)
                }
                vb.btnToggle.text = "Стоп"
                
                // Сбрасываем все поля при запуске
                vb.tvSpeed.text = "Получение GPS..."
                vb.tvGpsStatus.text = "Запуск сервиса..."
                vb.tvGpsStatus.setTextColor(0xFF666666.toInt())
                vb.tvSearchTime.text = "Время поиска: 0с"
                vb.tvAccuracy.text = "Точность: --"
                vb.tvSatellites.text = "Спутники: --"
                
                // Запускаем таймер обновления времени поиска
                serviceStartTime = System.currentTimeMillis()
                startSearchTimeTimer()
                
                // Проверяем, что сервис действительно запустился через 2 секунды
                handler.postDelayed({
                    if (SpeedService.isRunning) {
                        Log.d("MainActivity", "Сервис запущен успешно")
                        // Если за 2 секунды не получили ни одного broadcast, используем статические переменные
                        if (vb.tvGpsStatus.text == "Запуск сервиса...") {
                            Log.w("MainActivity", "Не получаем broadcast от сервиса! Используем статические переменные")
                            updateFromStaticVariables()
                        }
                    } else {
                        Log.w("MainActivity", "Сервис не запустился!")
                        vb.tvGpsStatus.text = "Ошибка запуска сервиса"
                        vb.tvGpsStatus.setTextColor(0xFFFF0000.toInt())
                    }
                }, 2000)
            }
        }


        requestPermsIfNeeded()
    }

    private fun updateSpeedDisplay(speed: Double, accuracy: Float, satellites: Int, searchTime: Long, provider: String = "Неизвестно") {
        vb.tvSpeed.text = "%.1f км/ч".format(speed)
        
        // Обновляем точность GPS с информацией о провайдере
        if (accuracy > 0) {
            val accuracyText = when {
                accuracy <= 3 -> "Отличная (±${accuracy.toInt()}м)"
                accuracy <= 8 -> "Хорошая (±${accuracy.toInt()}м)"
                accuracy <= 20 -> "Средняя (±${accuracy.toInt()}м)"
                accuracy <= 50 -> "Низкая (±${accuracy.toInt()}м)"
                else -> "Очень низкая (±${accuracy.toInt()}м)"
            }
            val providerText = when (provider) {
                "gps" -> "GPS"
                "network" -> "Сеть"
                "passive" -> "Пассивный"
                else -> provider
            }
            vb.tvAccuracy.text = "Точность: $accuracyText ($providerText)"
        }
        
        // Обновляем количество спутников
        vb.tvSatellites.text = "Спутники: $satellites"
        
        // Обновляем время поиска
        val searchTimeSeconds = searchTime / 1000
        vb.tvSearchTime.text = "Время поиска: ${searchTimeSeconds}с"
    }

    private fun updateGpsStatus(status: String, isActive: Boolean, searchTime: Long, lastLocationTime: Long) {
        vb.tvGpsStatus.text = status
        vb.tvGpsStatus.setTextColor(
            if (isActive) 0xFF4CAF50.toInt() else 0xFF666666.toInt()
        )
        
        // Обновляем время поиска
        val searchTimeSeconds = searchTime / 1000
        vb.tvSearchTime.text = "Время поиска: ${searchTimeSeconds}с"
        
        // Показываем время последнего обновления
        if (lastLocationTime > 0) {
            val timeSinceLastUpdate = (System.currentTimeMillis() - lastLocationTime) / 1000
            if (timeSinceLastUpdate < 60) {
                vb.tvSearchTime.text = "Обновлено ${timeSinceLastUpdate}с назад"
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume() - регистрируем receiver")
        val filter = IntentFilter().apply {
            addAction(SpeedService.ACTION_SPEED_UPDATE)
            addAction(SpeedService.ACTION_GPS_STATUS)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(speedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(speedReceiver, filter)
            }
            Log.d("MainActivity", "Receiver зарегистрирован успешно")
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка регистрации receiver", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(speedReceiver)
        } catch (e: Exception) {
            // Receiver может быть не зарегистрирован
        }
        stopSearchTimeTimer()
    }

    private fun requestPermsIfNeeded() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        val need = perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need) permLauncher.launch(perms)
    }

    private fun startSearchTimeTimer() {
        stopSearchTimeTimer() // Останавливаем предыдущий таймер, если есть
        searchTimeRunnable = object : Runnable {
            override fun run() {
                if (SpeedService.isRunning && serviceStartTime > 0) {
                    val elapsed = (System.currentTimeMillis() - serviceStartTime) / 1000
                    vb.tvSearchTime.text = "Время поиска: ${elapsed}с"
                    
                    // Каждые 3 секунды проверяем статические переменные как fallback
                    if (elapsed % 3L == 0L) {
                        updateFromStaticVariables()
                    }
                    
                    handler.postDelayed(this, 1000) // Обновляем каждую секунду
                }
            }
        }
        handler.post(searchTimeRunnable!!)
    }

    private fun stopSearchTimeTimer() {
        searchTimeRunnable?.let { handler.removeCallbacks(it) }
        searchTimeRunnable = null
        serviceStartTime = 0L
    }

    private fun updateFromStaticVariables() {
        Log.d("MainActivity", "Обновляем UI из статических переменных сервиса")
        vb.tvGpsStatus.text = SpeedService.lastGpsStatus
        vb.tvGpsStatus.setTextColor(0xFFFF8800.toInt()) // Оранжевый цвет для fallback
        vb.tvSpeed.text = "%.1f км/ч".format(SpeedService.lastSpeed)
        vb.tvSearchTime.text = "Время поиска: ${SpeedService.lastSearchTime / 1000}с"
        if (SpeedService.lastAccuracy > 0) {
            val accuracyText = when {
                SpeedService.lastAccuracy <= 5 -> "Отличная (±${SpeedService.lastAccuracy.toInt()}м)"
                SpeedService.lastAccuracy <= 15 -> "Хорошая (±${SpeedService.lastAccuracy.toInt()}м)"
                else -> "Низкая (±${SpeedService.lastAccuracy.toInt()}м)"
            }
            vb.tvAccuracy.text = "Точность: $accuracyText"
        }
    }
}

// Упрощённый listener для SeekBar
fun interface SimpleSeekBar : android.widget.SeekBar.OnSeekBarChangeListener {
    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
        if (fromUser) invoke(p)
    }
    fun invoke(p: Int)
}
