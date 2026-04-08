# mixts

#### Description

A mobile (Android/iOS) LAN file transfer application that establishes a simple Web file server between phones and computers via Wi-Fi, supporting file upload and download, similar to a simple FTP/TFTP web version.

#### Features

- 🌐 **LAN Web Service** - Start service on phone, access via browser from computer/other devices
- 📁 **File Management** - View, upload, download files in App data directory from web
- 🎮 **App Control** - One-click start/stop service, display access address and status
- 🔒 **Access Password (Optional)** - Default no encryption, configurable password protection
- 🔒 **No Storage Permission Required** - Uses App private directory
- 📂 **Dual Directory Storage** - Both App private directory and system Downloads/Mixts directory

#### Technical Architecture

| Platform | Language | Min Version | Target Version | HTTP Server | UI Framework |
|----------|----------|-------------|----------------|-------------|--------------|
| Android | Kotlin 2.0.x | API 26 (Android 8.0) | API 35 (Android 15) | NanoHTTPD 2.3.x | Jetpack Compose |
| iOS | Swift 6.0.x | iOS 17 | iOS 18 | GCDWebServer 3.5.x | SwiftUI |
