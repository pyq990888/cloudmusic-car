package com.paopao.music.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.random.Random

/**
 * ES 2.0 keeps the same visual on Android 9 car units. Render at a capped resolution
 * and 30 fps; the Compose lyric layer remains at the native display resolution.
 */
@Composable
internal fun InkFlowBackground(
    running: Boolean,
    foreground: Boolean,
    backdrop: ImmersiveBackdrop,
    artwork: Bitmap? = null,
    beat: Int = 0,
    energy: Float = 0.5f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = remember(context) { InkSurface(context) }
    SideEffect { view.ink.targetEnergy = energy }
    val firstBeat = remember(view) { intArrayOf(beat) }
    LaunchedEffect(view, beat) {
        // A new lyric line sends one swell through the backdrop; the first composition is silent.
        if (beat != firstBeat[0] && running && foreground) view.ink.kick()
        firstBeat[0] = Int.MIN_VALUE
    }
    DisposableEffect(view, foreground) {
        if (foreground) view.onResume() else view.onPause()
        onDispose { view.onPause() }
    }
    LaunchedEffect(view, foreground, running, backdrop, artwork) {
        view.ink.backdrop = backdrop.ordinal
        view.ink.artwork = artwork
        if (!foreground) return@LaunchedEffect
        view.requestRender()
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                if (now - last >= 32_000_000L) {
                    view.ink.advance(((now - last) / 1_000_000_000f).coerceAtMost(0.1f))
                    last = now
                    view.requestRender()
                }
            }
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        onRelease = { it.onPause() },
    )
}

private class InkSurface(context: Context) : GLSurfaceView(context) {
    val ink = InkRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(ink)
        renderMode = RENDERMODE_WHEN_DIRTY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val scale = minOf(1f, 640f / maxOf(w, h))
        holder.setFixedSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }
}

private class InkRenderer : GLSurfaceView.Renderer {
    @Volatile private var elapsed = 12f
    @Volatile var backdrop = 0
    @Volatile var artwork: Bitmap? = null
    /** 1 while a lyric is being sung, lower in gaps and when paused; eased so changes never pop. */
    @Volatile var targetEnergy = 0.5f
    @Volatile private var energy = 0.5f
    @Volatile private var pulse = 0f
    private var kickAge = Float.MAX_VALUE
    private var energyLocation = -1
    private var pulseLocation = -1
    private var program = 0
    private var timeLocation = -1
    private var sizeLocation = -1
    private var themeLocation = -1
    private var noiseTexture = 0
    private var imageTexture = 0
    private var uploadedImage: Bitmap? = null
    private var imagePresentLocation = -1
    private var imageSizeLocation = -1
    private var width = 1
    private var height = 1
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    fun kick() { kickAge = 0f }

    /** Flow speeds up with energy and surges briefly on each pulse, so the backdrop breathes with the song. */
    fun advance(seconds: Float) {
        energy += (targetEnergy - energy) * (seconds * 1.6f).coerceAtMost(1f)
        // Ease into the swell instead of jumping to it; a one-frame jump reads as a flash.
        kickAge += seconds
        pulse = if (kickAge < PulseAttack) {
            val x = kickAge / PulseAttack
            x * x * (3f - 2f * x)
        } else exp(-(kickAge - PulseAttack) * 1.9f)
        elapsed += seconds * (0.55f + 0.75f * energy + 1.6f * pulse)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.025f, 0.032f, 0.033f, 1f)
        program = 0
        runCatching {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = try {
                compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            } catch (error: Exception) {
                GLES20.glDeleteShader(vertex)
                throw error
            }
            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, vertex)
            GLES20.glAttachShader(linked, fragment)
            GLES20.glBindAttribLocation(linked, 0, "aPosition")
            GLES20.glLinkProgram(linked)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetProgramInfoLog(linked)
                GLES20.glDeleteProgram(linked)
                error(message)
            }
            program = linked
            timeLocation = GLES20.glGetUniformLocation(program, "uTime")
            sizeLocation = GLES20.glGetUniformLocation(program, "uSize")
            themeLocation = GLES20.glGetUniformLocation(program, "uBackdrop")
            energyLocation = GLES20.glGetUniformLocation(program, "uEnergy")
            pulseLocation = GLES20.glGetUniformLocation(program, "uPulse")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            noiseTexture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            val noise = ByteBuffer.allocateDirect(256 * 256).apply {
                put(Random(19).nextBytes(256 * 256))
                position(0)
            }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 256, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, noise)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uNoise"), 0)
            GLES20.glGenTextures(1, textures, 0)
            imageTexture = textures[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImage"), 1)
            imagePresentLocation = GLES20.glGetUniformLocation(program, "uHasImage")
            imageSizeLocation = GLES20.glGetUniformLocation(program, "uImageSize")
            uploadedImage = null
        }.onFailure { Log.e("ImmersiveLyrics", "Ink shader unavailable; keeping static background", it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (backdrop == ImmersiveBackdrop.RIPPLE.ordinal) {
            GLES20.glClearColor(0.78f, 0.85f, 0.80f, 1f)
        } else {
            GLES20.glClearColor(0.025f, 0.032f, 0.033f, 1f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture)
        val image = artwork?.takeUnless { it.isRecycled }
        if (image != null && image !== uploadedImage) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0)
            uploadedImage = image
        }
        GLES20.glUniform1f(imagePresentLocation, if (image == null) 0f else 1f)
        GLES20.glUniform2f(imageSizeLocation, (image?.width ?: 1).toFloat(), (image?.height ?: 1).toFloat())
        GLES20.glUniform1f(timeLocation, elapsed)
        GLES20.glUniform2f(sizeLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(themeLocation, backdrop.toFloat())
        GLES20.glUniform1f(energyLocation, energy)
        GLES20.glUniform1f(pulseLocation, pulse)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(message)
        }
        return shader
    }
}

private const val PulseAttack = 0.35f

private const val VERTEX_SHADER = """
attribute vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
"""

// Domain-warped noise forms continuous ink folds, not separate decorative blobs.
private const val FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uSize;
uniform float uTime;
uniform float uBackdrop;
uniform sampler2D uNoise;
uniform sampler2D uImage;
uniform vec2 uImageSize;
uniform float uHasImage;
uniform float uEnergy;
uniform float uPulse;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return texture2D(uNoise, (i + f + 0.5) / 256.0).r;
}
float fbm(vec2 p) {
    float n = 0.0, a = 0.5;
    mat2 turn = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 3; i++) {
        n += a * noise(p);
        p = turn * p * 2.04 + 3.7;
        a *= 0.5;
    }
    return n;
}
vec3 ink(vec2 uv) {
    vec2 p = (gl_FragCoord.xy - uSize * 0.5) / min(uSize.x, uSize.y);
    float t = uTime * 0.075;
    p *= 2.15;
    p += vec2(sin(t * 0.31) * 0.12, t * 0.11);
    vec2 q = vec2(fbm(p + t * 0.16), fbm(p + vec2(4.8, 1.3) - t * 0.12));
    vec2 r = vec2(fbm(p + q * 2.8 + vec2(1.7, 8.1) + t * 0.17),
                  fbm(p + q * 2.9 + vec2(8.3, 2.8) - t * 0.13));
    float n = fbm(p + r * 3.6);
    float fold = sin(n * 18.0 + r.x * 4.0);
    float vein = pow(1.0 - abs(fold), 7.0);
    float body = smoothstep(0.22, 0.80, n);
    float haze = smoothstep(0.18, 0.75, r.x) * 0.22;
    float bloom = smoothstep(0.18, 0.92, n) * (0.18 + 0.12 * sin(t + r.y * 7.0));
    float light = body * 0.42 + vein * (0.30 + uPulse * 0.34) + haze + bloom;
    vec3 color = vec3(0.020, 0.024, 0.026) + vec3(0.47, 0.63, 0.58) * light;
    color += vec3(0.60, 0.30, 0.20) * smoothstep(0.58, 0.92, r.y) * (0.10 + 0.10 * uPulse);
    color += vec3(0.76, 0.68, 0.45) * vein * 0.08;
    float edge = smoothstep(0.05, 0.70, length((uv - 0.5) * vec2(0.9, 1.1)));
    color *= mix(0.54, 0.88, edge);
    return color;
}
vec3 ripple(vec2 uv) {
    float aspect = uSize.x / uSize.y;
    vec2 p = vec2((uv.x - 0.5) * aspect, (uv.y - 0.22) * 3.3);
    float r = length(p);
    float rings = sin(r * 29.0 - uTime * 1.9) * exp(-r * 3.0);
    float highlight = cos(r * 29.0 - uTime * 1.9) * exp(-r * 3.3);
    float water = fbm(uv * 3.0 + vec2(uTime * 0.015, -uTime * 0.01));
    vec3 color = vec3(0.72, 0.82, 0.75) + water * vec3(0.12, 0.10, 0.08);
    color += rings * 0.055 + highlight * 0.105;
    float wave = (1.0 - uPulse) * 1.6;
    color += exp(-abs(r - wave) * 22.0) * uPulse * 0.16;
    color -= smoothstep(0.1, 0.85, length(uv - 0.5)) * 0.11;
    return color;
}
vec3 aurora(vec2 uv) {
    vec3 color = vec3(0.012, 0.025, 0.029);
    float t = uTime * 0.12;
    float wave = sin(uv.x * 5.5 + t) * 0.12 + sin(uv.x * 11.0 - t * 0.6) * 0.04;
    float curtain = exp(-abs(uv.y - 0.72 - wave) * 8.0);
    float ribs = 0.6 + 0.4 * sin(uv.x * 85.0 + noise(vec2(uv.x * 7.0, t)) * 8.0);
    color += vec3(0.14, 0.62, 0.40) * curtain * ribs * (0.44 + 0.26 * uEnergy + 0.38 * uPulse);
    float coral = exp(-abs(uv.y - 0.18 + sin(uv.x * 4.0 + t * 0.5) * 0.07) * 13.0);
    color += vec3(0.52, 0.20, 0.13) * coral * (0.45 + uv.x * 0.3);
    return color;
}
vec3 picture(vec2 uv) {
    if (uHasImage < 0.5) {
        float n = fbm(uv * 2.8);
        return mix(vec3(0.12, 0.30, 0.29), vec3(0.66, 0.36, 0.32), n);
    }
    float viewAspect = uSize.x / uSize.y;
    float imageAspect = uImageSize.x / uImageSize.y;
    uv = (uv - 0.5) * vec2(min(1.0, viewAspect / imageAspect), min(1.0, imageAspect / viewAspect)) + 0.5;
    return texture2D(uImage, vec2(uv.x, 1.0 - uv.y)).rgb;
}
vec3 common(vec2 uv) {
    float t = uTime * 0.025;
    vec3 color = picture(vec2(fbm(uv * 1.6 + t), fbm(uv * 1.7 - t))) * 0.25;
    vec2 p = (uv - 0.5) * vec2(uSize.x / uSize.y, 1.0);
    for (int i = 0; i < 5; i++) {
        float k = float(i);
        vec2 q = p - vec2(sin(k * 4.1 + t) * 0.75, cos(k * 3.0 - t) * 0.40);
        float angle = t + k * 0.7;
        q = mat2(cos(angle), -sin(angle), sin(angle), cos(angle)) * q;
        float d = abs(max(abs(q.x), abs(q.y)) - 0.045 - k * 0.014);
        color += vec3(0.35, 0.70, 0.65) * exp(-d * 450.0) * 0.2;
    }
    return color;
}
vec3 monet(vec2 uv) {
    vec2 p = (uv - 0.5) * 0.92 + 0.5;
    p += vec2(sin(uTime * 0.09), cos(uTime * 0.07)) * 0.025;
    vec2 brush = vec2(noise(p * vec2(170.0, 60.0)), noise(p * vec2(90.0, 180.0))) - 0.5;
    p += brush * 0.012;
    vec3 c = picture(p) * 0.45 + picture(p + 0.018) * 0.3 + picture(p - 0.018) * 0.25;
    float luma = dot(c, vec3(0.3, 0.59, 0.11));
    c = mix(vec3(luma), c, 0.75);
    c = mix(c, vec3(0.68, 0.56, 0.58), 0.14);
    return c * 0.52 + brush.x * 0.018;
}
vec3 latent(vec2 uv) {
    float t = uTime * 0.06;
    float field = fbm(uv * 2.0 + vec2(t, -t * 0.7));
    vec3 a = picture(vec2(0.2, 0.3));
    vec3 b = picture(vec2(0.8, 0.6));
    vec3 c = mix(a, b + vec3(0.12, 0.01, 0.08), smoothstep(0.22, 0.7, field));
    float threshold = mod(gl_FragCoord.x, 3.0) / 3.0 + mod(gl_FragCoord.y, 3.0) / 9.0;
    c = floor(c * 8.0 + threshold) / 8.0;
    return c * 0.47;
}
vec3 sora(vec2 uv) {
    vec3 color = vec3(0.015, 0.02, 0.033);
    vec2 p = uv * vec2(38.0, 23.0) + vec2(uTime * 0.12, 0.0);
    vec2 cell = floor(p);
    float seed = hash(cell);
    vec2 star = fract(p) - vec2(0.15 + seed * 0.7, 0.15 + hash(cell + 4.0) * 0.7);
    float light = exp(-dot(star, star) * 950.0) * step(0.40, seed);
    float blink = 0.65 + 0.35 * sin(uTime * (0.6 + seed) + seed * 18.0) + uPulse * 0.7 * step(0.7, hash(cell + 9.0));
    color += mix(vec3(0.58, 0.76, 1.0), vec3(1.0, 0.64, 0.46), step(0.88, seed)) * light * blink;
    return color;
}
// Artwork arrives as a tiny thumbnail; bilinear sampling across the screen turns it into soft colour fields.
vec3 fluid(vec2 uv) {
    float t = uTime * 0.045;
    vec2 p = (uv - 0.5) * vec2(uSize.x / uSize.y, 1.0);
    float a = t * 0.7;
    p = mat2(cos(a), -sin(a), sin(a), cos(a)) * p;
    vec2 q = vec2(fbm(p * 1.4 + vec2(t, -t * 0.6)), fbm(p * 1.4 + vec2(5.2 - t * 0.8, 1.3 + t)));
    vec2 w = p * 0.62 + (q - 0.5) * (1.05 + uPulse * 0.35) + 0.5;
    vec2 w2 = mat2(0.0, -1.0, 1.0, 0.0) * (w - 0.5) * 0.85 + 0.5 + (q.yx - 0.5) * 0.4;
    vec3 c;
    if (uHasImage < 0.5) c = mix(vec3(0.10, 0.32, 0.30), vec3(0.62, 0.30, 0.34), fbm(w * 2.5));
    else c = mix(texture2D(uImage, clamp(w, 0.02, 0.98)).rgb, texture2D(uImage, clamp(w2, 0.02, 0.98)).rgb,
                 0.5 + 0.5 * sin(t * 3.0 + q.x * 6.0));
    float luma = dot(c, vec3(0.3, 0.59, 0.11));
    c = clamp(mix(vec3(luma), c, 1.45), 0.0, 1.0);
    c = c * (0.50 + 0.12 * uEnergy) + vec3(0.02, 0.025, 0.03);
    c *= 1.0 - smoothstep(0.35, 1.05, length((uv - 0.5) * vec2(1.1, 1.3))) * 0.45;
    return c;
}
// Particles orbit a soft centre and leave thin, glyph-like traces. The lyric layer stays readable above it.
vec3 kinetic(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uSize.x / uSize.y, 1.0);
    float t = uTime * 0.18;
    vec3 color = vec3(0.035, 0.022, 0.027);
    float halo = exp(-length(p * vec2(0.85, 1.05)) * 2.2);
    color += vec3(0.23, 0.12, 0.10) * halo * (0.55 + 0.35 * uEnergy + 0.5 * uPulse);
    for (int i = 0; i < 32; i++) {
        float k = float(i);
        float seed = hash(vec2(k, 9.4));
        float radius = 0.10 + seed * 0.78 + uPulse * 0.08;
        float angle = k * 2.39996 + t * (0.34 + seed * 0.32) * (mod(k, 2.0) * 2.0 - 1.0);
        vec2 pos = vec2(cos(angle), sin(angle)) * vec2(radius, radius * 0.66);
        float d = length(p - pos);
        float spark = exp(-d * (150.0 + seed * 170.0)) * (0.22 + 0.68 * uEnergy + 0.8 * uPulse);
        float trail = exp(-abs(length(p) - radius) * 38.0) * exp(-abs(atan(p.y, p.x) - angle) * 3.4) * 0.025;
        vec3 tint = mix(vec3(0.55, 0.86, 0.79), vec3(0.94, 0.58, 0.30), step(0.58, seed));
        color += tint * (spark + trail);
    }
    float sweep = exp(-abs(p.y - sin(p.x * 3.4 + t) * 0.12) * 26.0) * 0.045;
    color += vec3(0.20, 0.54, 0.55) * sweep;
    return color;
}
vec3 eclipse(vec2 uv) {
    vec2 p = (uv - vec2(0.69, 0.42)) * vec2(uSize.x / uSize.y, 1.0);
    float t = uTime * 0.12;
    vec3 color = vec3(0.012, 0.021, 0.043);
    vec2 starUv = uv * vec2(42.0, 25.0) + vec2(t * 0.12, 0.0);
    vec2 cell = floor(starUv);
    float starSeed = hash(cell);
    vec2 star = fract(starUv) - vec2(0.18 + hash(cell + 2.0) * 0.64, 0.16 + hash(cell + 5.0) * 0.68);
    float starLight = exp(-dot(star, star) * 680.0) * step(0.57, starSeed);
    color += mix(vec3(0.54, 0.70, 0.84), vec3(1.0, 0.67, 0.38), step(0.86, starSeed)) * starLight * (0.48 + 0.52 * uEnergy + uPulse * 0.7);
    for (int i = 0; i < 3; i++) {
        float seed = hash(vec2(float(i), 31.7));
        float phase = fract(uTime * 0.055 + seed);
        float active = smoothstep(0.20, 0.29, phase) * (1.0 - smoothstep(0.58, 0.68, phase));
        vec2 direction = normalize(vec2(-1.0, -0.48));
        vec2 head = vec2(1.22 + seed * 0.18 - phase * 2.15, 0.88 + seed * 0.10 - phase * 1.05);
        vec2 tail = head - direction * 0.18;
        vec2 segment = head - tail;
        float along = clamp(dot(uv - tail, segment) / dot(segment, segment), 0.0, 1.0);
        float distanceToTrail = length(uv - (tail + segment * along));
        float trail = exp(-distanceToTrail * 720.0) * active * (0.22 + along * 0.78);
        float mist = exp(-distanceToTrail * 110.0) * active * 0.10;
        float spark = exp(-length(uv - head) * 260.0) * active;
        vec3 meteorColor = mix(vec3(0.52, 0.82, 1.0), vec3(1.0, 0.72, 0.48), step(0.58, seed));
        color += meteorColor * (trail * (0.75 + 0.35 * uEnergy) + mist + spark * (0.35 + 0.45 * uPulse));
    }
    float d = length(p * vec2(1.0, 1.04));
    float corona = exp(-abs(d - 0.302) * 15.0) * (0.30 + 0.26 * uEnergy + 0.56 * uPulse);
    color += vec3(0.83, 0.44, 0.21) * corona;
    float tilt = 0.523599;
    vec2 tilted = mat2(cos(tilt), -sin(tilt), sin(tilt), cos(tilt)) * p;
    vec2 ringPoint = vec2(tilted.x + tilted.y * 0.10, tilted.y * 1.75 - tilted.x * 0.04);
    float ringDistance = length(ringPoint);
    float ring = exp(-abs(ringDistance - 0.36) * 150.0) + exp(-abs(ringDistance - 0.45) * 210.0) * 0.58;
    float frontArc = 1.0 - smoothstep(-0.018, 0.018, ringPoint.y);
    float ringAngle = atan(ringPoint.y, ringPoint.x);
    float sweepAngle = ringAngle - uTime * 0.28;
    float sweepDelta = atan(sin(sweepAngle), cos(sweepAngle));
    float sweep = exp(-abs(sweepDelta) * 8.0) * exp(-abs(ringDistance - 0.405) * 38.0);
    float sweepFront = sweep * frontArc;
    float beadPattern = pow(max(0.0, cos(ringAngle * 4.0 - uTime * 0.72)), 22.0);
    float beads = beadPattern * exp(-abs(ringDistance - 0.36) * 52.0);
    float ringGlow = 0.30 + 0.28 * uPulse;
    color += vec3(0.87, 0.58, 0.32) * ring * (1.0 - frontArc) * ringGlow;
    color += vec3(0.98, 0.76, 0.45) * sweep * (1.0 - frontArc) * 0.42;
    color += vec3(0.30, 0.72, 0.95) * beads * (1.0 - frontArc) * 0.78;
    float planet = 1.0 - smoothstep(0.292, 0.308, d);
    color = mix(color, vec3(0.0), planet);
    color += vec3(0.87, 0.58, 0.32) * ring * frontArc * (ringGlow + 0.08);
    color += vec3(0.98, 0.76, 0.45) * sweepFront * 0.5;
    color += vec3(0.30, 0.72, 0.95) * beads * frontArc * 0.92;
    float limb = exp(-abs(d - 0.302) * 165.0) * smoothstep(-0.20, 0.12, p.x + p.y * 0.12) * (1.0 - planet);
    color += vec3(1.0, 0.67, 0.37) * limb * (0.22 + 0.5 * uPulse);
    color *= 1.0 - smoothstep(0.75, 1.25, length((uv - 0.5) * vec2(1.0, 1.15))) * 0.36;
    return color;
}
vec3 cyber(vec2 uv) {
    vec3 color = vec3(0.018, 0.008, 0.035);
    float t = uTime * 0.42;
    float pulse = 0.4 + 0.6 * uPulse + 0.15 * uEnergy;
    vec2 p = (uv - vec2(0.5, 0.64)) * vec2(uSize.x / uSize.y, 1.0);
    float horizon = 0.48;
    float gridY = abs(fract((uv.y - horizon) * 15.0 / max(0.02, 1.0 - horizon)) - 0.5);
    float grid = exp(-gridY * 110.0) * smoothstep(horizon, 1.0, uv.y);
    float gridX = abs(fract((uv.x - 0.5) * 18.0 + (uv.y - horizon) * 2.2) - 0.5);
    grid += exp(-gridX * 120.0) * smoothstep(horizon, 1.0, uv.y) * 0.75;
    color += vec3(0.13, 0.48, 0.54) * grid * (0.22 + pulse * 0.32);
    for (int i = 0; i < 24; i++) {
        float k = float(i);
        float level = 0.08 + 0.34 * (0.5 + 0.5 * sin(k * 1.73 + t * (1.0 + uEnergy))) * (0.6 + 0.6 * uPulse);
        float x = (k + 0.5) / 24.0;
        float bar = exp(-abs(uv.x - x) * 240.0) * smoothstep(horizon - level, horizon - level + 0.012, uv.y) * step(uv.y, horizon);
        float body = step(horizon - level, uv.y) * step(uv.y, horizon) * exp(-abs(uv.x - x) * 125.0);
        vec3 tint = mod(k, 3.0) < 1.0 ? vec3(1.0, 0.16, 0.68) : (mod(k, 3.0) < 2.0 ? vec3(0.16, 0.88, 1.0) : vec3(0.70, 1.0, 0.22));
        color += tint * (bar * (0.34 + pulse * 0.66) + body * 0.025);
    }
    for (int i = 0; i < 4; i++) {
        float k = float(i);
        float beam = abs(p.y - sin(p.x * 2.0 + t * (0.8 + k * 0.24) + k) * 0.22);
        color += mix(vec3(1.0, 0.10, 0.72), vec3(0.08, 0.85, 1.0), mod(k, 2.0)) * exp(-beam * 175.0) * (0.10 + 0.15 * pulse);
    }
    float centre = exp(-length(p * vec2(1.0, 1.5)) * 4.0);
    color += vec3(0.35, 0.13, 0.50) * centre * (0.25 + 0.25 * uPulse);
    return color;
}
void main() {
    vec2 uv = gl_FragCoord.xy / uSize;
    vec3 color;
    if (uBackdrop < 0.5) color = ink(uv);
    else if (uBackdrop < 1.5) color = ripple(uv);
    else if (uBackdrop < 2.5) color = aurora(uv);
    else if (uBackdrop < 3.5) color = common(uv);
    else if (uBackdrop < 4.5) color = monet(uv);
    else if (uBackdrop < 5.5) color = latent(uv);
    else if (uBackdrop < 6.5) color = sora(uv);
    else if (uBackdrop < 7.5) color = fluid(uv);
    else if (uBackdrop < 8.5) color = kinetic(uv);
    else if (uBackdrop < 9.5) color = eclipse(uv);
    else if (uBackdrop < 10.5) color = cyber(uv);
    else color = fluid(uv);
    // Shared breath: a soft bloom from the centre on each pulse, a touch dimmer in gaps and pauses.
    float bloom = exp(-length((uv - 0.5) * vec2(uSize.x / uSize.y, 1.0)) * 2.2);
    color *= 0.88 + 0.12 * uEnergy;
    color += color * uPulse * 0.42 * bloom;
    color += (hash(gl_FragCoord.xy) - 0.5) * 0.008;
    gl_FragColor = vec4(color, 1.0);
}
"""
