package ru.finhelper.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.finhelper.app.di.AppContainer

/** Контейнер зависимостей, доступный экранам для создания моделей представления. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("Контейнер зависимостей не задан") }

/**
 * Создает модель представления экрана, передавая ей зависимости из контейнера.
 * Экраны разделены на две функции: «Route» получает состояние из модели, а
 * «Screen» только отображает переданное состояние и потому не зависит от данных.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { "Владелец моделей представления не задан" },
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(viewModelStoreOwner = owner, key = key, factory = viewModelFactory { initializer { create(container) } })
}
