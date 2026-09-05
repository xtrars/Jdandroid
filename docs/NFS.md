# NFS-Freigabe (NAS) als Speicherziel

Seit 0.2.0 kann JDAndroid fertige Downloads und entpackte Inhalte auf eine
NFS-Freigabe im eigenen Netz legen, etwa auf ein Synology-, QNAP- oder
TrueNAS-Gerät. Die App spricht NFSv3 über TCP mit `AUTH_SYS` (uid/gid) und
braucht dafür weder Root noch eine Systemeinbindung des Exports.

## So läuft es ab

- Download und Entpacken bleiben lokal im App-Ordner: Fortsetzen per
  HTTP-Range und das Entpacken von RAR-Archiven brauchen lokale Dateien.
- Fertige Dateien und entpackte Inhalte werden danach per NFS nach
  `Export-Pfad/Unterordner/<Paketname>/` hochgeladen und lokal gelöscht.
  Vorhandene Dateien werden nicht überschrieben, gleiche Namen erhalten
  „(2)“, „(3)“ … wie bei den anderen Zielen.
- Ist das NAS nicht erreichbar (ausgeschaltet, anderes WLAN, Mobilfunk),
  bleibt die Datei lokal mit dem Vermerk **„Wartet auf NAS“**. Die App
  versucht den Upload bei jedem Netzwechsel und im Minutentakt erneut, ohne
  Nutzereingriff.
- Dauerhafte Fehler (Zugriff verweigert, Export fehlt) erscheinen als
  Fehlertext am Eintrag; die Datei bleibt lokal erhalten.
- Das NFS-Ziel hat Vorrang vor dem SAF-Zielordner und vor
  `Downloads/JDAndroid/`. Ist es abgeschaltet oder unvollständig
  eingetragen, gilt wieder die bisherige Reihenfolge.

## Voraussetzungen am NAS

| Punkt | Was nötig ist |
|---|---|
| Protokoll | NFSv3 eingeschaltet (NFSv4 wird nicht verwendet) |
| Ports | 111 (rpcbind/portmapper), 2049 (nfsd) und der mountd-Port aus dem WLAN erreichbar; bei fester mountd-Portnummer auch diese freigeben |
| Nicht-privilegierte Ports | Export mit Option `insecure` bzw. „Verbindungen von nicht-privilegierten Ports zulassen“. Android-Apps können keine Ports unter 1024 verwenden; ohne diese Option lehnt der Server das Einhängen mit `MNT3ERR_ACCES` ab |
| Squash / Rechte | Die in der App eingetragene uid/gid muss im Zielordner schreiben dürfen – entweder ein Benutzer mit dieser uid/gid, oder das NAS ordnet alle Zugriffe einem schreibberechtigten Konto zu (`all_squash` mit `anonuid`/`anongid`, bei Synology „Alle Benutzer zu admin zuordnen“) |
| Host-Regel | Die IP des Android-Geräts oder das Subnetz des WLANs (z. B. `192.168.1.0/24`) ist in der Export-Regel eingetragen, mit Lesen/Schreiben |

## Einrichtung Schritt für Schritt

### Synology DSM

1. **Systemsteuerung → Dateidienste → NFS**: „NFS-Dienst aktivieren“
   anhaken. Als maximales NFS-Protokoll genügt NFSv3; NFSv4 darf zusätzlich
   an sein, wird aber nicht genutzt.
2. **Systemsteuerung → Freigabeordner** → Ordner wählen → **Bearbeiten →
   NFS-Berechtigungen → Erstellen**:
   - *Hostname oder IP*: IP des Android-Geräts oder das WLAN-Subnetz,
     z. B. `192.168.1.0/24`.
   - *Berechtigung*: Lesen/Schreiben.
   - *Squash*: „Alle Benutzer zu admin zuordnen“ (einfachster Weg) oder
     „Keine Zuordnung“, wenn es auf dem NAS einen Benutzer mit der in der
     App eingetragenen uid/gid gibt, der auf den Ordner schreiben darf.
   - *Sicherheit*: `sys`.
   - **„Verbindungen von nicht-privilegierten Ports zulassen“ anhaken.**
     Ohne diesen Haken scheitert jedes Einhängen aus einer Android-App.
   - „Benutzern den Zugriff auf eingehängte Unterordner erlauben“ kann an
     bleiben.
3. Unten im Dialog steht der **Mount-Pfad**, z. B. `/volume1/downloads`.
   Das ist der Export-Pfad für die App.
4. Firewall (falls aktiv): Regel für NFS (111, 2049, mountd) aus dem
   WLAN-Subnetz zulassen.

### QNAP QTS

1. **Systemsteuerung → Netzwerk- und Dateidienste → Win/Mac/NFS → NFS-Dienst**:
   NFSv3 aktivieren.
2. **Systemsteuerung → Berechtigungen → Freigabeordner** → Ordner →
   **Freigabeordnerberechtigung bearbeiten → NFS-Hostzugriff**: Zugriff
   einschalten, Host oder Subnetz eintragen, Lesen/Schreiben, Squash auf
   „Alle Benutzer zuordnen“ mit einem schreibberechtigten Konto (oder
   „Keine Benutzerzuordnung“ mit passender uid/gid).
3. Export-Pfad ist der Pfad des Freigabeordners auf dem Volume, z. B.
   `/share/CACHEDEV1_DATA/Download` (steht in der Freigabeübersicht unter
   „Pfad“). QNAP erlaubt Verbindungen von nicht-privilegierten Ports in der
   Regel ohne weitere Einstellung; scheitert das Einhängen mit „Zugriff
   verweigert“, in den NFS-Optionen der Freigabe nach `insecure` bzw.
   „nicht-privilegierte Ports“ sehen.

### TrueNAS und Linux-Server

Bei TrueNAS unter **Shares → Unix (NFS) Shares** die Freigabe anlegen,
NFSv3 in den NFS-Diensteinstellungen aktivieren, „Maproot“ bzw. „Mapall“ auf
einen schreibberechtigten Benutzer setzen und „Allow non-root mount“ bzw.
`insecure` in den zusätzlichen Optionen zulassen.

Auf einem Linux-Server genügt ein Eintrag in `/etc/exports`:

```
/srv/downloads  192.168.1.0/24(rw,insecure,all_squash,anonuid=1000,anongid=1000)
```

Danach `exportfs -ra` ausführen und mit `showmount -e <server>` prüfen, ob
der Export erscheint. `anonuid`/`anongid` müssen zu einem Konto gehören, dem
`/srv/downloads` gehört bzw. das dort schreiben darf; `insecure` erlaubt die
Verbindung von Ports über 1023.

## Werte in der App

Einstellungen → **NFS-Freigabe (NAS)**:

| Feld | Eintrag |
|---|---|
| Schalter | einschalten; ohne Server und Export-Pfad bleibt das Ziel aus |
| Server | Hostname oder IP des NAS, z. B. `192.168.1.10` oder `nas.local` (mDNS-Namen lösen nicht auf jedem Android-Gerät auf; im Zweifel die IP eintragen und am Router fest vergeben) |
| Export-Pfad | der Export wie vom NAS angezeigt, z. B. `/volume1/downloads` (Synology), `/share/CACHEDEV1_DATA/Download` (QNAP), `/mnt/pool/downloads` (TrueNAS) |
| Unterordner | optional, z. B. `JDAndroid`; wird unterhalb des Exports angelegt |
| uid / gid | Standard 1000/1000. Bei „Alle Benutzer zuordnen“ am NAS ist der Wert egal; bei „Keine Zuordnung“ die uid/gid des NAS-Benutzers eintragen (`id <name>` per SSH auf dem NAS) |

**Verbindung prüfen** hängt den Export ein, listet den Zielordner und zeigt
den freien Platz. Erst wenn das gelingt, lohnt sich das Einschalten.

## Fehlerbilder und Abhilfe

| Meldung | Ursache | Abhilfe |
|---|---|---|
| „NAS nicht erreichbar“ / Zeitüberschreitung | Gerät nicht im selben Netz, NAS aus, Firewall blockt 111/2049/mountd, Mobilfunk statt WLAN | WLAN prüfen, Ports in der NAS-Firewall freigeben, `showmount -e <server>` von einem Rechner im selben Netz testen |
| „Zugriff verweigert beim Einhängen“ (`MNT3ERR_ACCES`) | Option `insecure` / „nicht-privilegierte Ports“ fehlt, oder die IP des Geräts passt nicht zur Host-Regel des Exports | Haken setzen, Host-Regel auf das WLAN-Subnetz erweitern; nach Änderungen an der Regel die Prüfung wiederholen |
| „Zugriff verweigert beim Schreiben“ (`NFS3ERR_ACCES`) | Squash-Einstellung oder uid/gid ohne Schreibrecht im Zielordner | Squash auf ein schreibberechtigtes Konto setzen oder uid/gid an einen Benutzer mit Schreibrecht anpassen; Rechte des Ordners auf dem NAS prüfen |
| „Export nicht gefunden“ (`MNT3ERR_NOENT`) | Export-Pfad falsch geschrieben oder Freigabe hat keine NFS-Regel | Mount-Pfad aus dem NFS-Dialog des NAS übernehmen (Groß-/Kleinschreibung beachten), `showmount -e` vergleichen |
| „Wartet auf NAS“ an fertigen Downloads | NAS vorübergehend nicht erreichbar | Nichts zu tun: der Upload wird bei Netzwechsel und im Minutentakt wiederholt |

## Sicherheit

NFSv3 mit `AUTH_SYS` ist unverschlüsselt und kennt keine Passwörter: Jeder
im selben Netz, den die Host-Regel des Exports zulässt, kann mit einer
beliebigen uid zugreifen, und die übertragenen Dateien laufen im Klartext
durch das WLAN. Deshalb:

- Nur im eigenen, vertrauenswürdigen Netz verwenden, nie über das Internet
  oder ein offenes WLAN; die NFS-Ports am Router nicht weiterleiten.
- Host-Regel am NAS so eng wie möglich fassen (feste IP des Geräts statt
  ganzes Subnetz, wenn der Router feste Adressen vergibt).
- Einen eigenen Freigabeordner für die App verwenden, nicht das
  Home-Verzeichnis oder Ordner mit persönlichen Daten.

Die App speichert Server, Export-Pfad, Unterordner, uid und gid lokal in
ihren Einstellungen; Passwörter oder Schlüssel gibt es bei NFSv3 nicht.
Andere Daten (Konten, Warteschlange) gehen nicht an das NAS.

## Grenzen

- Nur NFSv3 über TCP mit `AUTH_SYS`; kein NFSv4, kein Kerberos (`krb5`),
  kein NFS über UDP.
- Das NAS ist ein Ablageziel, keine Quelle: Downloads und Entpacken laufen
  lokal, das Gerät braucht also weiterhin so viel freien Speicher wie das
  größte Archiv samt Inhalt.
- Nachträgliches Entpacken holt Archivteile vom NAS zurück auf das Gerät.
- Ein Wechsel des Export-Pfads verschiebt bereits hochgeladene Dateien
  nicht.
