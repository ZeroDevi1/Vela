package com.vela.app.ui.screens.dashboard.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vela.app.BuildConfig
import com.vela.data.update.AUTO_DOWNLOAD_MIRROR_ID
import com.vela.data.update.CUSTOM_DOWNLOAD_MIRROR_ID
import com.vela.data.update.DIRECT_DOWNLOAD_MIRROR_ID
import com.vela.data.update.compareVersions
import com.vela.shared.R
import com.vela.app.ui.components.tvFocusHighlight
import com.vela.app.ui.components.tvInitialFocus
import com.vela.shared.update.AppUpdateDialogs
import com.vela.shared.update.AppUpdateUiState
import com.vela.shared.update.AppUpdateViewModel

private const val GithubUrl = "https://github.com/ZeroDevi1/Vela"
private const val PrivacyUrl = "$GithubUrl/blob/main/PRIVACY"
private const val LicenseUrl = "$GithubUrl/blob/main/LICENSE"
private const val SupportUrl = "https://www.buymeacoffee.com/ZeroDevi1"
private const val ContactEmail = "ZeroDevi10503@gmail.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBackPressed: () -> Unit = {}) {
    val context = LocalContext.current
    val updateViewModel: AppUpdateViewModel = viewModel {
        AppUpdateViewModel(context.applicationContext as Application, BuildConfig.VERSION_NAME)
    }
    val state by updateViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed, modifier = Modifier.tvFocusHighlight(CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back_button))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().tvInitialFocus(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(painterResource(R.drawable.vela_logo), null, Modifier.size(64.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.about_update_current, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { TvSettingsSectionLabel(stringResource(R.string.about_section_update)) }
                item {
                    TvSettingsSection {
                        TvSettingsItem(
                            icon = Icons.Rounded.SystemUpdate,
                            title = stringResource(R.string.about_check_update),
                            subtitle = checkUpdateSubtitle(state),
                            isLoading = state.checking,
                            onClick = updateViewModel::checkForUpdate
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TvSettingsItem(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.about_update_download_mirror),
                            subtitle = mirrorSubtitle(state),
                            onClick = updateViewModel::openMirrorPicker
                        )
                    }
                }
                item { TvSettingsSectionLabel(stringResource(R.string.about_section_project)) }
                item {
                    TvSettingsSection {
                        TvSettingsItem(
                            icon = Icons.Rounded.Code,
                            title = stringResource(R.string.about_source_code),
                            subtitle = "ZeroDevi1 / Vela",
                            onClick = { openUrl(context, GithubUrl) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TvSettingsItem(
                            icon = Icons.Rounded.Policy,
                            title = stringResource(R.string.about_privacy_policy),
                            subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                            onClick = { openUrl(context, PrivacyUrl) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TvSettingsItem(
                            icon = Icons.Rounded.Gavel,
                            title = stringResource(R.string.about_open_source_license),
                            subtitle = stringResource(R.string.about_open_source_license_subtitle),
                            onClick = { openUrl(context, LicenseUrl) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TvSettingsItem(
                            icon = Icons.Rounded.Email,
                            title = stringResource(R.string.about_contact_developer),
                            subtitle = stringResource(R.string.about_contact_developer_subtitle),
                            onClick = { composeEmail(context) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TvSettingsItem(
                            icon = Icons.Rounded.LocalCafe,
                            title = stringResource(R.string.about_buy_me_a_coffee),
                            subtitle = stringResource(R.string.about_author),
                            onClick = { openUrl(context, SupportUrl) }
                        )
                    }
                }
            }
        }
    }
    AppUpdateDialogs(viewModel = updateViewModel, uiState = state)
}

private fun openUrl(context: Context, url: String) {
    launchIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun composeEmail(context: Context) {
    launchIntent(
        context,
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$ContactEmail")
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.about_feedback_subject))
        }
    )
}

private fun launchIntent(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun checkUpdateSubtitle(state: AppUpdateUiState): String {
    val error = state.checkError
    val release = state.release
    return when {
        state.checking -> stringResource(R.string.about_check_update_checking)
        error != null -> error
        state.isNewer && release != null ->
            stringResource(R.string.about_update_available, release.versionName)
        release != null && compareVersions(release.versionName, state.currentVersion) < 0 ->
            stringResource(R.string.about_update_behind, release.versionName)
        release != null ->
            stringResource(R.string.about_update_latest, release.versionName)
        else -> stringResource(R.string.about_check_update_subtitle)
    }
}

@Composable
private fun mirrorSubtitle(state: AppUpdateUiState): String {
    return when (state.mirror.id) {
        AUTO_DOWNLOAD_MIRROR_ID -> stringResource(R.string.about_update_mirror_auto)
        DIRECT_DOWNLOAD_MIRROR_ID -> stringResource(R.string.about_update_mirror_direct)
        CUSTOM_DOWNLOAD_MIRROR_ID ->
            state.customPrefix.trimEnd('/').ifBlank { stringResource(R.string.about_update_mirror_custom) }
        else -> state.mirror.label
    }
}
