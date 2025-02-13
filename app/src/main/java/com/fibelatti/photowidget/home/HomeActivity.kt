package com.fibelatti.photowidget.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.app.ShareCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fibelatti.photowidget.BuildConfig
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.configure.PhotoWidgetConfigureActivity
import com.fibelatti.photowidget.configure.appWidgetId
import com.fibelatti.photowidget.configure.aspectRatio
import com.fibelatti.photowidget.configure.sharedPhotos
import com.fibelatti.photowidget.licenses.OssLicensesActivity
import com.fibelatti.photowidget.model.PhotoWidgetAspectRatio
import com.fibelatti.photowidget.platform.AppTheme
import com.fibelatti.photowidget.platform.ComposeBottomSheetDialog
import com.fibelatti.photowidget.platform.SelectionDialog
import com.fibelatti.photowidget.preferences.Appearance
import com.fibelatti.photowidget.preferences.UserPreferencesStorage
import com.fibelatti.photowidget.preferences.WidgetDefaultsActivity
import com.fibelatti.photowidget.widget.PhotoWidgetProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    private val homeViewModel by viewModels<HomeViewModel>()

    @Inject
    lateinit var userPreferencesStorage: UserPreferencesStorage

    private var preparedIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                val currentWidgets by homeViewModel.currentWidgets.collectAsStateWithLifecycle()

                HomeScreen(
                    onCreateNewWidgetClick = ::createNewWidget,
                    currentWidgets = currentWidgets,
                    onCurrentWidgetClick = ::editExistingWidget,
                    onDefaultsClick = ::showDefaults,
                    onAppearanceClick = ::showAppearancePicker,
                    onColorsClick = ::showAppColorsPicker,
                    onSendFeedbackClick = ::sendFeedback,
                    onRateClick = ::rateApp,
                    onShareClick = ::shareApp,
                    onHelpClick = ::showHelp,
                    onPrivacyPolicyClick = ::openPrivacyPolicy,
                    onViewLicensesClick = ::viewOpenSourceLicenses,
                )
            }
        }

        checkIntent()
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.loadCurrentWidgets(ids = PhotoWidgetProvider.ids(context = this))
    }

    @Suppress("DEPRECATION")
    private fun checkIntent() {
        if (!intent.hasExtra(Intent.EXTRA_STREAM)) return

        preparedIntent = Intent(this, PhotoWidgetConfigureActivity::class.java).apply {
            sharedPhotos = when {
                Intent.ACTION_SEND == intent.action -> {
                    (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let(::listOf)
                }

                Intent.ACTION_SEND_MULTIPLE == intent.action -> {
                    intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.mapNotNull { it as? Uri }
                }

                else -> null
            }
        }

        val size = preparedIntent?.sharedPhotos?.size ?: 0
        if (size == 0) {
            preparedIntent = null
            return
        }

        MaterialAlertDialogBuilder(this)
            .setMessage(resources.getQuantityString(R.plurals.photo_widget_home_share_received, size, size))
            .setPositiveButton(R.string.photo_widget_action_got_it) { _, _ -> }
            .show()
    }

    private fun createNewWidget(aspectRatio: PhotoWidgetAspectRatio) {
        val intent = (preparedIntent ?: Intent(this, PhotoWidgetConfigureActivity::class.java)).apply {
            this.aspectRatio = aspectRatio
        }

        preparedIntent = null

        startActivity(intent)
    }

    private fun editExistingWidget(appWidgetId: Int) {
        val intent = (preparedIntent ?: Intent(this, PhotoWidgetConfigureActivity::class.java)).apply {
            this.appWidgetId = appWidgetId
        }

        preparedIntent = null

        startActivity(intent)
    }

    private fun showHelp() {
        ComposeBottomSheetDialog(context = this) {
            HelpScreen()
        }.show()
    }

    private fun showDefaults() {
        startActivity(Intent(this, WidgetDefaultsActivity::class.java))
    }

    private fun showAppearancePicker() {
        SelectionDialog.show(
            context = this,
            title = getString(R.string.photo_widget_home_appearance),
            options = Appearance.entries,
            optionName = { appearance ->
                getString(
                    when (appearance) {
                        Appearance.FOLLOW_SYSTEM -> R.string.preferences_appearance_follow_system
                        Appearance.LIGHT -> R.string.preferences_appearance_light
                        Appearance.DARK -> R.string.preferences_appearance_dark
                    },
                )
            },
            onOptionSelected = { newAppearance ->
                userPreferencesStorage.appearance = newAppearance

                val mode = when (newAppearance) {
                    Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }

                AppCompatDelegate.setDefaultNightMode(mode)
            },
        )
    }

    private fun showAppColorsPicker() {
        SelectionDialog.show(
            context = this,
            title = getString(R.string.photo_widget_home_dynamic_colors),
            options = listOf(true, false),
            optionName = { value ->
                getString(
                    if (value) {
                        R.string.preferences_dynamic_colors_enabled
                    } else {
                        R.string.preferences_dynamic_colors_disabled
                    },
                )
            },
            onOptionSelected = { newValue ->
                userPreferencesStorage.dynamicColors = newValue

                ActivityCompat.recreate(this)
            },
        )
    }

    private fun shareApp() {
        ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setChooserTitle(R.string.share_title)
            .setText(getString(R.string.share_text, APP_URL))
            .startChooser()
    }

    private fun sendFeedback() {
        val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf("appsupport@fibelatti.com"))
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Material Photo Widget (${BuildConfig.VERSION_NAME}) — Feature request / Bug report",
            )
        }

        startActivity(Intent.createChooser(emailIntent, getString(R.string.photo_widget_home_feedback)))
    }

    private fun rateApp() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APP_URL)))
    }

    private fun openPrivacyPolicy() {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://fibelatti.github.io/photo-widget/"),
            ),
        )
    }

    private fun viewOpenSourceLicenses() {
        startActivity(Intent(this, OssLicensesActivity::class.java))
    }

    private companion object {

        private const val APP_URL = "https://play.google.com/store/apps/details?id=com.fibelatti.photowidget"
    }
}
