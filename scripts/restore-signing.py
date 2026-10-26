"""Restore the single signing secret without printing credentials or key bytes."""
import base64
import json
import os
from pathlib import Path

raw = os.environ.get('ANDROID_SIGNING_BUNDLE', '')
if not raw:
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write('configured=false\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
        out.write('### Podpis APK wymaga konfiguracji\nDodaj sekret `ANDROID_SIGNING_BUNDLE` zgodnie z `SIGNING_PL.md`. Testy zostaną wykonane, ale APK nie zostanie opublikowany z losowym kluczem.\n')
else:
    try:
        bundle = json.loads(raw)
        password = bundle['password']
        alias = bundle['alias']
        if not all(isinstance(v, str) and v and '\n' not in v and '\r' not in v for v in (password, alias)):
            raise ValueError()
        data = base64.b64decode(bundle['keystore'], validate=True)
        if not data or len(data) > 100_000:
            raise ValueError()
    except Exception:
        raise SystemExit('Niepoprawny ANDROID_SIGNING_BUNDLE; brak publikacji APK.')
    # Register derived strings for masking before any Gradle/keytool invocation.
    for value in (password, alias):
        escaped = value.replace('%', '%25')
        print('::add-mask::' + escaped)
    key = Path(os.environ['RUNNER_TEMP']) / 'zlap-signing.p12'
    fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as out:
        out.write(data)
    with open(os.environ['GITHUB_ENV'], 'a') as out:
        out.write(f'ZLAP_KEYSTORE={key}\nZLAP_KEY_PASSWORD={password}\nZLAP_KEY_ALIAS={alias}\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write('configured=true\n')
