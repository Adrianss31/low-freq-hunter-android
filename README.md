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

Interfaccia "capsula" (v1.0): corpo chiaro da strumento, display scuri
incassati, un solo accento arancio per REC e per ciò che supera la soglia.
Quattro schede in una capsula flottante, con il tasto **REC** sempre a portata
di pollice; in testata le spie REC / PROG / PC e la pastiglia UPD quando c'è
un aggiornamento.

- **Monitor** — ascolto live (microfono locale in standby, quello del
  servizio durante REC): lettura grande della banda selezionata, frequenza
  dominante, spettro con le bande disegnate sopra e waterfall di 30 s. Le
  bande (1–8, sempre etichettate per frequenza) sono tessere: tocca per aprire
  il regolatore di centro, larghezza e soglia, oppure trascina direttamente
  sullo spettro (orizzontale = centro, verticale = soglia). Durante REC compare
  la scheda della sessione: spettrogramma, presenza per banda colorata per
  intensità e **note con orario** ("spento climatizzatore"). Sotto, il
  **contesto** (stanza, posizione, condizioni) salvato con ogni sessione.
- **Archivio** — calendario mensile: una riga per giorno, metà giorno e metà
  notte (con due spezzamenti segue i loro orari, altrimenti 09–21 / 21–09),
  ogni cella è il livello massimo dell'ora rispetto alla soglia su scala
  −12…+10 dB. Tocca per aprire la sessione, tieni premuto e scorri per
  l'anteprima, trascina in orizzontale per cambiare mese; filtro per banda.
  La sessione si apre in un foglio con timeline (zoom 2 h e scrubbing),
  spettrogramma, riepilogo per banda, diario (note, interruzioni, eventi
  lunghi), clip con forma d'onda, export PNG/JSON/CSV ed eliminazione.
  Da qui anche il **dossier per LLM** (7/14 giorni o il mese, solo notti/giorni).
  Gli aggregati orari stanno in una cache su file aggiornata in modo
  incrementale, così il calendario non rilegge tutti i campioni.
- **Mappa** — rilievi della casa per frequenza: tocchi ≈ dove sei, rifinisci
  il mirino trascinando (movimento relativo), premi MISURA (10 s); zoom con le
  dita o con −/+, piantina opzionale, export PNG.
- **Setup** — moduli a fisarmonica: programma con quadrante 24 h trascinabile
  (manuale / notturno / continuo con uno o due spezzamenti), eventi
  (apertura, chiusura, isteresi, pulsanti), sensori e analisi (FFT, spettro,
  vibrazioni, stima SPL), clip audio, sistema (aggiornamenti, Monitor dal PC,
  esenzione batteria).

Sotto il cofano, invariati: sorgente **UNPROCESSED** (niente passa-alto/AGC
di sistema sulle basse frequenze), registrazione a schermo spento nel
foreground service, canale **V** dall'accelerometro, programmazione con
setAlarmClock, **sessioni unite per finestra** giorno/notte (stop e riprese
restano una sessione con il buco documentato come gap), clip WAV sugli eventi,
**Monitor dal PC** (dashboard web in LAN, spettro live, notte 21–09, diario
delle ultime 15 notti, avvisi nella pagina ed esportazioni), widget home. Smussatura "a due velocità": spettro
e meter interpolati a 60 fps, cifre mediate ~1 s; motore eventi e dati
registrati usano sempre i valori grezzi.

Rimossi nella v1.0 rispetto alle versioni 0.x: sonificazione Geiger, freeze e
confronto A/B della vecchia scheda Live, marker "lo sento adesso" dall'app
(sostituito dalle note con orario; resta dalla dashboard PC), heatmap di
ricorrenza sulle ultime 14 sessioni (sostituita dal calendario mensile).

## Build

Build locale con JDK 17, SDK Android 35 e `./gradlew testReleaseUnitTest assembleRelease`.
Il wrapper include il checksum della distribuzione Gradle 8.10.2.
GitHub Actions (`.github/workflows/build.yml`) esegue i test e la firma con keystore dai secrets `KEYSTORE_BASE64` /
`KEYSTORE_PASSWORD` (alias `lowfreqhunter`; i file locali stanno in `.keys/`,
mai committati). Ogni push su `main` produce l'APK come artifact; i tag `v*`
preparano una release in bozza con `lowfreqhunter.apk` allegato; si pubblica dopo
la verifica di firma, compatibilità e checksum. Il canale stabile è GitHub Latest.

I test JVM (`gradle testReleaseUnitTest`) verificano FFT, integrazione di
banda, macchina a stati eventi, gap, slice waterfall, canale V, smussatori
(EMA/mediana) e aggregazione di ricorrenza con segnali sintetici.

## Dashboard PC

In **Setup → Sistema → Monitor dal PC**, attivare l'accesso e copiare l'URL
mostrato dall'app. PC e telefono devono condividere la rete locale. La dashboard
resta accessibile dopo lo stop di REC; disattivare Monitor dal PC chiude il server
e libera le risorse dedicate. Un servizio separato mantiene la connessione,
con la propria notifica, senza aprire il microfono.

Lo spettro live copre 0–250 Hz; lo storico conserva solo le misure effettivamente
salvate: **20–200 Hz, 64 intervalli, medie di circa 30 secondi, −110…−20 dBFS**.
Fuori da questa copertura, nei buchi e nel futuro non vengono inventati valori.
Il diario include le notti mancanti. Orari e cambi d'ora seguono il fuso del
telefono. Le comparazioni escludono notti mancanti e configurazioni/dispositivi,
sorgenti o contesti diversi. Audio in dBFS e vibrazioni in dB relativi a 1 g
usano scale distinte; non sono misure fonometriche certificate.

Trascinare o usare la rotella per ingrandire, le frecce per scorrere, cliccare
per fissare il cursore e premere Esc per ripristinare la notte. Il report PNG
comprende l'intera notte; PNG VISTA salva l'inquadratura; CSV mantiene le
sessioni, i parametri originali degli eventi e i gap. Restano disponibili gli
export JSON/campioni/report delle singole sessioni e il marker dal PC.
Le bande si modificano in bozza: Annulla non scrive sul telefono; Salvare
applica i parametri e apre una nuova sessione se necessario.

Gli avvisi compaiono **nella pagina aperta**: inizio/fine evento, rumore oltre
30 minuti, interruzione del collegamento oltre 60 secondi e ritorno,
registrazione ferma/ripartita e batteria sotto 20%. Sono silenziabili per
30 minuti, 1 o 8 ore; la prima lettura e il ritorno online non replicano gli
eventi già in corso. Il server HTTP richiede il token; font e dati restano locali.

Verifiche browser: `node --test tests/*.test.cjs`.

## Installazione

Scaricare `lowfreqhunter.apk` dall'ultima release e installarlo (serve
consentire le origini sconosciute). Al primo avvio: permesso microfono e
notifiche; per il log notturno consigliata l'esenzione batteria (Setup).

I livelli sono dBFS relativi al fondo scala del microfono, non dB SPL
calibrati: misura indicativa, non fonometria certificata.


## Dossier per LLM

Nel **Monitor**, prima di registrare, salva stanza, posizione/orientamento e condizioni
(finestre, impianti). Il contesto e il dispositivo vengono fotografati nella nuova
sessione; le sessioni precedenti mantengono i propri metadati, o "sconosciuto".
Durante REC puoi aggiungere note e azioni con orario, ad esempio "spento climatizzatore".

In **Archivio → DOSSIER** scegli il periodo (7 o 14 giorni, o il mese visibile; al massimo 31 sessioni concluse) e crea lo ZIP.
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
in **Setup → Sistema** puoi ripetere il controllo (la pastiglia UPD in testata porta lì). **Aggiorna** scarica
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
