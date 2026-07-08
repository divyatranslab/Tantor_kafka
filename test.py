import urllib.request, json
req = urllib.request.Request('http://localhost:8443/api/v1/ui/external-clusters/bootstrap/test', data=json.dumps({'bootstrapServers':'192.168.3.222:9093', 'securityProtocol':'SASL_SSL', 'saslMechanism':'SCRAM-SHA-512', 'saslUsername':'admin', 'saslPassword':'password', 'truststoreType':'PKCS12', 'disableHostnameVerification': False}).encode('utf-8'), headers={'Content-Type': 'application/json'}, method='POST')
try:
  res = urllib.request.urlopen(req)
  print(res.read().decode('utf-8'))
except Exception as e:
  print(e.read().decode('utf-8'))
