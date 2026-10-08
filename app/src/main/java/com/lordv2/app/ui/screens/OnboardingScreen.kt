package com.lordv2.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.ConnState
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = Lord.colors
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            if (pager.currentPage < 2) TextButton(onClick = onDone) { Text("Skip", color = c.muted) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (page) {
                    0 -> LordLogo(132.dp)
                    1 -> WorldMap(Modifier.fillMaxWidth(), servers = listOf("DE", "US", "SG", "JP", "NL"), selected = "DE", home = "IR", state = ConnState.CONNECTED, animate = true)
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Feature(Icons.Rounded.Bolt); Feature(Icons.Rounded.Lock); Feature(Icons.Rounded.TouchApp)
                    }
                }
                Spacer(Modifier.height(40.dp))
                val (title, body) = when (page) {
                    0 -> "Welcome to Lord V2" to "A premium home for your VPN configurations."
                    1 -> "Your Configs.\nYour Connection.\nYour Control." to "Import VLESS, VMess, Trojan and Shadowsocks configs or subscriptions in seconds."
                    else -> "Fast, Private & Simple" to "One tap to connect. Live ping, speed and a kill switch when you need it."
                }
                Text(title, color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 34.sp)
                Spacer(Modifier.height(12.dp))
                Text(body, color = c.muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(3) { i ->
                val w by animateDpAsState(if (pager.currentPage == i) 24.dp else 8.dp, label = "dot")
                Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(w).clip(CircleShape).background(if (pager.currentPage == i) c.cyan else c.stroke))
            }
        }
        PrimaryButton(
            if (pager.currentPage == 2) "Get Started" else "Next",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp),
        ) {
            if (pager.currentPage == 2) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector) {
    val c = Lord.colors
    Box(Modifier.size(78.dp).clip(RoundedCornerShape(24.dp)).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.cyan, modifier = Modifier.size(36.dp))
    }
}
