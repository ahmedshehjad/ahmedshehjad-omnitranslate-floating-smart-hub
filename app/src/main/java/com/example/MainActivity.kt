package com.example

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.ClipboardHistoryScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.OfflineModelsScreen
import com.example.ui.screens.OcrScreen
import com.example.ui.screens.TranslatorScreen
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.OmniTranslateTheme
import com.example.ui.viewmodel.OmniViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: OmniViewModel by viewModels()
    val adManager = com.example.ads.AdManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        adManager.loadInterstitialAd(this)

        handleIncomingIntent(intent)

        setContent {
            OmniTranslateTheme {
                OmniMainApp(
                    viewModel = viewModel,
                    onOpenBubbleToggle = {
                        if (Settings.canDrawOverlays(this)) {
                            viewModel.toggleFloatingBubble(this)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        var receivedText: String? = null

        // 1. Process Text Context Menu (highlight text in any app -> "Translate")
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            receivedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }
        // 2. Share Sheet Intent
        else if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            receivedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }

        if (!receivedText.isNullOrBlank()) {
            viewModel.updateSourceText(receivedText)
            viewModel.performTranslation(saveToHistory = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniMainApp(
    viewModel: OmniViewModel,
    onOpenBubbleToggle: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isBubbleRunning by viewModel.isBubbleRunning.collectAsState()
    var showTelegramDialog by remember { androidx.compose.runtime.mutableStateOf(true) }

    if (showTelegramDialog) {
        com.example.ui.components.TelegramPromoDialog(
            onDismiss = { showTelegramDialog = false }
        )
    }

    // If on subscreen, BackHandler returns to Dashboard
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "OmniTranslate",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Glowing live bubble badge
                        Surface(
                            shape = CircleShape,
                            color = if (isBubbleRunning) EmeraldGlow.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isBubbleRunning) EmeraldGlow else Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBubbleRunning) "LIVE" else "IDLE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isBubbleRunning) EmeraldGlow else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showTelegramDialog = true },
                        modifier = Modifier.testTag("topbar_telegram_action_button")
                    ) {
                        Box(
                            modifier = Modifier.size(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.getmoddevice_logo),
                                contentDescription = "GETMODDEVICE",
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                            )
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_telegram_logo),
                                contentDescription = "Telegram",
                                modifier = Modifier
                                    .size(12.dp)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenBubbleToggle,
                        modifier = Modifier.testTag("topbar_bubble_action_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Toggle Bubble",
                            tint = if (isBubbleRunning) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                com.example.ui.components.AdMobBannerView()
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("bottom_navigation_bar")
                ) {
                    val items = listOf(
                        Triple(0, "Hub", Icons.Default.Home),
                        Triple(1, "Studio", Icons.Default.Translate),
                        Triple(2, "Clipboard", Icons.Default.Assignment),
                        Triple(3, "Vision OCR", Icons.Default.DocumentScanner),
                        Triple(4, "Offline", Icons.Default.CloudDownload)
                    )

                    items.forEach { (index, title, icon) ->
                        val isSelected = selectedTab == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = title
                                )
                            },
                            label = {
                                Text(
                                    text = title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("nav_item_${title.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Crossfade(targetState = selectedTab, label = "tab_crossfade") { tab ->
                when (tab) {
                    0 -> DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToTab = { selectedTab = it }
                    )
                    1 -> TranslatorScreen(viewModel = viewModel)
                    2 -> ClipboardHistoryScreen(viewModel = viewModel)
                    3 -> OcrScreen(
                        viewModel = viewModel,
                        onNavigateToTranslator = { selectedTab = 1 }
                    )
                    4 -> OfflineModelsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
