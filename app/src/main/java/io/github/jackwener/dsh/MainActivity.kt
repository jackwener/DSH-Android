package io.github.jackwener.dsh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.core.account.AccountRepository
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.feature.account.AccountToastHost
import io.github.jackwener.dsh.feature.account.LocalAccount
import io.github.jackwener.dsh.feature.account.SignInDialog
import io.github.jackwener.dsh.feature.account.PlatformWebScreen
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.feature.automation.AutomationScreen
import io.github.jackwener.dsh.feature.automation.RuleDetailScreen
import io.github.jackwener.dsh.feature.conversation.HomeScreen
import io.github.jackwener.dsh.feature.conversation.HomeViewModel
import io.github.jackwener.dsh.feature.settings.EnhanceStep
import io.github.jackwener.dsh.feature.settings.Onboarding
import io.github.jackwener.dsh.feature.settings.shellAccessOf
import io.github.jackwener.dsh.feature.settings.NotificationAppsScreen
import io.github.jackwener.dsh.feature.settings.SettingsScreen
import io.github.jackwener.dsh.feature.settings.SettingsSection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository, secrets: SecretStore, account: AccountRepository) : ViewModel() {
    private val deepSeek = secrets.read() != null || account.signedIn
    /** null = still loading (keeps the splash up). Second: a model credential exists — an API key, a
     *  DeepSeek account login (cached from the engine), or an enabled third-party provider. */
    val boot = settings.settings.map { it.theme to (deepSeek || it.providers.any { p -> p.enabled }) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * The app opens straight into the conversation (dsh home). Onboarding is only the credential
 * choice when there is none (dsh: 登录 DeepSeek 账号 / 使用 API Key); everything else (Android
 * permissions) is asked just in time from the composer slot.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var account: AccountRepository
    private var pendingSession by mutableStateOf<String?>(null)
    /** Settings section to open (EXTRA_SECTION, e.g. the quick panel's 登录 / 添加 API Key / 打开自检). */
    private var pendingSection by mutableStateOf<String?>(null)
    /** The sign-in dialog's 添加 API Key while onboarding expands the key card in place. */
    private var onboardingKey by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only for a fresh launch: after recreation (rotation, theme) the user may have moved on.
        if (savedInstanceState == null) {
            pendingSession = intent.getStringExtra(EXTRA_SESSION)
            pendingSection = intent.getStringExtra(EXTRA_SECTION)
        }
        handleAppLink(intent)
        setContent {
            val vm: MainViewModel = hiltViewModel()
            val boot by vm.boot.collectAsStateWithLifecycle()
            val theme = boot?.first
            splash.setKeepOnScreenCondition { theme == null }
            DshTheme(theme ?: ThemeMode.System) {
                val c = Dsh.colors
                LaunchedEffect(c.isDark) { account.darkTheme = c.isDark }
                // Platform pages (sign-in, 充值, 用量) cover the app in its own WebView screen.
                val platformPage by account.page.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalAccount provides account) {
                    Box(Modifier.fillMaxSize().background(c.bgBase)) {
                        boot?.let { AppNav(it.second, platformPage == null) }
                        platformPage?.let { PlatformWebScreen(it, account) }
                        AccountToastHost(account)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSession = intent.getStringExtra(EXTRA_SESSION)
        pendingSection = intent.getStringExtra(EXTRA_SECTION)
        handleAppLink(intent)
    }

    /**
     * `dsh-android://account/login?result=…` from the engine's sign-in completion page when it was
     * opened outside the app (the copied sign-in link); the in-app screen intercepts it itself.
     */
    private fun handleAppLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "dsh-android" && data.host == "account") account.onSignInReturned()
    }

    @Composable
    private fun AppNav(hasCredentials: Boolean, dialogs: Boolean) {
        val nav = rememberNavController()
        val home: HomeViewModel = hiltViewModel()
        val accountState by account.state.collectAsStateWithLifecycle()
        LaunchedEffect(pendingSession) {
            pendingSession?.let { id -> home.open(id); nav.popBackStack("home", inclusive = false); pendingSession = null }
        }
        LaunchedEffect(pendingSection) {
            pendingSection?.let { sec ->
                pendingSection = null
                if (nav.currentDestination?.route != "onboarding") nav.navigate("settings?section=$sec") { launchSingleTop = true }
            }
        }
        val start = remember { if (hasCredentials) "home" else "onboarding" }
        // The dialog is a window of its own: hidden while the Platform screen is up.
        if (accountState.loginVisible && dialogs) SignInDialog(account, onUseApiKey = {
            if (nav.currentDestination?.route == "onboarding") onboardingKey = true
            else nav.navigate("settings?section=Model")
        })
        NavHost(nav, startDestination = start, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            composable("onboarding") {
                Onboarding(showKey = onboardingKey, onShowKey = { onboardingKey = it },
                    onThirdParty = {
                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                        nav.navigate("settings?section=Model")
                    },
                    onDone = {
                        // Optional 增强能力 step (Shizuku) unless it is already set up.
                        val next = if (shellAccessOf(this@MainActivity).refresh().ready) "home" else "enhance"
                        nav.navigate(next) { popUpTo("onboarding") { inclusive = true } }
                    })
            }
            composable("enhance") { EnhanceStep(onDone = { nav.navigate("home") { popUpTo("enhance") { inclusive = true } } }) }
            composable("home") {
                HomeScreen(home,
                    onSettings = { section -> nav.navigate("settings?section=${section ?: ""}") },
                    onAutomation = { nav.navigate("automation") })
            }
            composable("settings?section={section}", arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = "" })) { e ->
                SettingsScreen(SettingsSection.of(e.arguments?.getString("section")), onBack = { nav.popBackStack() }, onNotificationApps = { nav.navigate("notif_apps") },
                    onAppNotes = { nav.navigate("app_notes") })
            }
            composable("app_notes") { io.github.jackwener.dsh.feature.settings.AppNotesScreen(onBack = { nav.popBackStack() }) }
            composable("notif_apps") { NotificationAppsScreen(onBack = { nav.popBackStack() }) }
            composable("automation") {
                AutomationScreen(onBack = { nav.popBackStack() },
                    onNew = { prefill -> home.newSession(prefill); nav.popBackStack("home", inclusive = false) },
                    onOpenRule = { nav.navigate("rule/$it") })
            }
            composable("rule/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                RuleDetailScreen(onBack = { nav.popBackStack() }, onOpenSession = { sid -> home.open(sid); nav.popBackStack("home", inclusive = false) })
            }
        }
    }

    companion object {
        const val EXTRA_SESSION = "session"
        /** A SettingsSection name to open on arrival. */
        const val EXTRA_SECTION = "settings_section"
    }
}
