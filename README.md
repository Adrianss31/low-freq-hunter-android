# Low-Freq Hunter (Android)

App Android nativa (Kotlin + Compose) per rilevare e **documentare** rumori a
bassa frequenza (50/100 Hz: ronzii di rete, trasformatori, impianti) e
vibrazioni strutturali. Evoluzione nativa della
[PWA](https://github.com/Adrianss31/low-freq-hunter), che risolve i suoi due
limiti: qui il microfono usa la sorgente **UNPROCESSED** (niente filtro
passa-alto/AGC di sistema che mangia le basse frequenze) e la registrazione
continua **a schermo spento** in un foreground service con notifica
informativa.

## Funzioni

- **Live** — spettro in tempo reale, waterfall, meter a segmenti per banda,
  confronto A/B, sonificazione Geiger
- **Notte** — log continuo con eventi a soglia (isteresi + durate minime),
  spettrogramma persistito, marker "lo sento adesso", clip WAV sugli eventi,
  gap di monitoraggio registrati; schermo spegnibile
- **Canale V** — vibrazioni strutturali dall'accelerometro (dB rel 1 g)
- **Programmazione** — avvio/stop automatico ogni notte (setAlarmClock +
  activity-trampolino per l'accesso al microfono da background)
- **Log** — timeline, statistiche, export report PNG / CSV / JSON in
  Documents/LowFreqHunter e via share sheet
- Bande dinamiche (1–8), soglie assolute in dBFS, batteria nei campioni,
  esenzione ottimizzazioni batteria
- UI ispirata a Teenage Engineering / Nothing: font dot-matrix (Doto),
  pannelli piatti, feedback aptico su ogni interazione

## Build

Solo via GitHub Actions (`.github/workflows/build.yml`): JDK 17,
gradle 8.10.2, firma con keystore dai secrets `KEYSTORE_BASE64` /
`KEYSTORE_PASSWORD` (alias `lowfreqhunter`; i file locali stanno in `.keys/`,
mai committati). Ogni push su `main` produce l'APK come artifact; i tag `v*`
pubblicano una release con `lowfreqhunter.apk` allegato.

I test JVM (`gradle testReleaseUnitTest`) verificano FFT, integrazione di
banda, macchina a stati eventi, gap, slice waterfall e canale V con segnali
sintetici.

## Installazione

Scaricare `lowfreqhunter.apk` dall'ultima release e installarlo (serve
consentire le origini sconosciute). Al primo avvio: permesso microfono e
notifiche; per il log notturno consigliata l'esenzione batteria (Setup).

I livelli sono dBFS relativi al fondo scala del microfono, non dB SPL
calibrati: misura indicativa, non fonometria certificata.
