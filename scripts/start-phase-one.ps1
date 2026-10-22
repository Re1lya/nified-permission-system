param([int]$Port=18081)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if(!$env:JAVA_HOME){throw '请先设置 JAVA_HOME 到 JDK 21。'}
if(!(Test-Path 'src/main/resources/static/ui/index.html')){throw '请先在 frontend 运行 npm ci 和 npm run build。'}
if(!(Test-Path 'target/nified-permission-system-0.0.1-SNAPSHOT.jar')){throw '请先运行 mvn clean verify。'}
$env:PERMISSION_BOOTSTRAP_USERNAME=Read-Host '首次管理员用户名'
$secure=Read-Host '密码至少8位且包含大小写、数字、符号中至少三类' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try{
 $env:PERMISSION_BOOTSTRAP_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
 Write-Host "一期界面：http://127.0.0.1:$Port/ui/index.html。H2 内存数据在停止后清空。"
 Write-Host 'OA/HR 默认关闭；需要演示时在启动前配置 PERMISSION_HR_SECRET 和 PERMISSION_OA_SECRET。'
 & "$env:JAVA_HOME/bin/java.exe" -jar target/nified-permission-system-0.0.1-SNAPSHOT.jar --spring.profiles.active=phase-one "--server.port=$Port"
}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);Remove-Item Env:PERMISSION_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue}
