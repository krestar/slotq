$ErrorActionPreference = 'Stop'
$container = 'slotq-kafka-secure-kafka-1'
$server = 'kafka-secure:19092' # Compose-private admin bootstrap; never published on the host.
& docker exec $container /opt/kafka/bin/kafka-topics.sh --bootstrap-server $server `
    --create --if-not-exists --topic slotq.waitlist.events.v1 --partitions 3 --replication-factor 1
if ($LASTEXITCODE -ne 0) { throw 'secure topic creation failed' }
& docker exec $container /opt/kafka/bin/kafka-acls.sh --bootstrap-server $server `
    --add --allow-principal User:relay --operation WRITE --operation DESCRIBE --topic slotq.waitlist.events.v1
if ($LASTEXITCODE -ne 0) { throw 'relay ACL failed' }
& docker exec $container /opt/kafka/bin/kafka-acls.sh --bootstrap-server $server `
    --add --allow-principal User:waitlist --operation READ --operation DESCRIBE --topic slotq.waitlist.events.v1
if ($LASTEXITCODE -ne 0) { throw 'waitlist topic ACL failed' }
& docker exec $container /opt/kafka/bin/kafka-acls.sh --bootstrap-server $server `
    --add --allow-principal User:waitlist --operation READ --group slotq.waitlist.promotion
if ($LASTEXITCODE -ne 0) { throw 'waitlist group ACL failed' }
& docker exec $container /opt/kafka/bin/kafka-acls.sh --bootstrap-server $server `
    --add --allow-principal User:monitor --operation DESCRIBE --topic slotq.waitlist.events.v1
if ($LASTEXITCODE -ne 0) { throw 'monitor ACL failed' }
