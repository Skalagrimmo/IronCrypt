package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.game.model.GameSaveSnapshot
import com.example.game.model.SaveSlotSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ironCryptSaveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ironcrypt_save"
)

class GameSaveRepository(private val context: Context) {

    private val keyHasSave = booleanPreferencesKey("has_save")
    private val keySavedAt = longPreferencesKey("saved_at_ms")
    private val keySavedHp = intPreferencesKey("saved_hp")
    private val keySavedMaxHp = intPreferencesKey("saved_max_hp")
    private val keySavedGold = intPreferencesKey("saved_gold")
    private val keySavedBosses = intPreferencesKey("saved_bosses")
    private val keyPayloadJson = stringPreferencesKey("save_payload_json")

    val saveSummaryFlow: Flow<SaveSlotSummary> = context.ironCryptSaveDataStore.data.map { prefs ->
        val hasSave = prefs[keyHasSave] ?: false
        if (!hasSave) {
            SaveSlotSummary(hasSave = false)
        } else {
            SaveSlotSummary(
                hasSave = true,
                savedAtEpochMs = prefs[keySavedAt] ?: 0L,
                hp = prefs[keySavedHp] ?: 120,
                maxHp = prefs[keySavedMaxHp] ?: 120,
                gold = prefs[keySavedGold] ?: 0,
                bossesDefeated = prefs[keySavedBosses] ?: 0,
                totalBosses = 2
            )
        }
    }

    suspend fun saveGame(snapshot: GameSaveSnapshot) {
        val json = snapshot.toJson()
        context.ironCryptSaveDataStore.edit { prefs ->
            prefs[keyHasSave] = true
            prefs[keySavedAt] = snapshot.savedAtEpochMs
            prefs[keySavedHp] = snapshot.playerHp
            prefs[keySavedMaxHp] = snapshot.playerMaxHp
            prefs[keySavedGold] = snapshot.playerGold
            prefs[keySavedBosses] = snapshot.bossesDefeated
            prefs[keyPayloadJson] = json
        }
    }

    suspend fun loadGame(): GameSaveSnapshot? {
        val prefs = context.ironCryptSaveDataStore.data.first()
        if (prefs[keyHasSave] != true) return null
        val json = prefs[keyPayloadJson] ?: return null
        return GameSaveSnapshot.fromJson(json)
    }

    suspend fun clearSave() {
        context.ironCryptSaveDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
