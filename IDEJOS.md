# Idėjos ateičiai (nedaroma dabar)

## Krano apribojimas programos padėtyje (PADARYTA 2026-11-19)

Programa remiasi dviem idėjomis:
1. **Stebėtojo horizontas** – prietaisas pats nustato savo vietą ir orientaciją, viskas skaičiuojama stebėtojo ir horizonto atžvilgiu (dabartinis darbas).
2. **Krano vieta** – atėjus prie bokštinio krano, atsistojus ant žemės bokšto centre, nustatoma krano (bokšto ašies) vieta.

Kabinos apribojimas (`Crane.kt`: apskritimas aplink bokšto ašį, spindulys r, pastovus aukštis) programoje jau yra, bet naudojamas tik RTK filtre ir paprastai išjungtas. Vėliau:

- **Bokšto ašis:** stovint ant žemės bokšto centre – mygtukas „Čia krano ašis“ (kaip „Čia žemė“: 3 min. vidurkis, sklaida, atmetimas einant).
- **Kabinos spindulys:** įvedamas arba išmokstamas iš kelių valandų kabinos įrašų (apskritimo pritaikymas taškams, kai kranas sukasi).
- **Programos padėtyje (SPP, ne tik RTK):** pseudo-matavimas „horizontalus atstumas iki ašies = r“ (kaip žinomo aukščio `Sp3V5.HCon`). Su žinomu aukščiu lieka vienas nežinomasis – strėlės pasisukimo kampas; atspindžiai stumia padėtį tik išilgai apskritimo.
- **Patikra kaip žinomo aukščio:** laisva padėtis (be apribojimo) lyginama su apskritimu; nesutampa – apribojimas nenaudojamas.
- **Tikrinimas:** kabinos ZIP įrašai su tikra kabinos vieta iš apskritimo ir kampo (tools/eval), prieš įjungiant.

Padaryta: ašies matavimas, apskritimas programos padėtyje, patikra. Liko: spindulio išmokimas iš įrašų, patikra su kabinos įrašais, kai bus išmatuota ašis.
