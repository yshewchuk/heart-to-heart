package com.hearttoheart.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearttoheart.app.data.MessageCategory
import com.hearttoheart.app.ui.theme.*

/**
 * A circular button for selecting a message category.
 */
@Composable
fun CategoryButton(
    category: MessageCategory,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLongPressComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    
    val backgroundColor = when (category) {
        MessageCategory.FLUTTER -> FlutterColor
        MessageCategory.NUDGE -> NudgeColor
        MessageCategory.HEARTBEAT -> HeartbeatColor
        MessageCategory.LIFELINE -> LifelineColor
    }
    
    val animatedColor by animateColorAsState(
        targetValue = if (isSelected) backgroundColor else backgroundColor.copy(alpha = 0.3f),
        animationSpec = tween(200),
        label = "color"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .pointerInput(category) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            try {
                                awaitRelease()
                            } finally {
                                isPressed = false
                            }
                        },
                        onTap = {
                            onSelect()
                        }
                    )
                }
        ) {
            Surface(
                shape = CircleShape,
                color = animatedColor,
                shadowElevation = if (isSelected) 8.dp else 2.dp,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = category.emoji,
                        fontSize = 32.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) backgroundColor else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        
        Text(
            text = category.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp)
        )
    }
}
