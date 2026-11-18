package com.example.game.engine

import com.example.game.model.SpriteId
import kotlin.math.floor

/**
 * Compact 36x24 Gothic-Industrial dungeon tilemap with O(1) collision checks.
 * Divided into three interconnected chambers:
 *  - West (x 1..12): Foundry Entry & Chronicler Sanctuary
 *  - Center (x 13..22): Grate Hallway, Gear Alcove & Lever Wing
 *  - East (x 23..34): Crimson Sanctum & Boss Arena
 */
class DungeonMap {

    companion object {
        const val WIDTH = 36
        const val HEIGHT = 24

        const val TILE_STONE = 0
        const val TILE_GRATE = 1
        const val TILE_RUST = 2
        const val TILE_BOLTED = 3
        const val TILE_WALL = 4
        const val TILE_ARCH = 5
        const val TILE_STAIRS = 6
        const val TILE_PLANKS = 7
        const val TILE_GATE_LOCKED = 8
        const val TILE_GATE_OPEN = 9
    }

    private val tiles = IntArray(WIDTH * HEIGHT)
    var gateUnlocked: Boolean = false
        private set

    init {
        reset()
    }

    fun reset() {
        gateUnlocked = false
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val idx = y * WIDTH + x
                tiles[idx] = when {
                    x == 0 || y == 0 || x == WIDTH - 1 || y == HEIGHT - 1 -> TILE_WALL
                    // Vertical divider wall between Entry (West) and Corridor (Center)
                    x == 13 && y !in 10..13 && y !in 4..5 -> TILE_WALL
                    // Vertical divider wall between Corridor (Center) and Boss Sanctum (East)
                    x == 23 && y !in 10..12 -> TILE_WALL
                    // Locked Iron Gate blocking Boss Sanctum at x=23, y=10..12
                    else -> TILE_STONE
                }
            }
        }

        // Boss sanctum bolted metal flooring (x 24..34)
        for (y in 1 until HEIGHT - 1) {
            for (x in 24 until WIDTH - 1) {
                setTile(x, y, if ((x + y) % 3 == 0) TILE_BOLTED else TILE_RUST)
            }
        }

        // Center corridor rust & grate catwalk
        for (y in 2 until HEIGHT - 2) {
            for (x in 14 until 23) {
                setTile(x, y, if (y in 9..14) TILE_GRATE else TILE_RUST)
            }
        }

        // North wing alcove walls for tactical cover
        for (x in 16..20) {
            setTile(x, 7, TILE_WALL)
        }
        setTile(18, 7, TILE_ARCH)

        // West Foundry grates, stairs & plank bridge
        for (x in 3..9) {
            setTile(x, 6, TILE_GRATE)
            setTile(x, 17, TILE_GRATE)
        }
        setTile(6, 2, TILE_ARCH)
        setTile(13, 10, TILE_PLANKS)
        setTile(13, 11, TILE_PLANKS)
        setTile(13, 12, TILE_PLANKS)
        setTile(13, 13, TILE_PLANKS)
        setTile(13, 4, TILE_STAIRS)
        setTile(13, 5, TILE_STAIRS)

        // Pillars in West Foundry & East Boss Arena
        setTile(8, 10, TILE_WALL)
        setTile(8, 13, TILE_WALL)
        setTile(28, 7, TILE_WALL)
        setTile(28, 16, TILE_WALL)
        setTile(31, 7, TILE_WALL)
        setTile(31, 16, TILE_WALL)

        // Iron Gate at x=23, y=10..12
        setTile(23, 10, TILE_GATE_LOCKED)
        setTile(23, 11, TILE_GATE_LOCKED)
        setTile(23, 12, TILE_GATE_LOCKED)
    }

    fun unlockGate() {
        if (gateUnlocked) return
        gateUnlocked = true
        setTile(23, 10, TILE_GATE_OPEN)
        setTile(23, 11, TILE_GATE_OPEN)
        setTile(23, 12, TILE_GATE_OPEN)
    }

    fun setTile(x: Int, y: Int, type: Int) {
        if (x in 0 until WIDTH && y in 0 until HEIGHT) {
            tiles[y * WIDTH + x] = type
        }
    }

    fun getTile(x: Int, y: Int): Int {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return TILE_WALL
        return tiles[y * WIDTH + x]
    }

    fun isTileSolid(type: Int): Boolean {
        return type == TILE_WALL || type == TILE_GATE_LOCKED
    }

    fun isWalkable(wx: Float, wy: Float, radius: Float): Boolean {
        val minX = floor((wx - radius).toDouble()).toInt()
        val maxX = floor((wx + radius).toDouble()).toInt()
        val minY = floor((wy - radius).toDouble()).toInt()
        val maxY = floor((wy + radius).toDouble()).toInt()
        for (ty in minY..maxY) {
            for (tx in minX..maxX) {
                if (isTileSolid(getTile(tx, ty))) return false
            }
        }
        return true
    }

    fun spriteForTile(type: Int): SpriteId {
        return when (type) {
            TILE_STONE -> SpriteId.TILE_STONE_BRICK
            TILE_GRATE -> SpriteId.TILE_IRON_GRATE
            TILE_RUST -> SpriteId.TILE_RUST_PLATE
            TILE_BOLTED -> SpriteId.TILE_BOLTED_FRAME
            TILE_WALL -> SpriteId.TILE_RED_BRICK
            TILE_ARCH -> SpriteId.TILE_ARCHWAY
            TILE_STAIRS -> SpriteId.TILE_STAIRS
            TILE_PLANKS -> SpriteId.TILE_PLANKS
            TILE_GATE_LOCKED -> SpriteId.PROP_GATE
            TILE_GATE_OPEN -> SpriteId.TILE_BOLTED_FRAME
            else -> SpriteId.TILE_STONE_BRICK
        }
    }

    fun encodeMiniMapTerrain(): String {
        val sb = StringBuilder(WIDTH * HEIGHT)
        for (i in tiles.indices) {
            val code = when (tiles[i]) {
                TILE_WALL -> 'W'
                TILE_GATE_LOCKED -> 'L'
                TILE_GATE_OPEN -> 'O'
                TILE_GRATE, TILE_PLANKS, TILE_STAIRS, TILE_ARCH -> 'S'
                else -> 'F'
            }
            sb.append(code)
        }
        return sb.toString()
    }
}
