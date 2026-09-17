from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
m = (root / 'app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java').read_text()
b = (root / 'app/build.gradle').read_text()
assert 'versionCode 45' in b
assert 'versionName "2.0.1-internal.1"' in b
assert 'applicationId "io.github.asteroidb612zs.hondatadash.internal"' in b
assert "setPrefixScale('E', 1.0f)" in m
for tok in [
    'STRIM_WEIGHT_TRUSTED = 1.00f', 'STRIM_WEIGHT_CONTEXT = 0.82f', 'STRIM_WEIGHT_LOW = 0.62f',
    'applyStrimInterpretabilityVisual', 'getStrimInterpretabilityTarget', 'updateStrimPresentationWeight',
    'RC7_EXTREME_LABEL_SESSION_STYLE', 'RECENT_PEAK_HOLD_MS', 'isEligibleForExtreme',
    'case 4: // MAP — MAX = real turbo demand; MIN = real engine vacuum.',
    'case 5: // A/F — both HI/LO only when combustion itself is interpretable.',
    'case 6: // IGN — HI may describe normal advance; LO requires meaningful load.',
    'case 7: // S.TRIM — trend extrema only in stable closed-loop context.',
]:
    assert tok in m, tok
policy = re.search(r'private static final int\[] EXTREME_POLICY = \{(.*?)\};', m, re.S).group(1)
entries = [x.strip() for x in policy.split(',') if x.strip()]
assert len(entries) == 8, entries
assert all('EXTREME_MAX | EXTREME_MIN' in x for x in entries), entries
assert 'highLabel.setVisibility(View.VISIBLE);' in m
assert 'lowLabel.setVisibility(View.VISIBLE);' in m
assert 'maxValueViews[i].setVisibility(View.VISIBLE);' in m
assert 'minValueViews[i].setVisibility(View.VISIBLE);' in m
assert 'highLabel.setText(RC7_EXTREME_LABEL_SESSION_STYLE[i] ? "MAX" : "HI")' in m
assert 'lowLabel.setText(RC7_EXTREME_LABEL_SESSION_STYLE[i] ? "MIN" : "LO")' in m
assert 'renderExtremeText(maxValueViews[i], "--")' in m
assert 'renderExtremeText(minValueViews[i], "--")' in m
assert 'private static final long RECENT_PEAK_HOLD_MS = 30000' not in m
print('PASS: V2.0.1-internal.1 S.TRIM interpretability + dual-slot semantic extrema + package-id contract')
