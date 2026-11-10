# CLAUDE.md

Android GNSS programa (`lt.example.gnss`), Kotlin, viena veikla `app/src/main/java/lt/example/gnss/MainActivity.kt`.

## Repozitorija ir build

- Darbo šaka `claude/screens-workflow-setup-9rqg9v`; GitHub `dmdainius34-sudo/gps` (paskyra atkurta) ir GitLab `gitlab.com/dmdainius34/gps` – ta pati šaka abiejose.
- Build: GitHub `.github/workflows/build.yml` ir GitLab `.gitlab-ci.yml` – vienetų testai ir `app-debug` APK artifact.

## Įrenginys ir GPS sprendinys

- Planšetė Samsung SM-X926B (MediaTek MT6686) navigacinių žinučių nesiunčia, todėl GPS sprendiniui naudojamos SP3 orbitos.
- `Sp3Reader.kt`, `Sp3Solver.kt` – kol kas tik testui, pavyzdžiai `samples/`.
- Įrašymas fone: `NavRecordService`, `SessionFiles`, `GapLog`.

## Ekrano nuotraukos (NEVEIKIA)

Workflow `.github/workflows/screens.yml` neveikia: GitHub paskyra sustabdyta, o GitLab nemokami serveriai emuliatoriaus ekrano nuotraukų daryti negali. Toliau – tik ankstesnės sąrankos aprašas.

- Workflow (tik `workflow_dispatch`), įvestis `mode`:
  - `shot` (numatytasis) – `shot.png`, 540 px pločio;
  - `video` – `sheet.png`, 6 kadrų lapas (fps=1, tile=6x1, scale=360:-1).
- Fiksavimą atlieka `tools/capture.sh [shot|video] [katalogas]`.
- Rezultatas – artifact `gnss-screens`; atsisiųsti į `screens/`.

## Taisyklės

- Vienoje iteracijoje – ne daugiau kaip **vienas** workflow paleidimas.
- Vienoje iteracijoje peržiūrai – ne daugiau kaip **3** nuotraukos.
- Nekeisk `MainActivity.kt`, jei to aiškiai neprašoma.
- Nekeisk pseudoatstumų, fiksų, žvaigždžių, palydovų rodymo, Žemės, stabilizacijos, geoido, pariteto kodo ir CSV formatų, jei to aiškiai neprašoma.

## Atsakymų stilius

- Dirbk be ilgų komentarų; visą darbą (kodas, kompiliavimas, testai) atlik tyliai, be tarpinių pranešimų.
- Galutiniame atsakyme pateik tik:
  - ar build pavyko;
  - APK artifact pavadinimą (`app-debug` iš `.gitlab-ci.yml`);
  - vieną eilutę apie pakeitimus.
- Ekrano kopijų nežiūrėk ir jų nepasakok, nebent tai tiesiogiai paprašyta.
- Jei build nukrenta – pateik tik klaidos eilutę (`e:` arba `FAILURE`).
