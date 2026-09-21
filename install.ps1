<#
.SYNOPSIS
  Instala el panel Cameo Cut en la carpeta de extensiones CEP del usuario.

.DESCRIPTION
  Crea un symlink (o copia con -Copy) de .\panel en
  %APPDATA%\Adobe\CEP\extensions\com.digitalfox.cameocut
  y verifica PlayerDebugMode en HKCU\Software\Adobe\CSXS.9–12.

.PARAMETER Copy
  Copia los archivos en vez de crear un symlink.

.PARAMETER Uninstall
  Elimina el symlink/carpeta instalada.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\install.ps1
  powershell -ExecutionPolicy Bypass -File .\install.ps1 -Copy
  powershell -ExecutionPolicy Bypass -File .\install.ps1 -Uninstall
#>
param(
  [switch]$Copy,
  [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

$bundleId = 'com.digitalfox.cameocut'
$src = Join-Path $PSScriptRoot 'panel'
$extRoot = Join-Path $env:APPDATA 'Adobe\CEP\extensions'
$dst = Join-Path $extRoot $bundleId

if (-not (Test-Path (Join-Path $src 'CSXS\manifest.xml'))) {
  Write-Error "No se encuentra $src\CSXS\manifest.xml. Ejecuta este script desde la raíz del proyecto."
}

# --- Desinstalar ---
if ($Uninstall) {
  if (Test-Path $dst) {
    $item = Get-Item $dst -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
      # symlink: borrar solo el enlace
      [IO.Directory]::Delete($dst)
    } else {
      Remove-Item -Recurse -Force $dst
    }
    Write-Host "Desinstalado: $dst"
  } else {
    Write-Host "No estaba instalado: $dst"
  }
  exit 0
}

# --- PlayerDebugMode ---
foreach ($v in 9..12) {
  $key = "HKCU:\Software\Adobe\CSXS.$v"
  if (-not (Test-Path $key)) { New-Item -Path $key -Force | Out-Null }
  $cur = (Get-ItemProperty -Path $key -Name PlayerDebugMode -ErrorAction SilentlyContinue).PlayerDebugMode
  if ($cur -ne '1') {
    Set-ItemProperty -Path $key -Name PlayerDebugMode -Value '1' -Type String
    Write-Host "PlayerDebugMode=1 activado en CSXS.$v"
  }
}

# --- Preparar destino ---
New-Item -ItemType Directory -Force $extRoot | Out-Null
if (Test-Path $dst) {
  $item = Get-Item $dst -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
    [IO.Directory]::Delete($dst)
  } else {
    Remove-Item -Recurse -Force $dst
  }
  Write-Host "Instalación previa eliminada."
}

# --- Instalar ---
$linked = $false
if (-not $Copy) {
  try {
    New-Item -ItemType SymbolicLink -Path $dst -Target $src | Out-Null
    $linked = $true
    Write-Host "Symlink creado: $dst -> $src"
  } catch {
    Write-Warning "No se pudo crear symlink ($($_.Exception.Message)). Se copiará la carpeta. (Activa el Modo de desarrollador de Windows o ejecuta como admin para usar symlink.)"
  }
}
if (-not $linked) {
  Copy-Item -Recurse -Force $src $dst
  Write-Host "Copiado: $src -> $dst"
}

Write-Host ""
Write-Host "Listo. Reinicia Illustrator y abre Ventana > Extensiones > Cameo Cut."
Write-Host "Depuración: con el panel abierto, visita http://localhost:8092"
