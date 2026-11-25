# FreeVPN

Android-приложение VPN с двумя режимами:

- **VLESS · для 4G** — движок Xray-core (VLESS/Trojan + TLS/Reality/XHTTP), трафик выглядит как обычный HTTPS,
  поэтому проходит там, где оператор режет обычные VPN. Бесплатные ключи берутся из публичной подборки
  [igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia); свой сервер добавляется
  кнопкой «＋ Ключ» (вставить ссылку `vless://…`).
- **VPN Gate · Wi-Fi** — OpenVPN к **бесплатным серверам VPN Gate**
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

## Режим VLESS
- «Подключить» параллельно проверяет ключи (12 одновременно) и подключается к первому рабочему;
  если ключ перестал работать — сам берёт следующий.
- Поддерживаются ключи `vless://`, `vmess://`, `trojan://`, `ss://` (Shadowsocks).
- «＋ Ключ» принимает и **подписки**: ссылку `https://…` от VPN-сервиса или Telegram-бота
  (обычный список или base64, как в v2rayNG/Happ/Hiddify). Ключи подписки помечены 📦 и обновляются по «Обновить».
- Долгое нажатие на ключ — скопировать его; на свой ключ — удалить; на ключ подписки — удалить подписку.
- Бесплатные ключи публичные: владельцы серверов неизвестны и могут видеть, куда идёт трафик.
  Для важного используй свой сервер.
- Движок: [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) (`libv2ray.aar`, качается в CI).

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
