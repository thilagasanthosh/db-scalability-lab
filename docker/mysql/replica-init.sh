#!/bin/bash
# Runs inside the REPLICA container at first start.
# Waits for the primary to be ready, then sets up replication.
set -e

echo "Waiting for primary to be ready..."
until mysql -h mysql-primary -uroot -proot -e "SELECT 1" 2>/dev/null; do
  sleep 2
done

echo "Primary is up. Creating replica user and starting replication..."

mysql -h mysql-primary -uroot -proot <<-SQL
  CREATE USER IF NOT EXISTS 'replicator'@'%' IDENTIFIED WITH mysql_native_password BY 'replicator_pass';
  GRANT REPLICATION SLAVE ON *.* TO 'replicator'@'%';
  FLUSH PRIVILEGES;
SQL

# Get binlog position from primary
BINLOG_FILE=$(mysql -h mysql-primary -uroot -proot -e "SHOW MASTER STATUS\G" | grep File | awk '{print $2}')
BINLOG_POS=$(mysql  -h mysql-primary -uroot -proot -e "SHOW MASTER STATUS\G" | grep Position | awk '{print $2}')

echo "Binlog: $BINLOG_FILE @ $BINLOG_POS"

mysql -uroot -proot <<-SQL
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='mysql-primary',
    SOURCE_USER='replicator',
    SOURCE_PASSWORD='replicator_pass',
    SOURCE_LOG_FILE='$BINLOG_FILE',
    SOURCE_LOG_POS=$BINLOG_POS;
  START REPLICA;
SQL

echo "Replication started."

# Create a dedicated replica read user for the Spring app
mysql -uroot -proot <<-SQL
  CREATE USER IF NOT EXISTS 'replica_user'@'%' IDENTIFIED BY 'replica_pass';
  GRANT SELECT ON scalabilitylab.* TO 'replica_user'@'%';
  FLUSH PRIVILEGES;
SQL

echo "Replica user created."
