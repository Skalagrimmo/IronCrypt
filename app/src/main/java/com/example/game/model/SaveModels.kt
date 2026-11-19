package com.example.game.model

import org.json.JSONArray
import org.json.JSONObject

data class SavedItemStack(
    val itemId: String,
    val count: Int
)

data class SavedPropState(
    val index: Int,
    val active: Boolean,
    val used: Boolean
)

data class SavedEnemyState(
    val index: Int,
    val active: Boolean,
    val hp: Int,
    val x: Float,
    val y: Float,
    val facing: Direction
)

data class SavedPickupState(
    val x: Float,
    val y: Float,
    val itemId: String
)

data class SaveSlotSummary(
    val hasSave: Boolean = false,
    val savedAtEpochMs: Long = 0L,
    val hp: Int = 0,
    val maxHp: Int = 0,
    val gold: Int = 0,
    val bossesDefeated: Int = 0,
    val totalBosses: Int = 2
)

data class GameSaveSnapshot(
    val version: Int = 2,
    val savedAtEpochMs: Long,
    val playerX: Float,
    val playerY: Float,
    val playerFacing: Direction,
    val playerHp: Int,
    val playerMaxHp: Int,
    val playerStamina: Float,
    val playerGold: Int,
    val kills: Int,
    val bossesDefeated: Int,
    val gateUnlocked: Boolean,
    val giftedPotion: Boolean,
    val giftedHood: Boolean,
    val equippedWeaponId: String?,
    val equippedShieldId: String?,
    val equippedHelmetId: String?,
    val equippedCloakId: String?,
    val backpack: List<SavedItemStack>,
    val props: List<SavedPropState>,
    val enemies: List<SavedEnemyState>,
    val pickups: List<SavedPickupState>,
    val discoveredMask: String = ""
) {
    fun toJson(): String {
        val root = JSONObject().apply {
            put("version", version)
            put("savedAtEpochMs", savedAtEpochMs)
            put("playerX", playerX.toDouble())
            put("playerY", playerY.toDouble())
            put("playerFacing", playerFacing.name)
            put("playerHp", playerHp)
            put("playerMaxHp", playerMaxHp)
            put("playerStamina", playerStamina.toDouble())
            put("playerGold", playerGold)
            put("kills", kills)
            put("bossesDefeated", bossesDefeated)
            put("gateUnlocked", gateUnlocked)
            put("giftedPotion", giftedPotion)
            put("giftedHood", giftedHood)
            put("equippedWeaponId", equippedWeaponId ?: JSONObject.NULL)
            put("equippedShieldId", equippedShieldId ?: JSONObject.NULL)
            put("equippedHelmetId", equippedHelmetId ?: JSONObject.NULL)
            put("equippedCloakId", equippedCloakId ?: JSONObject.NULL)
            put("discoveredMask", discoveredMask)

            val bpArr = JSONArray()
            backpack.forEach { stack ->
                bpArr.put(JSONObject().apply {
                    put("itemId", stack.itemId)
                    put("count", stack.count)
                })
            }
            put("backpack", bpArr)

            val propArr = JSONArray()
            props.forEach { p ->
                propArr.put(JSONObject().apply {
                    put("index", p.index)
                    put("active", p.active)
                    put("used", p.used)
                })
            }
            put("props", propArr)

            val enemyArr = JSONArray()
            enemies.forEach { e ->
                enemyArr.put(JSONObject().apply {
                    put("index", e.index)
                    put("active", e.active)
                    put("hp", e.hp)
                    put("x", e.x.toDouble())
                    put("y", e.y.toDouble())
                    put("facing", e.facing.name)
                })
            }
            put("enemies", enemyArr)

            val pickupArr = JSONArray()
            pickups.forEach { pk ->
                pickupArr.put(JSONObject().apply {
                    put("x", pk.x.toDouble())
                    put("y", pk.y.toDouble())
                    put("itemId", pk.itemId)
                })
            }
            put("pickups", pickupArr)
        }
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String): GameSaveSnapshot? {
            if (raw.isBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                val facingName = root.optString("playerFacing", Direction.EAST.name)
                val facing = runCatching { Direction.valueOf(facingName) }.getOrDefault(Direction.EAST)

                val bpList = buildList {
                    val arr = root.optJSONArray("backpack") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(SavedItemStack(obj.getString("itemId"), obj.getInt("count")))
                    }
                }

                val propList = buildList {
                    val arr = root.optJSONArray("props") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            SavedPropState(
                                index = obj.getInt("index"),
                                active = obj.getBoolean("active"),
                                used = obj.getBoolean("used")
                            )
                        )
                    }
                }

                val enemyList = buildList {
                    val arr = root.optJSONArray("enemies") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val eFacing = runCatching {
                            Direction.valueOf(obj.optString("facing", Direction.SOUTH.name))
                        }.getOrDefault(Direction.SOUTH)
                        add(
                            SavedEnemyState(
                                index = obj.getInt("index"),
                                active = obj.getBoolean("active"),
                                hp = obj.getInt("hp"),
                                x = obj.getDouble("x").toFloat(),
                                y = obj.getDouble("y").toFloat(),
                                facing = eFacing
                            )
                        )
                    }
                }

                val pickupList = buildList {
                    val arr = root.optJSONArray("pickups") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            SavedPickupState(
                                x = obj.getDouble("x").toFloat(),
                                y = obj.getDouble("y").toFloat(),
                                itemId = obj.getString("itemId")
                            )
                        )
                    }
                }

                fun optNullableString(key: String): String? {
                    return if (root.isNull(key)) null else root.optString(key).takeIf { it.isNotBlank() }
                }

                GameSaveSnapshot(
                    version = root.optInt("version", 1),
                    savedAtEpochMs = root.getLong("savedAtEpochMs"),
                    playerX = root.getDouble("playerX").toFloat(),
                    playerY = root.getDouble("playerY").toFloat(),
                    playerFacing = facing,
                    playerHp = root.getInt("playerHp"),
                    playerMaxHp = root.getInt("playerMaxHp"),
                    playerStamina = root.getDouble("playerStamina").toFloat(),
                    playerGold = root.getInt("playerGold"),
                    kills = root.getInt("kills"),
                    bossesDefeated = root.getInt("bossesDefeated"),
                    gateUnlocked = root.getBoolean("gateUnlocked"),
                    giftedPotion = root.optBoolean("giftedPotion", false),
                    giftedHood = root.optBoolean("giftedHood", false),
                    equippedWeaponId = optNullableString("equippedWeaponId"),
                    equippedShieldId = optNullableString("equippedShieldId"),
                    equippedHelmetId = optNullableString("equippedHelmetId"),
                    equippedCloakId = optNullableString("equippedCloakId"),
                    backpack = bpList,
                    props = propList,
                    enemies = enemyList,
                    pickups = pickupList,
                    discoveredMask = root.optString("discoveredMask", "")
                )
            }.getOrNull()
        }
    }
}
