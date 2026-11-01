package cl.apophis.simulator

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * Deterministic, offline equirectangular visual textures.
 * Artistic approximations, not spacecraft photographic maps.
 * Runs only when an OpenGL context is created.
 */
internal object PlanetTextures {
    private const val WIDTH = 384
    private const val HEIGHT = 192

    private fun hash(x: Int, y: Int, seed: Int): Double {
        var n = x * 374761393 + y * 668265263 + seed * 1442695041
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)).toLong() and 0x7fffffffL).toDouble() / 2147483647.0
    }

    private fun noise(x: Double, y: Double, seed: Int): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val u = fx * fx * (3.0 - 2.0 * fx)
        val v = fy * fy * (3.0 - 2.0 * fy)
        val a = hash(ix, iy, seed) * (1 - u) + hash(ix + 1, iy, seed) * u
        val b = hash(ix, iy + 1, seed) * (1 - u) + hash(ix + 1, iy + 1, seed) * u
        return a * (1 - v) + b * v
    }

    private fun fractal(x: Double, y: Double, seed: Int): Double {
        var amplitude = 0.5
        var frequency = 1.0
        var sum = 0.0
        var norm = 0.0
        repeat(5) { octave ->
            sum += amplitude * noise(x * frequency, y * frequency, seed + octave * 19)
            norm += amplitude
            amplitude *= 0.5
            frequency *= 2.0
        }
        return sum / norm
    }

    private fun rgb(r: Double, g: Double, b: Double): Int =
        Color.rgb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))

    fun create(name: String): Bitmap {
        val pixels = IntArray(WIDTH * HEIGHT)
        val seed = name.hashCode()
        for (y in 0 until HEIGHT) {
            val lat = (y + 0.5) / HEIGHT.toDouble() * PI - PI / 2
            for (x in 0 until WIDTH) {
                val u = x.toDouble() / WIDTH
                val n = fractal(u * 9.0, y.toDouble() / HEIGHT * 5.0, seed)
                val fine = fractal(u * 38.0, y.toDouble() / HEIGHT * 22.0, seed + 31)
                val bands = sin(lat * 38.0 + n * 3.0)
                val color = when (name) {
                    "Mercurio" -> {
                        val v = 65.0 + 130.0 * (0.68 * n + 0.32 * fine)
                        rgb(v + 9, v + 6, v)
                    }
                    "Venus" -> {
                        val v = 0.6 * n + 0.4 * sin(lat * 22.0 + n * 7.0) * 0.5 + 0.2
                        rgb(205 + 35 * v, 172 + 48 * v, 110 + 65 * v)
                    }
                    "Luna" -> {
                        val maria = if (n > 0.54) -40.0 else 0.0
                        val v = 95 + 90 * fine + maria
                        rgb(v + 5, v + 4, v)
                    }
                    "Marte" -> {
                        val dark = if (n > 0.57) 0.65 else 1.0
                        val ice = if (abs(lat) > 1.35) 1.0 else 0.0
                        rgb((125 + 115 * fine) * dark + 90 * ice, (54 + 70 * n) * dark + 110 * ice, (31 + 40 * fine) * dark + 145 * ice)
                    }
                    "Júpiter" -> {
                        val storm = exp(-((u - 0.68).pow(2) / 0.003 + (lat + 0.34).pow(2) / 0.012))
                        val v = bands * 20 + n * 28
                        rgb(185 + v + 45 * storm, 144 + v * 0.85 - 20 * storm, 108 + v * 0.65 - 28 * storm)
                    }
                    "Saturno" -> {
                        val v = bands * 13 + n * 20
                        rgb(206 + v, 187 + v, 137 + v)
                    }
                    "Urano" -> {
                        val v = bands * 3 + n * 14
                        rgb(132 + v, 206 + v, 213 + v)
                    }
                    "Neptuno" -> {
                        val storm = exp(-((u - 0.62).pow(2) / 0.008 + (lat - 0.15).pow(2) / 0.015))
                        val v = bands * 9 + n * 30
                        rgb(35 + v + storm * 20, 71 + v + storm * 30, 173 + v + storm * 20)
                    }
                    else -> rgb(128.0, 128.0, 128.0)
                }
                pixels[y * WIDTH + x] = color
            }
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }
}
