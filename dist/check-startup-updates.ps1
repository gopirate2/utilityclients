param([Parameter(Mandatory=$true)][string]$Setup,[Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @'
using System;
using System.Linq.Expressions;
using System.Net;
public static class StartupUpdateFixture {
    public static object Value;
    public static int Calls;
    public static bool Fail;
    public static object Next() { Calls++; if(Fail)throw new WebException("fixture offline"); return Value; }
    public static Delegate Create(Type type) {
        return Expression.Lambda(type,Expression.Convert(Expression.Call(typeof(StartupUpdateFixture).GetMethod("Next")),type.GetMethod("Invoke").ReturnType)).Compile();
    }
}
'@
$assembly=[Reflection.Assembly]::LoadFile([IO.Path]::GetFullPath($Setup))
$type=$assembly.GetType('Bootstrapper')
$flags=[Reflection.BindingFlags]'Static,NonPublic'
$instance=[Reflection.BindingFlags]'Instance,NonPublic'
$type.GetField('Root',$flags).SetValue($null,(Join-Path $Root 'startup-profile'))
$type.GetField('AppRoot',$flags).SetValue($null,(Join-Path $Root 'startup-app'))
$field=$type.GetField('DiscoverUpdate',$flags)
$original=$field.GetValue($null)
$release=[Activator]::CreateInstance($assembly.GetType('Bootstrapper+UpdateRelease'),$true)
foreach($entry in @{Label='Fixture release';Url='https://github.com/example/update.zip';Published='2026-11-21';Digest=('a'*64)}.GetEnumerator()) {
    $release.GetType().GetField($entry.Key,$instance).SetValue($release,$entry.Value)
}
try {
    $field.SetValue($null,[StartupUpdateFixture]::Create($field.FieldType))
    foreach($case in @('current','new','offline','network-failure')) {
        [StartupUpdateFixture]::Value=$release
        [StartupUpdateFixture]::Calls=0
        [StartupUpdateFixture]::Fail=$case -eq 'network-failure'
        $release.GetType().GetField('Installed',$instance).SetValue($release,($case -eq 'current'))
        $type.GetField('AutoUpdates',$flags).SetValue($null,($case -ne 'offline'))
        $form=[Activator]::CreateInstance($assembly.GetType('SetupForm'),$true)
        try {
            $form.ShowInTaskbar=$false;$form.Opacity=0
            $form.Show()
            $timer=[Diagnostics.Stopwatch]::StartNew()
            do {
                [Windows.Forms.Application]::DoEvents()
                Start-Sleep -Milliseconds 10
                $running=$form.GetType().GetField('running',$instance).GetValue($form)
            } while(($running -or $timer.ElapsedMilliseconds -lt 250) -and $timer.ElapsedMilliseconds -lt 8000)
            if($running){throw 'Startup check did not complete'}
            $expectedCalls=if($case -eq 'offline'){0}else{1}
            if([StartupUpdateFixture]::Calls -ne $expectedCalls){throw "Startup check count for $case"}
            $heading=$form.GetType().GetField('heading',$instance).GetValue($form).Text
            if($case -eq 'current' -and $heading -ne "YOU'RE UP TO DATE"){throw 'Current release was offered again'}
            if($case -eq 'new' -and $heading -ne 'UPDATE AVAILABLE'){throw 'New release missing from startup result'}
            if($case -eq 'network-failure' -and $heading -ne 'OFFLINE PACKAGE READY'){throw 'Network failure did not preserve offline use'}
            if(Test-Path -LiteralPath (Join-Path $Root 'startup-profile')){throw 'Startup check installed files without selection'}
        } finally {$form.Dispose()}
    }
    Write-Output 'PASS: setup opening checks once, identifies current/new releases, respects offline selection, preserves offline use on failure and does not install'
} finally {$field.SetValue($null,$original)}
