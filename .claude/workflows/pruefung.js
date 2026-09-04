export const meta = {
  name: 'pruefung',
  description: 'JDAndroid-Gesamtpruefung: Fehler, Architektur, Tests finden, adversarial verifizieren, beheben, bauen',
  whenToUse: 'Regelmaessige Gesamtpruefung des Projekts nach docs/PRUEFUNG.md (Routine oder auf Wunsch des Nutzers)',
  phases: [
    { title: 'Finden', detail: 'sechs Blickwinkel parallel: Engine, Hoster, UI/Plattform, Architektur, Sicherheit, Tests' },
    { title: 'Verifizieren', detail: 'jeder Fund von zwei Skeptikern widerlegt oder bestaetigt' },
    { title: 'Beheben', detail: 'bestaetigte Funde nacheinander im Arbeitsbaum beheben, Tests ergaenzen' },
    { title: 'Bauen', detail: 'Unit-Tests und Release-Build, Schema-Export pruefen' },
  ],
}

// Grundlagen fuer jeden Agenten: Projektregeln zuerst lesen.
const BASIS =
  'Repository: /home/user/Jdandroid (Android, Kotlin, Compose, Room). Lies zuerst CLAUDE.md und ' +
  'docs/PRUEFUNG.md und halte dich an die dort festgelegten Regeln. Verifiziere jede Aussage im Code, ' +
  'keine Spekulation. Antworte knapp und auf Deutsch. '

const FUNDE_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          scenario: { type: 'string', description: 'konkreter Ablauf, der den Fehler zeigt' },
          fix: { type: 'string', description: 'minimaler Vorschlag, gern mit Code' },
        },
        required: ['title', 'file', 'severity', 'scenario', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const URTEIL_SCHEMA = {
  type: 'object',
  properties: {
    refuted: { type: 'boolean', description: 'true, wenn der Fund nicht zutrifft oder nicht relevant ist' },
    reason: { type: 'string' },
  },
  required: ['refuted', 'reason'],
}

const BLICKWINKEL = [
  {
    key: 'engine',
    prompt:
      'Suche konkrete Fehler in app/src/main/java/com/jdandroid/engine (DownloadEngine, DownloadService, ' +
      'Extractor, BootReceiver, SpeedLimiter) und data (Db, LinkSink, LinkChecker, AccountRefresher, ' +
      'SettingsRepository, Secrets): Zustandsautomat der Downloads, Races, Fortsetzen (Range/206/416), ' +
      'HTML statt Datei, Dateinamen, Entpacken von Multipart-Sets, Fortschritt, Export (SAF/MediaStore), ' +
      'Abbruch und Pause, Neustart nach Absturz.',
  },
  {
    key: 'hoster',
    prompt:
      'Suche konkrete Fehler in app/src/main/java/com/jdandroid/hoster und container: Einheiten (1024), ' +
      'Ablaufdaten, voruebergehend vs. dauerhaft (5xx und Sperren duerfen Konten nie abschalten), ' +
      'Weiterleitungsketten, Cookie-Handling, API-Antworten ohne JSON, Regex-Randfaelle, ' +
      "Click'n'Load (Preflight-Header, Formularfelder, Limits), DLC-Entschluesselung.",
  },
  {
    key: 'ui',
    prompt:
      'Suche konkrete Fehler in app/src/main/java/com/jdandroid/ui und AndroidManifest.xml: sichtbare ' +
      'Schaltflaechen (Insets, Tastatur), Aktionsmenues vollstaendig, Dialoge drehfest (rememberSaveable), ' +
      'Hauptthread-IO, Flow-Sammlung mit Lebenszyklus, Benachrichtigungen und Berechtigungen, ' +
      'Vordergrunddienst-Typen fuer Android 14/15, Predictive Back, nur Material You.',
  },
  {
    key: 'architektur',
    prompt:
      'Bewerte Architektur und Stand der Technik des gesamten app/src/main-Baums sowie app/build.gradle.kts ' +
      'und gradle/libs.versions.toml: Schichten, zu grosse Klassen, doppelte Hilfsfunktionen, ' +
      'Coroutine-Scopes und Dispatcher, Room-Abfragen (all()-Schleifen), veraltete Abhaengigkeiten, ' +
      'CI-Workflow. Melde nur Punkte mit konkretem Nutzen und konkretem Umbauvorschlag.',
  },
  {
    key: 'sicherheit',
    prompt:
      'Pruefe Sicherheit: network_security_config, exportierte Komponenten und Intent-Filter, ' +
      "Eingaben des Click'n'Load-Servers, Keystore-Nutzung in Secrets, WebView-Einstellungen, " +
      'Geheimnisse in Logs oder Meldungen, Pfadangriffe beim Entpacken (Zip-Slip), Dateinamen vom Server.',
  },
  {
    key: 'tests',
    prompt:
      'Pruefe die Tests unter app/src/test und app/src/androidTest: Sind sie sinnvoll (Verhalten statt ' +
      'Implementierung), deterministisch, nicht doppelt? Welche wichtigen Verhalten sind ungetestet und ' +
      'auf der JVM sinnvoll testbar (reine Funktionen, MockWebServer fuer Hoster)? Melde schwache Tests ' +
      'als Funde (severity low) und fehlende Tests als Funde mit dem Titel "Test fehlt: ..." und dem ' +
      'genauen Szenario; das Feld fix beschreibt den Test.',
  },
]

// Vorab-Berichte (z.B. aus einer frueheren Sitzung) koennen die Suche ersetzen.
const vorab = args && Array.isArray(args.reports) ? args.reports : null

phase('Finden')
let funde
if (vorab) {
  log(`Nutze ${vorab.length} vorab erstellte Berichte statt eigener Suche`)
  const extrahiert = await parallel(
    vorab.map((text, i) => () =>
      agent(
        BASIS +
          'Extrahiere aus folgendem Pruefbericht alle konkreten Funde als strukturierte Liste; ' +
          'lasse reine Stilhinweise weg. Bericht:\n\n' + text,
        { label: `bericht:${i + 1}`, phase: 'Finden', schema: FUNDE_SCHEMA, effort: 'low' }
      )
    )
  )
  funde = extrahiert.filter(Boolean).flatMap(r => r.findings)
} else {
  const gefunden = await parallel(
    BLICKWINKEL.map(b => () =>
      agent(BASIS + b.prompt + ' Liefere nur verifizierte, konkrete Funde mit Datei und Zeile.', {
        label: `finden:${b.key}`,
        phase: 'Finden',
        schema: FUNDE_SCHEMA,
      })
    )
  )
  funde = gefunden.filter(Boolean).flatMap(r => r.findings)
}

// Duplikate (gleiche Datei + Zeile) zusammenfuehren, bevor teuer verifiziert wird
const gesehen = new Set()
funde = funde.filter(f => {
  const key = `${f.file}:${f.line ?? 0}:${f.title.toLowerCase().slice(0, 40)}`
  if (gesehen.has(key)) return false
  gesehen.add(key)
  return true
})
log(`${funde.length} Funde nach Zusammenfuehrung`)
if (funde.length === 0) {
  return { confirmed: [], refuted: [], fixed: [], build: 'nicht noetig - keine Funde' }
}

phase('Verifizieren')
const geprueft = await parallel(
  funde.map((f, i) => () =>
    parallel(
      ['Korrektheit', 'Relevanz im echten Ablauf'].map(linse => () =>
        agent(
          BASIS +
            `Versuche, diesen Fund zu WIDERLEGEN (Blickwinkel: ${linse}). Lies die genannte Stelle ` +
            `und ihr Umfeld. Bei Unsicherheit gilt refuted=true. Fund: ${f.title} in ${f.file}` +
            `${f.line ? ':' + f.line : ''}. Szenario: ${f.scenario}. Vorschlag: ${f.fix}`,
          { label: `pruefen:${i + 1}/${funde.length}`, phase: 'Verifizieren', schema: URTEIL_SCHEMA }
        )
      )
    ).then(urteile => {
      const gueltig = urteile.filter(Boolean)
      // Bestaetigt, wenn kein Skeptiker widerlegt hat (bei Test-Funden reicht ein Urteil)
      const bestaetigt = gueltig.length > 0 && gueltig.every(u => !u.refuted)
      return { ...f, confirmed: bestaetigt, verdicts: gueltig.map(u => u.reason) }
    })
  )
)
const bestaetigt = geprueft.filter(Boolean).filter(f => f.confirmed)
const widerlegt = geprueft.filter(Boolean).filter(f => !f.confirmed)
log(`${bestaetigt.length} bestaetigt, ${widerlegt.length} widerlegt`)

phase('Beheben')
// Nacheinander, gruppiert nach Datei: parallele Aenderungen an derselben Datei wuerden kollidieren.
const reihenfolge = ['critical', 'high', 'medium', 'low']
bestaetigt.sort((a, b) => reihenfolge.indexOf(a.severity) - reihenfolge.indexOf(b.severity))
const gruppen = new Map()
for (const f of bestaetigt) {
  const key = f.file
  if (!gruppen.has(key)) gruppen.set(key, [])
  gruppen.get(key).push(f)
}
const behoben = []
let nr = 0
for (const [datei, liste] of gruppen) {
  nr++
  const beschreibung = liste
    .map(f => `- ${f.title} (${f.severity})${f.line ? ', Zeile ' + f.line : ''}: ${f.scenario} Vorschlag: ${f.fix}`)
    .join('\n')
  const ergebnis = await agent(
    BASIS +
      `Behebe die folgenden bestaetigten Funde in ${datei} (und, falls noetig, in direkt betroffenen ` +
      'Dateien) minimal und sauber. Ergaenze fuer jedes behobene Verhalten einen Unit-Test unter ' +
      'app/src/test, wenn es auf der JVM sinnvoll testbar ist; Funde mit dem Titel "Test fehlt" bedeuten ' +
      'nur einen neuen Test. Fuehre danach ./gradlew --offline -q testDebugUnitTest aus und behebe ' +
      'Fehlschlaege. Aendere nichts, was nicht zu den Funden gehoert. Berichte am Ende in zwei bis ' +
      'fuenf Zeilen, was geaendert wurde.\n\nFunde:\n' + beschreibung,
    { label: `beheben:${nr}/${gruppen.size} ${datei.split('/').pop()}`, phase: 'Beheben' }
  )
  behoben.push({ file: datei, findings: liste.map(f => f.title), report: ergebnis })
}

phase('Bauen')
const build = await agent(
  BASIS +
    'Fuehre nacheinander aus: ./gradlew --offline -q assembleRelease und danach ' +
    './gradlew --offline -q testDebugUnitTest compileDebugAndroidTestKotlin. Entsteht dabei eine leere ' +
    'Datei unter app/schemas/com.jdandroid.data.AppDatabase/, loesche sie und baue erneut. Behebe ' +
    'Kompilier- oder Testfehler, die aus den gerade gemachten Aenderungen stammen. Melde: Build-Ergebnis, ' +
    'Anzahl der Tests und Fehlschlaege, sowie git status --short. Erhoehe KEINE Version und committe NICHT.',
  { label: 'bauen', phase: 'Bauen' }
)

return {
  confirmed: bestaetigt.map(f => ({ title: f.title, file: f.file, line: f.line, severity: f.severity })),
  refuted: widerlegt.map(f => ({ title: f.title, file: f.file, reasons: f.verdicts })),
  fixed: behoben,
  build,
}
