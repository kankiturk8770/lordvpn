package com.lordv2.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.components.LordAlert
import com.lordv2.app.ui.screens.*
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.ConnState
import com.lordv2.app.vpn.VpnState

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val MAIN_ROUTES = setOf("home", "configs", "servers", "settings")

@Composable
fun LordRoot() {
    val c = Lord.colors
    val settings by Repo.settings.collectAsState()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val importer = rememberImportController(nav)
    val startRoute = remember { if (Repo.settings.value.onboarded) "home" else "onboarding" }

    LaunchedEffect(Unit) { UiEvents.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { UiEvents.openAdd.collect { importer.open() } }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val anim = settings.animations

    CompositionLocalProvider(LocalImporter provides importer) {
        Scaffold(
            containerColor = c.bg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(data, containerColor = c.cardHi, contentColor = c.text, shape = RoundedCornerShape(14.dp))
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = route in MAIN_ROUTES,
                    enter = fadeIn(tween(200)) + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically(),
                ) {
                    LordBottomBar(route) { target ->
                        if (target != route) nav.navigate(target) {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            },
        ) { pad ->
            NavHost(
                navController = nav,
                startDestination = startRoute,
                modifier = Modifier.fillMaxSize().padding(pad),
                enterTransition = { if (anim) fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 30 } else EnterTransition.None },
                exitTransition = { if (anim) fadeOut(tween(140)) else ExitTransition.None },
                popEnterTransition = { if (anim) fadeIn(tween(220)) else EnterTransition.None },
                popExitTransition = { if (anim) fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 30 } else ExitTransition.None },
            ) {
                composable("onboarding") {
                    OnboardingScreen {
                        Repo.updateSettings { it.copy(onboarded = true) }
                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                    }
                }
                composable("home") { HomeScreen(nav) }
                composable("configs") { ConfigsScreen(nav) }
                composable("servers") { ServersScreen(nav) }
                composable("settings") { SettingsScreen(nav) }
                composable("subs") { SubscriptionsScreen(nav, openAdd = false) }
                composable("subs_add") { SubscriptionsScreen(nav, openAdd = true) }
                composable("stats") { StatisticsScreen(nav) }
                composable("logs") { LogsScreen(nav) }
                composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                    EditConfigScreen(nav, e.arguments?.getString("id") ?: "new")
                }
            }
        }
        AddConfigSheet(importer)
        GlobalDialogs(nav)
    }
}

@Composable
private fun LordBottomBar(route: String?, onNavigate: (String) -> Unit) {
    val c = Lord.colors
    val items = listOf(
        NavItem("home", "Home", Icons.Rounded.Home),
        NavItem("configs", "Configs", Icons.Rounded.Layers),
        NavItem("servers", "Servers", Icons.Rounded.Public),
        NavItem("settings", "Settings", Icons.Rounded.Settings),
    )
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.card.copy(alpha = 0.96f)).border(1.dp, c.stroke, shape).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items.forEach { item ->
                val sel = route == item.route
                val bg by animateColorAsState(if (sel) c.primary.copy(alpha = 0.16f) else c.card.copy(alpha = 0f), label = "bg")
                val tint by animateColorAsState(if (sel) c.cyan else c.muted, label = "tint")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(bg)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onNavigate(item.route) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, color = tint, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun GlobalDialogs(nav: NavHostController) {
    val dialog by UiEvents.dialog.collectAsState()
    val status by VpnState.status.collectAsState()
    val actions = LocalActions.current
    val d = dialog
    if (d != null) {
        LordAlert(
            title = d.title,
            message = d.message,
            confirm = d.primary to { UiEvents.dismiss(); d.onPrimary?.invoke(); Unit },
            dismiss = d.secondary?.let { s -> s to { UiEvents.dismiss(); d.onSecondary?.invoke(); Unit } },
            onDismiss = { UiEvents.dismiss() },
        )
    } else if (status.state == ConnState.ERROR && status.error != null) {
        LordAlert(
            title = "Connection Failed",
            message = "Unable to connect to this configuration.\n\n${status.error}",
            confirm = "Retry" to {
                VpnState.dismissError()
                status.profileId?.let { actions.connect(it) }
                Unit
            },
            dismiss = "Change Config" to {
                VpnState.dismissError()
                nav.navigate("configs") { launchSingleTop = true }
                Unit
            },
            onDismiss = { VpnState.dismissError() },
        )
    }
}
