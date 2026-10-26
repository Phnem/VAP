package com.phnem.vap

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.ui.update.UpdateSheet
import com.phnem.vap.update.AppUpdateScheduler
import com.phnem.vap.update.UpdateRuntime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import com.phnem.vap.library.*
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.ui.library.LibraryRoot
import com.phnem.vap.ui.onboarding.AppLocale
import com.phnem.vap.ui.onboarding.AppPrefs
import com.phnem.vap.ui.onboarding.FirstRunScreen
import com.phnem.vap.ui.theme.VapTheme

class MainActivity : ComponentActivity() {
    private val library by viewModels<LibraryViewModel>()
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { library.startObserving(); library.rescan() }
    private val permissionsPrefs by lazy { getSharedPreferences("media_onboarding", MODE_PRIVATE) }
    private val appPrefs by lazy { AppPrefs(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase, AppPrefs(newBase).language))
    }

    private fun requestVideoAccess() {
        val asked = permissionsPrefs.getBoolean("requested", false)
        if (VideoPermissions.access(this) == VideoAccess.NONE && asked &&
            !shouldShowRequestPermissionRationale(VideoPermissions.requested().first())) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } else {
            permissionsPrefs.edit().putBoolean("requested", true).apply()
            permissionRequest.launch(VideoPermissions.requested())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Встроенное обновление: проверка релиза при запуске и периодическая фоновая (только для сборки, подписанной ключом автора).
        val updates = UpdateRuntime.get(this)
        AppUpdateScheduler.sync(this, updates.manager.mode)
        updates.manager.refreshInBackground()
        setContent {
            var theme by remember { mutableStateOf(appPrefs.theme) }
            var firstRun by remember { mutableStateOf(!appPrefs.firstRunDone) }
            // Окно обновления при запуске: показывается, когда пользователю есть что сделать, и держится, пока
            // с релизом что-то происходит (иначе оно пропадало бы в момент нажатия «Обновить»).
            val updateOffer by updates.manager.startupOffer.collectAsStateWithLifecycle(initialValue = false)
            val updateKeep by updates.manager.startupOfferKeep.collectAsStateWithLifecycle(initialValue = false)
            var showUpdate by remember { mutableStateOf(false) }
            LaunchedEffect(updateOffer, firstRun) { if (updateOffer && !firstRun) showUpdate = true }
            LaunchedEffect(updateKeep) { if (!updateKeep) showUpdate = false }
            val dark = theme.isDark(isSystemInDarkTheme())
            // Значки системных панелей следуют теме приложения, а не системы: тёмная тема на светлой системе иначе даёт тёмные значки на тёмном.
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            VapTheme(darkTheme = dark) {
                LibraryRoot(
                    onPlay = { items, index ->
                        startActivity(PlayerActivity.intent(this, items, index, portraitHint = library.isPortrait(items.getOrNull(index)?.uri)))
                    },
                    onRequestVideoAccess = ::requestVideoAccess,
                    theme = theme,
                    onTheme = { theme = it; appPrefs.theme = it },
                    viewModel = library,
                )
                if (showUpdate) UpdateSheet(manual = false, onDismiss = { showUpdate = false })
                // Первый запуск: язык и тема, пока в фоне готовятся превью библиотеки.
                if (firstRun) {
                    FirstRunScreen(
                        language = appPrefs.language,
                        theme = theme,
                        onLanguage = { AppLocale.change(this@MainActivity, it) },
                        onTheme = { theme = it; appPrefs.theme = it },
                        onContinue = { appPrefs.firstRunDone = true; firstRun = false },
                    )
                }
            }
        }
        if (savedInstanceState == null && !permissionsPrefs.getBoolean("requested", false) && VideoPermissions.access(this) == VideoAccess.NONE) {
            permissionsPrefs.edit().putBoolean("requested", true).apply()
            permissionRequest.launch(VideoPermissions.requested())
        }
    }
    private var startedOnce = false

    override fun onStart() {
        super.onStart()
        library.startObserving()
        // Возврат из фона: релиз мог выйти, пока приложение лежало в памяти. Первый запуск проверил его в onCreate.
        if (startedOnce) UpdateRuntime.get(this).manager.refreshInBackground()
        startedOnce = true
    }
    override fun onResume() { super.onResume(); library.startObserving(); library.rescan() }
    override fun onStop() { library.stopObserving(); super.onStop() }
}
