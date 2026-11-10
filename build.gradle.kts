// Корневой build-файл проекта "Ai NAS".
// Версии плагинов зафиксированы здесь, чтобы ":app" наследовал их без повторов.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}
