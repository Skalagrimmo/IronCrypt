package com.example.game.engine

import com.example.game.model.DialogueChoice
import com.example.game.model.DialogueNode
import com.example.game.model.SpriteId

object DialogueScriptStore {

    fun getStartNode(npcId: String, gateUnlocked: Boolean): DialogueNode {
        return when (npcId) {
            "chronicler" -> if (!gateUnlocked) {
                DialogueNode(
                    id = "chr_root",
                    speakerUk = "Хроніст Тіней",
                    portrait = SpriteId.NPC_SHADOW,
                    textUk = "Крипта задихається від пари й проклятого заліза. Головна Брама зачинена — важіль сховано у північному крилі коридору.",
                    choices = listOf(
                        DialogueChoice("Хто охороняє Святилище?", nextNodeId = "chr_bosses"),
                        DialogueChoice("Чи є припаси для бою?", nextNodeId = "chr_supply"),
                        DialogueChoice("Вирушаю до коридору", nextNodeId = null)
                    )
                )
            } else {
                DialogueNode(
                    id = "chr_open",
                    speakerUk = "Хроніст Тіней",
                    portrait = SpriteId.NPC_SHADOW,
                    textUk = "Важіль спрацював! Залізна Брама на сході відчинена. Покінчи із Залізним Големом та Демоном Крипти.",
                    choices = listOf(
                        DialogueChoice("Відновити сили (Зцілення)", actionTag = "HEAL_PLAYER"),
                        DialogueChoice("До бою!", nextNodeId = null)
                    )
                )
            }

            "cultist" -> DialogueNode(
                id = "cul_root",
                speakerUk = "Відступник Культу",
                portrait = SpriteId.NPC_CULTIST,
                textUk = "Я залишив орден, коли машини вийшли з-під контролю. Візьми мій Тіньовий Капюшон — він прискорить твої кроки.",
                choices = listOf(
                    DialogueChoice("Прийняти Тіньовий Капюшон", actionTag = "GIFT_HOOD"),
                    DialogueChoice("Що за механізми попереду?", nextNodeId = "cul_lore"),
                    DialogueChoice("Залишити розмову", nextNodeId = null)
                )
            )

            else -> DialogueNode(
                id = "default",
                speakerUk = "Невідомий",
                portrait = SpriteId.NPC_SHADOW,
                textUk = "...",
                choices = listOf(DialogueChoice("Закрити", nextNodeId = null))
            )
        }
    }

    fun getNode(nodeId: String): DialogueNode? {
        return when (nodeId) {
            "chr_bosses" -> DialogueNode(
                id = "chr_bosses",
                speakerUk = "Хроніст Тіней",
                portrait = SpriteId.NPC_SHADOW,
                textUk = "Залізний Голем тисне бронею в ближньому бою, а Кривавий Демон метає тіньові сфери. Використовуй Ривок (i-frames), щоб ухилятися!",
                choices = listOf(
                    DialogueChoice("Дякую за пораду", nextNodeId = "chr_root"),
                    DialogueChoice("Вперед", nextNodeId = null)
                )
            )

            "chr_supply" -> DialogueNode(
                id = "chr_supply",
                speakerUk = "Хроніст Тіней",
                portrait = SpriteId.NPC_SHADOW,
                textUk = "Розбивай старі бочки в ливарні та відкривай скрині. Тримай запасне Червоне Зілля — воно врятує у скрутну мить.",
                choices = listOf(
                    DialogueChoice("Взяти зілля", actionTag = "GIFT_POTION"),
                    DialogueChoice("Закрити", nextNodeId = null)
                )
            )

            "cul_lore" -> DialogueNode(
                id = "cul_lore",
                speakerUk = "Відступник Культу",
                portrait = SpriteId.NPC_CULTIST,
                textUk = "Старий важіль біля шестерень вимикає засуви. У східній залі шукай скриню із Шолом Вартового для максимального захисту.",
                choices = listOf(
                    DialogueChoice("Зрозуміло", nextNodeId = null)
                )
            )

            else -> null
        }
    }
}
