# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Lay chung chi Let's Encrypt tu PVC tb-acme-webroot (do job tb-certbot ghi vao) va cap nhat
# secret tb-haproxy-tls, roi restart HAProxy de no dung chung chi moi.
#
# Dung sau moi lan chay job tb-certbot (cap moi hoac gia han):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\use-letsencrypt-cert.ps1

param(
    [string]$Kubeconfig = "C:\Users\vthea\AppData\Roaming\Lens\kubeconfigs\f2e08611-80c2-43e2-bda8-06ffce6759b4-pasted-kubeconfig.yaml",
    [string]$Kubectl = "C:\Users\vthea\AppData\Local\Programs\Lens\resources\x64\kubectl.exe",
    [string]$Namespace = "thingsboard",
    [string]$Domain = "app.greeniq.vn"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Kubectl)) { $Kubectl = "kubectl" }
if (-not (Test-Path $Kubeconfig)) { throw "Khong tim thay kubeconfig: $Kubeconfig" }
$env:KUBECONFIG = $Kubeconfig

$pod = "tb-cert-export"
Write-Host "==> doc chung chi tu PVC tb-acme-webroot"
& $Kubectl -n $Namespace delete pod $pod --ignore-not-found | Out-Null

$manifest = @"
apiVersion: v1
kind: Pod
metadata:
  name: $pod
  namespace: $Namespace
spec:
  restartPolicy: Never
  containers:
    - name: export
      image: alpine:3.20
      command: ["/bin/sh","-c"]
      args:
        - |
          cat /webroot/le/live/$Domain/fullchain.pem /webroot/le/live/$Domain/privkey.pem | base64 -w0
      volumeMounts:
        - name: webroot
          mountPath: /webroot
  volumes:
    - name: webroot
      persistentVolumeClaim:
        claimName: tb-acme-webroot
"@
$tmp = Join-Path $env:TEMP "tb-cert-export.yaml"
[System.IO.File]::WriteAllText($tmp, $manifest, [System.Text.UTF8Encoding]::new($false))
& $Kubectl apply -f $tmp | Out-Null
& $Kubectl -n $Namespace wait --for=condition=Ready pod/$pod --timeout=120s | Out-Null

$b64 = (& $Kubectl -n $Namespace logs $pod) | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($b64)) { throw "Khong doc duoc chung chi (PVC da co file chua?)" }
$bytes = [Convert]::FromBase64String($b64)

$dir = Join-Path $env:TEMP "tb-tls"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$pem = Join-Path $dir "tls.pem"
[System.IO.File]::WriteAllBytes($pem, $bytes)
Write-Host "==> tls.pem: $($bytes.Length) bytes"

Push-Location $dir
& $Kubectl -n $Namespace create secret generic tb-haproxy-tls --from-file=tls.pem --dry-run=client -o yaml |
    & $Kubectl apply -f - | Out-Host
Pop-Location

& $Kubectl -n $Namespace delete pod $pod --ignore-not-found | Out-Null

Write-Host "==> restart HAProxy de nap chung chi"
& $Kubectl -n $Namespace rollout restart deploy/tb-haproxy | Out-Host
& $Kubectl -n $Namespace rollout status deploy/tb-haproxy --timeout=180s | Out-Host
Write-Host "Xong. Kiem tra: https://$Domain"
