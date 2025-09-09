package org.iligm.ginspeedwarning

import android.util.Log
import kotlin.math.*

/**
 * Детектор остановки (ZUPT - Zero Velocity Update)
 * Определяет моменты, когда транспортное средство остановлено
 */
class ZuptDetector {

    companion object {
        private const val TAG = "ZuptDetector"
        private const val MIN_STOP_DURATION = 2.0 // секунды
        private const val MAX_ACCELERATION_VARIANCE = 0.5 // м/с²
        private const val MAX_SPEED_FOR_STOP = 0.5 // м/с
        private const val WINDOW_SIZE = 20 // количество измерений для анализа
    }

    private val accelerationHistory = mutableListOf<Double>()
    private val speedHistory = mutableListOf<Double>()
    private var stopStartTime = 0L
    private var isStopped = false
    private var lastUpdateTime = 0L

    /**
     * Обновить детектор новыми данными
     * @param acceleration текущее ускорение в м/с²
     * @param speed текущая скорость в м/с
     * @return true, если обнаружена остановка
     */
    fun update(acceleration: Double, speed: Double): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Добавляем новые данные
        accelerationHistory.add(acceleration)
        speedHistory.add(speed)
        
        // Ограничиваем размер буфера
        if (accelerationHistory.size > WINDOW_SIZE) {
            accelerationHistory.removeAt(0)
        }
        if (speedHistory.size > WINDOW_SIZE) {
            speedHistory.removeAt(0)
        }

        // Нужно минимум 5 измерений для анализа
        if (accelerationHistory.size < 5) {
            return false
        }

        val wasStopped = isStopped
        isStopped = detectStop()

        // Логируем изменение состояния
        if (isStopped != wasStopped) {
            if (isStopped) {
                stopStartTime = currentTime
                Log.d(TAG, "Stop detected at speed ${speed}m/s")
            } else {
                val stopDuration = (currentTime - stopStartTime) / 1000.0
                Log.d(TAG, "Stop ended after ${stopDuration}s")
            }
        }

        lastUpdateTime = currentTime
        return isStopped
    }

    private fun detectStop(): Boolean {
        if (accelerationHistory.size < 5) return false

        // Проверяем скорость
        val avgSpeed = speedHistory.average()
        val maxSpeed = speedHistory.maxOrNull() ?: 0.0
        
        if (avgSpeed > MAX_SPEED_FOR_STOP || maxSpeed > MAX_SPEED_FOR_STOP * 2) {
            return false
        }

        // Проверяем дисперсию ускорения
        val accelVariance = calculateVariance(accelerationHistory)
        if (accelVariance > MAX_ACCELERATION_VARIANCE) {
            return false
        }

        // Проверяем, что ускорение близко к нулю
        val avgAcceleration = accelerationHistory.average()
        if (abs(avgAcceleration) > 0.3) {
            return false
        }

        // Проверяем продолжительность остановки
        if (isStopped) {
            val stopDuration = (System.currentTimeMillis() - stopStartTime) / 1000.0
            return stopDuration >= MIN_STOP_DURATION
        }

        return true
    }

    private fun calculateVariance(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    /**
     * Получить текущее состояние остановки
     */
    fun isStopped(): Boolean = isStopped

    /**
     * Получить продолжительность текущей остановки в секундах
     */
    fun getStopDuration(): Double {
        return if (isStopped) {
            (System.currentTimeMillis() - stopStartTime) / 1000.0
        } else {
            0.0
        }
    }

    /**
     * Сбросить детектор
     */
    fun reset() {
        accelerationHistory.clear()
        speedHistory.clear()
        isStopped = false
        stopStartTime = 0L
        lastUpdateTime = 0L
        Log.d(TAG, "ZUPT detector reset")
    }

    /**
     * Получить статистику для отладки
     */
    fun getStats(): String {
        val avgSpeed = if (speedHistory.isNotEmpty()) speedHistory.average() else 0.0
        val avgAccel = if (accelerationHistory.isNotEmpty()) accelerationHistory.average() else 0.0
        val accelVar = if (accelerationHistory.isNotEmpty()) calculateVariance(accelerationHistory) else 0.0
        
        return "Stopped: $isStopped, AvgSpeed: ${String.format("%.2f", avgSpeed)}m/s, AvgAccel: ${String.format("%.2f", avgAccel)}m/s², AccelVar: ${String.format("%.2f", accelVar)}"
    }
}
