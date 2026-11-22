package com.ycm50.subscribemeter.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SubscribeMeterTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val login by viewModel.login.collectAsStateWithLifecycle()
                MeterScreen(
                    state = state,
                    login = login,
                    onStartOpenDesignLogin = viewModel::startOpenDesignLogin,
                    onCancelOpenDesignLogin = viewModel::cancelOpenDesignLogin,
                    onRefresh = viewModel::refresh,
                    onSaveProvider = viewModel::saveProvider,
                    onDeleteProvider = viewModel::deleteProvider,
                    onSecretOf = viewModel::secretOf,
                    onExport = viewModel::exportConfig,
                    onImport = viewModel::importConfig,
                    onSetRefreshMinutes = viewModel::setRefreshMinutes,
                    onSetNotify = viewModel::setNotify,
                    onSetProxy = viewModel::setProxy,
                    onClearMessage = viewModel::clearMessage,
                )
            }
        }
    }
}