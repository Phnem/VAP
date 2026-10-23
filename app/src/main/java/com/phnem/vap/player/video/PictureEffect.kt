package com.phnem.vap.player.video

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Media3 supplies linear RGB; adjustments are made in electrical RGB and returned as linear. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PictureEffect(private val settings: PictureSettings) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        if (useHdr) androidx.media3.effect.RgbAdjustment.Builder().build().toGlShaderProgram(context, true)
        else Shader(settings, false)

    private class Shader(settings: PictureSettings, hdr: Boolean) : BaseGlShaderProgram(hdr, 1) {
        private val program = try {
            GlProgram("""
                attribute vec4 aPosition;
                varying vec2 vTex;
                void main() { gl_Position = aPosition; vTex = aPosition.xy * 0.5 + 0.5; }
            """.trimIndent(), """
                precision highp float;
                uniform sampler2D uTex;
                uniform float uBrightness;
                uniform float uContrast;
                uniform float uSaturation;
                uniform float uGamma;
                varying vec2 vTex;
                void main() {
                    vec4 source = texture2D(uTex, vTex);
                    vec3 rgb = pow(max(source.rgb, vec3(0.0)), vec3(1.0 / 2.2));
                    float y = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    rgb = mix(vec3(y), rgb, uSaturation);
                    rgb = clamp((rgb - 0.5) * uContrast + 0.5 + uBrightness, 0.0, 1.0);
                    rgb = pow(rgb, vec3(1.0 / uGamma));
                    gl_FragColor = vec4(pow(rgb, vec3(2.2)), source.a);
                }
            """.trimIndent()).apply {
                setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                setFloatUniform("uBrightness", settings.brightness)
                setFloatUniform("uContrast", settings.contrast)
                setFloatUniform("uSaturation", settings.saturation)
                setFloatUniform("uGamma", settings.gamma)
            }
        } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error) }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTex", inputTexId, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error, presentationTimeUs) }
        }
        override fun release() {
            super.release()
            try { program.delete() } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error) }
        }
    }
}
