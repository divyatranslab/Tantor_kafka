$properties = @"
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="password";
ssl.truststore.location=truststore_6.p12
ssl.truststore.password=password
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=
"@

Set-Content -Path command-config.properties -Value $properties
Write-Host "Created command-config.properties"

Write-Host "Running kafka-topics.bat..."
# Assuming kafka-topics.bat is in C:\srv\kafka\bin\windows\
C:\srv\kafka\bin\windows\kafka-topics.bat --bootstrap-server 192.168.3.222:9093 --command-config command-config.properties --list
