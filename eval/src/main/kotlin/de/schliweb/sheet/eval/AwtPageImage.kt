// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.eval

import de.schliweb.sheet.core.corners.DocQuad
import de.schliweb.sheet.core.corners.Letterbox
import de.schliweb.sheet.core.corners.PageImage
import de.schliweb.sheet.core.corners.RotatedFrame
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

class AwtPageImage(val image: BufferedImage) : PageImage {
    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override fun letterboxed(letterbox: Letterbox): IntArray {
        val size = DocQuad.INPUT_SIZE
        val out = draw(size, size) { g ->
            g.drawImage(image, AffineTransform(letterbox.scale, 0.0, 0.0, letterbox.scale, letterbox.left, letterbox.top), null)
        }
        return out.getRGB(0, 0, size, size, null, 0, size)
    }

    override fun scaled(width: Int, height: Int): PageImage = AwtPageImage(
        draw(width, height) { g ->
            g.drawImage(image, AffineTransform.getScaleInstance(width / this.width.toDouble(), height / this.height.toDouble()), null)
        },
    )

    override fun render(width: Int, height: Int, affine: DoubleArray, background: Int): PageImage =
        AwtPageImage(draw(width, height, Color(background, true)) { g -> g.drawImage(image, AffineTransform(affine[0], affine[3], affine[1], affine[4], affine[2], affine[5]), null) })

    override fun argb(): IntArray = image.getRGB(0, 0, width, height, null, 0, width)

    override fun rotated(frame: RotatedFrame): PageImage {
        val source = if (width == frame.scaledWidth && height == frame.scaledHeight) this else scaled(frame.scaledWidth, frame.scaledHeight) as AwtPageImage
        val a = frame.affine
        return AwtPageImage(draw(frame.width, frame.height) { g -> g.drawImage(source.image, AffineTransform(a[0], a[3], a[1], a[4], a[2], a[5]), null) })
    }

    private fun draw(w: Int, h: Int, background: Color = Color.BLACK, paint: (java.awt.Graphics2D) -> Unit): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = background
            g.fillRect(0, 0, w, h)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paint(g)
        } finally {
            g.dispose()
        }
        return out
    }
}
