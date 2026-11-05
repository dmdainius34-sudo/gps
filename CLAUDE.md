# CLAUDE.md

Android GNSS programa (`lt.example.gnss`), Kotlin, viena veikla `app/src/main/java/lt/example/gnss/MainActivity.kt`.

## Ekrano nuotraukos

- Workflow `.github/workflows/screens.yml` (tik `workflow_dispatch`), įvestis `mode`:
  - `shot` (numatytasis) – `shot.png`, 540 px pločio;
  - `video` – `sheet.png`, 6 kadrų lapas (fps=1, tile=6x1, scale=360:-1).
- Fiksavimą atlieka `tools/capture.sh [shot|video] [katalogas]`.
- Rezultatas – artifact `gnss-screens`; atsisiųsti į `screens/`.

## Taisyklės

- Vienoje iteracijoje – ne daugiau kaip **vienas** workflow paleidimas.
- Vienoje iteracijoje peržiūrai – ne daugiau kaip **3** nuotraukos.
- Nekeisk `MainActivity.kt`, jei to aiškiai neprašoma.

## Atsakymų stilius

- Dirbk be ilgų komentarų; visą darbą (kodas, kompiliavimas, testai) atlik tyliai, be tarpinių pranešimų.
- Galutiniame atsakyme pateik tik:
  - ar build pavyko;
  - APK artifact pavadinimą (`app-debug` iš `build.yml`);
  - vieną eilutę apie pakeitimus.
- Ekrano kopijų nežiūrėk ir jų nepasakok, nebent tai tiesiogiai paprašyta.
- Jei build nukrenta – pateik tik klaidos eilutę (`e:` arba `FAILURE`).
