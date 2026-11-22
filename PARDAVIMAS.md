# Pasiruošimas pardavimui

Ne teisinė išvada – prieš pardavimą verta pasitikrinti su teisininku.

## Jau padaryta
- Palydovinės plytelės – Sentinel-2 cloudless **2016** (CC BY 4.0, komercinis naudojimas leidžiamas su nuoroda). Naujesni sluoksniai (CC BY-NC-SA) nebenaudojami, jų kaupiniai ištrinami.
- Pašalinta Anthropic (Claude) biblioteka.
- Nustatymuose – „Apie programą ir šaltiniai…“ (visi šaltiniai ir licencijos, `Credits.kt`).
- Autorystė ekrane, kai rodomos plytelės.
- Open-Meteo pakeistas: jūros lygio slėgis – MET Norway (CC BY 4.0), žemės aukštis ir jūros gylis – Terrain Tiles (AWS atvirieji duomenys); UV patikra pašalinta.
- EGM2008 geoidas (NGA, viešoji sritis) – CI paruošia `WORLD_EGM2008.txt` (0,25° tinklelis) iš PROJ CDN.

## Dar reikia išspręsti
1. **MET Norway sąlygos** – užklausose turi būti programos pavadinimas ir kontaktas (svetainė ar el. paštas); prieš pardavimą įrašyti savo kontaktą į User-Agent (`MainActivity.kt`, `updateMslp`).
2. **LIT20G geoidas** – patikrinti, ar jo sąlygos leidžia įdėti į parduodamą programą (gauti leidimą iš savininko).
3. **Saulės tekstūra `sun.jpg`** – nustatyti kilmę (žvaigždžių fonas – NASA SVS, viešoji sritis). **HYG** žvaigždžių duomenys – CC BY-SA 4.0: jų pakeistą failą platinti su ta pačia licencija.
4. **Programos pavadinimas ir paketas** – dabar „GNSS App“ ir `lt.example.gnss`; prieš Google Play reikia savo pavadinimo ir paketo (pvz. `lt.<vardas>.gnss`).
5. **Google Play reikalavimai** – privatumo politika (vieta, fono vieta, rodymas virš kitų programų), leidimų paaiškinimai, pasirašytas „release“ APK/AAB.
6. **NASA/ESA/NOAA** – jų logotipų nenaudoti ir nerašyti, kad jie programą rekomenduoja.
