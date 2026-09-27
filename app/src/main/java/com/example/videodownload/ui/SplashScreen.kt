package com.example.videodownload.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videodownload.R
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    val scale = remember { Animatable(0.4f) }
    val alpha = remember { Animatable(0f) }

    // Animación de pulso de luz de fondo
    val infiniteTransition = rememberInfiniteTransition(label = "PulseGlow")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    LaunchedEffect(key1 = true) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 400)
        )
        delay(1100)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .scale(scale.value)
                .alpha(alpha.value)
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                // Anillo de brillo pulsante en neón azul
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .background(Color(0xFF38BDF8).copy(alpha = 0.4f), CircleShape)
                )

                // Contenedor principal con borde de neón
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .border(2.dp, Color(0xFF38BDF8), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_jpdown_foreground),
                        contentDescription = "Logo JPDOWNLOAD",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Text(
                text = "JPDOWNLOAD",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )

            Text(
                text = "Descargador de Videos HD",
                color = Color(0xFF38BDF8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
