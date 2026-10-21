param([int]$Port = 18080)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$jar = 'target/nified-permission-system-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw '请先使用 JDK 21 运行 mvn clean verify（首次先安装 CloneFactory）。' }
$username = Read-Host '首次管理员用户名'
$securePassword = Read-Host '首次管理员密码（至少8字符，最多72字节）' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:PERMISSION_BOOTSTRAP_USERNAME = $username
    $env:PERMISSION_BOOTSTRAP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    Write-Host "启动隔离 H2 演示：http://127.0.0.1:$Port 。Ctrl+C 停止后数据清空。"
    & $java -jar $jar --spring.profiles.active=demo "--server.port=$Port"
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Item Env:PERMISSION_BOOTSTRAP_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:PERMISSION_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
}
