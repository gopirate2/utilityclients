[CmdletBinding()]
param([string]$Setup, [string]$TestJavaHome)
$ErrorActionPreference = 'Stop'
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
if ($TestJavaHome) { $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' -Djava.home="' + $TestJavaHome + '"').Trim() }
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$project = Split-Path -Parent $PSScriptRoot
if (-not $Setup) { $Setup = Join-Path $project 'build\bootstrapper\UtilityClient-Setup.exe' }
$output = Join-Path $project 'build\bootstrapper'
$work = Join-Path $output ('ui-check-' + [guid]::NewGuid().ToString('N'))
$profile = Join-Path $work 'profile'
New-Item -ItemType Directory -Path $profile -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $profile 'utilityclient.json'), 'preserved configuration')
$assembly = [Reflection.Assembly]::LoadFile([IO.Path]::GetFullPath($Setup))
$flags = [Reflection.BindingFlags]'Static,NonPublic'
$instanceFlags = [Reflection.BindingFlags]'Instance,NonPublic'
$bootstrap = $assembly.GetType('Bootstrapper')
$bootstrap.GetField('Root',$flags).SetValue($null,$profile)
$bootstrap.GetField('AutoUpdates',$flags).SetValue($null,$false)
$bootstrap.GetField('AppRoot',$flags).SetValue($null,(Join-Path $work 'runtime'))
$bootstrap.GetField('ShortcutDirectory',$flags).SetValue($null,(Join-Path $work 'shortcuts'))
$type = $assembly.GetType('SetupForm')
$form = [Activator]::CreateInstance($type,$true)
function Save-Preview([string]$Name) {
    $bitmap=[Drawing.Bitmap]::new($form.Width,$form.Height)
    try {
        $form.DrawToBitmap($bitmap,[Drawing.Rectangle]::new(0,0,$form.Width,$form.Height))
        $bitmap.Save((Join-Path $output $Name))
    } finally { $bitmap.Dispose() }
}
try {
    $form.Opacity=0; $form.ShowInTaskbar=$false
    $type.GetField('launch',$instanceFlags).GetValue($form).Checked=$false
    $form.Show(); [Windows.Forms.Application]::DoEvents()
    $percentage=$type.GetField('percent',$instanceFlags).GetValue($form)
    if (-not $percentage.Parent.ClientRectangle.Contains($percentage.Bounds)) { throw 'Percentage is clipped at initial size.' }
    Save-Preview 'setup-preview.png'
    $form.ClientSize=[Drawing.Size]::new(760,620); [Windows.Forms.Application]::DoEvents()
    if (-not $percentage.Parent.ClientRectangle.Contains($percentage.Bounds)) { throw 'Percentage is clipped at minimum size.' }
    $launchButton=$type.GetField('open',$instanceFlags).GetValue($form)
    if (-not $launchButton.Parent.ClientRectangle.Contains($launchButton.Bounds)) { throw 'Launch button is clipped at minimum size.' }
    foreach ($button in $type.GetField('maintenanceButtons',$instanceFlags).GetValue($form)) {
        if (-not $button.Parent.ClientRectangle.Contains($button.Bounds)) { throw ('Maintenance button is clipped: ' + $button.Text) }
        if (-not $button.Parent.Parent.ClientRectangle.Contains($button.Parent.Bounds)) { throw 'Maintenance toolbar is clipped.' }
    }
    Save-Preview 'setup-small-preview.png'
    $form.ClientSize=[Drawing.Size]::new(880,680); [Windows.Forms.Application]::DoEvents()
    [Threading.SynchronizationContext]::SetSynchronizationContext([Windows.Forms.WindowsFormsSynchronizationContext]::new())
    $type.GetField('install',$instanceFlags).GetValue($form).PerformClick()
    if (-not $type.GetField('running',$instanceFlags).GetValue($form)) {
        # A transparent test window cannot receive focus; invoke the same click action.
        $type.GetMethod('BeginInstall',$instanceFlags).Invoke($form,@())
    }
    $worker=$type.GetField('worker',$instanceFlags).GetValue($form)
    $deadline=[Diagnostics.Stopwatch]::StartNew()
    while ($type.GetField('running',$instanceFlags).GetValue($form) -and $deadline.ElapsedMilliseconds -lt 75000) {
        [Windows.Forms.Application]::DoEvents()
        [Threading.Thread]::Sleep(15)
    }
    [Windows.Forms.Application]::DoEvents()
    if ($type.GetField('running',$instanceFlags).GetValue($form)) { throw 'GUI installation timed out.' }
    if (-not $type.GetField('completed',$instanceFlags).GetValue($form)) { throw ('GUI installation did not complete. ' + (@($type.GetField('log',$instanceFlags).GetValue($form).Items) -join ' ')) }
    if ($percentage.Text -ne '100%') { throw 'Completion percentage is incorrect.' }
    if ($type.GetField('bytes',$instanceFlags).GetValue($form) -ne $type.GetField('totalBytes',$instanceFlags).GetValue($form)) { throw 'Byte progress does not match the payload.' }
    if ([IO.File]::ReadAllText((Join-Path $profile 'utilityclient.json')) -ne 'preserved configuration') { throw 'Existing config changed.' }
    if (-not $launchButton.Enabled) { throw 'Launch button remains disabled after installation.' }
    Save-Preview 'setup-complete-preview.png'
    Write-Output 'PASS: actual GUI installation, byte/file progress, preserved config, launch enabled, initial/minimum-size layout'
    Write-Output "Isolated GUI installation: $work"
} finally { $form.Close(); $form.Dispose(); $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
