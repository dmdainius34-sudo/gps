# Skaičiavimo patikra (be Android)

Programos padėties skaičiavimas (Sp3V5, glodinimas pagal Doplerį, RAIM, PosFilter su greičiu) paleidžiamas kompiuteryje.

    python3 prepare.py
    gradle -q run --args="<GSDC važiavimo katalogas> ..."   # device_gnss.csv + ground_truth.csv (Google Smartphone Decimeter Challenge 2023)
    gradle -q run --args="own <išskleistas programos ZIP> ..."  # gnss_raw.csv + *.SP3; tiesa – „Žinomos koordinatės“ ataskaitoje

GSDC duomenys (Kaggle) į saugyklą nededami – jų licencija leidžia tik tyrimus.
