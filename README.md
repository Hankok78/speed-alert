# Speed Alert - Android Native App

Aplicație nativă Android pentru avertizare limită de viteză.

## Funcționalități:
- GPS în fundal (funcționează și când folosești Uber)
- Anunț vocal când se schimbă limita de viteză
- Date din OpenStreetMap (limite reale pentru Germania)
- Notificare permanentă cu viteza curentă

## Cum să compilezi:

### Opțiunea 1: Android Studio
1. Deschide folderul în Android Studio
2. Sync Gradle
3. Build > Build APK

### Opțiunea 2: Online (Appetize sau similar)
1. Upload codul pe GitHub
2. Folosește un serviciu de build online

### Opțiunea 3: Comandă Gradle
```bash
./gradlew assembleDebug
```
APK-ul va fi în: app/build/outputs/apk/debug/

## Permisiuni necesare:
- ACCESS_FINE_LOCATION
- ACCESS_BACKGROUND_LOCATION  
- FOREGROUND_SERVICE
- INTERNET
