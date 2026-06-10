param(
  [Parameter(Mandatory = $true)]
  [string]$TicketName,
  [switch]$AllowParallel
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

$nodeArgs = @("tools/harness-cli/index.js", "start-ticket", $TicketName)
if ($AllowParallel) {
  $nodeArgs += "--allow-parallel"
}
& node @nodeArgs
