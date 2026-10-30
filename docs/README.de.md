<p align="center">
    <img
      src="../media/fhg.gif"
      alt="FoxHole VPN"
      width="180"
      height="180"
    >
</p>

<div align="center">

[![English](https://img.shields.io/badge/%F0%9F%87%AC%F0%9F%87%A7-English-ff7a00?style=flat-square)](../README.md)
[![Русский](https://img.shields.io/badge/%F0%9F%87%B7%F0%9F%87%BA-%D0%A0%D1%83%D1%81%D1%81%D0%BA%D0%B8%D0%B9-ff7a00?style=flat-square)](README.ru.md)
[![Deutsch](https://img.shields.io/badge/%F0%9F%87%A9%F0%9F%87%AA-Deutsch-ff7a00?style=flat-square)](README.de.md)
[![Français](https://img.shields.io/badge/%F0%9F%87%AB%F0%9F%87%B7-Fran%C3%A7ais-ff7a00?style=flat-square)](README.fr.md)
[![Español](https://img.shields.io/badge/%F0%9F%87%AA%F0%9F%87%B8-Espa%C3%B1ol-ff7a00?style=flat-square)](README.es.md)
[![日本語](https://img.shields.io/badge/%F0%9F%87%AF%F0%9F%87%B5-%E6%97%A5%E6%9C%AC%E8%AA%9E-ff7a00?style=flat-square)](README.ja.md)
[![简体中文](https://img.shields.io/badge/%F0%9F%87%A8%F0%9F%87%B3-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-ff7a00?style=flat-square)](README.zh-CN.md)

</div>

<p align="center">
  <a href="https://github.com/foxhole-team/foxhole-vpn/releases">
    <img src="https://img.shields.io/github/v/release/foxhole-team/foxhole-vpn?label=version&style=flat-square" alt="GitHub Version">
  </a>
  <a href="https://f-droid.org/packages/com.foxhole.vpn/">
    <img src="https://img.shields.io/f-droid/v/com.foxhole.vpn?label=F-Droid&logo=f-droid&style=flat-square" alt="F-Droid Version">
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.foxhole.vpn%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Ffoxhole-team%2Ffoxhole-vpn%22%2C%22author%22%3A%22FoxHole%20Team%22%2C%22name%22%3A%22FoxHole%20VPN%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64-v8a-release%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22verifyLatestTag%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D">
    <img src="https://img.shields.io/badge/Obtainium-add%20app-1A73E8?style=flat-square" alt="Get it on Obtainium">
  </a>
</p>

# FoxHole VPN

[![FoxHole VPN](https://img.shields.io/badge/GitHub-FoxHole_VPN-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-vpn)


<p align="center">
  <img src="../fastlane/metadata/android/de-DE/images/phoneScreenshots/00_main.png" width="24%" alt="English main screen">
  <img src="../fastlane/metadata/android/de-DE/images/phoneScreenshots/01_openwrt.png" width="24%" alt="English router management">
  <img src="../fastlane/metadata/android/de-DE/images/phoneScreenshots/02_settings.png" width="24%" alt="English settings">
</p>

FoxHole VPN ist ein einfacher VPN-Client für Android 8 und neuer. Importieren Sie ein Profil Ihres VPN-Anbieters oder eigenen Servers, verbinden Sie Ihr Telefon und verwalten Sie einen gekoppelten OpenWrt-Router in der App.

Unterstützt VLESS, VMess, Trojan, Shadowsocks/Outline, WireGuard, AmneziaWG (experimentell), Hysteria2, Naive, TUIC und AnyTLS. Importiert kompatible FoxHole-Guard-Konfigurationen, v2rayTun-Links und -Abonnements, Clash/mihomo-YAML, SIP008-JSON und dynamische Outline-Schlüssel (`ssconf://`). Teilen Sie Profile nur mit vertrauenswürdigen Personen: exportierte Profile und Quelllinks können Zugangsdaten enthalten.

## 🤖 Unterstützte Plattformen

Android 8 (API 26) oder neuer ist erforderlich. Build-Profile können `arm64-v8a`, `armeabi-v7a`, `x86` und `x86_64` enthalten; jede veröffentlichte Version listet ihre verfügbaren APKs auf.

Die aktuelle Router-Paket-/Updaterichtlinie lässt OpenWrt 25.12.5 mit `mediatek/filogic` und `x86/64` zu. Erforderlich sind ein authentifizierter Katalog für die genaue Architektur, passende Abhängigkeiten und Kernel-ABI sowie genügend Flash/RAM. Ein Zielname bestätigt nicht jedes Routermodell.

## 🎛️ Bedienung im Alltag

- Profile aus Zwischenablage, Datei oder QR-Code importieren, gespeicherte Profile teilen und Profile mit gespeicherter Abonnementquelle aktualisieren.
- Ein Protokoll aus den Optionen eines importierten Profils wählen. Der Protokollwechsel beim aktiven Profil erfordert eine Bestätigung vor dem erneuten Verbinden.
- Startbildschirm-Widgets zeigen den Verbindungsstatus und bieten Start/Stopp für das ausgewählte Profil.
- Design und App-Sprache in den Einstellungen wählen: Englisch, Russisch, Deutsch, Französisch, Spanisch, Japanisch oder vereinfachtes Chinesisch.

Wenn unter Einstellungen → Hintergrundupdates aktiviert, werden Abonnements standardmäßig etwa alle 12 Stunden aktualisiert und signierte Verbindungsdaten von FoxHole DB alle 24 Stunden geprüft. Beide Hintergrundprüfungen lassen sich dort einzeln deaktivieren. Die Dateninstallation erfordert weiterhin Ihre Aktion. Die GitHub-Version prüft täglich auf App-Updates; APK-Download und -Installation erfordern Ihre Aktion und eine Android-Bestätigung. Die F-Droid-Version prüft oder installiert keine eigenen APK-Updates: Verwenden Sie F-Droid. Android kann Hintergrundaufgaben verzögern.

## 🛜 Router-Verwaltung (öffentliche Beta)

Die Routerverwaltung ist eine öffentliche Beta. Gerätekompatibilität sowie Installation und Aktualisierung werden noch validiert.

> [!IMPORTANT]
> Die automatische Installation wird derzeit nur auf Routern mit unveränderter OpenWrt-Standardkonfiguration unterstützt.
> Die Kopplung ist auch nach der manuellen Installation der aktuellen Version von [FoxHole OpenWrt Client](https://github.com/foxhole-team/foxhole-openwrt-client) möglich.
> Für Einrichtung und Kopplung müssen sich Smartphone und Router im selben lokalen Netzwerk befinden; der Router benötigt Internetzugang.

`ACCESS_LOCAL_NETWORK` - Router suchen, einrichten und verwalten. Unter Android 17 (API 37) oder neuer fordert die App bei Ziel-API 37 oder neuer vor LAN-Aktionen die Systemberechtigung an. Eine Ablehnung blockiert diese Aktionen.

Der Verwaltungsschalter gilt für die ausgewählte gespeicherte Kopplung. Sein Ausschalten beendet die App-Verwaltung, nicht das Router-VPN; Einrichtung und Kopplung bleiben für einen ungekoppelten Router verfügbar.

Das WLAN-Symbol zeigt nur eine physische WLAN-Verbindung, ohne SSID oder Standortzugriff. Suche und neue Einrichtung/Kopplung erfordern WLAN. Die manuelle Adresse wird einmal mit dem aktuellen privaten IPv4-WLAN-Gateway vorbelegt, falls verfügbar, und bleibt bearbeitbar.

Über Heim-VPN verwalten ist eine separate Zustimmung und für jede bestehende Kopplung standardmäßig AUS. Zusätzlich sind Ihre Bestätigung Öffentliche IP-Adresse (der Router hat eine öffentliche IP und einen eingehenden VPN-Dienst) und eine nutzbare aktive VPN-Route erforderlich. Diese Bestätigung weist keine Adresse zu und konfiguriert keine Firewall. Die App kann die Verwaltung über dieses VPN-Netz am gespeicherten privaten Endpunkt mit bestehender TLS-Zertifikatsbindung und Kopplungsauthentifizierung versuchen; sie öffnet keinen WAN-Verwaltungsport und ermöglicht keine neue Suche/Kopplung über das Mobilfunknetz. Automatisches Umschalten/Handoff des Telefon-VPN bleibt deaktiviert; diese Zustimmung betrifft nur die Router-Verwaltung.

Separate App-Verknüpfungsmetadaten erhalten nur Profile für den eingehenden VPN-Zugang, die ein authentifizierter gekoppelter Router direkt an die App übergibt. Verbindungs-URI, Konfiguration und QR-Formate bleiben unverändert. Allgemeine Web-/QR-/Provider-Exporte bleiben unmarkiert; importierte Hinweise allein begründen kein Vertrauen.

Die automatische Erkennung erfordert außerdem das passende gespeicherte lokale Profil, dessen aktuelle Konfiguration und die ausgewählte Kopplung, während dieses Profil tatsächlich über das eigene App-VPN verbunden ist. Pro tatsächlicher Verbindung erfolgt eine begrenzte Prüfung, ohne Timer, automatische Wiederholung oder Provider-Profilabfragen. Kopplung wiederholen ist ein ausdrücklicher WLAN-/Berechtigungs-/PIN-Ablauf: Der alte Eintrag bleibt bis zur erfolgreichen Authentifizierung erhalten; eine geänderte Zertifikatsidentität wird nie stillschweigend akzeptiert.

Lesen und akzeptieren Sie vor der ersten Routerinstallation die Vereinbarung für FoxHole OpenWRT Client ausdrücklich. Die Auswahl ist zunächst deaktiviert und von automatischen Updates getrennt. Bereits gekoppelte Router verlangen keine nachträgliche Zustimmung.

Installieren Sie das Paket [FoxHole OpenWrt Client](https://github.com/foxhole-team/foxhole-openwrt-client), um VPN-Profile, Geräterouting und Protokollpakete auf dem Router zu verwalten. Die Einrichtung erfordert OpenWrt 25.12.5 mit dem Paketmanager apk und einen authentifizierten Release-Katalog für die genaue Router-Architektur; auch der freie Speicher wird geprüft. Veröffentlichte Kataloge bestimmen die verfügbaren Ziele. Administratorzugriff ist erforderlich. VPN-Profile für Ihre Geräte außerhalb des Heimnetzes benötigen eine erreichbare öffentliche Routeradresse und einen eingerichteten eingehenden VPN-Dienst. Prüfen Sie die Erreichbarkeit über ein Mobilfunknetz.

Bei einer neuen Routerinstallation ist das sichtbare Kästchen für automatische FoxHole-Updates zunächst aktiviert. Deaktivieren Sie es bei Bedarf oder ändern Sie die Richtlinie später in den Router-Einstellungen. Wenn aktiviert, prüft der Router täglich und installiert verifizierte Paketupdates aus FoxHole-Releases, unabhängig von F-Droid. Das Koppeln eines vorhandenen Routers behält dessen Richtlinie bei. Updates erhalten Profile und Kopplung; OpenWrt-Firmware und andere Systempakete bleiben unverändert. Das Entkoppeln des Telefons deaktiviert diese Routerrichtlinie nicht.

Manuelle FoxHole-Updates aus App und LuCI verwenden dieselbe authentifizierte Transaktion. Bei direkten CLI-/Backend-Installationen sind automatische Updates standardmäßig aus.

## 🔒 Datenschutz

Profile und Einstellungen sind auf dem Gerät mit einem Android-Keystore-Schlüssel verschlüsselt. Keine Werbe- oder Analyse-SDKs enthalten. VPN-Prüfungen kontaktieren `www.google.com/generate_204`; Informationen zur Ausgangs-IP stammen von `ipwho.is`. Diese Anfragen nutzen das gewählte VPN-Netz. Ohne VPN kann der ungefähre Gerätestandort über die aktuelle Internetverbindung aktualisiert werden, auch durch ein Widget. Die Dienste erhalten die Anfrage und deren Quell-IP. Signierte Verbindungsdaten stammen von `foxhole-team.github.io/foxhole-db`; Abonnements verwenden gespeicherte Anbieter-URLs. GPS ist nicht erforderlich.

## 📄 Lizenz

[GPL-3.0-or-later](../LICENSE) · Copyright (C) 2026 FOXHOLE TEAM.

Die Komponentenlizenzen stehen unter Einstellungen → Über die App.

[Drittanbieterkomponenten](../THIRD_PARTY_NOTICES.md).

## 🔗 Verwandte Projekte

[![FoxHole Core](https://img.shields.io/badge/GitHub-FoxHole_Core-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-core)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-core?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-core/releases)

[![FoxHole DB](https://img.shields.io/badge/GitHub-FoxHole_DB-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-db)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-db?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-db/releases)

[![FoxHole Guard](https://img.shields.io/badge/GitHub-FoxHole_Guard-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-guard)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-guard?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-guard/releases)

[![FoxHole OpenWrt](https://img.shields.io/badge/GitHub-FoxHole_OpenWrt-181717?logo=github&style=flat-square)](https://github.com/foxhole-team/foxhole-openwrt-client)
[![Version](https://img.shields.io/github/v/release/foxhole-team/foxhole-openwrt-client?label=version&style=flat-square)](https://github.com/foxhole-team/foxhole-openwrt-client/releases)

## 📦 Verfügbarkeit von Veröffentlichungen

Verfügbare APK-Architekturen und Router-Ziele stehen in jeder veröffentlichten Version. Eine Build-Option oder ein Quellcode-Rezept bestätigt weder Gerätekompatibilität noch F-Droid-Verfügbarkeit; die Aufnahme in F-Droid unterliegt dessen Prüfung.

## 💖 Entwicklung unterstützen

Wir entwickeln und pflegen FoxHole VPN vollständig mit unserer eigenen Zeit und unseren eigenen Mitteln, unterstützt durch KI-Werkzeuge. Ihre Unterstützung hilft uns, dem Projekt mehr Zeit zu widmen und die Entwicklung zu beschleunigen.

[Projekt unterstützen - Kryptowährungs-Wallets](#cryptocurrency-wallets)

<a id="cryptocurrency-wallets"></a>

### 🪙 Kryptowährungs-Wallets

**XMR (Monero):**

```text
48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr
```

**BTC (Bitcoin):**

```text
bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f
```

**ETH (Ethereum):**

```text
0xDEBA357Cc8f5E865ea7FFa98E138C8241A16A465
```
