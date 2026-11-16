# Sickla-tavlan

Android-app som visar avgångstavlorna för hållplatserna **Sickla**, **Sickla bro** och **Sickla station** i realtid, med SL:s störningsinformation.

- Avgångar från [SL Transport API](https://www.trafiklab.se/api/our-apis/sl/transport/) (ingen API-nyckel)
- Störningar från [SL Deviations API](https://www.trafiklab.se/api/our-apis/sl/deviations/) + hållplats- och avgångsspecifika avvikelser
- Uppdateras automatiskt var 30:e sekund medan appen är öppen
- Flik "Alla" eller en flik per hållplats; avgångar grupperade per trafikslag och läge

## Installera

Varje push till `main` bygger en APK via GitHub Actions. Ladda ner `app-release.apk` under **Releases → Senaste build** och öppna den på telefonen (tillåt installation från okänd källa).

## Bygga själv

Öppna mappen i Android Studio och kör, eller `./gradlew assembleRelease`.

Hållplatsernas site-id slås upp via namn i `/v1/sites` vid första start och cachas. Ändra `TARGET_STOPS` i `SlApi.kt` för andra hållplatser.
