# FreeVPN

Android-приложение VPN, которое подключается к **бесплатным серверам VPN Gate**
(сеть добровольцев, которую ведёт Университет Цукубы, Япония — https://www.vpngate.net).

## Что умеет
- Загружает свежий список бесплатных серверов VPN Gate (с флагами стран, скоростью, пингом).
- Одна большая кнопка «Подключить» — сама выбирает лучший сервер.
- Фильтр по стране и ручной выбор сервера.
- Если бесплатный сервер не отвечает 20 секунд — автоматически пробует следующий (до 8 штук).
- Показывает трафик ↓/↑, уведомление со статусом, кнопка «Отключить».
- Если VPN Gate недоступен — показывает прошлый сохранённый список.

## Как получить APK
Каждый push в `main` собирает APK через GitHub Actions.
Готовый файл: **Releases → latest → FreeVPN.apk**
(https://github.com/revgiler-arch/FreeVPN/releases/latest).

## Устройство
- VPN-ядро — [OpenVPN for Android (ics-openvpn)](https://github.com/schwabe/ics-openvpn)
  Арне Швабе, вариант сборки `skeleton` (ядро без чужого интерфейса).
- Собственный экран приложения: `main/src/skeleton/java/com/revgiler/freevpn/`
  - `MainActivity.java` — экран, подключение, авто-переключение серверов
  - `VpnGateApi.java` — загрузка и разбор списка VPN Gate
  - `VpnServer.java` — модель сервера
- Ресурсы экрана: `main/src/skeleton/res/`, манифест: `main/src/skeleton/AndroidManifest.xml`.
- Сборка вручную: `./gradlew :main:assembleSkeletonOvpn2Release` (нужны Android SDK, NDK, swig;
  исходники OpenVPN подтягиваются через `git submodule update --init --recursive`).

## Важно знать
- Серверы VPN Gate запускают добровольцы: скорость и стабильность разные,
  а владелец сервера видит, к каким сайтам идёт трафик (содержимое HTTPS — нет).
- Лицензия: GPL v2 (как у ics-openvpn), см. `doc/LICENSE.txt`.
