package ru.finhelper.app

import android.app.Application
import ru.finhelper.app.di.AppContainer

/** Класс приложения: создает контейнер зависимостей и запускает фоновую синхронизацию. */
class FinHelperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
