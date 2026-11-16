package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.game.model.ActiveDialogue
import com.example.game.model.AtlasBitmapStore
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.CharcoalShadow
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronPanel
import com.example.ui.theme.IronSurfaceHigh
import com.example.ui.theme.MoltenBrass
import kotlinx.coroutines.delay

@Composable
fun DialogueOverlay(
    dialogue: ActiveDialogue,
    atlasStore: AtlasBitmapStore,
    onSelectChoice: (nextNodeId: String?, actionTag: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val node = dialogue.node
    var visibleChars by remember(node.id) { mutableIntStateOf(0) }

    LaunchedEffect(node.id) {
        visibleChars = 0
        for (i in 1..node.textUk.length) {
            visibleChars = i
            delay(14L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalShadow.copy(alpha = 0.55f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IronPanel.copy(alpha = 0.96f))
                .border(2.dp, MoltenBrass.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                .clickable { visibleChars = node.textUk.length }
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Character pixel-art portrait
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(IronSurfaceHigh)
                    .border(1.5.dp, IronBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = atlasStore.getSpriteBitmap(node.portrait),
                    contentDescription = node.speakerUk,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.speakerUk,
                    style = MaterialTheme.typography.titleMedium,
                    color = MoltenBrass
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = node.textUk.take(visibleChars),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AgedParchment
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    node.choices.forEachIndexed { idx, choice ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(IronSurfaceHigh)
                                .border(1.dp, MoltenBrass.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                .clickable { onSelectChoice(choice.nextNodeId, choice.actionTag) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("dialogue_choice_$idx"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = choice.labelUk,
                                style = MaterialTheme.typography.labelMedium,
                                color = AgedParchment
                            )
                        }
                    }
                }
            }
        }
    }
}
