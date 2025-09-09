package org.iligm.ginspeedwarning

import android.util.Log

/**
 * Мини-фильтр Калмана для коррекции скорости между GPS обновлениями
 * 
 * Модель состояния: x = [v, b]^T
 * v_k = v_{k-1} + (a_meas - b)*dt + w_v
 * b_k = b_{k-1} + w_b
 * 
 * Измерение: z_k = v_gps + n
 * Где b — оценка смещения акселерометра, w_v, w_b — шумы модели
 */
class SpeedKF {
    companion object {
        private const val TAG = "SpeedKF"
    }

    // Состояние: x = [v, b]^T (скорость в м/с, смещение акселерометра в м/с²)
    private val x = doubleArrayOf(0.0, 0.0)   // v, bias
    
    // Ковариационная матрица P
    private val P = arrayOf(
        doubleArrayOf(1.0, 0.0),
        doubleArrayOf(0.0, 1.0)
    )

    // Параметры шумов (настраиваемые)
    var qV = 0.1     // процессный шум скорости (м²/с²)
    var qB = 1e-5    // дрейф смещения (м²/с⁴) — маленький
    var rGps = 0.25  // дисперсия измерения скорости GNSS (м²/с²) ~= (0.5 м/с)²

    // Статистика для отладки
    private var predictionCount = 0
    private var updateCount = 0

    /**
     * Предсказание состояния на основе измерения акселерометра
     * @param aMeas измеренное ускорение в м/с²
     * @param dt временной интервал в секундах
     */
    fun predict(aMeas: Double, dt: Double) {
        if (dt <= 0) return
        
        val v = x[0]
        val b = x[1]
        
        // Ограничиваем ускорение для стабильности
        val aClamped = aMeas.coerceIn(-5.0, 5.0)
        
        // x_k|k-1
        val vPred = v + (aClamped - b) * dt
        val bPred = b
        x[0] = vPred
        x[1] = bPred

        // P_k|k-1 = F P F^T + Q
        // F = [[1, -dt],
        //      [0,  1 ]]
        val F00 = 1.0
        val F01 = -dt
        val F10 = 0.0
        val F11 = 1.0

        val P00 = P[0][0]
        val P01 = P[0][1]
        val P10 = P[1][0]
        val P11 = P[1][1]

        val FP00 = F00 * P00 + F01 * P10
        val FP01 = F00 * P01 + F01 * P11
        val FP10 = F10 * P00 + F11 * P10
        val FP11 = F10 * P01 + F11 * P11

        val Ft00 = F00
        val Ft10 = F01
        val Ft01 = F10
        val Ft11 = F11

        val Pn00 = FP00 * Ft00 + FP01 * Ft10 + qV
        val Pn01 = FP00 * Ft01 + FP01 * Ft11
        val Pn10 = FP10 * Ft00 + FP11 * Ft10
        val Pn11 = FP10 * Ft01 + FP11 * Ft11 + qB

        P[0][0] = Pn00
        P[0][1] = Pn01
        P[1][0] = Pn10
        P[1][1] = Pn11

        predictionCount++
        
        // Ограничиваем скорость для стабильности
        x[0] = x[0].coerceIn(0.0, 50.0) // максимум 180 км/ч
    }

    /**
     * Обновление состояния на основе GPS измерения
     * @param vGps скорость от GPS в м/с
     * @param r дисперсия измерения (по умолчанию rGps)
     */
    fun updateWithGps(vGps: Double, r: Double = rGps) {
        // H = [1, 0], z = vGps
        val y = vGps - x[0]                   // innovation
        val S = P[0][0] + r                   // scalar
        val K0 = P[0][0] / S                  // K = P H^T S^-1
        val K1 = P[1][0] / S

        x[0] += K0 * y
        x[1] += K1 * y

        // P = (I - K H) P
        val P00 = P[0][0]
        val P01 = P[0][1]
        val P10 = P[1][0]
        val P11 = P[1][1]
        P[0][0] = (1 - K0) * P00
        P[0][1] = (1 - K0) * P01
        P[1][0] = -K1 * P00 + P10
        P[1][1] = -K1 * P01 + P11

        updateCount++
        
        Log.d(TAG, "GPS update: vGps=${vGps}m/s, innovation=${y}m/s, K0=$K0")
    }

    /**
     * Обновление с нулевой скоростью (ZUPT - Zero Velocity Update)
     * @param rZu дисперсия ZUPT измерения (по умолчанию очень маленькая)
     */
    fun updateZeroVelocity(rZu: Double = 1e-3) {
        // «жёстко» притягиваем скорость к 0 при стопе
        updateWithGps(0.0, rZu)
        Log.d(TAG, "ZUPT update applied")
    }

    /**
     * Получить текущее состояние
     * @return Pair(скорость в м/с, смещение акселерометра в м/с²)
     */
    fun state(): Pair<Double, Double> = x[0] to x[1]

    /**
     * Получить скорость в км/ч
     */
    fun getSpeedKmh(): Double = x[0] * 3.6

    /**
     * Получить смещение акселерометра в м/с²
     */
    fun getBias(): Double = x[1]

    /**
     * Сбросить фильтр
     */
    fun reset() {
        x[0] = 0.0
        x[1] = 0.0
        P[0][0] = 1.0
        P[0][1] = 0.0
        P[1][0] = 0.0
        P[1][1] = 1.0
        predictionCount = 0
        updateCount = 0
        Log.d(TAG, "Filter reset")
    }

    /**
     * Получить статистику для отладки
     */
    fun getStats(): String = "Predictions: $predictionCount, Updates: $updateCount, Speed: ${String.format("%.1f", getSpeedKmh())} km/h, Bias: ${String.format("%.3f", getBias())} m/s²"

    /**
     * Проверить, инициализирован ли фильтр
     */
    fun isInitialized(): Boolean = updateCount > 0

    /**
     * Получить неопределённость скорости
     */
    fun getSpeedUncertainty(): Double = kotlin.math.sqrt(P[0][0])
}
