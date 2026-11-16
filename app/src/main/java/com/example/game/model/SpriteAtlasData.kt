package com.example.game.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.R

data class SpriteRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {
    val u0: Float get() = x / 1024f
    val v0: Float get() = y / 1024f
    val u1: Float get() = (x + w) / 1024f
    val v1: Float get() = (y + h) / 1024f
}

enum class SpriteId(val rect: SpriteRect) {
    TILE_STONE_BRICK(SpriteRect(4, 4, 119, 120)),
    TILE_IRON_GRATE(SpriteRect(127, 4, 121, 120)),
    TILE_RUST_PLATE(SpriteRect(252, 4, 120, 120)),
    TILE_BOLTED_FRAME(SpriteRect(376, 4, 122, 120)),
    TILE_RED_BRICK(SpriteRect(502, 4, 122, 120)),
    TILE_ARCHWAY(SpriteRect(628, 4, 121, 121)),
    PROP_IRON_FENCE(SpriteRect(753, 4, 175, 109)),
    TILE_STAIRS(SpriteRect(4, 129, 105, 118)),
    TILE_PLANKS(SpriteRect(113, 129, 111, 72)),
    PROP_RUBBLE(SpriteRect(228, 129, 114, 103)),

    PROP_BARREL(SpriteRect(346, 129, 77, 122)),
    PROP_CHEST(SpriteRect(427, 129, 110, 107)),
    PROP_TORCH(SpriteRect(541, 129, 33, 114)),
    PROP_GEARS(SpriteRect(578, 129, 120, 120)),
    PROP_CHANDELIER(SpriteRect(702, 129, 116, 123)),
    PROP_LEVER(SpriteRect(822, 129, 59, 117)),
    PROP_LADDER(SpriteRect(885, 129, 59, 128)),
    PROP_BARRICADE(SpriteRect(4, 261, 130, 112)),
    PROP_SKULLS(SpriteRect(138, 261, 125, 96)),
    PROP_GATE(SpriteRect(267, 261, 147, 126)),

    HERO_SOUTH(SpriteRect(418, 261, 69, 104)),
    HERO_NORTH(SpriteRect(491, 261, 66, 103)),
    HERO_EAST(SpriteRect(561, 261, 91, 98)),
    HERO_WEST(SpriteRect(656, 261, 68, 106)),
    HERO_ATTACK_1(SpriteRect(728, 261, 86, 98)),
    HERO_ATTACK_2(SpriteRect(818, 261, 77, 98)),
    HERO_DYING(SpriteRect(899, 261, 95, 74)),

    ENEMY_GARGOYLE(SpriteRect(4, 391, 114, 110)),
    ENEMY_AUTOMATON(SpriteRect(122, 391, 104, 128)),
    NPC_SHADOW(SpriteRect(230, 391, 65, 109)),
    NPC_CULTIST(SpriteRect(299, 391, 62, 110)),
    ENEMY_SKELETON(SpriteRect(365, 391, 62, 107)),

    BOSS_DEMON_IDLE(SpriteRect(431, 391, 116, 128)),
    BOSS_DEMON_ATTACK(SpriteRect(551, 391, 117, 128)),
    BOSS_DEMON_EAST(SpriteRect(672, 391, 117, 129)),
    BOSS_DEMON_WEST(SpriteRect(793, 391, 115, 130)),
    BOSS_MECH_1(SpriteRect(4, 525, 128, 138)),
    BOSS_MECH_2(SpriteRect(136, 525, 118, 133)),
    BOSS_MECH_3(SpriteRect(258, 525, 121, 142)),
    BOSS_MECH_4(SpriteRect(383, 525, 133, 129)),
    BOSS_MECH_5(SpriteRect(520, 525, 133, 133)),

    ITEM_SWORD(SpriteRect(657, 525, 71, 72)),
    ITEM_AXE(SpriteRect(732, 525, 70, 70)),
    ITEM_SHIELD(SpriteRect(806, 525, 64, 70)),
    ITEM_HELMET(SpriteRect(874, 525, 58, 71)),
    ITEM_HOOD(SpriteRect(936, 525, 60, 71)),
    ITEM_POTION(SpriteRect(4, 671, 46, 70)),
    ITEM_COINS(SpriteRect(54, 671, 68, 69))
}

class AtlasBitmapStore(context: Context) {
    private val atlasBitmap: Bitmap
    private val cache = mutableMapOf<SpriteId, ImageBitmap>()

    init {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        atlasBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ironcrypt_atlas, opts)
    }

    fun getSpriteBitmap(id: SpriteId): ImageBitmap {
        return cache.getOrPut(id) {
            val r = id.rect
            Bitmap.createBitmap(atlasBitmap, r.x, r.y, r.w, r.h).asImageBitmap()
        }
    }
}
