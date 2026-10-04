# Verifica dell’aggiornamento dashboard PC

Verifica del 4 ottobre 2026 sul repository `Adrianss31/low-freq-hunter-android`, ramo `main`, commit `3448c1f47665f439453ac7c759af15116cc3e4e2`. Ultima release rilevata: `v1.0.3`.

## Obiettivo indicato nell’allegato

Ricreare la dashboard del prototipo LFH Dashboard PC v4 nella pagina servita dal telefono: spettro live, spettrogramma principale, navigazione fra notti, confronto delle ultime 15 notti, stato di affidabilità, avvisi, modifica delle bande ed esportazioni. Il prototipo contiene dati simulati e un runtime dedicato; la versione dell’app deve usare i dati reali e JavaScript/canvas senza una nuova catena di compilazione web.

## Cosa esiste nel progetto attuale

| Informazione o funzione | Implementazione disponibile |
| --- | --- |
| Stato, bande attive, livelli e batteria | `/api/state` |
| Spettro live fino a 500 Hz | `/api/spectrum` |
| Campioni, eventi, gap, marker e spettrogramma | `/api/session` e `/api/session/<id>` |
| Elenco sessioni | `/api/sessions` |
| Aggregati di ricorrenza | `/api/recurrence`, attualmente 14 sessioni e fasce sulle 24 ore |
| Configurazione e salvataggio sul telefono | GET/POST `/api/settings`, con applicazione immediata e riavvio della sessione se necessario |
| CSV e JSON | Esportazioni della sessione già esposte dal server |
| Report PNG | `Exporter.reportPng`, disponibile nell’app ma da esporre nel server |
| Massimi orari | `HourStats`, con cache incrementale già usata nell’Archivio Android |
| Font | Geist, Geist Mono e Doto già presenti nelle risorse Android |

## Differenze da risolvere

1. **Spettrogramma storico.** L’archivio conserva 64 intervalli di frequenza fra 20 e 200 Hz, mediati in intervalli di circa 30 secondi e quantizzati fra −110 e −20 dBFS. Ingrandire il disegno non aumenta la risoluzione della misura. La vista 0–250 Hz deve segnalare l’assenza dei dati fuori dall’intervallo registrato. Il profilo al cursore deve dichiarare la risoluzione disponibile.
2. **Notti e sessioni.** Il prototipo confronta finestre 21:00–09:00, mentre il server restituisce sessioni. Una notte può comprendere più sessioni, comprese quelle create dopo un cambio di configurazione. Il riepilogo deve aggregarle nella finestra notturna, mantenendo gap e soglie originali. Una notte non registrata non vale come una notte silenziosa.
3. **Server dopo lo stop.** Oggi il server vive nel servizio di monitoraggio e viene arrestato allo stop. La schermata di registrazione ferma non può quindi essere letta dal telefono dopo lo stop. Mantenere la dashboard disponibile richiede separare il ciclo di vita del server dalla cattura, mantenendo esplicito il controllo LAN nell’app.
4. **Affidabilità.** L’orario della risposta HTTP non dimostra che il microfono stia producendo nuovi dati: occorre esporre il timestamp reale dell’ultimo dato. Stato di carica e spazio libero non sono attualmente restituiti. Il numero di notti memorizzabili deve essere una stima basata sui dati disponibili oppure risultare non disponibile.
5. **Notifiche desktop.** L’indirizzo LAN attuale è HTTP. Chrome e Firefox richiedono un contesto sicuro per le notifiche di sistema. Con HTTP gli avvisi possono comparire dentro la pagina; per le notifiche desktop serve una soluzione HTTPS attendibile e accessibile dal PC. Fonte: [MDN, Using the Notifications API](https://developer.mozilla.org/en-US/docs/Web/API/Notifications_API/Using_the_Notifications_API).
6. **Esportazioni.** Il report PNG può riutilizzare il generatore dell’app per singola sessione. Per una notte composta da più sessioni occorre esplicitare l’ambito e produrre un riepilogo coerente. PNG VISTA deve rappresentare la vista corrente, con assi e intervallo leggibili; CSV deve conservare l’identità delle sessioni.
7. **Configurazione.** Il pannello Bande deve lavorare su una bozza, preservare le altre impostazioni e confermare il successo soltanto dopo la risposta del telefono. Le soglie usate per lo storico restano quelle salvate con le sessioni.

## Approccio consigliato

Riutilizzare server, archivio, esportazioni e cache orarie attuali; ricreare fedelmente la pagina del prototipo; aggiungere soltanto le letture aggregate e i metadati necessari. Servire i font localmente dal telefono. Mantenere compatibili gli endpoint esistenti e il token di accesso.

Le alternative sono rifare il server oppure cambiare anche il formato di acquisizione dello spettrogramma. Entrambe ampliano lo scopo e richiedono verifiche ulteriori; non recuperano le frequenze assenti dalle registrazioni precedenti.

## Verifiche previste prima della consegna

- Confronto visivo con il prototipo, inclusi drawer, palette, zoom, cursore, wipe e modalità movimento ridotto.
- Prove del browser con registrazione attiva, silenzio, telefono non raggiungibile, registrazione ferma e notte priva di dati.
- Controlli di aggregazione: eventi sovrapposti, sessioni multiple, configurazioni differenti, gap, mezzanotte, fuso del telefono e cambio dell’ora.
- Controlli HTTP: token, parametri non validi, formati dei dati, esportazioni e salvataggio delle bande.
- Verifica del passaggio stop/ripresa senza perdere dati e senza indicare falsamente una cattura attiva.
- Test Android, compilazione e lint. La verifica fisica del microfono e della rete sul telefono richiede un dispositivo disponibile.

## Stato del lavoro

Allegato letto, prototipo aperto e codice corrente recuperato e analizzato. Nessuna modifica al codice dell’app, nessun push e nessuna nuova release eseguiti. L’utente ha approvato il riuso del server, HTTP con avvisi nella pagina, l’assenza esplicita dei dati storici fuori dai 20–200 Hz e la disponibilità del server dopo lo stop. Il piano di implementazione è pronto per la revisione.
