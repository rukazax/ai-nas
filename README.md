# AI NAS — Android-приложение для домашнего NAS

Нативный Android-клиент (Kotlin + Jetpack Compose + Material 3) для домашнего NAS-сервера:
доступ к файлам, фото-синхронизация и мониторинг дашборда.

## Возможности

- **Протоколы доступа**: SMB, WebDAV, SFTP — файловый менеджер поверх Storage Access Framework
- **Фото-синхронизация**: фоновый воркер (WorkManager) автозагрузки фотографий с телефона на NAS
- **Авторизация**: сохранение сессий между подключениями (AuthStore)
- **Обновления**: встроенный UpdateChecker
- **UI**: Compose + Material 3, тёмная/светлая тема, боковое меню

## Скачать

Готовые APK — на странице [Releases](https://github.com/rukazax/ai-nas/releases/latest).

## Сборка из исходников

```bash
./gradlew :app:assembleDebug    # отладочный APK в app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # релизный (нужны подписи)
```

Требуется Android Studio (или SDK + JDK 17). `local.properties` создаётся локально:

```properties
sdk.dir=/путь/к/Android/sdk
```

## Структура

```
app/src/main/kotlin/ru/ainas/app/
├── auth/    AuthStore — учётные записи и сессии
├── net/     HttpClient, SmbProvider, WebDavProvider, SftpProvider,
│            PhotoSyncWorker, StorageUri
├── up/      UpdateChecker
└── ui/      Compose-экраны: Landing, Drawer, Screens, Theme
```

## Лицензия

MIT
