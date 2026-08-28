# Bus et Tram Nantes

A native Android alternative to the official Naolib app (Nantes public transport network), built
for speed and offline use. Built on **open data**: the Nantes Métropole GTFS ships with the app, so
stops, lines and timetables work without a connection.

- **Package name**: `com.bustramnantes.app`
- **Platform**: Android, native Kotlin, min SDK 26 (Android 8.0), target SDK 35
- **Architecture**: Clean Architecture (`data` / `domain` / `presentation`) + MVVM, Jetpack Compose,
  Hilt, Room
- **Language**: English in code, French in all UI strings

## Building

```bash
./gradlew :app:assembleDebug
```
