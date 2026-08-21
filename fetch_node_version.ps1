$ErrorActionPreference = 'Stop'
$tokenUrl = 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/node:pull'
$token = (Invoke-RestMethod -Uri $tokenUrl).token
$headers = @{ Authorization = "Bearer $token" }
$manifestUrl = 'https://registry-1.docker.io/v2/library/node/manifests/sha256:0557ac14e0d45d02ed563067b82856ca5e7aa3437fa28d98d4350ea9c3d9494a'
$manifest = Invoke-RestMethod -Uri $manifestUrl -Headers @{ 
    Authorization = "Bearer $token"; 
    Accept = 'application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json' 
}
if ($manifest.manifests) {
    $digest = $manifest.manifests[0].digest
    $manifestUrl = "https://registry-1.docker.io/v2/library/node/manifests/$digest"
    $manifest = Invoke-RestMethod -Uri $manifestUrl -Headers @{ 
        Authorization = "Bearer $token"; 
        Accept = 'application/vnd.docker.distribution.manifest.v2+json' 
    }
}
$configDigest = $manifest.config.digest
$configUrl = "https://registry-1.docker.io/v2/library/node/blobs/$configDigest"
$config = Invoke-RestMethod -Uri $configUrl -Headers @{ 
    Authorization = "Bearer $token";
    Accept = 'application/octet-stream'
}
$config.config.Env | Where-Object { $_ -match 'NODE_VERSION' -or $_ -match 'YARN_VERSION' -or $_ -match 'NPM' }
