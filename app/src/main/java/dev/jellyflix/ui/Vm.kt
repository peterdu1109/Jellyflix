package dev.jellyflix.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jellyflix.JellyflixApp
import dev.jellyflix.data.AppContainer

@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as JellyflixApp).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = rememberContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
