# Политика конфиденциальности Pultoo (Pultoo: Remote for Samsung)

_Действует с 26.10.2026 для всех версий приложения Pultoo._

## Коротко

Приложение Pultoo **не собирает, не хранит и не передаёт разработчику никаких
персональных данных**. У приложения нет аналитики, рекламы, трекеров и
собственных серверов.

## Какие данные обрабатывает приложение

Pultoo работает напрямую между вашим телефоном и вашими телевизорами Samsung:

- **IP-адреса ваших телевизоров и токены сопряжения** хранятся только в локальном
  хранилище приложения на телефоне (private storage Android) и используются
  исключительно для подключения к вашим ТВ в вашей домашней сети. Ни разработчик,
  ни какие-либо третьи стороны эти данные не получают.
- **Команды управления** (кнопки пульта, текст, ссылки, списки приложений ТВ)
  отправляются только на выбранный вами телевизор по вашей локальной сети.

## Сетевые запросы вне домашней сети

- **Проверка обновлений** (кнопка в настройках): приложение запрашивает публичный
  API GitHub (`api.github.com/repos/teliy/Tizen-pult/releases/latest`), чтобы узнать
  номер последней версии. При скачивании обновления приложение получает файл APK
  с GitHub Releases. Никаких идентификаторов устройства, аккаунтов или персональных
  данных при этом не передаётся.
- Других внешних сетевых запросов приложение не выполняет.

## Разрешения Android

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` — подключение к
  телевизорам и проверка обновлений;
- `REQUEST_INSTALL_PACKAGES` — установка скачанного обновления приложения.

## Данные на телефоне

Настройки (выбранные ТВ, темы, язык, макросы, история) хранятся локально в
приложении и удаляются вместе с ним при удалении. Резервная копия Android может
включать локальные настройки приложения (стандартный механизм `allowBackup`) и
остаётся на вашем устройстве/в вашем аккаунте Google.

## Дети

Приложение не собирает данные о пользователях любого возраста, включая детей до 13 лет.

## Контакты

Вопросы о политике: создайте issue в репозитории
<https://github.com/teliy/Tizen-pult> или напишите владельцу репозитория.

---

# Pultoo Privacy Policy (Pultoo: Remote for Samsung)

_Effective 2026-10-26 for all versions of the Pultoo app._

## In short

Pultoo **does not collect, store, or transmit any personal data to the developer**.
The app contains no analytics, no ads, no trackers, and has no servers of its own.

## Data processed by the app

Pultoo works directly between your phone and your Samsung TVs:

- **Your TVs' IP addresses and pairing tokens** are stored only in the app's
  private storage on your phone and are used solely to connect to your TVs on
  your home network. Neither the developer nor any third party ever receives them.
- **Remote-control commands** (buttons, text, links, TV app lists) are sent only
  to the TV you select, over your local network.

## Network requests outside your home network

- **Update check** (button in Settings): the app queries the public GitHub API
  (`api.github.com/repos/teliy/Tizen-pult/releases/latest`) for the latest version
  number. When downloading an update, the app fetches an APK file from GitHub
  Releases. No device identifiers, accounts, or personal data are transmitted.
- The app performs no other external network requests.

## Android permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` — TV connections and
  update checks;
- `REQUEST_INSTALL_PACKAGES` — installing the downloaded app update.

## Data on your phone

Settings (selected TVs, themes, language, macros, history) are stored locally and
removed when you uninstall the app. An Android backup may include the app's local
settings (standard `allowBackup` behavior) and stays on your device / your Google
account.

## Children

The app does not collect data from users of any age, including children under 13.

## Contact

Questions: open an issue at <https://github.com/teliy/Tizen-pult> or contact the
repository owner.
