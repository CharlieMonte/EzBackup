# Builds the mod jar. Run from PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#
# Nothing version-specific is hardcoded here. It reads:
#   - gradle.properties                       (java_version, mod_id, mod_group, mod_main_class, ...)
#   - gradle/wrapper/gradle-wrapper.properties (Gradle version)
#
# What it does:
#   1. finds a JDK of the required version (java_version in gradle.properties)
#   2. uses ./gradlew.bat if you copied the wrapper in, otherwise downloads the Gradle version named in
#      gradle-wrapper.properties into .gradle-dist
#   3. runs "gradle clean build"
#   4. copies the jar to this folder and checks what is inside it

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location $PSScriptRoot

function Read-Properties([string]$path) {
    $props = @{}
    foreach ($line in Get-Content $path) {
        if ($line -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
    }
    return $props
}

$props = Read-Properties '.\gradle.properties'
$javaVersion = [int]$props['java_version']
$mainClassPath = $props['mod_main_class'].Replace('.', '/') + '.class'

$wrapper = Read-Properties '.\gradle\wrapper\gradle-wrapper.properties'
if ($wrapper['distributionUrl'] -match 'gradle-([\d.]+)-(bin|all)\.zip') {
    $gradleVersion = $Matches[1]
} else {
    Write-Host 'Cannot read the Gradle version from gradle/wrapper/gradle-wrapper.properties.' -ForegroundColor Red
    exit 1
}

function Get-JavaMajor([string]$javaExe) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $javaExe -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $old
    }
    if ($text -match 'version "(\d+)(\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[3]) { $major = [int]$Matches[3] }
        return $major
    }
    return 0
}

function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $roots = @(
        "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft",
        "$env:USERPROFILE\.jdks", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $candidates += @(Get-ChildItem $root -Directory | Where-Object { $_.Name -match "$javaVersion" } | ForEach-Object { $_.FullName })
        }
    }
    foreach ($jdkDir in $candidates) {
        $java = Join-Path $jdkDir 'bin\java.exe'
        $javac = Join-Path $jdkDir 'bin\javac.exe'
        if ((Test-Path $java) -and (Test-Path $javac) -and ((Get-JavaMajor $java) -eq $javaVersion)) { return $jdkDir }
    }
    return $null
}

# ---- 1. Java -------------------------------------------------------------------------------------
$jdk = Find-Jdk
if (-not $jdk) {
    Write-Host ''
    Write-Host "No JDK $javaVersion found. Install one, then open a NEW PowerShell window and run this script again:" -ForegroundColor Red
    Write-Host "    winget install EclipseAdoptium.Temurin.$javaVersion.JDK" -ForegroundColor Yellow
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Write-Host "Using JDK $javaVersion at $jdk"

# ---- 2. Gradle -----------------------------------------------------------------------------------
if ((Test-Path '.\gradlew.bat') -and (Test-Path '.\gradle\wrapper\gradle-wrapper.jar')) {
    $gradle = (Resolve-Path '.\gradlew.bat').Path
    Write-Host 'Using the Gradle wrapper (gradlew.bat).'
} else {
    $dist = Join-Path $PSScriptRoot '.gradle-dist'
    $gradle = Join-Path $dist "gradle-$gradleVersion\bin\gradle.bat"
    if (-not (Test-Path $gradle)) {
        Write-Host "Downloading Gradle $gradleVersion (one time, about 120 MB)..."
        New-Item -ItemType Directory -Force $dist | Out-Null
        $zip = Join-Path $dist "gradle-$gradleVersion-bin.zip"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $dist -Force
        Remove-Item $zip
    }
    Write-Host "Using Gradle $gradleVersion from .gradle-dist."
}

# ---- 3. Build ------------------------------------------------------------------------------------
Write-Host ''
Write-Host 'Building. The first build downloads Minecraft/Fabric tooling and can take 5-15 minutes.'
& $gradle clean build --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host 'BUILD FAILED. Copy the red/ERROR lines above (and the lines just before them) and send them back.' -ForegroundColor Red
    exit 1
}

# ---- 4. Collect and check the jar ----------------------------------------------------------------
$built = Get-ChildItem '.\build\libs' -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '-(sources|javadoc|slim|dev)' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $built) {
    Write-Host 'Build reported success but no jar was found in build\libs.' -ForegroundColor Red
    exit 1
}
$out = Join-Path $PSScriptRoot $built.Name
Copy-Item $built.FullName $out -Force

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($out)
try { $names = @($archive.Entries | ForEach-Object { $_.FullName }) } finally { $archive.Dispose() }

$checks = @(
    @{ Name = "mod class ($mainClassPath)";                Ok = ($names -contains $mainClassPath) },
    @{ Name = 'fabric.mod.json';                             Ok = ($names -contains 'fabric.mod.json') },
    @{ Name = 'client entrypoint (EzBackupClient)';          Ok = ($names -contains 'com/ezbackup/client/EzBackupClient.class') },
    @{ Name = 'Zstd classes bundled';                        Ok = ($names -contains 'com/github/luben/zstd/ZstdOutputStream.class') },
    @{ Name = 'native libraries bundled (.dll/.so/.dylib)';   Ok = (@($names | Where-Object { $_ -match '\.(dll|so|dylib)$' }).Count -ge 2) },
    @{ Name = 'no Minecraft classes inside the jar';   Ok = (@($names | Where-Object { $_ -like 'net/minecraft/*' -or $_ -like 'net/fabricmc/*' }).Count -eq 0) },
    @{ Name = 'no module-info.class';                        Ok = (-not ($names | Where-Object { $_ -like '*module-info.class' })) }
)
Write-Host ''
$allOk = $true
foreach ($c in $checks) {
    if ($c.Ok) { Write-Host ('  PASS  ' + $c.Name) -ForegroundColor Green }
    else       { Write-Host ('  FAIL  ' + $c.Name) -ForegroundColor Red; $allOk = $false }
}
Write-Host ''
$sizeMb = [math]::Round((Get-Item $out).Length / 1MB, 2)
Write-Host "Created: $out ($sizeMb MB)"
if ($allOk) {
    Write-Host 'Copy the jar into your mods folder (together with Fabric API), then start the game.' -ForegroundColor Green
} else {
    Write-Host 'The jar was created but a check failed. Do not use it yet; send me this output.' -ForegroundColor Yellow
    exit 2
}
