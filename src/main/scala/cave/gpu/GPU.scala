/*
 *   __   __     __  __     __         __
 *  /\ "-.\ \   /\ \/\ \   /\ \       /\ \
 *  \ \ \-.  \  \ \ \_\ \  \ \ \____  \ \ \____
 *   \ \_\\"\_\  \ \_____\  \ \_____\  \ \_____\
 *    \/_/ \/_/   \/_____/   \/_____/   \/_____/
 *   ______     ______       __     ______     ______     ______
 *  /\  __ \   /\  == \     /\ \   /\  ___\   /\  ___\   /\__  _\
 *  \ \ \/\ \  \ \  __<    _\_\ \  \ \  __\   \ \ \____  \/_/\ \/
 *   \ \_____\  \ \_____\ /\_____\  \ \_____\  \ \_____\    \ \_\
 *    \/_____/   \/_____/ \/_____/   \/_____/   \/_____/     \/_/
 *
 * https://joshbassett.info
 * https://twitter.com/nullobject
 * https://github.com/nullobject
 *
 * Copyright (c) 2022 Josh Bassett
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cave.gpu

import axon.Util
import axon.gfx._
import axon.mem._
import axon.types._
import axon.util.Counter
import cave._
import cave.types._
import chisel3._
import chisel3.util._

/** Graphics processing unit (GPU). */
class GPU extends Module {
  val io = IO(new Bundle {
    /** Game config port */
    val gameConfig = Input(GameConfig())
    /** Layer 0 port */
    val layer0 = Input(new Layer)
    /** Layer 1 port */
    val layer1 = Input(new Layer)
    /** Layer 2 port */
    val layer2 = Input(new Layer)
    /** Layer 0 VRAM port */
    val layer0Ram = new LayerRamIO
    /** Layer 1 VRAM port */
    val layer1Ram = new LayerRamIO
    /** Layer 2 VRAM port */
    val layer2Ram = new LayerRamIO
    /** Layer 0 tile ROM port */
    val layer0Rom = new LayerRomIO
    /** Layer 1 tile ROM port */
    val layer1Rom = new LayerRomIO
    /** Layer 2 tile ROM port */
    val layer2Rom = new LayerRomIO
    /** Sprite RAM port */
    val spriteRam = new SpriteRamIO
    /** Palette RAM port */
    val paletteRam = new PaletteRamIO
    /** Video port */
    val video = Flipped(VideoIO())
    /** RGB output */
    val rgb = Output(new RGB(Config.DDR_FRAME_BUFFER_BITS_PER_CHANNEL))
  })

  io.spriteRam.default()

  // Layer 0 processor
  val layer0Processor = Module(new TilemapProcessor)
  layer0Processor.io.layer <> io.layer0
  layer0Processor.io.layerRam <> io.layer0Ram
  layer0Processor.io.tileRom <> io.layer0Rom
  layer0Processor.io.video <> io.video

  // Layer 1 processor
  val layer1Processor = Module(new TilemapProcessor)
  layer1Processor.io.layer <> io.layer1
  layer1Processor.io.layerRam <> io.layer1Ram
  layer1Processor.io.tileRom <> io.layer1Rom
  layer1Processor.io.video <> io.video

  // Layer 2 processor
  val layer2Processor = Module(new TilemapProcessor)
  layer2Processor.io.layer <> io.layer2
  layer2Processor.io.layerRam <> io.layer2Ram
  layer2Processor.io.tileRom <> io.layer2Rom
  layer2Processor.io.video <> io.video

  // Color mixer
  val colorMixer = Module(new ColorMixer)
  colorMixer.io.numColors := io.gameConfig.numColors
  colorMixer.io.layer0Pen := layer0Processor.io.pen
  colorMixer.io.layer1Pen := layer1Processor.io.pen
  colorMixer.io.layer2Pen := layer2Processor.io.pen
  colorMixer.io.paletteRam <> io.paletteRam
  colorMixer.io.rgb <> io.rgb
}

object GPU {
  /**
   * Returns the palette entry used to clear the frame buffer for the given game index.
   *
   * @param index The game index.
   * @return A palette entry.
   */
  def backgroundPen(index: UInt): PaletteEntry =
    MuxLookup(index, PaletteEntry(0x7f.U, 0.U), Seq(
      GameConfig.DFEVERON.U -> PaletteEntry(0x3f.U, 0.U)
    ))

  /**
   * Transforms a frame buffer pixel position to a memory address, applying the optional flip and
   * rotate transforms.
   *
   * @param pos    The pixel position.
   * @param flip   Flips the image.
   * @param rotate Rotates the image 90 degrees.
   * @return An address value.
   */
  def transformAddr(pos: UVec2, flip: Bool, rotate: Bool): UInt = {
    val x = pos.x(Config.FRAME_BUFFER_ADDR_WIDTH_X - 1, 0)
    val y = pos.y(Config.FRAME_BUFFER_ADDR_WIDTH_Y - 1, 0)
    val x_ = (Config.SCREEN_WIDTH - 1).U - x
    val y_ = (Config.SCREEN_HEIGHT - 1).U - y
    Mux(rotate,
      Mux(flip, (x * Config.SCREEN_HEIGHT.U) + y_, (x_ * Config.SCREEN_HEIGHT.U) + y),
      Mux(flip, (y_ * Config.SCREEN_WIDTH.U) + x_, (y * Config.SCREEN_WIDTH.U) + x)
    )
  }

  def transformAddr(pos: SVec2, flip: Bool, rotate: Bool): UInt = {
    val x = pos.x(Config.FRAME_BUFFER_ADDR_WIDTH_X - 1, 0)
    val y = pos.y(Config.FRAME_BUFFER_ADDR_WIDTH_Y - 1, 0)
    val x_ = (Config.SCREEN_WIDTH - 1).U - x
    val y_ = (Config.SCREEN_HEIGHT - 1).U - y
    Mux(rotate,
      Mux(flip, (x * Config.SCREEN_HEIGHT.U) + y_, (x_ * Config.SCREEN_HEIGHT.U) + y),
      Mux(flip, (y_ * Config.SCREEN_WIDTH.U) + x_, (y * Config.SCREEN_WIDTH.U) + x)
    )
  }

  /**
   * Creates a virtual write-only memory interface that writes a constant value to the given
   * address.
   *
   * @param addr The address value.
   * @param data The constant value.
   */
  def clearMem(addr: UInt, data: Bits = 0.U): WriteMemIO = {
    val mem = Wire(WriteMemIO(Config.FRAME_BUFFER_ADDR_WIDTH, Config.FRAME_BUFFER_DATA_WIDTH))
    mem.wr := true.B
    mem.addr := addr
    mem.mask := 0.U
    mem.din := data
    mem
  }

  /**
   * Decodes a list of pixels.
   *
   * @param data The pixel data.
   * @param n    The number of pixels.
   * @return A list of 24-bit pixel values.
   */
  def decodePixels(data: Bits, n: Int): Seq[Bits] =
    Util
      // Decode channels
      .decode(data, n * 3, Config.BITS_PER_CHANNEL)
      // Convert channel values to 8BPP
      .map { c => c(4, 0) ## c(4, 2) }
      // Group channels
      .grouped(3).toSeq
      // Reorder channels (BRG -> BGR)
      .map { case Seq(b, r, g) => Cat(b, g, r) }
      // Swap pixels values
      .reverse

  /**
   * Calculates the visibility of a pixel.
   *
   * @param pos The pixel position.
   * @return A boolean value indicating whether the pixel is visible.
   */
  def isVisible(pos: UVec2): Bool =
    Util.between(pos.x, 0 until Config.SCREEN_WIDTH) &&
      Util.between(pos.y, 0 until Config.SCREEN_HEIGHT)

  def isVisible(pos: SVec2): Bool =
    Util.between(pos.x, 0 until Config.SCREEN_WIDTH) &&
      Util.between(pos.y, 0 until Config.SCREEN_HEIGHT)
}
