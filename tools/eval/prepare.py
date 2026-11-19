"""Nukopijuoja programos skaičiavimo failus į app-src/ ir iš PositionSolver.kt išmeta tik įrenginyje naudojamą solveFiles()."""
import os, re, shutil
here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, '..', '..', 'app', 'src', 'main', 'java', 'lt', 'example', 'gnss')
dst = os.path.join(here, 'app-src'); os.makedirs(dst, exist_ok=True)
for f in ['Sp3V5.kt', 'Sp3V4.kt', 'PositionSolver.kt', 'Sp3Solver.kt', 'Sp3Reader.kt', 'PosFilter.kt', 'CnoMask.kt',
          'AppDiag.kt', 'DualFreq.kt', 'DopplerVel.kt', 'Brdc.kt', 'NavDecoders.kt', 'SatHealth.kt']:
    shutil.copy(os.path.join(src, f), dst)
p = os.path.join(dst, 'PositionSolver.kt'); s = open(p, encoding='utf-8').read()
i = s.index('    /** Pagrindinis įėjimas: nuskaito failus')
j = s.index('    /** SP3 failo, naudoto skaičiavimui, kopija')
open(p, 'w', encoding='utf-8').write(s[:i] + s[j:])
p = os.path.join(dst, 'NavDecoders.kt'); s = open(p, encoding='utf-8').read()
i = s.find('object NavFeed'); j = s.rfind('/**', 0, i)
open(p, 'w', encoding='utf-8').write(s[:j if j > s.rfind('}', 0, i) else i])
print('app-src paruoštas')
