package org.iligm.ginspeedwarning

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.*

/**
 * Обработчик сенсоров для получения продольного ускорения
 */
class SensorProcessor(
    private val sensorManager: SensorManager,
    private val onAccelerationUpdate: (acceleration: Double, dt: Double) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SensorProcessor"
        private const val MAX_ACCELERATION = 5.0 // м/с²
        private const val MIN_SPEED_FOR_BEARING = 2.0 // м/с
    }

    private var lastTimestamp = 0L
    private var lastBearing = 0.0
    private var lastSpeed = 0.0
    private var isInitialized = false

    // Матрица поворота для преобразования из системы координат устройства в мировую
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Буферы для сглаживания
    private val accelerationBuffer = mutableListOf<Double>()
    private val maxBufferSize = 5

    private var rotationSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null

    init {
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        
        if (rotationSensor == null) {
            Log.w(TAG, "Rotation vector sensor not available")
        }
        if (linearAccelSensor == null) {
            Log.w(TAG, "Linear acceleration sensor not available")
        }
    }

    fun start() {
        try {
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                Log.d(TAG, "Rotation sensor registered")
            }
            linearAccelSensor?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                Log.d(TAG, "Linear acceleration sensor registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sensors", e)
        }
    }

    fun stop() {
        try {
            sensorManager.unregisterListener(this)
            Log.d(TAG, "Sensors unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sensors", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                processRotationVector(event)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                processLinearAcceleration(event)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Игнорируем изменения точности
    }

    private fun processRotationVector(event: SensorEvent) {
        try {
            // Получаем матрицу поворота из вектора поворота
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            
            // Получаем углы ориентации
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // bearing (азимут) в радианах, от -π до π
            lastBearing = orientationAngles[0].toDouble()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing rotation vector", e)
        }
    }

    private fun processLinearAcceleration(event: SensorEvent) {
        if (event.timestamp == 0L) return

        val currentTime = event.timestamp
        val dt = if (lastTimestamp > 0) {
            (currentTime - lastTimestamp) / 1_000_000_000.0 // наносекунды в секунды
        } else {
            0.0
        }

        if (dt <= 0 || dt > 1.0) { // Игнорируем слишком большие интервалы
            lastTimestamp = currentTime
            return
        }

        lastTimestamp = currentTime

        // Получаем ускорение в системе координат устройства
        val accelX = event.values[0].toDouble()
        val accelY = event.values[1].toDouble()
        val accelZ = event.values[2].toDouble()

        // Преобразуем в мировую систему координат
        val worldAccel = transformToWorldCoordinates(accelX, accelY, accelZ)
        
        // Получаем продольное ускорение
        val forwardAcceleration = getForwardAcceleration(worldAccel)

        // Сглаживаем ускорение
        accelerationBuffer.add(forwardAcceleration)
        if (accelerationBuffer.size > maxBufferSize) {
            accelerationBuffer.removeAt(0)
        }

        val smoothedAcceleration = accelerationBuffer.average()

        // Ограничиваем ускорение
        val clampedAcceleration = smoothedAcceleration.coerceIn(-MAX_ACCELERATION, MAX_ACCELERATION)

        // Проверяем на выбросы
        if (abs(clampedAcceleration) > MAX_ACCELERATION * 0.8) {
            Log.w(TAG, "High acceleration detected: ${clampedAcceleration}m/s²")
        }

        if (isInitialized || abs(clampedAcceleration) > 0.1) {
            isInitialized = true
            onAccelerationUpdate(clampedAcceleration, dt)
        }
    }

    private fun transformToWorldCoordinates(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        return try {
            // Используем матрицу поворота для преобразования
            val worldX = rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z
            val worldY = rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z
            val worldZ = rotationMatrix[6] * x + rotationMatrix[7] * y + rotationMatrix[8] * z
            Triple(worldX, worldY, worldZ)
        } catch (e: Exception) {
            Log.e(TAG, "Error transforming coordinates", e)
            Triple(x, y, z) // Возвращаем исходные значения
        }
    }

    private fun getForwardAcceleration(worldAccel: Triple<Double, Double, Double>): Double {
        val (worldX, worldY, worldZ) = worldAccel

        return if (lastSpeed > MIN_SPEED_FOR_BEARING) {
            // При достаточной скорости используем курс от GPS
            worldX * cos(lastBearing) + worldY * sin(lastBearing)
        } else {
            // При малой скорости используем ориентацию устройства
            // Предполагаем, что устройство направлено в сторону движения
            worldX
        }
    }

    /**
     * Обновить курс от GPS
     */
    fun updateBearingFromGps(bearing: Double) {
        lastBearing = Math.toRadians(bearing)
    }

    /**
     * Обновить скорость для выбора метода определения курса
     */
    fun updateSpeed(speed: Double) {
        lastSpeed = speed
    }

    /**
     * Получить текущий курс в радианах
     */
    fun getCurrentBearing(): Double = lastBearing

    /**
     * Проверить, инициализирован ли процессор
     */
    fun isInitialized(): Boolean = isInitialized
}
