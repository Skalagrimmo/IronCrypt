package com.example.game.engine

import com.example.game.model.SpriteId
import kotlin.math.floor

/**
 * Hand-authored 96x72 Gothic-Industrial dungeon.
 *
 * The world is a connected complex of ten distinct spaces rather than one open arena:
 * Foundry Entrance, Chronicler's Sanctuary, Furnace Nave, Gearworks, Ashen Archive,
 * Cinder Drainage, Service Shaft, Ossuary Crypt, Crimson Antechamber and Crimson Sanctum.
 *
 * Geometry is carved once during reset. Runtime collision and tile lookup remain O(1).
 */
class DungeonMap {

    companion object {
        const val WIDTH = 96
        const val HEIGHT = 72

        const val START_X = 8.5f
        const val START_Y = 36.0f

        const val CHRONICLER_X = 12.5f
        const val CHRONICLER_Y = 36.0f
        const val LEVER_X = 37.5f
        const val LEVER_Y = 10.5f

        const val GATE_X_FIRST = 82
        const val GATE_X_LAST = 87
        const val GATE_Y = 25
        const val GATE_CENTER_X = 85.0f
        const val GATE_CENTER_Y = 25.5f

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

    /**
     * Changes only when tile passability changes. The navigation system can use this
     * revision to know when a cached flow field must be rebuilt.
     */
    var walkabilityRevision: Int = 0
        private set

    init {
        reset()
    }

    fun reset() {
        gateUnlocked = false
        walkabilityRevision = 0
        tiles.fill(TILE_WALL)

        // North-west residential / shrine wing.
        carveRoom(2, 3, 20, 23, TILE_STONE)       // Chronicler's Sanctuary
        carveRoom(25, 3, 46, 22, TILE_GRATE)      // Gearworks
        carveRoom(50, 3, 72, 23, TILE_PLANKS)     // Ashen Archive

        // Main western and central route.
        carveRoom(2, 27, 20, 46, TILE_STONE)      // Foundry Entrance
        carveRoom(24, 22, 52, 50, TILE_RUST)      // Furnace Nave

        // South service wing and the optional loop.
        carveRoom(24, 51, 48, 69, TILE_GRATE)     // Cinder Drainage
        carveRoom(52, 28, 72, 45, TILE_RUST)      // Service Shaft
        carveRoom(50, 50, 73, 69, TILE_STONE)     // Ossuary Crypt

        // Eastern progression route.
        carveRoom(74, 26, 94, 49, TILE_BOLTED)    // Crimson Antechamber
        carveRoom(74, 3, 94, 24, TILE_BOLTED)     // Crimson Sanctum

        // Sanctuary <-> Foundry entrance.
        carveVerticalCorridor(8, 11, 19, 30, TILE_PLANKS)

        // Foundry entrance <-> Furnace Nave.
        carveHorizontalCorridor(17, 28, 34, 5, TILE_RUST)

        // Furnace Nave <-> Gearworks.
        carveVerticalCorridor(35, 39, 18, 27, TILE_GRATE)

        // Gearworks <-> Ashen Archive.
        carveHorizontalCorridor(43, 54, 13, 5, TILE_GRATE)

        // Furnace Nave <-> Cinder Drainage.
        carveVerticalCorridor(35, 39, 45, 55, TILE_GRATE)

        // Cinder Drainage <-> Ossuary Crypt.
        carveHorizontalCorridor(43, 55, 59, 5, TILE_STONE)

        // Archive <-> Service Shaft <-> Ossuary: an optional alternate loop.
        carveVerticalCorridor(60, 64, 20, 54, TILE_RUST)

        // Furnace Nave <-> Crimson Antechamber.
        carveHorizontalCorridor(49, 78, 34, 5, TILE_BOLTED)

        // Antechamber <-> final Sanctum. The iron gate blocks this passage.
        carveVerticalCorridor(82, 87, 20, 30, TILE_BOLTED)

        // Recesses, broken floor patterns and cover. These do not close any corridor.
        for (y in 4..21) {
            for (x in 26..45) {
                if ((x + y) % 5 == 0) setTile(x, y, TILE_RUST)
            }
        }
        for (y in 4..22) {
            for (x in 51..71) {
                if ((x + y) % 4 == 0) setTile(x, y, TILE_STONE)
            }
        }
        for (y in 23..49) {
            for (x in 25..51) {
                if ((x * 3 + y) % 11 == 0) setTile(x, y, TILE_BOLTED)
            }
        }

        // Foundry supports, furnace machinery and sanctum pillars.
        setTile(29, 29, TILE_WALL)
        setTile(29, 43, TILE_WALL)
        setTile(46, 29, TILE_WALL)
        setTile(46, 43, TILE_WALL)
        setTile(31, 31, TILE_ARCH)
        setTile(44, 41, TILE_ARCH)

        setTile(78, 30, TILE_WALL)
        setTile(90, 30, TILE_WALL)
        setTile(78, 44, TILE_WALL)
        setTile(90, 44, TILE_WALL)
        setTile(79, 7, TILE_WALL)
        setTile(89, 7, TILE_WALL)
        setTile(79, 20, TILE_WALL)
        setTile(89, 20, TILE_WALL)

        // Decorative arch markers on the principal thresholds.
        setTile(9, 27, TILE_ARCH)
        setTile(25, 35, TILE_ARCH)
        setTile(37, 22, TILE_ARCH)
        setTile(52, 13, TILE_ARCH)
        setTile(37, 51, TILE_ARCH)
        setTile(51, 59, TILE_ARCH)
        setTile(74, 35, TILE_ARCH)
        setTile(84, 24, TILE_ARCH)

        // Locked Iron Gate at the only entrance to Crimson Sanctum.
        for (x in GATE_X_FIRST..GATE_X_LAST) {
            setTile(x, GATE_Y, TILE_GATE_LOCKED)
        }
    }

    private fun carveRoom(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        floorType: Int
    ) {
        for (y in y1..y2) {
            for (x in x1..x2) {
                val boundary = x == x1 || x == x2 || y == y1 || y == y2
                setTile(x, y, if (boundary) TILE_WALL else floorType)
            }
        }
    }

    private fun carveHorizontalCorridor(
        x1: Int,
        x2: Int,
        centerY: Int,
        width: Int,
        floorType: Int
    ) {
        val half = width / 2
        val minY = centerY - half
        val maxY = minY + width - 1
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (y in minY..maxY) setTile(x, y, floorType)
        }
    }

    private fun carveVerticalCorridor(
        minX: Int,
        maxX: Int,
        y1: Int,
        y2: Int,
        floorType: Int
    ) {
        for (y in minOf(y1, y2)..maxOf(y1, y2)) {
            for (x in minX..maxX) setTile(x, y, floorType)
        }
    }

    fun unlockGate() {
        if (gateUnlocked) return
        gateUnlocked = true
        for (x in GATE_X_FIRST..GATE_X_LAST) {
            setTile(x, GATE_Y, TILE_GATE_OPEN)
        }
    }

    fun setTile(x: Int, y: Int, type: Int) {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return
        val index = y * WIDTH + x
        val previous = tiles[index]
        if (previous == type) return
        tiles[index] = type
        if (isTileSolid(previous) != isTileSolid(type)) {
            walkabilityRevision++
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
        for (tile in tiles) {
            val code = when (tile) {
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
