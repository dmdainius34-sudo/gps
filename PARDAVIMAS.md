# Pasiruošimas pardavimui

Ne teisinė išvada – prieš pardavimą verta pasitikrinti su teisininku.

## Jau padaryta
- Palydovinės plytelės – Sentinel-2 cloudless **2016** (CC BY 4.0, komercinis naudojimas leidžiamas su nuoroda). Naujesni sluoksniai (CC BY-NC-SA) nebenaudojami, jų kaupiniai ištrinami.
- Pašalinta Anthropic (Claude) biblioteka.
- Nustatymuose – „Apie programą ir šaltiniai…“ (visi šaltiniai ir licencijos, `Credits.kt`).
- Autorystė ekrane, kai rodomos plytelės.

## Dar reikia išspręsti
1. **Open-Meteo** (jūros lygio slėgis, UV, žemės aukštis, ekrano centro aukštis) – nemokamas API tik nekomerciniam naudojimui.
   Parduodant: mokamas Open-Meteo planas arba kiti šaltiniai (pvz. MET Norway – slėgis ir UV, leidžia komercinį naudojimą su nuoroda).
2. **LIT20G geoidas** – patikrinti, ar jo sąlygos leidžia įdėti į parduodamą programą (gauti leidimą iš savininko).
3. **Žvaigždžių fonas `stars_bg.jpg`, Saulės tekstūra `sun.jpg`** – nustatyti kilmę ir licenciją (jei ESA Gaia – CC BY-SA 3.0 IGO).
4. **Programos pavadinimas ir paketas** – dabar „GNSS App“ ir `lt.example.gnss`; prieš Google Play reikia savo pavadinimo ir paketo (pvz. `lt.<vardas>.gnss`).
5. **Google Play reikalavimai** – privatumo politika (vieta, fono vieta, rodymas virš kitų programų), leidimų paaiškinimai, pasirašytas „release“ APK/AAB.
6. **NASA/ESA/NOAA** – jų logotipų nenaudoti ir nerašyti, kad jie programą rekomenduoja.
