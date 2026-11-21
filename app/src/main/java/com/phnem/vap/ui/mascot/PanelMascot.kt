package com.phnem.vap.ui.mascot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phnem.vap.ui.components.LocalPanelDark
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.PanelText

/**
 * Пустое состояние внутри шторки: маскот и одна строка текста панели. Тема — панели ([LocalPanelDark]),
 * а не приложения: шторки плеера всегда тёмные.
 */
@Composable
fun PanelMascot(scene: MascotScene, text: String, modifier: Modifier = Modifier, size: Dp = 140.dp) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MascotIllustration(scene, Modifier.size(size), isDark = LocalPanelDark.current)
        PanelText(text, color = PanelColors.Muted, modifier = Modifier.padding(horizontal = 12.dp))
    }
}
