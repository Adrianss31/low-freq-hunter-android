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
  activity-trampolino per l'accesso al microfono da background);
  **registrazione continua** con uno o due spezzamenti giornalieri
  (es. 21:00 e 07:00 → sessioni "Notte" 21–7 e "Giorno" 7–21)
- **Log** — timeline, statistiche, export report PNG / CSV / JSON in
  Documents/LowFreqHunter e via share sheet; **heatmap di ricorrenza**
  (ora del giorno × notte sulle ultime sessioni: colore = livello massimo
  rispetto alla soglia su scala −10…+10 dB — la soglia è il centro, si vede
  anche il rumore che si avvicina senza superarla)
- **Monitor dal PC** — dashboard web sulla LAN con live, archivio, centro
  notifiche, heatmap di ricorrenza e modifica di tutte le impostazioni
  dell'app dal browser (`/api/settings`, `/api/recurrence`); al salvataggio
  la sessione in corso riparte subito coi nuovi parametri
- **Mappa** — heatmap della casa per frequenza; pinch-zoom e mirino di
  conferma: tocchi ≈ dove sei, rifinisci trascinando (movimento relativo,
  niente dito grosso) e premi MISURA
- **Sessioni unite per finestra** — le registrazioni avviate nella stessa
  finestra giorno/notte (dagli spezzamenti o dalla programmazione, altrimenti
  il giorno solare) proseguono la stessa sessione: stop e riprese, manuali o
  automatici, restano un'unica registrazione con il buco documentato come gap
- **Stima dB SPL** opzionale — offset tarato dall'utente su un riferimento
  (fonometro o app); i report restano marcati come stima indicativa
- Valori e grafici smussati: spettro e meter interpolati a 60 fps, livelli
  testuali mediati ~1 s, frequenza dominante come mediana mobile (il motore
  eventi e i dati registrati usano sempre i valori grezzi)
- Bande dinamiche (1–8), soglie assolute in dBFS, batteria nei campioni,
  esenzione ottimizzazioni batteria
- UI ispirata a Teenage Engineering / Nothing: font dot-matrix (Doto),
  pannelli piatti, feedback aptico su ogni interazione

## Build

Build locale con JDK 17, SDK Android 35 e `./gradlew testReleaseUnitTest assembleRelease`.
Il wrapper include il checksum della distribuzione Gradle 8.10.2.
GitHub Actions (`.github/workflows/build.yml`) esegue i test e la firma con keystore dai secrets `KEYSTORE_BASE64` /
`KEYSTORE_PASSWORD` (alias `lowfreqhunter`; i file locali stanno in `.keys/`,
mai committati). Ogni push su `main` produce l'APK come artifact; i tag `v*`
pubblicano una release con `lowfreqhunter.apk` allegato.

I test JVM (`gradle testReleaseUnitTest`) verificano FFT, integrazione di
banda, macchina a stati eventi, gap, slice waterfall, canale V, smussatori
(EMA/mediana) e aggregazione di ricorrenza con segnali sintetici.

## Installazione

Scaricare `lowfreqhunter.apk` dall'ultima release e installarlo (serve
consentire le origini sconosciute). Al primo avvio: permesso microfono e
notifiche; per il log notturno consigliata l'esenzione batteria (Setup).

I livelli sono dBFS relativi al fondo scala del microfono, non dB SPL
calibrati: misura indicativa, non fonometria certificata.


## Dossier per LLM

In **Notte**, prima di registrare, salva stanza, posizione/orientamento e condizioni
(finestre, impianti). Il contesto e il dispositivo vengono fotografati nella nuova
sessione; le sessioni precedenti mantengono i propri metadati, o "sconosciuto".
Durante REC puoi aggiungere note e azioni con orario, ad esempio "spento climatizzatore".

In **Log → dossier per LLM** seleziona da 1 a 31 sessioni concluse e crea lo ZIP.
La condivisione Android permette di salvarlo o caricarlo nella chat scelta. L'app
non invia dati a servizi AI. Campioni al secondo e audio sono opzioni separate,
entrambe disattivate all'inizio; con audio vengono incluse le prime tre clip di
ogni sessione (se disponibili e <=12 MB), non una selezione esaustiva dei fenomeni.

Il pacchetto contiene istruzioni per l'analisi, riepilogo JSON con configurazione,
metadati e copertura, andamento per minuto, eventi e gap con identificativi,
annotazioni, ricorrenze orarie, PNG e spettrogrammi numerici. La media è energetica;
i massimi sono dei campioni al secondo; il percentile 10 è solo un indicatore del
fondo. I minuti senza dati rimangono vuoti. Canale V e audio hanno unità diverse.
I gruppi di confronto includono configurazione, dispositivo, sorgente e contesto:
una stessa frequenza non implica una stessa sorgente fisica.

## Aggiornamenti dall'app

All'apertura viene controllata l'ultima release stabile del repository GitHub;
in **Setup → Aggiornamenti** puoi ripetere il controllo. **Aggiorna** scarica
`lowfreqhunter.apk` in cache e apre la conferma d'installazione di Android.
Alla prima installazione Android può richiedere "Consenti da questa origine";
al ritorno l'app prosegue con l'installazione. Se annulli puoi riprovare.
Termina prima una registrazione: l'app non avvia l'installer durante REC/ascolto.

Il download usa HTTPS, controlla dimensione, digest GitHub quando presente,
package name, versionCode crescente e corrispondenza dei firmatari con l'app
installata. Le release devono continuare a usare il medesimo keystore; una
build debug non può aggiornare un'installazione release. Le prerelease sono escluse.
Per ottenere questa funzione chi usa una versione precedente deve installare
manualmente **una prima release che la contenga**. Le successive saranno aggiornabili
dall'app. Il workflow impedisce di pubblicare un tag senza chiavi di firma.

## Verifica e limiti

`./gradlew testReleaseUnitTest assembleRelease lintDebug` esegue i test e i
controlli Android. Le pull request eseguono anche test e build debug senza secrets.
Le verifiche includono drenaggio scritture Room allo stop, dati di un dossier ZIP,
statistiche energetiche, picco iniziale degli eventi, gap finale, WAV parziale,
migrazione dei metadati e rifiuto di APK con firma/versione/package incompatibili.

Restano necessarie prove su dispositivo per registrazione notturna prolungata,
Doze, permessi OEM, avvio programmato e conferma dell'installer. I test JVM non
simulano la risposta fisica del microfono. Le statistiche già salvate da vecchie
versioni non vengono riscritte retroattivamente.
