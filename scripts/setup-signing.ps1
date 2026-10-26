# Run once in Windows PowerShell with JDK (keytool) and GitHub CLI (gh) installed.
# Reuses the same private key on subsequent runs. Never commit the output folder.
$ErrorActionPreference = 'Stop'
$repo = 'lachim27/Testest'
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { throw 'Zainstaluj JDK 17 (lub użyj terminala z JDK Android Studio): brak keytool.' }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Zainstaluj GitHub CLI z https://cli.github.com/ i wykonaj gh auth login.' }
& gh auth status
if ($LASTEXITCODE -ne 0) { throw 'Najpierw wykonaj gh auth login.' }
$folder = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'ZlapEgzamin-private-signing'
$bundlePath = Join-Path $folder 'signing-bundle.json'
$keyPath = Join-Path $folder 'zlapegzamin.p12'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
# Restrict the directory to this Windows account before writing private material.
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $folder /inheritance:r /grant:r "${identity}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Nie mozna zabezpieczyc katalogu klucza.' }
if (-not (Test-Path $bundlePath)) {
    if (Test-Path $keyPath) { throw 'Klucz juz istnieje, ale brak jego konfiguracji. Nie nadpisuje go.' }
    $random = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random)
    $rng.Dispose()
    $env:ZLAP_NEW_KEY_PASSWORD = [Convert]::ToBase64String($random)
    try {
        & keytool -genkeypair -keystore $keyPath -storetype PKCS12 -alias zlapegzamin -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=ZlapEgzamin Android' -storepass:env ZLAP_NEW_KEY_PASSWORD -keypass:env ZLAP_NEW_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Nie udalo sie utworzyc klucza.' }
        $bundle = @{ keystore = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)); password = $env:ZLAP_NEW_KEY_PASSWORD; alias = 'zlapegzamin' } | ConvertTo-Json -Compress
        [IO.File]::WriteAllText($bundlePath, $bundle, (New-Object Text.UTF8Encoding($false)))
    } finally { Remove-Item Env:ZLAP_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue }
}
# Never silently replace an existing signing identity.
$existing = & gh secret list --repo $repo --json name --jq '.[].name'
if ($LASTEXITCODE -ne 0) { throw 'Nie mozna sprawdzic sekretow repozytorium.' }
if ($existing -contains 'ANDROID_SIGNING_BUNDLE') {
    throw 'ANDROID_SIGNING_BUNDLE juz istnieje. Nie nadpisuje stalego podpisu. Uruchom budowanie w GitHub Actions.'
}
Get-Content -Raw $bundlePath | & gh secret set ANDROID_SIGNING_BUNDLE --repo $repo
if ($LASTEXITCODE -ne 0) { throw 'Nie udalo sie zapisac sekretu. Lokalny klucz zostal zachowany; uruchom skrypt ponownie.' }
Write-Host "Zapisano staly klucz w GitHub Secrets. Zachowaj prywatna kopie folderu: $folder"
Write-Host 'Nie publikuj ani nie przesylaj plikow klucza na czacie.'
& gh workflow run build-apk.yml --repo $repo --ref main
if ($LASTEXITCODE -ne 0) { throw 'Klucz zapisany. Uruchom recznie workflow ZlapEgzamin Android APK w zakladce Actions.' }
Write-Host 'Uruchomiono budowe APK ze stalym podpisem.'
